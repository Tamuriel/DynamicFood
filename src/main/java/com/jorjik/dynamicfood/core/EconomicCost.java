package com.jorjik.dynamicfood.core;

import java.util.Objects;

public record EconomicCost(
    Double value,
    String primitiveId,
    Integer observationHorizon,
    String evidence
) {
    public EconomicCost {
        if (value == null) {
            if (primitiveId != null || observationHorizon != null) {
                throw new IllegalArgumentException("unknown economic cost cannot claim a primitive or horizon");
            }
            if (evidence == null || evidence.isBlank()) {
                throw new IllegalArgumentException("unknown economic cost requires an explanation");
            }
        } else {
            if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
                throw new IllegalArgumentException("economic cost must be finite and in [0,1]");
            }
            if (primitiveId == null || primitiveId.isBlank() || observationHorizon == null
                || observationHorizon < 1 || evidence == null || evidence.isBlank()) {
                throw new IllegalArgumentException("known economic cost requires primitive, horizon and evidence");
            }
            primitiveId = Objects.requireNonNull(primitiveId).trim();
        }
    }

    public static EconomicCost known(double value, String primitiveId, int observationHorizon, String evidence) {
        return new EconomicCost(value, primitiveId, observationHorizon, evidence);
    }

    public static EconomicCost unknown(String reason) {
        return new EconomicCost(null, null, null, reason);
    }

    public boolean isKnown() {
        return value != null;
    }

    public boolean hasCompatibleBasis(EconomicCost other) {
        return isKnown() && other != null && other.isKnown()
            && primitiveId.equals(other.primitiveId)
            && observationHorizon.equals(other.observationHorizon);
    }
}
