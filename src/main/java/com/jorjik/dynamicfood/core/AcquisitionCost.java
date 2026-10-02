package com.jorjik.dynamicfood.core;

import java.util.Map;

public record AcquisitionCost(
    Double cost,
    ResolutionStatus status,
    int economicHorizon,
    Map<String, Double> normalizedFactors,
    Map<String, String> missingFactors
) {
    public AcquisitionCost {
        if (status == ResolutionStatus.UNKNOWN) {
            cost = null;
        } else if (cost == null || !Double.isFinite(cost) || cost < 0.0D || cost > 1.0D) {
            throw new IllegalArgumentException("resolved AcquisitionCost must be in [0,1]");
        }
        if (economicHorizon < 1) {
            throw new IllegalArgumentException("economicHorizon must be positive");
        }
        normalizedFactors = Map.copyOf(normalizedFactors);
        missingFactors = Map.copyOf(missingFactors);
    }
}