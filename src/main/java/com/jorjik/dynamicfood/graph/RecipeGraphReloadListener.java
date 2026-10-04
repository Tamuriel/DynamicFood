package com.jorjik.dynamicfood.graph;

import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import com.jorjik.dynamicfood.core.DynamicFoodEngine;
import com.jorjik.dynamicfood.core.IngredientContribution;
import com.jorjik.dynamicfood.provenance.ItemStackFoodResolver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;

public final class RecipeGraphReloadListener extends SimplePreparableReloadListener<RecipeManager> {
    private final DynamicFoodEngine engine;
    private final RecipeManager recipeManager;
    private final HolderLookup.Provider registries;

    public RecipeGraphReloadListener(DynamicFoodEngine engine, RecipeManager recipeManager, HolderLookup.Provider registries) {
        this.engine = engine;
        this.recipeManager = recipeManager;
        this.registries = registries;
    }

    public static void register(AddReloadListenerEvent event, DynamicFoodEngine engine) {
        event.addListener(new RecipeGraphReloadListener(
            engine,
            event.getServerResources().getRecipeManager(),
            event.getRegistryAccess()
        ));
    }

    @Override
    protected RecipeManager prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return recipeManager;
    }

    @Override
    protected void apply(RecipeManager loadedRecipeManager, ResourceManager resourceManager, ProfilerFiller profiler) {
        List<RecipeNode> nodes = new ArrayList<>();
        for (RecipeHolder<?> holder : loadedRecipeManager.getRecipes()) {
            Recipe<?> recipe = holder.value();
            String recipeType = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
            if (!DynamicFoodConfig.allowsRecipeType(recipeType)) {
                continue;
            }

            ItemStack output = recipe.getResultItem(registries);
            if (output.isEmpty()) {
                continue;
            }

            List<IngredientContribution> inputs = new ArrayList<>();
            List<AcquisitionIngredient> acquisitionInputs = new ArrayList<>();
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                if (ingredient.hasNoItems()) {
                    acquisitionInputs.add(new AcquisitionIngredient(
                        List.of(), 1, AcquisitionIngredient.InputUse.UNKNOWN));
                    continue;
                }
                ItemStack[] alternatives = ingredient.getItems();
                inputs.add(resolveIngredientDefinition(ingredient));
                acquisitionInputs.add(new AcquisitionIngredient(
                    java.util.Arrays.stream(alternatives)
                        .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()))
                        .filter(java.util.Objects::nonNull)
                        .map(Object::toString)
                        .toList(), 1, classifyInputUse(alternatives)));
            }
            nodes.add(new RecipeNode(
                holder.id().toString(),
                recipeType,
                BuiltInRegistries.ITEM.getKey(output.getItem()).toString(),
                output.getCount(),
                inputs,
                processingTimeTicks(recipe),
                acquisitionInputs,
                true
            ));
        }
        List<RecipeNode> immutableNodes = List.copyOf(nodes);
        engine.replaceStaticRecipes(immutableNodes);
        engine.rebuildLootTableAnalyzer(resourceManager);
        engine.markStaticAcquisitionInputsReady();
        engine.rebuildCalibrationWithDiscovery(DynamicFoodConfig.economicProfiles(),
            DynamicFoodConfig.calibrationSettings());
        Set<String> loggedAmbiguities = new HashSet<>();
        Set<String> loggedCycles = new HashSet<>();
        for (RecipeNode node : immutableNodes) {
            if (engine.graph().hasAmbiguousRecipes(node.resultId()) && loggedAmbiguities.add(node.resultId())) {
                DynamicFood.LOGGER.warn("Multiple recipes produce {}; static fallback selects the lexicographically first recipe ID", node.resultId());
            }
            List<String> cycle = engine.graph().cycleFrom(node.resultId());
            if (!cycle.isEmpty() && loggedCycles.add(cycle.toString())) {
                DynamicFood.LOGGER.warn("Recipe graph cycle detected: {}", cycle);
            }
        }
        if (DynamicFoodConfig.flag(DynamicFoodConfig.DEBUG, false)) {
            DynamicFood.LOGGER.info("Rebuilt Dynamic Food recipe graph with {} recipes", immutableNodes.size());
        }
    }

    private static Double processingTimeTicks(Recipe<?> recipe) {
        if (recipe instanceof AbstractCookingRecipe cookingRecipe) {
            return (double) cookingRecipe.getCookingTime();
        }
        if (!recipe.getClass().getName()
            .equals("vectorwing.farmersdelight.common.crafting.CookingPotRecipe")) {
            return null;
        }
        try {
            Object cookTime = recipe.getClass().getMethod("getCookTime").invoke(recipe);
            if (cookTime instanceof Integer ticks && ticks >= 0) {
                return ticks.doubleValue();
            }
            DynamicFood.LOGGER.warn("Farmer's Delight CookingPotRecipe returned invalid cook time {}",
                cookTime);
        } catch (ReflectiveOperationException exception) {
            DynamicFood.LOGGER.warn(
                "Could not read the Farmer's Delight CookingPotRecipe cook time", exception);
        }
        return null;
    }

    static AcquisitionIngredient.InputUse classifyInputUse(ItemStack[] alternatives) {
        if (alternatives.length == 0) {
            return AcquisitionIngredient.InputUse.UNKNOWN;
        }
        boolean hasConsumed = false;
        boolean hasReusable = false;
        for (ItemStack alternative : alternatives) {
            if (!alternative.hasCraftingRemainingItem()) {
                hasConsumed = true;
            } else {
                ItemStack remainder = alternative.getCraftingRemainingItem();
                if (remainder.isEmpty()) {
                    return AcquisitionIngredient.InputUse.UNKNOWN;
                }
                if (isReusableRemainder(alternative, remainder)) {
                    hasReusable = true;
                } else {
                    return AcquisitionIngredient.InputUse.UNKNOWN;
                }
            }
        }
        if (hasConsumed && hasReusable) {
            return AcquisitionIngredient.InputUse.UNKNOWN;
        }
        return hasReusable ? AcquisitionIngredient.InputUse.REUSABLE : AcquisitionIngredient.InputUse.CONSUMED;
    }

    static boolean isReusableRemainder(ItemStack input, ItemStack remainder) {
        return !remainder.isEmpty()
            && ItemStack.isSameItemSameComponents(input, remainder)
            && input.getCount() == remainder.getCount();
    }

    private static IngredientContribution resolveIngredientDefinition(Ingredient ingredient) {
        ItemStack[] alternatives = ingredient.getItems();
        List<IngredientContribution> foodAlternatives = new ArrayList<>();
        for (ItemStack alternative : alternatives) {
            IngredientContribution contribution = ItemStackFoodResolver.resolve(alternative);
            if (contribution.foodComponent()) {
                foodAlternatives.add(contribution);
            }
        }
        if (foodAlternatives.isEmpty()) {
            return new IngredientContribution("minecraft:air", 0.0D, 0.0D, 1, false, "non_food_ingredient");
        }
        if (foodAlternatives.size() == 1) {
            IngredientContribution only = foodAlternatives.getFirst();
            return new IngredientContribution(only.itemId(), only.nutrition(), only.saturation(), 1,
                true, "ingredient_definition:" + only.itemId());
        }

        double averageNutrition = foodAlternatives.stream().mapToDouble(IngredientContribution::nutrition).average().orElse(0.0D);
        double averageSaturation = foodAlternatives.stream().mapToDouble(IngredientContribution::saturation).average().orElse(0.0D);
        return new IngredientContribution(
            "dynamicfood:ingredient_alternatives",
            averageNutrition,
            averageSaturation,
            1,
            true,
            "ingredient_definition_average:" + foodAlternatives.size(),
            foodAlternatives.stream().mapToDouble(IngredientContribution::difficulty).average().orElse(0.0D)
        );
    }
}
