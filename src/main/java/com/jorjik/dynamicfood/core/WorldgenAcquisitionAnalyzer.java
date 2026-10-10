package com.jorjik.dynamicfood.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
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
            "worldgen/configured_feature", "dimension",
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

    public WorldgenAcquisitionAnalyzer withActiveDimensionBiomes(Map<String, Set<String>> biomesByDimension) {
        if (biomesByDimension == null) {
            throw new IllegalArgumentException("active worldgen dimension evidence is required");
        }
        Map<String, List<WorldgenSource>> updated = new HashMap<>();
        sourcesByItem.forEach((itemId, sources) -> updated.put(itemId, sources.stream().map(source -> {
            Set<String> dimensions = new TreeSet<>();
            biomesByDimension.forEach((dimensionId, biomeIds) -> {
                if (biomeIds.contains(source.biomeId())) {
                    dimensions.add(dimensionId);
                }
            });
            Map<String, String> attributes = new HashMap<>(source.attributes());
            attributes.put("dimension", dimensions.isEmpty()
                ? "unknown: biome is not present in the active server dimension biome sources"
                : String.join(",", dimensions));
            attributes.put("active_dimension", dimensions.isEmpty()
                ? "unknown: biome is not present in the active server dimension biome sources"
                : String.join(",", dimensions));
            attributes.put("active_biome_feature_source", dimensions.isEmpty() ? "UNKNOWN" : "TRUE");
            return new WorldgenSource(source.sourceId(), source.biomeId(), source.placedFeatureId(),
                source.blockId(), source.blockItemId(), source.requiresCorrectTool(), source.lootTableId(),
                source.measurements(), Map.copyOf(attributes), source.extractionPath(),
                source.causalEvidence());
        }).toList()));
        return new WorldgenAcquisitionAnalyzer(updated);
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
                new BlockExtraction(itemId, block.getLootTable().location().toString(),
                    block.defaultBlockState().requiresCorrectToolForDrops()));
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
                        blockExtraction.blockItemId(), blockExtraction.requiresCorrectTool(), lootTableId,
                        source.measurements(), source.attributes(), null,
                        addExtractionEvidence(source.causalEvidence(), blockId, blockExtraction, null))));
                return;
            }
            for (WorldgenBlockSource source : sources) {
                for (AcquisitionPath extractionPath : extractionPaths) {
                    indexed.computeIfAbsent(extractionPath.itemId(), ignored -> new ArrayList<>())
                        .add(new WorldgenSource(source.sourceId(), source.biomeId(), source.placedFeatureId(),
                            blockId, blockExtraction.blockItemId(), blockExtraction.requiresCorrectTool(),
                            lootTableId, source.measurements(), source.attributes(), extractionPath,
                            addExtractionEvidence(source.causalEvidence(), blockId, blockExtraction,
                                extractionPath)));
                }
            }
        });
        Map<String, List<WorldgenSource>> frozen = new HashMap<>();
        indexed.forEach((itemId, sources) -> frozen.put(itemId, sources.stream()
            .distinct().sorted(Comparator.comparing(WorldgenSource::sourceId)).toList()));
        return new WorldgenAcquisitionAnalyzer(frozen);
    }

    record BlockExtraction(String blockItemId, String lootTableId, Boolean requiresCorrectTool) {
        BlockExtraction(String blockItemId, String lootTableId) {
            this(blockItemId, lootTableId, null);
        }
    }

    private static WorldgenCausalEvidence.RelationshipType relationshipForTarget(
        WorldgenCausalEvidence.RelationshipType relationship, WorldgenCausalEvidence.NodeType targetType) {
        if (targetType == WorldgenCausalEvidence.NodeType.PLACED_FEATURE
            && (relationship == WorldgenCausalEvidence.RelationshipType.PLACES_CONFIGURED_FEATURE
                || relationship == WorldgenCausalEvidence.RelationshipType.NESTED_CONFIGURED_FEATURE)) {
            return WorldgenCausalEvidence.RelationshipType.NESTED_PLACED_FEATURE;
        }
        if (targetType == WorldgenCausalEvidence.NodeType.CONFIGURED_FEATURE
            && relationship == WorldgenCausalEvidence.RelationshipType.NESTED_PLACED_FEATURE) {
            return WorldgenCausalEvidence.RelationshipType.NESTED_CONFIGURED_FEATURE;
        }
        return relationship;
    }

    private static WorldgenCausalEvidence addExtractionEvidence(WorldgenCausalEvidence causalEvidence,
        String blockId, BlockExtraction extraction, AcquisitionPath extractionPath) {
        String blockEvidence = "block_id=" + blockId + ";requires_correct_tool="
            + Objects.toString(extraction.requiresCorrectTool(), "UNKNOWN");
        String lootTableResource = extraction.lootTableId() == null
            ? "" : worldgenResourceId("loot_table", extraction.lootTableId());
        WorldgenCausalEvidence.NodeRef operation = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.EXTRACTION_OPERATION, "block_break:" + blockId);
        WorldgenCausalEvidence.Builder addition = new WorldgenCausalEvidence.Builder()
            .addNode(operation.type(), operation.identifier(), "", blockEvidence);
        List<WorldgenCausalEvidence.NodeRef> states = causalEvidence.nodes().stream()
            .map(WorldgenCausalEvidence.Node::ref)
            .filter(ref -> ref.type() == WorldgenCausalEvidence.NodeType.BLOCK_STATE
                && ref.identifier().startsWith(blockId + "|"))
            .distinct().toList();
        causalEvidence.nodes().stream()
            .filter(node -> node.ref().type() == WorldgenCausalEvidence.NodeType.BLOCK_STATE
                && node.ref().identifier().startsWith(blockId + "|"))
            .forEach(node -> addition.addNode(node.ref().type(), node.ref().identifier(),
                node.sourceResource(), node.rawEvidence()));
        if (states.isEmpty()) {
            WorldgenCausalEvidence.NodeRef state = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.BLOCK_STATE, blockId);
            addition.addNode(state.type(), state.identifier(), extraction.lootTableId(), "");
            states = List.of(state);
        }
        for (WorldgenCausalEvidence.NodeRef state : states) {
            addition.addRelationship(state, WorldgenCausalEvidence.RelationshipType.EXTRACTED_BY, operation,
                WorldgenCausalEvidence.BranchKind.NONE, "", "", "");
        }
        if (extraction.lootTableId() != null) {
            WorldgenCausalEvidence.NodeRef lootTable = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.LOOT_TABLE, extraction.lootTableId());
            addition.addNode(lootTable.type(), lootTable.identifier(), lootTableResource, "")
                .addRelationship(operation, WorldgenCausalEvidence.RelationshipType.USES_LOOT_TABLE, lootTable,
                    WorldgenCausalEvidence.BranchKind.NONE, "", lootTableResource, "");
            if (extractionPath != null) {
                WorldgenCausalEvidence.NodeRef output = new WorldgenCausalEvidence.NodeRef(
                    WorldgenCausalEvidence.NodeType.ITEM_OUTPUT, extractionPath.itemId());
                addition.addNode(output.type(), output.identifier(), lootTableResource,
                        extractionPath.sourceType() + ":" + extractionPath.sourceId())
                    .addRelationship(lootTable, WorldgenCausalEvidence.RelationshipType.YIELDS_ITEM, output,
                        WorldgenCausalEvidence.BranchKind.NONE, extractionPath.sourceId(),
                        lootTableResource, "");
            }
        }
        return causalEvidence.merge(addition.build());
    }

    static WorldgenAcquisitionAnalyzer fromItemSources(
        Map<String, List<WorldgenBlockSource>> sourcesByItem) {
        Map<String, List<WorldgenSource>> indexed = new HashMap<>();
        sourcesByItem.forEach((itemId, sources) -> indexed.put(itemId, sources.stream()
            .map(source -> new WorldgenSource(source.sourceId(), source.biomeId(), source.placedFeatureId(),
                itemId, null, null, null, source.measurements(), source.attributes(), null,
                source.causalEvidence()))
            .toList()));
        return new WorldgenAcquisitionAnalyzer(indexed);
    }

    public static Map<String, List<WorldgenBlockSource>> discoverBlockSources(Map<String, JsonObject> jsonResources) {
        Map<String, JsonObject> biomes = resourcesInDirectory(jsonResources, "worldgen/biome");
        Map<String, JsonObject> placedFeatures = resourcesInDirectory(jsonResources, "worldgen/placed_feature");
        Map<String, JsonObject> configuredFeatures = resourcesInDirectory(jsonResources, "worldgen/configured_feature");
        Map<String, Set<String>> dimensionsByBiome = discoverBiomeDimensions(jsonResources, biomes.keySet());
        Map<String, WorldgenFeatureEvidence> configuredEvidence = new HashMap<>();
        configuredFeatures.forEach((id, json) -> {
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
                WorldgenFeatureClosure featureClosure = configuredFeatureClosure(
                    configuredReferences, configuredFeatures, placedFeatures);
                Set<String> referencedConfiguredFeatures = new TreeSet<>(featureClosure.configuredFeatureIds());
                Map<String, WorldgenCausalEvidence> causalEvidenceByBlock = causalEvidenceForSource(
                    biome.getKey(), biome.getValue(), placedId, placedFeature, configuredFeatures,
                    placedFeatures);
                WorldgenFeatureEvidence placedEvidence = placedFeatureEvidence(placedFeature);
                for (Map.Entry<String, WorldgenCausalEvidence> causalEntry : causalEvidenceByBlock.entrySet()) {
                    String blockId = causalEntry.getKey();
                    String sourceId = biome.getKey() + "/" + placedId;
                    boolean positiveTreeOpportunity = hasPositiveTreeGenerationOpportunity(
                        blockId, placedFeature, configuredReferences, configuredFeatures, placedFeatures);
                    indexed.computeIfAbsent(blockId, ignored -> new ArrayList<>())
                        .add(new WorldgenBlockSource(sourceId, biome.getKey(), placedId,
                            mergeMeasurements(placedEvidence.measurements(), referencedConfiguredFeatures,
                                configuredEvidence),
                            mergeAttributes(Map.of(
                                "biome_restriction", biome.getKey(),
                                "placed_feature", placedId,
                                "configured_features", String.join(",", referencedConfiguredFeatures),
                                "nested_placed_features", String.join(",", featureClosure.nestedPlacedFeatureIds()),
                                "configured_feature_generation_opportunity",
                                    positiveTreeOpportunity ? "TRUE" : "UNKNOWN",
                                "dimension", dimensionsByBiome.getOrDefault(biome.getKey(), Set.of()).isEmpty()
                                    ? "unknown: biome-to-dimension relation is not declared by loaded dimension data"
                                    : String.join(",", dimensionsByBiome.get(biome.getKey()))
                            ), placedEvidence.attributes()), causalEntry.getValue()));
                }
            }
        });
        Map<String, List<WorldgenBlockSource>> frozen = new HashMap<>();
        indexed.forEach((blockId, sources) -> frozen.put(blockId, sources.stream()
            .distinct().sorted(Comparator.comparing(WorldgenBlockSource::sourceId)).toList()));
        return Map.copyOf(frozen);
    }

    private static Map<String, WorldgenCausalEvidence> causalEvidenceForSource(String biomeId,
        JsonObject biome, String placedId, JsonObject placed, Map<String, JsonObject> configuredFeatures,
        Map<String, JsonObject> placedFeatures) {
        WorldgenCausalEvidence.Builder graph = new WorldgenCausalEvidence.Builder();
        WorldgenCausalEvidence.NodeRef biomeRef = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.BIOME, biomeId);
        WorldgenCausalEvidence.NodeRef placedRef = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.PLACED_FEATURE, placedId);
        String biomeResource = worldgenResourceId("worldgen/biome", biomeId);
        String placedResource = worldgenResourceId("worldgen/placed_feature", placedId);
        graph.addNode(biomeRef.type(), biomeRef.identifier(), biomeResource, canonicalJson(biome))
            .addNode(placedRef.type(), placedRef.identifier(), placedResource, canonicalJson(placed));

        List<Integer> stages = featureStageIndices(biome, placedId);
        if (stages.isEmpty()) {
            WorldgenCausalEvidence.NodeRef unresolvedStage = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.FEATURE_STEP, biomeId + "#features:unknown");
            graph.addNode(unresolvedStage.type(), unresolvedStage.identifier(), biomeResource, "")
                .addRelationship(biomeRef, WorldgenCausalEvidence.RelationshipType.DECLARES_FEATURE_STEP,
                    unresolvedStage, WorldgenCausalEvidence.BranchKind.UNKNOWN, "", biomeResource,
                    "placed feature stage could not be resolved from the biome feature declaration")
                .addRelationship(unresolvedStage, WorldgenCausalEvidence.RelationshipType.CONTAINS_PLACED_FEATURE,
                    placedRef, WorldgenCausalEvidence.BranchKind.UNKNOWN, placedId, biomeResource,
                    "placed feature stage could not be resolved from the biome feature declaration");
        } else {
            for (Integer stage : stages) {
                WorldgenCausalEvidence.NodeRef stageRef = new WorldgenCausalEvidence.NodeRef(
                    WorldgenCausalEvidence.NodeType.FEATURE_STEP, biomeId + "#features:" + stage);
                graph.addNode(stageRef.type(), stageRef.identifier(), biomeResource, Integer.toString(stage))
                    .addRelationship(biomeRef, WorldgenCausalEvidence.RelationshipType.DECLARES_FEATURE_STEP,
                        stageRef, WorldgenCausalEvidence.BranchKind.NONE, Integer.toString(stage), biomeResource, "")
                    .addRelationship(stageRef, WorldgenCausalEvidence.RelationshipType.CONTAINS_PLACED_FEATURE,
                        placedRef, WorldgenCausalEvidence.BranchKind.NONE, placedId, biomeResource, "");
            }
        }
        addPlacementModifierEvidence(graph, placedRef, placedId, placed, placedResource);

        Map<String, WorldgenCausalEvidence> outputs = new TreeMap<>();
        Set<String> visitedPlaced = new java.util.HashSet<>();
        Set<String> visitingPlaced = new java.util.HashSet<>();
        Set<String> visitingConfigured = new java.util.HashSet<>();
        JsonElement rootFeature = placed == null ? null : placed.get("feature");
        if (rootFeature == null) {
            unresolvedFeature(graph, placedRef, placedId + "#feature", placedResource,
                rootFeature,
                "placed feature has no resolvable configured-feature reference");
        } else {
            collectNestedFeature(graph, placedRef, rootFeature, "feature",
                WorldgenCausalEvidence.RelationshipType.PLACES_CONFIGURED_FEATURE,
                WorldgenCausalEvidence.BranchKind.NONE, "", placedResource, configuredFeatures,
                placedFeatures, visitedPlaced, visitingPlaced, visitingConfigured, outputs);
        }
        WorldgenCausalEvidence completeEvidence = graph.build();
        Map<String, WorldgenCausalEvidence> result = new TreeMap<>();
        outputs.keySet().forEach(blockId -> result.put(blockId, completeEvidence));
        return Map.copyOf(result);
    }

    private static List<Integer> featureStageIndices(JsonObject biome, String placedId) {
        JsonArray features = biome == null ? null : biome.getAsJsonArray("features");
        if (features == null) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < features.size(); index++) {
            JsonElement stage = features.get(index);
            if (containsDirectReference(stage, placedId)) {
                result.add(index);
            }
        }
        return result.stream().distinct().sorted().toList();
    }

    private static boolean containsDirectReference(JsonElement element, String id) {
        if (element == null) {
            return false;
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray().asList().stream().anyMatch(child -> containsDirectReference(child, id));
        }
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
            && id.equals(normalizeResourceId(element.getAsString()));
    }

    private static void addPlacementModifierEvidence(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef placed, String placedId, JsonObject json, String resourceId) {
        JsonArray placement = json == null ? null : json.getAsJsonArray("placement");
        if (placement == null) {
            unresolvedFeature(graph, placed, placedId + "#placement", resourceId,
                json == null ? null : json.get("placement"), "placed feature placement list is unavailable");
            return;
        }
        for (int index = 0; index < placement.size(); index++) {
            JsonElement element = placement.get(index);
            WorldgenCausalEvidence.NodeRef modifier = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.FEATURE_COMPONENT, placedId + "#placement:" + index);
            graph.addNode(modifier.type(), modifier.identifier(), resourceId, canonicalJson(element))
                .addRelationship(placed, WorldgenCausalEvidence.RelationshipType.HAS_FEATURE_COMPONENT,
                    modifier, WorldgenCausalEvidence.BranchKind.NONE, "placement_modifier:" + index,
                    resourceId, "");
        }
    }

    private static void linkFeatureReference(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef parent, String reference,
        WorldgenCausalEvidence.RelationshipType relationship, WorldgenCausalEvidence.BranchKind branchKind,
        String branchEvidence, String sourceResource, Map<String, JsonObject> configuredFeatures,
        Map<String, JsonObject> placedFeatures, Set<String> visitedPlaced, Set<String> visitingPlaced,
        Set<String> visitingConfigured, Map<String, WorldgenCausalEvidence> outputs) {
        String id = normalizeResourceId(reference);
        if (id == null) {
            unresolvedFeature(graph, parent, parent.identifier() + "#unresolved-reference", sourceResource,
                new JsonPrimitive(reference), "feature reference is not a valid resource identifier");
            return;
        }
        if (configuredFeatures.containsKey(id)) {
            WorldgenCausalEvidence.NodeRef configured = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.CONFIGURED_FEATURE, id);
            JsonObject json = configuredFeatures.get(id);
            String resource = worldgenResourceId("worldgen/configured_feature", id);
            graph.addNode(configured.type(), configured.identifier(), resource, canonicalJson(json))
                .addRelationship(parent, relationshipForTarget(relationship, configured.type()),
                    configured, branchKind, branchEvidence, sourceResource, "");
            collectConfiguredFeatureEvidence(graph, configured, id, json, resource, configuredFeatures,
                placedFeatures, visitedPlaced, visitingPlaced, visitingConfigured, outputs);
            return;
        }
        if (placedFeatures.containsKey(id)) {
            WorldgenCausalEvidence.NodeRef nestedPlaced = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.PLACED_FEATURE, id);
            JsonObject json = placedFeatures.get(id);
            String resource = worldgenResourceId("worldgen/placed_feature", id);
            WorldgenCausalEvidence.RelationshipType resolvedRelationship =
                relationshipForTarget(relationship, nestedPlaced.type());
            graph.addNode(nestedPlaced.type(), nestedPlaced.identifier(), resource, canonicalJson(json))
                .addRelationship(parent, resolvedRelationship,
                    nestedPlaced, branchKind, branchEvidence, sourceResource, "");
            collectPlacedFeatureEvidence(graph, nestedPlaced, id, json, resource, configuredFeatures,
                placedFeatures, visitedPlaced, visitingPlaced, visitingConfigured, outputs);
            return;
        }
        unresolvedFeature(graph, parent, id, sourceResource, new JsonPrimitive(id),
            "referenced configured or placed feature is not present in the loaded datapack resources");
    }

    private static void collectPlacedFeatureEvidence(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef placed, String placedId, JsonObject json, String resourceId,
        Map<String, JsonObject> configuredFeatures, Map<String, JsonObject> placedFeatures,
        Set<String> visitedPlaced, Set<String> visitingPlaced, Set<String> visitingConfigured,
        Map<String, WorldgenCausalEvidence> outputs) {
        if (!visitedPlaced.add(placedId)) {
            if (visitingPlaced.contains(placedId)) {
                unresolvedFeature(graph, placed, placedId + "#cycle", resourceId, null,
                    "cyclic placed-feature reference was not traversed");
            }
            return;
        }
        visitingPlaced.add(placedId);
        addPlacementModifierEvidence(graph, placed, placedId, json, resourceId);
        JsonElement root = json == null ? null : json.get("feature");
        if (root == null) {
            unresolvedFeature(graph, placed, placedId + "#feature", resourceId, json.get("feature"),
                "placed feature has no resolvable configured-feature reference");
        } else {
            collectNestedFeature(graph, placed, root, "feature",
                WorldgenCausalEvidence.RelationshipType.PLACES_CONFIGURED_FEATURE,
                WorldgenCausalEvidence.BranchKind.NONE, "", resourceId, configuredFeatures,
                placedFeatures, visitedPlaced, visitingPlaced, visitingConfigured, outputs);
        }
        visitingPlaced.remove(placedId);
    }

    private static void collectConfiguredFeatureEvidence(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef configured, String configuredId, JsonObject json, String resourceId,
        Map<String, JsonObject> configuredFeatures, Map<String, JsonObject> placedFeatures,
        Set<String> visitedPlaced, Set<String> visitingPlaced, Set<String> visitingConfigured,
        Map<String, WorldgenCausalEvidence> outputs) {
        if (!visitingConfigured.add(configuredId)) {
            unresolvedFeature(graph, configured, configuredId + "#cycle", resourceId, null,
                "cyclic configured-feature reference was not traversed");
            return;
        }
        String type = configuredType(json);
        JsonObject config = json == null ? null : json.getAsJsonObject("config");
        if (config == null) {
            unresolvedFeature(graph, configured, configuredId + "#config", resourceId,
                json == null ? null : json.get("config"),
                "configured feature has no decodable configuration object");
        } else if ("minecraft:tree".equals(type)) {
            collectTreeOutputs(graph, configured, configuredId, config, resourceId, outputs);
            JsonArray decorators = config.getAsJsonArray("decorators");
            if (decorators != null && !decorators.isEmpty()) {
                collectTreeDecoratorOutputs(graph, configured, configuredId, decorators, resourceId, outputs);
            }
        } else if ("minecraft:root_system".equals(type)) {
            unresolvedConfiguredMechanic(graph, configured, configuredId, config, resourceId,
                "root-system placement conditions and output distribution are not interpreted");
            collectProviderOutputs(graph, configured, configuredId, "root_state_provider",
                config.get("root_state_provider"), resourceId, outputs);
            collectProviderOutputs(graph, configured, configuredId, "hanging_root_state_provider",
                config.get("hanging_root_state_provider"), resourceId, outputs);
            collectNestedFeature(graph, configured, config.get("feature"), "config.feature",
                WorldgenCausalEvidence.RelationshipType.NESTED_CONFIGURED_FEATURE,
                WorldgenCausalEvidence.BranchKind.NONE, "", resourceId, configuredFeatures,
                placedFeatures, visitedPlaced, visitingPlaced, visitingConfigured, outputs);
        } else if ("minecraft:vegetation_patch".equals(type)
            || "minecraft:waterlogged_vegetation_patch".equals(type)) {
            unresolvedConfiguredMechanic(graph, configured, configuredId, config, resourceId,
                "vegetation-patch placement and ground replacement are not interpreted");
            collectProviderOutputs(graph, configured, configuredId, "ground_state",
                config.get("ground_state"), resourceId, outputs);
            JsonElement vegetationFeature = config.get("vegetation_feature");
            if (vegetationFeature == null) {
                unresolvedFeature(graph, configured, configuredId + "#vegetation_feature", resourceId,
                    null, "vegetation-patch feature is unavailable");
            } else {
                collectNestedFeature(graph, configured, vegetationFeature, "vegetation_feature",
                    WorldgenCausalEvidence.RelationshipType.CONDITIONAL_BRANCH,
                    WorldgenCausalEvidence.BranchKind.CONDITIONAL,
                    "vegetation_chance=" + canonicalJson(config.get("vegetation_chance")),
                    resourceId, configuredFeatures, placedFeatures, visitedPlaced,
                    visitingPlaced, visitingConfigured, outputs);
            }
        } else if ("minecraft:ore".equals(type)) {
            collectOreOutputs(graph, configured, configuredId, config, resourceId, outputs);
        } else if ("minecraft:simple_block".equals(type)) {
            collectProviderOutputs(graph, configured, configuredId, "to_place",
                config.get("to_place"), resourceId, outputs);
        } else if ("minecraft:random_selector".equals(type)) {
            collectRandomSelectorBranches(graph, configured, configuredId, config, resourceId,
                configuredFeatures, placedFeatures, visitedPlaced, visitingPlaced, visitingConfigured, outputs);
        } else if ("minecraft:random_boolean_selector".equals(type)) {
            collectBooleanSelectorBranches(graph, configured, configuredId, config, resourceId,
                configuredFeatures, placedFeatures, visitedPlaced, visitingPlaced, visitingConfigured, outputs);
        } else if ("minecraft:decorated".equals(type)) {
            collectNestedFeature(graph, configured, config.get("feature"), "config.feature",
                WorldgenCausalEvidence.RelationshipType.NESTED_CONFIGURED_FEATURE,
                WorldgenCausalEvidence.BranchKind.NONE, "", resourceId, configuredFeatures,
                placedFeatures, visitedPlaced, visitingPlaced, visitingConfigured, outputs);
        } else if ("minecraft:random_patch".equals(type) || "minecraft:flower".equals(type)) {
            collectNestedFeature(graph, configured, config.get("feature"), "config.feature",
                WorldgenCausalEvidence.RelationshipType.NESTED_PLACED_FEATURE,
                WorldgenCausalEvidence.BranchKind.NONE, "", resourceId, configuredFeatures,
                placedFeatures, visitedPlaced, visitingPlaced, visitingConfigured, outputs);
        } else {
            unresolvedConfiguredOutputs(graph, configured, configuredId, config, resourceId, outputs,
                "configured feature type is not supported for output interpretation: "
                    + Objects.toString(type, "missing type"));
        }
        visitingConfigured.remove(configuredId);
    }

    private static void collectTreeOutputs(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef configured, String configuredId, JsonObject config,
        String resourceId, Map<String, WorldgenCausalEvidence> outputs) {
        for (String provider : List.of("trunk_provider", "foliage_provider", "dirt_provider")) {
            collectProviderOutputs(graph, configured, configuredId, provider, config.get(provider),
                resourceId, outputs);
        }
        JsonObject rootPlacer = config.getAsJsonObject("root_placer");
        if (rootPlacer != null && rootPlacer.has("root_provider")) {
            collectProviderOutputs(graph, configured, configuredId, "root_placer.root_provider",
                rootPlacer.get("root_provider"), resourceId, outputs);
        }
        JsonObject aboveRootPlacement = rootPlacer == null
            ? null : rootPlacer.getAsJsonObject("above_root_placement");
        if (aboveRootPlacement != null && aboveRootPlacement.has("above_root_provider")) {
            collectProviderOutputs(graph, configured, configuredId,
                "root_placer.above_root_placement.above_root_provider",
                aboveRootPlacement.get("above_root_provider"), resourceId, outputs);
        }
        JsonObject mangroveRootPlacement = rootPlacer == null
            ? null : rootPlacer.getAsJsonObject("mangrove_root_placement");
        if (mangroveRootPlacement != null && mangroveRootPlacement.has("muddy_roots_provider")) {
            collectProviderOutputs(graph, configured, configuredId,
                "root_placer.mangrove_root_placement.muddy_roots_provider",
                mangroveRootPlacement.get("muddy_roots_provider"), resourceId, outputs);
        }
    }

    private static void collectTreeDecoratorOutputs(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef configured, String configuredId, JsonArray decorators,
        String resourceId, Map<String, WorldgenCausalEvidence> outputs) {
        for (int index = 0; index < decorators.size(); index++) {
            JsonElement decoratorElement = decorators.get(index);
            JsonObject decorator = decoratorElement.isJsonObject() ? decoratorElement.getAsJsonObject() : null;
            String type = decorator == null ? null : string(decorator, "type");
            String role = "decorator:" + index;
            WorldgenCausalEvidence.NodeRef decoratorRef = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.FEATURE_COMPONENT, configuredId + "#" + role);
            graph.addNode(decoratorRef.type(), decoratorRef.identifier(), resourceId,
                    canonicalJson(decoratorElement))
                .addRelationship(configured, WorldgenCausalEvidence.RelationshipType.HAS_FEATURE_COMPONENT,
                    decoratorRef, WorldgenCausalEvidence.BranchKind.NONE, role, resourceId, "");
            if ("minecraft:attached_to_leaves".equals(type)) {
                collectProviderOutputs(graph, decoratorRef, configuredId, role + ".block_provider",
                    decorator == null ? null : decorator.get("block_provider"), resourceId, outputs);
            } else if ("minecraft:alter_ground".equals(type)) {
                collectProviderOutputs(graph, decoratorRef, configuredId, role + ".provider",
                    decorator == null ? null : decorator.get("provider"), resourceId, outputs,
                    WorldgenCausalEvidence.BranchKind.CONDITIONAL, "decorator=minecraft:alter_ground");
            } else {
                unresolvedFeature(graph, decoratorRef, configuredId + "#" + role + "#unresolved",
                    resourceId, decoratorElement,
                    "unsupported tree decorator type: " + Objects.toString(type, "missing type"));
            }
        }
    }

    private static void unresolvedConfiguredMechanic(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef configured, String configuredId, JsonObject config,
        String resourceId, String reason) {
        WorldgenCausalEvidence.NodeRef unresolved = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.UNRESOLVED_MECHANIC, configuredId + "#unresolved-mechanic");
        graph.addNode(unresolved.type(), unresolved.identifier(), resourceId, canonicalJson(config))
            .addRelationship(configured, WorldgenCausalEvidence.RelationshipType.UNRESOLVED,
                unresolved, WorldgenCausalEvidence.BranchKind.UNKNOWN, "", resourceId, reason);
    }

    private static void collectOreOutputs(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef configured, String configuredId, JsonObject config,
        String resourceId, Map<String, WorldgenCausalEvidence> outputs) {
        JsonArray targets = config.getAsJsonArray("targets");
        if (targets == null) {
            unresolvedConfiguredOutputs(graph, configured, configuredId, config, resourceId, outputs,
                "ore feature target list is unavailable or malformed");
            return;
        }
        for (int index = 0; index < targets.size(); index++) {
            JsonElement targetElement = targets.get(index);
            JsonObject target = targetElement.isJsonObject() ? targetElement.getAsJsonObject() : null;
            JsonElement state = target == null ? null : target.get("state");
            WorldgenCausalEvidence.NodeRef component = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.FEATURE_COMPONENT,
                configuredId + "#ore_target:" + index);
            graph.addNode(component.type(), component.identifier(), resourceId,
                    target == null ? canonicalJson(targetElement) : canonicalJson(target.get("target")))
                .addRelationship(configured, WorldgenCausalEvidence.RelationshipType.HAS_FEATURE_COMPONENT,
                    component, WorldgenCausalEvidence.BranchKind.NONE, "ore_target:" + index, resourceId, "");
            addStateOutput(graph, component, state, resourceId, outputs,
                WorldgenCausalEvidence.RelationshipType.MAY_GENERATE_BLOCK,
                WorldgenCausalEvidence.BranchKind.ORDERED_CONDITIONAL,
                "target[" + index + "]=" + canonicalJson(target == null ? null : target.get("target")), "");
        }
    }

    private static void collectProviderOutputs(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef configured, String configuredId, String role, JsonElement providerElement,
        String resourceId, Map<String, WorldgenCausalEvidence> outputs) {
        collectProviderOutputs(graph, configured, configuredId, role, providerElement, resourceId, outputs,
            WorldgenCausalEvidence.BranchKind.NONE, "");
    }

    private static void collectProviderOutputs(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef configured, String configuredId, String role, JsonElement providerElement,
        String resourceId, Map<String, WorldgenCausalEvidence> outputs,
        WorldgenCausalEvidence.BranchKind outputBranchKind, String outputBranchEvidence) {
        WorldgenCausalEvidence.NodeRef component = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.FEATURE_COMPONENT, configuredId + "#" + role);
        graph.addNode(component.type(), component.identifier(), resourceId, canonicalJson(providerElement))
            .addRelationship(configured, WorldgenCausalEvidence.RelationshipType.HAS_FEATURE_COMPONENT,
                component, WorldgenCausalEvidence.BranchKind.NONE, role, resourceId, "");
        JsonObject provider = providerElement != null && providerElement.isJsonObject()
            ? providerElement.getAsJsonObject() : null;
        String type = provider == null ? null : string(provider, "type");
        if ("minecraft:simple_state_provider".equals(type)
            || "minecraft:rotated_block_provider".equals(type)) {
            addStateOutput(graph, component, provider.get("state"), resourceId, outputs,
                WorldgenCausalEvidence.RelationshipType.MAY_GENERATE_BLOCK,
                outputBranchKind, outputBranchEvidence, "");
        } else if ("minecraft:randomized_int_state_provider".equals(type)) {
            JsonElement source = provider.get("source");
            if (source == null) {
                addStateOutput(graph, component, null, resourceId, outputs,
                    WorldgenCausalEvidence.RelationshipType.UNRESOLVED,
                    WorldgenCausalEvidence.BranchKind.UNKNOWN, "",
                    "randomized integer state provider has no source provider");
            } else {
                collectProviderOutputs(graph, component, configuredId, role + ".source",
                    source, resourceId, outputs, outputBranchKind, outputBranchEvidence);
            }
        } else if ("minecraft:weighted_state_provider".equals(type)) {
            JsonArray entries = provider.getAsJsonArray("entries");
            if (entries == null) {
                addStateOutput(graph, component, providerElement, resourceId, outputs,
                    WorldgenCausalEvidence.RelationshipType.UNRESOLVED,
                    WorldgenCausalEvidence.BranchKind.UNKNOWN, "",
                    "weighted block-state provider entries are unavailable");
            } else {
                for (int index = 0; index < entries.size(); index++) {
                    JsonElement entryElement = entries.get(index);
                    JsonObject entry = entryElement.isJsonObject() ? entryElement.getAsJsonObject() : null;
                    addStateOutput(graph, component, entry == null ? null : entry.get("data"), resourceId,
                        outputs, WorldgenCausalEvidence.RelationshipType.MAY_GENERATE_BLOCK,
                        WorldgenCausalEvidence.BranchKind.ALTERNATIVE,
                        (outputBranchEvidence.isBlank() ? "" : outputBranchEvidence + ";")
                            + "weighted_state_provider.weight="
                            + canonicalJson(entry == null ? null : entry.get("weight")),
                        "");
                }
            }
        } else if (providerElement == null) {
            addStateOutput(graph, component, null, resourceId, outputs,
                WorldgenCausalEvidence.RelationshipType.UNRESOLVED,
                WorldgenCausalEvidence.BranchKind.UNKNOWN, "", "required block-state provider is absent");
        } else {
            addStateOutput(graph, component, providerElement, resourceId, outputs,
                WorldgenCausalEvidence.RelationshipType.UNRESOLVED,
                WorldgenCausalEvidence.BranchKind.UNKNOWN, "",
                "unsupported block-state provider type: " + Objects.toString(type, "missing type"));
        }
    }

    private static void collectRandomSelectorBranches(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef configured, String configuredId, JsonObject config, String resourceId,
        Map<String, JsonObject> configuredFeatures, Map<String, JsonObject> placedFeatures,
        Set<String> visitedPlaced, Set<String> visitingPlaced, Set<String> visitingConfigured,
        Map<String, WorldgenCausalEvidence> outputs) {
        JsonArray features = config.getAsJsonArray("features");
        if (features != null) {
            for (int index = 0; index < features.size(); index++) {
                JsonElement branchElement = features.get(index);
                JsonObject branch = branchElement.isJsonObject() ? branchElement.getAsJsonObject() : null;
                JsonElement feature = branch == null ? null : branch.get("feature");
                boolean supportedFeature = feature != null
                    && (feature.isJsonPrimitive() && feature.getAsJsonPrimitive().isString()
                        || feature.isJsonObject() && feature.getAsJsonObject().has("feature")
                            && feature.getAsJsonObject().has("placement"));
                if (!supportedFeature) {
                    unresolvedFeature(graph, configured, configuredId + "#features:" + index,
                        resourceId, branchElement, "random selector branch reference is unresolved");
                } else {
                    collectNestedFeature(graph, configured, feature, "features[" + index + "]",
                        WorldgenCausalEvidence.RelationshipType.ALTERNATIVE_BRANCH,
                        WorldgenCausalEvidence.BranchKind.ALTERNATIVE,
                        "index=" + index + ";chance=" + canonicalJson(branch.get("chance")),
                        resourceId, configuredFeatures, placedFeatures, visitedPlaced,
                        visitingPlaced, visitingConfigured, outputs);
                }
            }
        } else {
            unresolvedFeature(graph, configured, configuredId + "#features", resourceId, null,
                "random selector feature branches are unavailable");
        }
        JsonElement defaultFeature = config.get("default");
        if (defaultFeature == null) {
            unresolvedFeature(graph, configured, configuredId + "#default", resourceId, null,
                "random selector default branch is unavailable");
        } else {
            collectNestedFeature(graph, configured, defaultFeature, "default",
                WorldgenCausalEvidence.RelationshipType.DEFAULT_BRANCH,
                WorldgenCausalEvidence.BranchKind.DEFAULT, "selector_default",
                resourceId, configuredFeatures, placedFeatures, visitedPlaced,
                visitingPlaced, visitingConfigured, outputs);
        }
    }

    private static void collectBooleanSelectorBranches(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef configured, String configuredId, JsonObject config, String resourceId,
        Map<String, JsonObject> configuredFeatures, Map<String, JsonObject> placedFeatures,
        Set<String> visitedPlaced, Set<String> visitingPlaced, Set<String> visitingConfigured,
        Map<String, WorldgenCausalEvidence> outputs) {
        for (String branch : List.of("feature_true", "feature_false")) {
            JsonElement value = config.get(branch);
            if (value == null) {
                unresolvedFeature(graph, configured, configuredId + "#" + branch, resourceId, null,
                    "random boolean selector branch is unavailable: " + branch);
            } else {
                collectNestedFeature(graph, configured, value, branch,
                    WorldgenCausalEvidence.RelationshipType.CONDITIONAL_BRANCH,
                    WorldgenCausalEvidence.BranchKind.CONDITIONAL, branch,
                    resourceId, configuredFeatures, placedFeatures, visitedPlaced,
                    visitingPlaced, visitingConfigured, outputs);
            }
        }
    }

    private static void collectNestedFeature(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef parent, JsonElement feature, String role,
        WorldgenCausalEvidence.RelationshipType relationship, WorldgenCausalEvidence.BranchKind branchKind,
        String branchEvidence, String sourceResource, Map<String, JsonObject> configuredFeatures,
        Map<String, JsonObject> placedFeatures, Set<String> visitedPlaced, Set<String> visitingPlaced,
        Set<String> visitingConfigured, Map<String, WorldgenCausalEvidence> outputs) {
        if (feature != null && feature.isJsonPrimitive() && feature.getAsJsonPrimitive().isString()) {
            linkFeatureReference(graph, parent, feature.getAsString(), relationship, branchKind,
                branchEvidence, sourceResource, configuredFeatures, placedFeatures, visitedPlaced, visitingPlaced,
                visitingConfigured, outputs);
        } else if (feature != null && feature.isJsonObject()
            && feature.getAsJsonObject().has("feature") && feature.getAsJsonObject().has("placement")) {
            JsonObject inline = feature.getAsJsonObject();
            String inlineId = parent.identifier() + "#" + role + ":" + canonicalJson(inline);
            WorldgenCausalEvidence.NodeRef inlinePlaced = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.PLACED_FEATURE, inlineId);
            graph.addNode(inlinePlaced.type(), inlinePlaced.identifier(), sourceResource, canonicalJson(inline))
                .addRelationship(parent, relationshipForTarget(relationship, inlinePlaced.type()),
                    inlinePlaced, branchKind, branchEvidence,
                    sourceResource, "");
            addPlacementModifierEvidence(graph, inlinePlaced, inlineId, inline, sourceResource);
            JsonElement root = inline.get("feature");
            if (root == null) {
                unresolvedFeature(graph, inlinePlaced, inlineId + "#feature", sourceResource,
                    inline.get("feature"), "inline placed feature has no configured-feature reference");
            } else if (root.isJsonPrimitive() && root.getAsJsonPrimitive().isString()) {
                linkFeatureReference(graph, inlinePlaced, root.getAsString(),
                    WorldgenCausalEvidence.RelationshipType.PLACES_CONFIGURED_FEATURE,
                    WorldgenCausalEvidence.BranchKind.NONE, "", sourceResource, configuredFeatures,
                    placedFeatures, visitedPlaced, visitingPlaced, visitingConfigured, outputs);
            } else {
                collectNestedFeature(graph, inlinePlaced, root, "configured_feature",
                    WorldgenCausalEvidence.RelationshipType.PLACES_CONFIGURED_FEATURE,
                    WorldgenCausalEvidence.BranchKind.NONE, "", sourceResource, configuredFeatures,
                    placedFeatures, visitedPlaced, visitingPlaced, visitingConfigured, outputs);
            }
        } else if (feature != null && feature.isJsonObject()
            && feature.getAsJsonObject().has("type") && feature.getAsJsonObject().has("config")) {
            JsonObject inline = feature.getAsJsonObject();
            String inlineId = parent.identifier() + "#" + role + ":" + canonicalJson(inline);
            WorldgenCausalEvidence.NodeRef inlineConfigured = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.CONFIGURED_FEATURE, inlineId);
            graph.addNode(inlineConfigured.type(), inlineConfigured.identifier(), sourceResource,
                    canonicalJson(inline))
                .addRelationship(parent, relationshipForTarget(relationship, inlineConfigured.type()),
                    inlineConfigured, branchKind, branchEvidence,
                    sourceResource, "");
            collectConfiguredFeatureEvidence(graph, inlineConfigured, inlineId, inline, sourceResource,
                configuredFeatures, placedFeatures, visitedPlaced, visitingPlaced, visitingConfigured, outputs);
        } else {
            unresolvedFeature(graph, parent, parent.identifier() + "#" + role, sourceResource, feature,
                "inline or malformed nested feature data is preserved but not interpreted");
        }
    }

    private static void unresolvedConfiguredOutputs(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef configured, String configuredId, JsonObject config, String resourceId,
        Map<String, WorldgenCausalEvidence> outputs, String reason) {
        WorldgenCausalEvidence.NodeRef unresolved = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.UNRESOLVED_MECHANIC, configuredId + "#unresolved-output");
        graph.addNode(unresolved.type(), unresolved.identifier(), resourceId, canonicalJson(config))
            .addRelationship(configured, WorldgenCausalEvidence.RelationshipType.UNRESOLVED,
                unresolved, WorldgenCausalEvidence.BranchKind.UNKNOWN, "", resourceId, reason);
        for (JsonObject state : collectBlockStates(config)) {
            addStateOutput(graph, unresolved, state, resourceId, outputs,
                WorldgenCausalEvidence.RelationshipType.UNRESOLVED,
                WorldgenCausalEvidence.BranchKind.UNKNOWN, "",
                "block-state reference was found in an unsupported configured-feature structure");
        }
    }

    private static void addStateOutput(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef source, JsonElement stateElement, String resourceId,
        Map<String, WorldgenCausalEvidence> outputs,
        WorldgenCausalEvidence.RelationshipType relation,
        WorldgenCausalEvidence.BranchKind branchKind, String branchEvidence, String unresolvedReason) {
        List<JsonObject> states = collectBlockStates(stateElement);
        if (states.isEmpty()) {
            WorldgenCausalEvidence.NodeRef unresolved = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.UNRESOLVED_MECHANIC,
                source.identifier() + "#unresolved-block-state");
            graph.addNode(unresolved.type(), unresolved.identifier(), resourceId, canonicalJson(stateElement))
                .addRelationship(source, WorldgenCausalEvidence.RelationshipType.UNRESOLVED,
                    unresolved, WorldgenCausalEvidence.BranchKind.UNKNOWN, "", resourceId,
                    unresolvedReason.isBlank()
                        ? "block-state provider does not expose a supported state" : unresolvedReason);
            return;
        }
        for (JsonObject state : states) {
            String blockId = normalizeResourceId(string(state, "Name"));
            if (blockId == null) {
                continue;
            }
            String stateJson = canonicalJson(state);
            WorldgenCausalEvidence.NodeRef stateRef = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.BLOCK_STATE, blockId + "|" + stateJson);
            graph.addNode(stateRef.type(), stateRef.identifier(), resourceId, stateJson)
                .addRelationship(source, relation, stateRef, branchKind, branchEvidence, resourceId,
                    unresolvedReason);
            outputs.putIfAbsent(blockId, WorldgenCausalEvidence.empty());
        }
    }

    private static List<JsonObject> collectBlockStates(JsonElement element) {
        List<JsonObject> states = new ArrayList<>();
        collectBlockStates(element, states);
        return states.stream().distinct().sorted(Comparator.comparing(WorldgenAcquisitionAnalyzer::canonicalJson))
            .toList();
    }

    private static void collectBlockStates(JsonElement element, List<JsonObject> states) {
        if (element == null) {
            return;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectBlockStates(child, states));
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (string(object, "Name") != null) {
                states.add(object);
            } else {
                object.entrySet().forEach(entry -> collectBlockStates(entry.getValue(), states));
            }
        }
    }

    private static void unresolvedFeature(WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef parent, String unresolvedId, String resourceId,
        JsonElement rawEvidence, String reason) {
        WorldgenCausalEvidence.NodeRef unresolved = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.UNRESOLVED_MECHANIC, unresolvedId);
        graph.addNode(unresolved.type(), unresolved.identifier(), resourceId, canonicalJson(rawEvidence))
            .addRelationship(parent, WorldgenCausalEvidence.RelationshipType.UNRESOLVED,
                unresolved, WorldgenCausalEvidence.BranchKind.UNKNOWN, "", resourceId, reason);
    }

    private static String configuredType(JsonObject configuredFeature) {
        String type = string(configuredFeature, "type");
        return type == null ? string(configuredFeature, "feature") : type;
    }

    private static String normalizeResourceId(String value) {
        if (value == null) {
            return null;
        }
        ResourceLocation resource = ResourceLocation.tryParse(value);
        return resource == null ? null : resource.toString();
    }

    private static String worldgenResourceId(String directory, String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? id : location.getNamespace() + ":" + directory + "/" + location.getPath() + ".json";
    }

    private static String canonicalJson(JsonElement element) {
        if (element == null) {
            return "";
        }
        if (element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            element.getAsJsonArray().forEach(value -> array.add(JsonParser.parseString(canonicalJson(value))));
            return array.toString();
        }
        if (element.isJsonObject()) {
            JsonObject object = new JsonObject();
            element.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> object.add(entry.getKey(),
                    JsonParser.parseString(canonicalJson(entry.getValue()))));
            return object.toString();
        }
        return element.toString();
    }

    private static WorldgenFeatureClosure configuredFeatureClosure(Set<String> roots,
        Map<String, JsonObject> configuredFeatures, Map<String, JsonObject> placedFeatures) {
        Set<String> reachable = new TreeSet<>();
        Set<String> nestedPlacedFeatures = new TreeSet<>();
        List<String> pending = new ArrayList<>(roots);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!reachable.add(id)) {
                continue;
            }
            JsonObject feature = configuredFeatures.get(id);
            if (feature == null) {
                continue;
            }
            Set<String> references = new TreeSet<>();
            collectConfiguredFeatureReferences(feature.get("config"),
                unionIds(configuredFeatures.keySet(), placedFeatures.keySet()), references);
            for (String reference : references) {
                JsonObject nestedPlacedFeature = placedFeatures.get(reference);
                if (nestedPlacedFeature != null) {
                    if (nestedPlacedFeatures.add(reference)) {
                        Set<String> nestedConfiguredFeatures = new TreeSet<>();
                        collectKnownReferences(nestedPlacedFeature.get("feature"),
                            configuredFeatures.keySet(), nestedConfiguredFeatures);
                        pending.addAll(nestedConfiguredFeatures);
                    }
                } else if (configuredFeatures.containsKey(reference)) {
                    pending.add(reference);
                }
            }
        }
        return new WorldgenFeatureClosure(List.copyOf(reachable), List.copyOf(nestedPlacedFeatures));
    }

    private static boolean hasPositiveTreeGenerationOpportunity(String blockId, JsonObject placedFeature,
        Set<String> configuredRoots, Map<String, JsonObject> configuredFeatures,
        Map<String, JsonObject> placedFeatures) {
        if (!hasPositiveTreePlacement(placedFeature)) {
            return false;
        }
        Set<String> knownFeatureIds = unionIds(configuredFeatures.keySet(), placedFeatures.keySet());
        return configuredRoots.stream().anyMatch(root ->
            hasPositiveTreeOutput(root, blockId, configuredFeatures, placedFeatures, knownFeatureIds,
                new java.util.HashSet<>()));
    }

    private static boolean hasPositiveTreeOutput(String featureId, String blockId,
        Map<String, JsonObject> configuredFeatures, Map<String, JsonObject> placedFeatures,
        Set<String> knownFeatureIds, Set<String> visiting) {
        if (!visiting.add(featureId)) {
            return false;
        }
        JsonObject configured = configuredFeatures.get(featureId);
        boolean found = false;
        if (configured != null) {
            String type = string(configured, "type");
            JsonObject config = configured.getAsJsonObject("config");
            if ("minecraft:tree".equals(type) && config != null) {
                Set<String> trunkBlocks = new TreeSet<>();
                collectBlockStateNames(config.get("trunk_provider"), trunkBlocks);
                found = trunkBlocks.contains(blockId);
            } else if ("minecraft:random_selector".equals(type) && config != null) {
                found = hasPositiveRandomSelectorTreeOutput(config, blockId, configuredFeatures,
                    placedFeatures, knownFeatureIds, visiting);
            } else if ("minecraft:decorated".equals(type) && config != null) {
                Set<String> nested = new TreeSet<>();
                collectKnownReferences(config.get("feature"), knownFeatureIds, nested);
                found = nested.size() == 1 && nested.stream().anyMatch(id ->
                    hasPositiveTreeOutput(id, blockId, configuredFeatures, placedFeatures,
                        knownFeatureIds, visiting));
            }
        } else {
            JsonObject nestedPlaced = placedFeatures.get(featureId);
            if (nestedPlaced != null && hasPositiveTreePlacement(nestedPlaced)) {
                Set<String> nested = new TreeSet<>();
                collectKnownReferences(nestedPlaced.get("feature"), knownFeatureIds, nested);
                found = nested.stream().anyMatch(id ->
                    hasPositiveTreeOutput(id, blockId, configuredFeatures, placedFeatures,
                        knownFeatureIds, visiting));
            }
        }
        visiting.remove(featureId);
        return found;
    }

    private static boolean hasPositiveRandomSelectorTreeOutput(JsonObject config, String blockId,
        Map<String, JsonObject> configuredFeatures, Map<String, JsonObject> placedFeatures,
        Set<String> knownFeatureIds, Set<String> visiting) {
        JsonArray features = config.getAsJsonArray("features");
        if (features == null) {
            return false;
        }
        double remainingChance = 1.0D;
        for (JsonElement element : features) {
            if (!element.isJsonObject()) {
                return false;
            }
            JsonObject branch = element.getAsJsonObject();
            OptionalDouble chance = exactNumber(branch.get("chance"));
            if (chance.isEmpty() || chance.getAsDouble() < 0.0D || chance.getAsDouble() > 1.0D) {
                return false;
            }
            if (chance.getAsDouble() > 0.0D && remainingChance > 0.0D) {
                Set<String> branchFeatures = new TreeSet<>();
                collectKnownReferences(branch.get("feature"), knownFeatureIds, branchFeatures);
                if (branchFeatures.stream().anyMatch(id ->
                    hasPositiveTreeOutput(id, blockId, configuredFeatures, placedFeatures,
                        knownFeatureIds, visiting))) {
                    return true;
                }
            }
            remainingChance *= 1.0D - chance.getAsDouble();
        }
        if (remainingChance <= 0.0D) {
            return false;
        }
        Set<String> defaultFeatures = new TreeSet<>();
        collectKnownReferences(config.get("default"), knownFeatureIds, defaultFeatures);
        return defaultFeatures.stream().anyMatch(id ->
            hasPositiveTreeOutput(id, blockId, configuredFeatures, placedFeatures,
                knownFeatureIds, visiting));
    }

    private static boolean hasPositiveTreePlacement(JsonObject placedFeature) {
        if (placedFeature == null || !placedFeature.has("placement")
            || !placedFeature.get("placement").isJsonArray()) {
            return false;
        }
        boolean positiveCount = false;
        for (JsonElement element : placedFeature.getAsJsonArray("placement")) {
            if (!element.isJsonObject()) {
                return false;
            }
            JsonObject modifier = element.getAsJsonObject();
            String type = string(modifier, "type");
            if ("minecraft:count".equals(type)) {
                Optional<Boolean> positive = hasPositiveCount(modifier.get("count"));
                if (positive.isEmpty() || !positive.get()) {
                    return false;
                }
                positiveCount = true;
            } else if ("minecraft:in_square".equals(type) || "minecraft:biome".equals(type)) {
                continue;
            } else if ("minecraft:surface_water_depth_filter".equals(type)) {
                OptionalDouble depth = exactNumber(modifier.get("max_water_depth"));
                if (depth.isEmpty() || depth.getAsDouble() < 0.0D) {
                    return false;
                }
            } else if ("minecraft:heightmap".equals(type)) {
                String heightmap = string(modifier, "heightmap");
                if (heightmap == null || heightmap.isBlank()) {
                    return false;
                }
            } else if ("minecraft:block_predicate_filter".equals(type)) {
                JsonObject predicate = modifier.getAsJsonObject("predicate");
                JsonObject state = predicate == null ? null : predicate.getAsJsonObject("state");
                ResourceLocation stateBlock = state == null ? null
                    : ResourceLocation.tryParse(string(state, "Name"));
                if (predicate == null || !"minecraft:would_survive".equals(string(predicate, "type"))
                    || stateBlock == null) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return positiveCount;
    }

    private static Optional<Boolean> hasPositiveCount(JsonElement count) {
        OptionalDouble literal = exactNumber(count);
        if (literal.isPresent()) {
            return literal.getAsDouble() < 0.0D ? Optional.empty()
                : Optional.of(literal.getAsDouble() > 0.0D);
        }
        if (count == null || !count.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject weighted = count.getAsJsonObject();
        if (!"minecraft:weighted_list".equals(string(weighted, "type"))
            || weighted.entrySet().stream().anyMatch(entry ->
                !Set.of("type", "distribution").contains(entry.getKey()))) {
            return Optional.empty();
        }
        JsonArray distribution = weighted.getAsJsonArray("distribution");
        if (distribution == null || distribution.isEmpty()) {
            return Optional.empty();
        }
        boolean positiveValue = false;
        for (JsonElement element : distribution) {
            if (!element.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject outcome = element.getAsJsonObject();
            OptionalDouble value = exactNumber(outcome.get("data"));
            OptionalDouble weight = exactNumber(outcome.get("weight"));
            if (value.isEmpty() || value.getAsDouble() < 0.0D
                || weight.isEmpty() || weight.getAsDouble() <= 0.0D
                || outcome.entrySet().stream().anyMatch(entry ->
                    !Set.of("data", "weight").contains(entry.getKey()))) {
                return Optional.empty();
            }
            positiveValue |= value.getAsDouble() > 0.0D;
        }
        return Optional.of(positiveValue);
    }

    private static Set<String> unionIds(Set<String> first, Set<String> second) {
        Set<String> ids = new TreeSet<>(first);
        ids.addAll(second);
        return ids;
    }

    private static void collectConfiguredFeatureReferences(JsonElement element, Set<String> knownIds,
        Set<String> found) {
        if (element == null) {
            return;
        }
        if (element.isJsonObject()) {
            element.getAsJsonObject().entrySet().forEach(entry -> {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                if (key.equals("feature") || key.equals("default") || key.startsWith("feature_")) {
                    collectKnownReferences(value, knownIds, found);
                } else if (key.equals("features") && value.isJsonArray()) {
                    value.getAsJsonArray().forEach(child -> {
                        if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                            collectKnownReferences(child, knownIds, found);
                        } else {
                            collectConfiguredFeatureReferences(child, knownIds, found);
                        }
                    });
                } else {
                    collectConfiguredFeatureReferences(value, knownIds, found);
                }
            });
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child ->
                collectConfiguredFeatureReferences(child, knownIds, found));
        }
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
                Map.entry("equipment_availability", equipmentAvailability(source.requiresCorrectTool())),
                Map.entry("reliability", EconomicFactor.unknown(
                    "runtime generation conditions are not evaluated")
                ));
            Map<EconomicChannel, EconomicFactor> costFactors = new java.util.EnumMap<>(EconomicChannel.class);
            costFactors.put(EconomicChannel.PROBABILITY_BURDEN, EconomicFactor.unknown(
                "worldgen source availability lacks a comparable opportunity unit and bounded burden normalization"));
            costFactors.put(EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN,
                equipmentCost(source.requiresCorrectTool()));
            costFactors.put(EconomicChannel.MATERIAL_CONSUMPTION, EconomicFactor.notApplicable(
                "worldgen block extraction has no separately consumed player material input"));
            Map<Integer, CostVector> costsByHorizon = new HashMap<>();
            for (int horizon : supportedHorizons()) {
                Map<EconomicChannel, EconomicFactor> horizonFactors = new java.util.EnumMap<>(costFactors);
                CostVector extractionCosts = source.extractionPath() == null
                    ? null : source.extractionPath().costsByHorizon().get(horizon);
                horizonFactors.put(EconomicChannel.QUANTITY, extractionCosts == null
                    ? EconomicFactor.unknown(
                        "no indexed block-loot extraction quantity is available at this horizon")
                    : extractionCosts.factor(EconomicChannel.QUANTITY) == null
                        ? EconomicFactor.unknown("block-loot quantity is unavailable at this horizon")
                        : extractionCosts.factor(EconomicChannel.QUANTITY));
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
                attributes.put("ordinary_player_break_output_evidence",
                    extractionAttributes.getOrDefault("ordinary_player_break_output_evidence", "UNKNOWN"));
                String breakEvidenceReason =
                    extractionAttributes.get("ordinary_player_break_output_reason");
                if (breakEvidenceReason != null) {
                    attributes.put("ordinary_player_break_output_reason", breakEvidenceReason);
                }
            }
            if (source.blockItemId() != null) {
                attributes.put("worldgen_block_item_id", source.blockItemId());
            }
            if (source.requiresCorrectTool() != null) {
                attributes.put("worldgen_requires_correct_tool", source.requiresCorrectTool().toString());
            }
            attributes.put("survival_availability",
                "unknown: generated block discovery does not prove active-world or player access");
            attributes.put("source_availability_classification", "UNKNOWN");
            return new AcquisitionPath(itemId, "worldgen_feature",
                source.sourceId() + (source.extractionPath() == null ? ""
                    : "/extract/" + source.blockId() + "/" + source.lootTableId()), 1.0D,
                null, null, null, false, feasibilityFactors, costsByHorizon,
                new AcquisitionEvidence(evidence, attributes, List.of(), source.causalEvidence()));
        }).toList();
    }

    @Override
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
        Map<String, JsonObject> dimensions = resourcesInDirectory(resources, "dimension");
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

    private static EconomicFactor equipmentAvailability(Boolean requiresCorrectTool) {
        if (Boolean.FALSE.equals(requiresCorrectTool)) {
            return EconomicFactor.notApplicable(
                "registered block state proves that no correct tool is required for drops");
        }
        return EconomicFactor.unknown(Boolean.TRUE.equals(requiresCorrectTool)
            ? "registered block requires a correct tool, but player access to that equipment is unresolved"
            : "registered block correct-tool requirement is not exposed");
    }

    private static EconomicFactor equipmentCost(Boolean requiresCorrectTool) {
        if (Boolean.FALSE.equals(requiresCorrectTool)) {
            return EconomicFactor.notApplicable(
                "registered block state proves this extraction path requires no correct tool");
        }
        return EconomicFactor.unknown(Boolean.TRUE.equals(requiresCorrectTool)
            ? "registered block requires a correct tool, but its acquisition and replacement cost are unresolved"
            : "registered block correct-tool requirement is not exposed, so equipment cost applicability is unresolved");
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
        String type = configuredType(configuredFeature);
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
        String blockItemId, Boolean requiresCorrectTool, String lootTableId,
        Map<String, AcquisitionMeasurement> measurements, Map<String, String> attributes,
        AcquisitionPath extractionPath, WorldgenCausalEvidence causalEvidence) {}

    private record WorldgenFeatureClosure(List<String> configuredFeatureIds,
        List<String> nestedPlacedFeatureIds) {}

    private record WorldgenFeatureEvidence(Map<String, AcquisitionMeasurement> measurements,
        Map<String, String> attributes) {}

    public record WorldgenBlockSource(String sourceId, String biomeId, String placedFeatureId,
        Map<String, AcquisitionMeasurement> measurements, Map<String, String> attributes,
        WorldgenCausalEvidence causalEvidence) {
        public WorldgenBlockSource(String sourceId, String biomeId, String placedFeatureId) {
            this(sourceId, biomeId, placedFeatureId, Map.of(), Map.of(), WorldgenCausalEvidence.empty());
        }

        public WorldgenBlockSource(String sourceId, String biomeId, String placedFeatureId,
            Map<String, AcquisitionMeasurement> measurements, Map<String, String> attributes) {
            this(sourceId, biomeId, placedFeatureId, measurements, attributes, WorldgenCausalEvidence.empty());
        }

        public WorldgenBlockSource {
            measurements = Map.copyOf(measurements);
            attributes = Map.copyOf(attributes);
            causalEvidence = causalEvidence == null ? WorldgenCausalEvidence.empty() : causalEvidence;
        }
    }
}
