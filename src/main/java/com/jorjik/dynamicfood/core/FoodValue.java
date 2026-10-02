package com.jorjik.dynamicfood.core;

import java.util.List;

public record FoodValue(
    double rawNutrition,
    int nutrition,
    double rawSaturation,
    double saturation,
    double difficulty,
    String sourceRecipe,
    int outputCount,
    List<IngredientContribution> components
) {
    public FoodValue {
        rawNutrition = finiteNonNegative(rawNutrition);
        nutrition = Math.max(0, nutrition);
        rawSaturation = finiteNonNegative(rawSaturation);
        saturation = finiteNonNegative(saturation);
        difficulty = Double.isFinite(difficulty) ? Math.max(0.0D, Math.min(5.0D, difficulty)) : 0.0D;
        sourceRecipe = sourceRecipe == null ? "unknown" : sourceRecipe;
        outputCount = Math.max(1, outputCount);
        components = List.copyOf(components);
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }
}
