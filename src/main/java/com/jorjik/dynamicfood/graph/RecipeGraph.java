package com.jorjik.dynamicfood.graph;

import com.jorjik.dynamicfood.core.IngredientContribution;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RecipeGraph {
    private final Map<String, List<RecipeNode>> recipesByResult = new HashMap<>();
    private volatile CycleIndex cycleIndex;

    public synchronized void add(RecipeNode recipe) {
        List<RecipeNode> recipes = new ArrayList<>(recipesByResult.getOrDefault(recipe.resultId(), List.of()));
        recipes.add(recipe);
        recipes.sort(java.util.Comparator.comparing(RecipeNode::recipeId));
        recipesByResult.put(recipe.resultId(), List.copyOf(recipes));
        cycleIndex = null;
    }

    public Optional<RecipeNode> deterministicRecipeFor(String resultId) {
        List<RecipeNode> recipes = recipesByResult.getOrDefault(resultId, List.of());
        return recipes.isEmpty() ? Optional.empty() : Optional.of(recipes.getFirst());
    }

    public Optional<RecipeNode> recipeFor(String resultId, String recipeId) {
        if (recipeId == null || recipeId.isBlank()) {
            return deterministicRecipeFor(resultId);
        }
        return recipesByResult.getOrDefault(resultId, List.of()).stream()
            .filter(recipe -> recipe.recipeId().equals(recipeId))
            .findFirst()
            .or(() -> deterministicRecipeFor(resultId));
    }

    public List<RecipeNode> recipesFor(String resultId) {
        return recipesByResult.getOrDefault(resultId, List.of());
    }

    public java.util.Set<String> resultItemIds() {
        return java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(recipesByResult.keySet()));
    }

    public boolean hasAmbiguousRecipes(String resultId) {
        return recipesByResult.getOrDefault(resultId, List.of()).size() > 1;
    }

    public List<String> describeTree(String resultId) {
        List<String> lines = new ArrayList<>();
        describeTree(resultId, 0, new HashSet<>(), lines);
        return List.copyOf(lines);
    }

    private void describeTree(String itemId, int depth, Set<String> path, List<String> lines) {
        String prefix = "  ".repeat(Math.max(0, depth));
        if (!path.add(itemId)) {
            lines.add(prefix + itemId + " [cycle]");
            return;
        }
        RecipeNode recipe = deterministicRecipeFor(itemId).orElse(null);
        if (recipe == null) {
            lines.add(prefix + itemId + " [terminal]");
            path.remove(itemId);
            return;
        }
        lines.add(prefix + itemId + " <- " + recipe.recipeId() + " [" + recipe.recipeType()
            + ", output x" + recipe.outputCount() + "]");
        for (IngredientContribution ingredient : recipe.ingredients()) {
            lines.add(prefix + "  input " + ingredient.itemId() + " x" + ingredient.count()
                + " [nutrition=" + ingredient.nutrition() + ", effective_saturation="
                + ingredient.saturation() + ", food_component=" + ingredient.foodComponent() + "]");
            if (ingredient.foodComponent()) {
                describeTree(ingredient.itemId(), depth + 2, path, lines);
            }
        }
        path.remove(itemId);
    }

    public List<String> cycleFrom(String startItemId) {
        return visit(startItemId, new HashSet<>(), new HashSet<>(), new ArrayList<>());
    }

    public List<String> valueIncreasingEconomicCycles() {
        return cycleIndex().cycles();
    }

    public List<String> valueIncreasingEconomicCyclesFor(String itemId) {
        return cycleIndex().cyclesByItem().getOrDefault(itemId, List.of());
    }

    private CycleIndex cycleIndex() {
        CycleIndex cached = cycleIndex;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = cycleIndex;
            if (cached == null) {
                cached = buildCycleIndex();
                cycleIndex = cached;
            }
        }
        return cached;
    }

    private CycleIndex buildCycleIndex() {
        List<String> cycles = new ArrayList<>();
        for (String start : recipesByResult.keySet().stream().sorted().toList()) {
            findIncreasingCycles(start, start, new HashSet<>(), new ArrayList<>(), new ArrayList<>(),
                0.0D, cycles);
        }
        List<String> sortedCycles = cycles.stream().distinct().sorted().toList();
        Map<String, List<String>> cyclesByItem = new HashMap<>();
        for (String cycle : sortedCycles) {
            int detailsStart = cycle.indexOf(" (output ");
            String path = detailsStart < 0 ? cycle : cycle.substring(0, detailsStart);
            for (String itemId : path.split(" -> ")) {
                cyclesByItem.computeIfAbsent(itemId, ignored -> new ArrayList<>()).add(cycle);
            }
        }
        cyclesByItem.replaceAll((itemId, itemCycles) -> List.copyOf(itemCycles));
        return new CycleIndex(sortedCycles, Map.copyOf(cyclesByItem));
    }

    private void findIncreasingCycles(String start, String current, Set<String> visiting,
        List<String> path, List<CycleEdge> edges, double yieldMultiplier, List<String> cycles) {
        if (!visiting.add(current)) {
            return;
        }
        path.add(current);
        for (RecipeNode recipe : recipesByResult.getOrDefault(current, List.of())) {
            for (CycleEdge edge : cycleEdges(recipe)) {
                String next = edge.nextItemId();
                double nextYieldMultiplier = yieldMultiplier + edge.logYield();
                if (next.equals(start)) {
                    List<CycleEdge> cycleEdges = new ArrayList<>(edges);
                    cycleEdges.add(edge);
                    if (nextYieldMultiplier > 1.0E-12D) {
                        List<String> cycle = new ArrayList<>(path);
                        cycle.add(next);
                        CycleEdge increasingEdge = cycleEdges.stream()
                            .filter(CycleEdge::individuallyIncreasing)
                            .findFirst()
                            .orElseThrow();
                        cycles.add(String.join(" -> ", cycle) + " (output " + increasingEdge.outputCount()
                            + " > consumed " + increasingEdge.consumedCount() + "; cycle gain "
                            + Math.exp(nextYieldMultiplier) + ")");
                    }
                } else if (!visiting.contains(next) && recipesByResult.containsKey(next)) {
                    List<CycleEdge> nextEdges = new ArrayList<>(edges);
                    nextEdges.add(edge);
                    findIncreasingCycles(start, next, visiting, path, nextEdges, nextYieldMultiplier, cycles);
                }
            }
        }
        path.removeLast();
        visiting.remove(current);
    }

    private static List<CycleEdge> cycleEdges(RecipeNode recipe) {
        Map<String, Long> consumedByItem = new HashMap<>();
        for (AcquisitionIngredient ingredient : recipe.acquisitionIngredients()) {
            if (ingredient.alternatives().size() == 1) {
                consumedByItem.merge(ingredient.alternatives().getFirst(), (long) ingredient.count(),
                    Math::addExact);
            }
        }
        return consumedByItem.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(entry -> new CycleEdge(entry.getKey(), recipe.outputCount(), entry.getValue()))
            .toList();
    }

    private List<String> visit(String itemId, Set<String> visiting, Set<String> visited, List<String> path) {
        if (visiting.contains(itemId)) {
            path.add(itemId);
            return new ArrayList<>(path);
        }
        if (!visited.add(itemId)) {
            return List.of();
        }
        visiting.add(itemId);
        path.add(itemId);
        for (RecipeNode recipe : recipesByResult.getOrDefault(itemId, List.of())) {
            for (IngredientContribution ingredient : recipe.ingredients()) {
                List<String> cycle = visit(ingredient.itemId(), visiting, new HashSet<>(visited), new ArrayList<>(path));
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            }
        }
        visiting.remove(itemId);
        return List.of();
    }

    public synchronized void clear() {
        recipesByResult.clear();
        cycleIndex = null;
    }

    private record CycleIndex(List<String> cycles, Map<String, List<String>> cyclesByItem) {
    }

    private record CycleEdge(String nextItemId, int outputCount, long consumedCount) {
        private double logYield() {
            return Math.log(outputCount) - Math.log(consumedCount);
        }

        private boolean individuallyIncreasing() {
            return outputCount > consumedCount;
        }
    }
}
