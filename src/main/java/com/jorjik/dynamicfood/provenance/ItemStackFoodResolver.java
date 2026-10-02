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

    public static IngredientContribution resolve(ItemStack stack) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int count = stack.getCount();
        if (contains(DynamicFoodConfig.strings(DynamicFoodConfig.BLACKLISTED_ITEMS, List.of()), itemId)) {
            return new IngredientContribution(itemId, 0.0D, 0.0D, count, false, "blacklist");
        }

        boolean foodTag = hasConfiguredFoodTag(stack);
        var resourceDifficulty = DynamicFood.ENGINE.resourceDifficulty(itemId);
        double automaticDifficulty = resourceDifficulty.score() == null ? 0.0D : resourceDifficulty.score();
        Optional<ItemFoodProfile> configured = profile(itemId);
        if (configured.isPresent()) {
            ItemFoodProfile profile = configured.get();
            if (!profile.enabled()) {
                return new IngredientContribution(itemId, 0.0D, 0.0D, count, false, "disabled_profile");
            }
            double difficulty = DynamicFoodConfig.itemDifficulty(itemId, profile.difficulty());
            var calibrated = DynamicFood.ENGINE.calibratedBaseFoodValue(itemId);
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
                var calibrated = DynamicFood.ENGINE.calibratedBaseFoodValue(itemId);
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

        if (food.nutrition() == 0 && DynamicFoodConfig.flag(DynamicFoodConfig.ZERO_HUNGER_FOOD_ENABLED, true)) {
            var calibrated = DynamicFood.ENGINE.calibratedBaseFoodValue(itemId);
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
        var calibrated = DynamicFood.ENGINE.calibratedBaseFoodValue(itemId);
        if (calibrated.isPresent()) {
            return contribution(itemId, calibrated.get(), count, true);
        }
        return new IngredientContribution(itemId, food.nutrition(),
            SaturationConverter.foodPropertiesToEffective(food.nutrition(), food.saturation()), count,
            true, "minecraft:food_properties", DynamicFoodConfig.itemDifficulty(itemId, automaticDifficulty));
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
