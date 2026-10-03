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
    private final Map<String, List<ItemLootSource>> sourcesByTable;
    private final double attemptsReference;
    private final double attemptsCap;

    private LootTableAcquisitionAnalyzer(Map<String, List<LootSource>> sourcesByItem,
        double attemptsReference, double attemptsCap) {
        this.sourcesByItem = Map.copyOf(sourcesByItem);
        Map<String, List<ItemLootSource>> byTable = new HashMap<>();
        sourcesByItem.forEach((itemId, sources) -> sources.forEach(source ->
            byTable.computeIfAbsent(source.tableId(), ignored -> new ArrayList<>())
                .add(new ItemLootSource(itemId, source))));
        byTable.replaceAll((tableId, sources) -> sources.stream()
            .sorted(Comparator.comparing(ItemLootSource::itemId)).toList());
        this.sourcesByTable = Map.copyOf(byTable);
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
        return sources.stream().map(source -> analyzeSource(itemId, source)).toList();
    }

    List<AcquisitionPath> analyzeTable(String tableId) {
        return sourcesByTable.getOrDefault(tableId, List.of()).stream()
            .map(source -> analyzeSource(source.itemId(), source.source()))
            .toList();
    }

    private AcquisitionPath analyzeSource(String itemId, LootSource source) {
        if (source.expectedUnitsPerAttempt() == null) {
            Map<Integer, CostVector> unknownHorizons = new HashMap<>();
            for (int horizon : supportedHorizons()) {
                unknownHorizons.put(horizon, lootCostVector(horizon,
                    EconomicFactor.unknown("conditional or function-based loot yield is not statically measurable"),
                    null, null));
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
            addCropMeasurements(source, unknownEvidence);
            return new AcquisitionPath(itemId, source.sourceType(), source.tableId(), 1.0D,
                null, null, null, false,
                lootFeasibilityFactors(
                    EconomicFactor.unknown("loot conditions are not evaluated"),
                    EconomicFactor.unknown("loot functions or conditions are not evaluated"), false),
                unknownHorizons, new AcquisitionEvidence(unknownEvidence, lootAttributes(source)));
        }
        LootEvidence loot = source.evidence();
        EconomicFactor probability = loot == null
            ? EconomicFactor.unknown("exact loot probability was not retained")
            : EconomicFactor.known(loot.probability());
        EconomicFactor yield = loot == null
            ? EconomicFactor.unknown("conditional loot yield is not available")
            : EconomicFactor.known(Math.min(1.0D, loot.expectedYieldOnSuccess()));
        Map<String, EconomicFactor> feasibility = new HashMap<>();
        feasibility.putAll(lootFeasibilityFactors(probability, yield, true));
        Map<Integer, CostVector> horizons = new HashMap<>();
        for (int horizon : supportedHorizons()) {
            horizons.put(horizon, lootCostVector(horizon,
                FactorNormalizer.quantityCostForHorizon(source.expectedUnitsPerAttempt(), horizon,
                    attemptsReference, attemptsCap),
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
        addCropMeasurements(source, measurements);
        return new AcquisitionPath(itemId, source.sourceType(), source.tableId(), 1.0D,
            null, null, null, false, feasibility, horizons,
            new AcquisitionEvidence(measurements, lootAttributes(source)));
    }

    private CostVector lootCostVector(int horizon, EconomicFactor quantity,
        Double probability, Double expectedYield) {
        Map<String, EconomicFactor> factors = new HashMap<>();
        factors.put("quantity_cost", quantity);
        boolean canonicalQuantityKnown = quantity.isKnown();
        factors.put("probability_cost", canonicalQuantityKnown
            ? EconomicFactor.notApplicable("probability is already represented by expected units per attempt")
            : EconomicFactor.unknown(probability == null
                ? "probability is unknown and canonical expected quantity cannot be calculated"
                : "probability is measured but dynamic loot behavior prevents canonical expected quantity"));
        factors.put("yield_cost", canonicalQuantityKnown
            ? EconomicFactor.notApplicable("conditional yield is already represented by expected units per attempt")
            : EconomicFactor.unknown(expectedYield == null
                ? "conditional yield is unknown and canonical expected quantity cannot be calculated"
                : "conditional yield is measured but dynamic loot behavior prevents canonical expected quantity"));
        factors.put("time_cost", EconomicFactor.unknown("loot-table data does not expose time per acquisition attempt"));
        factors.put("startup_cost", EconomicFactor.notApplicable(
            "loot-table definitions do not describe one-time source setup"));
        factors.put("recurring_cost", EconomicFactor.unknown(
            "source-specific recurring inputs and tool replacement are not represented in loot tables"));
        factors.put("prerequisite_cost", EconomicFactor.unknown(
            "source access requirements are not represented by loot output data"));
        factors.put("progression_cost", EconomicFactor.unknown(
            "source progression requirements are not represented by loot output data"));
        factors.put("equipment_cost", EconomicFactor.unknown(
            "required tools or equipment are not represented by loot output data"));
        factors.put("danger_cost", EconomicFactor.unknown(
            "source-specific danger is not represented by loot output data"));
        factors.put("transport_cost", EconomicFactor.unknown(
            "source accessibility and travel distance are not represented by loot output data"));
        factors.put("intermediate_cost", EconomicFactor.notApplicable(
            "loot functions describe the output rather than recursive recipe inputs"));
        factors.put("resource_consumption_cost", EconomicFactor.unknown(
            "source-specific consumables are not represented by loot output data"));
        factors.put("material_cost", EconomicFactor.unknown(
            "source-specific inputs are not represented by loot output data"));
        return new CostVector(horizon, factors);
    }

    private static void addCropMeasurements(LootSource source, Map<String, AcquisitionMeasurement> measurements) {
        if (!source.sourceType().equals("crop")) {
            return;
        }
        CropBlock crop = cropBlock(source);
        measurements.put("crop_max_age", crop == null
            ? AcquisitionMeasurement.unknown("crop block type is no longer available in the current registry")
            : AcquisitionMeasurement.known(crop.getMaxAge()));
        measurements.put("crop_harvest_expected_units_per_loot_invocation", source.expectedUnitsPerAttempt() == null
            ? AcquisitionMeasurement.unknown("crop loot functions or conditions prevent exact yield calculation")
            : AcquisitionMeasurement.known(source.expectedUnitsPerAttempt()));
        AcquisitionMeasurement growthTime = AcquisitionMeasurement.unknown(
            "CropBlock random-tick growth depends on runtime environment and random tick settings");
        AcquisitionMeasurement seedReturn = AcquisitionMeasurement.unknown(
            "loot output is indexed per item, but the crop seed item and mature-state drop are not resolved");
        AcquisitionMeasurement seedsRequired = AcquisitionMeasurement.unknown(
            "block loot does not establish how many propagules are required to replant one crop");
        CropCycleResolver.Assessment cycle = CropCycleResolver.resolve(seedReturn, seedsRequired, growthTime);
        measurements.put("crop_growth_time_ticks", cycle.growthTimeTicks());
        measurements.put("crop_seed_return_per_cycle", seedReturn);
        measurements.put("crop_seeds_required_per_cycle", seedsRequired);
        measurements.put("crop_external_seed_input_per_cycle", cycle.externalSeedsRequiredPerCycle());
    }

    private static CropBlock cropBlock(LootSource source) {
        ResourceLocation tableId = ResourceLocation.tryParse(source.tableId());
        if (tableId == null || !tableId.getPath().startsWith("blocks/")) {
            return null;
        }
        String blockPath = tableId.getPath().substring("blocks/".length());
        ResourceLocation blockId = ResourceLocation.fromNamespaceAndPath(tableId.getNamespace(), blockPath);
        var block = BuiltInRegistries.BLOCK.getOptional(blockId).orElse(null);
        return block instanceof CropBlock crop ? crop : null;
    }

    private static Map<String, EconomicFactor> lootFeasibilityFactors(
        EconomicFactor probability, EconomicFactor expectedYield, boolean canonicalQuantityKnown) {
        return Map.ofEntries(
            Map.entry("probability", canonicalQuantityKnown
                ? EconomicFactor.notApplicable("probability is already represented by expected units per attempt")
                : probability),
            Map.entry("expected_yield", canonicalQuantityKnown
                ? EconomicFactor.notApplicable("conditional yield is already represented by expected units per attempt")
                : expectedYield),
            Map.entry("repeatability", EconomicFactor.unknown(
                "loot data does not establish whether the source can be repeated")),
            Map.entry("renewability", EconomicFactor.unknown(
                "loot data does not establish source renewability")),
            Map.entry("startup_cost", EconomicFactor.notApplicable("loot tables do not describe source setup")),
            Map.entry("recurring_cost", EconomicFactor.unknown(
                "recurring source inputs are not represented in loot data")),
            Map.entry("prerequisite_cost", EconomicFactor.unknown(
                "source access prerequisites are not represented in loot data")),
            Map.entry("processing_requirements", EconomicFactor.notApplicable(
                "loot output generation is not a recipe processing operation")),
            Map.entry("progression_requirement", EconomicFactor.unknown(
                "source progression requirements are not represented in loot data")),
            Map.entry("danger", EconomicFactor.unknown("source danger is not represented in loot data")),
            Map.entry("resource_consumption", EconomicFactor.unknown(
                "source consumables are not represented in loot data")),
            Map.entry("intermediate_steps", EconomicFactor.notApplicable(
                "loot output generation has no recursive recipe step")),
            Map.entry("equipment_availability", EconomicFactor.unknown(
                "source equipment requirements are not represented in loot data")),
            Map.entry("reliability", EconomicFactor.known(1.0D))
        );
    }

    private static Map<String, String> lootAttributes(LootSource source) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("loot_table", source.tableId());
        attributes.put("source_type", source.sourceType());
        attributes.put("canonical_quantity_source", "expected_units_per_attempt");
        attributes.put("canonical_quantity_unit", "item per loot-table invocation");
        attributes.put("canonical_quantity_semantics",
            "expected weighted item quantity per invocation; not guaranteed unless probability is 1 and yield deterministic");
        attributes.put("quantity_derived_from", "loot probability, conditional yield, stack count, and roll count");
        attributes.put("survival_availability",
            "unknown: loot-table presence does not prove that its triggering mechanic is available in survival");
        attributes.put("source_availability_classification", "UNKNOWN");
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
            CropBlock crop = cropBlock(source);
            if (crop != null) {
                attributes.put("crop_max_age", Integer.toString(crop.getMaxAge()));
            }
            attributes.put("growth_time", "unknown: CropBlock data does not specify a deterministic tick duration");
            attributes.put("crop_cycle", "unknown: block loot does not establish planting, replanting, or seed renewal");
            attributes.put("seed_renewal_state", "UNKNOWN: seed return and replant requirement are unresolved");
            attributes.put("seed_return",
                "unknown: crop seed identity and mature-state return are not established by this loot path");
        } else if (source.sourceType().equals("block_loot") && path.startsWith("blocks/")) {
            attributes.put("source_block", tableId.getNamespace() + ":"
                + path.substring("blocks/".length()));
            attributes.put("extraction_operation", "Block#getDrops evaluates this table when the block is broken");
            attributes.put("canonical_quantity_unit", "item per block-break loot invocation");
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

    private record ItemLootSource(String itemId, LootSource source) {}
}