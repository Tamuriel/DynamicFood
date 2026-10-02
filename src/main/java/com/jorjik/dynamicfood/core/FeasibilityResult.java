package com.jorjik.dynamicfood.core;

import java.util.List;

public record FeasibilityResult(
    Double feasibility,
    double coverage,
    ResolutionStatus status,
    boolean hardFailed,
    boolean eligibleForPrimary,
    List<String> missingFactors
) {
    public FeasibilityResult {
        missingFactors = List.copyOf(missingFactors);
        if (feasibility != null && (!Double.isFinite(feasibility) || feasibility < 0.0D || feasibility > 1.0D)) {
            throw new IllegalArgumentException("feasibility must be in [0,1]");
        }
        if (!Double.isFinite(coverage) || coverage < 0.0D || coverage > 1.0D) {
            throw new IllegalArgumentException("feasibility coverage must be in [0,1]");
        }
    }
}