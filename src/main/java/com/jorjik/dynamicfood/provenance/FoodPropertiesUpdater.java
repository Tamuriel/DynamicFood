package com.jorjik.dynamicfood.provenance;

import net.minecraft.world.food.FoodProperties;

public final class FoodPropertiesUpdater {
    private FoodPropertiesUpdater() {}

    public static FoodProperties withDynamicValue(FoodProperties existing, DynamicFoodValue value) {
        return new FoodProperties(
            value.nutrition(),
            (float) SaturationConverter.effectiveToFoodProperties(value.nutrition(), value.saturation()),
            existing.canAlwaysEat(),
            existing.eatSeconds(),
            existing.usingConvertsTo(),
            existing.effects()
        );
    }
}