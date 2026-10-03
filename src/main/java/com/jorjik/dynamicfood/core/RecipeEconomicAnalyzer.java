package com.jorjik.dynamicfood.core;

import com.jorjik.dynamicfood.graph.RecipeGraph;
import com.jorjik.dynamicfood.graph.RecipeNode;
import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RecipeEconomicAnalyzer {
    private final RecipeGraph graph;
    private final Map<String, Double> terminalCosts;
    private final Map<String, RecipeEconomicResult> memo = new HashMap<>();

    public RecipeEconomicAnalyzer(RecipeGraph graph, Map<String, Double> terminalCosts,
        double materialReference, double materialCap) {
        if (!Double.isFinite(materialReference) || materialReference <= 0.0D
            || !Double.isFinite(materialCap) || materialCap <= 0.0D) {
            throw new IllegalArgumentException("material normalization reference and cap must be finite and positive");
        }
        this.graph = graph;
        this.terminalCosts = Map.copyOf(terminalCosts);
    }

    public synchronized RecipeEconomicResult resolve(String itemId) {
        return resolve(itemId, new ArrayList<>(), new HashSet<>());
    }

    public synchronized Optional<RecipeEconomicResult> resolveUsingRecipe(RecipeNode recipe) {
        if (recipe == null) {
            return Optional.empty();
        }
        if (recipe.acquisitionIngredients().isEmpty()) {
            return Optional.of(new RecipeEconomicResult(recipe.resultId(), null, ResolutionStatus.UNKNOWN,
                List.of(recipe.recipeId()), List.of(),
                List.of("recipe exposes no item inputs; fluid or other inputs may still be required")));
        }
        double rawMaterialCost = 0.0D;
        List<String> recipePath = new ArrayList<>();
        recipePath.add(recipe.recipeId());
        List<String> cycles = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean knownInputs = true;
        for (AcquisitionIngredient ingredient : recipe.acquisitionIngredients()) {
            RecipeEconomicResult child = resolveIngredient(ingredient.alternatives(), new ArrayList<>(),
                new HashSet<>(), recipe.recipeId());
            cycles.addAll(child.detectedCycles());
            missing.addAll(child.missingInputs());
            if (child.economicCost() == null) {
                knownInputs = false;
                missing.add("unknown input " + ingredient.alternatives());
            } else {
                rawMaterialCost += child.economicCost() * ingredient.count();
                recipePath.addAll(child.recipePath());
            }
        }
        if (!knownInputs) {
            return Optional.of(new RecipeEconomicResult(recipe.resultId(), null, ResolutionStatus.UNKNOWN,
                recipePath, cycles.stream().distinct().sorted().toList(),
                missing.stream().distinct().sorted().toList()));
        }
        double perOutputRawCost = rawMaterialCost / Math.max(1, recipe.outputCount());
        return Optional.of(new RecipeEconomicResult(recipe.resultId(), perOutputRawCost,
            cycles.isEmpty() ? ResolutionStatus.COMPLETE : ResolutionStatus.PARTIAL,
            recipePath, cycles.stream().distinct().sorted().toList(),
            missing.stream().distinct().sorted().toList()));
    }

    private RecipeEconomicResult resolve(String itemId, List<String> path, Set<String> visiting) {
        Double terminalCost = terminalCosts.get(itemId);
        if (terminalCost != null) {
            return new RecipeEconomicResult(itemId, terminalCost, ResolutionStatus.COMPLETE,
                List.of("terminal:" + itemId), List.of(), List.of());
        }
        RecipeEconomicResult cached = memo.get(itemId);
        if (cached != null && !visiting.contains(itemId)) {
            return cached;
        }
        if (!visiting.add(itemId)) {
            ArrayList<String> cycle = new ArrayList<>(path);
            cycle.add(itemId);
            return new RecipeEconomicResult(itemId, null, ResolutionStatus.UNKNOWN, List.of(),
                List.of(String.join(" -> ", cycle)), List.of());
        }
        path.add(itemId);

        List<RecipeEconomicResult> viable = new ArrayList<>();
        List<String> cycles = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (RecipeNode recipe : graph.recipesFor(itemId)) {
            if (recipe.acquisitionIngredients().isEmpty()) {
                missing.add(recipe.recipeId()
                    + ": recipe exposes no item inputs; fluid or other inputs may still be required");
                continue;
            }
            double rawMaterialCost = 0.0D;
            boolean knownInputs = true;
            List<String> recipePath = new ArrayList<>();
            recipePath.add(recipe.recipeId());
            for (AcquisitionIngredient ingredient : recipe.acquisitionIngredients()) {
                RecipeEconomicResult child = resolveIngredient(ingredient.alternatives(), path, visiting,
                    recipe.recipeId());
                if (child == null) {
                    missing.add(recipe.recipeId() + ": input alternatives have unknown or unequal economic costs "
                        + ingredient.alternatives());
                    knownInputs = false;
                    break;
                }
                cycles.addAll(child.detectedCycles());
                if (child.economicCost() == null) {
                    missing.add(recipe.recipeId() + ": unknown input " + ingredient.alternatives());
                    knownInputs = false;
                    break;
                }
                rawMaterialCost += child.economicCost() * ingredient.count();
                recipePath.addAll(child.recipePath());
            }
            if (!knownInputs) {
                continue;
            }
            double perOutputRawCost = rawMaterialCost / Math.max(1, recipe.outputCount());
            viable.add(new RecipeEconomicResult(itemId, perOutputRawCost, ResolutionStatus.COMPLETE,
                recipePath, cycles, List.of()));
        }

        path.removeLast();
        visiting.remove(itemId);
        RecipeEconomicResult result;
        if (viable.isEmpty()) {
            result = new RecipeEconomicResult(itemId, null, ResolutionStatus.UNKNOWN, List.of(),
                cycles.stream().distinct().sorted().toList(), missing.stream().distinct().sorted().toList());
        } else {
            viable.sort(java.util.Comparator.comparingDouble((RecipeEconomicResult candidate) -> candidate.economicCost())
                .thenComparing(candidate -> String.join("|", candidate.recipePath())));
            result = viable.getFirst();
        }
        if (result.detectedCycles().isEmpty()) {
            memo.put(itemId, result);
        }
        return result;
    }

    private RecipeEconomicResult resolveIngredient(List<String> alternatives, List<String> path,
        Set<String> visiting, String recipeId) {
        if (alternatives.isEmpty()) {
            return new RecipeEconomicResult("unknown", null, ResolutionStatus.UNKNOWN, List.of(),
                List.of(), List.of("ingredient has no resolved item alternatives"));
        }
        List<RecipeEconomicResult> candidates = alternatives.stream().sorted()
            .map(itemId -> resolve(itemId, path, visiting)).toList();
        RecipeEconomicResult selected = candidates.getFirst();
        List<String> cycles = candidates.stream().flatMap(candidate -> candidate.detectedCycles().stream())
            .distinct().sorted().toList();
        List<String> missing = candidates.stream().flatMap(candidate -> candidate.missingInputs().stream())
            .distinct().sorted().toList();
        if (candidates.stream().anyMatch(candidate -> candidate.economicCost() == null)) {
            return new RecipeEconomicResult(selected.itemId(), null, ResolutionStatus.UNKNOWN, List.of(),
                cycles, missing);
        }
        if (candidates.size() == 1) {
            return selected;
        }
        double cost = selected.economicCost();
        if (candidates.stream().anyMatch(candidate -> Double.compare(candidate.economicCost(), cost) != 0)) {
            return new RecipeEconomicResult(selected.itemId(), null, ResolutionStatus.UNKNOWN, List.of(),
                cycles, List.of("alternative inputs have unequal economic costs"));
        }
        List<String> route = new ArrayList<>(selected.recipePath());
        route.add(0, recipeId + ":equivalent-alternative-inputs");
        return new RecipeEconomicResult(selected.itemId(), selected.economicCost(), selected.status(),
            route, cycles, missing);
    }
}