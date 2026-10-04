package com.jorjik.dynamicfood.core;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

/** Explicitly publishes and reads complete immutable economic generations atomically. */
public final class EconomicGenerationPublisher {
    private final AtomicReference<PublishedEconomicGeneration> current = new AtomicReference<>();

    public Optional<PublishedEconomicGeneration> current() {
        return Optional.ofNullable(current.get());
    }

    public synchronized PublishedEconomicGeneration publish(PublishedEconomicGeneration generation) {
        if (generation == null) {
            throw new IllegalArgumentException("published economic generation is required");
        }
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
        long nextGeneration = current.get() == null ? 1L : Math.incrementExact(current.get().generation());
        PublishedEconomicGeneration candidate = generationBuilder.apply(nextGeneration);
        if (candidate == null || candidate.generation() != nextGeneration) {
            throw new IllegalArgumentException("generation builder returned an unexpected publication generation");
        }
        current.set(candidate);
        return candidate;
    }
}
