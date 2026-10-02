package com.jorjik.dynamicfood.core;

import java.util.List;

public final class RecipeDifficultyResolver {
    private RecipeDifficultyResolver() {}

    public static double estimate(List<IngredientContribution> ingredients) {
        List<IngredientContribution> components = ingredients.stream()
            .filter(IngredientContribution::foodComponent)
            .filter(value -> value.count() > 0)
            .toList();
        if (components.isEmpty()) {
            return 0.0D;
        }

        double totalCount = components.stream().mapToDouble(IngredientContribution::count).sum();
        double meanDifficulty = components.stream()
            .mapToDouble(value -> value.difficulty() * value.count())
            .sum() / totalCount;
        double maximumDifficulty = components.stream().mapToDouble(IngredientContribution::difficulty).max().orElse(0.0D);
        long distinctComponents = components.stream().map(IngredientContribution::itemId).distinct().count();
        double countFactor = Math.max(0.0D, Math.min(5.0D, totalCount) - 1.0D) * 0.1D;
        double varietyFactor = Math.max(0.0D, Math.min(5.0D, distinctComponents) - 1.0D) * 0.35D;
        double decayedChain = meanDifficulty * 0.65D + maximumDifficulty * 0.25D;
        return Math.min(5.0D, decayedChain + countFactor + varietyFactor);
    }
}
