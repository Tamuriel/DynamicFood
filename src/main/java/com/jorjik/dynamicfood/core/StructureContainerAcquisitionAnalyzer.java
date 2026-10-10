package com.jorjik.dynamicfood.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Discovers configured jigsaw-structure routes to loot-table containers.
 * Placement and template data are mechanical evidence only; no survival availability is inferred.
 */
public final class StructureContainerAcquisitionAnalyzer implements AcquisitionAnalyzer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String STRUCTURE_SET_DIR = "worldgen/structure_set";
    private static final String STRUCTURE_DIR = "worldgen/structure";
    private static final String POOL_DIR = "worldgen/template_pool";
    private static final String PROCESSOR_DIR = "worldgen/processor_list";
    private static final String TEMPLATE_DIR = "structure";
    private final Map<String, List<AcquisitionPath>> pathsByItem;
    private final Summary summary;

    private StructureContainerAcquisitionAnalyzer(Map<String, List<AcquisitionPath>> pathsByItem,
        Summary summary) {
        Map<String, List<AcquisitionPath>> immutable = new TreeMap<>();
        pathsByItem.forEach((itemId, paths) -> immutable.put(itemId,
            paths.stream().sorted(Comparator.comparing(AcquisitionPath::sourceId)).toList()));
        this.pathsByItem = Map.copyOf(immutable);
        this.summary = Objects.requireNonNull(summary, "structure analysis summary");
    }

    public static StructureContainerAcquisitionAnalyzer fromResourceManager(ResourceManager resourceManager,
        HolderLookup.Provider registries, LootTableAcquisitionAnalyzer lootAnalyzer) {
        Objects.requireNonNull(resourceManager, "resource manager");
        Objects.requireNonNull(registries, "registry lookup provider");
        Objects.requireNonNull(lootAnalyzer, "loot analyzer");

        Map<String, JsonObject> structures = readJsonResources(resourceManager, STRUCTURE_DIR);
        Map<String, JsonObject> structureSets = readJsonResources(resourceManager, STRUCTURE_SET_DIR);
        Map<String, JsonObject> pools = readJsonResources(resourceManager, POOL_DIR);
        Map<String, JsonObject> processorLists = readJsonResources(resourceManager, PROCESSOR_DIR);
        Map<String, Resource> resources = listTemplateResources(resourceManager);
        Map<String, Supplier<CompoundTag>> templates = new TreeMap<>();
        resources.forEach((id, resource) -> templates.put(id, () -> readTemplate(id, resource)));
        return fromResourceSuppliers(structures, structureSets, pools, processorLists, templates,
            registries, lootAnalyzer);
    }

    static StructureContainerAcquisitionAnalyzer fromResources(Map<String, JsonObject> structures,
        Map<String, JsonObject> structureSets, Map<String, JsonObject> pools,
        Map<String, CompoundTag> templates, HolderLookup.Provider registries,
        LootTableAcquisitionAnalyzer lootAnalyzer) {
        Objects.requireNonNull(structures, "structure definitions");
        Objects.requireNonNull(structureSets, "structure sets");
        Objects.requireNonNull(pools, "template pools");
        Objects.requireNonNull(templates, "structure templates");
        Objects.requireNonNull(registries, "registry lookup provider");
        Objects.requireNonNull(lootAnalyzer, "loot analyzer");

        Map<String, Supplier<CompoundTag>> suppliers = new TreeMap<>();
        templates.forEach((id, tag) -> suppliers.put(id, tag::copy));
        return fromResourceSuppliers(structures, structureSets, pools, Map.of(), suppliers,
            registries, lootAnalyzer);
    }

    private static StructureContainerAcquisitionAnalyzer fromResourceSuppliers(
        Map<String, JsonObject> structures, Map<String, JsonObject> structureSets,
        Map<String, JsonObject> pools, Map<String, JsonObject> processorLists,
        Map<String, Supplier<CompoundTag>> templates,
        HolderLookup.Provider registries, LootTableAcquisitionAnalyzer lootAnalyzer) {
        Index index = new Index(structures, structureSets, pools, processorLists, templates, registries);
        Map<String, List<AcquisitionPath>> pathsByItem = new TreeMap<>();
        for (Map.Entry<String, JsonObject> setEntry : index.structureSets.entrySet()) {
            JsonObject set = setEntry.getValue();
            JsonArray structuresInSet = set.has("structures") && set.get("structures").isJsonArray()
                ? set.getAsJsonArray("structures") : new JsonArray();
            List<JsonObject> configuredStructures = sortedObjects(structuresInSet);
            Map<String, Integer> occurrenceCounts = new HashMap<>();
            for (JsonObject configuredStructure : configuredStructures) {
                String canonicalEntry = canonicalJson(configuredStructure);
                int occurrence = occurrenceCounts.merge(canonicalEntry, 1, Integer::sum) - 1;
                String structureId = string(configuredStructure, "structure");
                if (structureId == null) {
                    continue;
                }
                String routeKey = setEntry.getKey() + "|" + structureId
                    + "|entry=" + digest(canonicalEntry) + ":" + occurrence;
                WorldgenCausalEvidence.Builder graph = new WorldgenCausalEvidence.Builder();
                WorldgenCausalEvidence.NodeRef setRef = index.addNode(graph,
                    WorldgenCausalEvidence.NodeType.STRUCTURE_SET, setEntry.getKey(),
                    "worldgen/structure_set/" + setEntry.getKey().replace(':', '/') + ".json",
                    canonicalJson(set));
                addPlacement(index, graph, setRef, setEntry.getKey(), set);

                WorldgenCausalEvidence.NodeRef structureRef = index.addNode(graph,
                    WorldgenCausalEvidence.NodeType.STRUCTURE, structureId,
                    "worldgen/structure/" + structureId.replace(':', '/') + ".json",
                    index.structures.containsKey(structureId)
                        ? canonicalJson(index.structures.get(structureId)) : "");
                graph.addRelationship(setRef, WorldgenCausalEvidence.RelationshipType.CONTAINS_STRUCTURE,
                    structureRef, WorldgenCausalEvidence.BranchKind.ALTERNATIVE,
                    "structure_weight=" + string(configuredStructure, "weight")
                        + ";entry=" + digest(canonicalEntry) + ":" + occurrence,
                    "worldgen/structure_set/" + setEntry.getKey().replace(':', '/') + ".json", "");
                if (!index.structures.containsKey(structureId)) {
                    index.unresolved(graph, structureRef, routeKey, "structure definition is not present in the active resource set",
                        "worldgen/structure/" + structureId.replace(':', '/') + ".json");
                    continue;
                }
                JsonObject structure = index.structures.get(structureId);
                if (!"minecraft:jigsaw".equals(string(structure, "type"))) {
                    index.unsupportedStructureTypes.add(string(structure, "type"));
                    index.unresolved(graph, structureRef, routeKey,
                        "only minecraft:jigsaw structure definitions are interpreted by this analyzer",
                        "worldgen/structure/" + structureId.replace(':', '/') + ".json");
                    continue;
                }
                index.jigsawStructures.add(structureId);
                addBiomeConstraints(index, graph, structureRef, structureId, structure);
                addPoolAliases(index, graph, structureRef, structureId, structure);
                String startPool = string(structure, "start_pool");
                if (startPool == null) {
                    index.unresolved(graph, structureRef, routeKey,
                        "jigsaw structure has no start_pool reference",
                        "worldgen/structure/" + structureId.replace(':', '/') + ".json");
                    continue;
                }
                WorldgenCausalEvidence.NodeRef start = new WorldgenCausalEvidence.NodeRef(
                    WorldgenCausalEvidence.NodeType.TEMPLATE_POOL, startPool);
                graph.addNode(start.type(), start.identifier(),
                    "worldgen/template_pool/" + startPool.replace(':', '/') + ".json",
                    index.pools.containsKey(startPool) ? canonicalJson(index.pools.get(startPool)) : "");
                graph.addRelationship(structureRef, WorldgenCausalEvidence.RelationshipType.USES_START_POOL,
                    start, WorldgenCausalEvidence.BranchKind.NONE, "", structureId, "");
                index.visitPool(graph, startPool, routeKey, new HashSet<>(), new HashSet<>());

                WorldgenCausalEvidence fullEvidence = graph.build();
                Set<String> referencedTables = fullEvidence.nodes().stream()
                    .filter(node -> node.ref().type() == WorldgenCausalEvidence.NodeType.LOOT_TABLE)
                    .map(node -> node.ref().identifier()).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                for (String tableId : referencedTables) {
                    index.candidateRoutes++;
                    WorldgenCausalEvidence routeEvidence = fullEvidence;
                    List<AcquisitionPath> lootPaths = lootAnalyzer.analyzeTable(tableId);
                    for (AcquisitionPath lootPath : lootPaths) {
                        Map<String, String> attributes = new TreeMap<>(lootPath.evidence().attributes());
                        attributes.put("structure_set_id", setEntry.getKey());
                        attributes.put("structure_id", structureId);
                        attributes.put("structure_loot_table_id", tableId);
                        attributes.put("structure_route_status", hasUnresolved(routeEvidence) ? "PARTIAL" : "CONFIGURED");
                        attributes.put("structure_route_configuration_only", "true");
                        WorldgenCausalEvidence outputEvidence =
                            addLootOutputEvidence(routeEvidence, tableId, lootPath);
                        AcquisitionEvidence evidence = new AcquisitionEvidence(
                            lootPath.evidence().measurements(), attributes, lootPath.evidence().inputs(),
                            lootPath.evidence().worldgenCausalEvidence().merge(outputEvidence));
                        String sourceId = "structure-set=" + setEntry.getKey()
                            + "|structure=" + structureId
                            + "|entry=" + digest(canonicalEntry) + ":" + occurrence
                            + "|loot-table=" + tableId;
                        AcquisitionPath path = new AcquisitionPath(lootPath.itemId(),
                            "worldgen_structure_container", sourceId, lootPath.confidence(),
                            lootPath.renewability(), lootPath.risk(), lootPath.repeatable(),
                            lootPath.hardFailed(), lootPath.feasibilityFactors(), lootPath.costsByHorizon(),
                            evidence, lootPath.economicCost(), lootPath.economicCostSchedule(), null);
                        pathsByItem.computeIfAbsent(path.itemId(), ignored -> new ArrayList<>()).add(path);
                        if (hasUnresolved(routeEvidence)) {
                            index.partialCandidates++;
                        } else {
                            index.completeCandidates++;
                        }
                    }
                }
            }
        }
        return new StructureContainerAcquisitionAnalyzer(pathsByItem, index.summary(pathsByItem));
    }

    private static WorldgenCausalEvidence addLootOutputEvidence(
        WorldgenCausalEvidence routeEvidence, String tableId, AcquisitionPath lootPath) {
        WorldgenCausalEvidence.NodeRef lootTable = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.LOOT_TABLE, tableId);
        WorldgenCausalEvidence.Node tableNode = routeEvidence.nodes().stream()
            .filter(node -> node.ref().equals(lootTable))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "structure route does not retain its referenced loot table: " + tableId));
        boolean provenPossible = "PROVEN_POSSIBLE".equals(
            lootPath.evidence().attributes().get("loot_output_evidence_status"));
        String reason = lootPath.evidence().attributes().getOrDefault("loot_output_evidence_reason",
            "loot analyzer did not expose output-possibility evidence for this indexed candidate");
        WorldgenCausalEvidence.NodeRef output = new WorldgenCausalEvidence.NodeRef(
            provenPossible ? WorldgenCausalEvidence.NodeType.ITEM_OUTPUT
                : WorldgenCausalEvidence.NodeType.ITEM_OUTPUT_CANDIDATE,
            lootPath.itemId());
        WorldgenCausalEvidence.Builder addition = new WorldgenCausalEvidence.Builder()
            .addNode(tableNode.ref().type(), tableNode.ref().identifier(),
                tableNode.sourceResource(), tableNode.rawEvidence())
            .addNode(output.type(), output.identifier(), tableNode.sourceResource(),
                provenPossible ? lootPath.sourceType() + ":" + lootPath.sourceId() : reason);
        addition.addRelationship(lootTable,
            provenPossible ? WorldgenCausalEvidence.RelationshipType.YIELDS_ITEM
                : WorldgenCausalEvidence.RelationshipType.CANDIDATE_OUTPUT,
            output,
            provenPossible ? WorldgenCausalEvidence.BranchKind.NONE
                : WorldgenCausalEvidence.BranchKind.UNKNOWN,
            provenPossible ? lootPath.sourceId() : "",
            tableNode.sourceResource(),
            provenPossible ? "" : reason);
        return routeEvidence.merge(addition.build());
    }

    @Override
    public boolean supports(String itemId) {
        return pathsByItem.containsKey(itemId);
    }

    @Override
    public List<AcquisitionPath> analyze(String itemId) {
        return pathsByItem.getOrDefault(itemId, List.of());
    }

    @Override
    public Set<String> indexedItemIds() {
        return pathsByItem.keySet();
    }

    public Summary summary() {
        return summary;
    }

    private static void addPlacement(Index index, WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef setRef, String setId, JsonObject set) {
        String resource = "worldgen/structure_set/" + setId.replace(':', '/') + ".json";
        JsonElement placement = set.get("placement");
        if (placement == null || !placement.isJsonObject()) {
            index.unresolved(graph, setRef, setId + "#placement",
                "structure set placement configuration is missing or malformed", resource);
            return;
        }
        String id = setId + "#placement";
        WorldgenCausalEvidence.NodeRef placementRef = index.addNode(graph,
            WorldgenCausalEvidence.NodeType.STRUCTURE_PLACEMENT, id, resource, canonicalJson(placement));
        graph.addRelationship(setRef, WorldgenCausalEvidence.RelationshipType.USES_STRUCTURE_PLACEMENT,
            placementRef, WorldgenCausalEvidence.BranchKind.NONE, "", resource, "");
    }

    private static void addBiomeConstraints(Index index, WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef structureRef, String structureId, JsonObject structure) {
        JsonElement biomes = structure.get("biomes");
        if (biomes == null) {
            index.unresolved(graph, structureRef, structureId + "#biomes",
                "jigsaw structure biome constraint is missing", "worldgen/structure/" + structureId + ".json");
            return;
        }
        String resource = "worldgen/structure/" + structureId.replace(':', '/') + ".json";
        WorldgenCausalEvidence.NodeRef constraint = index.addNode(graph,
            WorldgenCausalEvidence.NodeType.BIOME_CONSTRAINT, structureId + "#biomes", resource,
            canonicalJson(biomes));
        graph.addRelationship(structureRef, WorldgenCausalEvidence.RelationshipType.DECLARES_BIOME_CONSTRAINT,
            constraint, WorldgenCausalEvidence.BranchKind.NONE, "", resource, "");
        List<JsonElement> references = biomes.isJsonArray()
            ? biomes.getAsJsonArray().asList() : List.of(biomes);
        for (JsonElement reference : references) {
            if (!reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString()) {
                index.unresolved(graph, constraint, structureId + "#biome-reference:" + canonicalJson(reference),
                    "biome constraint is not a direct biome or biome tag reference", resource);
                continue;
            }
            String value = reference.getAsString();
            if (value.startsWith("#")) {
                index.resolveBiomeTag(graph, constraint, value.substring(1), structureId, resource);
            } else {
                index.addBiomeReference(graph, constraint, value, structureId, resource);
            }
        }
    }

    private static void addPoolAliases(Index index, WorldgenCausalEvidence.Builder graph,
        WorldgenCausalEvidence.NodeRef structureRef, String structureId, JsonObject structure) {
        JsonElement aliases = structure.get("pool_aliases");
        if (aliases == null || !aliases.isJsonArray()) {
            return;
        }
        List<JsonObject> entries = sortedObjects(aliases.getAsJsonArray());
        Map<String, Integer> occurrences = new HashMap<>();
        for (JsonObject alias : entries) {
            String raw = canonicalJson(alias);
            int occurrence = occurrences.merge(raw, 1, Integer::sum) - 1;
            String identifier = structureId + "#pool-alias:" + digest(raw) + ":" + occurrence;
            WorldgenCausalEvidence.NodeRef aliasRef = index.addNode(graph,
                WorldgenCausalEvidence.NodeType.POOL_ALIAS, identifier,
                "worldgen/structure/" + structureId.replace(':', '/') + ".json", raw);
            graph.addRelationship(structureRef, WorldgenCausalEvidence.RelationshipType.DECLARES_POOL_ALIAS,
                aliasRef, WorldgenCausalEvidence.BranchKind.UNKNOWN, "alias_entry=" + raw,
                "worldgen/structure/" + structureId.replace(':', '/') + ".json", "");
            index.unresolved(graph, aliasRef, identifier + "#resolution",
                "jigsaw pool alias forms are preserved but not applied to pool references",
                "worldgen/structure/" + structureId.replace(':', '/') + ".json");
            index.aliasEntries++;
        }
    }

    private static boolean hasUnresolved(WorldgenCausalEvidence evidence) {
        return evidence.nodes().stream()
            .anyMatch(node -> node.ref().type() == WorldgenCausalEvidence.NodeType.UNRESOLVED_MECHANIC);
    }

    private static List<JsonObject> sortedObjects(JsonArray array) {
        return array.asList().stream().filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject)
            .sorted(Comparator.comparing(StructureContainerAcquisitionAnalyzer::canonicalJson)).toList();
    }

    private static Map<String, JsonObject> readJsonResources(ResourceManager resourceManager, String directory) {
        Map<String, JsonObject> result = new TreeMap<>();
        resourceManager.listResources(directory, id -> id.getPath().endsWith(".json")).entrySet().stream()
            .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String id = resourceId(entry.getKey(), directory, ".json");
                if (id == null) {
                    return;
                }
                try (InputStreamReader reader = new InputStreamReader(entry.getValue().open(),
                    StandardCharsets.UTF_8)) {
                    result.put(id, JsonParser.parseReader(reader).getAsJsonObject());
                } catch (IOException | RuntimeException exception) {
                    com.jorjik.dynamicfood.DynamicFood.LOGGER.warn(
                        "Unable to read structure acquisition resource {}", entry.getKey(), exception);
                }
            });
        return result;
    }

    private static Map<String, Resource> listTemplateResources(ResourceManager resourceManager) {
        Map<String, Resource> result = new TreeMap<>();
        resourceManager.listResources(TEMPLATE_DIR, id -> id.getPath().endsWith(".nbt")).entrySet().stream()
            .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String id = resourceId(entry.getKey(), TEMPLATE_DIR, ".nbt");
                if (id != null) {
                    result.put(id, entry.getValue());
                }
            });
        return result;
    }

    private static CompoundTag readTemplate(String id, Resource resource) {
        try (InputStream input = resource.open()) {
            return NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException exception) {
            com.jorjik.dynamicfood.DynamicFood.LOGGER.warn(
                "Unable to read structure template acquisition resource {}", id, exception);
            throw new IllegalStateException("structure template resource could not be read: " + id, exception);
        }
    }

    private static String resourceId(ResourceLocation resource, String directory, String suffix) {
        String prefix = directory + "/";
        String path = resource.getPath();
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        String relative = path.substring(prefix.length(), path.length() - suffix.length());
        ResourceLocation parsed = ResourceLocation.tryBuild(resource.getNamespace(), relative);
        return parsed == null ? null : parsed.toString();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : null;
    }

    private static String canonicalJson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            new TreeMap<>(element.getAsJsonObject().asMap())
                .forEach((key, value) -> sorted.add(key, canonicalElement(value)));
            return GSON.toJson(sorted);
        }
        return GSON.toJson(canonicalElement(element));
    }

    private static JsonElement canonicalElement(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            new TreeMap<>(element.getAsJsonObject().asMap())
                .forEach((key, value) -> sorted.add(key, canonicalElement(value)));
            return sorted;
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            element.getAsJsonArray().forEach(value -> array.add(canonicalElement(value)));
            return array;
        }
        return element.deepCopy();
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(Character.forDigit((current >>> 4) & 0xf, 16));
                result.append(Character.forDigit(current & 0xf, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for deterministic structure identities", exception);
        }
    }

    public record Summary(
        int structureSets,
        int structures,
        int jigsawStructures,
        int templatePools,
        int templateResources,
        int referencedTemplates,
        int loadedTemplates,
        int templateBlockEntries,
        int blockEntityTags,
        int lootTableTags,
        int invalidContainerReferences,
        int containerPlacements,
        int staticContainerInventories,
        int containersWithLootTables,
        int lootTableIds,
        int candidateRoutes,
        int candidatePaths,
        int completeCandidates,
        int partialCandidates,
        int unresolvedReferences,
        int cycleReferences,
        int aliasEntries,
        int unsupportedPoolElements,
        int unresolvedBiomeTags,
        List<String> unsupportedStructureTypes
    ) {
        public Summary {
            unsupportedStructureTypes = unsupportedStructureTypes.stream().sorted().distinct().toList();
        }
    }

    private record TemplateContainer(String identifier, int paletteIndex, int x, int y, int z,
        String lootTableId, String rawEvidence) {}

    private record TemplateJigsaw(String identifier, int paletteIndex, String poolId, String rawEvidence) {}

    private record TemplateScan(List<TemplateContainer> containers, List<TemplateJigsaw> jigsaws,
        List<String> unresolved) {}

    private static final class Index {
        private final Map<String, JsonObject> structures = new TreeMap<>();
        private final Map<String, JsonObject> structureSets = new TreeMap<>();
        private final Map<String, JsonObject> pools = new TreeMap<>();
        private final Map<String, JsonObject> processorLists = new TreeMap<>();
        private final Map<String, Supplier<CompoundTag>> templateResources = new TreeMap<>();
        private final Map<String, TemplateScan> templateScans = new HashMap<>();
        private final HolderLookup.Provider registries;
        private final Set<String> referencedTemplates = new TreeSet<>();
        private final Set<String> loadedTemplates = new TreeSet<>();
        private final Set<String> containers = new TreeSet<>();
        private final Set<String> lootTables = new TreeSet<>();
        private final Set<String> lootTableContainers = new TreeSet<>();
        private final Set<String> staticContainerInventories = new TreeSet<>();
        private final Set<String> unresolvedKeys = new TreeSet<>();
        private final Set<String> jigsawStructures = new TreeSet<>();
        private final Set<String> unsupportedStructureTypes = new TreeSet<>();
        private int candidateRoutes;
        private int candidatePaths;
        private int completeCandidates;
        private int partialCandidates;
        private int cycleReferences;
        private int aliasEntries;
        private int unsupportedPoolElements;
        private int unresolvedBiomeTags;
        private int templateBlockEntries;
        private int blockEntityTags;
        private int lootTableTags;
        private int invalidContainerReferences;

        private Index(Map<String, JsonObject> structures, Map<String, JsonObject> structureSets,
            Map<String, JsonObject> pools, Map<String, JsonObject> processorLists,
            Map<String, Supplier<CompoundTag>> templates,
            HolderLookup.Provider registries) {
            this.structures.putAll(structures);
            this.structureSets.putAll(structureSets);
            this.pools.putAll(pools);
            this.processorLists.putAll(processorLists);
            this.templateResources.putAll(templates);
            this.registries = registries;
        }

        private WorldgenCausalEvidence.NodeRef addNode(WorldgenCausalEvidence.Builder graph,
            WorldgenCausalEvidence.NodeType type, String id, String resource, String raw) {
            WorldgenCausalEvidence.NodeRef ref = new WorldgenCausalEvidence.NodeRef(type, id);
            graph.addNode(type, id, resource, raw);
            return ref;
        }

        private void unresolved(WorldgenCausalEvidence.Builder graph, WorldgenCausalEvidence.NodeRef from,
            String id, String reason, String resource) {
            WorldgenCausalEvidence.NodeRef ref = addNode(graph,
                WorldgenCausalEvidence.NodeType.UNRESOLVED_MECHANIC, id, resource, reason);
            graph.addRelationship(from, WorldgenCausalEvidence.RelationshipType.UNRESOLVED, ref,
                WorldgenCausalEvidence.BranchKind.UNKNOWN, "", resource, reason);
            unresolvedKeys.add(resource + "|" + id + "|" + reason);
        }

        private void addBiomeReference(WorldgenCausalEvidence.Builder graph,
            WorldgenCausalEvidence.NodeRef constraint, String id, String structureId, String resource) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null) {
                unresolved(graph, constraint, structureId + "#invalid-biome:" + id,
                    "biome constraint contains an invalid resource identifier", resource);
                return;
            }
            ResourceKey<net.minecraft.world.level.biome.Biome> key =
                ResourceKey.create(Registries.BIOME, location);
            HolderLookup.RegistryLookup<net.minecraft.world.level.biome.Biome> biomeLookup =
                registries.lookupOrThrow(Registries.BIOME);
            WorldgenCausalEvidence.NodeRef biome = addNode(graph,
                WorldgenCausalEvidence.NodeType.BIOME, location.toString(), resource,
                biomeLookup.get(key).isPresent() ? "configured biome constraint" : "unresolved configured biome");
            graph.addRelationship(constraint, WorldgenCausalEvidence.RelationshipType.HAS_BIOME_MEMBER,
                biome, WorldgenCausalEvidence.BranchKind.ALTERNATIVE, "configured_biome=" + location,
                resource, "");
            if (biomeLookup.get(key).isEmpty()) {
                unresolved(graph, constraint, structureId + "#missing-biome:" + location,
                    "configured biome is not present in the active biome registry", resource);
            }
        }

        private void resolveBiomeTag(WorldgenCausalEvidence.Builder graph,
            WorldgenCausalEvidence.NodeRef constraint, String id, String structureId, String resource) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null) {
                unresolvedBiomeTags++;
                unresolved(graph, constraint, structureId + "#invalid-biome-tag:" + id,
                    "biome constraint contains an invalid biome tag identifier", resource);
                return;
            }
            TagKey<net.minecraft.world.level.biome.Biome> key = TagKey.create(Registries.BIOME, location);
            HolderLookup.RegistryLookup<net.minecraft.world.level.biome.Biome> biomeLookup =
                registries.lookupOrThrow(Registries.BIOME);
            WorldgenCausalEvidence.NodeRef tag = addNode(graph,
                WorldgenCausalEvidence.NodeType.BIOME_TAG, location.toString(), resource,
                "resolved as configuration membership only");
            graph.addRelationship(constraint, WorldgenCausalEvidence.RelationshipType.HAS_BIOME_MEMBER,
                tag, WorldgenCausalEvidence.BranchKind.ALTERNATIVE, "configured_biome_tag=" + location,
                resource, "");
            var holders = biomeLookup.get(key);
            if (holders.isEmpty()) {
                unresolvedBiomeTags++;
                unresolved(graph, tag, structureId + "#unresolved-biome-tag:" + location,
                    "biome tag is absent from the active biome registry", resource);
                return;
            }
            List<Holder<net.minecraft.world.level.biome.Biome>> members =
                holders.get().stream().sorted(Comparator.comparing(holder ->
                    holder.unwrapKey().map(resourceKey -> resourceKey.location().toString()).orElse(""))).toList();
            for (Holder<net.minecraft.world.level.biome.Biome> holder : members) {
                String biomeId = holder.unwrapKey().map(resourceKey -> resourceKey.location().toString())
                    .orElse("unkeyed:" + location);
                WorldgenCausalEvidence.NodeRef biome = addNode(graph,
                    WorldgenCausalEvidence.NodeType.BIOME, biomeId, resource,
                    "active biome-tag configuration member; not dimension or survival evidence");
                graph.addRelationship(tag, WorldgenCausalEvidence.RelationshipType.HAS_BIOME_MEMBER,
                    biome, WorldgenCausalEvidence.BranchKind.ALTERNATIVE, "tag_member=" + biomeId,
                    resource, "");
            }
        }

        private void visitPool(WorldgenCausalEvidence.Builder graph, String poolId, String routeKey,
            Set<String> visiting, Set<String> expanded) {
            String resource = "worldgen/template_pool/" + poolId.replace(':', '/') + ".json";
            WorldgenCausalEvidence.NodeRef poolRef = new WorldgenCausalEvidence.NodeRef(
                WorldgenCausalEvidence.NodeType.TEMPLATE_POOL, poolId);
            JsonObject pool = pools.get(poolId);
            if (pool == null) {
                unresolved(graph, poolRef, routeKey + "#missing-pool:" + poolId,
                    "referenced template pool is absent from the active resource set", resource);
                return;
            }
            if (!visiting.add(poolId)) {
                cycleReferences++;
                unresolved(graph, poolRef, routeKey + "#pool-cycle:" + poolId,
                    "recursive template-pool reference was stopped to preserve a finite causal graph", resource);
                return;
            }
            if (!expanded.add(poolId)) {
                visiting.remove(poolId);
                return;
            }
            JsonElement elementsValue = pool.get("elements");
            if (elementsValue == null || !elementsValue.isJsonArray()) {
                unresolved(graph, poolRef, routeKey + "#malformed-elements:" + poolId,
                    "template pool has no elements array", resource);
            } else {
                List<JsonObject> elements = sortedObjects(elementsValue.getAsJsonArray());
                Map<String, Integer> occurrences = new HashMap<>();
                for (JsonObject weighted : elements) {
                    String raw = canonicalJson(weighted);
                    int occurrence = occurrences.merge(raw, 1, Integer::sum) - 1;
                    String identity = poolId + "#element:" + digest(raw) + ":" + occurrence;
                    WorldgenCausalEvidence.NodeRef elementRef = addNode(graph,
                        WorldgenCausalEvidence.NodeType.POOL_ELEMENT, identity, resource, raw);
                    graph.addRelationship(poolRef, WorldgenCausalEvidence.RelationshipType.CONTAINS_POOL_ELEMENT,
                        elementRef, WorldgenCausalEvidence.BranchKind.ALTERNATIVE,
                        "weight=" + Objects.toString(string(weighted, "weight"), "UNKNOWN"),
                        resource, "");
                    JsonElement elementValue = weighted.get("element");
                    if (elementValue == null || !elementValue.isJsonObject()) {
                        unsupportedPoolElements++;
                        unresolved(graph, elementRef, identity + "#missing-element",
                            "pool element payload is missing or is not an object", resource);
                    } else {
                        visitElement(graph, elementRef, elementValue.getAsJsonObject(), identity,
                            routeKey, resource, visiting, expanded);
                    }
                }
            }
            String fallback = string(pool, "fallback");
            if (fallback != null) {
                WorldgenCausalEvidence.NodeRef fallbackRef = new WorldgenCausalEvidence.NodeRef(
                    WorldgenCausalEvidence.NodeType.TEMPLATE_POOL, fallback);
                graph.addNode(fallbackRef.type(), fallbackRef.identifier(),
                    "worldgen/template_pool/" + fallback.replace(':', '/') + ".json",
                    pools.containsKey(fallback) ? canonicalJson(pools.get(fallback)) : "");
                graph.addRelationship(poolRef, WorldgenCausalEvidence.RelationshipType.USES_FALLBACK_POOL,
                    fallbackRef, WorldgenCausalEvidence.BranchKind.DEFAULT, "fallback=" + fallback,
                    resource, "");
                visitPool(graph, fallback, routeKey, visiting, expanded);
            }
            visiting.remove(poolId);
        }

        private void visitElement(WorldgenCausalEvidence.Builder graph,
            WorldgenCausalEvidence.NodeRef elementRef, JsonObject element, String identity,
            String routeKey, String resource, Set<String> visiting, Set<String> expanded) {
            String type = string(element, "element_type");
            if ("minecraft:empty_pool_element".equals(type)) {
                return;
            }
            if (isTemplateElement(type)) {
                String templateId = string(element, "location");
                if (templateId == null) {
                    unresolved(graph, elementRef, identity + "#missing-template-location",
                        "supported template pool element has no location", resource);
                    return;
                }
                ResourceLocation parsed = ResourceLocation.tryParse(templateId);
                if (parsed == null) {
                    unresolved(graph, elementRef, identity + "#invalid-template:" + templateId,
                        "template pool element location is not a valid resource identifier", resource);
                    return;
                }
                String normalized = parsed.toString();
                referencedTemplates.add(normalized);
                WorldgenCausalEvidence.NodeRef templateRef = new WorldgenCausalEvidence.NodeRef(
                    WorldgenCausalEvidence.NodeType.STRUCTURE_TEMPLATE, normalized);
                graph.addNode(templateRef.type(), templateRef.identifier(),
                    "structure/" + normalized.replace(':', '/') + ".nbt",
                    templateResources.containsKey(normalized) ? "template resource is present" : "");
                graph.addRelationship(elementRef, WorldgenCausalEvidence.RelationshipType.REFERENCES_TEMPLATE,
                    templateRef, WorldgenCausalEvidence.BranchKind.NONE,
                    "element_type=" + type, resource, "");
                if (!processorsAreKnownEmpty(element.get("processors"))) {
                    unresolved(graph, elementRef, identity + "#processors",
                        "template processor list is not proven empty; it may change placed containers or jigsaw connections",
                        resource);
                }
                TemplateScan scan = templateScan(normalized);
                addTemplateEvidence(graph, templateRef, scan, normalized, routeKey, visiting, expanded);
                return;
            }
            if ("minecraft:list_pool_element".equals(type)
                || "minecraft:alternatives_pool_element".equals(type)) {
                JsonElement childValues = element.get("elements");
                if (childValues == null || !childValues.isJsonArray()) {
                    unsupportedPoolElements++;
                    unresolved(graph, elementRef, identity + "#malformed-nested-elements",
                        "nested pool element has no elements array", resource);
                    return;
                }
                JsonArray children = childValues.getAsJsonArray();
                WorldgenCausalEvidence.BranchKind kind = "minecraft:alternatives_pool_element".equals(type)
                    ? WorldgenCausalEvidence.BranchKind.ALTERNATIVE : WorldgenCausalEvidence.BranchKind.NONE;
                List<JsonObject> sortedChildren = sortedObjects(children);
                Map<String, Integer> occurrences = new HashMap<>();
                for (JsonObject child : sortedChildren) {
                    String raw = canonicalJson(child);
                    int occurrence = occurrences.merge(raw, 1, Integer::sum) - 1;
                    String childIdentity = identity + "#nested:" + digest(raw) + ":" + occurrence;
                    WorldgenCausalEvidence.NodeRef childRef = addNode(graph,
                        WorldgenCausalEvidence.NodeType.POOL_ELEMENT, childIdentity, resource, raw);
                    graph.addRelationship(elementRef,
                        WorldgenCausalEvidence.RelationshipType.CONTAINS_POOL_ELEMENT, childRef,
                        kind, "nested_element=" + digest(raw) + ":" + occurrence, resource, "");
                    visitElement(graph, childRef, child, childIdentity, routeKey, resource, visiting, expanded);
                }
                return;
            }
            unsupportedPoolElements++;
            unresolved(graph, elementRef, identity + "#unsupported-type:" + Objects.toString(type, "MISSING"),
                "template pool element type is preserved but not interpreted", resource);
        }

        private boolean processorsAreKnownEmpty(JsonElement reference) {
            if (reference == null) {
                return true;
            }
            if (reference.isJsonArray()) {
                return reference.getAsJsonArray().isEmpty();
            }
            if (!reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString()) {
                return false;
            }
            ResourceLocation id = ResourceLocation.tryParse(reference.getAsString());
            if (id == null) {
                return false;
            }
            JsonObject processorList = processorLists.get(id.toString());
            return processorList != null && processorList.has("processors")
                && processorList.get("processors").isJsonArray()
                && processorList.getAsJsonArray("processors").isEmpty();
        }

        private TemplateScan templateScan(String templateId) {
            TemplateScan cached = templateScans.get(templateId);
            if (cached != null) {
                return cached;
            }
            Supplier<CompoundTag> templateResource = templateResources.get(templateId);
            if (templateResource == null) {
                String reason = "referenced structure template is absent from the active resource set";
                TemplateScan missing = new TemplateScan(List.of(), List.of(), List.of(reason));
                templateScans.put(templateId, missing);
                return missing;
            }
            CompoundTag root;
            try {
                root = templateResource.get();
                new StructureTemplate().load(BuiltInRegistries.BLOCK.asLookup(), root.copy());
            } catch (RuntimeException exception) {
                String reason = "structure template could not be decoded by the 1.21.1 StructureTemplate API: "
                    + exception.getClass().getSimpleName();
                TemplateScan invalid = new TemplateScan(List.of(), List.of(), List.of(reason));
                templateScans.put(templateId, invalid);
                return invalid;
            }
            TemplateScan scan = scanTemplate(templateId, root);
            templateScans.put(templateId, scan);
            loadedTemplates.add(templateId);
            return scan;
        }

        private TemplateScan scanTemplate(String templateId, CompoundTag root) {
            List<TemplateContainer> containers = new ArrayList<>();
            List<TemplateJigsaw> jigsaws = new ArrayList<>();
            List<String> unresolved = new ArrayList<>();
            ListTag paletteList = root.getList(StructureTemplate.PALETTE_LIST_TAG, Tag.TAG_LIST);
            List<ListTag> palettes = new ArrayList<>();
            if (!paletteList.isEmpty()) {
                for (int i = 0; i < paletteList.size(); i++) {
                    if (paletteList.get(i) instanceof ListTag palette) {
                        palettes.add(palette);
                    } else {
                        unresolved.add("template has a non-list entry in its palette list");
                    }
                }
            } else {
                ListTag palette = root.getList(StructureTemplate.PALETTE_TAG, Tag.TAG_COMPOUND);
                if (!palette.isEmpty()) {
                    palettes.add(palette);
                } else {
                    unresolved.add("template has no supported palette data");
                }
            }
            ListTag blocks = root.getList(StructureTemplate.BLOCKS_TAG, Tag.TAG_COMPOUND);
            templateBlockEntries += blocks.size();
            for (int paletteIndex = 0; paletteIndex < palettes.size(); paletteIndex++) {
                ListTag palette = palettes.get(paletteIndex);
                for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                    CompoundTag block = blocks.getCompound(blockIndex);
                    int stateIndex = block.getInt(StructureTemplate.BLOCK_TAG_STATE);
                    if (stateIndex < 0 || stateIndex >= palette.size()
                        || !(palette.get(stateIndex) instanceof CompoundTag stateTag)) {
                        unresolved.add("template block refers to an absent palette state");
                        continue;
                    }
                    var state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), stateTag);
                    CompoundTag blockEntityTag = block.getCompound(StructureTemplate.BLOCK_TAG_NBT);
                    if (!blockEntityTag.isEmpty()) {
                        blockEntityTags++;
                    }
                    int[] pos = templateBlockPosition(block.get(StructureTemplate.BLOCK_TAG_POS));
                    if (pos.length != 3) {
                        if (!blockEntityTag.isEmpty()) {
                            unresolved.add("template block entity has an invalid position");
                        }
                        continue;
                    }
                    String location = templateId + "#palette=" + paletteIndex + "#pos="
                        + pos[0] + "," + pos[1] + "," + pos[2];
                    String raw = blockEntityTag.isEmpty() ? "" : blockEntityTag.toString();
                    if (state.is(Blocks.JIGSAW)) {
                        String poolId = blockEntityTag.getString("pool");
                        if (poolId.isBlank()) {
                            unresolved.add("jigsaw block has no pool reference");
                        } else {
                            jigsaws.add(new TemplateJigsaw(location, paletteIndex, poolId, raw));
                        }
                    }
                    boolean hasLootTable = blockEntityTag.contains("LootTable", Tag.TAG_STRING);
                    String tableId = hasLootTable ? blockEntityTag.getString("LootTable") : null;
                    if (hasLootTable) {
                        lootTableTags++;
                    }
                    if (blockEntityTag.isEmpty()) {
                        continue;
                    }
                    ResourceLocation blockEntityId = ResourceLocation.tryParse(blockEntityTag.getString("id"));
                    BlockEntityType<?> type = blockEntityId == null ? null
                        : BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(blockEntityId).orElse(null);
                    if (type == null || !type.isValid(state)) {
                        if (hasLootTable) {
                            invalidContainerReferences++;
                            unresolved.add("loot-table block entity has an invalid block-entity type or state");
                        }
                        continue;
                    }
                    BlockPos blockPos = new BlockPos(pos[0], pos[1], pos[2]);
                    BlockEntity blockEntity;
                    try {
                        blockEntity = type.create(blockPos, state);
                    } catch (RuntimeException exception) {
                        if (hasLootTable) {
                            invalidContainerReferences++;
                            unresolved.add("loot-table block entity could not be created for its registered state");
                        }
                        continue;
                    }
                    if (!(blockEntity instanceof Container)) {
                        if (hasLootTable) {
                            invalidContainerReferences++;
                            unresolved.add("block entity contains a LootTable tag but is not a Container");
                        }
                        continue;
                    }
                    String containerId = location + "#block_entity=" + blockEntityId;
                    this.containers.add(containerId);
                    if (blockEntityTag.get("Items") instanceof ListTag items && !items.isEmpty()) {
                        staticContainerInventories.add(containerId);
                    }
                    if (!hasLootTable) {
                        containers.add(new TemplateContainer(containerId, paletteIndex,
                            pos[0], pos[1], pos[2], null, raw));
                        continue;
                    }
                    ResourceLocation parsedTable = ResourceLocation.tryParse(tableId);
                    if (parsedTable == null) {
                        invalidContainerReferences++;
                        unresolved.add("container has a LootTable field with an invalid resource identifier");
                        continue;
                    }
                    String normalizedTable = parsedTable.toString();
                    containers.add(new TemplateContainer(containerId, paletteIndex,
                        pos[0], pos[1], pos[2], normalizedTable, raw));
                    lootTableContainers.add(containerId);
                    lootTables.add(normalizedTable);
                }
            }
            containers.sort(Comparator.comparing(TemplateContainer::identifier)
                .thenComparing(container -> Objects.toString(container.lootTableId(), "")));
            jigsaws.sort(Comparator.comparing(TemplateJigsaw::identifier)
                .thenComparing(TemplateJigsaw::poolId));
            unresolved.sort(Comparator.naturalOrder());
            return new TemplateScan(List.copyOf(containers), List.copyOf(jigsaws), List.copyOf(unresolved));
        }

        private static int[] templateBlockPosition(Tag position) {
            if (position instanceof IntArrayTag intArray) {
                return intArray.getAsIntArray();
            }
            if (position instanceof ListTag list && list.size() == 3
                && list.getElementType() == Tag.TAG_INT) {
                return new int[] {list.getInt(0), list.getInt(1), list.getInt(2)};
            }
            return new int[0];
        }

        private void addTemplateEvidence(WorldgenCausalEvidence.Builder graph,
            WorldgenCausalEvidence.NodeRef templateRef, TemplateScan scan, String templateId,
            String routeKey, Set<String> visiting, Set<String> expanded) {
            String resource = "structure/" + templateId.replace(':', '/') + ".nbt";
            for (String reason : scan.unresolved()) {
                unresolved(graph, templateRef, routeKey + "#template-reference:" + templateId + ":" + digest(reason),
                    reason, resource);
            }
            for (TemplateContainer container : scan.containers()) {
                WorldgenCausalEvidence.NodeRef containerRef = addNode(graph,
                    WorldgenCausalEvidence.NodeType.CONTAINER, container.identifier(), resource,
                    container.rawEvidence());
                graph.addRelationship(templateRef, WorldgenCausalEvidence.RelationshipType.CONTAINS_CONTAINER,
                    containerRef, scanPaletteBranch(container.paletteIndex()),
                    "palette=" + container.paletteIndex() + ";position="
                        + container.x() + "," + container.y() + "," + container.z(),
                    resource, "");
                if (container.lootTableId() != null) {
                    WorldgenCausalEvidence.NodeRef lootRef = addNode(graph,
                        WorldgenCausalEvidence.NodeType.LOOT_TABLE, container.lootTableId(),
                        "loot_table/" + container.lootTableId().replace(':', '/') + ".json",
                        "exact LootTable block-entity reference");
                    graph.addRelationship(containerRef, WorldgenCausalEvidence.RelationshipType.USES_LOOT_TABLE,
                        lootRef, WorldgenCausalEvidence.BranchKind.NONE, "", resource, "");
                }
            }
            for (TemplateJigsaw jigsaw : scan.jigsaws()) {
                WorldgenCausalEvidence.NodeRef poolRef = new WorldgenCausalEvidence.NodeRef(
                    WorldgenCausalEvidence.NodeType.TEMPLATE_POOL, jigsaw.poolId());
                graph.addNode(poolRef.type(), poolRef.identifier(),
                    "worldgen/template_pool/" + jigsaw.poolId().replace(':', '/') + ".json",
                    pools.containsKey(jigsaw.poolId()) ? canonicalJson(pools.get(jigsaw.poolId())) : "");
                WorldgenCausalEvidence.NodeRef jigsawRef = addNode(graph,
                    WorldgenCausalEvidence.NodeType.POOL_ELEMENT, jigsaw.identifier(), resource,
                    jigsaw.rawEvidence());
                graph.addRelationship(templateRef, WorldgenCausalEvidence.RelationshipType.REFERENCES_POOL,
                    jigsawRef, scanPaletteBranch(jigsaw.paletteIndex()),
                    "palette=" + jigsaw.paletteIndex(), resource, "");
                graph.addRelationship(jigsawRef, WorldgenCausalEvidence.RelationshipType.REFERENCES_POOL,
                    poolRef, WorldgenCausalEvidence.BranchKind.NONE,
                    "jigsaw_pool=" + jigsaw.poolId(), resource, "");
                visitPool(graph, jigsaw.poolId(), routeKey, visiting, expanded);
            }
        }

        private static WorldgenCausalEvidence.BranchKind scanPaletteBranch(int paletteIndex) {
            return paletteIndex == 0 ? WorldgenCausalEvidence.BranchKind.NONE
                : WorldgenCausalEvidence.BranchKind.ALTERNATIVE;
        }

        private static boolean isTemplateElement(String type) {
            return "minecraft:single_pool_element".equals(type)
                || "minecraft:legacy_single_pool_element".equals(type)
                || "minecraft:processed_single_pool_element".equals(type);
        }

        private Summary summary(Map<String, List<AcquisitionPath>> pathsByItem) {
            int paths = pathsByItem.values().stream().mapToInt(List::size).sum();
            candidatePaths = paths;
            return new Summary(structureSets.size(), structures.size(), jigsawStructures.size(),
                pools.size(), templateResources.size(), referencedTemplates.size(), loadedTemplates.size(),
                templateBlockEntries, blockEntityTags, lootTableTags, invalidContainerReferences,
                containers.size(), staticContainerInventories.size(), lootTableContainers.size(),
                lootTables.size(), candidateRoutes, candidatePaths, completeCandidates, partialCandidates,
                unresolvedKeys.size(), cycleReferences, aliasEntries, unsupportedPoolElements,
                unresolvedBiomeTags, List.copyOf(unsupportedStructureTypes));
        }

    }
}
