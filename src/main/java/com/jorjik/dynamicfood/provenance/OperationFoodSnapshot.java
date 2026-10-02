package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.core.FoodValue;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.Comparator;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record OperationFoodSnapshot(
    String recipeType,
    String station,
    int stationDifficulty,
    String itemInputs,
    String fluidInputs,
    String processingMetadata,
    double allocationShare,
    double componentNutrition,
    double componentSaturation,
    double processingNutritionBonus,
    double processingSaturationBonus
) {
    public static final Codec<OperationFoodSnapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.fieldOf("recipe_type").forGetter(OperationFoodSnapshot::recipeType),
        Codec.STRING.fieldOf("station").forGetter(OperationFoodSnapshot::station),
        Codec.INT.fieldOf("station_difficulty").forGetter(OperationFoodSnapshot::stationDifficulty),
        Codec.STRING.fieldOf("item_inputs").forGetter(OperationFoodSnapshot::itemInputs),
        Codec.STRING.fieldOf("fluid_inputs").forGetter(OperationFoodSnapshot::fluidInputs),
        Codec.STRING.fieldOf("processing_metadata").forGetter(OperationFoodSnapshot::processingMetadata),
        Codec.DOUBLE.fieldOf("allocation_share").forGetter(OperationFoodSnapshot::allocationShare),
        Codec.DOUBLE.fieldOf("component_nutrition").forGetter(OperationFoodSnapshot::componentNutrition),
        Codec.DOUBLE.fieldOf("component_saturation").forGetter(OperationFoodSnapshot::componentSaturation),
        Codec.DOUBLE.fieldOf("processing_nutrition_bonus").forGetter(OperationFoodSnapshot::processingNutritionBonus),
        Codec.DOUBLE.fieldOf("processing_saturation_bonus").forGetter(OperationFoodSnapshot::processingSaturationBonus)
    ).apply(instance, OperationFoodSnapshot::new));

    public static final StreamCodec<ByteBuf, OperationFoodSnapshot> STREAM_CODEC = StreamCodec.of(
        (buffer, value) -> {
            ByteBufCodecs.STRING_UTF8.encode(buffer, value.recipeType());
            ByteBufCodecs.STRING_UTF8.encode(buffer, value.station());
            ByteBufCodecs.VAR_INT.encode(buffer, value.stationDifficulty());
            ByteBufCodecs.STRING_UTF8.encode(buffer, value.itemInputs());
            ByteBufCodecs.STRING_UTF8.encode(buffer, value.fluidInputs());
            ByteBufCodecs.STRING_UTF8.encode(buffer, value.processingMetadata());
            ByteBufCodecs.DOUBLE.encode(buffer, value.allocationShare());
            ByteBufCodecs.DOUBLE.encode(buffer, value.componentNutrition());
            ByteBufCodecs.DOUBLE.encode(buffer, value.componentSaturation());
            ByteBufCodecs.DOUBLE.encode(buffer, value.processingNutritionBonus());
            ByteBufCodecs.DOUBLE.encode(buffer, value.processingSaturationBonus());
        },
        buffer -> new OperationFoodSnapshot(
            ByteBufCodecs.STRING_UTF8.decode(buffer),
            ByteBufCodecs.STRING_UTF8.decode(buffer),
            ByteBufCodecs.VAR_INT.decode(buffer),
            ByteBufCodecs.STRING_UTF8.decode(buffer),
            ByteBufCodecs.STRING_UTF8.decode(buffer),
            ByteBufCodecs.STRING_UTF8.decode(buffer),
            ByteBufCodecs.DOUBLE.decode(buffer),
            ByteBufCodecs.DOUBLE.decode(buffer),
            ByteBufCodecs.DOUBLE.decode(buffer),
            ByteBufCodecs.DOUBLE.decode(buffer),
            ByteBufCodecs.DOUBLE.decode(buffer)
        )
    );

    public OperationFoodSnapshot {
        recipeType = recipeType == null ? "unknown" : recipeType;
        station = station == null ? "unknown" : station;
        stationDifficulty = Math.max(0, Math.min(5, stationDifficulty));
        itemInputs = itemInputs == null ? "" : itemInputs;
        fluidInputs = fluidInputs == null ? "" : fluidInputs;
        processingMetadata = processingMetadata == null ? "" : processingMetadata;
        if (!unit(allocationShare) || !nonNegative(componentNutrition) || !nonNegative(componentSaturation)
            || !Double.isFinite(processingNutritionBonus) || !Double.isFinite(processingSaturationBonus)) {
            throw new IllegalArgumentException("operation food snapshot contains invalid values");
        }
    }

    public static OperationFoodSnapshot from(RecipeOperation operation, FoodValue value, double allocationShare) {
        double componentNutrition = value.components().stream()
            .mapToDouble(input -> input.nutrition() * input.count()).sum();
        double componentSaturation = value.components().stream()
            .mapToDouble(input -> input.saturation() * input.count()).sum();
        double operationNutrition = value.rawNutrition() * value.outputCount();
        double operationSaturation = value.rawSaturation() * value.outputCount();
        String itemInputs = operation.itemInputs().stream()
            .map(input -> RuntimeFoodApplier.itemId(input.stack()) + " x" + input.consumedCount())
            .collect(java.util.stream.Collectors.joining(", "));
        String fluidInputs = operation.fluidInputs().stream()
            .map(input -> BuiltInRegistries.FLUID.getKey(input.stack().getFluid()) + " "
                + input.stack().getAmount() + "mB")
            .collect(java.util.stream.Collectors.joining(", "));
        String metadata = operation.processingMetadata().entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(", "));
        return new OperationFoodSnapshot(operation.recipeType(), operation.station(), operation.stationDifficulty(),
            itemInputs, fluidInputs, metadata, allocationShare, componentNutrition, componentSaturation,
            operationNutrition - componentNutrition, operationSaturation - componentSaturation);
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= 1.0D;
    }

    private static boolean nonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0D;
    }
}
