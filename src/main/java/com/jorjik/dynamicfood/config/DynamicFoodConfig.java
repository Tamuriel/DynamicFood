package com.jorjik.dynamicfood.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.core.CalibrationAnchor;
import com.jorjik.dynamicfood.core.FoodCalibrationSettings;
import com.jorjik.dynamicfood.core.FoodValueCurve;
import com.jorjik.dynamicfood.core.ResourceEconomicProfile;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DynamicFoodConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FOOD_CALIBRATION_ENABLED = BUILDER
        .comment("Enable self-calibrated FoodIndex for resources with known economic profiles.")
        .define("food_calibration.enabled", true);

    public static final ModConfigSpec.ConfigValue<String> FOOD_CALIBRATION_POPULATION_SCOPE = BUILDER
        .comment("Calibration population: all_survival_economic_resources, configured_tag, or manual.")
        .define("food_calibration.population_scope", "all_survival_economic_resources",
            value -> value instanceof String text && List.of(
                "all_survival_economic_resources", "configured_tag", "manual").contains(text));

    public static final ModConfigSpec.ConfigValue<List<? extends String>> FOOD_CALIBRATION_POPULATION_TAGS = BUILDER
        .comment("Item tags used when population_scope is configured_tag.")
        .defineListAllowEmpty("food_calibration.population_tags", List.of(), value -> value instanceof String);

    public static final ModConfigSpec.ConfigValue<String> FOOD_CALIBRATION_DISABLED_MODE = BUILDER
        .comment("Fallback when food calibration is disabled: vanilla or configured_fallback.")
        .define("food_calibration.disabled_mode", "vanilla",
            value -> value instanceof String text && List.of("vanilla", "configured_fallback").contains(text));

    public static final ModConfigSpec.IntValue CALIBRATION_MIN_POPULATION = BUILDER
        .comment("Minimum number of unique resolved economic resources before rank-only fallback is used.")
        .defineInRange("food_calibration.min_population", 16, 1, 1000000);

    public static final ModConfigSpec.DoubleValue CALIBRATION_MAGNITUDE_WEIGHT = BUILDER
        .comment("Weight of robust normalized log-economic-cost magnitude. Must sum to one with rank_weight.")
        .defineInRange("food_calibration.magnitude_weight", 0.70D, 0.0D, 1.0D);

    public static final ModConfigSpec.DoubleValue CALIBRATION_RANK_WEIGHT = BUILDER
        .comment("Weight of weighted normalized economic rank. Must sum to one with magnitude_weight.")
        .defineInRange("food_calibration.rank_weight", 0.30D, 0.0D, 1.0D);

    public static final ModConfigSpec.DoubleValue CALIBRATION_LOWER_QUANTILE = BUILDER
        .defineInRange("food_calibration.lower_quantile", 0.05D, 0.000001D, 0.999998D);

    public static final ModConfigSpec.DoubleValue CALIBRATION_UPPER_QUANTILE = BUILDER
        .defineInRange("food_calibration.upper_quantile", 0.95D, 0.000002D, 0.999999D);

    public static final ModConfigSpec.DoubleValue CALIBRATION_COVERAGE_LOW_THRESHOLD = BUILDER
        .defineInRange("food_calibration.coverage_low_threshold", 0.50D, 0.0D, 1.0D);

    public static final ModConfigSpec.DoubleValue CALIBRATION_COVERAGE_MEDIUM_THRESHOLD = BUILDER
        .defineInRange("food_calibration.coverage_medium_threshold", 0.80D, 0.0D, 1.0D);

    public static final ModConfigSpec.ConfigValue<String> FOOD_GAMEPLAY_PRESET = BUILDER
        .define("food_calibration.gameplay_preset", "medium",
            value -> value instanceof String text && List.of("easy", "medium", "hard", "very_hard").contains(text));

    public static final ModConfigSpec.ConfigValue<List<? extends String>> CUSTOM_HUNGER_ANCHORS = BUILDER
        .comment("FoodIndex hunger anchors use x|nutrition; empty inherits the selected preset.")
        .defineListAllowEmpty("food_calibration.custom_hunger_anchors", List.of(), DynamicFoodConfig::isAnchorEntry);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> CUSTOM_SATURATION_ANCHORS = BUILDER
        .comment("FoodIndex effective saturation anchors use x|points; empty inherits the selected preset.")
        .defineListAllowEmpty("food_calibration.custom_saturation_anchors", List.of(), DynamicFoodConfig::isAnchorEntry);

    public static final ModConfigSpec.DoubleValue CALIBRATION_FALLBACK_NUTRITION = BUILDER
        .defineInRange("food_calibration.configured_fallback_nutrition", 2.0D, 0.0D, 1000.0D);

    public static final ModConfigSpec.DoubleValue CALIBRATION_FALLBACK_SATURATION = BUILDER
        .defineInRange("food_calibration.configured_fallback_saturation", 0.0D, 0.0D, 1000.0D);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ECONOMIC_RESOURCE_OVERRIDES = BUILDER
        .comment("Explicit economic profiles: item_id|economic_cost>=0|weight>0|group-or--|TRUE/FALSE/UNKNOWN survival availability.")
        .defineListAllowEmpty("food_calibration.economic_resources", List.of(), value -> value instanceof String);

    public static final ModConfigSpec.DoubleValue MATERIAL_COST_REFERENCE = BUILDER
        .defineInRange("acquisition.normalization.material.reference_cost", 1.0D, 0.000001D, 1.0E12D);

    public static final ModConfigSpec.DoubleValue MATERIAL_COST_CAP = BUILDER
        .defineInRange("acquisition.normalization.material.cap_cost", 100.0D, 0.000001D, 1.0E12D);

    public static final ModConfigSpec.DoubleValue LOOT_ATTEMPTS_REFERENCE = BUILDER
        .defineInRange("acquisition.normalization.quantity.attempts_reference", 1.0D, 0.000001D, 1.0E12D);

    public static final ModConfigSpec.DoubleValue LOOT_ATTEMPTS_CAP = BUILDER
        .defineInRange("acquisition.normalization.quantity.attempts_cap", 10000.0D, 0.000001D, 1.0E12D);

    public static final ModConfigSpec.DoubleValue TIME_COST_REFERENCE_TICKS = BUILDER
        .comment("Reference expected processing ticks per output unit for normalized recipe time cost.")
        .defineInRange("acquisition.normalization.time.reference_ticks", 200.0D, 0.000001D, 1.0E12D);

    public static final ModConfigSpec.DoubleValue TIME_COST_CAP_TICKS = BUILDER
        .comment("Expected processing ticks per output unit normalized to the maximum time cost.")
        .defineInRange("acquisition.normalization.time.cap_ticks", 72000.0D, 0.000001D, 1.0E12D);

    public static final ModConfigSpec.ConfigValue<String> ACQUISITION_PRIMARY_PATH_STRATEGY = BUILDER
        .comment("best_repeatable_cost, weighted_average, minimum_feasible or median_feasible.")
        .define("acquisition.primary_path_strategy", "best_repeatable_cost",
            value -> value instanceof String text && List.of("best_repeatable_cost", "weighted_average",
                "minimum_feasible", "median_feasible").contains(text));

    public static final ModConfigSpec.IntValue ACQUISITION_ECONOMIC_HORIZON = BUILDER
        .defineInRange("acquisition.economic_horizon", 100, 1, 1000000);

    public static final ModConfigSpec.DoubleValue ACQUISITION_MINIMUM_FEASIBILITY = BUILDER
        .defineInRange("acquisition.minimum_feasibility_threshold", 0.10D, 0.0D, 1.0D);

    public static final ModConfigSpec.DoubleValue ACQUISITION_MINIMUM_FEASIBILITY_COVERAGE = BUILDER
        .defineInRange("acquisition.feasibility.min_coverage", 0.80D, 0.0D, 1.0D);

    public static final ModConfigSpec.BooleanValue ACQUISITION_ALLOW_PARTIAL_FEASIBILITY = BUILDER
        .define("acquisition.feasibility.allow_partial", false);

    public static final ModConfigSpec.BooleanValue ACQUISITION_ALLOW_PARTIAL_COST = BUILDER
        .define("acquisition.cost.allow_partial", false);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> FEASIBILITY_FACTOR_WEIGHTS = BUILDER
        .comment("Factor weights use factor|weight. Unknown factors reduce coverage.")
        .defineList("acquisition.feasibility.factor_weights", List.of(
            "probability|1", "expected_yield|1", "repeatability|1", "renewability|1",
            "startup_cost|1", "recurring_cost|1", "prerequisite_cost|1",
            "processing_requirements|1", "progression_requirement|1", "danger|1",
            "resource_consumption|1", "intermediate_steps|1", "equipment_availability|1",
            "reliability|1"), value -> value instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> COST_FACTOR_WEIGHTS = BUILDER
        .comment("Canonical cost factor weights use factor|weight.")
        .defineList("acquisition.cost.factor_weights", List.of(
            "quantity_cost|1", "time_cost|1", "startup_cost|1", "recurring_cost|1",
            "prerequisite_cost|1", "progression_cost|1", "equipment_cost|1",
            "danger_cost|1", "transport_cost|1", "intermediate_cost|1",
            "resource_consumption_cost|1", "material_cost|1"), value -> value instanceof String);

    public static final ModConfigSpec.ConfigValue<String> OUTPUT_ALLOCATION_MODE = BUILDER
        .define("processing.output_allocation", "quantity",
            value -> value instanceof String text && List.of("quantity", "weighted").contains(text));

    public static final ModConfigSpec.ConfigValue<String> DETERMINISTIC_FALLBACK_MODE = BUILDER
        .define("fallback.deterministic_mode", "lexicographic",
            value -> value instanceof String text && List.of("lexicographic", "lowest_cost").contains(text));

    public static final ModConfigSpec.ConfigValue<List<? extends String>> FLUID_FOOD_PROFILES = BUILDER
        .comment("Fluid food profiles use fluid_id|nutrition|effective_saturation|value_unit_mb|food_component|enabled.")
        .defineListAllowEmpty("fluid_ingredients.profiles", List.of(),
            value -> value instanceof String text && FluidFoodProfile.parse(text) != null);

    public static final ModConfigSpec.DoubleValue MAX_PROCESSING_NUTRITION_BONUS = BUILDER
        .comment("Maximum total nutrition bonus per recipe operation, in Minecraft hunger points.")
        .defineInRange("dynamics.max_processing_nutrition_bonus", 3.0D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue MAX_PROCESSING_SATURATION_BONUS = BUILDER
        .comment("Maximum total saturation bonus per recipe operation.")
        .defineInRange("dynamics.max_processing_saturation_bonus", 1.0D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue BASE_PROCESSING_NUTRITION_BONUS = BUILDER
        .comment("Ordinary processing bonus before station difficulty is applied.")
        .defineInRange("dynamics.base_processing_nutrition_bonus", 0.15D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue BASE_PROCESSING_SATURATION_BONUS = BUILDER
        .comment("Ordinary processing saturation bonus before station difficulty is applied.")
        .defineInRange("dynamics.base_processing_saturation_bonus", 0.05D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue STATION_NUTRITION_BONUS_PER_LEVEL = BUILDER
        .comment("Additional operation-wide nutrition bonus per station difficulty level.")
        .defineInRange("dynamics.station_nutrition_bonus_per_level", 0.15D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue STATION_SATURATION_BONUS_PER_LEVEL = BUILDER
        .comment("Additional operation-wide saturation bonus per station difficulty level.")
        .defineInRange("dynamics.station_saturation_bonus_per_level", 0.05D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue RECIPE_COMPLEXITY_NUTRITION_BONUS_PER_LEVEL = BUILDER
        .comment("Additional operation-wide nutrition bonus per automatically estimated recipe complexity level.")
        .defineInRange("dynamics.recipe_complexity_nutrition_bonus_per_level", 0.05D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue RECIPE_COMPLEXITY_SATURATION_BONUS_PER_LEVEL = BUILDER
        .comment("Additional operation-wide saturation bonus per automatically estimated recipe complexity level.")
        .defineInRange("dynamics.recipe_complexity_saturation_bonus_per_level", 0.02D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue MAX_AUTOMATIC_SATURATION = BUILDER
        .comment("Maximum automatically-created saturation. Inherited component saturation is never reduced.")
        .defineInRange("dynamics.max_automatic_saturation", 20.0D, 0.0D, 1000.0D);

    public static final ModConfigSpec.DoubleValue MAX_AUTOMATIC_NUTRITION = BUILDER
        .comment("Maximum automatically-created nutrition. Inherited component nutrition is never reduced.")
        .defineInRange("dynamics.max_automatic_nutrition", 18.0D, 0.0D, 1000.0D);

    public static final ModConfigSpec.DoubleValue MAX_SATURATION = BUILDER
        .comment("Absolute effective saturation cap for automatically-created values. Inherited components are preserved.")
        .defineInRange("dynamics.max_saturation", 20.0D, 0.0D, 1000.0D);

    public static final ModConfigSpec.DoubleValue MAX_HUNGER = BUILDER
        .comment("Nutrition cap for automatically-created values. Inherited components are preserved.")
        .defineInRange("dynamics.max_hunger", 18.0D, 0.0D, 1000.0D);

    public static final ModConfigSpec.ConfigValue<String> SATURATION_OVERFLOW_MODE = BUILDER
        .comment("preserve_components keeps inherited saturation above the limit; normalize_components scales it down.")
        .define("dynamics.saturation_overflow_mode", "preserve_components",
            value -> value instanceof String text && List.of("preserve_components", "normalize_components").contains(text));

    public static final ModConfigSpec.BooleanValue ZERO_HUNGER_FOOD_ENABLED = BUILDER
        .comment("Allow configured zero-hunger ingredients to contribute nutrition to recipes.")
        .define("zero_hunger_food.enabled", true);

    public static final ModConfigSpec.DoubleValue ZERO_HUNGER_DEFAULT_CONTRIBUTION = BUILDER
        .comment("Fallback nutrition contribution for unprofiled zero-hunger edible ingredients.")
        .defineInRange("zero_hunger_food.default_contribution", 0.25D, 0.0D, 10.0D);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_PROFILES = BUILDER
        .comment("Entries use item_id|nutrition|saturation|difficulty|food_component|enabled. Values are Minecraft nutrition/saturation points; -1 uses the calibrated value.")
        .defineList("ingredients.profiles", List.of(
            "minecraft:wheat|2.0|0.4|1|true|true",
            "minecraft:sugar|0.25|0.1|1|true|true",
            "minecraft:cocoa_beans|0.75|0.2|3|true|true"
        ), value -> value instanceof String text && ItemFoodProfile.parse(text) != null);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> FOOD_COMPONENT_TAGS = BUILDER
        .comment("Item tags that mark non-edible ingredient items as food components.")
        .defineListAllowEmpty("ingredients.food_component_tags", List.of(
            "c:foods", "forge:foods", "c:foods/raw", "c:foods/cooked", "c:foods/fruit", "c:foods/fruits",
            "c:foods/vegetable", "c:foods/vegetables", "c:foods/leafy_green", "c:foods/bread",
            "c:foods/dough", "c:foods/doughs", "c:foods/raw_meats", "c:foods/cooked_meats",
            "c:foods/raw_meats/raw_beef", "c:foods/raw_meats/raw_chicken", "c:foods/raw_meats/raw_mutton",
            "c:foods/raw_meats/raw_pork", "c:foods/raw_meats/raw_rabbit", "c:foods/raw_fish",
            "c:foods/cooked_fish", "c:foods/edible_when_placed",
            "c:meats", "c:fish", "farmersdelight:meals", "farmersdelight:feasts"
        ), value -> value instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLACKLISTED_ITEMS = BUILDER
        .comment("Item IDs that must never contribute to food values.")
        .defineListAllowEmpty("blacklist.items", List.of(
            "minecraft:bowl", "minecraft:glass_bottle", "minecraft:stick", "minecraft:bucket", "minecraft:water_bucket"
        ), value -> value instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> RECIPE_TYPE_WHITELIST = BUILDER
        .comment("Empty means all recipe types are allowed; otherwise only listed type IDs are analyzed.")
        .defineListAllowEmpty("recipe_types.whitelist", List.of(), value -> value instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> RECIPE_TYPE_BLACKLIST = BUILDER
        .comment("Recipe type IDs excluded from static analysis and runtime bonuses.")
        .defineListAllowEmpty("recipe_types.blacklist", List.of(), value -> value instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> RECIPE_DIFFICULTY_OVERRIDES = BUILDER
        .comment("Recipe overrides use recipe_id|difficulty where difficulty is in [0,5].")
        .defineListAllowEmpty("difficulty.recipe_overrides", List.of(), value -> value instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_DIFFICULTY_OVERRIDES = BUILDER
        .comment("Item overrides use item_id|difficulty where difficulty is in [0,5].")
        .defineListAllowEmpty("difficulty.item_overrides", List.of(), value -> value instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> STATION_DIFFICULTY_OVERRIDES = BUILDER
        .comment("Station overrides use recipe_type_id|difficulty where difficulty is in [0,5].")
        .defineListAllowEmpty("difficulty.station_overrides", List.of(), value -> value instanceof String);

    public static final ModConfigSpec.BooleanValue DEBUG = BUILDER
        .comment("Enable dynamic food diagnostic logging.")
        .define("debug.enabled", false);

    public static final ModConfigSpec.BooleanValue OVERRIDE_EXISTING_FOOD = BUILDER
        .comment("Whether dynamic nutrition may replace existing FoodProperties nutrition and saturation.")
        .define("dynamics.override_existing_food", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private DynamicFoodConfig() {}

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC);
    }

    public static FoodCalibrationSettings calibrationSettings() {
        double magnitudeWeight = number(CALIBRATION_MAGNITUDE_WEIGHT, 0.70D);
        double rankWeight = number(CALIBRATION_RANK_WEIGHT, 0.30D);
        if (Math.abs(magnitudeWeight + rankWeight - 1.0D) > 1.0E-9D) {
            DynamicFood.LOGGER.warn("Food calibration weights must sum to 1.0; using defaults 0.70/0.30");
            magnitudeWeight = 0.70D;
            rankWeight = 0.30D;
        }
        double lower = number(CALIBRATION_LOWER_QUANTILE, 0.05D);
        double upper = number(CALIBRATION_UPPER_QUANTILE, 0.95D);
        if (lower >= upper) {
            DynamicFood.LOGGER.warn("Food calibration quantiles are invalid; using defaults 0.05/0.95");
            lower = 0.05D;
            upper = 0.95D;
        }
        double lowCoverage = number(CALIBRATION_COVERAGE_LOW_THRESHOLD, 0.50D);
        double mediumCoverage = number(CALIBRATION_COVERAGE_MEDIUM_THRESHOLD, 0.80D);
        if (lowCoverage > mediumCoverage) {
            DynamicFood.LOGGER.warn("Food calibration coverage thresholds are invalid; using defaults 0.50/0.80");
            lowCoverage = 0.50D;
            mediumCoverage = 0.80D;
        }
        return new FoodCalibrationSettings(flag(FOOD_CALIBRATION_ENABLED, true),
            text(FOOD_CALIBRATION_DISABLED_MODE, "vanilla"),
            integer(CALIBRATION_MIN_POPULATION, 16), magnitudeWeight, rankWeight, lower, upper,
            lowCoverage, mediumCoverage, text(FOOD_GAMEPLAY_PRESET, "medium"),
            parseAnchors(strings(CUSTOM_HUNGER_ANCHORS, List.of()), "hunger"),
            parseAnchors(strings(CUSTOM_SATURATION_ANCHORS, List.of()), "saturation"),
            number(CALIBRATION_FALLBACK_NUTRITION, 2.0D), number(CALIBRATION_FALLBACK_SATURATION, 0.0D),
            text(FOOD_CALIBRATION_POPULATION_SCOPE, "all_survival_economic_resources"),
            strings(FOOD_CALIBRATION_POPULATION_TAGS, List.of()).stream().map(String::valueOf).toList());
    }

    public static List<ResourceEconomicProfile> economicProfiles() {
        return strings(ECONOMIC_RESOURCE_OVERRIDES, List.of()).stream().map(entry -> {
            EconomicProfileOverride parsed = EconomicProfileOverride.parse(entry);
            if (parsed == null) {
                DynamicFood.LOGGER.warn("Ignoring invalid food calibration economic profile: {}", entry);
                return null;
            }
            return parsed.toProfile();
        }).filter(java.util.Objects::nonNull).toList();
    }

    public static double materialCostReference() {
        return number(MATERIAL_COST_REFERENCE, 1.0D);
    }

    public static double materialCostCap() {
        return number(MATERIAL_COST_CAP, 100.0D);
    }

    public static double lootAttemptsReference() {
        return number(LOOT_ATTEMPTS_REFERENCE, 1.0D);
    }

    public static double lootAttemptsCap() {
        return number(LOOT_ATTEMPTS_CAP, 10000.0D);
    }

    public static double timeCostReferenceTicks() {
        return number(TIME_COST_REFERENCE_TICKS, 200.0D);
    }

    public static double timeCostCapTicks() {
        return number(TIME_COST_CAP_TICKS, 72000.0D);
    }

    public static com.jorjik.dynamicfood.core.PrimaryPathStrategy acquisitionStrategy() {
        return com.jorjik.dynamicfood.core.PrimaryPathStrategy.parse(
            text(ACQUISITION_PRIMARY_PATH_STRATEGY, "best_repeatable_cost"));
    }

    public static int acquisitionEconomicHorizon() {
        return integer(ACQUISITION_ECONOMIC_HORIZON, 100);
    }

    public static double minimumFeasibility() {
        return number(ACQUISITION_MINIMUM_FEASIBILITY, 0.10D);
    }

    public static double minimumFeasibilityCoverage() {
        return number(ACQUISITION_MINIMUM_FEASIBILITY_COVERAGE, 0.80D);
    }

    public static boolean allowPartialFeasibility() {
        return flag(ACQUISITION_ALLOW_PARTIAL_FEASIBILITY, false);
    }

    public static boolean allowPartialCost() {
        return flag(ACQUISITION_ALLOW_PARTIAL_COST, false);
    }

    public static Map<String, Double> feasibilityFactorWeights() {
        return parseWeights(strings(FEASIBILITY_FACTOR_WEIGHTS, List.of()), "feasibility");
    }

    public static Map<String, Double> costFactorWeights() {
        return parseWeights(strings(COST_FACTOR_WEIGHTS, List.of()), "cost");
    }

    public static String outputAllocationMode() {
        return text(OUTPUT_ALLOCATION_MODE, "quantity");
    }

    public static String deterministicFallbackMode() {
        return text(DETERMINISTIC_FALLBACK_MODE, "lexicographic");
    }

    public static double maxAutomaticNutrition() {
        return number(MAX_HUNGER, number(MAX_AUTOMATIC_NUTRITION, 18.0D));
    }

    public static double maxAutomaticSaturation() {
        return number(MAX_SATURATION, number(MAX_AUTOMATIC_SATURATION, 20.0D));
    }

    public static java.util.Optional<FluidFoodProfile> fluidProfile(String fluidId) {
        return strings(FLUID_FOOD_PROFILES, List.of()).stream()
            .map(FluidFoodProfile::parse)
            .filter(java.util.Objects::nonNull)
            .filter(profile -> profile.fluidId().equals(fluidId))
            .findFirst();
    }

    public static boolean allowsRecipeType(String recipeType) {
        if (contains(strings(RECIPE_TYPE_BLACKLIST, List.of()), recipeType)) {
            return false;
        }
        List<? extends String> whitelist = strings(RECIPE_TYPE_WHITELIST, List.of());
        return whitelist.isEmpty() || contains(whitelist, recipeType);
    }

    public static double recipeDifficulty(String recipeId, double automatic) {
        double override = numericOverride(strings(RECIPE_DIFFICULTY_OVERRIDES, List.of()), recipeId, automatic);
        return Math.max(0.0D, Math.min(5.0D, override));
    }

    public static double itemDifficulty(String itemId, double automatic) {
        return itemDifficultyOverride(itemId).orElse(Math.max(0.0D, Math.min(5.0D, automatic)));
    }

    public static java.util.OptionalDouble itemDifficultyOverride(String itemId) {
        String prefix = itemId + "|";
        for (String entry : strings(ITEM_DIFFICULTY_OVERRIDES, List.of())) {
            if (!entry.startsWith(prefix)) {
                continue;
            }
            try {
                double value = Double.parseDouble(entry.substring(prefix.length()).trim());
                if (Double.isFinite(value) && value >= 0.0D && value <= 5.0D) {
                    return java.util.OptionalDouble.of(value);
                }
                DynamicFood.LOGGER.warn("Ignoring out-of-range item difficulty override for {}", itemId);
            } catch (NumberFormatException exception) {
                DynamicFood.LOGGER.warn("Ignoring invalid item difficulty override for {}", itemId, exception);
            }
            return java.util.OptionalDouble.empty();
        }
        return java.util.OptionalDouble.empty();
    }

    public static int stationDifficulty(String recipeType, int automatic) {
        double override = numericOverride(strings(STATION_DIFFICULTY_OVERRIDES, List.of()), recipeType, automatic);
        return (int) Math.round(Math.max(0.0D, Math.min(5.0D, override)));
    }

    private static double numericOverride(Iterable<? extends String> entries, String id, double fallback) {
        String prefix = id + "|";
        for (String entry : entries) {
            if (entry.startsWith(prefix)) {
                try {
                    return Double.parseDouble(entry.substring(prefix.length()).trim());
                } catch (NumberFormatException exception) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static Map<String, Double> parseWeights(Iterable<? extends String> entries, String label) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String entry : entries) {
            String[] parts = entry.split("\\|", -1);
            if (parts.length != 2 || parts[0].isBlank()) {
                DynamicFood.LOGGER.warn("Ignoring invalid {} acquisition factor weight: {}", label, entry);
                continue;
            }
            try {
                double weight = Double.parseDouble(parts[1].trim());
                if (!Double.isFinite(weight) || weight < 0.0D) {
                    throw new NumberFormatException("weight must be finite and non-negative");
                }
                result.put(parts[0].trim(), weight);
            } catch (NumberFormatException exception) {
                DynamicFood.LOGGER.warn("Ignoring invalid {} acquisition factor weight: {}", label, entry);
            }
        }
        return Map.copyOf(result);
    }

    public static double number(ModConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    public static boolean flag(ModConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    public static int integer(ModConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    public static String text(ModConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    public static List<? extends String> strings(
        ModConfigSpec.ConfigValue<List<? extends String>> value,
        List<? extends String> fallback
    ) {
        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    private static boolean contains(Iterable<? extends String> values, String expected) {
        for (String value : values) {
            if (expected.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnchorEntry(Object value) {
        if (!(value instanceof String entry)) {
            return false;
        }
        String[] parts = entry.split("\\|", -1);
        if (parts.length != 2) {
            return false;
        }
        try {
            return Double.isFinite(Double.parseDouble(parts[0].trim()))
                && Double.isFinite(Double.parseDouble(parts[1].trim()));
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static List<CalibrationAnchor> parseAnchors(List<? extends String> entries, String label) {
        if (entries.isEmpty()) {
            return List.of();
        }
        try {
            List<CalibrationAnchor> anchors = entries.stream().map(entry -> {
                String[] parts = entry.split("\\|", -1);
                return new CalibrationAnchor(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
            }).toList();
            new FoodValueCurve(anchors);
            return anchors;
        } catch (RuntimeException exception) {
            DynamicFood.LOGGER.warn("Ignoring invalid custom {} curve; selected preset will be used", label, exception);
            return List.of();
        }
    }
}
