package com.jorjik.dynamicfood.graph;

import com.jorjik.dynamicfood.adapter.RecipeAdapter;
import com.jorjik.dynamicfood.adapter.RecipeAdapterRegistry;
import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import com.jorjik.dynamicfood.core.FoodValue;
import com.jorjik.dynamicfood.core.FoodValueResolver;
import com.jorjik.dynamicfood.core.IngredientContribution;
import com.jorjik.dynamicfood.core.RecipeDifficultyResolver;
import com.jorjik.dynamicfood.core.SaturationOverflowMode;
import com.jorjik.dynamicfood.provenance.RuntimeProvenance;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class RecipeResolver {
    private final RecipeGraph graph;
    private final RecipeValueCache cache;
    private final double processingNutritionBudget;
    private final double processingSaturationBudget;
    private final double maxAutomaticNutrition;
    private final double maxAutomaticSaturation;
    private final SaturationOverflowMode overflowMode;
    private final RecipeAdapterRegistry adapters;

    public RecipeResolver(RecipeGraph graph, RecipeValueCache cache, double processingNutritionBudget, double processingSaturationBudget) {
        this(graph, cache, processingNutritionBudget, processingSaturationBudget,
            18.0D, 20.0D, SaturationOverflowMode.PRESERVE_COMPONENTS);
    }

    public RecipeResolver(RecipeGraph graph, RecipeValueCache cache, double processingNutritionBudget,
        double processingSaturationBudget, double maxAutomaticSaturation, SaturationOverflowMode overflowMode) {
        this(graph, cache, processingNutritionBudget, processingSaturationBudget,
            18.0D, maxAutomaticSaturation, overflowMode);
    }

    public RecipeResolver(RecipeGraph graph, RecipeValueCache cache, double processingNutritionBudget,
        double processingSaturationBudget, double maxAutomaticNutrition, double maxAutomaticSaturation,
        SaturationOverflowMode overflowMode) {
        this.graph = graph;
        this.cache = cache;
        this.processingNutritionBudget = Math.max(0.0D, processingNutritionBudget);
        this.processingSaturationBudget = Math.max(0.0D, processingSaturationBudget);
        this.maxAutomaticNutrition = Math.max(0.0D, maxAutomaticNutrition);
        this.maxAutomaticSaturation = Math.max(0.0D, maxAutomaticSaturation);
        this.overflowMode = overflowMode;
        this.adapters = null;
    }

    public RecipeResolver(RecipeGraph graph, RecipeValueCache cache, RecipeAdapterRegistry adapters) {
        this.graph = graph;
        this.cache = cache;
        this.processingNutritionBudget = 0.0D;
        this.processingSaturationBudget = 0.0D;
        this.maxAutomaticNutrition = DynamicFoodConfig.maxAutomaticNutrition();
        this.maxAutomaticSaturation = DynamicFoodConfig.maxAutomaticSaturation();
        this.overflowMode = SaturationOverflowMode.parse(
            DynamicFoodConfig.text(DynamicFoodConfig.SATURATION_OVERFLOW_MODE, "preserve_components"));
        this.adapters = adapters;
    }

    public FoodValue resolve(RuntimeProvenance provenance) {
        if (provenance.hasActualInputs()) {
            return resolveActualOperation(provenance);
        }
        return resolveStatic(provenance.resultItemId(), provenance.recipeId(), provenance.recipeId(), new HashSet<>());
    }

    public FoodValue resolveActualOperation(RuntimeProvenance provenance) {
        return calculateOperation(provenance.recipeId(), provenance.recipeType(),
            provenance.actualInputs(), provenance.outputCount(), provenance.stationDifficulty());
    }

    public FoodValue resolveStatic(String itemId) {
        return resolveStatic(itemId, "static-analysis", null, new HashSet<>());
    }

    private FoodValue resolveStatic(String itemId, String sourceId, Set<String> path) {
        return resolveStatic(itemId, sourceId, null, path);
    }

    private FoodValue resolveStatic(String itemId, String sourceId, String preferredRecipeId, Set<String> path) {
        Optional<RecipeNode> recipe = graph.recipeFor(itemId, preferredRecipeId);
        if (recipe.isEmpty()) {
            return FoodValueResolver.compute(List.of(), 1, 0.0D, 0.0D,
                maxAutomaticNutrition, maxAutomaticSaturation, overflowMode, sourceId);
        }
        RecipeNode node = recipe.get();
        Optional<FoodValue> cached = cache.get(node.recipeId());
        if (cached.isPresent()) {
            return cached.get();
        }
        if (!path.add(itemId)) {
            return FoodValueResolver.compute(List.of(), node.outputCount(), 0.0D, 0.0D,
                maxAutomaticNutrition, maxAutomaticSaturation, overflowMode, node.recipeId());
        }

        List<IngredientContribution> resolved = new ArrayList<>();
        for (IngredientContribution ingredient : node.ingredients()) {
            if (path.contains(ingredient.itemId()) || graph.deterministicRecipeFor(ingredient.itemId()).isEmpty()) {
                resolved.add(ingredient);
                continue;
            }
            FoodValue child = resolveStatic(ingredient.itemId(), node.recipeId(), null, new HashSet<>(path));
            resolved.add(new IngredientContribution(
                ingredient.itemId(), child.rawNutrition(), child.rawSaturation(), ingredient.count(),
                ingredient.foodComponent(), child.sourceRecipe(), child.difficulty()
            ));
        }

        FoodValue value = calculateOperation(node.recipeId(), node.recipeType(), resolved, node.outputCount(), -1);
        cache.put(node.recipeId(), value);
        return value;
    }

    private FoodValue calculateOperation(String recipeId, String recipeType, List<IngredientContribution> ingredients,
        int outputCount, int stationDifficultyOverride) {
        double nutritionBonus = processingNutritionBudget;
        double saturationBonus = processingSaturationBudget;
        if (adapters != null && DynamicFoodConfig.allowsRecipeType(recipeType)) {
            RecipeAdapter adapter = adapters.find(recipeType).orElse(null);
            int automaticStationDifficulty = stationDifficultyOverride >= 0
                ? stationDifficultyOverride : adapter == null ? 0 : adapter.stationDifficulty();
            int stationDifficulty = DynamicFoodConfig.stationDifficulty(recipeType, automaticStationDifficulty);
            double recipeDifficulty = DynamicFoodConfig.recipeDifficulty(recipeId, RecipeDifficultyResolver.estimate(ingredients));
            nutritionBonus = DynamicFoodConfig.number(DynamicFoodConfig.BASE_PROCESSING_NUTRITION_BONUS, 0.15D)
                + stationDifficulty * DynamicFoodConfig.number(DynamicFoodConfig.STATION_NUTRITION_BONUS_PER_LEVEL, 0.15D)
                + recipeDifficulty * DynamicFoodConfig.number(DynamicFoodConfig.RECIPE_COMPLEXITY_NUTRITION_BONUS_PER_LEVEL, 0.05D);
            saturationBonus = DynamicFoodConfig.number(DynamicFoodConfig.BASE_PROCESSING_SATURATION_BONUS, 0.05D)
                + stationDifficulty * DynamicFoodConfig.number(DynamicFoodConfig.STATION_SATURATION_BONUS_PER_LEVEL, 0.05D)
                + recipeDifficulty * DynamicFoodConfig.number(DynamicFoodConfig.RECIPE_COMPLEXITY_SATURATION_BONUS_PER_LEVEL, 0.02D);
            nutritionBonus = Math.min(DynamicFoodConfig.number(DynamicFoodConfig.MAX_PROCESSING_NUTRITION_BONUS, 3.0D), nutritionBonus);
            saturationBonus = Math.min(DynamicFoodConfig.number(DynamicFoodConfig.MAX_PROCESSING_SATURATION_BONUS, 1.0D), saturationBonus);
        }
        FoodValue computed = FoodValueResolver.compute(ingredients, outputCount, nutritionBonus, saturationBonus,
            maxAutomaticNutrition, maxAutomaticSaturation, overflowMode, recipeId);
        double difficulty = DynamicFoodConfig.recipeDifficulty(recipeId, computed.difficulty());
        return new FoodValue(computed.rawNutrition(), computed.nutrition(), computed.rawSaturation(), computed.saturation(),
            difficulty, computed.sourceRecipe(), computed.outputCount(), computed.components());
    }
}
