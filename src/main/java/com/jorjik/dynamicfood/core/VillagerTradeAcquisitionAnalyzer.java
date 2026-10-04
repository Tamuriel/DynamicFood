package com.jorjik.dynamicfood.core;

import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.npc.VillagerTrades.ItemListing;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.neoforge.common.BasicItemListing;

public final class VillagerTradeAcquisitionAnalyzer implements AcquisitionAnalyzer {
    private static final Field VANILLA_FIXED_OUTPUT = findVanillaFixedOutputField();
    private static final Field VANILLA_EMERALD_COST = findVanillaField("emeraldCost");
    private static final Field VANILLA_MAX_USES = findVanillaField("maxUses");
    private static final Field VANILLA_PRICE_MULTIPLIER = findVanillaField("priceMultiplier");

    private final Map<String, List<TradeOutput>> outputsByProfession;
    private final Map<String, List<TradeOutput>> outputsByItem;
    private final int unindexedListingCount;

    private VillagerTradeAcquisitionAnalyzer(Map<String, List<TradeOutput>> outputsByProfession) {
        Map<String, List<TradeOutput>> professionSnapshot = new TreeMap<>();
        outputsByProfession.forEach((profession, outputs) ->
            professionSnapshot.put(profession, List.copyOf(outputs)));
        this.outputsByProfession = Map.copyOf(professionSnapshot);

        Map<String, List<TradeOutput>> byItem = new HashMap<>();
        int unindexed = 0;
        for (List<TradeOutput> outputs : professionSnapshot.values()) {
            for (TradeOutput output : outputs) {
                if (output.itemId() == null) {
                    unindexed++;
                } else {
                    byItem.computeIfAbsent(output.itemId(), ignored -> new ArrayList<>()).add(output);
                }
            }
        }
        byItem.replaceAll((ignored, outputs) -> outputs.stream()
            .sorted(Comparator.comparing(TradeOutput::sourceId)).toList());
        this.outputsByItem = Map.copyOf(byItem);
        this.unindexedListingCount = unindexed;
    }

    public static VillagerTradeAcquisitionAnalyzer empty() {
        return new VillagerTradeAcquisitionAnalyzer(Map.of());
    }

    public VillagerTradeAcquisitionAnalyzer withProfession(String professionId,
        Map<Integer, List<ItemListing>> trades) {
        if (professionId == null || professionId.isBlank() || trades == null) {
            throw new IllegalArgumentException("profession id and trade listings are required");
        }
        List<TradeOutput> outputs = new ArrayList<>();
        trades.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(levelEntry -> {
            List<ItemListing> listings = levelEntry.getValue();
            for (int index = 0; index < listings.size(); index++) {
                ItemListing listing = listings.get(index);
                String sourceId = professionId + "/level_" + levelEntry.getKey() + "/listing_" + index;
                Optional<FixedTrade> fixedTrade = fixedTrade(listing);
                if (fixedTrade.isEmpty()) {
                    outputs.add(TradeOutput.unindexed(sourceId, levelEntry.getKey()));
                    continue;
                }
                FixedTrade trade = fixedTrade.get();
                ItemStack stack = trade.output();
                if (stack.isEmpty()) {
                    outputs.add(TradeOutput.unindexed(sourceId, levelEntry.getKey()));
                    continue;
                }
                var itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (itemKey == null) {
                    DynamicFood.LOGGER.warn("Villager trade {} has an output item without a registry key",
                        sourceId);
                    outputs.add(TradeOutput.unindexed(sourceId, levelEntry.getKey()));
                    continue;
                }
                List<TradeInput> inputs = trade.inputs().stream()
                    .map(input -> itemInput(input, sourceId))
                    .filter(java.util.Objects::nonNull)
                    .toList();
                outputs.add(new TradeOutput(itemKey.toString(), sourceId, stack.getCount(), inputs,
                    levelEntry.getKey(), trade.maxUses(), trade.priceMultiplier()));
            }
        });

        Map<String, List<TradeOutput>> updated = new TreeMap<>(outputsByProfession);
        updated.put(professionId, List.copyOf(outputs));
        return new VillagerTradeAcquisitionAnalyzer(updated);
    }

    @Override
    public boolean supports(String itemId) {
        return outputsByItem.containsKey(itemId);
    }

    @Override
    public List<AcquisitionPath> analyze(String itemId) {
        List<TradeOutput> outputs = outputsByItem.get(itemId);
        if (outputs == null) {
            return List.of();
        }
        return outputs.stream().map(output -> {
            Map<String, EconomicFactor> costs = new HashMap<>();
            Map<Integer, CostVector> horizons = new HashMap<>();
            for (int horizon : supportedHorizons()) {
                EconomicFactor materialCost = materialCost(output);
                costs.clear();
                costs.put("quantity_cost", FactorNormalizer.quantityCostForHorizon(output.outputCount(),
                    horizon, DynamicFoodConfig.lootAttemptsReference(), DynamicFoodConfig.lootAttemptsCap()));
                costs.put("time_cost", EconomicFactor.unknown("trade data does not expose time per completed offer"));
                costs.put("prerequisite_cost", EconomicFactor.unknown(
                    "profession and workstation access costs are not resolved"));
                costs.put("progression_cost", output.level() == 1
                    ? EconomicFactor.unknown("profession access requirements are not resolved")
                    : EconomicFactor.unknown("villager level is known but its leveling costs are not resolved"));
                costs.put("equipment_cost", EconomicFactor.unknown(
                    "villager workstation acquisition cost is not resolved"));
                costs.put("danger_cost", EconomicFactor.unknown(
                    "villager location and protection requirements are not resolved"));
                costs.put("transport_cost", EconomicFactor.unknown(
                    "villager and input travel distance are not resolved"));
                costs.put("intermediate_cost", EconomicFactor.notApplicable(
                    "trade input economics are represented by material_cost"));
                costs.put("resource_consumption_cost", EconomicFactor.notApplicable(
                    "consumed trade input quantities are represented by material_cost"));
                costs.put("material_cost", materialCost);
                horizons.put(horizon, new CostVector(horizon, costs));
            }
            EconomicFactor quantity = FactorNormalizer.quantityCostForHorizon(output.outputCount(), 1,
                DynamicFoodConfig.lootAttemptsReference(), DynamicFoodConfig.lootAttemptsCap());
            Map<String, AcquisitionMeasurement> evidence = new HashMap<>(Map.of(
                "output_quantity_per_completed_offer", AcquisitionMeasurement.exact(output.outputCount()),
                "expected_units_per_attempt", AcquisitionMeasurement.exact(output.outputCount()),
                "expected_attempts_per_unit", AcquisitionMeasurement.exact(1.0D / output.outputCount()),
                "offer_selection_probability", AcquisitionMeasurement.known(1.0D),
                "maximum_uses", AcquisitionMeasurement.known(output.maxUses()),
                "villager_level", AcquisitionMeasurement.known(output.level()),
                "canonical_quantity_normalized_at_unit_horizon", AcquisitionMeasurement.known(quantity.value())
            ));
            for (TradeInput input : output.inputs()) {
                evidence.put("input_quantity:" + input.itemId(), AcquisitionMeasurement.known(input.quantity()));
                evidence.put("input_economic_cost:" + input.itemId(), AcquisitionMeasurement.unknown(
                    "no compatible recursively resolved EconomicCost is available for this trade input"));
            }
            evidence.put("restock_dependency", AcquisitionMeasurement.unknown(
                "maximum uses are known, but workstation access and restock completion are not resolved"));
            evidence.put("required_villager_level", AcquisitionMeasurement.known(output.level()));
            evidence.put("progression_gate", AcquisitionMeasurement.unknown(
                "the required level is recorded, but villager leveling inputs and progression are unresolved"));
            for (int horizon : supportedHorizons()) {
                evidence.put("expected_successful_offers_to_obtain_" + horizon,
                    AcquisitionMeasurement.exact(horizon / (double) output.outputCount()));
            }
            return new AcquisitionPath(itemId, "villager_trade", output.sourceId(), 1.0D,
                null, null, null, false,
                Map.ofEntries(
                    Map.entry("probability", EconomicFactor.notApplicable(
                        "the player chooses the available listed trade")),
                    Map.entry("expected_yield", EconomicFactor.notApplicable(
                        "deterministic trade output quantity is represented by canonical quantity")),
                    Map.entry("repeatability", output.maxUses() > 0
                        ? EconomicFactor.known(1.0D)
                        : EconomicFactor.unknown("trade offer has no positive maximum-use limit")),
                    Map.entry("renewability", EconomicFactor.unknown(
                        "input-resource acquisition and villager restock conditions are not fully resolved")),
                    Map.entry("startup_cost", EconomicFactor.notApplicable("trade has no separate startup input")),
                    Map.entry("recurring_cost", EconomicFactor.notApplicable(
                        "repeated trade inputs are represented by material economics")),
                    Map.entry("prerequisite_cost", EconomicFactor.unknown(
                        "profession and workstation access costs are not resolved")),
                    Map.entry("processing_requirements", EconomicFactor.notApplicable(
                        "trade completion is not a recipe-processing operation")),
                    Map.entry("progression_requirement", EconomicFactor.unknown(
                        "villager level is recorded but progression costs are not resolved")),
                    Map.entry("danger", EconomicFactor.unknown(
                        "villager location and protection requirements are not resolved")),
                    Map.entry("resource_consumption", output.inputs().isEmpty()
                        ? EconomicFactor.unknown("fixed trade inputs are unavailable")
                        : EconomicFactor.known(1.0D)),
                    Map.entry("intermediate_steps", EconomicFactor.notApplicable(
                        "trade input economics are direct and do not contain a recipe chain here")),
                    Map.entry("equipment_availability", EconomicFactor.unknown(
                        "villager workstation availability is not resolved")),
                    Map.entry("reliability", EconomicFactor.known(1.0D))
                ), horizons, new AcquisitionEvidence(evidence, tradeAttributes(output)));
        }).toList();
    }

    private EconomicFactor materialCost(TradeOutput output) {
        return EconomicFactor.unknown(output.inputs().isEmpty()
            ? "fixed trade inputs are unavailable"
            : "trade input economics cannot be recursively resolved to compatible EconomicCost values");
    }

    private static Map<String, String> tradeAttributes(TradeOutput output) {
        Map<String, String> attributes = new TreeMap<>();
        attributes.put("trade_listing", output.sourceId());
        attributes.put("profession", output.sourceId().substring(0, output.sourceId().indexOf("/level_")));
        attributes.put("villager_level", Integer.toString(output.level()));
        attributes.put("maximum_uses", Integer.toString(output.maxUses()));
        attributes.put("input_quantities", output.inputs().toString());
        attributes.put("price_model", "base listing price; demand, reputation, and temporary discounts excluded");
        attributes.put("canonical_quantity_source", "output quantity per player-selected completed offer");
        attributes.put("canonical_quantity_unit", "item per completed trade offer");
        attributes.put("canonical_quantity_semantics", "guaranteed listing output per completed offer");
        attributes.put("survival_availability",
            "unknown: registered listing does not prove profession access, villager availability, or restocking");
        attributes.put("source_availability_classification", "UNKNOWN");
        if (output.priceMultiplier() != null) {
            attributes.put("dynamic_price_multiplier", Float.toString(output.priceMultiplier()));
        }
        return Map.copyOf(attributes);
    }

    public int unindexedListingCount() {
        return unindexedListingCount;
    }

    public Map<String, Integer> sourceCounts() {
        int count = outputsByProfession.values().stream().mapToInt(List::size).sum();
        return count == 0 ? Map.of() : Map.of("villager_trade", count);
    }

    @Override
    public java.util.Set<String> indexedItemIds() {
        return outputsByItem.keySet();
    }

    private static List<Integer> supportedHorizons() {
        return java.util.stream.Stream.of(1, 10, 100,
                DynamicFoodConfig.acquisitionEconomicHorizon())
            .distinct().sorted().toList();
    }

    private static Optional<FixedTrade> fixedTrade(ItemListing listing) {
        if (listing.getClass() == BasicItemListing.class) {
            MerchantOffer offer = listing.getOffer(null, RandomSource.create(0L));
            if (offer == null) {
                return Optional.empty();
            }
            List<ItemStack> inputs = new ArrayList<>();
            inputs.add(offer.getBaseCostA());
            ItemStack second = offer.getCostB();
            if (!second.isEmpty()) {
                inputs.add(second);
            }
            return Optional.of(new FixedTrade(offer.getResult().copy(), inputs,
                offer.getMaxUses(), offer.getPriceMultiplier()));
        }
        if (listing.getClass() == VillagerTrades.ItemsForEmeralds.class
            && VANILLA_FIXED_OUTPUT != null && VANILLA_EMERALD_COST != null
            && VANILLA_MAX_USES != null && VANILLA_PRICE_MULTIPLIER != null) {
            try {
                Object value = VANILLA_FIXED_OUTPUT.get(listing);
                Object emeraldCost = VANILLA_EMERALD_COST.get(listing);
                Object maxUses = VANILLA_MAX_USES.get(listing);
                Object priceMultiplier = VANILLA_PRICE_MULTIPLIER.get(listing);
                if (value instanceof ItemStack stack && emeraldCost instanceof Integer price && price > 0
                    && maxUses instanceof Integer uses && priceMultiplier instanceof Float multiplier) {
                    return Optional.of(new FixedTrade(stack.copy(),
                        List.of(new ItemStack(Items.EMERALD, price)), uses, multiplier));
                }
            } catch (IllegalAccessException exception) {
                DynamicFood.LOGGER.warn("Could not read the verified 1.21.1 fixed villager trade data", exception);
            }
        }
        return Optional.empty();
    }

    private static Field findVanillaFixedOutputField() {
        return findVanillaField("itemStack");
    }

    private static Field findVanillaField(String name) {
        try {
            Field field = VillagerTrades.ItemsForEmeralds.class.getDeclaredField(name);
            if (!field.trySetAccessible()) {
                throw new IllegalAccessException("VillagerTrades.ItemsForEmeralds." + name + " is not accessible");
            }
            return field;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            DynamicFood.LOGGER.warn("Could not access verified 1.21.1 ItemsForEmeralds field {}", name, exception);
            return null;
        }
    }

    private static TradeInput itemInput(ItemStack stack, String sourceId) {
        var itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemKey == null || stack.isEmpty() || stack.getCount() <= 0) {
            DynamicFood.LOGGER.warn("Villager trade {} has an invalid input stack", sourceId);
            return null;
        }
        return new TradeInput(itemKey.toString(), stack.getCount());
    }

    private record FixedTrade(ItemStack output, List<ItemStack> inputs, int maxUses, float priceMultiplier) {
    }

    private record TradeInput(String itemId, int quantity) {
        private TradeInput {
            if (itemId == null || quantity <= 0) {
                throw new IllegalArgumentException("trade inputs require an item id and positive quantity");
            }
        }
    }

    private record TradeOutput(String itemId, String sourceId, int outputCount, List<TradeInput> inputs,
        int level, int maxUses, Float priceMultiplier) {
        private TradeOutput {
            inputs = List.copyOf(inputs);
        }

        private static TradeOutput unindexed(String sourceId, int level) {
            return new TradeOutput(null, sourceId, 0, List.of(), level, 0, null);
        }
    }
}
