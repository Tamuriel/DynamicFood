package com.jorjik.dynamicfood.core;

import java.util.Map;
import java.util.Objects;

public record PathIdentity(
    String resourceId,
    String sourceType,
    String sourceId,
    String requirementKey,
    String satisfierKey,
    String cycleKey
) {
    public PathIdentity {
        resourceId = Objects.requireNonNullElse(resourceId, "unknown");
        sourceType = Objects.requireNonNullElse(sourceType, "unknown");
        sourceId = Objects.requireNonNullElse(sourceId, "unknown");
        requirementKey = requirementKey == null ? "" : requirementKey;
        satisfierKey = satisfierKey == null ? "" : satisfierKey;
        cycleKey = cycleKey == null ? "" : cycleKey;
    }

    public static PathIdentity fromMechanicalIdentity(
        String resourceId,
        String sourceType,
        String sourceId,
        AcquisitionRequirements requirements,
        CapabilitySatisfaction satisfaction,
        String cycleKey
    ) {
        return new PathIdentity(
            resourceId,
            sourceType,
            sourceId,
            requirements == null ? "" : requirements.canonicalKey(),
            satisfaction == null ? "" : satisfaction.canonicalKey(),
            cycleKey == null ? "" : cycleKey
        );
    }

    public String canonicalKey() {
        return resourceId + "|" + sourceType + "|" + sourceId + "|" + requirementKey + "|" + satisfierKey + "|" + cycleKey;
    }
}
