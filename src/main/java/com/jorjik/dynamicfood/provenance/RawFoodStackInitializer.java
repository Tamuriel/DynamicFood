package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import com.jorjik.dynamicfood.core.FoodValue;
import com.jorjik.dynamicfood.core.IngredientContribution;
import com.jorjik.dynamicfood.data.DynamicFoodDataComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class RawFoodStackInitializer {
    private RawFoodStackInitializer() {}

    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getEntity().getItemInHand(event.getHand());
        initialize(stack, RuntimeEconomicContext.capture(DynamicFood.ECONOMIC_GENERATIONS));
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getEntity().getItemInHand(event.getHand());
        initialize(stack, RuntimeEconomicContext.capture(DynamicFood.ECONOMIC_GENERATIONS));
    }

    static void initialize(ItemStack stack, RuntimeEconomicContext economicContext) {
        if (stack.isEmpty() || !DynamicFoodConfig.flag(DynamicFoodConfig.OVERRIDE_EXISTING_FOOD, true)
            || economicContext == null || economicContext.publishedGeneration().isEmpty()) {
            return;
        }
        DynamicFoodValue existingValue = stack.get(DynamicFoodDataComponents.VALUE.get());
        if (existingValue != null && (existingValue.origin() != DynamicFoodValue.Origin.STATIC_CALIBRATED
            || existingValue.belongsTo(economicContext))) {
            return;
        }
        FoodProperties existing = stack.get(DataComponents.FOOD);
        if (existing == null) {
            return;
        }
        String itemId = RuntimeFoodApplier.itemId(stack);
        if (ItemStackFoodResolver.profile(itemId).isEmpty()
            && ItemStackFoodResolver.calibratedBaseFoodValue(itemId, economicContext).isEmpty()) {
            return;
        }
        IngredientContribution contribution = ItemStackFoodResolver.resolve(stack, economicContext);
        if (!contribution.foodComponent()) {
            return;
        }
        IngredientContribution perUnit = new IngredientContribution(contribution.itemId(), contribution.nutrition(),
            contribution.saturation(), 1, contribution.foodComponent(), contribution.sourceRecipe(), contribution.difficulty());

        FoodValue snapshotValue = new FoodValue(perUnit.nutrition(),
            (int) Math.ceil(perUnit.nutrition()), perUnit.saturation(), perUnit.saturation(),
            perUnit.difficulty(), "raw:" + perUnit.sourceRecipe(), 1, java.util.List.of(perUnit));
        DynamicFoodValue snapshot = DynamicFoodValue.snapshotCalibrated(snapshotValue, economicContext);
        stack.set(DynamicFoodDataComponents.VALUE.get(), snapshot);
        stack.set(DataComponents.FOOD, FoodPropertiesUpdater.withDynamicValue(existing, snapshot));
    }
}