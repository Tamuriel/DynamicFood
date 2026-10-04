package com.jorjik.dynamicfood.core;

import java.util.Map;

/** A path-level aggregate of normalized acquisition diagnostics at one observation horizon. */
public record AcquisitionCost(
    Double cost,
    ResolutionStatus status,
    int economicHorizon,
    Map<String, Double> normalizedFactors,
    Map<String, String> missingFactors,
    Map<String, String> notApplicableFactors,
    double additionalCoverage
) {
    public AcquisitionCost(Double cost, ResolutionStatus status, int economicHorizon,
        Map<String, Double> normalizedFactors, Map<String, String> missingFactors) {
        this(cost, status, economicHorizon, normalizedFactors, missingFactors, Map.of(), 1.0D);
    }

    public AcquisitionCost(Double cost, ResolutionStatus status, int economicHorizon,
        Map<String, Double> normalizedFactors, Map<String, String> missingFactors,
        Map<String, String> notApplicableFactors) {
        this(cost, status, economicHorizon, normalizedFactors, missingFactors, notApplicableFactors, 1.0D);
    }

    public AcquisitionCost {
        if (status == ResolutionStatus.UNKNOWN) {
            cost = null;
        } else if (cost == null || !Double.isFinite(cost) || cost < 0.0D || cost > 1.0D) {
            throw new IllegalArgumentException("resolved AcquisitionCost must be finite and in [0,1]");
        }
        if (economicHorizon < 1 || !Double.isFinite(additionalCoverage)
            || additionalCoverage < 0.0D || additionalCoverage > 1.0D) {
            throw new IllegalArgumentException("economic horizon must be positive and additional coverage in [0,1]");
        }
        normalizedFactors = Map.copyOf(normalizedFactors);
        missingFactors = Map.copyOf(missingFactors);
        notApplicableFactors = Map.copyOf(notApplicableFactors);
    }
}