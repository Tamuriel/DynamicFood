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
    Optional<GenerationReference> staticGenerationReference,
    int formatVersion
) {
    public static final int CURRENT_FORMAT_VERSION = 3;

    public enum Origin {
        RUNTIME_OPERATION,
        STATIC_CALIBRATED,
        MIGRATED_OR_UNKNOWN
    }

    public record GenerationReference(long generation, String economicContentSignature,
        Optional<String> calibrationContentSignature) {
        public static final Codec<GenerationReference> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                Codec.LONG.fieldOf("generation").forGetter(GenerationReference::generation),
                Codec.STRING.fieldOf("economic_content_signature")
                    .forGetter(GenerationReference::economicContentSignature),
                Codec.STRING.optionalFieldOf("calibration_content_signature")
                    .forGetter(GenerationReference::calibrationContentSignature)
            ).apply(instance, GenerationReference::new));

        public static final StreamCodec<ByteBuf, GenerationReference> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                ByteBufCodecs.VAR_LONG.encode(buffer, value.generation());
                ByteBufCodecs.STRING_UTF8.encode(buffer, value.economicContentSignature());
                buffer.writeBoolean(value.calibrationContentSignature().isPresent());
                value.calibrationContentSignature()
                    .ifPresent(signature -> ByteBufCodecs.STRING_UTF8.encode(buffer, signature));
            },
            buffer -> {
                long generation = ByteBufCodecs.VAR_LONG.decode(buffer);
                String economicSignature = ByteBufCodecs.STRING_UTF8.decode(buffer);
                Optional<String> calibrationSignature = buffer.readBoolean()
                    ? Optional.of(ByteBufCodecs.STRING_UTF8.decode(buffer)) : Optional.empty();
                return new GenerationReference(generation, economicSignature, calibrationSignature);
            }
        );

        public GenerationReference(long generation, String economicContentSignature) {
            this(generation, economicContentSignature, Optional.empty());
        }

        public GenerationReference {
            calibrationContentSignature = calibrationContentSignature == null
                ? Optional.empty() : calibrationContentSignature;
            if (generation < 1 || economicContentSignature == null || economicContentSignature.isBlank()) {
                throw new IllegalArgumentException("generation reference must contain a positive generation "
                    + "and non-empty economic content signature");
            }
            if (calibrationContentSignature.filter(String::isBlank).isPresent()) {
                throw new IllegalArgumentException("calibration content signature must be non-empty");
            }
        }
    }

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
        GenerationReference.CODEC.optionalFieldOf("static_generation")
            .forGetter(DynamicFoodValue::staticGenerationReference),
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
    private static final StreamCodec<ByteBuf, Optional<GenerationReference>> OPTIONAL_GENERATION_STREAM_CODEC =
        StreamCodec.of(
            (buffer, value) -> {
                buffer.writeBoolean(value.isPresent());
                value.ifPresent(reference -> GenerationReference.STREAM_CODEC.encode(buffer, reference));
            },
            buffer -> buffer.readBoolean()
                ? Optional.of(GenerationReference.STREAM_CODEC.decode(buffer)) : Optional.empty()
        );

    public static final StreamCodec<ByteBuf, DynamicFoodValue> STREAM_CODEC = StreamCodec.composite(
        NUTRITION_STREAM_CODEC, value -> new NutritionSnapshot(value.rawNutrition(), value.nutrition(), value.rawSaturation(), value.saturation(), value.difficulty()),
        ByteBufCodecs.STRING_UTF8, DynamicFoodValue::sourceRecipe,
        ByteBufCodecs.VAR_INT, DynamicFoodValue::outputCount,
        COMPONENTS_STREAM_CODEC, DynamicFoodValue::components,
        OPTIONAL_OPERATION_STREAM_CODEC, DynamicFoodValue::operationSnapshot,
        OPTIONAL_GENERATION_STREAM_CODEC, DynamicFoodValue::staticGenerationReference,
        (nutrition, source, outputCount, components, operationSnapshot, generationReference) -> new DynamicFoodValue(
            nutrition.rawNutrition(), nutrition.nutrition(), nutrition.rawSaturation(), nutrition.saturation(), nutrition.difficulty(),
            source, outputCount, components, operationSnapshot, generationReference, CURRENT_FORMAT_VERSION
        )
    );

    public DynamicFoodValue(double rawNutrition, int nutrition, double rawSaturation, float saturation,
        double difficulty, String sourceRecipe, int outputCount, List<IngredientFoodSnapshot> components) {
        this(rawNutrition, nutrition, rawSaturation, saturation, difficulty, sourceRecipe, outputCount,
            components, Optional.empty(), Optional.empty(), CURRENT_FORMAT_VERSION);
    }

    public DynamicFoodValue(double rawNutrition, int nutrition, double rawSaturation, float saturation,
        double difficulty, String sourceRecipe, int outputCount, List<IngredientFoodSnapshot> components,
        int formatVersion) {
        this(rawNutrition, nutrition, rawSaturation, saturation, difficulty, sourceRecipe, outputCount,
            components, Optional.empty(), Optional.empty(), formatVersion);
    }

    public DynamicFoodValue(double rawNutrition, int nutrition, double rawSaturation, float saturation,
        double difficulty, String sourceRecipe, int outputCount, List<IngredientFoodSnapshot> components,
        Optional<OperationFoodSnapshot> operationSnapshot, int formatVersion) {
        this(rawNutrition, nutrition, rawSaturation, saturation, difficulty, sourceRecipe, outputCount,
            components, operationSnapshot, Optional.empty(), formatVersion);
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
        staticGenerationReference = staticGenerationReference == null
            ? Optional.empty() : staticGenerationReference;
        if (operationSnapshot.isPresent() && staticGenerationReference.isPresent()) {
            throw new IllegalArgumentException("a value cannot be both operation-observed and static-calibrated");
        }
        formatVersion = CURRENT_FORMAT_VERSION;
    }

    public static DynamicFoodValue snapshot(FoodValue value) {
        return new DynamicFoodValue(value.rawNutrition(), value.nutrition(), value.rawSaturation(),
            (float) value.saturation(), value.difficulty(), value.sourceRecipe(), value.outputCount(),
            value.components().stream().map(IngredientFoodSnapshot::from).toList(),
            Optional.empty(), Optional.empty(),
            CURRENT_FORMAT_VERSION);
    }

    public static DynamicFoodValue snapshot(FoodValue value, OperationFoodSnapshot operationSnapshot) {
        return new DynamicFoodValue(value.rawNutrition(), value.nutrition(), value.rawSaturation(),
            (float) value.saturation(), value.difficulty(), value.sourceRecipe(), value.outputCount(),
            value.components().stream().map(IngredientFoodSnapshot::from).toList(),
            Optional.of(operationSnapshot), Optional.empty(), CURRENT_FORMAT_VERSION);
    }

    public static DynamicFoodValue snapshotCalibrated(FoodValue value, RuntimeEconomicContext context) {
        if (context == null || context.generation().isEmpty() || context.economicContentSignature().isEmpty()
            || context.calibrationContentSignature().isEmpty()) {
            throw new IllegalArgumentException("a published generation is required for a calibrated value");
        }
        GenerationReference reference = new GenerationReference(context.generation().orElseThrow(),
            context.economicContentSignature().orElseThrow(), context.calibrationContentSignature());
        return new DynamicFoodValue(value.rawNutrition(), value.nutrition(), value.rawSaturation(),
            (float) value.saturation(), value.difficulty(), value.sourceRecipe(), value.outputCount(),
            value.components().stream().map(IngredientFoodSnapshot::from).toList(), Optional.empty(),
            Optional.of(reference), CURRENT_FORMAT_VERSION);
    }

    public Origin origin() {
        if (operationSnapshot.isPresent()) {
            return Origin.RUNTIME_OPERATION;
        }
        return staticGenerationReference.isPresent() ? Origin.STATIC_CALIBRATED : Origin.MIGRATED_OR_UNKNOWN;
    }

    public boolean belongsTo(RuntimeEconomicContext context) {
        if (context == null || context.generation().isEmpty()
            || context.economicContentSignature().isEmpty()) {
            return false;
        }
        long generation = context.generation().orElseThrow();
        String signature = context.economicContentSignature().orElseThrow();
        if (operationSnapshot.isPresent()) {
            OperationFoodSnapshot operation = operationSnapshot.orElseThrow();
            return operation.economicGeneration().filter(value -> value == generation).isPresent()
                && operation.economicContentSignature().filter(signature::equals).isPresent()
                && operation.calibrationContentSignature()
                    .equals(context.calibrationContentSignature());
        }
        return staticGenerationReference
            .filter(reference -> reference.generation() == generation
                && reference.economicContentSignature().equals(signature)
                && reference.calibrationContentSignature().equals(context.calibrationContentSignature()))
            .isPresent();
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
