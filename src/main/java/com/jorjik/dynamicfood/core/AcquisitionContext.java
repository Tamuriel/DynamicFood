package com.jorjik.dynamicfood.core;

import java.util.Map;
import java.util.Objects;

public record AcquisitionContext(
    String resourceId,
    String sourceType,
    String sourceId,
    Map<String, String> attributes
) {
    public AcquisitionContext {
        resourceId = Objects.requireNonNullElse(resourceId, "unknown");
        sourceType = Objects.requireNonNullElse(sourceType, "unknown");
        sourceId = Objects.requireNonNullElse(sourceId, "unknown");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static AcquisitionContext empty() {
        return new AcquisitionContext("unknown", "unknown", "unknown", Map.of());
    }
}
