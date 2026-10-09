package com.jorjik.dynamicfood.core;

import java.util.Map;
import java.util.Objects;

public record CapabilitySatisfaction(
    String requirementId,
    String satisfierId,
    String type,
    Map<String, String> evidence
) {
    public CapabilitySatisfaction {
        requirementId = Objects.requireNonNullElse(requirementId, "unknown");
        satisfierId = Objects.requireNonNullElse(satisfierId, "unknown");
        type = Objects.requireNonNullElse(type, "unknown");
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    public String canonicalKey() {
        return requirementId + "->" + satisfierId + "@" + type;
    }
}
