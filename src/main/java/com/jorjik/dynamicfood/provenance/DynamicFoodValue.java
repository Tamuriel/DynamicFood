package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.core.FoodValue;
import com.jorjik.dynamicfood.core.IngredientContribution;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record DynamicFoodValue(
    double rawNutrition,
    int nutrition,
    double rawSaturation,
    float saturation,
    double difficulty,
    String sourceRecipe,
    int outputCount,
    List<IngredientFoodSnapshot> components,
    Optional<OperationFoodSnapshot> operationSnapshot,
    int formatVersion
) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    private static final StreamCodec<ByteBuf, NutritionSnapshot> NUTRITION_STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.DOUBLE, NutritionSnapshot::rawNutrition,
        ByteBufCodecs.VAR_INT, NutritionSnapshot::nutrition,
        ByteBufCodecs.DOUBLE, NutritionSnapshot::rawSaturation,
        ByteBufCodecs.FLOAT, NutritionSnapshot::saturation,
        ByteBufCodecs.DOUBLE, NutritionSnapshot::difficulty,
        NutritionSnapshot::new
    );

    public static final Codec<DynamicFoodValue> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.DOUBLE.fieldOf("raw_nutrition").forGetter(DynamicFoodValue::rawNutrition),
        Codec.INT.fieldOf("nutrition").forGetter(DynamicFoodValue::nutrition),
        Codec.DOUBLE.fieldOf("raw_saturation").forGetter(DynamicFoodValue::rawSaturation),
        Codec.FLOAT.fieldOf("saturation").forGetter(DynamicFoodValue::saturation),
        Codec.DOUBLE.fieldOf("difficulty").forGetter(DynamicFoodValue::difficulty),
        Codec.STRING.fieldOf("source_recipe").forGetter(DynamicFoodValue::sourceRecipe),
        Codec.INT.fieldOf("output_count").forGetter(DynamicFoodValue::outputCount),
        IngredientFoodSnapshot.CODEC.listOf().fieldOf("components").forGetter(DynamicFoodValue::components),
        OperationFoodSnapshot.CODEC.optionalFieldOf("operation_snapshot").forGetter(DynamicFoodValue::operationSnapshot),
        Codec.INT.optionalFieldOf("value_version", 0).forGetter(DynamicFoodValue::formatVersion)
    ).apply(instance, DynamicFoodValue::new));

    private static final StreamCodec<ByteBuf, List<IngredientFoodSnapshot>> COMPONENTS_STREAM_CODEC =
        IngredientFoodSnapshot.STREAM_CODEC.apply(ByteBufCodecs.list());
    private static final StreamCodec<ByteBuf, Optional<OperationFoodSnapshot>> OPTIONAL_OPERATION_STREAM_CODEC =
        StreamCodec.of(
            (buffer, value) -> {
                buffer.writeBoolean(value.isPresent());
                value.ifPresent(snapshot -> OperationFoodSnapshot.STREAM_CODEC.encode(buffer, snapshot));
            },
            buffer -> buffer.readBoolean()
                ? Optional.of(OperationFoodSnapshot.STREAM_CODEC.decode(buffer)) : Optional.empty()
        );

    public static final StreamCodec<ByteBuf, DynamicFoodValue> STREAM_CODEC = StreamCodec.composite(
        NUTRITION_STREAM_CODEC, value -> new NutritionSnapshot(value.rawNutrition(), value.nutrition(), value.rawSaturation(), value.saturation(), value.difficulty()),
        ByteBufCodecs.STRING_UTF8, DynamicFoodValue::sourceRecipe,
        ByteBufCodecs.VAR_INT, DynamicFoodValue::outputCount,
        COMPONENTS_STREAM_CODEC, DynamicFoodValue::components,
        OPTIONAL_OPERATION_STREAM_CODEC, DynamicFoodValue::operationSnapshot,
        (nutrition, source, outputCount, components, operationSnapshot) -> new DynamicFoodValue(
            nutrition.rawNutrition(), nutrition.nutrition(), nutrition.rawSaturation(), nutrition.saturation(), nutrition.difficulty(),
            source, outputCount, components, operationSnapshot, CURRENT_FORMAT_VERSION
        )
    );

    public DynamicFoodValue(double rawNutrition, int nutrition, double rawSaturation, float saturation,
        double difficulty, String sourceRecipe, int outputCount, List<IngredientFoodSnapshot> components) {
        this(rawNutrition, nutrition, rawSaturation, saturation, difficulty, sourceRecipe, outputCount,
            components, Optional.empty(), CURRENT_FORMAT_VERSION);
    }

    public DynamicFoodValue(double rawNutrition, int nutrition, double rawSaturation, float saturation,
        double difficulty, String sourceRecipe, int outputCount, List<IngredientFoodSnapshot> components,
        int formatVersion) {
        this(rawNutrition, nutrition, rawSaturation, saturation, difficulty, sourceRecipe, outputCount,
            components, Optional.empty(), formatVersion);
    }

    public DynamicFoodValue {
        if (formatVersion < 0 || formatVersion > CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported DynamicFoodValue format version: " + formatVersion);
        }
        if (formatVersion == 0) {
            double migratedSaturation = SaturationConverter.modifierToEffective(nutrition, saturation);
            rawSaturation = migratedSaturation;
            saturation = (float) migratedSaturation;
            formatVersion = CURRENT_FORMAT_VERSION;
        }
        rawNutrition = finiteNonNegative(rawNutrition);
        nutrition = Math.max(0, nutrition);
        rawSaturation = finiteNonNegative(rawSaturation);
        saturation = Float.isFinite(saturation) ? Math.max(0.0F, saturation) : 0.0F;
        difficulty = Double.isFinite(difficulty) ? Math.max(0.0D, Math.min(5.0D, difficulty)) : 0.0D;
        sourceRecipe = sourceRecipe == null ? "unknown" : sourceRecipe;
        outputCount = Math.max(1, outputCount);
        components = components == null ? List.of() : List.copyOf(components);
        operationSnapshot = operationSnapshot == null ? Optional.empty() : operationSnapshot;
    }

    public static DynamicFoodValue snapshot(FoodValue value) {
        return new DynamicFoodValue(value.rawNutrition(), value.nutrition(), value.rawSaturation(),
            (float) value.saturation(), value.difficulty(), value.sourceRecipe(), value.outputCount(),
            value.components().stream().map(IngredientFoodSnapshot::from).toList(), Optional.empty(),
            CURRENT_FORMAT_VERSION);
    }

    public static DynamicFoodValue snapshot(FoodValue value, OperationFoodSnapshot operationSnapshot) {
        return new DynamicFoodValue(value.rawNutrition(), value.nutrition(), value.rawSaturation(),
            (float) value.saturation(), value.difficulty(), value.sourceRecipe(), value.outputCount(),
            value.components().stream().map(IngredientFoodSnapshot::from).toList(),
            Optional.of(operationSnapshot), CURRENT_FORMAT_VERSION);
    }

    public IngredientContribution asIngredientContribution(String itemId, int count) {
        return new IngredientContribution(itemId, rawNutrition, rawSaturation, count, true, sourceRecipe, difficulty);
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }

    private record NutritionSnapshot(double rawNutrition, int nutrition, double rawSaturation, float saturation, double difficulty) {
    }
}
