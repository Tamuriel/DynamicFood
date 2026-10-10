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
import java.util.Objects;
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
    private static final String EXPECTED_VALUE_EVALUATOR_VERSION = "minecraft-loot-expected-value-v1";
    private static final String UNKNOWN_LOOT_YIELD_REASON =
        "candidate item outputs were identified, but unsupported or conditional loot semantics prevent "
            + "a defensible expected-yield calculation";
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
                            table.evidenceByItem().get(itemId),
                            outputPossibility(expectedUnits, table.provenPossibleItems().contains(itemId)),
                            table.ordinaryPlayerBreakOutputs().contains(itemId),
                            table.ordinaryPlayerBreakRequiresCorrectTool())));
                table.unknownItems().stream().sorted().forEach(itemId ->
                    sources.computeIfAbsent(itemId, ignored -> new ArrayList<>())
                        .add(new LootSource(table.tableId(), table.sourceType(), null, null,
                            table.provenPossibleItems().contains(itemId)
                                ? OutputPossibilityStatus.PROVEN_POSSIBLE
                                : OutputPossibilityStatus.UNKNOWN_CANDIDATE,
                            table.ordinaryPlayerBreakOutputs().contains(itemId),
                            table.ordinaryPlayerBreakRequiresCorrectTool())));
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
                    EconomicFactor.unknown(UNKNOWN_LOOT_YIELD_REASON),
                    source.ordinaryPlayerBreakOutput(), source.requiresCorrectTool()));
            }
            Map<String, AcquisitionMeasurement> unknownEvidence = new HashMap<>(Map.of(
                "probability", AcquisitionMeasurement.unknown(
                    "loot conditions or alternative-entry selection are not resolved"),
                "expected_yield_on_success", AcquisitionMeasurement.unknown(UNKNOWN_LOOT_YIELD_REASON),
                "expected_units_per_attempt", AcquisitionMeasurement.unknown(UNKNOWN_LOOT_YIELD_REASON),
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
                    EconomicFactor.unknown("loot functions or conditions are not evaluated"), false, source),
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
        feasibility.putAll(lootFeasibilityFactors(probability, yield, true, source));
        Map<Integer, CostVector> horizons = new HashMap<>();
        for (int horizon : supportedHorizons()) {
            horizons.put(horizon, lootCostVector(horizon,
                FactorNormalizer.quantityCostForHorizon(source.expectedUnitsPerAttempt(), horizon,
                    attemptsReference, attemptsCap),
                source.ordinaryPlayerBreakOutput(), source.requiresCorrectTool()));
        }
        Map<String, AcquisitionMeasurement> measurements = new HashMap<>(Map.of(
            "probability", loot == null
                ? AcquisitionMeasurement.unknown("exact loot probability was not retained")
                : AcquisitionMeasurement.analytical(loot.probability(),
                    expectedValueMetadata(source.tableId(), "item inclusion probability")),
            "expected_yield_on_success", loot == null
                ? AcquisitionMeasurement.unknown("exact conditional yield unavailable")
                : AcquisitionMeasurement.analytical(loot.expectedYieldOnSuccess(),
                    expectedValueMetadata(source.tableId(), "conditional expected yield")),
            "expected_units_per_attempt", AcquisitionMeasurement.analytical(source.expectedUnitsPerAttempt(),
                expectedValueMetadata(source.tableId(), "expected item units per invocation")),
            "expected_attempts_per_unit",
                AcquisitionMeasurement.analytical(1.0D / source.expectedUnitsPerAttempt(),
                    expectedValueMetadata(source.tableId(), "reciprocal expected units per invocation"))
        ));
        for (int horizon : supportedHorizons()) {
            measurements.put("expected_attempts_to_obtain_" + horizon,
                AcquisitionMeasurement.analytical(horizon / source.expectedUnitsPerAttempt(),
                    expectedValueMetadata(source.tableId(), "normalized expected attempts for " + horizon
                        + " expected units")));
        }
        addCropMeasurements(source, measurements);
        return new AcquisitionPath(itemId, source.sourceType(), source.tableId(), 1.0D,
            null, null, null, false, feasibility, horizons,
            new AcquisitionEvidence(measurements, lootAttributes(source)));
    }

    private CostVector lootCostVector(int horizon, EconomicFactor quantity,
        boolean ordinaryPlayerBreakOutput, Boolean requiresCorrectTool) {
        Map<EconomicChannel, EconomicFactor> factors = new java.util.EnumMap<>(EconomicChannel.class);
        factors.put(EconomicChannel.QUANTITY, quantity);
        factors.put(EconomicChannel.PROBABILITY_BURDEN, EconomicFactor.notApplicable(
            "loot selection probability is represented by canonical expected quantity"));
        factors.put(EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN,
            ordinaryPlayerBreakOutput && Boolean.FALSE.equals(requiresCorrectTool)
                ? EconomicFactor.notApplicable(
                    "registered block does not require a correct tool for drops; optional tools that only improve "
                        + "speed are outside the equipment requirement burden")
                : EconomicFactor.unknown(ordinaryPlayerBreakOutput && Boolean.TRUE.equals(requiresCorrectTool)
                    ? "registered block requires a correct tool, but its acquisition and replacement cost are "
                        + "unresolved"
                    : "required tools or equipment are not represented by loot output data"));
        factors.put(EconomicChannel.MATERIAL_CONSUMPTION,
            ordinaryPlayerBreakOutput
                ? EconomicFactor.notApplicable(
                    "ordinary block breaking consumes no separate player material input")
                : EconomicFactor.unknown("source-specific inputs are not represented by loot output data"));
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
            : AcquisitionMeasurement.analytical(source.expectedUnitsPerAttempt(),
                expectedValueMetadata(source.tableId(), "crop harvest expected units per loot invocation")));
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

    private static EstimateMetadata expectedValueMetadata(String tableId, String metric) {
        return new EstimateMetadata("supported_minecraft_loot_expected_value", EXPECTED_VALUE_EVALUATOR_VERSION,
            Map.of("loot_table", tableId, "metric", metric),
            List.of("uses statically supported loot entries, weights, and literal roll counts",
                "conditions or functions that affect the metric remain UNKNOWN"));
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
        EconomicFactor probability, EconomicFactor expectedYield, boolean canonicalQuantityKnown,
        LootSource source) {
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
            Map.entry("equipment_availability", lootEquipmentAvailability(source)),
            Map.entry("reliability", EconomicFactor.known(1.0D))
        );
    }

    private static EconomicFactor lootEquipmentAvailability(LootSource source) {
        if (!source.sourceType().equals("block_loot") || !source.ordinaryPlayerBreakOutput()) {
            return EconomicFactor.unknown("source equipment requirements are not represented in loot data");
        }
        if (Boolean.FALSE.equals(source.requiresCorrectTool())) {
            return EconomicFactor.notApplicable(
                "registered block state proves that no correct tool is required for drops");
        }
        return EconomicFactor.unknown(Boolean.TRUE.equals(source.requiresCorrectTool())
            ? "registered block requires a correct tool, but player access to that equipment is unresolved"
            : "registered block correct-tool requirement is not exposed");
    }

    private static Map<String, String> lootAttributes(LootSource source) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("loot_table", source.tableId());
        attributes.put("source_type", source.sourceType());
        attributes.put("loot_output_evidence_status", source.outputPossibility().name());
        attributes.put("loot_output_evidence_reason", source.outputPossibilityReason());
        attributes.put("loot_analysis_category", source.expectedUnitsPerAttempt() == null
            ? "YIELD_UNKNOWN" : "EXPECTED_YIELD_ANALYZED");
        attributes.put("loot_analysis_reason", source.expectedUnitsPerAttempt() == null
            ? UNKNOWN_LOOT_YIELD_REASON
            : "expected item yield was derived by the supported loot expected-value evaluator");
        attributes.put("canonical_quantity_source", "expected_units_per_attempt");
        attributes.put("canonical_quantity_unit", "item per loot-table invocation");
        attributes.put("canonical_quantity_semantics",
            "expected weighted item quantity per invocation; not guaranteed unless probability is 1 and yield deterministic");
        attributes.put("quantity_derived_from", "loot probability, conditional yield, stack count, and roll count");
        attributes.put("survival_availability",
            "unknown: loot-table presence does not prove that its triggering mechanic is available in survival");
        attributes.put("source_availability_classification", "UNKNOWN");
        attributes.put("ordinary_player_break_output_evidence",
            source.ordinaryPlayerBreakOutput() ? "TRUE" : "UNKNOWN");
        attributes.put("ordinary_player_break_output_reason", source.ordinaryPlayerBreakOutput()
            ? "registered block loot has one direct block-item entry, one ordinary roll, and no non-explosion condition or function"
            : "ordinary player block-break output is not established by the supported direct-drop loot pattern");
        if (source.ordinaryPlayerBreakOutput()) {
            attributes.put("ordinary_player_break_requires_correct_tool",
                source.requiresCorrectTool() == null ? "UNKNOWN" : source.requiresCorrectTool().toString());
            attributes.put("ordinary_player_break_tool_requirement_reason",
                source.requiresCorrectTool() == null
                    ? "registered blocks sharing this loot table do not establish one unambiguous tool requirement"
                    : "tool requirement was resolved from registered blocks referencing this loot table");
        }
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

    @Override
    public Set<String> indexedItemIds() {
        return sourcesByItem.keySet();
    }

    private static Optional<ParsedTable> parseResource(ResourceLocation resourceId, Resource resource) {
        try (var reader = resource.openAsReader()) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                logIndexFailure(resourceId, "MALFORMED_ROOT",
                    "loot-table JSON root must be an object", null);
                return Optional.empty();
            }
            String tablePath = resourceId.getPath().substring(0,
                resourceId.getPath().length() - ".json".length());
            if (tablePath.startsWith("loot_table/")) {
                tablePath = tablePath.substring("loot_table/".length());
            }
            String tableId = resourceId.getNamespace() + ":" + tablePath;
            return parseTable(tableId, parsed.getAsJsonObject());
        } catch (IOException exception) {
            logIndexFailure(resourceId, "RESOURCE_READ_FAILURE",
                "loot-table resource could not be read: " + exception.getMessage(), exception);
            return Optional.empty();
        } catch (com.google.gson.JsonParseException exception) {
            logIndexFailure(resourceId, "MALFORMED_JSON",
                "loot-table resource is not valid JSON: " + exception.getMessage(), exception);
            return Optional.empty();
        } catch (RuntimeException exception) {
            logIndexFailure(resourceId, "PARSER_FAILURE",
                "loot-table indexing failed (" + exception.getClass().getSimpleName() + "): "
                    + Objects.toString(exception.getMessage(), "no exception message"), exception);
            return Optional.empty();
        }
    }

    private static void logIndexFailure(ResourceLocation resourceId, String category, String reason,
        Throwable exception) {
        if (exception == null) {
            com.jorjik.dynamicfood.DynamicFood.LOGGER.warn(
                "Unable to index loot table {} [category={}]: {}", resourceId, category, reason);
        } else {
            com.jorjik.dynamicfood.DynamicFood.LOGGER.warn(
                "Unable to index loot table {} [category={}]: {}", resourceId, category, reason, exception);
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
        ParsedTable parsed;
        if (exact.isPresent()) {
            parsed = exact.get();
        } else {
            Set<String> possibleItems = possibleItemOutputs(root);
            if (possibleItems.isEmpty()) {
                return Optional.empty();
            }
            parsed = new ParsedTable(tableId, sourceType(ResourceLocation.parse(tableId)),
                Map.of(), possibleItems);
        }
        Set<String> provenPossibleItems = new TreeSet<>(parsed.expectedUnitsByItem().entrySet().stream()
            .filter(entry -> Double.isFinite(entry.getValue()) && entry.getValue() > 0.0D)
            .map(Map.Entry::getKey).toList());
        provenPossibleItems.addAll(provenSetCountOutputs(root));
        parsed = parsed.withProvenPossibleItems(provenPossibleItems);
        Set<String> ordinaryOutputs = ordinaryPlayerBreakOutputs(tableId, root);
        Boolean requiresCorrectTool = ordinaryOutputs.isEmpty()
            ? null : ordinaryPlayerBreakRequiresCorrectTool(tableId);
        return Optional.of(parsed.withOrdinaryPlayerBreakOutputs(ordinaryOutputs, requiresCorrectTool));
    }

    private static Set<String> provenSetCountOutputs(JsonObject root) {
        if (root.has("neoforge:conditions") || hasEntries(root, "functions")
            || root.entrySet().stream().anyMatch(entry -> !Set.of("type", "pools").contains(entry.getKey()))) {
            return Set.of();
        }
        JsonArray pools = jsonArray(root, "pools");
        if (pools == null || pools.size() != 1 || !pools.get(0).isJsonObject()) {
            return Set.of();
        }
        JsonObject pool = pools.get(0).getAsJsonObject();
        if (pool.entrySet().stream().anyMatch(entry ->
                !Set.of("rolls", "bonus_rolls", "conditions", "functions", "entries").contains(entry.getKey()))
            || hasEntries(pool, "functions")
            || hasEntries(pool, "conditions")
            || exactNumber(pool.get("rolls"), 1.0D).orElse(Double.NaN) != 1.0D
            || exactNumber(pool.get("bonus_rolls"), 0.0D).orElse(Double.NaN) != 0.0D) {
            return Set.of();
        }
        JsonArray entries = jsonArray(pool, "entries");
        if (entries == null || entries.size() != 1 || !entries.get(0).isJsonObject()) {
            return Set.of();
        }
        JsonObject entry = entries.get(0).getAsJsonObject();
        OptionalDouble weight = exactNumber(entry.get("weight"), 1.0D);
        OptionalDouble quality = exactNumber(entry.get("quality"), 0.0D);
        if (!"minecraft:item".equals(string(entry, "type"))
            || entry.entrySet().stream().anyMatch(field ->
                !Set.of("type", "name", "weight", "quality", "conditions", "functions").contains(field.getKey()))
            || hasEntries(entry, "conditions")
            || weight.isEmpty() || !Double.isFinite(weight.getAsDouble()) || weight.getAsDouble() <= 0.0D
            || quality.isEmpty() || quality.getAsDouble() != 0.0D) {
            return Set.of();
        }
        JsonArray functions = jsonArray(entry, "functions");
        if (functions == null || functions.size() != 1 || !functions.get(0).isJsonObject()) {
            return Set.of();
        }
        JsonObject function = functions.get(0).getAsJsonObject();
        JsonElement addValue = function.get("add");
        boolean additiveCount = addValue != null && addValue.isJsonPrimitive()
            && addValue.getAsJsonPrimitive().isBoolean() && addValue.getAsBoolean();
        if (!"minecraft:set_count".equals(string(function, "function"))
            || function.entrySet().stream().anyMatch(field ->
                !Set.of("function", "count", "add", "conditions").contains(field.getKey()))
            || hasEntries(function, "conditions")
            || addValue != null && (!addValue.isJsonPrimitive()
                || !addValue.getAsJsonPrimitive().isBoolean() || additiveCount)
            || !hasPositiveIntegerCountRange(function.get("count"))) {
            return Set.of();
        }
        String itemName = string(entry, "name");
        ResourceLocation item = itemName == null ? null : ResourceLocation.tryParse(itemName);
        return item == null ? Set.of() : Set.of(item.toString());
    }

    private static boolean hasPositiveIntegerCountRange(JsonElement count) {
        if (count == null) {
            return false;
        }
        if (count.isJsonPrimitive() && count.getAsJsonPrimitive().isNumber()) {
            OptionalDouble value = exactNumber(count, Double.NaN);
            return value.isPresent() && isPositiveInteger(value.getAsDouble());
        }
        if (!count.isJsonObject()) {
            return false;
        }
        JsonObject provider = count.getAsJsonObject();
        String type = string(provider, "type");
        if ("minecraft:constant".equals(type)
            && provider.entrySet().stream().allMatch(field -> Set.of("type", "value").contains(field.getKey()))) {
            OptionalDouble value = exactNumber(provider.get("value"), Double.NaN);
            return value.isPresent() && isPositiveInteger(value.getAsDouble());
        }
        if (type != null && !"minecraft:uniform".equals(type)
            || provider.entrySet().stream().anyMatch(field ->
                !Set.of("type", "min", "max").contains(field.getKey()))
            || !provider.has("min") || !provider.has("max")) {
            return false;
        }
        OptionalDouble minimum = exactNumber(provider.get("min"), Double.NaN);
        OptionalDouble maximum = exactNumber(provider.get("max"), Double.NaN);
        return minimum.isPresent() && maximum.isPresent()
            && isPositiveInteger(minimum.getAsDouble())
            && isPositiveInteger(maximum.getAsDouble())
            && minimum.getAsDouble() <= maximum.getAsDouble();
    }

    private static boolean isPositiveInteger(double value) {
        return Double.isFinite(value) && value > 0.0D && value == Math.rint(value);
    }

    private static OutputPossibilityStatus outputPossibility(Double expectedUnitsPerAttempt,
        boolean explicitlyProven) {
        return explicitlyProven || expectedUnitsPerAttempt != null
            && Double.isFinite(expectedUnitsPerAttempt) && expectedUnitsPerAttempt > 0.0D
                ? OutputPossibilityStatus.PROVEN_POSSIBLE
                : OutputPossibilityStatus.UNKNOWN_CANDIDATE;
    }

    private static String outputPossibilityReason(OutputPossibilityStatus status,
        Double expectedUnitsPerAttempt) {
        if (status == OutputPossibilityStatus.UNKNOWN_CANDIDATE) {
            return "item is an indexed candidate, but supported loot evaluation does not establish that it can occur";
        }
        return expectedUnitsPerAttempt == null
            ? "supported minecraft:set_count semantics and a strictly positive resolved count range establish "
                + "possible positive output; expected quantity remains unresolved"
            : "supported loot evaluation establishes positive expected output for this exact item";
    }

    private static Boolean ordinaryPlayerBreakRequiresCorrectTool(String tableId) {
        ResourceLocation tableLocation = ResourceLocation.tryParse(tableId);
        if (tableLocation == null) {
            return null;
        }
        List<Boolean> requirements = new ArrayList<>();
        for (var block : BuiltInRegistries.BLOCK) {
            if (block.getLootTable().location().equals(tableLocation)) {
                requirements.add(block.defaultBlockState().requiresCorrectToolForDrops());
            }
        }
        return uniqueCorrectToolRequirement(requirements);
    }

    static Boolean uniqueCorrectToolRequirement(Collection<Boolean> requirements) {
        Boolean resolved = null;
        boolean found = false;
        if (requirements == null || requirements.isEmpty()) {
            return null;
        }
        for (Boolean requirement : requirements) {
            if (requirement == null || found && !resolved.equals(requirement)) {
                return null;
            }
            resolved = requirement;
            found = true;
        }
        return found ? resolved : null;
    }

    private static Set<String> ordinaryPlayerBreakOutputs(String tableId, JsonObject root) {
        ResourceLocation tableLocation = ResourceLocation.tryParse(tableId);
        if (tableLocation == null || !sourceType(tableLocation).equals("block_loot")) {
            return Set.of();
        }
        String path = tableLocation.getPath();
        if (!path.startsWith("blocks/")) {
            return Set.of();
        }
        ResourceLocation blockLocation = ResourceLocation.fromNamespaceAndPath(
            tableLocation.getNamespace(), path.substring("blocks/".length()));
        var block = BuiltInRegistries.BLOCK.getOptional(blockLocation).orElse(null);
        if (block == null || block.asItem() == net.minecraft.world.item.Items.AIR
            || !block.getLootTable().location().equals(tableLocation)
            || !hasNoEntries(root, "functions") || root.has("neoforge:conditions")
            || root.entrySet().stream().anyMatch(entry ->
                !Set.of("type", "pools", "random_sequence").contains(entry.getKey()))) {
            return Set.of();
        }

        JsonArray pools = jsonArray(root, "pools");
        if (pools == null || pools.size() != 1 || !pools.get(0).isJsonObject()) {
            return Set.of();
        }
        JsonObject pool = pools.get(0).getAsJsonObject();
        if (!hasNoEntries(pool, "functions")
            || pool.entrySet().stream().anyMatch(entry ->
                !Set.of("rolls", "bonus_rolls", "conditions", "functions", "entries").contains(entry.getKey()))
            || !onlyOrdinaryBlockBreakConditions(pool)) {
            return Set.of();
        }
        OptionalDouble rolls = exactNumber(pool.get("rolls"), 1.0D);
        OptionalDouble bonusRolls = exactNumber(pool.get("bonus_rolls"), 0.0D);
        JsonArray entries = jsonArray(pool, "entries");
        if (rolls.isEmpty() || rolls.getAsDouble() != 1.0D
            || bonusRolls.isEmpty() || bonusRolls.getAsDouble() != 0.0D
            || entries == null || entries.size() != 1 || !entries.get(0).isJsonObject()) {
            return Set.of();
        }

        JsonObject entry = entries.get(0).getAsJsonObject();
        ResourceLocation output = ordinaryBlockItemOutput(entry);
        String blockItemId = BuiltInRegistries.ITEM.getKey(block.asItem()).toString();
        OptionalDouble weight = exactNumber(entry.get("weight"), 1.0D);
        OptionalDouble quality = exactNumber(entry.get("quality"), 0.0D);
        if (output == null || !output.toString().equals(blockItemId)
            || weight.isEmpty() || weight.getAsDouble() != 1.0D
            || quality.isEmpty() || quality.getAsDouble() != 0.0D
            || !hasNoEntries(entry, "conditions") || !hasNoEntries(entry, "functions")
            || entry.entrySet().stream().anyMatch(field ->
                !Set.of("type", "name", "weight", "quality", "conditions", "functions")
                    .contains(field.getKey()))) {
            return Set.of();
        }
        return Set.of(blockItemId);
    }

    static ResourceLocation ordinaryBlockItemOutput(JsonObject entry) {
        if (!"minecraft:item".equals(string(entry, "type"))) {
            return null;
        }
        String outputName = string(entry, "name");
        return outputName == null ? null : ResourceLocation.tryParse(outputName);
    }

    private static boolean onlyOrdinaryBlockBreakConditions(JsonObject pool) {
        JsonElement conditionValue = pool.get("conditions");
        if (conditionValue == null) {
            return true;
        }
        if (!conditionValue.isJsonArray()) {
            return false;
        }
        JsonArray conditions = conditionValue.getAsJsonArray();
        if (conditions.isEmpty()) {
            return true;
        }
        if (conditions.size() != 1 || !conditions.get(0).isJsonObject()) {
            return false;
        }
        JsonObject condition = conditions.get(0).getAsJsonObject();
        return condition.size() == 1
            && "minecraft:survives_explosion".equals(string(condition, "condition"));
    }

    private static boolean hasNoEntries(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonArray() && value.getAsJsonArray().isEmpty();
    }

    private static Optional<ParsedTable> parseExactTable(String tableId, JsonObject root) {
        if (hasEntries(root, "functions") || root.has("neoforge:conditions")) {
            return Optional.empty();
        }
        JsonArray pools = jsonArray(root, "pools");
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
            if (hasEntries(pool, "functions")) {
                return Optional.empty();
            }
            if (hasEntries(pool, "conditions") && !onlyOrdinaryBlockBreakConditions(pool)) {
                return Optional.empty();
            }
            OptionalDouble rolls = exactNumber(pool.get("rolls"), 1.0D);
            OptionalDouble bonusRolls = exactNumber(pool.get("bonus_rolls"), 0.0D);
            if (rolls.isEmpty() || rolls.getAsDouble() < 1.0D
                || rolls.getAsDouble() != Math.rint(rolls.getAsDouble())
                || bonusRolls.isEmpty() || bonusRolls.getAsDouble() != 0.0D) {
                return Optional.empty();
            }
            JsonArray entries = jsonArray(pool, "entries");
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
                if (!"minecraft:item".equals(string(entry, "type"))) {
                    return Optional.empty();
                }
                if (hasEntries(entry, "functions")) {
                    return Optional.empty();
                }
                if (hasEntries(entry, "conditions") && !onlyOrdinaryBlockBreakConditions(entry)) {
                    return Optional.empty();
                }
                OptionalDouble weight = exactNumber(entry.get("weight"), 1.0D);
                OptionalDouble quality = exactNumber(entry.get("quality"), 0.0D);
                String itemName = string(entry, "name");
                ResourceLocation item = itemName == null ? null : ResourceLocation.tryParse(itemName);
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
        if (element == null) {
            return;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectPossibleItemOutputs(child, result));
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if ("minecraft:item".equals(string(object, "type"))) {
                String itemName = string(object, "name");
                ResourceLocation item = itemName == null ? null : ResourceLocation.tryParse(itemName);
                if (item != null) {
                    result.add(item.toString());
                }
            }
            object.entrySet().forEach(entry -> collectPossibleItemOutputs(entry.getValue(), result));
        }
    }

    private static boolean hasEntries(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && (!value.isJsonArray() || !value.getAsJsonArray().isEmpty());
    }

    private static JsonArray jsonArray(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
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

    private static Set<String> positiveExpectedItems(Map<String, Double> expectedUnitsByItem) {
        return expectedUnitsByItem.entrySet().stream()
            .filter(entry -> entry.getValue() != null && Double.isFinite(entry.getValue())
                && entry.getValue() > 0.0D)
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record ParsedTable(String tableId, String sourceType, Map<String, Double> expectedUnitsByItem,
        Set<String> unknownItems, Map<String, LootEvidence> evidenceByItem,
        Set<String> ordinaryPlayerBreakOutputs, Boolean ordinaryPlayerBreakRequiresCorrectTool,
        Set<String> provenPossibleItems) {
        public ParsedTable(String tableId, Map<String, Double> expectedUnitsByItem) {
            this(tableId, LootTableAcquisitionAnalyzer.sourceType(ResourceLocation.parse(tableId)),
                expectedUnitsByItem, Set.of(), Map.of(), Set.of(), null, positiveExpectedItems(expectedUnitsByItem));
        }

        public ParsedTable(String tableId, String sourceType, Map<String, Double> expectedUnitsByItem) {
            this(tableId, sourceType, expectedUnitsByItem, Set.of(), Map.of(), Set.of(), null,
                positiveExpectedItems(expectedUnitsByItem));
        }

        public ParsedTable(String tableId, String sourceType, Map<String, Double> expectedUnitsByItem,
            Set<String> unknownItems, Map<String, LootEvidence> evidenceByItem,
            Set<String> ordinaryPlayerBreakOutputs, Boolean ordinaryPlayerBreakRequiresCorrectTool) {
            this(tableId, sourceType, expectedUnitsByItem, unknownItems, evidenceByItem,
                ordinaryPlayerBreakOutputs, ordinaryPlayerBreakRequiresCorrectTool,
                positiveExpectedItems(expectedUnitsByItem));
        }

        public ParsedTable(String tableId, String sourceType, Map<String, Double> expectedUnitsByItem,
            Set<String> unknownItems) {
            this(tableId, sourceType, expectedUnitsByItem, unknownItems, Map.of(), Set.of(), null);
        }

        public ParsedTable(String tableId, String sourceType, Map<String, Double> expectedUnitsByItem,
            Set<String> unknownItems, Map<String, LootEvidence> evidenceByItem) {
            this(tableId, sourceType, expectedUnitsByItem, unknownItems, evidenceByItem, Set.of(), null);
        }

        public ParsedTable(String tableId, String sourceType, Map<String, Double> expectedUnitsByItem,
            Set<String> unknownItems, Map<String, LootEvidence> evidenceByItem,
            Set<String> ordinaryPlayerBreakOutputs) {
            this(tableId, sourceType, expectedUnitsByItem, unknownItems, evidenceByItem,
                ordinaryPlayerBreakOutputs, null);
        }

        public ParsedTable {
            expectedUnitsByItem = Map.copyOf(expectedUnitsByItem);
            unknownItems = Set.copyOf(unknownItems);
            evidenceByItem = Map.copyOf(evidenceByItem);
            ordinaryPlayerBreakOutputs = Set.copyOf(ordinaryPlayerBreakOutputs);
            provenPossibleItems = Set.copyOf(provenPossibleItems);
            if (sourceType == null || sourceType.isBlank()) {
                throw new IllegalArgumentException("loot source type is required");
            }
            if (expectedUnitsByItem.keySet().stream().anyMatch(unknownItems::contains)) {
                throw new IllegalArgumentException("loot output cannot have both known and unknown yield");
            }
            Set<String> allOutputs = new java.util.HashSet<>(expectedUnitsByItem.keySet());
            allOutputs.addAll(unknownItems);
            if (!allOutputs.containsAll(ordinaryPlayerBreakOutputs)
                || !allOutputs.containsAll(provenPossibleItems)) {
                throw new IllegalArgumentException("loot evidence must refer to a parsed item output");
            }
        }

        private ParsedTable withSourceType(String type) {
            return new ParsedTable(tableId, type, expectedUnitsByItem, unknownItems, evidenceByItem,
                ordinaryPlayerBreakOutputs, ordinaryPlayerBreakRequiresCorrectTool, provenPossibleItems);
        }

        private ParsedTable withOrdinaryPlayerBreakOutputs(Set<String> outputs, Boolean requiresCorrectTool) {
            return new ParsedTable(tableId, sourceType, expectedUnitsByItem, unknownItems, evidenceByItem, outputs,
                requiresCorrectTool, provenPossibleItems);
        }

        private ParsedTable withProvenPossibleItems(Set<String> outputs) {
            return new ParsedTable(tableId, sourceType, expectedUnitsByItem, unknownItems, evidenceByItem,
                ordinaryPlayerBreakOutputs, ordinaryPlayerBreakRequiresCorrectTool, outputs);
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

    private enum OutputPossibilityStatus {
        PROVEN_POSSIBLE,
        UNKNOWN_CANDIDATE
    }

    private record LootSource(String tableId, String sourceType, Double expectedUnitsPerAttempt,
        LootEvidence evidence, OutputPossibilityStatus outputPossibility,
        boolean ordinaryPlayerBreakOutput, Boolean requiresCorrectTool) {
        private String outputPossibilityReason() {
            return LootTableAcquisitionAnalyzer.outputPossibilityReason(outputPossibility,
                expectedUnitsPerAttempt);
        }
    }

    private record ItemLootSource(String itemId, LootSource source) {}
}