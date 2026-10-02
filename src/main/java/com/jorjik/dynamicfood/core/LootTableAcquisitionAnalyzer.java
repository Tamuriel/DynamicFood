package com.jorjik.dynamicfood.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.CropBlock;

public final class LootTableAcquisitionAnalyzer implements AcquisitionAnalyzer {
    private final Map<String, List<LootSource>> sourcesByItem;
    private final double attemptsReference;
    private final double attemptsCap;

    private LootTableAcquisitionAnalyzer(Map<String, List<LootSource>> sourcesByItem,
        double attemptsReference, double attemptsCap) {
        this.sourcesByItem = Map.copyOf(sourcesByItem);
        this.attemptsReference = attemptsReference;
        this.attemptsCap = attemptsCap;
    }

    public LootTableAcquisitionAnalyzer withNormalization(double reference, double cap) {
        return new LootTableAcquisitionAnalyzer(sourcesByItem, reference, cap);
    }

    public static LootTableAcquisitionAnalyzer fromResourceManager(ResourceManager resourceManager,
        double attemptsReference, double attemptsCap) {
        List<ParsedTable> tables = new ArrayList<>();
        resourceManager.listResources("loot_table", id -> id.getPath().endsWith(".json")).entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> parseResource(entry.getKey(), entry.getValue())
                .map(table -> table.withSourceType(sourceType(entry.getKey())))
                .ifPresent(tables::add));
        return fromParsedTables(tables, attemptsReference, attemptsCap);
    }

    public static LootTableAcquisitionAnalyzer fromParsedTables(Collection<ParsedTable> tables,
        double attemptsReference, double attemptsCap) {
        if (!Double.isFinite(attemptsReference) || attemptsReference <= 0.0D
            || !Double.isFinite(attemptsCap) || attemptsCap <= 0.0D) {
            throw new IllegalArgumentException("loot quantity normalization must be finite and positive");
        }
        Map<String, List<LootSource>> sources = new HashMap<>();
        tables.stream().sorted(Comparator.comparing(ParsedTable::tableId)).forEach(table ->
            {
                table.expectedUnitsByItem().forEach((itemId, expectedUnits) ->
                    sources.computeIfAbsent(itemId, ignored -> new ArrayList<>())
                        .add(new LootSource(table.tableId(), table.sourceType(), expectedUnits,
                            table.evidenceByItem().get(itemId))));
                table.unknownItems().stream().sorted().forEach(itemId ->
                    sources.computeIfAbsent(itemId, ignored -> new ArrayList<>())
                        .add(new LootSource(table.tableId(), table.sourceType(), null, null)));
            });
        Map<String, List<LootSource>> frozen = new HashMap<>();
        sources.forEach((itemId, values) -> frozen.put(itemId, values.stream()
            .sorted(Comparator.comparing(LootSource::tableId)).toList()));
        return new LootTableAcquisitionAnalyzer(frozen, attemptsReference, attemptsCap);
    }

    @Override
    public boolean supports(String itemId) {
        return sourcesByItem.containsKey(itemId);
    }

    @Override
    public List<AcquisitionPath> analyze(String itemId) {
        List<LootSource> sources = sourcesByItem.get(itemId);
        if (sources == null) {
            return List.of();
        }
        return sources.stream().map(source -> {
            if (source.expectedUnitsPerAttempt() == null) {
                Map<Integer, CostVector> unknownHorizons = new HashMap<>();
                for (int horizon : supportedHorizons()) {
                    unknownHorizons.put(horizon, lootCostVector(horizon, EconomicFactor.unknown(
                        "conditional or function-based loot yield is not statically measurable")));
                }
                Map<String, AcquisitionMeasurement> unknownEvidence = new HashMap<>(Map.of(
                    "probability", AcquisitionMeasurement.unknown("loot conditions or weighted selection are not evaluated"),
                    "expected_yield_on_success", AcquisitionMeasurement.unknown("loot functions or conditions are not evaluated"),
                    "expected_units_per_attempt", AcquisitionMeasurement.unknown("loot functions or conditions are not evaluated"),
                    "expected_attempts_per_unit", AcquisitionMeasurement.unknown("expected yield is unknown")
                ));
                for (int horizon : supportedHorizons()) {
                    unknownEvidence.put("expected_attempts_to_obtain_" + horizon,
                        AcquisitionMeasurement.unknown("expected yield is unknown"));
                }
                return new AcquisitionPath(itemId, source.sourceType(), source.tableId(), 0.5D,
                    null, null, null, false,
                    Map.of(
                        "probability", EconomicFactor.unknown("loot conditions are not evaluated"),
                        "expected_yield", EconomicFactor.unknown("loot functions or conditions are not evaluated")
                    ),
                    unknownHorizons, new AcquisitionEvidence(unknownEvidence, lootAttributes(source)));
            }
            EconomicFactor quantity = FactorNormalizer.quantityCost(
                source.expectedUnitsPerAttempt(), attemptsReference, attemptsCap);
            if (!quantity.isKnown()) {
                return null;
            }
            LootEvidence loot = source.evidence();
            EconomicFactor probability = loot == null
                ? EconomicFactor.unknown("exact loot probability was not retained")
                : EconomicFactor.known(loot.probability());
            EconomicFactor yield = loot == null
                ? EconomicFactor.unknown("conditional loot yield is not available")
                : EconomicFactor.known(Math.min(1.0D, loot.expectedYieldOnSuccess()));
            Map<String, EconomicFactor> feasibility = new HashMap<>();
            feasibility.put("probability", probability);
            feasibility.put("expected_yield", yield);
            feasibility.put("reliability", EconomicFactor.known(1.0D));
            Map<Integer, CostVector> horizons = new HashMap<>();
            for (int horizon : supportedHorizons()) {
                horizons.put(horizon, lootCostVector(horizon, quantity,
                    loot == null ? null : loot.probability(),
                    loot == null ? null : loot.expectedYieldOnSuccess()));
            }
            Map<String, AcquisitionMeasurement> measurements = new HashMap<>(Map.of(
                "probability", loot == null
                    ? AcquisitionMeasurement.unknown("exact loot probability was not retained")
                    : AcquisitionMeasurement.known(loot.probability()),
                "expected_yield_on_success", loot == null
                    ? AcquisitionMeasurement.unknown("exact conditional yield unavailable")
                    : AcquisitionMeasurement.known(loot.expectedYieldOnSuccess()),
                "expected_units_per_attempt", AcquisitionMeasurement.known(source.expectedUnitsPerAttempt()),
                "expected_attempts_per_unit",
                    AcquisitionMeasurement.known(1.0D / source.expectedUnitsPerAttempt())
            ));
            for (int horizon : supportedHorizons()) {
                measurements.put("expected_attempts_to_obtain_" + horizon,
                    AcquisitionMeasurement.known(horizon / source.expectedUnitsPerAttempt()));
            }
            return new AcquisitionPath(itemId, source.sourceType(), source.tableId(), 1.0D,
                null, null, null, false, feasibility, horizons,
                new AcquisitionEvidence(measurements, lootAttributes(source)));
        }).filter(java.util.Objects::nonNull).toList();
    }

    private CostVector lootCostVector(int horizon, EconomicFactor quantity) {
        return lootCostVector(horizon, quantity, null, null);
    }

    private CostVector lootCostVector(int horizon, EconomicFactor quantity,
        Double probability, Double expectedYield) {
        Map<String, EconomicFactor> factors = new HashMap<>();
        factors.put("quantity_cost", quantity);
        factors.put("probability_cost", probability == null
            ? EconomicFactor.unknown("exact probability unavailable")
            : FactorNormalizer.logarithmic(1.0D / probability, attemptsReference, attemptsCap));
        factors.put("yield_cost", expectedYield == null || expectedYield <= 0.0D
            ? EconomicFactor.unknown("conditional yield unavailable")
            : FactorNormalizer.logarithmic(1.0D / expectedYield, attemptsReference, attemptsCap));
        for (String factor : List.of("time_cost", "startup_cost", "recurring_cost", "prerequisite_cost",
            "progression_cost", "equipment_cost", "danger_cost", "transport_cost", "intermediate_cost",
            "resource_consumption_cost", "material_cost")) {
            factors.put(factor, EconomicFactor.unknown("not observable from loot-table data"));
        }
        return new CostVector(horizon, factors);
    }

    private static Map<String, String> lootAttributes(LootSource source) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("loot_table", source.tableId());
        attributes.put("source_type", source.sourceType());
        ResourceLocation tableId = ResourceLocation.tryParse(source.tableId());
        if (tableId == null) {
            return Map.copyOf(attributes);
        }
        String path = tableId.getPath();
        if (source.sourceType().equals("mob_drop") && path.startsWith("entities/")) {
            attributes.put("source_entity", tableId.getNamespace() + ":"
                + path.substring("entities/".length()));
        } else if (source.sourceType().equals("fishing")) {
            attributes.put("source_mechanic", "fishing loot table");
        } else if (source.sourceType().equals("crop") && path.startsWith("blocks/")) {
            attributes.put("source_block", tableId.getNamespace() + ":"
                + path.substring("blocks/".length()));
            attributes.put("growth_time", "unknown: CropBlock data does not specify a deterministic tick duration");
            attributes.put("seed_return", "loot output can be observed; seed-return loop is not independently modeled");
        } else if (source.sourceType().equals("worldgen") && path.startsWith("chests/")) {
            attributes.put("structure_loot_table", tableId.getNamespace() + ":"
                + path.substring("chests/".length()));
            attributes.put("structure_placement", "unknown: chest loot id does not prove where or how often it is placed");
        }
        return Map.copyOf(attributes);
    }

    private List<Integer> supportedHorizons() {
        return java.util.stream.Stream.of(1, 10, 100,
                com.jorjik.dynamicfood.config.DynamicFoodConfig.acquisitionEconomicHorizon())
            .distinct().sorted().toList();
    }

    public Map<String, Integer> sourceCounts() {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        sourcesByItem.values().stream().flatMap(List::stream)
            .forEach(source -> counts.merge(source.sourceType(), 1, Integer::sum));
        return Map.copyOf(counts);
    }

    public Set<String> indexedItemIds() {
        return sourcesByItem.keySet();
    }

    private static Optional<ParsedTable> parseResource(ResourceLocation resourceId, Resource resource) {
        try (var reader = resource.openAsReader()) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            String tablePath = resourceId.getPath().substring(0,
                resourceId.getPath().length() - ".json".length());
            if (tablePath.startsWith("loot_table/")) {
                tablePath = tablePath.substring("loot_table/".length());
            }
            String tableId = resourceId.getNamespace() + ":" + tablePath;
            return parseTable(tableId, parsed.getAsJsonObject());
        } catch (IOException | RuntimeException exception) {
            com.jorjik.dynamicfood.DynamicFood.LOGGER.warn("Unable to index loot table {} for acquisition analysis",
                resourceId, exception);
            return Optional.empty();
        }
    }

    private static String sourceType(ResourceLocation tableId) {
        String path = tableId.getPath();
        if (path.startsWith("loot_table/")) {
            path = path.substring("loot_table/".length());
        }
        if (path.endsWith(".json")) {
            path = path.substring(0, path.length() - ".json".length());
        }
        if (path.startsWith("entities/")) {
            return "mob_drop";
        }
        if (path.equals("fishing") || path.endsWith("/fishing") || path.startsWith("gameplay/fishing/")) {
            return "fishing";
        }
        if (path.startsWith("chests/")) {
            return "worldgen";
        }
        if (path.startsWith("blocks/")) {
            String blockPath = path.substring("blocks/".length());
            ResourceLocation blockId = ResourceLocation.fromNamespaceAndPath(tableId.getNamespace(), blockPath);
            if (BuiltInRegistries.BLOCK.getOptional(blockId).orElse(null) instanceof CropBlock) {
                return "crop";
            }
            return "block_loot";
        }
        return "loot_table";
    }

    static Optional<ParsedTable> parseTable(String tableId, JsonObject root) {
        Optional<ParsedTable> exact = parseExactTable(tableId, root);
        if (exact.isPresent()) {
            return exact;
        }
        Set<String> possibleItems = possibleItemOutputs(root);
        return possibleItems.isEmpty() ? Optional.empty()
            : Optional.of(new ParsedTable(tableId, sourceType(ResourceLocation.parse(tableId)),
                Map.of(), possibleItems));
    }

    private static Optional<ParsedTable> parseExactTable(String tableId, JsonObject root) {
        if (hasEntries(root, "functions") || root.has("neoforge:conditions")) {
            return Optional.empty();
        }
        JsonArray pools = root.getAsJsonArray("pools");
        if (pools == null || pools.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> expected = new HashMap<>();
        Map<String, Double> failureAcrossPools = new HashMap<>();
        for (JsonElement poolElement : pools) {
            if (!poolElement.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject pool = poolElement.getAsJsonObject();
            if (hasEntries(pool, "conditions") || hasEntries(pool, "functions")) {
                return Optional.empty();
            }
            OptionalDouble rolls = exactNumber(pool.get("rolls"), 1.0D);
            OptionalDouble bonusRolls = exactNumber(pool.get("bonus_rolls"), 0.0D);
            if (rolls.isEmpty() || rolls.getAsDouble() < 1.0D
                || rolls.getAsDouble() != Math.rint(rolls.getAsDouble())
                || bonusRolls.isEmpty() || bonusRolls.getAsDouble() != 0.0D) {
                return Optional.empty();
            }
            JsonArray entries = pool.getAsJsonArray("entries");
            if (entries == null || entries.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Double> weights = new HashMap<>();
            double totalWeight = 0.0D;
            for (JsonElement entryElement : entries) {
                if (!entryElement.isJsonObject()) {
                    return Optional.empty();
                }
                JsonObject entry = entryElement.getAsJsonObject();
                if (!"minecraft:item".equals(string(entry, "type"))
                    || hasEntries(entry, "conditions") || hasEntries(entry, "functions")) {
                    return Optional.empty();
                }
                OptionalDouble weight = exactNumber(entry.get("weight"), 1.0D);
                OptionalDouble quality = exactNumber(entry.get("quality"), 0.0D);
                ResourceLocation item = ResourceLocation.tryParse(string(entry, "name"));
                if (weight.isEmpty() || weight.getAsDouble() <= 0.0D
                    || quality.isEmpty() || quality.getAsDouble() != 0.0D || item == null) {
                    return Optional.empty();
                }
                String itemId = item.toString();
                weights.merge(itemId, weight.getAsDouble(), Double::sum);
                totalWeight += weight.getAsDouble();
            }
            for (Map.Entry<String, Double> weightedItem : weights.entrySet()) {
                double oneRollProbability = weightedItem.getValue() / totalWeight;
                double poolProbability = oneRollProbability >= 1.0D ? 1.0D
                    : -Math.expm1(rolls.getAsDouble() * Math.log1p(-oneRollProbability));
                expected.merge(weightedItem.getKey(), rolls.getAsDouble() * oneRollProbability, Double::sum);
                failureAcrossPools.merge(weightedItem.getKey(), 1.0D - poolProbability, (left, right) -> left * right);
            }
        }
        String sourceType = sourceType(ResourceLocation.parse(tableId));
        if (expected.isEmpty()) {
            return Optional.empty();
        }
        Map<String, LootEvidence> evidence = new HashMap<>();
        expected.forEach((itemId, units) -> {
            double probability = 1.0D - failureAcrossPools.getOrDefault(itemId, 1.0D);
            if (probability > 0.0D && Double.isFinite(probability)) {
                evidence.put(itemId, new LootEvidence(probability, units / probability));
            }
        });
        return Optional.of(new ParsedTable(tableId, sourceType, Map.copyOf(expected), Set.of(),
            Map.copyOf(evidence)));
    }

    private static Set<String> possibleItemOutputs(JsonElement element) {
        Set<String> result = new TreeSet<>();
        collectPossibleItemOutputs(element, result);
        return Set.copyOf(result);
    }

    private static void collectPossibleItemOutputs(JsonElement element, Set<String> result) {
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectPossibleItemOutputs(child, result));
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if ("minecraft:item".equals(string(object, "type"))) {
                ResourceLocation item = ResourceLocation.tryParse(string(object, "name"));
                if (item != null) {
                    result.add(item.toString());
                }
            }
            object.entrySet().forEach(entry -> collectPossibleItemOutputs(entry.getValue(), result));
        }
    }

    private static boolean hasEntries(JsonObject object, String name) {
        JsonArray values = object.getAsJsonArray(name);
        return values != null && !values.isEmpty();
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : null;
    }

    private static OptionalDouble exactNumber(JsonElement element, double defaultValue) {
        if (element == null) {
            return OptionalDouble.of(defaultValue);
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            double value = element.getAsDouble();
            return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if ("minecraft:constant".equals(string(object, "type"))) {
                return exactNumber(object.get("value"), Double.NaN);
            }
        }
        return OptionalDouble.empty();
    }

    public record ParsedTable(String tableId, String sourceType, Map<String, Double> expectedUnitsByItem,
        Set<String> unknownItems, Map<String, LootEvidence> evidenceByItem) {
        public ParsedTable(String tableId, Map<String, Double> expectedUnitsByItem) {
            this(tableId, LootTableAcquisitionAnalyzer.sourceType(ResourceLocation.parse(tableId)),
                expectedUnitsByItem, Set.of(), Map.of());
        }

        public ParsedTable(String tableId, String sourceType, Map<String, Double> expectedUnitsByItem) {
            this(tableId, sourceType, expectedUnitsByItem, Set.of(), Map.of());
        }

        public ParsedTable(String tableId, String sourceType, Map<String, Double> expectedUnitsByItem,
            Set<String> unknownItems) {
            this(tableId, sourceType, expectedUnitsByItem, unknownItems, Map.of());
        }

        public ParsedTable {
            expectedUnitsByItem = Map.copyOf(expectedUnitsByItem);
            unknownItems = Set.copyOf(unknownItems);
            evidenceByItem = Map.copyOf(evidenceByItem);
            if (sourceType == null || sourceType.isBlank()) {
                throw new IllegalArgumentException("loot source type is required");
            }
            if (expectedUnitsByItem.keySet().stream().anyMatch(unknownItems::contains)) {
                throw new IllegalArgumentException("loot output cannot have both known and unknown yield");
            }
        }

        private ParsedTable withSourceType(String type) {
            return new ParsedTable(tableId, type, expectedUnitsByItem, unknownItems, evidenceByItem);
        }
    }

    public record LootEvidence(double probability, double expectedYieldOnSuccess) {
        public LootEvidence {
            if (!Double.isFinite(probability) || probability <= 0.0D || probability > 1.0D
                || !Double.isFinite(expectedYieldOnSuccess) || expectedYieldOnSuccess <= 0.0D) {
                throw new IllegalArgumentException("loot evidence must contain positive finite probability and yield");
            }
        }
    }

    private record LootSource(String tableId, String sourceType, Double expectedUnitsPerAttempt,
        LootEvidence evidence) {
    }
}