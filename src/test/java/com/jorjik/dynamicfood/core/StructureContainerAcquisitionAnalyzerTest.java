package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.Test;

class StructureContainerAcquisitionAnalyzerTest {
    @Test
    void preservesStructureConfigurationAndUnresolvedCyclesDeterministically() {
        Map<String, JsonObject> structures = Map.of("test:structure", json("""
            {"type":"minecraft:jigsaw","start_pool":"test:start",
             "pool_aliases":[{"type":"minecraft:random","name":"test:start","alias":"test:other"}]}
            """));
        Map<String, JsonObject> structureSets = Map.of("test:set", json("""
            {"placement":{"type":"minecraft:random_spread","spacing":34,"separation":12,"salt":42},
             "structures":[{"structure":"test:structure","weight":3}]}
            """));
        Map<String, JsonObject> pools = Map.of("test:start", json("""
            {"elements":[{"element":{"element_type":"minecraft:feature_pool_element",
                                     "feature":"test:feature"},"weight":1}],
             "fallback":"test:start"}
            """));
        StructureContainerAcquisitionAnalyzer first = analyzer(structures, structureSets, pools);
        StructureContainerAcquisitionAnalyzer repeated = analyzer(structures, structureSets, pools);

        assertEquals(first.summary(), repeated.summary());
        assertEquals(1, first.summary().structureSets());
        assertEquals(1, first.summary().jigsawStructures());
        assertEquals(1, first.summary().aliasEntries());
        assertEquals(1, first.summary().unsupportedPoolElements());
        assertEquals(1, first.summary().cycleReferences());
        assertEquals(0, first.summary().candidatePaths());
        assertTrue(first.summary().unresolvedReferences() > 0);
    }

    @Test
    void leavesUnsupportedStructureTypesOutsideTheJigsawCandidateFamily() {
        StructureContainerAcquisitionAnalyzer analyzer = analyzer(
            Map.of("test:structure", json("""
                {"type":"minecraft:buried_treasure","biomes":"minecraft:plains"}
                """)),
            Map.of("test:set", json("""
                {"placement":{"type":"minecraft:random_spread","spacing":10,"separation":2,"salt":1},
                 "structures":[{"structure":"test:structure","weight":1}]}
                """)),
            Map.of());

        assertEquals(1, analyzer.summary().structures());
        assertEquals(0, analyzer.summary().jigsawStructures());
        assertEquals(List.of("minecraft:buried_treasure"),
            analyzer.summary().unsupportedStructureTypes());
        assertEquals(0, analyzer.summary().candidatePaths());
        assertTrue(analyzer.summary().unresolvedReferences() > 0);
    }

    private static StructureContainerAcquisitionAnalyzer analyzer(
        Map<String, JsonObject> structures, Map<String, JsonObject> structureSets,
        Map<String, JsonObject> pools) {
        return StructureContainerAcquisitionAnalyzer.fromResources(structures, structureSets, pools,
            Map.of(), RegistryAccess.EMPTY, LootTableAcquisitionAnalyzer.fromParsedTables(
                List.of(), 1.0D, 100.0D));
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
