package com.jorjik.dynamicfood.core;

import java.util.Objects;

public record IngredientContribution(
    String itemId,
    double nutrition,
    double saturation,
    int count,
    boolean foodComponent,
    String sourceRecipe,
    double difficulty
) {
    public IngredientContribution(String itemId, double nutrition, double saturation, int count,
        boolean foodComponent, String sourceRecipe) {
        this(itemId, nutrition, saturation, count, foodComponent, sourceRecipe, 0.0D);
    }

    public IngredientContribution {
        Objects.requireNonNull(itemId, "itemId");
        sourceRecipe = sourceRecipe == null ? "unknown" : sourceRecipe;
        count = Math.max(0, count);
        nutrition = Double.isFinite(nutrition) ? Math.max(0.0D, nutrition) : 0.0D;
        saturation = Double.isFinite(saturation) ? Math.max(0.0D, saturation) : 0.0D;
        difficulty = Double.isFinite(difficulty) ? Math.max(0.0D, Math.min(5.0D, difficulty)) : 0.0D;
    }

    public static IngredientContribution of(String itemId, double nutrition, double saturation, int count, boolean foodComponent) {
        return new IngredientContribution(itemId, nutrition, saturation, count, foodComponent, "unknown", 0.0D);
    }
}
