package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.core.EconomicGenerationPublisher;
import com.jorjik.dynamicfood.core.PublishedEconomicGeneration;
import java.util.Optional;

/** One immutable result of reading the published generation at operation start. */
public record RuntimeEconomicContext(Optional<PublishedEconomicGeneration> publishedGeneration) {
    public RuntimeEconomicContext {
        publishedGeneration = publishedGeneration == null ? Optional.empty() : publishedGeneration;
    }

    public static RuntimeEconomicContext capture(EconomicGenerationPublisher publisher) {
        if (publisher == null) {
            throw new IllegalArgumentException("economic generation publisher is required");
        }
        return new RuntimeEconomicContext(publisher.current());
    }

    public Optional<Long> generation() {
        return publishedGeneration.map(PublishedEconomicGeneration::generation);
    }

    public Optional<String> economicContentSignature() {
        return publishedGeneration.map(PublishedEconomicGeneration::economicContentSignature);
    }
}
