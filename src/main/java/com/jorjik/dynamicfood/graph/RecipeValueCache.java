package com.jorjik.dynamicfood.graph;

import com.jorjik.dynamicfood.core.FoodValue;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class RecipeValueCache {
    private final Map<String, FoodValue> valuesByRecipe = new ConcurrentHashMap<>();

    public Optional<FoodValue> get(String recipeId) {
        return Optional.ofNullable(valuesByRecipe.get(recipeId));
    }

    public void put(String recipeId, FoodValue value) {
        valuesByRecipe.put(recipeId, value);
    }

    public void clear() {
        valuesByRecipe.clear();
    }
}
