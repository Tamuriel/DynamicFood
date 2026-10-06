package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.core.IngredientContribution;
import java.util.List;
import net.minecraft.world.item.ItemStack;

public record RuntimeProvenance(
    String resultItemId,
    String recipeId,
    String recipeType,
    int outputCount,
    List<IngredientContribution> actualInputs,
    int stationDifficulty
) {
    public RuntimeProvenance(String resultItemId, String recipeId, String recipeType, int outputCount,
        List<IngredientContribution> actualInputs) {
        this(resultItemId, recipeId, recipeType, outputCount, actualInputs, -1);
    }

    public RuntimeProvenance {
        resultItemId = resultItemId == null ? "unknown" : resultItemId;
        recipeId = recipeId == null ? "unknown" : recipeId;
        recipeType = recipeType == null ? "unknown" : recipeType;
        outputCount = Math.max(1, outputCount);
        actualInputs = actualInputs == null ? List.of() : List.copyOf(actualInputs);
        stationDifficulty = stationDifficulty < 0 ? -1 : Math.min(5, stationDifficulty);
    }

    public boolean hasActualInputs() {
        return !actualInputs.isEmpty();
    }

    public static RuntimeProvenance fromOperation(RecipeOperation operation, int foodOutputCount,
        List<IngredientContribution> resolvedInputs) {
        String resultItemId = operation.outputs().isEmpty() ? "unknown"
            : RuntimeFoodApplier.itemId(operation.outputs().getFirst().stack());
        return new RuntimeProvenance(resultItemId, operation.recipeId(), operation.recipeType(),
            foodOutputCount, resolvedInputs, operation.stationDifficulty());
    }

    @Deprecated(forRemoval = false)
    public static RuntimeProvenance fromStacks(
        String resultItemId,
        String recipeId,
        String recipeType,
        int outputCount,
        List<ItemStack> inputs
    ) {
        return new RuntimeProvenance(resultItemId, recipeId, recipeType, outputCount,
            RuntimeFoodApplier.contributions(inputs));
    }

    public static RuntimeProvenance fromStacks(
        String resultItemId,
        String recipeId,
        String recipeType,
        int outputCount,
        List<ItemStack> inputs,
        RuntimeEconomicContext economicContext
    ) {
        return new RuntimeProvenance(resultItemId, recipeId, recipeType, outputCount,
            RuntimeFoodApplier.contributions(inputs, economicContext));
    }
}
