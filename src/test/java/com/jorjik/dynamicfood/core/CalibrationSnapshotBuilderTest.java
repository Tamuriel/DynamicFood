package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CalibrationSnapshotBuilderTest {
    @Test
    void calibrationUsesOneEconomicSnapshotAndPreservesItsGenerationAndEvidence() {
        AtomicInteger analysisCalls = new AtomicInteger();
        AtomicInteger resolutionCalls = new AtomicInteger();
        AcquisitionAnalyzer analyzer = new AcquisitionAnalyzer() {
            @Override
            public boolean supports(String itemId) {
                return itemId.equals("test:known") || itemId.equals("test:unknown");
            }

            @Override
            public List<AcquisitionPath> analyze(String itemId) {
                analysisCalls.incrementAndGet();
                if (itemId.equals("test:unknown")) {
                    return List.of(path(itemId, "loot_table", "test:unknown_loot",
                        AcquisitionMeasurement.unknown("expected quantity is not evidenced")));
                }
                return List.of(
                    path(itemId, "recipe", "test:known_recipe", AcquisitionMeasurement.exact(2.0D)),
                    path(itemId, "loot_table", "test:known_loot",
                        AcquisitionMeasurement.analytical(0.5D,
                            new EstimateMetadata("loot expectation", "1", Map.of("rolls", "1"),
                                List.of("weighted independent entry selection"))))
                );
            }

            @Override
            public java.util.Set<String> indexedItemIds() {
                return java.util.Set.of("test:known", "test:unknown");
            }
        };
        var resolver = (java.util.function.BiFunction<String, List<AcquisitionPath>,
            EconomicCostResolution>) (itemId, paths) -> {
                resolutionCalls.incrementAndGet();
                if (itemId.equals("test:unknown")) {
                    return resolution(EconomicCost.unknown("no economic primitive is evidenced"),
                        ResolutionStatus.UNKNOWN, paths);
                }
                return resolution(EconomicCost.known(0.75D, "test:primitive", 100,
                    "resolved from static acquisition evidence"), ResolutionStatus.COMPLETE, paths);
            };
        List<String> resourceIds = List.of("test:known", "test:unknown");
        EconomicSnapshot generation10 = EconomicSnapshotBuilder.build(10, resourceIds,
            List.of(analyzer), resolver);
        EconomicSnapshot generation11 = EconomicSnapshotBuilder.build(11, resourceIds,
            List.of(analyzer), resolver);
        List<ResourceEconomicProfile> populationMetadata = List.of(
            metadata("test:known", "test:known_identity", 2.0D),
            metadata("test:unknown", "test:unknown_identity", 3.0D)
        );
        FoodCalibrationSettings settings = settings();
        Map<String, EconomicSnapshot.ResourceResult> originalEconomicResults = generation10.resources();
        String originalEconomicSignature = generation10.signature();
        int analysesBeforeCalibration = analysisCalls.get();
        int resolutionsBeforeCalibration = resolutionCalls.get();

        CalibrationSnapshot calibrated10 = CalibrationSnapshotBuilder.build(
            generation10, populationMetadata, settings);
        CalibrationSnapshot calibrated11 = CalibrationSnapshotBuilder.build(
            generation11, populationMetadata, settings);

        assertEquals(analysesBeforeCalibration, analysisCalls.get(),
            "calibration must not rerun acquisition analysis");
        assertEquals(resolutionsBeforeCalibration, resolutionCalls.get(),
            "calibration must not re-resolve EconomicCost");
        assertEquals(10, calibrated10.economicGeneration());
        assertEquals(generation10.signature(), calibrated10.economicContentSignature());
        assertEquals(11, calibrated11.economicGeneration());
        assertEquals(generation11.signature(), calibrated11.economicContentSignature());
        assertEquals(calibrated10.calibratedValues(), calibrated11.calibratedValues(),
            "equal economic content across generations must produce equal calibration values");
        assertEquals(calibrated10.signature(), calibrated11.signature(),
            "calibration content signature is independent of generation identity");
        assertNotEquals(calibrated10.economicGeneration(), calibrated11.economicGeneration());

        ResourceEconomicProfile unresolved = calibrated10.population().stream()
            .filter(profile -> profile.resourceId().equals("test:unknown")).findFirst().orElseThrow();
        assertEquals(null, unresolved.economicCost(),
            "an UNKNOWN snapshot result must not be replaced by the metadata profile's scalar cost");
        assertEquals(5.0D, calibrated10.candidatePopulationWeight(), 0.0D);
        assertEquals(2.0D, calibrated10.resolvedPopulationWeight(), 0.0D);
        assertEquals(0.4D, calibrated10.calibrationCoverage(), 0.0001D);
        assertTrue(calibrated10.population().stream()
            .anyMatch(profile -> profile.resourceId().equals("test:known")
                && profile.economicCost().equals(0.75D)));

        EconomicSnapshot.ResourceResult knownResult = generation10.resource("test:known").orElseThrow();
        assertEquals(2, knownResult.acquisitionPaths().size(),
            "both recipe and loot acquisition evidence must remain in the immutable snapshot");
        assertTrue(knownResult.acquisitionPaths().stream().anyMatch(path ->
            path.evidence().measurement("expected_units_per_attempt").estimateKind().orElseThrow()
                == EstimateKind.EXACT));
        assertTrue(knownResult.acquisitionPaths().stream().anyMatch(path ->
            path.evidence().measurement("expected_units_per_attempt").estimateKind().orElseThrow()
                == EstimateKind.ANALYTICAL));
        assertEquals(originalEconomicResults, generation10.resources(),
            "calibration must not mutate the source EconomicSnapshot");
        assertEquals(originalEconomicSignature, generation10.signature());
        assertThrows(UnsupportedOperationException.class,
            () -> calibrated10.population().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> calibrated10.calibratedValues().clear());

        CalibrationSnapshot copied = calibrated10.withFoodConfiguration(settings)
            .withConfigurationSignature("additional configuration");
        assertEquals(calibrated10.economicGeneration(), copied.economicGeneration());
        assertEquals(calibrated10.economicContentSignature(), copied.economicContentSignature());
        assertThrows(IllegalArgumentException.class,
            () -> calibrated10.withEconomicSnapshotLink(generation11));
    }

    @Test
    void calibrationRejectsCandidatesMissingFromSnapshotInsteadOfInventingUnknownRows() {
        EconomicSnapshot snapshot = EconomicSnapshotBuilder.build(1, List.of("test:present"),
            List.of(), (itemId, paths) -> resolution(
                EconomicCost.unknown("unresolved by static analysis"), ResolutionStatus.UNKNOWN, paths));

        assertThrows(IllegalArgumentException.class, () -> CalibrationSnapshotBuilder.build(
            snapshot, List.of(metadata("test:missing", "test:missing", 1.0D)), settings()));
    }

    @Test
    void disabledCalibrationProducesLinkedDisabledSnapshotWithoutFoodIndexValues() {
        EconomicSnapshot snapshot = EconomicSnapshotBuilder.build(4, List.of("test:present"),
            List.of(), (itemId, paths) -> resolution(
                EconomicCost.unknown("unresolved by static analysis"), ResolutionStatus.UNKNOWN, paths));
        FoodCalibrationSettings disabled = new FoodCalibrationSettings(false, "vanilla", 1,
            0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D, "medium",
            List.of(), List.of(), 2.0D, 0.0D);

        CalibrationSnapshot calibration = CalibrationSnapshotBuilder.build(snapshot,
            List.of(metadata("test:present", "test:present", 1.0D),
                metadata("test:missing", "test:missing", 1.0D)), disabled);

        assertEquals(CalibrationStatus.DISABLED, calibration.status());
        assertTrue(calibration.calibratedValues().isEmpty());
        assertTrue(calibration.population().isEmpty());
        assertEquals(snapshot.generation(), calibration.economicGeneration());
        assertEquals(snapshot.signature(), calibration.economicContentSignature());
        new PublishedEconomicGeneration(snapshot, calibration);
    }

    private static AcquisitionPath path(
        String itemId,
        String sourceType,
        String sourceId,
        AcquisitionMeasurement quantity
    ) {
        return new AcquisitionPath(itemId, sourceType, sourceId, 1.0D, null, null, true,
            false, Map.of(), Map.of(),
            new AcquisitionEvidence(Map.of("expected_units_per_attempt", quantity), Map.of()),
            EconomicCost.unknown("path measurement does not itself resolve EconomicCost"));
    }

    private static EconomicCostResolution resolution(
        EconomicCost cost,
        ResolutionStatus status,
        List<AcquisitionPath> paths
    ) {
        return new EconomicCostResolution(cost, null, status, 100, null, paths,
            cost.isKnown() ? 1.0D : 0.0D, List.of(cost.evidence()));
    }

    private static ResourceEconomicProfile metadata(String id, String identity, double weight) {
        return new ResourceEconomicProfile(id, identity, 0.5D, 0.25D, weight,
            true, true, SurvivalAcquirability.TRUE, false, false);
    }

    private static FoodCalibrationSettings settings() {
        return new FoodCalibrationSettings(true, "vanilla", 1, 0.70D, 0.30D,
            0.05D, 0.95D, 0.50D, 0.80D, "medium", List.of(), List.of(), 2.0D, 0.0D);
    }
}
