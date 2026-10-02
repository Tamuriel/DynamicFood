package com.jorjik.dynamicfood.adapter;

import com.jorjik.dynamicfood.provenance.RecipeOperation;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.fluids.FluidStack;

public interface RecipeAdapter {
    boolean supports(String recipeType);

    int stationDifficulty();

    default boolean supports(Recipe<?> recipe) {
        return recipe != null && supports(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString());
    }

    default List<RecipeOperation.ItemInput> captureInputs(List<ItemStack> actualInputs) {
        return actualInputs.stream().filter(stack -> !stack.isEmpty())
            .map(stack -> new RecipeOperation.ItemInput(stack, stack.getCount())).toList();
    }

    default List<RecipeOperation.FluidInput> captureFluidInputs(List<FluidStack> actualInputs) {
        return actualInputs.stream().filter(stack -> !stack.isEmpty())
            .map(RecipeOperation.FluidInput::new).toList();
    }

    default List<RecipeOperation.Output> captureOutputs(List<ItemStack> actualOutputs) {
        return actualOutputs.stream().filter(stack -> !stack.isEmpty())
            .map(stack -> new RecipeOperation.Output(stack, null)).toList();
    }

    default RecipeOperation createRecipeOperation(String recipeId, String recipeType, String station,
        List<ItemStack> actualInputs, List<FluidStack> actualFluidInputs, List<ItemStack> actualOutputs,
        Map<String, Double> processingMetadata) {
        return new RecipeOperation(recipeId, recipeType, station, stationDifficulty(),
            captureInputs(actualInputs), captureFluidInputs(actualFluidInputs), captureOutputs(actualOutputs),
            processingMetadata);
    }

}
