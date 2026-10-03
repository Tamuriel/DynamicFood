package com.jorjik.dynamicfood.core;

import java.util.List;

public record EconomicCostResolution(
    EconomicCost target,
    Double difficulty,
    ResolutionStatus status,
    int economicHorizon,
    AcquisitionPath primaryPath,
    List<AcquisitionPath> alternatives,
    double confidence,
    List<String> reasons
) {
    public EconomicCostResolution {
        if (target == null) {
            throw new IllegalArgumentException("economic target is required");
        }
        if (difficulty != null && (!Double.isFinite(difficulty) || difficulty < 0.0D || difficulty > 5.0D)) {
            throw new IllegalArgumentException("difficulty must be in [0,5]");
        }
        if (economicHorizon < 1 || !Double.isFinite(confidence) || confidence < 0.0D || confidence > 1.0D) {
            throw new IllegalArgumentException("invalid horizon or confidence");
        }
        if ((status == ResolutionStatus.UNKNOWN) == target.isKnown()) {
            throw new IllegalArgumentException("economic target and resolution status disagree");
        }
        alternatives = List.copyOf(alternatives);
        reasons = List.copyOf(reasons);
    }

    public Double economicCost() {
        return target.value();
    }
}
