package com.jorjik.dynamicfood.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jorjik.dynamicfood.DynamicFood;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Emits diagnostics only when explicitly executed by a GameTest server. */
@GameTestHolder(DynamicFood.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EconomicSnapshotDiagnosticsGameTest {
    private static final List<String> FACTORS = List.of(
        "quantity", "probability_burden", "material", "equipment");

    private EconomicSnapshotDiagnosticsGameTest() {}

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void explicitContainerLootReferenceBuildsTypedCandidate(GameTestHelper helper) {
        String structureId = "minecraft:dynamicfood_fixture";
        String secondStructureId = "minecraft:dynamicfood_fixture_second";
        String structureSetId = "minecraft:dynamicfood_fixture";
        String secondStructureSetId = "minecraft:dynamicfood_fixture_second";
        String poolId = "minecraft:dynamicfood_fixture/start";
        String templateId = "minecraft:dynamicfood_fixture/room";
        String tableId = "minecraft:chests/dynamicfood_fixture";
        var lootTable = LootTableAcquisitionAnalyzer.parseTable(tableId, JsonParser.parseString("""
            {"pools":[{"rolls":1,"entries":[
              {"type":"minecraft:item","name":"minecraft:apple"},
              {"type":"minecraft:item","name":"minecraft:carrot"}
            ]}]}
            """).getAsJsonObject()).orElseThrow();
        LootTableAcquisitionAnalyzer lootAnalyzer = LootTableAcquisitionAnalyzer.fromParsedTables(
            List.of(lootTable), 1.0D, 100.0D);
        JsonObject structure = JsonParser.parseString("""
            {"type":"minecraft:jigsaw","start_pool":"minecraft:dynamicfood_fixture/start",
             "size":1,"biomes":"minecraft:plains"}
            """).getAsJsonObject();
        JsonObject structureSet = JsonParser.parseString("""
            {"placement":{"type":"minecraft:random_spread","spacing":32,"separation":8,"salt":1},
             "structures":[{"structure":"minecraft:dynamicfood_fixture","weight":1}]}
            """).getAsJsonObject();
        JsonObject secondStructureSet = JsonParser.parseString("""
            {"placement":{"type":"minecraft:random_spread","spacing":32,"separation":8,"salt":2},
             "structures":[{"structure":"minecraft:dynamicfood_fixture_second","weight":1}]}
            """).getAsJsonObject();
        JsonObject pool = JsonParser.parseString("""
            {"elements":[{"element":{"element_type":"minecraft:single_pool_element",
                                     "location":"minecraft:dynamicfood_fixture/room",
                                     "processors":"minecraft:empty",
                                     "projection":"minecraft:rigid"},"weight":1}],
             "fallback":"minecraft:empty"}
            """).getAsJsonObject();
        JsonObject emptyPool = JsonParser.parseString("""
            {"elements":[],"fallback":"minecraft:empty"}
            """).getAsJsonObject();
        StructureContainerAcquisitionAnalyzer analyzer =
            StructureContainerAcquisitionAnalyzer.fromResources(
                Map.of(structureId, structure, secondStructureId, structure),
                Map.of(structureSetId, structureSet, secondStructureSetId, secondStructureSet),
                Map.of(poolId, pool, "minecraft:empty", emptyPool),
                Map.of(templateId, templateWithBlock(Blocks.CHEST.defaultBlockState(),
                    BlockEntityType.CHEST, "LootTable", tableId)),
                helper.getLevel().registryAccess(), lootAnalyzer);
        List<AcquisitionPath> applePaths = analyzer.analyze("minecraft:apple");
        List<AcquisitionPath> carrotPaths = analyzer.analyze("minecraft:carrot");
        AcquisitionPath path = applePaths.getFirst();
        WorldgenCausalEvidence evidence = path.evidence().worldgenCausalEvidence();
        boolean linkedContainerAndTable = evidence.relationships().stream().anyMatch(edge ->
            edge.type() == WorldgenCausalEvidence.RelationshipType.USES_LOOT_TABLE
                && edge.from().type() == WorldgenCausalEvidence.NodeType.CONTAINER
                && edge.to().type() == WorldgenCausalEvidence.NodeType.LOOT_TABLE);
        boolean linkedTableAndOutput = evidence.relationships().stream().anyMatch(edge ->
            edge.type() == WorldgenCausalEvidence.RelationshipType.YIELDS_ITEM
                && edge.from().equals(new WorldgenCausalEvidence.NodeRef(
                    WorldgenCausalEvidence.NodeType.LOOT_TABLE, tableId))
                && edge.to().equals(new WorldgenCausalEvidence.NodeRef(
                    WorldgenCausalEvidence.NodeType.ITEM_OUTPUT, "minecraft:apple")));

        helper.assertTrue(path.sourceType().equals("worldgen_structure_container")
                && path.sourceId().contains(structureSetId)
                && path.sourceId().contains(structureId)
                && path.sourceId().contains(tableId)
                && linkedContainerAndTable
                && linkedTableAndOutput
                && "PROVEN_POSSIBLE".equals(path.evidence().attributes()
                    .get("loot_output_evidence_status"))
                && applePaths.size() == 2
                && carrotPaths.size() == 2
                && applePaths.stream().map(AcquisitionPath::sourceId).distinct().count() == 2
                && carrotPaths.stream().map(AcquisitionPath::sourceId).distinct().count() == 2
                && carrotPaths.stream().allMatch(candidate -> candidate.evidence()
                    .worldgenCausalEvidence().relationships().stream().anyMatch(edge ->
                        edge.type() == WorldgenCausalEvidence.RelationshipType.YIELDS_ITEM
                            && edge.to().equals(new WorldgenCausalEvidence.NodeRef(
                                WorldgenCausalEvidence.NodeType.ITEM_OUTPUT, "minecraft:carrot")))
                    )
                && analyzer.summary().candidatePaths() == 4
                && new SurvivalAcquirabilityResolver().resolve(List.of(path)).state()
                    == SurvivalAcquirability.UNKNOWN,
            "multiple routes and outputs must retain exact proven links without proving survival availability");

        Map<String, JsonObject> reorderedStructures = new java.util.LinkedHashMap<>();
        reorderedStructures.put(secondStructureId, structure);
        reorderedStructures.put(structureId, structure);
        Map<String, JsonObject> reorderedSets = new java.util.LinkedHashMap<>();
        reorderedSets.put(secondStructureSetId, secondStructureSet);
        reorderedSets.put(structureSetId, structureSet);
        Map<String, JsonObject> reorderedPools = new java.util.LinkedHashMap<>();
        reorderedPools.put("minecraft:empty", emptyPool);
        reorderedPools.put(poolId, pool);
        Map<String, CompoundTag> reorderedTemplates = new java.util.LinkedHashMap<>();
        reorderedTemplates.put(templateId, templateWithBlock(Blocks.CHEST.defaultBlockState(),
            BlockEntityType.CHEST, "LootTable", tableId));
        StructureContainerAcquisitionAnalyzer reorderedAnalyzer =
            StructureContainerAcquisitionAnalyzer.fromResources(
                reorderedStructures, reorderedSets, reorderedPools, reorderedTemplates,
                helper.getLevel().registryAccess(), lootAnalyzer);
        helper.assertTrue(applePaths.stream().map(candidate -> candidate.evidence()
                    .worldgenCausalEvidence().canonicalKey()).toList()
                .equals(reorderedAnalyzer.analyze("minecraft:apple").stream()
                    .map(candidate -> candidate.evidence().worldgenCausalEvidence().canonicalKey()).toList()),
            "resource insertion order must not change causal output links");

        String unknownTableId = "minecraft:chests/dynamicfood_unknown_fixture";
        var unknownLootTable = LootTableAcquisitionAnalyzer.parseTable(unknownTableId,
            JsonParser.parseString("""
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item",
                  "name":"minecraft:pear","functions":[{"function":"minecraft:set_damage",
                  "damage":0.5}]}]}]}
                """).getAsJsonObject()).orElseThrow();
        LootTableAcquisitionAnalyzer unknownLootAnalyzer =
            LootTableAcquisitionAnalyzer.fromParsedTables(List.of(unknownLootTable), 1.0D, 100.0D);
        StructureContainerAcquisitionAnalyzer unknownOutputAnalyzer =
            StructureContainerAcquisitionAnalyzer.fromResources(
                Map.of(structureId, structure), Map.of(structureSetId, structureSet),
                Map.of(poolId, pool, "minecraft:empty", emptyPool),
                Map.of(templateId, templateWithBlock(Blocks.CHEST.defaultBlockState(),
                    BlockEntityType.CHEST, "LootTable", unknownTableId)),
                helper.getLevel().registryAccess(), unknownLootAnalyzer);
        AcquisitionPath unknownPath = unknownOutputAnalyzer.analyze("minecraft:pear").getFirst();
        WorldgenCausalEvidence unknownEvidence = unknownPath.evidence().worldgenCausalEvidence();
        helper.assertTrue(unknownEvidence.nodes().stream().anyMatch(node ->
                node.ref().equals(new WorldgenCausalEvidence.NodeRef(
                    WorldgenCausalEvidence.NodeType.ITEM_OUTPUT_CANDIDATE, "minecraft:pear")))
                && unknownEvidence.relationships().stream().anyMatch(edge ->
                    edge.type() == WorldgenCausalEvidence.RelationshipType.CANDIDATE_OUTPUT
                        && edge.from().identifier().equals(unknownTableId)
                        && edge.to().identifier().equals("minecraft:pear")
                        && !edge.unresolvedReason().isBlank())
                && unknownEvidence.relationships().stream().noneMatch(edge ->
                    edge.type() == WorldgenCausalEvidence.RelationshipType.YIELDS_ITEM
                        && edge.from().identifier().equals(unknownTableId)
                        && edge.to().identifier().equals("minecraft:pear"))
                && !unknownPath.evidence().measurement("expected_units_per_attempt").isKnown()
                && new SurvivalAcquirabilityResolver().resolve(List.of(unknownPath)).state()
                    == SurvivalAcquirability.UNKNOWN,
            "unsupported loot semantics must retain a candidate identity without a proven-yield edge");

        StructureContainerAcquisitionAnalyzer nonContainerAnalyzer =
            StructureContainerAcquisitionAnalyzer.fromResources(
                Map.of(structureId, structure), Map.of(structureSetId, structureSet),
                Map.of(poolId, pool, "minecraft:empty", emptyPool),
                Map.of(templateId, templateWithBlock(Blocks.SPAWNER.defaultBlockState(),
                    BlockEntityType.MOB_SPAWNER, "LootTable", tableId)),
                helper.getLevel().registryAccess(), lootAnalyzer);
        helper.assertTrue(nonContainerAnalyzer.analyze("minecraft:apple").isEmpty()
                && nonContainerAnalyzer.summary().invalidContainerReferences() == 1,
            "a LootTable tag on a non-Container block entity must remain unresolved");

        StructureContainerAcquisitionAnalyzer missingLootTableAnalyzer =
            StructureContainerAcquisitionAnalyzer.fromResources(
                Map.of(structureId, structure), Map.of(structureSetId, structureSet),
                Map.of(poolId, pool, "minecraft:empty", emptyPool),
                Map.of(templateId, templateWithBlock(Blocks.CHEST.defaultBlockState(),
                    BlockEntityType.CHEST, "LootTable", tableId)),
                helper.getLevel().registryAccess(),
                LootTableAcquisitionAnalyzer.fromParsedTables(List.of(), 1.0D, 100.0D));
        helper.assertTrue(missingLootTableAnalyzer.analyze("minecraft:apple").isEmpty(),
            "an unresolved loot-table output must not manufacture an item path");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void positiveSetCountOutputIsLinkedWithoutExpectedQuantity(GameTestHelper helper) {
        String structureId = "minecraft:dynamicfood_set_count_fixture";
        String structureSetId = "minecraft:dynamicfood_set_count_fixture";
        String poolId = "minecraft:dynamicfood_set_count_fixture/start";
        String templateId = "minecraft:dynamicfood_set_count_fixture/room";
        String tableId = "minecraft:chests/dynamicfood_set_count_fixture";
        var lootTable = LootTableAcquisitionAnalyzer.parseTable(tableId, JsonParser.parseString("""
            {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:pear",
              "functions":[{"function":"minecraft:set_count","count":{"min":1,"max":3}}]}]}]}
            """).getAsJsonObject()).orElseThrow();
        LootTableAcquisitionAnalyzer lootAnalyzer = LootTableAcquisitionAnalyzer.fromParsedTables(
            List.of(lootTable), 1.0D, 100.0D);
        JsonObject structure = JsonParser.parseString("""
            {"type":"minecraft:jigsaw","start_pool":"minecraft:dynamicfood_set_count_fixture/start",
             "size":1,"biomes":"minecraft:plains"}
            """).getAsJsonObject();
        JsonObject structureSet = JsonParser.parseString("""
            {"placement":{"type":"minecraft:random_spread","spacing":32,"separation":8,"salt":3},
             "structures":[{"structure":"minecraft:dynamicfood_set_count_fixture","weight":1}]}
            """).getAsJsonObject();
        JsonObject pool = JsonParser.parseString("""
            {"elements":[{"element":{"element_type":"minecraft:single_pool_element",
                                     "location":"minecraft:dynamicfood_set_count_fixture/room",
                                     "processors":"minecraft:empty",
                                     "projection":"minecraft:rigid"},"weight":1}],
             "fallback":"minecraft:empty"}
            """).getAsJsonObject();
        JsonObject emptyPool = JsonParser.parseString("""
            {"elements":[],"fallback":"minecraft:empty"}
            """).getAsJsonObject();
        StructureContainerAcquisitionAnalyzer analyzer = StructureContainerAcquisitionAnalyzer.fromResources(
            Map.of(structureId, structure), Map.of(structureSetId, structureSet),
            Map.of(poolId, pool, "minecraft:empty", emptyPool),
            Map.of(templateId, templateWithBlock(Blocks.CHEST.defaultBlockState(),
                BlockEntityType.CHEST, "LootTable", tableId)),
            helper.getLevel().registryAccess(), lootAnalyzer);
        AcquisitionPath path = analyzer.analyze("minecraft:pear").getFirst();
        WorldgenCausalEvidence evidence = path.evidence().worldgenCausalEvidence();
        boolean provenOutput = evidence.relationships().stream().anyMatch(edge ->
            edge.type() == WorldgenCausalEvidence.RelationshipType.YIELDS_ITEM
                && edge.from().equals(new WorldgenCausalEvidence.NodeRef(
                    WorldgenCausalEvidence.NodeType.LOOT_TABLE, tableId))
                && edge.to().equals(new WorldgenCausalEvidence.NodeRef(
                    WorldgenCausalEvidence.NodeType.ITEM_OUTPUT, "minecraft:pear")));

        helper.assertTrue(provenOutput
                && "PROVEN_POSSIBLE".equals(path.evidence().attributes()
                    .get("loot_output_evidence_status"))
                && !path.evidence().measurement("expected_units_per_attempt").isKnown()
                && path.costsByHorizon().get(100).factor(EconomicChannel.QUANTITY).isUnknown()
                && path.costsByHorizon().get(100).factor(EconomicChannel.PROBABILITY_BURDEN).isNotApplicable()
                && path.costsByHorizon().get(100).factor(EconomicChannel.MATERIAL_CONSUMPTION).isUnknown()
                && path.feasibilityFactors().get("equipment_availability").isUnknown()
                && new SurvivalAcquirabilityResolver().resolve(List.of(path)).state()
                    == SurvivalAcquirability.UNKNOWN,
            "positive set_count output proof must not resolve quantity, feasibility or survival availability");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void writesCompleteProductionEconomicDiagnostics(GameTestHelper helper) throws IOException {
        PublishedEconomicGeneration generation = DynamicFood.ECONOMIC_GENERATIONS.current().orElse(null);
        helper.assertTrue(generation != null,
            "diagnostic report requires the normal startup production economic generation");
        EconomicSnapshot snapshot = generation.economicSnapshot();
        StructureContainerAcquisitionAnalyzer.Summary structureSummary =
            DynamicFood.ENGINE.structureContainerAnalysisSummary();
        helper.assertTrue(structureSummary != null
                && structureSummary.jigsawStructures() > 0
                && structureSummary.referencedTemplates() > 0
                && structureSummary.loadedTemplates() > 0
                && structureSummary.containerPlacements() > 0
                && structureSummary.staticContainerInventories() > 0
                && (structureSummary.candidatePaths() == 0 || structureSummary.lootTableTags() > 0),
            "the active data pack must retain supported jigsaw template and container evidence without "
                + "inferring loot paths from static inventories");
        JsonObject report = EconomicSnapshotDiagnosticReport.create(generation,
            structureSummary);

        Path reportPath = Path.of("reports", "dynamicfood-economic-diagnostics.json");
        Files.createDirectories(reportPath.getParent());
        String serialized = new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report);
        Files.writeString(reportPath, serialized, StandardCharsets.UTF_8);
        JsonObject parsed = JsonParser.parseString(
            Files.readString(reportPath, StandardCharsets.UTF_8)).getAsJsonObject();

        helper.assertTrue(parsed.get("schema").getAsInt() == 1
                && parsed.get("economicSnapshotSchema").getAsInt() == EconomicSnapshotBuilder.SCHEMA_VERSION
                && parsed.get("generation").getAsLong() == generation.generation()
                && parsed.get("economicPolicySignature").getAsString().equals(
                    com.jorjik.dynamicfood.config.DynamicFoodConfig.economicPolicySignature())
                && parsed.get("snapshotSignature").getAsString().equals(snapshot.signature()),
            "the report must be valid JSON for the exact published policy and snapshot generation");
        JsonArray resources = parsed.getAsJsonArray("resources");
        JsonObject summary = parsed.getAsJsonObject("summary");
        helper.assertTrue(resources.size() == snapshot.inputSet().resourceIds().size()
                && summary.get("resources").getAsInt() == snapshot.resources().size(),
            "the report must retain every resource in the production generation input");
        JsonObject structureAnalysis = parsed.getAsJsonObject("structureAnalysis");
        helper.assertTrue(structureAnalysis != null
                && structureAnalysis.get("candidatePaths").getAsInt() == structureSummary.candidatePaths()
                && structureAnalysis.get("unresolvedReferences").getAsInt()
                    == structureSummary.unresolvedReferences(),
            "the production report must expose the analyzer's structural-route diagnostics");
        List<JsonObject> structuralPaths = resources.asList().stream().map(element -> element.getAsJsonObject())
            .flatMap(resource -> resource.getAsJsonArray("paths").asList().stream())
            .map(element -> element.getAsJsonObject())
            .filter(path -> path.get("sourceType").getAsString()
                .equals("worldgen_structure_container")).toList();
        boolean structuralAvailabilityRemainsUnknown = structuralPaths.stream()
            .allMatch(path -> !path.getAsJsonObject("availability").get("classification")
                .getAsString().equals("TRUE"));
        helper.assertTrue(structuralAvailabilityRemainsUnknown,
            "structure discovery must never prove survival access");
        var inputSet = snapshot.inputSet();
        var snapshotResourceIds = inputSet.resourceIds();
        long indexedStructuralPaths = inputSet.indexedResourceIds().stream()
            .flatMap(itemId -> DynamicFood.ENGINE.acquisitionPaths(itemId).stream())
            .filter(path -> path.sourceType().equals("worldgen_structure_container")).count();
        long excludedStructuralPaths = inputSet.indexedResourceIds().stream()
            .filter(itemId -> !snapshotResourceIds.contains(itemId))
            .flatMap(itemId -> DynamicFood.ENGINE.acquisitionPaths(itemId).stream())
            .filter(path -> path.sourceType().equals("worldgen_structure_container")).count();
        boolean excludedIdsAreTechnical = inputSet.technicalCandidateExclusionIds().containsAll(
            inputSet.indexedResourceIds().stream()
                .filter(itemId -> !snapshotResourceIds.contains(itemId)).toList());
        helper.assertTrue(!structuralPaths.isEmpty()
                && indexedStructuralPaths == structureSummary.candidatePaths()
                && structuralPaths.size() + excludedStructuralPaths == indexedStructuralPaths
                && excludedIdsAreTechnical
                && structuralPaths.stream().allMatch(path -> path.getAsJsonObject("diagnostics")
                    .getAsJsonObject("evidence").has("worldgenCausalEvidenceSummary")),
            "structural candidates must reconcile to published paths or established technical exclusions");
        JsonObject acaciaLog = resources.asList().stream()
            .map(element -> element.getAsJsonObject())
            .filter(resource -> resource.get("resourceId").getAsString().equals("minecraft:acacia_log"))
            .findFirst().orElse(null);
        helper.assertTrue(acaciaLog != null, "the production snapshot must retain the acacia-log resource");
        JsonObject acaciaWorldgen = acaciaLog == null ? null : acaciaLog.getAsJsonArray("paths").asList().stream()
            .map(element -> element.getAsJsonObject())
            .filter(path -> path.get("sourceType").getAsString().equals("worldgen_feature")
                && path.getAsJsonObject("diagnostics").getAsJsonObject("evidence").getAsJsonObject("attributes")
                    .get("worldgen_block_id").getAsString().equals("minecraft:acacia_log"))
            .findFirst().orElse(null);
        helper.assertTrue(acaciaWorldgen != null,
            "the savanna tree feature and block-break extraction must be linked in production evidence");
        if (acaciaWorldgen != null) {
            JsonObject causalEvidence = acaciaWorldgen.getAsJsonObject("diagnostics")
                .getAsJsonObject("evidence").getAsJsonObject("worldgenCausalEvidence");
            JsonArray causalNodes = causalEvidence.getAsJsonArray("nodes");
            JsonArray causalRelationships = causalEvidence.getAsJsonArray("relationships");
            helper.assertTrue(causalNodes.asList().stream().map(element -> element.getAsJsonObject())
                    .anyMatch(node -> node.get("type").getAsString().equals("BIOME")),
                "the published worldgen path must retain its biome declaration node");
            helper.assertTrue(causalRelationships.asList().stream().map(element -> element.getAsJsonObject())
                    .anyMatch(relationship -> relationship.get("type").getAsString().equals("EXTRACTED_BY"))
                    && causalRelationships.asList().stream().map(element -> element.getAsJsonObject())
                        .anyMatch(relationship -> relationship.get("type").getAsString()
                            .equals("USES_LOOT_TABLE")),
                "the published graph must retain block extraction and loot-table causality");
        }
        boolean activeOverworldBiomeEvidence = snapshot.resources().values().stream()
            .flatMap(resource -> resource.acquisitionPaths().stream())
            .filter(path -> path.sourceType().equals("worldgen_feature"))
            .map(path -> path.evidence().attributes().getOrDefault("dimension", ""))
            .anyMatch(dimension -> dimension.contains("minecraft:overworld"));
        helper.assertTrue(activeOverworldBiomeEvidence,
            "production worldgen paths must retain biome membership from an active Overworld biome source");
        if (acaciaWorldgen != null) {
            helper.assertTrue(acaciaWorldgen.getAsJsonObject("availability")
                    .get("classification").getAsString().equals("UNKNOWN"),
                "worldgen tree discovery alone must not establish survival availability");
        }

        long pathCount = 0;
        for (var resourceElement : resources) {
            JsonObject resource = resourceElement.getAsJsonObject();
            JsonArray paths = resource.getAsJsonArray("paths");
            pathCount += paths.size();
            for (var pathElement : paths) {
                JsonObject path = pathElement.getAsJsonObject();
                JsonObject factors = path.getAsJsonObject("costVector").getAsJsonObject("factors");
                helper.assertTrue(factors.size() == FACTORS.size()
                        && FACTORS.stream().allMatch(factors::has),
                    "every discovered path must serialize the four canonical economic channels");
                helper.assertTrue(path.has("pipeline") && path.has("acquisitionCost")
                        && path.has("economicResolution") && path.has("diagnostics"),
                    "every path must retain pipeline, cost, resource-resolution and evidence diagnostics");
            }
        }
        helper.assertTrue(pathCount == summary.get("paths").getAsLong()
                && summary.get("coreCompleteVectors").getAsLong()
                    + summary.get("coreIncompleteVectors").getAsLong() == pathCount
                && summary.get("acquisitionCostResolved").getAsLong()
                    + summary.get("acquisitionCostUnknown").getAsLong() == pathCount
                && summary.get("economicCostResolved").getAsLong()
                    + summary.get("economicCostUnknown").getAsLong() == resources.size(),
            "resource, vector and cost totals must reconcile with serialized paths");

        long analyzerPathCount = 0;
        JsonObject perAnalyzer = parsed.getAsJsonObject("perAnalyzer");
        for (String analyzer : List.of("recipe", "loot", "crop", "worldgen", "trade", "other")) {
            analyzerPathCount += perAnalyzer.getAsJsonObject(analyzer).get("paths").getAsLong();
        }
        long firstBlockers = 0;
        for (var blocker : summary.getAsJsonObject("firstBlockerCounts").entrySet()) {
            firstBlockers += blocker.getValue().getAsLong();
        }
        helper.assertTrue(analyzerPathCount == pathCount && firstBlockers == pathCount
                && summary.get("resourcesWithAtLeastOnePath").getAsLong()
                    + summary.get("resourcesWithoutPath").getAsLong() == resources.size(),
            "per-analyzer, first-blocker and no-path aggregates must reconcile");
        helper.succeed();
    }

    private static CompoundTag templateWithBlock(BlockState state, BlockEntityType<?> type,
        String lootKey, String lootValue) {
        CompoundTag root = new CompoundTag();
        root.putIntArray("size", new int[] {1, 1, 1});
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(state));
        root.put(StructureTemplate.PALETTE_TAG, palette);
        CompoundTag blockEntity = new CompoundTag();
        ResourceLocation typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        blockEntity.putString("id", typeId.toString());
        blockEntity.putString(lootKey, lootValue);
        CompoundTag block = new CompoundTag();
        block.putInt(StructureTemplate.BLOCK_TAG_STATE, 0);
        block.putIntArray(StructureTemplate.BLOCK_TAG_POS, new int[] {0, 0, 0});
        block.put(StructureTemplate.BLOCK_TAG_NBT, blockEntity);
        ListTag blocks = new ListTag();
        blocks.add(block);
        root.put(StructureTemplate.BLOCKS_TAG, blocks);
        return root;
    }
}
