package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
