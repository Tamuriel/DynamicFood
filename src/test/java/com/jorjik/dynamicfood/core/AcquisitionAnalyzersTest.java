package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import com.jorjik.dynamicfood.graph.RecipeGraph;
import com.jorjik.dynamicfood.graph.RecipeNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AcquisitionAnalyzersTest {
    @Test
    void unknownJavaOnlySourcesAreNotInferredFromItemNames() {
        DynamicFoodEngine engine = new DynamicFoodEngine();
        engine.replaceStaticRecipes(java.util.List.of());

        assertTrue(engine.acquisitionPaths("test:crop_apple").isEmpty());
        assertTrue(engine.acquisitionPaths("test:beef_cooked").isEmpty());
        assertTrue(engine.acquisitionPaths("test:salmon_fillet").isEmpty());
        assertTrue(engine.acquisitionPaths("test:bread_villager").isEmpty());
        assertTrue(engine.acquisitionPaths("test:ruin_chest_food").isEmpty());
    }

    @Test
    void economicSnapshotPreservesImmutableResultsAndEstimateMetadata() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:food_recipe", "minecraft:crafting", "test:food", 2,
            List.of(), null, List.of()));
        AcquisitionAnalyzer recipes = new RecipeGraphAcquisitionAnalyzer(graph,
            (itemId, horizon) -> EconomicCost.unknown("no independent economic primitive"),
            1.0D, 100.0D);
        var lootTable = LootTableAcquisitionAnalyzer.parseTable("test:gameplay/food", json("""
            {"pools":[{"rolls":1,"entries":[
              {"type":"minecraft:item","name":"test:food","weight":1},
              {"type":"minecraft:item","name":"test:other","weight":1}
            ]}]}
            """)).orElseThrow();
        AcquisitionAnalyzer loot = LootTableAcquisitionAnalyzer.fromParsedTables(
            List.of(lootTable), 1.0D, 100.0D);
        var unknownResolver = (java.util.function.BiFunction<String, List<AcquisitionPath>,
            EconomicCostResolution>) (itemId, paths) -> new EconomicCostResolution(
                EconomicCost.unknown("no independently evidenced EconomicCost"),
                null, ResolutionStatus.UNKNOWN, 100, null, paths, 0.0D,
                List.of("EconomicCost remains UNKNOWN"));

        EconomicSnapshot first = EconomicSnapshotBuilder.build(7, List.of("test:food"),
            List.of(recipes, loot), unknownResolver);
        EconomicSnapshot repeated = EconomicSnapshotBuilder.build(7, List.of("test:food"),
            List.of(recipes, loot), unknownResolver);
        EconomicSnapshot nextGeneration = EconomicSnapshotBuilder.build(8, List.of("test:food"),
            List.of(recipes, loot), unknownResolver);
        EconomicSnapshot.ResourceResult food = first.resource("test:food").orElseThrow();

        assertEquals(7, first.generation());
        assertEquals(first.signature(), repeated.signature());
        assertEquals(first, repeated, "same generation and inputs must produce an equal snapshot");
        assertEquals(first.signature(), nextGeneration.signature(),
            "generation identity is separate from the deterministic content signature");
        assertEquals(first.resources(), repeated.resources());
        assertEquals(2, food.acquisitionPaths().size(),
            "snapshot must retain recipe and loot acquisition sources");
        assertEquals(ResolutionStatus.UNKNOWN, food.economicResolution().status());
        assertTrue(!food.economicCost().isKnown());
        assertTrue(food.acquisitionPaths().stream().anyMatch(path ->
            path.sourceType().equals("recipe")
                && path.evidence().measurement("expected_units_per_attempt")
                    .estimateKind().orElseThrow() == EstimateKind.EXACT));
        AcquisitionPath lootPath = food.acquisitionPaths().stream()
            .filter(path -> path.sourceType().equals("loot_table")).findFirst().orElseThrow();
        AcquisitionMeasurement expectedUnits = lootPath.evidence().measurement("expected_units_per_attempt");
        assertEquals(EstimateKind.ANALYTICAL, expectedUnits.estimateKind().orElseThrow());
        assertTrue(expectedUnits.estimateMetadata().isPresent());
        assertTrue(food.acquisitionPaths().stream().allMatch(path -> !path.economicCost().isKnown()));

        assertThrows(UnsupportedOperationException.class, () -> first.resources().clear());
        assertThrows(UnsupportedOperationException.class, () -> food.acquisitionPaths().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> expectedUnits.estimateMetadata().orElseThrow().parameters().clear());
        assertThrows(IllegalArgumentException.class, () -> new EconomicSnapshot(
            first.schemaVersion(), first.generation(), "forged-signature", first.resources()));
    }

    @Test
    void worldgenDiscoveryFollowsBiomePlacedAndConfiguredFeaturesWithoutNamespaceGuessing() {
        Map<String, JsonObject> resources = Map.ofEntries(
            Map.entry("testmod:worldgen/biome/forest.json", json("""
                    {"features":[["testmod:ore_patch"],["testmod:trees"]]}
                    """)),
            Map.entry("testmod:worldgen/placed_feature/ore_patch.json", json("""
                    {"feature":"testmod:ore_config","placement":[
                      {"type":"minecraft:count","count":{"type":"minecraft:constant","value":4}},
                      {"type":"minecraft:rarity_filter","chance":20},
                      {"type":"minecraft:height_range","height":{"type":"minecraft:uniform","min_inclusive":{"absolute":0},"max_inclusive":{"absolute":64}}}
                    ]}
                    """)),
            Map.entry("testmod:worldgen/configured_feature/ore_config.json", json("""
                    {"type":"minecraft:ore","config":{"size":8,"targets":[
                      {"target":{"predicate_type":"minecraft:tag_match","tag":"minecraft:stone_ore_replaceables"},
                       "state":{"Name":"minecraft:coal_ore"}},
                      {"target":{"predicate_type":"minecraft:tag_match","tag":"minecraft:deepslate_ore_replaceables"},
                       "state":{"Name":"minecraft:deepslate_coal_ore"}}
                    ]}}
                    """)),
            Map.entry("testmod:worldgen/placed_feature/trees.json", json("""
                    {"feature":"testmod:tree_selector","placement":[]}
                    """)),
            Map.entry("testmod:worldgen/configured_feature/tree_selector.json", json("""
                    {"feature":"minecraft:random_selector","config":{
                      "default":"testmod:tree_branch_checked",
                      "features":[{"chance":0.8,"feature":"testmod:tree_checked"}]
                    }}
                    """)),
            Map.entry("testmod:worldgen/placed_feature/tree_branch_checked.json", json("""
                    {"feature":"testmod:tree_branch","placement":[]}
                    """)),
            Map.entry("testmod:worldgen/placed_feature/tree_checked.json", json("""
                    {"feature":"testmod:tree_nested","placement":[]}
                    """)),
            Map.entry("testmod:worldgen/configured_feature/tree_branch.json", json("""
                    {"feature":"minecraft:decorated","config":{"feature":"testmod:tree_trunk"}}
                    """)),
            Map.entry("testmod:worldgen/configured_feature/tree_nested.json", json("""
                    {"type":"minecraft:tree","config":{
                      "trunk_provider":{"type":"minecraft:simple_state_provider",
                        "state":{"Name":"minecraft:oak_log"}},
                      "foliage_provider":{"type":"minecraft:simple_state_provider",
                        "state":{"Name":"minecraft:oak_leaves"}},
                      "dirt_provider":{"type":"minecraft:simple_state_provider",
                        "state":{"Name":"minecraft:dirt"}},
                      "trunk_placer":{"type":"minecraft:straight_trunk_placer",
                        "base_height":4,"height_rand_a":2,"height_rand_b":0},
                      "foliage_placer":{"type":"minecraft:blob_foliage_placer",
                        "radius":2,"offset":0,"height":3},
                      "minimum_size":{"type":"minecraft:two_layers_feature_size",
                        "limit":1,"lower_size":0,"upper_size":1}
                    }}
                    """)),
            Map.entry("testmod:worldgen/configured_feature/tree_trunk.json", json("""
                    {"type":"minecraft:tree","config":{
                      "trunk_provider":{"type":"minecraft:simple_state_provider",
                        "state":{"Name":"minecraft:birch_log"}},
                      "foliage_provider":{"type":"minecraft:simple_state_provider",
                        "state":{"Name":"minecraft:birch_leaves"}},
                      "dirt_provider":{"type":"minecraft:simple_state_provider",
                        "state":{"Name":"minecraft:dirt"}},
                      "trunk_placer":{"type":"minecraft:straight_trunk_placer",
                        "base_height":4,"height_rand_a":2,"height_rand_b":0},
                      "foliage_placer":{"type":"minecraft:blob_foliage_placer",
                        "radius":2,"offset":0,"height":3},
                      "minimum_size":{"type":"minecraft:two_layers_feature_size",
                        "limit":1,"lower_size":0,"upper_size":1}
                    }}
                    """)),
            Map.entry("testmod:worldgen/placed_feature/unreferenced.json", json("""
                    {"feature":"testmod:orphan_config","placement":[]}
                    """)),
            Map.entry("testmod:worldgen/configured_feature/orphan_config.json", json("""
                    {"feature":"minecraft:ore","config":{"state":{"Name":"minecraft:diamond_ore"}}}
                    """)),
            Map.entry("testmod:dimension/my_dimension.json", json("""
                    {"generator":{"type":"minecraft:noise","biome_source":{
                      "type":"minecraft:fixed","biome":"testmod:forest"
                    }}}
                    """)));
        Map<String, java.util.List<WorldgenAcquisitionAnalyzer.WorldgenBlockSource>> discovered =
            WorldgenAcquisitionAnalyzer.discoverBlockSources(resources);

        assertTrue(discovered.containsKey("minecraft:coal_ore"));
        assertTrue(discovered.containsKey("minecraft:deepslate_coal_ore"));
        assertTrue(discovered.containsKey("minecraft:oak_log"),
            "nested random-selector configured features must expose their generated block states");
        assertTrue(discovered.containsKey("minecraft:oak_leaves"),
            "native tree foliage providers must remain distinct generated block outputs");
        assertTrue(discovered.containsKey("minecraft:birch_log"),
            "decorated configured-feature references must be followed to generated block states");
        assertTrue(discovered.containsKey("minecraft:birch_leaves"));
        assertTrue(!discovered.containsKey("minecraft:diamond_ore"),
            "configured features not referenced by an enabled biome must not be asserted as obtainable");
        assertTrue(discovered.get("minecraft:coal_ore").size() == 1);
        assertTrue(discovered.get("minecraft:coal_ore").getFirst().sourceId()
            .equals("testmod:forest/testmod:ore_patch"));
        var source = discovered.get("minecraft:coal_ore").getFirst();
        assertEquals(4.0D, source.measurements().get("placement_count_modifier_0").value());
        assertEquals(0.05D, source.measurements().get("rarity_filter_chance_1").value());
        assertEquals(8.0D, source.measurements()
            .get("configured:testmod:ore_config:configured_cluster_size").value());
        assertEquals("testmod:forest", source.attributes().get("biome_restriction"));
        assertEquals("testmod:my_dimension", source.attributes().get("dimension"));
        assertTrue(source.causalEvidence().nodes().stream().anyMatch(node ->
            node.ref().identifier().contains("#ore_target:")));
        assertTrue(source.causalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.MAY_GENERATE_BLOCK
                && relationship.branchEvidence().contains("predicate_type")));
        var treeSource = discovered.get("minecraft:oak_log").getFirst();
        assertTrue(treeSource.attributes().get("nested_placed_features").contains("testmod:tree_checked"));
        assertTrue(treeSource.attributes().get("configured_features").contains("testmod:tree_nested"));
        assertTrue(treeSource.causalEvidence().nodes().stream().anyMatch(node ->
            node.ref().type() == WorldgenCausalEvidence.NodeType.FEATURE_STEP));
        assertTrue(treeSource.causalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.ALTERNATIVE_BRANCH
                && relationship.branchEvidence().contains("chance")));
        assertTrue(treeSource.causalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.DEFAULT_BRANCH));
        assertTrue(treeSource.causalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.MAY_GENERATE_BLOCK
                && relationship.to().identifier().contains("minecraft:oak_log")));

    }

    @Test
    void worldgenCausalEvidenceRetainsBooleanBranchesWeightedOutputsAndUnsupportedMechanics() {
        Map<String, JsonObject> resources = Map.ofEntries(
            Map.entry("test:worldgen/biome/forest.json", json("""
                {"features":[["test:selector"],["test:unknown_provider"]]}
                """)),
            Map.entry("test:worldgen/placed_feature/selector.json", json("""
                {"feature":"test:boolean_selector","placement":[]}
                """)),
            Map.entry("test:worldgen/configured_feature/boolean_selector.json", json("""
                {"type":"minecraft:random_boolean_selector","config":{
                  "feature_true":"test:weighted_patch",
                  "feature_false":"test:unsupported_patch"
                }}
                """)),
            Map.entry("test:worldgen/placed_feature/weighted_patch.json", json("""
                {"feature":"test:weighted_block","placement":[]}
                """)),
            Map.entry("test:worldgen/configured_feature/weighted_block.json", json("""
                {"type":"minecraft:simple_block","config":{"to_place":{
                  "type":"minecraft:weighted_state_provider","entries":[
                    {"data":{"Name":"minecraft:stone"},"weight":3},
                    {"data":{"Name":"minecraft:andesite"},"weight":1}
                  ]
                }}}
                """)),
            Map.entry("test:worldgen/placed_feature/unsupported_patch.json", json("""
                {"feature":"test:unsupported_feature","placement":[]}
                """)),
            Map.entry("test:worldgen/configured_feature/unsupported_feature.json", json("""
                {"type":"test:unsupported_feature","config":{"state":{"Name":"minecraft:granite"}}}
                """)),
            Map.entry("test:worldgen/placed_feature/unknown_provider.json", json("""
                {"feature":"test:provider_feature","placement":[]}
                """)),
            Map.entry("test:worldgen/configured_feature/provider_feature.json", json("""
                {"type":"minecraft:simple_block","config":{"to_place":{
                  "type":"test:unknown_state_provider","state":{"Name":"minecraft:diorite"}
                }}}
                """)));

        Map<String, List<WorldgenAcquisitionAnalyzer.WorldgenBlockSource>> discovered =
            WorldgenAcquisitionAnalyzer.discoverBlockSources(resources);

        assertTrue(discovered.containsKey("minecraft:stone"));
        assertTrue(discovered.containsKey("minecraft:andesite"));
        assertTrue(discovered.containsKey("minecraft:granite"));
        assertTrue(discovered.containsKey("minecraft:diorite"));
        var stone = discovered.get("minecraft:stone").getFirst();
        assertTrue(stone.causalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.CONDITIONAL_BRANCH
                && relationship.branchEvidence().equals("feature_true")));
        assertTrue(stone.causalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.MAY_GENERATE_BLOCK
                && relationship.branchEvidence().contains("weighted_state_provider.weight")));
        var granite = discovered.get("minecraft:granite").getFirst();
        assertTrue(granite.causalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.UNRESOLVED
                && relationship.unresolvedReason().contains("not supported")));
        assertTrue(!granite.measurements().containsKey("expected_units_per_attempt"),
            "weighted alternatives and unsupported feature evidence do not invent item yield or probability");
        assertTrue(discovered.get("minecraft:diorite").getFirst().causalEvidence().relationships().stream()
            .anyMatch(relationship -> relationship.type() == WorldgenCausalEvidence.RelationshipType.UNRESOLVED
                && relationship.unresolvedReason().contains("unsupported block-state provider")));
    }

    @Test
    void worldgenMechanicalIdentityExcludesDiagnosticTextButRetainsItInCanonicalEvidence() {
        WorldgenCausalEvidence.NodeRef feature = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.CONFIGURED_FEATURE, "test:unknown");
        WorldgenCausalEvidence.NodeRef unresolved = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.UNRESOLVED_MECHANIC, "test:unknown#output");
        WorldgenCausalEvidence first = new WorldgenCausalEvidence.Builder()
            .addNode(feature.type(), feature.identifier(), "test:feature.json", "{}")
            .addNode(unresolved.type(), unresolved.identifier(), "test:feature.json", "{}")
            .addRelationship(feature, WorldgenCausalEvidence.RelationshipType.UNRESOLVED, unresolved,
                WorldgenCausalEvidence.BranchKind.UNKNOWN, "", "test:feature.json", "unsupported feature type")
            .build();
        WorldgenCausalEvidence relabeled = new WorldgenCausalEvidence.Builder()
            .addNode(feature.type(), feature.identifier(), "test:feature.json", "{}")
            .addNode(unresolved.type(), unresolved.identifier(), "test:feature.json", "{}")
            .addRelationship(feature, WorldgenCausalEvidence.RelationshipType.UNRESOLVED, unresolved,
                WorldgenCausalEvidence.BranchKind.UNKNOWN, "", "test:feature.json", "decoder unavailable")
            .build();

        assertEquals(first.mechanicalIdentityKey(), relabeled.mechanicalIdentityKey());
        assertFalse(first.canonicalKey().equals(relabeled.canonicalKey()),
            "diagnostic reason changes remain visible in snapshot evidence");
    }

    @Test
    void worldgenReusedPlacedFeatureKeepsEachBranchWithoutInventingACycle() {
        Map<String, JsonObject> resources = Map.of(
            "test:worldgen/biome/forest.json", json("""
                {"features":[["test:root"]]}
                """),
            "test:worldgen/placed_feature/root.json", json("""
                {"feature":"test:selector","placement":[]}
                """),
            "test:worldgen/configured_feature/selector.json", json("""
                {"type":"minecraft:random_selector","config":{
                  "default":"test:shared",
                  "features":[
                    {"chance":0.25,"feature":"test:shared"},
                    {"chance":0.5,"feature":"test:shared"}
                  ]
                }}
                """),
            "test:worldgen/placed_feature/shared.json", json("""
                {"feature":"test:tree","placement":[]}
                """),
            "test:worldgen/configured_feature/tree.json", json("""
                {"type":"minecraft:tree","config":{"trunk_provider":{
                  "type":"minecraft:simple_state_provider","state":{"Name":"minecraft:oak_log"}
                }}}
                """));

        var source = WorldgenAcquisitionAnalyzer.discoverBlockSources(resources)
            .get("minecraft:oak_log").getFirst();
        long branchReferences = source.causalEvidence().relationships().stream()
            .filter(relationship -> relationship.type() == WorldgenCausalEvidence.RelationshipType.ALTERNATIVE_BRANCH
                && relationship.to().identifier().equals("test:shared"))
            .count();

        assertEquals(2L, branchReferences);
        assertTrue(source.causalEvidence().nodes().stream().noneMatch(node ->
            node.ref().identifier().contains("#cycle")));
    }

    @Test
    void worldgenInlineConfiguredFeatureChainRetainsFlowerRandomPatchAndOutput() {
        Map<String, JsonObject> resources = Map.of(
            "test:worldgen/biome/forest.json", json("""
                {"features":[["test:flower_patch"]]}
                """),
            "test:worldgen/placed_feature/flower_patch.json", json("""
                {"feature":"test:flower","placement":[]}
                """),
            "test:worldgen/configured_feature/flower.json", json("""
                {"type":"minecraft:flower","config":{
                  "tries":64,"xz_spread":7,"y_spread":3,
                  "feature":{"type":"minecraft:random_patch","config":{
                    "tries":64,"xz_spread":7,"y_spread":3,
                    "feature":{"type":"minecraft:simple_block","config":{"to_place":{
                      "type":"minecraft:simple_state_provider","state":{"Name":"minecraft:allium"}
                    }}}
                  }}
                }}
                """));

        var source = WorldgenAcquisitionAnalyzer.discoverBlockSources(resources)
            .get("minecraft:allium").getFirst();

        assertTrue(source.causalEvidence().nodes().stream().filter(node ->
            node.ref().type() == WorldgenCausalEvidence.NodeType.CONFIGURED_FEATURE).count() == 3);
        assertTrue(source.causalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.NESTED_CONFIGURED_FEATURE));
        assertTrue(source.causalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.MAY_GENERATE_BLOCK
                && relationship.to().identifier().contains("minecraft:allium")));
    }

    @Test
    void worldgenRootSystemRetainsNestedPlacedTreeAndRootProviders() {
        Map<String, JsonObject> resources = Map.of(
            "test:worldgen/biome/lush_caves.json", json("""
                {"features":[["test:rooted_azalea"]]}
                """),
            "test:worldgen/placed_feature/rooted_azalea.json", json("""
                {"feature":"test:root_system","placement":[]}
                """),
            "test:worldgen/configured_feature/root_system.json", json("""
                {"type":"minecraft:root_system","config":{
                  "feature":{"feature":"test:azalea_tree","placement":[]},
                  "root_state_provider":{"type":"minecraft:simple_state_provider",
                    "state":{"Name":"minecraft:rooted_dirt"}},
                  "hanging_root_state_provider":{"type":"minecraft:simple_state_provider",
                    "state":{"Name":"minecraft:hanging_roots"}}
                }}
                """),
            "test:worldgen/placed_feature/azalea_tree.json", json("""
                {"feature":"test:azalea_tree","placement":[]}
                """),
            "test:worldgen/configured_feature/azalea_tree.json", json("""
                {"type":"minecraft:tree","config":{
                  "dirt_provider":{"type":"minecraft:simple_state_provider",
                    "state":{"Name":"minecraft:dirt"}},
                  "trunk_provider":{"type":"minecraft:simple_state_provider",
                    "state":{"Name":"minecraft:oak_log"}},
                  "foliage_provider":{"type":"minecraft:weighted_state_provider","entries":[
                    {"data":{"Name":"minecraft:azalea_leaves"},"weight":3},
                    {"data":{"Name":"minecraft:flowering_azalea_leaves"},"weight":1}
                  ]},
                  "decorators":[{"type":"minecraft:alter_ground",
                    "provider":{"type":"minecraft:simple_state_provider",
                      "state":{"Name":"minecraft:podzol"}}}]
                }}
                """));

        Map<String, List<WorldgenAcquisitionAnalyzer.WorldgenBlockSource>> sources =
            WorldgenAcquisitionAnalyzer.discoverBlockSources(resources);

        assertTrue(sources.keySet().containsAll(List.of("minecraft:rooted_dirt", "minecraft:hanging_roots",
            "minecraft:dirt", "minecraft:oak_log", "minecraft:azalea_leaves",
            "minecraft:flowering_azalea_leaves", "minecraft:podzol")));
        var leaves = sources.get("minecraft:azalea_leaves").getFirst().causalEvidence();
        assertTrue(leaves.relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.NESTED_PLACED_FEATURE));
        assertTrue(leaves.relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.UNRESOLVED
                && relationship.unresolvedReason().contains("root-system")));
        assertTrue(leaves.relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.MAY_GENERATE_BLOCK
                && relationship.branchKind() == WorldgenCausalEvidence.BranchKind.ALTERNATIVE
                && relationship.to().identifier().contains("minecraft:azalea_leaves")));
        var podzol = sources.get("minecraft:podzol").getFirst().causalEvidence();
        assertTrue(podzol.relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.MAY_GENERATE_BLOCK
                && relationship.branchKind() == WorldgenCausalEvidence.BranchKind.CONDITIONAL
                && relationship.branchEvidence().contains("alter_ground")));
    }

    @Test
    void worldgenVegetationPatchRetainsConditionalNestedFeatureWithoutResolvingPatchMechanics() {
        Map<String, JsonObject> resources = Map.of(
            "test:worldgen/biome/lush_caves.json", json("""
                {"features":[["test:dripleaf_patch"]]}
                """),
            "test:worldgen/placed_feature/dripleaf_patch.json", json("""
                {"feature":"test:selector","placement":[]}
                """),
            "test:worldgen/configured_feature/selector.json", json("""
                {"type":"minecraft:random_boolean_selector","config":{
                  "feature_false":{"feature":"test:water_patch","placement":[]},
                  "feature_true":{"feature":"test:dry_patch","placement":[]}
                }}
                """),
            "test:worldgen/configured_feature/water_patch.json", json("""
                {"type":"minecraft:waterlogged_vegetation_patch","config":{
                  "vegetation_chance":0.1,
                  "ground_state":{"type":"minecraft:simple_state_provider",
                    "state":{"Name":"minecraft:moss_block"}},
                  "vegetation_feature":{"feature":"test:dripleaf","placement":[]}
                }}
                """),
            "test:worldgen/configured_feature/dry_patch.json", json("""
                {"type":"minecraft:vegetation_patch","config":{
                  "vegetation_chance":0.05,
                  "ground_state":{"type":"minecraft:simple_state_provider",
                    "state":{"Name":"minecraft:moss_block"}},
                  "vegetation_feature":{"feature":"test:dripleaf","placement":[]}
                }}
                """),
            "test:worldgen/placed_feature/dripleaf.json", json("""
                {"feature":"test:dripleaf_block","placement":[]}
                """),
            "test:worldgen/configured_feature/dripleaf_block.json", json("""
                {"type":"minecraft:simple_block","config":{"to_place":{
                  "type":"minecraft:weighted_state_provider","entries":[
                    {"data":{"Name":"minecraft:big_dripleaf"},"weight":1},
                    {"data":{"Name":"minecraft:small_dripleaf"},"weight":1}
                  ]
                }}}
                """));

        Map<String, List<WorldgenAcquisitionAnalyzer.WorldgenBlockSource>> sources =
            WorldgenAcquisitionAnalyzer.discoverBlockSources(resources);
        var dripleaf = sources.get("minecraft:big_dripleaf").getFirst().causalEvidence();

        assertTrue(sources.containsKey("minecraft:small_dripleaf"));
        assertTrue(sources.containsKey("minecraft:moss_block"));
        assertTrue(dripleaf.relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.CONDITIONAL_BRANCH
                && relationship.branchEvidence().contains("vegetation_chance=0.1")));
        assertTrue(dripleaf.relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.UNRESOLVED
                && relationship.unresolvedReason().contains("vegetation-patch")));
        assertTrue(dripleaf.relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.MAY_GENERATE_BLOCK
                && relationship.branchKind() == WorldgenCausalEvidence.BranchKind.ALTERNATIVE));
        assertTrue(sources.get("minecraft:moss_block").getFirst().causalEvidence().relationships().stream()
            .anyMatch(relationship -> relationship.type() == WorldgenCausalEvidence.RelationshipType.UNRESOLVED
                && relationship.unresolvedReason().contains("vegetation-patch")));
    }

    @Test
    void worldgenNativeFlowerForestInlinePlacedFeatureRetainsUnresolvedNoiseProviderOutputs() {
        Map<String, JsonObject> resources = Map.of(
            "test:worldgen/biome/forest.json", json("""
                {"features":[["test:flower_forest"]]}
                """),
            "test:worldgen/placed_feature/flower_forest.json", json("""
                {"feature":"test:flower_flower_forest","placement":[]}
                """),
            "test:worldgen/configured_feature/flower_flower_forest.json", json("""
                {"type":"minecraft:flower","config":{"feature":{
                  "feature":{"type":"minecraft:simple_block","config":{"to_place":{
                    "type":"minecraft:noise_provider","states":[
                      {"Name":"minecraft:dandelion"},
                      {"Name":"minecraft:allium"}
                    ]
                  }}},
                  "placement":[{"type":"minecraft:block_predicate_filter"}]
                }}}
                """));

        Map<String, List<WorldgenAcquisitionAnalyzer.WorldgenBlockSource>> sources =
            WorldgenAcquisitionAnalyzer.discoverBlockSources(resources);
        var allium = sources.get("minecraft:allium").getFirst();

        assertTrue(sources.containsKey("minecraft:dandelion"));
        assertTrue(allium.causalEvidence().nodes().stream().anyMatch(node ->
            node.ref().type() == WorldgenCausalEvidence.NodeType.PLACED_FEATURE
                && node.ref().identifier().contains("#config.feature:")));
        assertTrue(allium.causalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.UNRESOLVED
                && relationship.unresolvedReason().contains("noise_provider")));
    }

    @Test
    void worldgenDiscoveryDoesNotInventExtractionQuantityOrEconomicCost() {
        var source = new WorldgenAcquisitionAnalyzer.WorldgenBlockSource("test:forest/test:ore_patch",
            "test:forest", "test:ore_patch",
            Map.of("configured_cluster_size", AcquisitionMeasurement.known(8.0D)),
            Map.of("biome_restriction", "test:forest", "source_feature", "test:ore_patch"));
        var analyzer = WorldgenAcquisitionAnalyzer.fromItemSources(Map.of("test:ore", List.of(source)));
        AcquisitionPath path = analyzer.analyze("test:ore").getFirst();

        assertEquals(8.0D, path.evidence().measurement("configured_cluster_size").value());
        assertTrue(!path.evidence().measurement("expected_units_per_attempt").isKnown());
        assertEquals(EstimateKind.UNKNOWN,
            path.evidence().measurement("expected_units_per_attempt").estimateKind().orElseThrow());
        assertTrue(path.costsByHorizon().get(100).factors().get("quantity_cost").isUnknown());
        assertTrue(path.costsByHorizon().get(100).factors().get("equipment_cost").isUnknown());
        assertTrue(!path.economicCost().isKnown());
        assertEquals("UNKNOWN", path.evidence().attributes().get("source_availability_classification"));
    }

    @Test
    void worldgenBlockBreakUsesRegisteredLootTableForExtractionEvidence() {
        var stoneTable = new LootTableAcquisitionAnalyzer.ParsedTable("minecraft:blocks/stone", "block_loot",
            Map.of("minecraft:stone", 1.0D), java.util.Set.of(),
            Map.of("minecraft:stone", new LootTableAcquisitionAnalyzer.LootEvidence(1.0D, 1.0D)));
        var oreTable = new LootTableAcquisitionAnalyzer.ParsedTable("minecraft:blocks/coal_ore", "block_loot",
            Map.of(), java.util.Set.of("minecraft:coal"), Map.of());
        var lootAnalyzer = LootTableAcquisitionAnalyzer.fromParsedTables(
            List.of(stoneTable, oreTable), 1.0D, 100.0D);
        var source = new WorldgenAcquisitionAnalyzer.WorldgenBlockSource("test:biome/test:feature",
            "test:biome", "test:feature",
            Map.of("configured_cluster_size", AcquisitionMeasurement.known(8.0D)),
            Map.of("biome_restriction", "test:biome"));
        var analyzer = WorldgenAcquisitionAnalyzer.fromResolvedBlockSources(Map.of(
            "minecraft:stone", List.of(source),
            "minecraft:coal_ore", List.of(source)
        ), Map.of(
            "minecraft:stone", new WorldgenAcquisitionAnalyzer.BlockExtraction(
                "minecraft:stone", "minecraft:blocks/stone"),
            "minecraft:coal_ore", new WorldgenAcquisitionAnalyzer.BlockExtraction(
                "minecraft:coal_ore", "minecraft:blocks/coal_ore")
        ), lootAnalyzer);

        AcquisitionPath stone = analyzer.analyze("minecraft:stone").getFirst();
        assertEquals("worldgen_feature", stone.sourceType());
        assertEquals("minecraft:blocks/stone", stone.evidence().attributes().get("block_loot_table"));
        assertEquals("break the generated block and evaluate its block loot table",
            stone.evidence().attributes().get("extraction_operation"));
        assertEquals(1.0D, stone.evidence().measurement("expected_units_per_attempt").value());
        assertEquals(EstimateKind.ANALYTICAL,
            stone.evidence().measurement("expected_units_per_attempt").estimateKind().orElseThrow());
        assertEquals("item per block-break loot invocation",
            stone.evidence().attributes().get("canonical_quantity_unit"));
        assertTrue(stone.costsByHorizon().get(100).factors().get("quantity_cost").isKnown());
        EconomicFactor probabilityBurden = stone.costsByHorizon().get(100)
            .factor(EconomicChannel.PROBABILITY_BURDEN);
        assertTrue(probabilityBurden.isUnknown(),
            "worldgen source availability has no comparable opportunity unit or bounded normalization");
        assertFalse(stone.costsByHorizon().get(100).factors().containsKey("time_cost"),
            "operation duration remains diagnostic evidence, not an economic channel");
        assertTrue(stone.costsByHorizon().get(100).factors().get("equipment_cost").isUnknown());
        assertTrue(!stone.economicCost().isKnown());

        AcquisitionPath coal = analyzer.analyze("minecraft:coal").getFirst();
        assertEquals("minecraft:blocks/coal_ore", coal.evidence().attributes().get("block_loot_table"));
        assertEquals("break the generated block and evaluate its block loot table",
            coal.evidence().attributes().get("extraction_operation"));
        assertTrue(!coal.evidence().measurement("expected_units_per_attempt").isKnown());
        assertEquals(EstimateKind.UNKNOWN,
            coal.evidence().measurement("expected_units_per_attempt").estimateKind().orElseThrow());
        assertTrue(coal.costsByHorizon().get(100).factors().get("quantity_cost").isUnknown());
        assertTrue(!coal.economicCost().isKnown());
        assertEquals("UNKNOWN", coal.evidence().attributes().get("source_availability_classification"));
        assertTrue(stone.evidence().worldgenCausalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.EXTRACTED_BY));
        assertTrue(stone.evidence().worldgenCausalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.USES_LOOT_TABLE));
        assertTrue(stone.evidence().worldgenCausalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.YIELDS_ITEM
                && relationship.to().identifier().equals("minecraft:stone")));
        assertTrue(stone.evidence().worldgenCausalEvidence().nodes().stream().anyMatch(node ->
            node.ref().type() == WorldgenCausalEvidence.NodeType.EXTRACTION_OPERATION
                && node.rawEvidence().contains("requires_correct_tool=UNKNOWN")));
        assertTrue(stone.evidence().worldgenCausalEvidence().nodes().stream().anyMatch(node ->
            node.ref().type() == WorldgenCausalEvidence.NodeType.LOOT_TABLE
                && node.sourceResource().equals("minecraft:loot_table/blocks/stone.json")));
    }

    @Test
    void activePositiveOakTreePathLinksOrdinaryBreakEvidenceWithoutResolvingQuantity() {
        var oakTable = new LootTableAcquisitionAnalyzer.ParsedTable("minecraft:blocks/oak_log",
            "block_loot", Map.of(), java.util.Set.of("minecraft:oak_log"), Map.of(),
            java.util.Set.of("minecraft:oak_log"));
        assertTrue(oakTable.unknownItems().contains("minecraft:oak_log"),
            "survival evidence must not resolve the conditional loot quantity");
        assertTrue(oakTable.ordinaryPlayerBreakOutputs().contains("minecraft:oak_log"));
        var unsupportedTable = new LootTableAcquisitionAnalyzer.ParsedTable("minecraft:blocks/oak_log",
            "block_loot", Map.of(), java.util.Set.of("minecraft:oak_log"), Map.of());
        assertTrue(!unsupportedTable.ordinaryPlayerBreakOutputs().contains("minecraft:oak_log"),
            "unsupported break conditions must not establish ordinary player drops");

        var lootAnalyzer = LootTableAcquisitionAnalyzer.fromParsedTables(
            List.of(oakTable), 1.0D, 100.0D);
        Map<String, JsonObject> worldgenResources = Map.of(
            "testmod:worldgen/biome/forest.json", json("""
                {"features":[["testmod:trees"]]}
                """),
            "testmod:worldgen/placed_feature/trees.json", json("""
                {"feature":"testmod:trees_selector","placement":[
                  {"type":"minecraft:count","count":{"type":"minecraft:weighted_list","distribution":[
                    {"data":0,"weight":19},{"data":1,"weight":1}
                  ]}},
                  {"type":"minecraft:in_square"},
                  {"type":"minecraft:surface_water_depth_filter","max_water_depth":0},
                  {"type":"minecraft:heightmap","heightmap":"OCEAN_FLOOR"},
                  {"type":"minecraft:block_predicate_filter","predicate":{
                    "type":"minecraft:would_survive",
                    "state":{"Name":"minecraft:oak_sapling","Properties":{"stage":"0"}}
                  }},
                  {"type":"minecraft:biome"}
                ]}
                """),
            "testmod:worldgen/configured_feature/trees_selector.json", json("""
                {"type":"minecraft:random_selector","config":{
                  "default":{"feature":"testmod:oak_tree","placement":[]},
                  "features":[{"chance":0.33333334,
                    "feature":{"feature":"testmod:fancy_oak_tree","placement":[]}}]
                }}
                """),
            "testmod:worldgen/configured_feature/oak_tree.json", json("""
                {"type":"minecraft:tree","config":{"trunk_provider":{
                  "type":"minecraft:simple_state_provider","state":{"Name":"minecraft:oak_log"}
                }}}
                """),
            "testmod:worldgen/configured_feature/fancy_oak_tree.json", json("""
                {"type":"minecraft:tree","config":{"trunk_provider":{
                  "type":"minecraft:simple_state_provider","state":{"Name":"minecraft:oak_log"}
                }}}
                """));
        var discoveredSources = WorldgenAcquisitionAnalyzer.discoverBlockSources(worldgenResources);
        var worldgen = WorldgenAcquisitionAnalyzer.fromResolvedBlockSources(discoveredSources,
            Map.of("minecraft:oak_log", new WorldgenAcquisitionAnalyzer.BlockExtraction(
                "minecraft:oak_log", "minecraft:blocks/oak_log", false)), lootAnalyzer)
            .withActiveDimensionBiomes(Map.of("minecraft:overworld", java.util.Set.of("testmod:forest")));
        AcquisitionPath discovered = worldgen.analyze("minecraft:oak_log").getFirst();
        assertEquals("TRUE", discovered.evidence().attributes()
            .get("configured_feature_generation_opportunity"));
        assertEquals("minecraft:overworld", discovered.evidence().attributes().get("active_dimension"));
        assertEquals("TRUE", discovered.evidence().attributes()
            .get("ordinary_player_break_output_evidence"));
        assertEquals("minecraft:oak_log", discovered.evidence().attributes().get("worldgen_block_item_id"));
        assertEquals("false", discovered.evidence().attributes().get("worldgen_requires_correct_tool"));
        assertTrue(discovered.feasibilityFactors().get("equipment_availability").isNotApplicable());
        assertTrue(discovered.costsByHorizon().get(100).factors().get("equipment_cost").isNotApplicable());
        assertTrue(discovered.costsByHorizon().get(100).factor(EconomicChannel.PROBABILITY_BURDEN).isUnknown());
        assertTrue(!discovered.evidence().measurement("expected_units_per_attempt").isKnown(),
            "the conditional loot yield stays UNKNOWN even when ordinary-break availability is proven");

        var toolRequiredWorldgen = WorldgenAcquisitionAnalyzer.fromResolvedBlockSources(discoveredSources,
            Map.of("minecraft:oak_log", new WorldgenAcquisitionAnalyzer.BlockExtraction(
                "minecraft:oak_log", "minecraft:blocks/oak_log", true)), lootAnalyzer);
        AcquisitionPath toolRequired = toolRequiredWorldgen.analyze("minecraft:oak_log").getFirst();
        assertTrue(toolRequired.feasibilityFactors().get("equipment_availability").isUnknown());
        assertTrue(toolRequired.costsByHorizon().get(100).factors().get("equipment_cost").isUnknown());

        Map<String, List<AcquisitionPath>> classified = new SurvivalAcquirabilityResolver()
            .resolvePathAvailability(Map.of("minecraft:oak_log", List.of(discovered)));
        assertEquals("TRUE", classified.get("minecraft:oak_log").getFirst().evidence().attributes()
            .get("source_availability_classification"));
        assertEquals("WORLDGEN_EXTRACTION", classified.get("minecraft:oak_log").getFirst().evidence().attributes()
            .get("source_availability_stage"));

        Map<String, JsonObject> zeroChanceResources = new java.util.HashMap<>(worldgenResources);
        zeroChanceResources.put("testmod:worldgen/configured_feature/trees_selector.json", json("""
            {"type":"minecraft:random_selector","config":{
              "default":{"feature":"testmod:birch_tree","placement":[]},
              "features":[{"chance":0.0,
                "feature":{"feature":"testmod:fancy_oak_tree","placement":[]}}]
            }}
            """));
        zeroChanceResources.put("testmod:worldgen/configured_feature/birch_tree.json", json("""
            {"type":"minecraft:tree","config":{"trunk_provider":{
              "type":"minecraft:simple_state_provider","state":{"Name":"minecraft:birch_log"}
            }}}
            """));
        var zeroChanceSources = WorldgenAcquisitionAnalyzer.discoverBlockSources(zeroChanceResources);
        assertTrue(zeroChanceSources.containsKey("minecraft:oak_log"),
            "the selector's structural branch must remain represented: " + zeroChanceSources.keySet());
        var zeroChanceWorldgen = WorldgenAcquisitionAnalyzer.fromResolvedBlockSources(
            zeroChanceSources,
            Map.of("minecraft:oak_log", new WorldgenAcquisitionAnalyzer.BlockExtraction(
                "minecraft:oak_log", "minecraft:blocks/oak_log", false)), lootAnalyzer)
            .withActiveDimensionBiomes(Map.of("minecraft:overworld", java.util.Set.of("testmod:forest")));
        AcquisitionPath zeroChanceOak = zeroChanceWorldgen.analyze("minecraft:oak_log").getFirst();
        assertEquals("UNKNOWN", zeroChanceOak.evidence().attributes()
            .get("configured_feature_generation_opportunity"),
            "discovery of an impossible selector branch must remain UNKNOWN, not become a positive opportunity");
        Map<String, List<AcquisitionPath>> zeroChanceClassification = new SurvivalAcquirabilityResolver()
            .resolvePathAvailability(Map.of("minecraft:oak_log", List.of(zeroChanceOak)));
        assertEquals("UNKNOWN", zeroChanceClassification.get("minecraft:oak_log").getFirst()
            .evidence().attributes().get("source_availability_classification"));
    }

    @Test
    void lootEquipmentAvailabilityUsesOnlyUnambiguousDirectBlockBreakEvidence() {
        AcquisitionPath noCorrectTool = lootPath("test:blocks/direct", "block_loot",
            Set.of("test:drop"), Boolean.FALSE);
        AcquisitionPath correctToolRequired = lootPath("test:blocks/direct", "block_loot",
            Set.of("test:drop"), Boolean.TRUE);
        AcquisitionPath missingBlockAssociation = lootPath("test:blocks/direct", "block_loot",
            Set.of("test:drop"), null);
        Boolean ambiguousRequirement = LootTableAcquisitionAnalyzer.uniqueCorrectToolRequirement(
            List.of(false, true));
        assertEquals(null, ambiguousRequirement);
        AcquisitionPath ambiguousBlockAssociation = lootPath("test:blocks/direct", "block_loot",
            Set.of("test:drop"), ambiguousRequirement);

        assertTrue(noCorrectTool.feasibilityFactors().get("equipment_availability").isNotApplicable());
        assertTrue(correctToolRequired.feasibilityFactors().get("equipment_availability").isUnknown());
        assertTrue(missingBlockAssociation.feasibilityFactors().get("equipment_availability").isUnknown());
        assertTrue(ambiguousBlockAssociation.feasibilityFactors().get("equipment_availability").isUnknown());

        Map<String, EconomicFactor> remainingFactors =
            new java.util.HashMap<>(noCorrectTool.feasibilityFactors());
        remainingFactors.remove("equipment_availability");
        Map<String, EconomicFactor> requiredToolRemainingFactors =
            new java.util.HashMap<>(correctToolRequired.feasibilityFactors());
        requiredToolRemainingFactors.remove("equipment_availability");
        assertEquals(requiredToolRemainingFactors, remainingFactors);
        assertEquals("UNKNOWN",
            noCorrectTool.evidence().attributes().get("source_availability_classification"));
        assertEquals(1.0D,
            noCorrectTool.evidence().measurement("expected_units_per_attempt").value());

        CostVector directDropCost = noCorrectTool.costsByHorizon().get(100);
        assertTrue(directDropCost.factor(EconomicChannel.QUANTITY).isKnown());
        assertTrue(directDropCost.factor(EconomicChannel.PROBABILITY_BURDEN).isNotApplicable());
        assertTrue(directDropCost.factor(EconomicChannel.MATERIAL_CONSUMPTION).isNotApplicable());
        assertTrue(directDropCost.factor(EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN).isNotApplicable());
        CostVector requiredToolCost = correctToolRequired.costsByHorizon().get(100);
        assertTrue(requiredToolCost.factor(EconomicChannel.QUANTITY).isKnown());
        assertTrue(requiredToolCost.factor(EconomicChannel.PROBABILITY_BURDEN).isNotApplicable());
        assertTrue(requiredToolCost.factor(EconomicChannel.MATERIAL_CONSUMPTION).isNotApplicable());
        assertTrue(requiredToolCost.factor(EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN).isUnknown());
        assertEquals(noCorrectTool.evidence().measurement("probability"),
            correctToolRequired.evidence().measurement("probability"));

        for (String unsupportedTable : List.of(
            "test:blocks/conditional", "test:blocks/alternative", "test:blocks/unsupported")) {
            AcquisitionPath unsupported = lootPath(unsupportedTable, "block_loot", Set.of(), Boolean.FALSE);
            assertTrue(unsupported.feasibilityFactors().get("equipment_availability").isUnknown(),
                unsupportedTable);
        }
        AcquisitionPath unrelatedSource = lootPath("test:entities/direct", "mob_drop",
            Set.of("test:drop"), Boolean.FALSE);
        assertTrue(unrelatedSource.feasibilityFactors().get("equipment_availability").isUnknown());
    }

    @Test
    void cropCycleSeparatesMeasuredRenewalFromUnknownLootOrGrowthEvidence() {
        var fullReturn = CropCycleResolver.resolve(AcquisitionMeasurement.known(1.0D),
            AcquisitionMeasurement.known(1.0D), AcquisitionMeasurement.known(600.0D));
        assertEquals(CropCycleResolver.RenewalState.SELF_RENEWING, fullReturn.renewalState());
        assertEquals(0.0D, fullReturn.externalSeedsRequiredPerCycle().value());
        assertEquals(600.0D, fullReturn.growthTimeTicks().value());

        var noReturn = CropCycleResolver.resolve(AcquisitionMeasurement.known(0.0D),
            AcquisitionMeasurement.known(1.0D), AcquisitionMeasurement.unknown("growth is unmeasured"));
        assertEquals(CropCycleResolver.RenewalState.CONSUMPTIVE, noReturn.renewalState());
        assertEquals(1.0D, noReturn.externalSeedsRequiredPerCycle().value());
        assertTrue(!noReturn.growthTimeTicks().isKnown());

        var partialReturn = CropCycleResolver.resolve(AcquisitionMeasurement.known(0.5D),
            AcquisitionMeasurement.known(1.0D), AcquisitionMeasurement.unknown("growth is unmeasured"));
        assertEquals(CropCycleResolver.RenewalState.PARTIALLY_RENEWING, partialReturn.renewalState());
        assertEquals(0.5D, partialReturn.externalSeedsRequiredPerCycle().value());

        var unresolvedReturn = CropCycleResolver.resolve(AcquisitionMeasurement.unknown("loot is unresolved"),
            AcquisitionMeasurement.known(1.0D), AcquisitionMeasurement.unknown("growth is unmeasured"));
        assertEquals(CropCycleResolver.RenewalState.UNKNOWN, unresolvedReturn.renewalState());
        assertTrue(!unresolvedReturn.externalSeedsRequiredPerCycle().isKnown());
        assertTrue(!unresolvedReturn.growthTimeTicks().isKnown());
    }

    @Test
    void recipeAlternativesAreOrAndRecursiveChainUsesInputAndOutputQuantities() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:ingots", "minecraft:crafting", "test:ingot", 2, List.of(), null,
            List.of(new AcquisitionIngredient(List.of("test:ore"), 3))));
        graph.add(new RecipeNode("test:plates", "minecraft:crafting", "test:plate", 3, List.of(), null,
            List.of(new AcquisitionIngredient(List.of("test:ingot"), 2))));
        graph.add(new RecipeNode("test:alternative_food", "minecraft:crafting", "test:food", 2, List.of(), null,
            List.of(new AcquisitionIngredient(List.of("test:ore_a", "test:ore_b"), 2))));
        EconomicCostEvidenceProvider evidence = (itemId, horizon) -> switch (itemId) {
            case "test:ore", "test:ore_a", "test:ore_b" ->
                EconomicCost.known(0.4D, "test:resource_units", horizon, "independent test policy cost");
            default -> EconomicCost.unknown("no independent primitive evidence for " + itemId);
        };
        RecipeEconomicAnalyzer analyzer = new RecipeEconomicAnalyzer(graph, evidence);

        assertEquals(0.4D, analyzer.resolve("test:plate", 100).economicCost(), 1.0E-15D);
        assertEquals(0.4D, analyzer.resolve("test:food", 100).economicCost(), 1.0E-15D);

        RecipeEconomicAnalyzer ambiguous = new RecipeEconomicAnalyzer(graph, (itemId, horizon) ->
            itemId.equals("test:ore_a")
                ? EconomicCost.known(0.4D, "test:resource_units", horizon, "independent test policy cost")
                : itemId.equals("test:ore_b")
                    ? EconomicCost.known(0.5D, "test:resource_units", horizon, "independent test policy cost")
                    : evidence.resolve(itemId, horizon));
        assertEquals(ResolutionStatus.UNKNOWN, ambiguous.resolve("test:food", 100).status());
    }

    @Test
    void reusableRecipeInputDoesNotBecomeConsumedMaterialCost() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:tool_food", "minecraft:crafting", "test:food", 1, List.of(), null,
            List.of(
                new AcquisitionIngredient(List.of("test:ingredient"), 1),
                new AcquisitionIngredient(List.of("test:tool"), 1, AcquisitionIngredient.InputUse.REUSABLE)
            )));
        RecipeEconomicAnalyzer analyzer = new RecipeEconomicAnalyzer(graph, (itemId, horizon) ->
            itemId.equals("test:ingredient")
                ? EconomicCost.known(0.2D, "test:resource_units", horizon, "independent test policy cost")
                : EconomicCost.unknown("no independent primitive evidence for " + itemId));

        RecipeEconomicResult result = analyzer.resolve("test:food", 100);
        AcquisitionPath path = new RecipeGraphAcquisitionAnalyzer(graph, (itemId, horizon) ->
            itemId.equals("test:ingredient")
                ? EconomicCost.known(0.2D, "test:resource_units", horizon, "independent test policy cost")
                : EconomicCost.unknown("no independent primitive evidence for " + itemId),
            1.0D, 100.0D).analyze("test:food").getFirst();

        assertEquals(0.2D, result.economicCost(), 0.0D);
        assertEquals("reusable", path.evidence().attributes().get("input_1_use"));
        assertEquals("test:tool", path.evidence().attributes().get("input_1_alternatives"));
        assertTrue(path.costsByHorizon().get(100).factors().get("equipment_cost").isUnknown());
    }

    @Test
    void estimateKindAppliesToMechanicalQuantityNotEconomicCost() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:fixed_output", "minecraft:crafting", "test:food", 4,
            List.of(), null, List.of()));
        var path = new RecipeGraphAcquisitionAnalyzer(graph,
            (itemId, horizon) -> EconomicCost.unknown("no independent primitive evidence"),
            1.0D, 100.0D).analyze("test:food").getFirst();

        AcquisitionMeasurement output = path.evidence().measurement("expected_units_per_attempt");
        assertEquals(EstimateKind.EXACT, output.estimateKind().orElseThrow());
        assertEquals(4.0D, output.value());
        assertTrue(!path.economicCost().isKnown(),
            "an exact output quantity must not imply a resolved EconomicCost");

        assertEquals(EstimateKind.UNKNOWN,
            AcquisitionMeasurement.unknown("unresolved mechanic").estimateKind().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> AcquisitionMeasurement.approximated(2.0D,
            new EstimateMetadata("deterministic approximation", "test-v1", Map.of(), List.of())));
    }

    @Test
    void lootExpectedUnitsUseMutuallyExclusiveWeightsAndIndependentPools() {
        var table = json("""
            {"pools":[
              {"rolls":1,"entries":[{"type":"minecraft:item","name":"test:fish"}]},
              {"rolls":2,"entries":[
                {"type":"minecraft:item","name":"test:fish","weight":1},
                {"type":"minecraft:item","name":"test:stick","weight":3}
              ]}
            ]}
            """);
        var parsed = LootTableAcquisitionAnalyzer.parseTable("test:gameplay/fishing", table).orElseThrow();
        var analyzer = LootTableAcquisitionAnalyzer.fromParsedTables(List.of(parsed), 1.0D, 100.0D);
        var fishPath = analyzer.analyze("test:fish").getFirst();
        var stickPath = analyzer.analyze("test:stick").getFirst();

        assertEquals(1.5D, fishPath.evidence().measurement("expected_units_per_attempt").value(), 1.0E-12D);
        assertEquals(EstimateKind.ANALYTICAL,
            fishPath.evidence().measurement("expected_units_per_attempt").estimateKind().orElseThrow());
        assertEquals("supported_minecraft_loot_expected_value",
            fishPath.evidence().measurement("expected_units_per_attempt").estimateMetadata().orElseThrow().method());
        assertEquals(1.5D, stickPath.evidence().measurement("expected_units_per_attempt").value(), 1.0E-12D);
        assertEquals("item per loot-table invocation",
            fishPath.evidence().attributes().get("canonical_quantity_unit"));
        assertTrue(fishPath.evidence().attributes().get("canonical_quantity_semantics").startsWith("expected"));
        assertTrue(fishPath.feasibilityFactors().get("probability").isNotApplicable());
        assertTrue(fishPath.feasibilityFactors().get("expected_yield").isNotApplicable());
    }

    @Test
    void ordinaryDirectBlockDropWithoutRequiredToolHasCompleteCoreVector() {
        var table = new LootTableAcquisitionAnalyzer.ParsedTable("test:blocks/direct_drop", "block_loot",
            Map.of("test:direct_drop", 1.0D), java.util.Set.of(),
            Map.of("test:direct_drop", new LootTableAcquisitionAnalyzer.LootEvidence(1.0D, 1.0D)),
            java.util.Set.of("test:direct_drop"), false);
        AcquisitionPath path = LootTableAcquisitionAnalyzer.fromParsedTables(List.of(table), 1.0D, 100.0D)
            .analyze("test:direct_drop").getFirst();
        CostVector vector = path.costsByHorizon().get(100);

        assertTrue(vector.factor(EconomicChannel.QUANTITY).isKnown());
        assertEquals(1.0D, path.evidence().measurement("expected_units_per_attempt").value());
        assertTrue(vector.factor(EconomicChannel.PROBABILITY_BURDEN).isNotApplicable());
        assertTrue(vector.factor(EconomicChannel.MATERIAL_CONSUMPTION).isNotApplicable());
        assertTrue(vector.factor(EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN).isNotApplicable());
        assertTrue(vector.isCoreComplete());
    }

    @Test
    void ordinaryDirectBlockDropWithRequiredToolKeepsEquipmentCostUnknown() {
        var table = new LootTableAcquisitionAnalyzer.ParsedTable("test:blocks/tool_required", "block_loot",
            Map.of("test:tool_required", 2.0D), java.util.Set.of(),
            Map.of("test:tool_required", new LootTableAcquisitionAnalyzer.LootEvidence(1.0D, 2.0D)),
            java.util.Set.of("test:tool_required"), true);
        AcquisitionPath path = LootTableAcquisitionAnalyzer.fromParsedTables(List.of(table), 1.0D, 100.0D)
            .analyze("test:tool_required").getFirst();
        CostVector vector = path.costsByHorizon().get(100);

        assertTrue(vector.factor(EconomicChannel.QUANTITY).isKnown());
        assertEquals(2.0D, path.evidence().measurement("expected_units_per_attempt").value());
        assertTrue(vector.factor(EconomicChannel.MATERIAL_CONSUMPTION).isNotApplicable());
        assertTrue(vector.factor(EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN).isUnknown());
        assertFalse(vector.isCoreComplete());
    }

    @Test
    void ambiguousOrUnavailableBlockToolRequirementRemainsUnknown() {
        assertEquals(Boolean.FALSE,
            LootTableAcquisitionAnalyzer.uniqueCorrectToolRequirement(List.of(false, false)));
        assertEquals(Boolean.TRUE, LootTableAcquisitionAnalyzer.uniqueCorrectToolRequirement(List.of(true)));
        assertTrue(LootTableAcquisitionAnalyzer.uniqueCorrectToolRequirement(List.of(false, true)) == null);
        assertTrue(LootTableAcquisitionAnalyzer.uniqueCorrectToolRequirement(
            java.util.Arrays.asList(null, false)) == null);
        assertTrue(LootTableAcquisitionAnalyzer.uniqueCorrectToolRequirement(List.of()) == null);

        var table = new LootTableAcquisitionAnalyzer.ParsedTable("test:blocks/unknown_tool", "block_loot",
            Map.of("test:unknown_tool", 1.0D), java.util.Set.of(),
            Map.of("test:unknown_tool", new LootTableAcquisitionAnalyzer.LootEvidence(1.0D, 1.0D)),
            java.util.Set.of("test:unknown_tool"));
        AcquisitionPath path = LootTableAcquisitionAnalyzer.fromParsedTables(List.of(table), 1.0D, 100.0D)
            .analyze("test:unknown_tool").getFirst();
        CostVector vector = path.costsByHorizon().get(100);

        assertTrue(vector.factor(EconomicChannel.MATERIAL_CONSUMPTION).isNotApplicable());
        assertTrue(vector.factor(EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN).isUnknown());
        assertFalse(vector.isCoreComplete());
    }

    @Test
    void conditionalNonDirectLootDoesNotReceiveBlockBreakApplicability() {
        var parsed = LootTableAcquisitionAnalyzer.parseTable("test:gameplay/conditional_block_drop", json("""
            {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"test:conditional_drop",
              "functions":[{"function":"minecraft:set_count","count":{"min":1,"max":3}}]}]}]}
            """)).orElseThrow();
        AcquisitionPath path = LootTableAcquisitionAnalyzer.fromParsedTables(List.of(parsed), 1.0D, 100.0D)
            .analyze("test:conditional_drop").getFirst();
        CostVector vector = path.costsByHorizon().get(100);

        assertTrue(parsed.ordinaryPlayerBreakOutputs().isEmpty());
        assertTrue(vector.factor(EconomicChannel.QUANTITY).isUnknown());
        assertTrue(vector.factor(EconomicChannel.MATERIAL_CONSUMPTION).isUnknown());
        assertTrue(vector.factor(EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN).isUnknown());
        assertTrue(path.feasibilityFactors().get("equipment_availability").isUnknown());
    }

    @Test
    void vanillaAlternativesLootPreservesCandidateOutputsWithUnknownYield() {
        var table = json("""
            {"type":"minecraft:block","pools":[{"rolls":1,"entries":[
              {"type":"minecraft:alternatives","children":[
                {"type":"minecraft:item","name":"minecraft:amethyst_cluster",
                  "conditions":[{"condition":"minecraft:match_tool"}]},
                {"type":"minecraft:alternatives","children":[
                  {"type":"minecraft:item","name":"minecraft:amethyst_shard",
                    "functions":[{"function":"minecraft:set_count","count":4}]},
                  {"type":"minecraft:item","name":"minecraft:amethyst_shard",
                    "functions":[{"function":"minecraft:set_count","count":2}]}
                ]}
              ]}
            ]}],"random_sequence":"minecraft:blocks/amethyst_cluster"}
            """);
        JsonObject alternativesEntry = table.getAsJsonArray("pools").get(0).getAsJsonObject()
            .getAsJsonArray("entries").get(0).getAsJsonObject();
        assertTrue(LootTableAcquisitionAnalyzer.ordinaryBlockItemOutput(alternativesEntry) == null);
        assertTrue(LootTableAcquisitionAnalyzer.ordinaryBlockItemOutput(json("""
            {"type":"minecraft:item"}
            """)) == null);

        var parsed = LootTableAcquisitionAnalyzer.parseTable("test:gameplay/amethyst_cluster", table)
            .orElseThrow();
        assertTrue(parsed.expectedUnitsByItem().isEmpty());
        assertTrue(parsed.unknownItems().containsAll(List.of(
            "minecraft:amethyst_cluster", "minecraft:amethyst_shard")));
        assertTrue(parsed.ordinaryPlayerBreakOutputs().isEmpty(),
            "an alternatives entry is not a direct ordinary block-break drop");

        var analyzer = LootTableAcquisitionAnalyzer.fromParsedTables(List.of(parsed), 1.0D, 100.0D);
        for (String candidate : List.of("minecraft:amethyst_cluster", "minecraft:amethyst_shard")) {
            var path = analyzer.analyze(candidate).getFirst();
            var expectedUnits = path.evidence().measurement("expected_units_per_attempt");
            assertFalse(expectedUnits.isKnown());
            assertEquals(EstimateKind.UNKNOWN, expectedUnits.estimateKind().orElseThrow());
            assertTrue(expectedUnits.unknownReason().contains("unsupported or conditional loot semantics"));
            assertEquals("test:gameplay/amethyst_cluster",
                path.evidence().attributes().get("loot_table"));
            assertEquals("YIELD_UNKNOWN",
                path.evidence().attributes().get("loot_analysis_category"));
            assertTrue(path.evidence().attributes().get("loot_analysis_reason")
                .contains("expected-yield calculation"));
            assertTrue(path.feasibilityFactors().get("equipment_availability").isUnknown());
        }
    }

    @Test
    void lootOutputEvidenceDistinguishesSupportedPositiveOutputFromUnknownCandidates() {
        var supported = LootTableAcquisitionAnalyzer.parseTable("test:chests/supported_output", json("""
            {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"test:known_output"}]}]}
            """)).orElseThrow();
        var positiveCount = LootTableAcquisitionAnalyzer.parseTable("test:chests/positive_count", json("""
            {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"test:unknown_output",
              "functions":[{"function":"minecraft:set_count","count":{"min":1,"max":3}}]}]}]}
            """)).orElseThrow();
        var zeroCount = LootTableAcquisitionAnalyzer.parseTable("test:chests/zero_count", json("""
            {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"test:zero_output",
              "functions":[{"function":"minecraft:set_count","count":0}]}]}]}
            """)).orElseThrow();
        var unresolvedCondition = LootTableAcquisitionAnalyzer.parseTable(
            "test:chests/unresolved_condition", json("""
                {"pools":[{"rolls":1,"conditions":[{"condition":"minecraft:match_tool","predicate":{}}],
                  "entries":[{"type":"minecraft:item","name":"test:conditional_output",
                    "functions":[{"function":"minecraft:set_count","count":{"min":1,"max":3}}]}]}]}
                """)).orElseThrow();
        var unsupportedFunction = LootTableAcquisitionAnalyzer.parseTable(
            "test:chests/unsupported_function", json("""
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"test:function_output",
                  "functions":[{"function":"minecraft:set_damage","damage":0.5}]}]}]}
                """)).orElseThrow();
        var unresolvedRoute = LootTableAcquisitionAnalyzer.parseTable("test:chests/unresolved_route", json("""
            {"pools":[{"rolls":1,"entries":[
              {"type":"minecraft:item","name":"test:reachable_candidate",
                "functions":[{"function":"minecraft:set_count","count":{"min":1,"max":3}}]},
              {"type":"minecraft:item","name":"test:other_candidate"}
            ]}]}
            """)).orElseThrow();
        var analyzer = LootTableAcquisitionAnalyzer.fromParsedTables(
            List.of(supported, positiveCount, zeroCount, unresolvedCondition,
                unsupportedFunction, unresolvedRoute),
            1.0D, 100.0D);
        AcquisitionPath knownPath = analyzer.analyze("test:known_output").getFirst();
        AcquisitionPath positiveCountPath = analyzer.analyze("test:unknown_output").getFirst();

        assertEquals("PROVEN_POSSIBLE",
            knownPath.evidence().attributes().get("loot_output_evidence_status"));
        assertEquals("PROVEN_POSSIBLE",
            positiveCountPath.evidence().attributes().get("loot_output_evidence_status"));
        assertTrue(positiveCountPath.evidence().attributes().get("loot_output_evidence_reason")
            .contains("expected quantity remains unresolved"));
        assertTrue(knownPath.evidence().measurement("expected_units_per_attempt").isKnown());
        assertFalse(positiveCountPath.evidence().measurement("expected_units_per_attempt").isKnown());
        assertTrue(knownPath.costsByHorizon().get(100).factor(EconomicChannel.QUANTITY).isKnown());
        assertTrue(positiveCountPath.costsByHorizon().get(100)
            .factor(EconomicChannel.QUANTITY).isUnknown());
        assertTrue(knownPath.costsByHorizon().get(100)
            .factor(EconomicChannel.PROBABILITY_BURDEN).isNotApplicable());
        assertTrue(positiveCountPath.costsByHorizon().get(100)
            .factor(EconomicChannel.PROBABILITY_BURDEN).isNotApplicable());
        assertEquals(knownPath.feasibilityFactors().get("equipment_availability"),
            positiveCountPath.feasibilityFactors().get("equipment_availability"));
        for (String itemId : List.of("test:zero_output", "test:conditional_output", "test:function_output",
            "test:reachable_candidate", "test:other_candidate")) {
            AcquisitionPath candidate = analyzer.analyze(itemId).getFirst();
            assertEquals("UNKNOWN_CANDIDATE",
                candidate.evidence().attributes().get("loot_output_evidence_status"), itemId);
            assertFalse(candidate.evidence().measurement("expected_units_per_attempt").isKnown(), itemId);
        }
    }

    @Test
    void malformedLootFieldsRetainRecoverableCandidatesAndDoNotPoisonOtherTables() {
        var malformed = json("""
            {"pools":[{"entries":[
              {"type":"minecraft:item"},
              {"type":"minecraft:item","name":"test:conditional_output","functions":"not_an_array"}
            ]}]}
            """);
        var malformedParsed = LootTableAcquisitionAnalyzer.parseTable("test:gameplay/malformed", malformed)
            .orElseThrow();
        assertTrue(malformedParsed.expectedUnitsByItem().isEmpty());
        assertEquals(java.util.Set.of("test:conditional_output"), malformedParsed.unknownItems());

        var independent = LootTableAcquisitionAnalyzer.parseTable("test:gameplay/independent", json("""
            {"pools":[{"rolls":2,"entries":[{"type":"minecraft:item","name":"test:known_output"}]}]}
            """)).orElseThrow();
        var analyzer = LootTableAcquisitionAnalyzer.fromParsedTables(
            List.of(malformedParsed, independent), 1.0D, 100.0D);

        assertTrue(analyzer.supports("test:conditional_output"));
        assertTrue(analyzer.supports("test:known_output"));
        assertEquals(2.0D, analyzer.analyze("test:known_output").getFirst()
            .evidence().measurement("expected_units_per_attempt").value());
        assertEquals(EstimateKind.UNKNOWN, analyzer.analyze("test:conditional_output").getFirst()
            .evidence().measurement("expected_units_per_attempt").estimateKind().orElseThrow());
    }

    @Test
    void lootFunctionsAndBonusRollsPreserveOutputsButKeepYieldUnknown() {
        var randomCount = json("""
            {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"test:crop",
              "functions":[{"function":"minecraft:set_count","count":{"min":1,"max":3}}]}]}]}
            """);
        var bonusRolls = json("""
            {"pools":[{"rolls":1,"bonus_rolls":1,
              "entries":[{"type":"minecraft:item","name":"test:bonus_drop"}]}]}
            """);
        var parsedFunction = LootTableAcquisitionAnalyzer.parseTable("test:entities/mob", randomCount)
            .orElseThrow();
        var parsedBonus = LootTableAcquisitionAnalyzer.parseTable("test:entities/bonus", bonusRolls)
            .orElseThrow();
        var analyzer = LootTableAcquisitionAnalyzer.fromParsedTables(List.of(parsedFunction, parsedBonus),
            1.0D, 100.0D);

        for (String itemId : List.of("test:crop", "test:bonus_drop")) {
            var path = analyzer.analyze(itemId).getFirst();
            assertTrue(path.costsByHorizon().get(100).factors().get("quantity_cost").isUnknown());
            assertTrue(!path.evidence().measurement("expected_units_per_attempt").isKnown());
            assertEquals(EstimateKind.UNKNOWN,
                path.evidence().measurement("expected_units_per_attempt").estimateKind().orElseThrow());
            assertEquals(itemId.equals("test:crop") ? "PROVEN_POSSIBLE" : "UNKNOWN_CANDIDATE",
                path.evidence().attributes().get("loot_output_evidence_status"));
        }
    }

    @Test
    void providerRegistryResolvesMechanicsDeterministicallyAndOrderIndependently() {
        MechanicProvider first = new MechanicProvider() {
            @Override
            public String providerId() {
                return "provider-a";
            }

            @Override
            public java.util.Collection<ProviderContribution> contributions() {
                return List.of(
                    new ProviderContribution("provider-a", "test:oak_log", ProviderContributionOperation.ADD,
                        "worldgen", "minecraft:oak_log", Map.of("state", "minecraft:oak_log"), Map.of("source", "first")),
                    new ProviderContribution("provider-a", "test:oak_log", ProviderContributionOperation.MODIFY,
                        "worldgen", "minecraft:oak_log", Map.of("tool_required", "false"), Map.of("source", "first"))
                );
            }
        };
        MechanicProvider second = new MechanicProvider() {
            @Override
            public String providerId() {
                return "provider-b";
            }

            @Override
            public java.util.Collection<ProviderContribution> contributions() {
                return List.of(
                    new ProviderContribution("provider-b", "test:oak_log", ProviderContributionOperation.ADD,
                        "worldgen", "minecraft:oak_log", Map.of("state", "minecraft:oak_log"), Map.of("source", "second"))
                );
            }
        };

        ProviderContributionRegistry registry = new ProviderContributionRegistry();
        registry.register(second);
        registry.register(first);

        EffectiveMechanicModel model = registry.resolve();
        assertEquals(Map.of("state", "minecraft:oak_log", "tool_required", "false"), model.mechanic("test:oak_log"));
        assertTrue(model.conflicts().isEmpty());
    }

    @Test
    void pathIdentityCanonicalizesEquivalentMechanicalPaths() {
        AcquisitionPath first = new AcquisitionPath("minecraft:oak_log", "worldgen_feature", "minecraft:oak_log",
            1.0D, null, null, true, false, Map.of(), Map.of());
        AcquisitionPath second = new AcquisitionPath("minecraft:oak_log", "worldgen_feature", "minecraft:oak_log",
            1.0D, null, null, true, false, Map.of(), Map.of());
        var deduped = AcquisitionPathDeduplicator.deduplicate(List.of(first, second));

        assertEquals(1, deduped.size());
        assertEquals(PathIdentity.fromMechanicalIdentity(
                "minecraft:oak_log", "worldgen_feature", "minecraft:oak_log",
                AcquisitionRequirements.empty(), null, "default").canonicalKey(),
            PathIdentity.fromMechanicalIdentity(
                "minecraft:oak_log", "worldgen_feature", "minecraft:oak_log",
                AcquisitionRequirements.empty(), null, "default").canonicalKey());
    }

    @Test
    void pathIdentityDoesNotDependOnEconomicEvaluationOrConfidence() {
        PathIdentity identity = PathIdentity.fromMechanicalIdentity("test:item", "test:source", "test:path",
            AcquisitionRequirements.empty(), null, "cycle-a", "condition-a", "operation-a");
        AcquisitionPath first = identityPath(identity, 0.9D, schedule(0.1D));
        AcquisitionPath second = identityPath(identity, 0.4D, schedule(0.2D));

        assertEquals(first.pathIdentity(), second.pathIdentity());
        assertEquals(1, AcquisitionPathDeduplicator.deduplicate(List.of(first, second)).size());
    }

    @Test
    void pathIdentityDoesNotDependOnDownstreamRiskEvaluation() {
        PathIdentity identity = PathIdentity.fromMechanicalIdentity("test:item", "test:source", "test:path",
            AcquisitionRequirements.empty(), null, "cycle-a", "condition-a", "operation-a");
        AcquisitionPath lowerRisk = identityPath(identity, 1.0D, null, 0.2D);
        AcquisitionPath higherRisk = identityPath(identity, 1.0D, null, 0.8D);

        assertEquals(lowerRisk.pathIdentity(), higherRisk.pathIdentity());
        AcquisitionPath merged = AcquisitionPathDeduplicator.deduplicate(List.of(lowerRisk, higherRisk)).getFirst();
        assertEquals("CONFLICT", merged.evidence().attributes().get("acquisition_path_deduplication_status"));
        assertEquals("[\"0x1.999999999999ap-1\",\"0x1.999999999999ap-3\"]",
            merged.evidence().attributes().get("deduplication.risk_values"));
        assertTrue(merged.risk() == null);
    }

    @Test
    void pathIdentityPreservesRequirementsSatisfiersCycleConditionsAndOperationInputs() {
        AcquisitionRequirements requirementA = new AcquisitionRequirements(
            List.of(new RequirementExpression.Atom("tool:diamond", "diamond tool")));
        AcquisitionRequirements requirementB = new AcquisitionRequirements(
            List.of(new RequirementExpression.Atom("tool:iron", "iron tool")));
        CapabilitySatisfaction satisfactionA =
            new CapabilitySatisfaction("tool:diamond", "item:diamond_pickaxe", "tool", Map.of());
        CapabilitySatisfaction satisfactionB =
            new CapabilitySatisfaction("tool:diamond", "item:netherite_pickaxe", "tool", Map.of());
        PathIdentity base = PathIdentity.fromMechanicalIdentity("test:item", "test:source", "test:path",
            requirementA, satisfactionA, "cycle-a", "branch-a", "operation-a");
        List<PathIdentity> identities = List.of(
            base,
            PathIdentity.fromMechanicalIdentity("test:item", "test:source", "test:path",
                requirementB, satisfactionA, "cycle-a", "branch-a", "operation-a"),
            PathIdentity.fromMechanicalIdentity("test:item", "test:source", "test:path",
                requirementA, satisfactionB, "cycle-a", "branch-a", "operation-a"),
            PathIdentity.fromMechanicalIdentity("test:item", "test:source", "test:path",
                requirementA, satisfactionA, "cycle-b", "branch-a", "operation-a"),
            PathIdentity.fromMechanicalIdentity("test:item", "test:source", "test:path",
                requirementA, satisfactionA, "cycle-a", "branch-b", "operation-a"),
            PathIdentity.fromMechanicalIdentity("test:item", "test:source", "test:path",
                requirementA, satisfactionA, "cycle-a", "branch-a", "operation-b")
        );
        List<AcquisitionPath> paths = identities.stream()
            .map(identity -> identityPath(identity, 1.0D, null)).toList();

        assertEquals(identities.size(), paths.stream().map(AcquisitionPath::pathIdentity).distinct().count());
        assertEquals(identities.size(), AcquisitionPathDeduplicator.deduplicate(paths).size());
    }

    @Test
    void pathIdentityIncludesRecipeInputMechanicsRegardlessOfInputOrder() {
        AcquisitionIngredient consumedWheat = new AcquisitionIngredient(List.of("test:wheat"), 2);
        AcquisitionIngredient reusableTool = new AcquisitionIngredient(List.of("test:tool"), 1,
            AcquisitionIngredient.InputUse.REUSABLE);
        AcquisitionPath first = new AcquisitionPath("test:item", "recipe", "test:recipe", 1.0D,
            null, null, true, false, Map.of(), Map.of(),
            new AcquisitionEvidence(Map.of(), Map.of(), List.of(consumedWheat, reusableTool)));
        AcquisitionPath reordered = new AcquisitionPath("test:item", "recipe", "test:recipe", 1.0D,
            null, null, true, false, Map.of(), Map.of(),
            new AcquisitionEvidence(Map.of(), Map.of(), List.of(reusableTool, consumedWheat)));
        AcquisitionPath differentInput = new AcquisitionPath("test:item", "recipe", "test:recipe", 1.0D,
            null, null, true, false, Map.of(), Map.of(),
            new AcquisitionEvidence(Map.of(), Map.of(), List.of(
                new AcquisitionIngredient(List.of("test:wheat"), 3), reusableTool)));

        assertEquals(first.pathIdentity(), reordered.pathIdentity());
        assertFalse(first.pathIdentity().equals(differentInput.pathIdentity()));
    }

    private static AcquisitionPath identityPath(PathIdentity identity, double confidence,
        EconomicCostSchedule schedule) {
        return identityPath(identity, confidence, schedule, null);
    }

    private static AcquisitionPath lootPath(String tableId, String sourceType,
        Set<String> ordinaryPlayerBreakOutputs, Boolean requiresCorrectTool) {
        LootTableAcquisitionAnalyzer.ParsedTable table = new LootTableAcquisitionAnalyzer.ParsedTable(
            tableId, sourceType, Map.of("test:drop", 1.0D), Set.of(),
            Map.of("test:drop", new LootTableAcquisitionAnalyzer.LootEvidence(1.0D, 1.0D)),
            ordinaryPlayerBreakOutputs, requiresCorrectTool);
        return LootTableAcquisitionAnalyzer.fromParsedTables(List.of(table), 1.0D, 100.0D)
            .analyze("test:drop").getFirst();
    }

    private static AcquisitionPath identityPath(PathIdentity identity, double confidence,
        EconomicCostSchedule schedule, Double risk) {
        return new AcquisitionPath(identity.resourceId(), identity.sourceType(), identity.sourceId(),
            confidence, null, risk, true, false, Map.of(), Map.of(), AcquisitionEvidence.empty(),
            EconomicCost.unknown("test economic evaluation"), schedule, identity);
    }

    private static EconomicCostSchedule schedule(double startup) {
        return new EconomicCostSchedule(EconomicCostComponent.known(startup, "test:primitive", "test startup"),
            EconomicCostComponent.notApplicable("test recurring"), "test schedule");
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
