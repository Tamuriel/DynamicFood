package com.jorjik.dynamicfood.core;

import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import com.jorjik.dynamicfood.graph.RecipeGraph;
import com.jorjik.dynamicfood.graph.RecipeNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RecipeEconomicAnalyzer {
    private final RecipeGraph graph;
    private final EconomicCostEvidenceProvider independentEvidence;

    public RecipeEconomicAnalyzer(RecipeGraph graph, EconomicCostEvidenceProvider independentEvidence) {
        this.graph = graph;
        this.independentEvidence = java.util.Objects.requireNonNull(independentEvidence, "independentEvidence");
    }

    public synchronized RecipeEconomicResult resolve(String itemId, int horizon) {
        requireHorizon(horizon);
        return resolve(itemId, horizon, new ArrayList<>(), new HashSet<>(), new java.util.HashMap<>());
    }

    public synchronized RecipeEconomicResult resolveUsingRecipe(RecipeNode recipe, int horizon) {
        requireHorizon(horizon);
        if (recipe == null) {
            return unknown("unknown", List.of(), List.of(), List.of("recipe is unavailable"));
        }
        return resolveRecipe(recipe, horizon, new ArrayList<>(List.of(recipe.recipeId())),
            new HashSet<>(Set.of(recipe.resultId())), new java.util.HashMap<>());
    }

    private RecipeEconomicResult resolve(String itemId, int horizon, List<String> path,
        Set<String> visiting, Map<String, RecipeEconomicResult> memo) {
        EconomicCost evidenced = independentEvidence.resolve(itemId, horizon);
        if (evidenced == null) {
            evidenced = EconomicCost.unknown("economic evidence provider returned no result for " + itemId);
        }
        if (evidenced != null && evidenced.isKnown() && evidenced.observationHorizon() == horizon) {
            return new RecipeEconomicResult(itemId, evidenced, ResolutionStatus.COMPLETE,
                List.of("independent primitive: " + itemId), List.of(), List.of());
        }
        RecipeEconomicResult cached = memo.get(itemId);
        if (cached != null && !visiting.contains(itemId)) {
            return cached;
        }
        if (!visiting.add(itemId)) {
            ArrayList<String> cycle = new ArrayList<>(path);
            cycle.add(itemId);
            return unknown(itemId, List.of(), List.of(String.join(" -> ", cycle)), List.of());
        }
        path.add(itemId);

        List<RecipeEconomicResult> viable = new ArrayList<>();
        List<String> cycles = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> attemptedPaths = new ArrayList<>();
        for (RecipeNode recipe : graph.recipesFor(itemId)) {
            RecipeEconomicResult result = resolveRecipe(recipe, horizon, path, visiting, memo);
            cycles.addAll(result.detectedCycles());
            missing.addAll(result.missingInputs());
            attemptedPaths.addAll(result.recipePath());
            if (result.target().isKnown()) {
                viable.add(result);
            }
        }

        path.removeLast();
        visiting.remove(itemId);
        RecipeEconomicResult result;
        if (viable.isEmpty()) {
            String evidenceReason = evidenced != null && evidenced.isKnown()
                ? "independent primitive is evidenced at horizon " + evidenced.observationHorizon()
                    + ", not requested horizon " + horizon
                : "no independently evidenced compatible economic primitive reaches this resource";
            missing.add(evidenceReason);
            result = unknown(itemId, attemptedPaths.stream().distinct().toList(),
                cycles.stream().distinct().sorted().toList(),
                missing.stream().distinct().sorted().toList());
        } else {
            String primitive = viable.getFirst().target().primitiveId();
            if (viable.stream().anyMatch(candidate ->
                !primitive.equals(candidate.target().primitiveId()))) {
                result = unknown(itemId, List.of(), cycles.stream().distinct().sorted().toList(),
                    List.of("recipe paths use incompatible economic primitives"));
            } else {
                viable.sort(java.util.Comparator
                    .comparingDouble((RecipeEconomicResult candidate) -> candidate.target().value())
                    .thenComparing(candidate -> String.join("|", candidate.recipePath())));
                RecipeEconomicResult selected = viable.getFirst();
                List<String> distinctMissing = missing.stream().distinct().sorted().toList();
                List<String> distinctCycles = cycles.stream().distinct().sorted().toList();
                result = distinctMissing.isEmpty() && distinctCycles.isEmpty()
                    && selected.status() == ResolutionStatus.COMPLETE
                    ? selected
                    : partial(selected, distinctCycles, distinctMissing);
            }
        }
        // A finalized UNKNOWN may include a back-edge; cache it to avoid re-expanding shared cyclic branches.
        memo.put(itemId, result);
        return result;
    }

    private RecipeEconomicResult resolveRecipe(RecipeNode recipe, int horizon, List<String> path,
        Set<String> visiting, Map<String, RecipeEconomicResult> memo) {
        List<AcquisitionIngredient> ingredients = recipe.acquisitionIngredients();
        if (ingredients.isEmpty()) {
            return unknown(recipe.resultId(), List.of(recipe.recipeId()), List.of(),
                List.of(recipe.recipeId() + ": recipe exposes no resolved item inputs"));
        }
        double rawMaterialCost = 0.0D;
        EconomicCost basis = null;
        List<String> recipePath = new ArrayList<>(List.of(recipe.recipeId()));
        List<String> cycles = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        boolean partial = false;
        for (AcquisitionIngredient ingredient : ingredients) {
            if (ingredient.inputUse() == AcquisitionIngredient.InputUse.REUSABLE) {
                recipePath.add("reusable input " + ingredient.alternatives());
                continue;
            }
            if (ingredient.inputUse() == AcquisitionIngredient.InputUse.UNKNOWN) {
                missing.add("input use is unknown for " + ingredient.alternatives());
                continue;
            }
            RecipeEconomicResult child = resolveIngredient(ingredient.alternatives(), horizon,
                path, visiting, memo);
            cycles.addAll(child.detectedCycles());
            if (!child.target().isKnown()) {
                missing.addAll(child.missingInputs());
                missing.add("unknown input " + ingredient.alternatives());
                continue;
            }
            diagnostics.addAll(child.missingInputs());
            partial |= child.status() == ResolutionStatus.PARTIAL || !child.detectedCycles().isEmpty();
            EconomicCost childCost = child.target();
            if (childCost.observationHorizon() != horizon) {
                missing.add("input " + ingredient.alternatives() + " is measured at an incompatible horizon");
                continue;
            }
            if (basis == null) {
                basis = childCost;
            } else if (!basis.hasCompatibleBasis(childCost)) {
                missing.add("recipe inputs use incompatible economic primitives or horizons");
                continue;
            }
            rawMaterialCost += childCost.value() * ingredient.count();
            recipePath.addAll(child.recipePath());
        }
        if (!missing.isEmpty()) {
            diagnostics.addAll(missing);
            return unknown(recipe.resultId(), recipePath, cycles.stream().distinct().sorted().toList(),
                diagnostics.stream().distinct().sorted().toList());
        }
        if (basis == null || !Double.isFinite(rawMaterialCost)) {
            return unknown(recipe.resultId(), recipePath, cycles.stream().distinct().sorted().toList(),
                diagnostics.isEmpty() ? List.of("recipe material cost is not representable") : diagnostics);
        }
        double perOutputCost = rawMaterialCost / recipe.outputCount();
        if (!Double.isFinite(perOutputCost) || perOutputCost < 0.0D || perOutputCost > 1.0D) {
            return unknown(recipe.resultId(), recipePath, cycles.stream().distinct().sorted().toList(),
                List.of("recursive material EconomicCost is outside the policy scale [0,1]"));
        }
        EconomicCost target = EconomicCost.known(perOutputCost, basis.primitiveId(), horizon,
            "recursive ingredient quantities divided by deterministic recipe output count");
        List<String> distinctCycles = cycles.stream().distinct().sorted().toList();
        List<String> distinctMissing = diagnostics.stream().distinct().sorted().toList();
        return new RecipeEconomicResult(recipe.resultId(), target,
            partial || !distinctCycles.isEmpty() || !distinctMissing.isEmpty()
                ? ResolutionStatus.PARTIAL : ResolutionStatus.COMPLETE,
            recipePath, distinctCycles, distinctMissing);
    }

    private RecipeEconomicResult resolveIngredient(List<String> alternatives, int horizon,
        List<String> path, Set<String> visiting, Map<String, RecipeEconomicResult> memo) {
        if (alternatives.isEmpty()) {
            return unknown("unknown", List.of(), List.of(), List.of("ingredient has no resolved item alternatives"));
        }
        List<RecipeEconomicResult> candidates = alternatives.stream().sorted()
            .map(itemId -> resolve(itemId, horizon, path, visiting, memo)).toList();
        List<String> cycles = candidates.stream().flatMap(candidate -> candidate.detectedCycles().stream())
            .distinct().sorted().toList();
        List<RecipeEconomicResult> known = candidates.stream()
            .filter(candidate -> candidate.target().isKnown()).toList();
        List<String> missing = new ArrayList<>(candidates.stream()
            .flatMap(candidate -> candidate.missingInputs().stream()).toList());
        candidates.stream().filter(candidate -> !candidate.target().isKnown())
            .map(candidate -> "unresolved alternative " + candidate.itemId())
            .forEach(missing::add);
        if (known.isEmpty()) {
            return unknown(candidates.getFirst().itemId(), List.of(), cycles,
                missing.stream().distinct().sorted().toList());
        }
        RecipeEconomicResult selected = known.getFirst();
        EconomicCost selectedCost = selected.target();
        for (RecipeEconomicResult candidate : known) {
            if (!selectedCost.hasCompatibleBasis(candidate.target())
                || Double.compare(selectedCost.value(), candidate.target().value()) != 0) {
                return unknown(selected.itemId(), List.of(), cycles,
                    List.of("ingredient alternatives have different evidenced economic costs"));
            }
        }
        return missing.isEmpty() && cycles.isEmpty()
            && known.stream().allMatch(candidate -> candidate.status() == ResolutionStatus.COMPLETE)
            ? selected : partial(selected, cycles, missing.stream().distinct().sorted().toList());
    }

    private static RecipeEconomicResult partial(RecipeEconomicResult resolved,
        List<String> cycles, List<String> missing) {
        return new RecipeEconomicResult(resolved.itemId(), resolved.target(), ResolutionStatus.PARTIAL,
            resolved.recipePath(), cycles, missing);
    }

    private static RecipeEconomicResult unknown(String itemId, List<String> path,
        List<String> cycles, List<String> missing) {
        return new RecipeEconomicResult(itemId, EconomicCost.unknown(
            "recipe path has no independently evidenced compatible economic primitive"),
            ResolutionStatus.UNKNOWN, path, cycles, missing);
    }

    private static void requireHorizon(int horizon) {
        if (horizon < 1) {
            throw new IllegalArgumentException("economic horizon must be positive");
        }
    }
}
