package com.jorjik.dynamicfood.provenance;

import java.util.List;
import net.minecraft.world.item.ItemStack;

public final class RecipeTransactionHandler {
    public void onTransaction(RecipeTransactionEvent event) {
        if (event.operation() != null) {
            RuntimeFoodApplier.applyOperation(event.operation());
            return;
        }
        if (event.outputs().isEmpty()) {
            return;
        }
        List<OutputValueAllocator.OutputTarget<ItemStack>> targets = OutputValueAllocator.foodTargets(
            event.outputs(),
            stack -> ItemStackFoodResolver.resolve(stack).foodComponent(),
            ItemStack::getCount
        );
        if (targets.isEmpty()) {
            return;
        }
        int outputCount = targets.size() == 1 && event.outputCount() > 0
            ? event.outputCount()
            : OutputValueAllocator.totalFoodOutputCount(targets);
        RuntimeProvenance provenance = RuntimeProvenance.fromStacks(
            RuntimeFoodApplier.itemId(targets.getFirst().output()),
            event.recipeId(),
            event.recipeType(),
            outputCount,
            event.inputs()
        );
        RuntimeFoodApplier.applyOperation(targets, provenance);
    }
}