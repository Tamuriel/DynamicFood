package com.jorjik.dynamicfood.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public final class WorldgenAcquisitionAnalyzer implements AcquisitionAnalyzer {
    private final Map<String, List<WorldgenSource>> sourcesByItem;

    private WorldgenAcquisitionAnalyzer(Map<String, List<WorldgenSource>> sourcesByItem) {
        this.sourcesByItem = Map.copyOf(sourcesByItem);
    }

    public static WorldgenAcquisitionAnalyzer fromResourceManager(ResourceManager resourceManager) {
        return fromResourceManager(resourceManager, null);
    }

    public static WorldgenAcquisitionAnalyzer fromResourceManager(ResourceManager resourceManager,
        LootTableAcquisitionAnalyzer lootAnalyzer) {
        Map<String, JsonObject> jsonResources = new HashMap<>();
        for (String directory : List.of("worldgen/biome", "worldgen/placed_feature",
            "worldgen/configured_feature", "worldgen/dimension",
            "worldgen/multi_noise_biome_source_parameter_list")) {
            resourceManager.listResources(directory, id -> id.getPath().endsWith(".json"))
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parseResource(entry.getKey(), entry.getValue())
                    .ifPresent(json -> jsonResources.put(entry.getKey().toString(), json)));
        }
        return fromBlockSources(discoverBlockSources(jsonResources), lootAnalyzer);
    }

    public static WorldgenAcquisitionAnalyzer fromJsonResources(Map<String, JsonObject> jsonResources) {
        return fromBlockSources(discoverBlockSources(jsonResources), null);
    }

    static WorldgenAcquisitionAnalyzer fromBlockSources(
        Map<String, List<WorldgenBlockSource>> discovered, LootTableAcquisitionAnalyzer lootAnalyzer) {
        Map<String, BlockExtraction> extractionByBlock = new HashMap<>();
        discovered.keySet().forEach(blockId -> {
            ResourceLocation blockLocation = ResourceLocation.tryParse(blockId);
            if (blockLocation == null) {
                return;
            }
            var block = BuiltInRegistries.BLOCK.getOptional(blockLocation).orElse(null);
            if (block == null) {
                return;
            }
            var item = block.asItem();
            if (item == net.minecraft.world.item.Items.AIR) {
                return;
            }
            String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
            extractionByBlock.put(blockId,
                new BlockExtraction(itemId, block.getLootTable().location().toString()));
        });
        return fromResolvedBlockSources(discovered, extractionByBlock, lootAnalyzer);
    }

    static WorldgenAcquisitionAnalyzer fromResolvedBlockSources(
        Map<String, List<WorldgenBlockSource>> discovered,
        Map<String, BlockExtraction> extractionByBlock,
        LootTableAcquisitionAnalyzer lootAnalyzer) {
        Map<String, List<WorldgenSource>> indexed = new HashMap<>();
        discovered.forEach((blockId, sources) -> {
            BlockExtraction blockExtraction = extractionByBlock.get(blockId);
            if (blockExtraction == null) {
                return;
            }
            String lootTableId = blockExtraction.lootTableId();
            List<AcquisitionPath> extractionPaths = lootAnalyzer == null
                ? List.of() : lootAnalyzer.analyzeTable(lootTableId);
            if (extractionPaths.isEmpty()) {
                sources.forEach(source -> indexed.computeIfAbsent(blockExtraction.blockItemId(),
                    ignored -> new ArrayList<>())
                    .add(new WorldgenSource(source.sourceId(), source.biomeId(), source.placedFeatureId(), blockId,
                        lootTableId, source.measurements(), source.attributes(), null)));
                return;
            }
            for (WorldgenBlockSource source : sources) {
                for (AcquisitionPath extractionPath : extractionPaths) {
                    indexed.computeIfAbsent(extractionPath.itemId(), ignored -> new ArrayList<>())
                        .add(new WorldgenSource(source.sourceId(), source.biomeId(), source.placedFeatureId(),
                            blockId, lootTableId, source.measurements(), source.attributes(), extractionPath));
                }
            }
        });
        Map<String, List<WorldgenSource>> frozen = new HashMap<>();
        indexed.forEach((itemId, sources) -> frozen.put(itemId, sources.stream()
            .distinct().sorted(Comparator.comparing(WorldgenSource::sourceId)).toList()));
        return new WorldgenAcquisitionAnalyzer(frozen);
    }

    record BlockExtraction(String blockItemId, String lootTableId) {}

    static WorldgenAcquisitionAnalyzer fromItemSources(
        Map<String, List<WorldgenBlockSource>> sourcesByItem) {
        Map<String, List<WorldgenSource>> indexed = new HashMap<>();
        sourcesByItem.forEach((itemId, sources) -> indexed.put(itemId, sources.stream()
            .map(source -> new WorldgenSource(source.sourceId(), source.biomeId(), source.placedFeatureId(),
                itemId, null, source.measurements(), source.attributes(), null))
            .toList()));
        return new WorldgenAcquisitionAnalyzer(indexed);
    }

    public static Map<String, List<WorldgenBlockSource>> discoverBlockSources(Map<String, JsonObject> jsonResources) {
        Map<String, JsonObject> biomes = resourcesInDirectory(jsonResources, "worldgen/biome");
        Map<String, JsonObject> placedFeatures = resourcesInDirectory(jsonResources, "worldgen/placed_feature");
        Map<String, JsonObject> configuredFeatures = resourcesInDirectory(jsonResources, "worldgen/configured_feature");
        Map<String, Set<String>> dimensionsByBiome = discoverBiomeDimensions(jsonResources, biomes.keySet());
        Map<String, Set<String>> configuredBlocks = new HashMap<>();
        Map<String, WorldgenFeatureEvidence> configuredEvidence = new HashMap<>();
        configuredFeatures.forEach((id, json) -> {
            Set<String> blocks = new TreeSet<>();
            collectBlockStateNames(json, blocks);
            configuredBlocks.put(id, Set.copyOf(blocks));
            configuredEvidence.put(id, configuredFeatureEvidence(json));
        });

        Map<String, List<WorldgenBlockSource>> indexed = new HashMap<>();
        biomes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(biome -> {
            Set<String> biomePlacedFeatures = new TreeSet<>();
            collectKnownReferences(biome.getValue().get("features"), placedFeatures.keySet(), biomePlacedFeatures);
            for (String placedId : biomePlacedFeatures) {
                Set<String> configuredReferences = new TreeSet<>();
                JsonObject placedFeature = placedFeatures.get(placedId);
                if (placedFeature != null) {
                    collectKnownReferences(placedFeature.get("feature"), configuredFeatures.keySet(),
                        configuredReferences);
                }
                Set<String> outputBlocks = new TreeSet<>();
                configuredReferences.forEach(id -> outputBlocks.addAll(configuredBlocks.getOrDefault(id, Set.of())));
                WorldgenFeatureEvidence placedEvidence = placedFeatureEvidence(placedFeature);
                for (String blockId : outputBlocks) {
                    String sourceId = biome.getKey() + "/" + placedId;
                    indexed.computeIfAbsent(blockId, ignored -> new ArrayList<>())
                        .add(new WorldgenBlockSource(sourceId, biome.getKey(), placedId,
                            mergeMeasurements(placedEvidence.measurements(), configuredReferences,
                                configuredEvidence),
                            mergeAttributes(Map.of(
                                "biome_restriction", biome.getKey(),
                                "placed_feature", placedId,
                                "configured_features", String.join(",", configuredReferences),
                                "dimension", dimensionsByBiome.getOrDefault(biome.getKey(), Set.of()).isEmpty()
                                    ? "unknown: biome-to-dimension relation is not declared by loaded dimension data"
                                    : String.join(",", dimensionsByBiome.get(biome.getKey()))
                            ), placedEvidence.attributes())));
                }
            }
        });
        Map<String, List<WorldgenBlockSource>> frozen = new HashMap<>();
        indexed.forEach((blockId, sources) -> frozen.put(blockId, sources.stream()
            .distinct().sorted(Comparator.comparing(WorldgenBlockSource::sourceId)).toList()));
        return Map.copyOf(frozen);
    }

    @Override
    public boolean supports(String itemId) {
        return sourcesByItem.containsKey(itemId);
    }

    @Override
    public List<AcquisitionPath> analyze(String itemId) {
        List<WorldgenSource> sources = sourcesByItem.get(itemId);
        if (sources == null) {
            return List.of();
        }
        return sources.stream().map(source -> {
            Map<String, EconomicFactor> feasibilityFactors = Map.ofEntries(
                Map.entry("probability", EconomicFactor.unknown(
                    "placement modifiers are known but do not establish player-available expected item yield")),
                Map.entry("expected_yield", EconomicFactor.unknown(
                    "feature placement and replacement behavior do not establish expected mined item yield")),
                Map.entry("repeatability", EconomicFactor.unknown(
                    "worldgen data does not establish the remaining accessible generation area")),
                Map.entry("renewability", EconomicFactor.unknown(
                    "worldgen data does not establish whether accessible generation is renewable")),
                Map.entry("startup_cost", EconomicFactor.notApplicable(
                    "worldgen placement has no player startup operation")),
                Map.entry("recurring_cost", EconomicFactor.notApplicable(
                    "worldgen placement has no player recurring input operation")),
                Map.entry("prerequisite_cost", EconomicFactor.notApplicable(
                    "worldgen feature definitions contain no player prerequisite operation")),
                Map.entry("processing_requirements", EconomicFactor.notApplicable(
                    "worldgen placement is not a recipe-processing operation")),
                Map.entry("progression_requirement", EconomicFactor.notApplicable(
                    "worldgen data has no progression-gated generation mechanic")),
                Map.entry("danger", EconomicFactor.unknown(
                    "biome and dimension evidence does not determine player danger")),
                Map.entry("resource_consumption", EconomicFactor.unknown(
                    "mining tool and durability requirements are not represented by feature data")),
                Map.entry("intermediate_steps", EconomicFactor.notApplicable(
                    "worldgen placement has no recursive recipe steps")),
                Map.entry("equipment_availability", EconomicFactor.unknown(
                    "required mining equipment is not represented by feature data")),
                Map.entry("reliability", EconomicFactor.unknown(
                    "runtime generation conditions are not evaluated")
                ));
            Map<String, EconomicFactor> costFactors = Map.ofEntries(
                Map.entry("time_cost", EconomicFactor.unknown(
                    "worldgen data does not expose travel or mining time")),
                Map.entry("startup_cost", EconomicFactor.notApplicable(
                    "worldgen placement has no player startup operation")),
                Map.entry("recurring_cost", EconomicFactor.notApplicable(
                    "worldgen placement has no player recurring input operation")),
                Map.entry("prerequisite_cost", EconomicFactor.notApplicable(
                    "worldgen feature definitions contain no player prerequisite operation")),
                Map.entry("progression_cost", EconomicFactor.notApplicable(
                    "worldgen data has no progression-gated generation mechanic")),
                Map.entry("equipment_cost", EconomicFactor.unknown(
                    "mining equipment and replacement cost are not represented by feature data")),
                Map.entry("danger_cost", EconomicFactor.unknown(
                    "biome and dimension evidence does not determine player danger")),
                Map.entry("transport_cost", EconomicFactor.unknown(
                    "biome restrictions do not determine player travel distance")),
                Map.entry("intermediate_cost", EconomicFactor.notApplicable(
                    "worldgen placement has no recursive recipe inputs")),
                Map.entry("resource_consumption_cost", EconomicFactor.unknown(
                    "tool durability and mining consumables are not represented by feature data")),
                Map.entry("material_cost", EconomicFactor.notApplicable(
                    "worldgen placement has no consumed player material inputs")));
            Map<Integer, CostVector> costsByHorizon = new HashMap<>();
            for (int horizon : supportedHorizons()) {
                Map<String, EconomicFactor> horizonFactors = new HashMap<>(costFactors);
                CostVector extractionCosts = source.extractionPath() == null
                    ? null : source.extractionPath().costsByHorizon().get(horizon);
                horizonFactors.put("quantity_cost", extractionCosts == null
                    ? EconomicFactor.unknown(
                        "no indexed block-loot extraction quantity is available at this horizon")
                    : extractionCosts.factors().getOrDefault("quantity_cost",
                        EconomicFactor.unknown("block-loot quantity is unavailable at this horizon")));
                costsByHorizon.put(horizon, new CostVector(horizon, horizonFactors));
            }
            Map<String, AcquisitionMeasurement> evidence = new HashMap<>(source.measurements());
            if (source.extractionPath() == null) {
                evidence.put("expected_units_per_attempt", AcquisitionMeasurement.unknown(
                    "no block-loot extraction output is indexed for this generated block"));
                evidence.put("expected_attempts_per_unit", AcquisitionMeasurement.unknown(
                    "block-break loot quantity is unknown"));
            } else {
                evidence.putAll(source.extractionPath().evidence().measurements());
            }
            Map<String, String> attributes = new HashMap<>(source.attributes());
            attributes.put("worldgen_block_id", source.blockId());
            if (source.lootTableId() != null) {
                attributes.put("block_loot_table", source.lootTableId());
            }
            if (source.extractionPath() == null) {
                attributes.put("canonical_quantity_source", "unknown: no indexed block-loot extraction output");
                attributes.put("canonical_quantity_unit", "item per defined extraction operation (unresolved)");
                attributes.put("canonical_quantity_semantics",
                    "UNKNOWN: no block-loot output was resolved for the generated block");
                attributes.put("extraction_operation", "UNKNOWN: block-loot path was not indexed");
            } else {
                Map<String, String> extractionAttributes = source.extractionPath().evidence().attributes();
                attributes.put("canonical_quantity_source",
                    extractionAttributes.getOrDefault("canonical_quantity_source", "expected_units_per_attempt"));
                attributes.put("canonical_quantity_unit",
                    "item per block-break loot invocation");
                attributes.put("canonical_quantity_semantics",
                    extractionAttributes.getOrDefault("canonical_quantity_semantics",
                        "block-loot table quantity per block break"));
                attributes.put("extraction_operation", "break the generated block and evaluate its block loot table");
                attributes.put("extraction_source_path", source.extractionPath().sourceId());
            }
            attributes.put("survival_availability",
                "unknown: generated block discovery does not prove active-world or player access");
            attributes.put("source_availability_classification", "UNKNOWN");
            return new AcquisitionPath(itemId, "worldgen_feature",
                source.sourceId() + (source.extractionPath() == null ? ""
                    : "/extract/" + source.blockId() + "/" + source.lootTableId()), 1.0D,
                null, null, null, false, feasibilityFactors, costsByHorizon,
                new AcquisitionEvidence(evidence, attributes));
        }).toList();
    }

    public Set<String> indexedItemIds() {
        return sourcesByItem.keySet();
    }

    public Map<String, Integer> sourceCounts() {
        return Map.of("worldgen_feature", sourcesByItem.values().stream().mapToInt(List::size).sum());
    }

    private static Map<String, JsonObject> resourcesInDirectory(Map<String, JsonObject> resources, String directory) {
        String prefix = directory + "/";
        Map<String, JsonObject> result = new HashMap<>();
        resources.forEach((resourceId, json) -> {
            ResourceLocation location = ResourceLocation.tryParse(resourceId);
            if (location == null || !location.getPath().startsWith(prefix)
                || !location.getPath().endsWith(".json")) {
                return;
            }
            String logicalPath = location.getPath().substring(prefix.length());
            logicalPath = logicalPath.substring(0, logicalPath.length() - ".json".length());
            ResourceLocation logicalId = ResourceLocation.fromNamespaceAndPath(location.getNamespace(), logicalPath);
            result.put(logicalId.toString(), json);
        });
        return Map.copyOf(result);
    }

    private static Map<String, Set<String>> discoverBiomeDimensions(Map<String, JsonObject> resources,
        Set<String> biomeIds) {
        Map<String, JsonObject> dimensions = resourcesInDirectory(resources, "worldgen/dimension");
        Map<String, JsonObject> parameterLists =
            resourcesInDirectory(resources, "worldgen/multi_noise_biome_source_parameter_list");
        Map<String, Set<String>> result = new HashMap<>();
        dimensions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject generator = entry.getValue().getAsJsonObject("generator");
            JsonObject biomeSource = generator == null ? null : generator.getAsJsonObject("biome_source");
            if (biomeSource == null) {
                return;
            }
            Set<String> referencedBiomes = new TreeSet<>();
            collectKnownReferences(biomeSource, biomeIds, referencedBiomes);
            String preset = string(biomeSource, "preset");
            JsonObject parameterList = preset == null ? null : parameterLists.get(preset);
            if (parameterList != null) {
                collectKnownReferences(parameterList, biomeIds, referencedBiomes);
            }
            for (String biome : referencedBiomes) {
                result.computeIfAbsent(biome, ignored -> new TreeSet<>()).add(entry.getKey());
            }
        });
        Map<String, Set<String>> frozen = new HashMap<>();
        result.forEach((biome, ids) -> frozen.put(biome, Set.copyOf(ids)));
        return Map.copyOf(frozen);
    }

    private static List<Integer> supportedHorizons() {
        return java.util.stream.Stream.of(1, 10, 100,
                com.jorjik.dynamicfood.config.DynamicFoodConfig.acquisitionEconomicHorizon())
            .distinct().sorted().toList();
    }

    private static Optional<JsonObject> parseResource(ResourceLocation id, Resource resource) {
        try (var reader = resource.openAsReader()) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.isJsonObject() ? Optional.of(parsed.getAsJsonObject()) : Optional.empty();
        } catch (IOException | RuntimeException exception) {
            com.jorjik.dynamicfood.DynamicFood.LOGGER.warn("Unable to inspect worldgen data resource {}", id, exception);
            return Optional.empty();
        }
    }

    private static void collectKnownReferences(JsonElement element, Set<String> knownIds, Set<String> found) {
        if (element == null) {
            return;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectKnownReferences(child, knownIds, found));
        } else if (element.isJsonObject()) {
            element.getAsJsonObject().entrySet()
                .forEach(entry -> collectKnownReferences(entry.getValue(), knownIds, found));
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            ResourceLocation location = ResourceLocation.tryParse(value);
            if (location != null && knownIds.contains(location.toString())) {
                found.add(location.toString());
            }
        }
    }

    private static WorldgenFeatureEvidence placedFeatureEvidence(JsonObject placedFeature) {
        if (placedFeature == null || !placedFeature.has("placement")
            || !placedFeature.get("placement").isJsonArray()) {
            return new WorldgenFeatureEvidence(Map.of(), Map.of());
        }
        Map<String, AcquisitionMeasurement> measurements = new HashMap<>();
        Map<String, String> attributes = new HashMap<>();
        var modifiers = placedFeature.getAsJsonArray("placement");
        for (int index = 0; index < modifiers.size(); index++) {
            JsonElement element = modifiers.get(index);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject modifier = element.getAsJsonObject();
            String type = string(modifier, "type");
            if ("minecraft:count".equals(type)) {
                measurements.put("placement_count_modifier_" + index,
                    exactNonNegativeNumber(modifier.get("count"), "placement count is not a literal constant"));
            } else if ("minecraft:rarity_filter".equals(type)) {
                OptionalDouble chance = exactPositiveInteger(modifier.get("chance"));
                measurements.put("rarity_filter_chance_" + index, chance.isPresent()
                    ? AcquisitionMeasurement.known(1.0D / chance.getAsDouble())
                    : AcquisitionMeasurement.unknown("rarity-filter chance is not a positive integer"));
            } else if ("minecraft:height_range".equals(type)) {
                attributes.put("height_restriction_" + index,
                    "height range is configured; range distribution is not reduced to item yield");
            } else if ("minecraft:biome".equals(type)) {
                attributes.put("biome_filter_" + index, "placed-feature biome filter is present");
            }
        }
        return new WorldgenFeatureEvidence(Map.copyOf(measurements), Map.copyOf(attributes));
    }

    private static WorldgenFeatureEvidence configuredFeatureEvidence(JsonObject configuredFeature) {
        Map<String, AcquisitionMeasurement> measurements = new HashMap<>();
        Map<String, String> attributes = new HashMap<>();
        String type = string(configuredFeature, "feature");
        if (type != null) {
            attributes.put("configured_feature_type", type);
        }
        JsonObject config = configuredFeature.getAsJsonObject("config");
        if (config != null && config.has("size")) {
            measurements.put("configured_cluster_size", exactNonNegativeNumber(config.get("size"),
                "configured cluster size is not a literal constant"));
        } else {
            measurements.put("configured_cluster_size",
                AcquisitionMeasurement.unknown("configured feature has no literal cluster size"));
        }
        return new WorldgenFeatureEvidence(Map.copyOf(measurements), Map.copyOf(attributes));
    }

    private static Map<String, AcquisitionMeasurement> mergeMeasurements(
        Map<String, AcquisitionMeasurement> placed,
        Set<String> configuredIds, Map<String, WorldgenFeatureEvidence> configured) {
        Map<String, AcquisitionMeasurement> result = new HashMap<>(placed);
        for (String id : configuredIds) {
            WorldgenFeatureEvidence evidence = configured.get(id);
            if (evidence != null) {
                evidence.measurements().forEach((name, value) ->
                    result.put("configured:" + id + ":" + name, value));
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> mergeAttributes(Map<String, String> attributes,
        Map<String, String> additional) {
        Map<String, String> result = new HashMap<>(attributes);
        result.putAll(additional);
        return Map.copyOf(result);
    }

    private static AcquisitionMeasurement exactNonNegativeNumber(JsonElement element, String unknownReason) {
        OptionalDouble number = exactNumber(element);
        return number.isPresent() && number.getAsDouble() >= 0.0D
            ? AcquisitionMeasurement.known(number.getAsDouble())
            : AcquisitionMeasurement.unknown(unknownReason);
    }

    private static OptionalDouble exactPositiveInteger(JsonElement element) {
        OptionalDouble number = exactNumber(element);
        if (number.isEmpty() || number.getAsDouble() < 1.0D
            || number.getAsDouble() != Math.rint(number.getAsDouble())) {
            return OptionalDouble.empty();
        }
        return number;
    }

    private static OptionalDouble exactNumber(JsonElement element) {
        if (element == null) {
            return OptionalDouble.empty();
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            double value = element.getAsDouble();
            return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if ("minecraft:constant".equals(string(object, "type"))) {
                return exactNumber(object.get("value"));
            }
        }
        return OptionalDouble.empty();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : null;
    }

    private static void collectBlockStateNames(JsonElement element, Set<String> found) {
        if (element == null) {
            return;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectBlockStateNames(child, found));
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement name = object.get("Name");
            if (name != null && name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()) {
                ResourceLocation blockId = ResourceLocation.tryParse(name.getAsString());
                if (blockId != null) {
                    found.add(blockId.toString());
                }
            }
            object.entrySet().forEach(entry -> collectBlockStateNames(entry.getValue(), found));
        }
    }

    private record WorldgenSource(String sourceId, String biomeId, String placedFeatureId, String blockId,
        String lootTableId, Map<String, AcquisitionMeasurement> measurements, Map<String, String> attributes,
        AcquisitionPath extractionPath) {}

    private record WorldgenFeatureEvidence(Map<String, AcquisitionMeasurement> measurements,
        Map<String, String> attributes) {}

    public record WorldgenBlockSource(String sourceId, String biomeId, String placedFeatureId,
        Map<String, AcquisitionMeasurement> measurements, Map<String, String> attributes) {
        public WorldgenBlockSource(String sourceId, String biomeId, String placedFeatureId) {
            this(sourceId, biomeId, placedFeatureId, Map.of(), Map.of());
        }

        public WorldgenBlockSource {
            measurements = Map.copyOf(measurements);
            attributes = Map.copyOf(attributes);
        }
    }
}
