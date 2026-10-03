package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void worldgenDiscoveryFollowsBiomePlacedAndConfiguredFeaturesWithoutNamespaceGuessing() {
        Map<String, JsonObject> resources = Map.of(
            "testmod:worldgen/biome/forest.json", json("""
                {"features":[["testmod:ore_patch"]]}
                """),
            "testmod:worldgen/placed_feature/ore_patch.json", json("""
                {"feature":"testmod:ore_config","placement":[
                  {"type":"minecraft:count","count":{"type":"minecraft:constant","value":4}},
                  {"type":"minecraft:rarity_filter","chance":20},
                  {"type":"minecraft:height_range","height":{"type":"minecraft:uniform","min_inclusive":{"absolute":0},"max_inclusive":{"absolute":64}}}
                ]}
                """),
            "testmod:worldgen/configured_feature/ore_config.json", json("""
                {"feature":"minecraft:ore","config":{"size":8,"states":[
                  {"state":{"Name":"minecraft:coal_ore"}},
                  {"state":{"Name":"minecraft:deepslate_coal_ore"}}
                ]}}
                """),
            "testmod:worldgen/placed_feature/unreferenced.json", json("""
                {"feature":"testmod:orphan_config","placement":[]}
                """),
            "testmod:worldgen/configured_feature/orphan_config.json", json("""
                {"feature":"minecraft:ore","config":{"state":{"Name":"minecraft:diamond_ore"}}}
                """),
            "testmod:worldgen/dimension/my_dimension.json", json("""
                {"generator":{"type":"minecraft:noise","biome_source":{
                  "type":"minecraft:fixed","biome":"testmod:forest"
                }}}
                """));
        Map<String, java.util.List<WorldgenAcquisitionAnalyzer.WorldgenBlockSource>> discovered =
            WorldgenAcquisitionAnalyzer.discoverBlockSources(resources);

        assertTrue(discovered.containsKey("minecraft:coal_ore"));
        assertTrue(discovered.containsKey("minecraft:deepslate_coal_ore"));
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
        assertEquals("item per block-break loot invocation",
            stone.evidence().attributes().get("canonical_quantity_unit"));
        assertTrue(stone.costsByHorizon().get(100).factors().get("quantity_cost").isKnown());
        assertTrue(stone.costsByHorizon().get(100).factors().get("equipment_cost").isUnknown());
        assertTrue(!stone.economicCost().isKnown());

        AcquisitionPath coal = analyzer.analyze("minecraft:coal").getFirst();
        assertEquals("minecraft:blocks/coal_ore", coal.evidence().attributes().get("block_loot_table"));
        assertEquals("break the generated block and evaluate its block loot table",
            coal.evidence().attributes().get("extraction_operation"));
        assertTrue(!coal.evidence().measurement("expected_units_per_attempt").isKnown());
        assertTrue(coal.costsByHorizon().get(100).factors().get("quantity_cost").isUnknown());
        assertTrue(!coal.economicCost().isKnown());
        assertEquals("UNKNOWN", coal.evidence().attributes().get("source_availability_classification"));
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
                EconomicCost.known(4.0D, "test:resource_units", horizon, "independent test primitive");
            default -> EconomicCost.unknown("no independent primitive evidence for " + itemId);
        };
        RecipeEconomicAnalyzer analyzer = new RecipeEconomicAnalyzer(graph, evidence);

        assertEquals(4.0D, analyzer.resolve("test:plate", 100).economicCost(), 0.0D);
        assertEquals(4.0D, analyzer.resolve("test:food", 100).economicCost(), 0.0D);

        RecipeEconomicAnalyzer ambiguous = new RecipeEconomicAnalyzer(graph, (itemId, horizon) ->
            itemId.equals("test:ore_a")
                ? EconomicCost.known(4.0D, "test:resource_units", horizon, "independent test primitive")
                : itemId.equals("test:ore_b")
                    ? EconomicCost.known(5.0D, "test:resource_units", horizon, "independent test primitive")
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
                ? EconomicCost.known(2.0D, "test:resource_units", horizon, "independent test primitive")
                : EconomicCost.unknown("no independent primitive evidence for " + itemId));

        RecipeEconomicResult result = analyzer.resolve("test:food", 100);
        AcquisitionPath path = new RecipeGraphAcquisitionAnalyzer(graph, (itemId, horizon) ->
            itemId.equals("test:ingredient")
                ? EconomicCost.known(2.0D, "test:resource_units", horizon, "independent test primitive")
                : EconomicCost.unknown("no independent primitive evidence for " + itemId),
            1.0D, 100.0D).analyze("test:food").getFirst();

        assertEquals(2.0D, result.economicCost(), 0.0D);
        assertEquals("reusable", path.evidence().attributes().get("input_1_use"));
        assertEquals("test:tool", path.evidence().attributes().get("input_1_alternatives"));
        assertTrue(path.costsByHorizon().get(100).factors().get("equipment_cost").isUnknown());
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
        }
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
