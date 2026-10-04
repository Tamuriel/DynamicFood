package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EconomicGenerationPublisherTest {
    @Test
    void publishesOneValidatedImmutablePairAndStartsWithoutAFakeGeneration() {
        EconomicGenerationPublisher publisher = new EconomicGenerationPublisher();
        assertTrue(publisher.current().isEmpty());

        PublishedEconomicGeneration generation = buildGeneration(10, 0.25D);
        assertSame(generation, publisher.publish(generation));
        PublishedEconomicGeneration current = publisher.current().orElseThrow();

        assertSame(generation, current);
        assertSame(generation.economicSnapshot(), current.economicSnapshot());
        assertSame(generation.calibrationSnapshot(), current.calibrationSnapshot());
        assertEquals(10, current.generation());
        assertEquals(generation.economicSnapshot().signature(), current.economicContentSignature());
        assertThrows(UnsupportedOperationException.class,
            () -> current.economicSnapshot().resources().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> current.calibrationSnapshot().population().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> current.calibrationSnapshot().calibratedValues().clear());
    }

    @Test
    void rejectsNullUnlinkedMismatchedAndInconsistentSnapshotPairs() {
        PublishedEconomicGeneration valid = buildGeneration(10, 0.25D);
        assertThrows(IllegalArgumentException.class,
            () -> new PublishedEconomicGeneration(null, valid.calibrationSnapshot()));
        assertThrows(IllegalArgumentException.class,
            () -> new PublishedEconomicGeneration(valid.economicSnapshot(), null));
        assertThrows(IllegalArgumentException.class,
            () -> new EconomicGenerationPublisher().publish((PublishedEconomicGeneration) null));

        CalibrationSnapshot unlinked = copyCalibration(valid.calibrationSnapshot(), null, 0L);
        assertThrows(IllegalArgumentException.class,
            () -> new PublishedEconomicGeneration(valid.economicSnapshot(), unlinked));

        PublishedEconomicGeneration sameContentNextGeneration = buildGeneration(11, 0.25D);
        assertEquals(valid.economicSnapshot().signature(),
            sameContentNextGeneration.economicSnapshot().signature());
        assertThrows(IllegalArgumentException.class,
            () -> new PublishedEconomicGeneration(valid.economicSnapshot(),
                sameContentNextGeneration.calibrationSnapshot()));

        PublishedEconomicGeneration sameGenerationDifferentContent = buildGeneration(10, 0.75D);
        assertNotEquals(valid.economicSnapshot().signature(),
            sameGenerationDifferentContent.economicSnapshot().signature());
        assertThrows(IllegalArgumentException.class,
            () -> new PublishedEconomicGeneration(valid.economicSnapshot(),
                sameGenerationDifferentContent.calibrationSnapshot()));

        CalibrationSnapshot wrongPopulationCost = copyCalibration(valid.calibrationSnapshot(),
            List.of(profile("test:food", 0.99D)), 10L);
        assertThrows(IllegalArgumentException.class,
            () -> new PublishedEconomicGeneration(valid.economicSnapshot(), wrongPopulationCost));
    }

    @Test
    void sameEconomicContentCanBePublishedAsDistinctGenerationsAndReplacementIsWholePair() {
        EconomicGenerationPublisher publisher = new EconomicGenerationPublisher();
        PublishedEconomicGeneration generation10 = buildGeneration(10, 0.25D);
        PublishedEconomicGeneration generation11 = buildGeneration(11, 0.25D);

        publisher.publish(generation10);
        PublishedEconomicGeneration captured10 = publisher.current().orElseThrow();
        publisher.publish(generation11);
        PublishedEconomicGeneration captured11 = publisher.current().orElseThrow();

        assertSame(generation10, captured10);
        assertSame(generation11, captured11);
        assertEquals(captured10.economicContentSignature(), captured11.economicContentSignature());
        assertNotEquals(captured10.generation(), captured11.generation());
        assertEquals(captured10.generation(), captured10.calibrationSnapshot().economicGeneration());
        assertEquals(captured11.generation(), captured11.calibrationSnapshot().economicGeneration());
        assertEquals(captured10.economicSnapshot().signature(),
            captured10.calibrationSnapshot().economicContentSignature());
        assertEquals(captured11.economicSnapshot().signature(),
            captured11.calibrationSnapshot().economicContentSignature());
    }

    @Test
    void concurrentReadersOnlyObserveCompletePublishedGenerationObjects() throws Exception {
        EconomicGenerationPublisher publisher = new EconomicGenerationPublisher();
        PublishedEconomicGeneration generation10 = buildGeneration(10, 0.25D);
        PublishedEconomicGeneration generation11 = buildGeneration(11, 0.25D);
        publisher.publish(generation10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean publishing = new AtomicBoolean(true);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> {
                await(start);
                for (int index = 0; index < 2_000; index++) {
                    publisher.publish((index & 1) == 0 ? generation11 : generation10);
                }
                publishing.set(false);
            });
            Future<?> reader = executor.submit(() -> {
                await(start);
                int reads = 0;
                while (publishing.get() || reads < 2_000) {
                    PublishedEconomicGeneration observed = publisher.current().orElseThrow();
                    assertCoherent(observed);
                    reads++;
                }
            });
            start.countDown();
            writer.get();
            reader.get();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void publicationAndReadsDoNotRunEconomicAcquisitionOrCalibrationWork() {
        AtomicInteger acquisitionAnalysisCalls = new AtomicInteger();
        AtomicInteger economicResolutionCalls = new AtomicInteger();
        AcquisitionAnalyzer analyzer = new AcquisitionAnalyzer() {
            @Override
            public boolean supports(String itemId) {
                return true;
            }

            @Override
            public List<AcquisitionPath> analyze(String itemId) {
                acquisitionAnalysisCalls.incrementAndGet();
                return List.of();
            }

            @Override
            public java.util.Set<String> indexedItemIds() {
                return java.util.Set.of("test:food");
            }
        };
        EconomicSnapshot economic = EconomicSnapshotBuilder.build(42, List.of("test:food"),
            List.of(analyzer), (itemId, paths) -> {
                economicResolutionCalls.incrementAndGet();
                return resolution();
            });
        CalibrationSnapshot calibration = CalibrationSnapshotBuilder.build(economic,
            List.of(profile("test:food", 1.0D)), settings());
        int analysesBefore = acquisitionAnalysisCalls.get();
        int resolutionsBefore = economicResolutionCalls.get();
        EconomicGenerationPublisher publisher = new EconomicGenerationPublisher();

        publisher.publish(economic, calibration);
        for (int index = 0; index < 100; index++) {
            publisher.current().orElseThrow();
        }

        assertEquals(analysesBefore, acquisitionAnalysisCalls.get());
        assertEquals(resolutionsBefore, economicResolutionCalls.get());
    }

    private static PublishedEconomicGeneration buildGeneration(long generation, double cost) {
        EconomicSnapshot snapshot = EconomicSnapshotBuilder.build(generation, List.of("test:food"),
            List.of(), (itemId, paths) -> new EconomicCostResolution(
                EconomicCost.known(cost, "test:primitive", 100, "static fixture"),
                null, ResolutionStatus.COMPLETE, 100, null, paths, 1.0D, List.of("fixture")));
        CalibrationSnapshot calibration = CalibrationSnapshotBuilder.build(snapshot,
            List.of(profile("test:food", 1.0D)), settings());
        return new PublishedEconomicGeneration(snapshot, calibration);
    }

    private static void assertCoherent(PublishedEconomicGeneration generation) {
        assertEquals(generation.generation(), generation.economicSnapshot().generation());
        assertEquals(generation.generation(), generation.calibrationSnapshot().economicGeneration());
        assertEquals(generation.economicContentSignature(), generation.economicSnapshot().signature());
        assertEquals(generation.economicContentSignature(),
            generation.calibrationSnapshot().economicContentSignature());
    }

    private static CalibrationSnapshot copyCalibration(
        CalibrationSnapshot source,
        List<ResourceEconomicProfile> population,
        long generation
    ) {
        return new CalibrationSnapshot(
            population == null ? source.population() : population,
            source.populationSize(),
            source.candidatePopulationWeight(),
            source.resolvedPopulationWeight(),
            source.calibrationCoverage(),
            source.coverageStatus(),
            source.p05(),
            source.p50(),
            source.p95(),
            source.magnitudeWeight(),
            source.rankWeight(),
            source.status(),
            source.calibratedValues(),
            source.signature(),
            source.gameplayPreset(),
            source.hungerCurve(),
            source.saturationCurve(),
            source.rankTieMode(),
            generation,
            generation == 0 ? null : source.economicContentSignature()
        );
    }

    private static ResourceEconomicProfile profile(String id, Double cost) {
        return new ResourceEconomicProfile(id, id, cost, 1.0D, 1.0D,
            true, true, SurvivalAcquirability.TRUE, false, false);
    }

    private static EconomicCostResolution resolution() {
        return new EconomicCostResolution(EconomicCost.known(1.0D, "test:primitive", 100,
            "static fixture"), null, ResolutionStatus.COMPLETE, 100, null, List.of(), 1.0D,
            List.of("fixture"));
    }

    private static FoodCalibrationSettings settings() {
        return new FoodCalibrationSettings(true, "vanilla", 1, 0.70D, 0.30D,
            0.05D, 0.95D, 0.50D, 0.80D, "medium", List.of(), List.of(), 2.0D, 0.0D);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("publication test interrupted", exception);
        }
    }
}
