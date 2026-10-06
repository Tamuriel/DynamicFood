package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SurvivalAcquirabilityResolverTest {
    @Test
    void activeRecipeRequiresSurvivalEvidenceForEveryInput() {
        AcquisitionPath recipe = recipePath("test:output", List.of(
            new AcquisitionIngredient(List.of("test:input"), 2)));
        AcquisitionPath input = sourcePath("test:input", "loot", "test:input_drop", "TRUE");

        Map<String, List<AcquisitionPath>> result = new SurvivalAcquirabilityResolver()
            .resolvePathAvailability(Map.of("test:output", List.of(recipe), "test:input", List.of(input)));

        assertEquals("TRUE", classification(result, "test:output"));
        assertTrue(result.get("test:output").getFirst().evidence().attributes()
            .get("survival_availability").contains("every required input"));
    }

    @Test
    void recipeIsFalseOnlyWhenEveryAlternativeForARequiredInputIsUnavailable() {
        AcquisitionPath recipe = recipePath("test:output", List.of(
            new AcquisitionIngredient(List.of("test:unavailable_a", "test:unavailable_b"), 1)));
        Map<String, List<AcquisitionPath>> result = new SurvivalAcquirabilityResolver()
            .resolvePathAvailability(Map.of(
                "test:output", List.of(recipe),
                "test:unavailable_a", List.of(sourcePath(
                    "test:unavailable_a", "loot", "test:a", "FALSE")),
                "test:unavailable_b", List.of(sourcePath(
                    "test:unavailable_b", "loot", "test:b", "FALSE"))));

        assertEquals("FALSE", classification(result, "test:output"));
    }

    @Test
    void oneProvenIngredientAlternativeIsEnoughForSurvivalAvailability() {
        AcquisitionPath recipe = recipePath("test:output", List.of(
            new AcquisitionIngredient(List.of("test:unknown", "test:available"), 1)));
        Map<String, List<AcquisitionPath>> result = new SurvivalAcquirabilityResolver()
            .resolvePathAvailability(Map.of(
                "test:output", List.of(recipe),
                "test:unknown", List.of(sourcePath("test:unknown", "loot", "test:unknown", "UNKNOWN")),
                "test:available", List.of(sourcePath("test:available", "loot", "test:available", "TRUE"))));

        assertEquals("TRUE", classification(result, "test:output"));
    }

    @Test
    void missingEvidenceAndRecipeCyclesRemainUnknown() {
        AcquisitionPath unresolved = recipePath("test:unresolved", List.of(
            new AcquisitionIngredient(List.of("test:missing_source"), 1)));
        AcquisitionPath cycleA = recipePath("test:cycle_a", List.of(
            new AcquisitionIngredient(List.of("test:cycle_b"), 1)));
        AcquisitionPath cycleB = recipePath("test:cycle_b", List.of(
            new AcquisitionIngredient(List.of("test:cycle_a"), 1)));
        Map<String, List<AcquisitionPath>> result = new SurvivalAcquirabilityResolver()
            .resolvePathAvailability(Map.of(
                "test:unresolved", List.of(unresolved),
                "test:cycle_a", List.of(cycleA),
                "test:cycle_b", List.of(cycleB)));

        assertEquals("UNKNOWN", classification(result, "test:unresolved"));
        assertEquals("UNKNOWN", classification(result, "test:cycle_a"));
        assertEquals("UNKNOWN", classification(result, "test:cycle_b"));
        assertEquals("RECIPE_INPUTS_UNKNOWN", result.get("test:unresolved").getFirst().evidence().attributes()
            .get("source_availability_blocker"));
        assertEquals("RECURSIVE_INPUT_RESOLUTION", result.get("test:unresolved").getFirst().evidence().attributes()
            .get("source_availability_stage"));
    }

    @Test
    void unavailableAlternativesReportExplicitBlockerAndStage() {
        AcquisitionPath recipe = recipePath("test:output", List.of(
            new AcquisitionIngredient(List.of("test:a", "test:b"), 1)));
        Map<String, List<AcquisitionPath>> result = new SurvivalAcquirabilityResolver()
            .resolvePathAvailability(Map.of(
                "test:output", List.of(recipe),
                "test:a", List.of(sourcePath("test:a", "loot", "test:a", "FALSE")),
                "test:b", List.of(sourcePath("test:b", "loot", "test:b", "FALSE"))));

        assertEquals("FALSE", classification(result, "test:output"));
        assertEquals("RECIPE_INPUT_ALTERNATIVES_UNAVAILABLE",
            result.get("test:output").getFirst().evidence().attributes().get("source_availability_blocker"));
        assertEquals("AVAILABILITY_CHECK",
            result.get("test:output").getFirst().evidence().attributes().get("source_availability_stage"));
    }

    @Test
    void unresolvedRecipeInputReasonIsRetainedBySurvivalClassification() {
        String reason = "ingredient tag(s) resolved to no item alternatives: #test:missing";
        AcquisitionPath recipe = recipePath("test:output", List.of(
            new AcquisitionIngredient(List.of(), 1, AcquisitionIngredient.InputUse.UNKNOWN, reason)));

        Map<String, List<AcquisitionPath>> result = new SurvivalAcquirabilityResolver()
            .resolvePathAvailability(Map.of("test:output", List.of(recipe)));
        AcquisitionPath resolved = result.get("test:output").getFirst();

        assertEquals("UNKNOWN", classification(result, "test:output"));
        assertEquals("RECIPE_INPUTS_UNKNOWN",
            resolved.evidence().attributes().get("source_availability_blocker"));
        assertTrue(resolved.evidence().attributes().get("survival_availability").contains(reason));
    }

    @Test
    void worldgenDirectBreakRequiresActivePositiveAndMatchingEvidence() {
        AcquisitionPath proven = worldgenPath(Map.of());
        Map<String, List<AcquisitionPath>> resolved = new SurvivalAcquirabilityResolver()
            .resolvePathAvailability(Map.of("test:oak_log", List.of(proven)));
        assertEquals("TRUE", classification(resolved, "test:oak_log"));
        assertEquals("WORLDGEN_EXTRACTION",
            resolved.get("test:oak_log").getFirst().evidence().attributes()
                .get("source_availability_stage"));

        assertWorldgenUnknown(Map.of("active_biome_feature_source", "UNKNOWN"));
        assertWorldgenUnknown(Map.of("configured_feature_generation_opportunity", "UNKNOWN"));
        assertWorldgenUnknown(Map.of("worldgen_block_item_id", "test:other"));
        assertWorldgenUnknown(Map.of("worldgen_requires_correct_tool", "true"));
        assertWorldgenUnknown(Map.of("ordinary_player_break_output_evidence", "UNKNOWN"));

        Map<String, List<AcquisitionPath>> unavailable = new SurvivalAcquirabilityResolver()
            .resolvePathAvailability(Map.of("test:oak_log",
                List.of(worldgenPath(Map.of("source_availability_classification", "FALSE")))));
        assertEquals("FALSE", classification(unavailable, "test:oak_log"));
    }

    @Test
    void snapshotBuilderClassifiesPathsAfterAllResourcesHaveBeenAnalyzed() {
        AcquisitionPath recipe = recipePath("test:output", List.of(
            new AcquisitionIngredient(List.of("test:input"), 1)));
        AcquisitionPath input = sourcePath("test:input", "loot", "test:input_drop", "TRUE");
        AcquisitionAnalyzer analyzer = new AcquisitionAnalyzer() {
            @Override
            public boolean supports(String itemId) {
                return itemId.equals("test:output") || itemId.equals("test:input");
            }

            @Override
            public List<AcquisitionPath> analyze(String itemId) {
                return itemId.equals("test:output") ? List.of(recipe) : List.of(input);
            }

            @Override
            public java.util.Set<String> indexedItemIds() {
                return java.util.Set.of("test:output", "test:input");
            }
        };
        EconomicSnapshot snapshot = EconomicSnapshotBuilder.build(1,
            List.of("test:output", "test:input"), List.of(analyzer),
            (itemId, paths) -> new EconomicCostResolution(EconomicCost.unknown("test unresolved"),
                null, ResolutionStatus.UNKNOWN, 100, null, paths, 0.0D, List.of("test unresolved")));

        assertEquals("TRUE", snapshot.resource("test:output").orElseThrow()
            .acquisitionPaths().getFirst().evidence().attributes()
            .get("source_availability_classification"));
    }

    private static String classification(Map<String, List<AcquisitionPath>> paths, String itemId) {
        return paths.get(itemId).getFirst().evidence().attributes()
            .get("source_availability_classification");
    }

    private static AcquisitionPath recipePath(String itemId, List<AcquisitionIngredient> inputs) {
        return new AcquisitionPath(itemId, "recipe", itemId + "_recipe", 1.0D, null, null, true, false,
            Map.of(), Map.of(), new AcquisitionEvidence(Map.of(), Map.of(
                "recipe_operation_availability", "TRUE",
                "recipe_operation_availability_reason", "active recipe in test fixture"
            ), inputs));
    }

    private static AcquisitionPath sourcePath(String itemId, String sourceType, String sourceId,
        String availability) {
        return new AcquisitionPath(itemId, sourceType, sourceId, 1.0D, null, null, true, false,
            Map.of(), Map.of(), new AcquisitionEvidence(Map.of(), Map.of(
                "source_availability_classification", availability)));
    }

    private static void assertWorldgenUnknown(Map<String, String> evidenceChange) {
        Map<String, List<AcquisitionPath>> resolved = new SurvivalAcquirabilityResolver()
            .resolvePathAvailability(Map.of("test:oak_log", List.of(worldgenPath(evidenceChange))));
        assertEquals("UNKNOWN", classification(resolved, "test:oak_log"));
    }

    private static AcquisitionPath worldgenPath(Map<String, String> evidenceChange) {
        Map<String, String> attributes = new HashMap<>(Map.of(
            "source_availability_classification", "UNKNOWN",
            "active_dimension", "minecraft:overworld",
            "active_biome_feature_source", "TRUE",
            "configured_feature_generation_opportunity", "TRUE",
            "worldgen_block_item_id", "test:oak_log",
            "worldgen_requires_correct_tool", "false",
            "ordinary_player_break_output_evidence", "TRUE"
        ));
        attributes.putAll(evidenceChange);
        return new AcquisitionPath("test:oak_log", "worldgen_feature", "test:plains/test:tree",
            1.0D, null, null, null, false, Map.of(), Map.of(),
            new AcquisitionEvidence(Map.of(), attributes));
    }
}
