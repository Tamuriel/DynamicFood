package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import com.jorjik.dynamicfood.config.ItemFoodProfile;
import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.core.CalibratedBaseFoodValue;
import com.jorjik.dynamicfood.core.IngredientContribution;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ItemStackFoodResolver {
    private ItemStackFoodResolver() {}

    @Deprecated(forRemoval = false)
    public static IngredientContribution resolve(ItemStack stack) {
        return resolve(stack, null);
    }

    public static IngredientContribution resolve(ItemStack stack, RuntimeEconomicContext context) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int count = stack.getCount();
        if (contains(DynamicFoodConfig.strings(DynamicFoodConfig.BLACKLISTED_ITEMS, List.of()), itemId)) {
            return new IngredientContribution(itemId, 0.0D, 0.0D, count, false, "blacklist");
        }

        boolean foodTag = hasConfiguredFoodTag(stack);
        double automaticDifficulty = automaticDifficulty(itemId, context);
        Optional<ItemFoodProfile> configured = profile(itemId);
        if (configured.isPresent()) {
            ItemFoodProfile profile = configured.get();
            if (!profile.enabled()) {
                return new IngredientContribution(itemId, 0.0D, 0.0D, count, false, "disabled_profile");
            }
            double difficulty = DynamicFoodConfig.itemDifficulty(itemId, profile.difficulty());
            var calibrated = calibratedBaseFoodValue(itemId, context);
            double automaticNutrition = calibrated.map(value -> value.nutrition()).orElseGet(() ->
                DynamicFoodConfig.number(DynamicFoodConfig.CALIBRATION_FALLBACK_NUTRITION, 2.0D));
            double automaticSaturation = calibrated.map(value -> value.effectiveSaturation()).orElseGet(() ->
                DynamicFoodConfig.number(DynamicFoodConfig.CALIBRATION_FALLBACK_SATURATION, 0.0D));
            double nutrition = profile.nutritionOverrideOr(automaticNutrition);
            double saturation = profile.saturationOverrideOr(automaticSaturation);
            return new IngredientContribution(itemId, nutrition, saturation, count,
                profile.foodComponent(), "config_profile", difficulty);
        }

        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) {
            if (foodTag) {
                var calibrated = calibratedBaseFoodValue(itemId, context);
                if (calibrated.isPresent()) {
                    return contribution(itemId, calibrated.get(), count, true);
                }
                double nutrition = DynamicFoodConfig.number(
                    DynamicFoodConfig.CALIBRATION_FALLBACK_NUTRITION, 2.0D);
                double saturation = DynamicFoodConfig.number(
                    DynamicFoodConfig.CALIBRATION_FALLBACK_SATURATION, 0.0D);
                return new IngredientContribution(itemId, nutrition, saturation, count, true,
                    "configured_food_fallback", DynamicFoodConfig.itemDifficulty(itemId, automaticDifficulty));
            }
            return new IngredientContribution(itemId, 0.0D, 0.0D, count, false, "non_food");
        }

        if (usesConfiguredDisabledFallback(context)) {
            return new IngredientContribution(itemId,
                DynamicFoodConfig.number(DynamicFoodConfig.CALIBRATION_FALLBACK_NUTRITION, 2.0D),
                DynamicFoodConfig.number(DynamicFoodConfig.CALIBRATION_FALLBACK_SATURATION, 0.0D),
                count, true, "configured_disabled_fallback",
                DynamicFoodConfig.itemDifficulty(itemId, automaticDifficulty));
        }

        if (food.nutrition() == 0 && DynamicFoodConfig.flag(DynamicFoodConfig.ZERO_HUNGER_FOOD_ENABLED, true)) {
            var calibrated = calibratedBaseFoodValue(itemId, context);
            if (calibrated.isPresent()) {
                return contribution(itemId, calibrated.get(), count, true);
            }
            double contribution = DynamicFoodConfig.number(
                DynamicFoodConfig.ZERO_HUNGER_DEFAULT_CONTRIBUTION, 0.25D);
            double difficulty = DynamicFoodConfig.itemDifficulty(itemId, automaticDifficulty);
            return new IngredientContribution(itemId, contribution,
                SaturationConverter.foodPropertiesToEffective(food.nutrition(), food.saturation()), count, true,
                "zero_hunger_fallback", difficulty);
        }
        var calibrated = calibratedBaseFoodValue(itemId, context);
        if (calibrated.isPresent()) {
            return contribution(itemId, calibrated.get(), count, true);
        }
        return new IngredientContribution(itemId, food.nutrition(),
            SaturationConverter.foodPropertiesToEffective(food.nutrition(), food.saturation()), count,
            true, "minecraft:food_properties", DynamicFoodConfig.itemDifficulty(itemId, automaticDifficulty));
    }

    private static double automaticDifficulty(String itemId, RuntimeEconomicContext context) {
        if (context == null) {
            var resolution = DynamicFood.ENGINE.resourceDifficulty(itemId);
            return resolution.score() == null ? 0.0D : resolution.score();
        }
        if (context.publishedGeneration().isEmpty()) {
            return 0.0D;
        }
        return context.publishedGeneration().orElseThrow().economicSnapshot().resource(itemId)
            .map(resource -> resource.economicResolution().difficulty())
            .map(score -> score == null ? 0.0D : score)
            .orElse(0.0D);
    }

    static Optional<CalibratedBaseFoodValue> calibratedBaseFoodValue(String itemId,
        RuntimeEconomicContext context) {
        if (context == null) {
            return DynamicFood.ENGINE.calibratedBaseFoodValue(itemId);
        }
        if (context.publishedGeneration().isEmpty()) {
            return Optional.empty();
        }
        var generation = context.publishedGeneration().orElseThrow();
        if (generation.calibrationSnapshot().status()
            == com.jorjik.dynamicfood.core.CalibrationStatus.DISABLED) {
            return Optional.empty();
        }
        var resource = generation.economicSnapshot().resource(itemId);
        var calibrated = generation.calibrationSnapshot().calibratedValues().get(itemId);
        if (resource.isEmpty() || calibrated == null || !resource.get().economicCost().isKnown()) {
            return Optional.empty();
        }
        double economicCost = resource.get().economicCost().value();
        if (Double.compare(economicCost, calibrated.economicCost()) != 0) {
            throw new IllegalStateException("published calibration cost does not match its economic snapshot for "
                + itemId);
        }
        double difficulty = DynamicFoodConfig.itemDifficulty(itemId,
            resource.get().economicResolution().difficulty() == null
                ? 0.0D : resource.get().economicResolution().difficulty());
        var calibration = generation.calibrationSnapshot();
        return Optional.of(new CalibratedBaseFoodValue(
            calibration.hungerCurve().evaluate(calibrated.foodIndex()),
            calibration.saturationCurve().evaluate(calibrated.foodIndex()), calibrated.foodIndex(),
            economicCost, difficulty,
            calibration.status(), "economic_snapshot:" + generation.generation()));
    }

    private static boolean usesConfiguredDisabledFallback(RuntimeEconomicContext context) {
        return context != null && context.publishedGeneration()
            .map(generation -> generation.calibrationSnapshot().status()
                == com.jorjik.dynamicfood.core.CalibrationStatus.DISABLED)
            .orElse(false)
            && DynamicFoodConfig.calibrationSettings().disabledMode().equals("configured_fallback");
    }

    private static IngredientContribution contribution(String itemId, CalibratedBaseFoodValue value,
        int count, boolean foodComponent) {
        return new IngredientContribution(itemId, value.nutrition(), value.effectiveSaturation(), count,
            foodComponent, value.source(), value.difficulty());
    }

    public static Optional<ItemFoodProfile> profile(String itemId) {
        for (String entry : DynamicFoodConfig.strings(DynamicFoodConfig.ITEM_PROFILES, List.of())) {
            ItemFoodProfile profile = ItemFoodProfile.parse(entry);
            if (profile != null && profile.itemId().equals(itemId)) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    private static boolean contains(Iterable<? extends String> values, String expected) {
        for (String value : values) {
            if (expected.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasConfiguredFoodTag(ItemStack stack) {
        for (String tagId : DynamicFoodConfig.strings(DynamicFoodConfig.FOOD_COMPONENT_TAGS, List.of())) {
            if (hasTag(stack, tagId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTag(ItemStack stack, String tagId) {
        ResourceLocation location = ResourceLocation.tryParse(tagId);
        return location != null && stack.is(TagKey.create(Registries.ITEM, location));
    }
}
