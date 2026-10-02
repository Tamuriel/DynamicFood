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
import net.neoforged.neoforge.common.BasicItemListing;

public final class VillagerTradeAcquisitionAnalyzer implements AcquisitionAnalyzer {
    private static final Field VANILLA_FIXED_OUTPUT = findVanillaFixedOutputField();

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
                Optional<ItemStack> fixedOutput = fixedOutput(listing);
                if (fixedOutput.isEmpty()) {
                    outputs.add(new TradeOutput(null, professionId + "/level_" + levelEntry.getKey()
                        + "/listing_" + index, 0));
                    continue;
                }
                ItemStack stack = fixedOutput.get();
                if (stack.isEmpty()) {
                    outputs.add(new TradeOutput(null, professionId + "/level_" + levelEntry.getKey()
                        + "/listing_" + index, 0));
                    continue;
                }
                var itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (itemKey == null) {
                    DynamicFood.LOGGER.warn("Villager trade {} has an output item without a registry key",
                        professionId + "/level_" + levelEntry.getKey() + "/listing_" + index);
                    outputs.add(new TradeOutput(null, professionId + "/level_" + levelEntry.getKey()
                        + "/listing_" + index, 0));
                    continue;
                }
                outputs.add(new TradeOutput(itemKey.toString(), professionId + "/level_" + levelEntry.getKey()
                    + "/listing_" + index, stack.getCount()));
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
            EconomicFactor quantity = FactorNormalizer.quantityCost(output.outputCount(),
                DynamicFoodConfig.lootAttemptsReference(), DynamicFoodConfig.lootAttemptsCap());
            costs.put("quantity_cost", quantity);
            costs.put("probability_cost", EconomicFactor.unknown(
                "villager offer selection probability is not observed"));
            costs.put("yield_cost", EconomicFactor.unknown(
                "trade offer output count is known; offer selection and failed attempts are not modeled"));
            for (String factor : List.of("time_cost", "startup_cost", "recurring_cost",
                "prerequisite_cost", "progression_cost", "equipment_cost", "danger_cost", "transport_cost",
                "intermediate_cost", "resource_consumption_cost", "material_cost")) {
                costs.put(factor, EconomicFactor.unknown(
                    "trade selection probability, final price, and input resource cost are not resolved"));
            }
            costs.put("probability_cost", EconomicFactor.unknown(
                "villager offer selection probability is not observed"));
            costs.put("yield_cost", EconomicFactor.unknown(
                "trade output quantity is not enough to resolve the number of offers required"));
            Map<Integer, CostVector> horizons = new HashMap<>();
            for (int horizon : supportedHorizons()) {
                horizons.put(horizon, new CostVector(horizon, costs));
            }
            Map<String, AcquisitionMeasurement> evidence = new HashMap<>(Map.of(
                "output_quantity_per_completed_offer", AcquisitionMeasurement.known(output.outputCount()),
                "expected_units_per_attempt", AcquisitionMeasurement.known(output.outputCount()),
                "expected_attempts_per_unit", AcquisitionMeasurement.known(1.0D / output.outputCount()),
                "offer_selection_probability", AcquisitionMeasurement.unknown(
                    "villager offer selection and price availability are not observed")
            ));
            for (int horizon : supportedHorizons()) {
                evidence.put("expected_successful_offers_to_obtain_" + horizon,
                    AcquisitionMeasurement.known(horizon / (double) output.outputCount()));
            }
            return new AcquisitionPath(itemId, "villager_trade", output.sourceId(), 1.0D,
                null, null, null, false,
                Map.of(
                    "expected_yield", EconomicFactor.known(Math.min(1.0D, output.outputCount())),
                    "probability", EconomicFactor.unknown("villager offer selection is not modeled"),
                    "reliability", EconomicFactor.known(1.0D)
                ), horizons, new AcquisitionEvidence(evidence, Map.of("trade_listing", output.sourceId())));
        }).toList();
    }

    public int unindexedListingCount() {
        return unindexedListingCount;
    }

    public Map<String, Integer> sourceCounts() {
        int count = outputsByProfession.values().stream().mapToInt(List::size).sum();
        return count == 0 ? Map.of() : Map.of("villager_trade", count);
    }

    public java.util.Set<String> indexedItemIds() {
        return outputsByItem.keySet();
    }

    private static List<Integer> supportedHorizons() {
        return java.util.stream.Stream.of(1, 10, 100,
                DynamicFoodConfig.acquisitionEconomicHorizon())
            .distinct().sorted().toList();
    }

    private static Optional<ItemStack> fixedOutput(ItemListing listing) {
        if (listing.getClass() == BasicItemListing.class) {
            var offer = listing.getOffer(null, RandomSource.create(0L));
            return offer == null ? Optional.empty() : Optional.of(offer.getResult().copy());
        }
        if (listing.getClass() == VillagerTrades.ItemsForEmeralds.class && VANILLA_FIXED_OUTPUT != null) {
            try {
                Object value = VANILLA_FIXED_OUTPUT.get(listing);
                return value instanceof ItemStack stack ? Optional.of(stack.copy()) : Optional.empty();
            } catch (IllegalAccessException exception) {
                DynamicFood.LOGGER.warn("Could not read the verified 1.21.1 fixed villager trade output", exception);
            }
        }
        return Optional.empty();
    }

    private static Field findVanillaFixedOutputField() {
        try {
            Field field = VillagerTrades.ItemsForEmeralds.class.getDeclaredField("itemStack");
            if (!field.trySetAccessible()) {
                throw new IllegalAccessException("VillagerTrades.ItemsForEmeralds.itemStack is not accessible");
            }
            return field;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            DynamicFood.LOGGER.warn(
                "Could not access the verified 1.21.1 fixed output for vanilla villager item trades", exception);
            return null;
        }
    }

    private record TradeOutput(String itemId, String sourceId, int outputCount) {
    }
}
