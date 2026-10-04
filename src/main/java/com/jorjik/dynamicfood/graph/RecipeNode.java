package com.jorjik.dynamicfood.graph;

import com.jorjik.dynamicfood.core.IngredientContribution;
import java.util.List;
import java.util.Objects;

public record RecipeNode(
    String recipeId,
    String recipeType,
    String resultId,
    int outputCount,
    List<IngredientContribution> ingredients,
    Double processingTimeTicks,
    List<AcquisitionIngredient> acquisitionIngredients,
    boolean activeInRecipeManager
) {
    public RecipeNode(String recipeId, String recipeType, String resultId, int outputCount,
        List<IngredientContribution> ingredients) {
        this(recipeId, recipeType, resultId, outputCount, ingredients, null, false);
    }

    public RecipeNode(String recipeId, String recipeType, String resultId, int outputCount,
        List<IngredientContribution> ingredients, Double processingTimeTicks) {
        this(recipeId, recipeType, resultId, outputCount, ingredients, processingTimeTicks, false);
    }

    public RecipeNode(String recipeId, String recipeType, String resultId, int outputCount,
        List<IngredientContribution> ingredients, Double processingTimeTicks,
        List<AcquisitionIngredient> acquisitionIngredients) {
        this(recipeId, recipeType, resultId, outputCount, ingredients, processingTimeTicks,
            acquisitionIngredients, false);
    }

    public RecipeNode(String recipeId, String recipeType, String resultId, int outputCount,
        List<IngredientContribution> ingredients, Double processingTimeTicks, boolean activeInRecipeManager) {
        this(recipeId, recipeType, resultId, outputCount, ingredients, processingTimeTicks,
            ingredients.stream().filter(IngredientContribution::foodComponent)
                .map(input -> new AcquisitionIngredient(
                    input.itemId().equals("dynamicfood:ingredient_alternatives")
                        ? List.of() : List.of(input.itemId()), input.count()))
                .toList(), activeInRecipeManager);
    }

    public RecipeNode {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(resultId, "resultId");
        outputCount = Math.max(1, outputCount);
        if (processingTimeTicks != null
            && (!Double.isFinite(processingTimeTicks) || processingTimeTicks < 0.0D)) {
            throw new IllegalArgumentException("processingTimeTicks must be finite and non-negative when known");
        }
        ingredients = List.copyOf(ingredients);
        acquisitionIngredients = List.copyOf(acquisitionIngredients);
    }
}
