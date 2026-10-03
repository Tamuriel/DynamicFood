package com.jorjik.dynamicfood.core;

import java.util.List;

public record EconomicCostResolution(
    Double economicCost,
    Double difficulty,
    ResolutionStatus status,
    int economicHorizon,
    AcquisitionPath primaryPath,
    List<AcquisitionPath> alternatives,
    double confidence,
    List<String> reasons
) {
    public EconomicCostResolution {
        if (economicCost != null && (!Double.isFinite(economicCost) || economicCost < 0.0D)) {
            throw new IllegalArgumentException("economicCost must be finite and non-negative");
        }
        if (difficulty != null && (!Double.isFinite(difficulty) || difficulty < 0.0D || difficulty > 5.0D)) {
            throw new IllegalArgumentException("difficulty must be in [0,5]");
        }
        if (economicHorizon < 1 || !Double.isFinite(confidence) || confidence < 0.0D || confidence > 1.0D) {
            throw new IllegalArgumentException("invalid horizon or confidence");
        }
        alternatives = List.copyOf(alternatives);
        reasons = List.copyOf(reasons);
    }
}