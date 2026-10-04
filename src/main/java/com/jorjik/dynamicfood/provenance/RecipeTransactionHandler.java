package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.core.EconomicGenerationPublisher;
import java.util.List;
import net.minecraft.world.item.ItemStack;

public final class RecipeTransactionHandler {
    private final EconomicGenerationPublisher generationPublisher;

    public RecipeTransactionHandler() {
        this(DynamicFood.ECONOMIC_GENERATIONS);
    }

    public RecipeTransactionHandler(EconomicGenerationPublisher generationPublisher) {
        if (generationPublisher == null) {
            throw new IllegalArgumentException("economic generation publisher is required");
        }
        this.generationPublisher = generationPublisher;
    }

    public void onTransaction(RecipeTransactionEvent event) {
        if (event.operation() != null) {
            RuntimeFoodApplier.applyOperation(event.operation(), generationPublisher);
            return;
        }
        if (event.outputs().isEmpty()) {
            return;
        }
        RuntimeEconomicContext economicContext = RuntimeEconomicContext.capture(generationPublisher);
        List<OutputValueAllocator.OutputTarget<ItemStack>> targets = OutputValueAllocator.foodTargets(
            event.outputs(),
            stack -> ItemStackFoodResolver.resolve(stack, economicContext).foodComponent(),
            ItemStack::getCount
        );
        if (targets.isEmpty()) {
            return;
        }
        int outputCount = targets.size() == 1 && event.outputCount() > 0
            ? event.outputCount()
            : OutputValueAllocator.totalFoodOutputCount(targets);
        RuntimeProvenance provenance = new RuntimeProvenance(
            RuntimeFoodApplier.itemId(targets.getFirst().output()),
            event.recipeId(),
            event.recipeType(),
            outputCount,
            RuntimeFoodApplier.contributions(event.inputs(), economicContext)
        );
        RuntimeFoodApplier.applyOperation(targets, provenance, economicContext);
    }
}