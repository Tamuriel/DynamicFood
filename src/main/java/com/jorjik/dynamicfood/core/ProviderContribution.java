package com.jorjik.dynamicfood.core;

import java.util.Map;
import java.util.Objects;

public record ProviderContribution(
    String providerId,
    String mechanicId,
    ProviderContributionOperation operation,
    String sourceType,
    String sourceId,
    Map<String, Object> payload,
    Map<String, String> provenance
) {
    public ProviderContribution {
        Objects.requireNonNull(providerId, "providerId required");
        Objects.requireNonNull(mechanicId, "mechanicId required");
        operation = operation == null ? ProviderContributionOperation.ADD : operation;
        sourceType = sourceType == null ? "unknown" : sourceType;
        sourceId = sourceId == null ? mechanicId : sourceId;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }

    public String canonicalKey() {
        return providerId + ":" + mechanicId + ":" + operation + ":" + sourceType + ":" + sourceId;
    }
}
