package com.jorjik.dynamicfood.core;

import java.util.Map;

public record AcquisitionCost(
    Double cost,
    ResolutionStatus status,
    int economicHorizon,
    Map<String, Double> normalizedFactors,
    Map<String, String> missingFactors,
    Map<String, String> notApplicableFactors
) {
    public AcquisitionCost(Double cost, ResolutionStatus status, int economicHorizon,
        Map<String, Double> normalizedFactors, Map<String, String> missingFactors) {
        this(cost, status, economicHorizon, normalizedFactors, missingFactors, Map.of());
    }

    public AcquisitionCost {
        if (status == ResolutionStatus.UNKNOWN) {
            cost = null;
        } else if (cost == null || !Double.isFinite(cost) || cost < 0.0D) {
            throw new IllegalArgumentException("resolved AcquisitionCost must be finite and non-negative");
        }
        if (economicHorizon < 1) {
            throw new IllegalArgumentException("economicHorizon must be positive");
        }
        normalizedFactors = Map.copyOf(normalizedFactors);
        missingFactors = Map.copyOf(missingFactors);
        notApplicableFactors = Map.copyOf(notApplicableFactors);
    }
}