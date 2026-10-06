package com.jorjik.dynamicfood.core;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

/** Explicitly publishes and reads complete immutable economic generations atomically. */
public final class EconomicGenerationPublisher {
    private final AtomicReference<PublishedEconomicGeneration> current = new AtomicReference<>();
    private long lastPublishedGeneration;

    public Optional<PublishedEconomicGeneration> current() {
        return Optional.ofNullable(current.get());
    }

    public synchronized PublishedEconomicGeneration publish(PublishedEconomicGeneration generation) {
        if (generation == null) {
            throw new IllegalArgumentException("published economic generation is required");
        }
        lastPublishedGeneration = Math.max(lastPublishedGeneration, generation.generation());
        current.set(generation);
        return generation;
    }

    public PublishedEconomicGeneration publish(
        EconomicSnapshot economicSnapshot,
        CalibrationSnapshot calibrationSnapshot
    ) {
        return publish(new PublishedEconomicGeneration(economicSnapshot, calibrationSnapshot));
    }

    public synchronized PublishedEconomicGeneration rebuildAndPublish(
        LongFunction<PublishedEconomicGeneration> generationBuilder
    ) {
        if (generationBuilder == null) {
            throw new IllegalArgumentException("generation builder is required");
        }
        long nextGeneration = Math.incrementExact(lastPublishedGeneration);
        PublishedEconomicGeneration candidate = generationBuilder.apply(nextGeneration);
        if (candidate == null || candidate.generation() != nextGeneration) {
            throw new IllegalArgumentException("generation builder returned an unexpected publication generation");
        }
        lastPublishedGeneration = nextGeneration;
        current.set(candidate);
        return candidate;
    }

    public synchronized void clearCurrent() {
        current.set(null);
    }

    public synchronized PublishedEconomicGeneration publishCalibrationForCurrent(
        PublishedEconomicGeneration expectedCurrent,
        CalibrationSnapshot calibrationSnapshot
    ) {
        if (expectedCurrent == null || calibrationSnapshot == null) {
            throw new IllegalArgumentException("current generation and calibration snapshot are required");
        }
        if (current.get() != expectedCurrent) {
            throw new IllegalStateException("economic generation changed while calibration was being rebuilt");
        }
        PublishedEconomicGeneration candidate = new PublishedEconomicGeneration(
            expectedCurrent.economicSnapshot(), calibrationSnapshot);
        current.set(candidate);
        return candidate;
    }
}
