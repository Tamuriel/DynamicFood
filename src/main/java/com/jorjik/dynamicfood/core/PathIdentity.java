package com.jorjik.dynamicfood.core;

import java.util.List;
import java.util.Objects;

public record PathIdentity(
    String resourceId,
    String sourceType,
    String sourceId,
    String requirementKey,
    String satisfierKey,
    String cycleKey,
    String conditionalKey,
    String operationKey
) {
    private static final String UNMODELED = "<unmodeled>";

    public PathIdentity(String resourceId, String sourceType, String sourceId,
        String requirementKey, String satisfierKey, String cycleKey) {
        this(resourceId, sourceType, sourceId, requirementKey, satisfierKey, cycleKey, UNMODELED, UNMODELED);
    }

    public PathIdentity {
        resourceId = Objects.requireNonNullElse(resourceId, "unknown");
        sourceType = Objects.requireNonNullElse(sourceType, "unknown");
        sourceId = Objects.requireNonNullElse(sourceId, "unknown");
        requirementKey = Objects.requireNonNullElse(requirementKey, UNMODELED);
        satisfierKey = Objects.requireNonNullElse(satisfierKey, UNMODELED);
        cycleKey = Objects.requireNonNullElse(cycleKey, UNMODELED);
        conditionalKey = Objects.requireNonNullElse(conditionalKey, UNMODELED);
        operationKey = Objects.requireNonNullElse(operationKey, UNMODELED);
    }

    public static PathIdentity fromMechanicalIdentity(
        String resourceId,
        String sourceType,
        String sourceId,
        AcquisitionRequirements requirements,
        CapabilitySatisfaction satisfaction,
        String cycleKey
    ) {
        return fromMechanicalIdentity(resourceId, sourceType, sourceId, requirements, satisfaction, cycleKey,
            null, null);
    }

    public static PathIdentity fromMechanicalIdentity(
        String resourceId,
        String sourceType,
        String sourceId,
        AcquisitionRequirements requirements,
        CapabilitySatisfaction satisfaction,
        String cycleKey,
        String conditionalKey,
        String operationKey
    ) {
        return new PathIdentity(
            resourceId,
            sourceType,
            sourceId,
            requirements == null ? UNMODELED : requirements.canonicalKey(),
            satisfaction == null ? UNMODELED : satisfaction.canonicalKey(),
            cycleKey,
            conditionalKey,
            operationKey
        );
    }

    static PathIdentity sourceOnly(String resourceId, String sourceType, String sourceId) {
        return new PathIdentity(resourceId, sourceType, sourceId, UNMODELED, UNMODELED,
            UNMODELED,
            UNMODELED, UNMODELED);
    }

    PathIdentity withPathCycleAndEvidence(Boolean repeatable, Double renewability, String evidenceKey) {
        return new PathIdentity(resourceId, sourceType, sourceId, requirementKey, satisfierKey,
            canonical(List.of(cycleKey, nullable(repeatable), nullable(renewability))),
            conditionalKey, canonical(List.of(operationKey, evidenceKey)));
    }

    public String canonicalKey() {
        return canonical(List.of(resourceId, sourceType, sourceId, requirementKey, satisfierKey,
            cycleKey, conditionalKey, operationKey));
    }

    private static String canonical(List<String> values) {
        StringBuilder result = new StringBuilder();
        values.forEach(value -> {
            String normalized = Objects.requireNonNullElse(value, UNMODELED);
            result.append(normalized.length()).append(':').append(normalized);
        });
        return result.toString();
    }

    private static String nullable(Object value) {
        return value == null ? UNMODELED : value.toString();
    }
}
