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
                    {"feature":"minecraft:ore","config":{"size":8,"states":[
                      {"state":{"Name":"minecraft:coal_ore"}},
                      {"state":{"Name":"minecraft:deepslate_coal_ore"}}
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
                    {"feature":"minecraft:tree","config":{"trunk_provider":{
                      "type":"minecraft:simple_state_provider",
                      "state":{"Name":"minecraft:oak_log"}
                    }}}
                    """)),
            Map.entry("testmod:worldgen/configured_feature/tree_trunk.json", json("""
                    {"feature":"minecraft:tree","config":{"trunk_provider":{
                      "type":"minecraft:simple_state_provider",
                      "state":{"Name":"minecraft:birch_log"}
                    }}}
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
        assertTrue(discovered.containsKey("minecraft:birch_log"),
            "decorated configured-feature references must be followed to generated block states");
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
        var treeSource = discovered.get("minecraft:oak_log").getFirst();
        assertTrue(treeSource.attributes().get("nested_placed_features").contains("testmod:tree_checked"));
        assertTrue(treeSource.attributes().get("configured_features").contains("testmod:tree_nested"));

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
        var zeroChanceWorldgen = WorldgenAcquisitionAnalyzer.fromResolvedBlockSources(
            WorldgenAcquisitionAnalyzer.discoverBlockSources(zeroChanceResources),
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

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
