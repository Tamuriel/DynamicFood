package com.jorjik.dynamicfood.core;

import java.util.List;

public final class FoodValueResolver {
    private FoodValueResolver() {}

    public static FoodValue compute(
        List<IngredientContribution> ingredients,
        int outputCount,
        double processingNutritionBudget,
        double processingSaturationBudget,
        double maxAutomaticNutrition,
        double maxAutomaticSaturation,
        SaturationOverflowMode overflowMode,
        String sourceRecipe
    ) {
        int safeOutputCount = Math.max(1, outputCount);
        double nutritionSum = ingredients.stream()
            .filter(IngredientContribution::foodComponent)
            .mapToDouble(value -> value.nutrition() * value.count())
            .sum();
        double saturationSum = ingredients.stream()
            .filter(IngredientContribution::foodComponent)
            .mapToDouble(value -> value.saturation() * value.count())
            .sum() / safeOutputCount;

        double nutritionLimit = finiteBudget(maxAutomaticNutrition);
        double inheritedNutrition = nutritionSum / safeOutputCount;
        double availableAutomaticNutrition = Math.max(0.0D, nutritionLimit - inheritedNutrition) * safeOutputCount;
        double nutritionBonus = Math.min(finiteBudget(processingNutritionBudget), availableAutomaticNutrition);
        double saturationBudget = finiteBudget(processingSaturationBudget) / safeOutputCount;
        double saturationLimit = finiteBudget(maxAutomaticSaturation);
        double inheritedSaturation = saturationSum;
        if (overflowMode == SaturationOverflowMode.NORMALIZE_COMPONENTS && inheritedSaturation > saturationLimit) {
            inheritedSaturation = saturationLimit;
        }
        double availableAutomaticSaturation = Math.max(0.0D, saturationLimit - inheritedSaturation);
        double saturationBonus = Math.min(saturationBudget, availableAutomaticSaturation);
        double rawNutrition = (nutritionSum + nutritionBonus) / safeOutputCount;
        double rawSaturation = inheritedSaturation + saturationBonus;
        double recipeDifficulty = RecipeDifficultyResolver.estimate(ingredients);

        return new FoodValue(
            rawNutrition,
            (int) Math.ceil(rawNutrition),
            rawSaturation,
            rawSaturation,
            recipeDifficulty,
            sourceRecipe,
            safeOutputCount,
            ingredients
        );
    }

    public static FoodValue compute(
        List<IngredientContribution> ingredients,
        int outputCount,
        double processingNutritionBudget,
        double processingSaturationBudget,
        String sourceRecipe
    ) {
        return compute(ingredients, outputCount, processingNutritionBudget, processingSaturationBudget,
            18.0D, 20.0D, SaturationOverflowMode.PRESERVE_COMPONENTS, sourceRecipe);
    }

    public static FoodValue compute(
        List<IngredientContribution> ingredients,
        int outputCount,
        double processingNutritionBudget,
        double processingSaturationBudget,
        double maxAutomaticSaturation,
        SaturationOverflowMode overflowMode,
        String sourceRecipe
    ) {
        return compute(ingredients, outputCount, processingNutritionBudget, processingSaturationBudget,
            18.0D, maxAutomaticSaturation, overflowMode, sourceRecipe);
    }

    private static double finiteBudget(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }
}
