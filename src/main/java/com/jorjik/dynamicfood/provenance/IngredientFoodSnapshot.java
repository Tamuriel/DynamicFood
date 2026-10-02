package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.core.IngredientContribution;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record IngredientFoodSnapshot(
    String itemId,
    double nutrition,
    double saturation,
    int count,
    boolean foodComponent,
    String sourceRecipe,
    double difficulty
) {
    public static final Codec<IngredientFoodSnapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.fieldOf("item_id").forGetter(IngredientFoodSnapshot::itemId),
        Codec.DOUBLE.fieldOf("nutrition").forGetter(IngredientFoodSnapshot::nutrition),
        Codec.DOUBLE.fieldOf("saturation").forGetter(IngredientFoodSnapshot::saturation),
        Codec.INT.fieldOf("count").forGetter(IngredientFoodSnapshot::count),
        Codec.BOOL.fieldOf("food_component").forGetter(IngredientFoodSnapshot::foodComponent),
        Codec.STRING.fieldOf("source_recipe").forGetter(IngredientFoodSnapshot::sourceRecipe),
        Codec.DOUBLE.optionalFieldOf("difficulty", 0.0D).forGetter(IngredientFoodSnapshot::difficulty)
    ).apply(instance, IngredientFoodSnapshot::new));

    private static final StreamCodec<ByteBuf, SourceSnapshot> SOURCE_STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, SourceSnapshot::sourceRecipe,
        ByteBufCodecs.DOUBLE, SourceSnapshot::difficulty,
        SourceSnapshot::new
    );

    public static final StreamCodec<ByteBuf, IngredientFoodSnapshot> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, IngredientFoodSnapshot::itemId,
        ByteBufCodecs.DOUBLE, IngredientFoodSnapshot::nutrition,
        ByteBufCodecs.DOUBLE, IngredientFoodSnapshot::saturation,
        ByteBufCodecs.VAR_INT, IngredientFoodSnapshot::count,
        ByteBufCodecs.BOOL, IngredientFoodSnapshot::foodComponent,
        SOURCE_STREAM_CODEC, value -> new SourceSnapshot(value.sourceRecipe(), value.difficulty()),
        (itemId, nutrition, saturation, count, foodComponent, source) -> new IngredientFoodSnapshot(
            itemId, nutrition, saturation, count, foodComponent, source.sourceRecipe(), source.difficulty())
    );

    public IngredientFoodSnapshot {
        itemId = itemId == null ? "unknown" : itemId;
        nutrition = Double.isFinite(nutrition) ? Math.max(0.0D, nutrition) : 0.0D;
        saturation = Double.isFinite(saturation) ? Math.max(0.0D, saturation) : 0.0D;
        count = Math.max(0, count);
        sourceRecipe = sourceRecipe == null ? "unknown" : sourceRecipe;
        difficulty = Double.isFinite(difficulty) ? Math.max(0.0D, Math.min(5.0D, difficulty)) : 0.0D;
    }

    public static IngredientFoodSnapshot from(IngredientContribution contribution) {
        return new IngredientFoodSnapshot(contribution.itemId(), contribution.nutrition(), contribution.saturation(),
            contribution.count(), contribution.foodComponent(), contribution.sourceRecipe(), contribution.difficulty());
    }

    private record SourceSnapshot(String sourceRecipe, double difficulty) {
    }
}
