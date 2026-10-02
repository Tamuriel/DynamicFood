package com.jorjik.dynamicfood.data;

import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.provenance.DynamicFoodValue;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class DynamicFoodDataComponents {
    public static final DeferredRegister.DataComponents REGISTRAR = DeferredRegister.createDataComponents(
        Registries.DATA_COMPONENT_TYPE,
        DynamicFood.MOD_ID
    );

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<DynamicFoodValue>> VALUE =
        REGISTRAR.registerComponentType("nutrition_value", builder -> builder
            .persistent(DynamicFoodValue.CODEC)
            .networkSynchronized(DynamicFoodValue.STREAM_CODEC));

    private DynamicFoodDataComponents() {}
}
