package com.jorjik.dynamicfood.core;

import java.util.ArrayList;
import java.util.Map;

public final class FeasibilityResolver {
    private FeasibilityResolver() {}

    public static FeasibilityResult resolve(AcquisitionPath path, Map<String, Double> factorWeights,
        double minimumCoverage, double minimumFeasibility, boolean allowPartial) {
        validateWeights(factorWeights);
        if (!unit(minimumCoverage) || !unit(minimumFeasibility)) {
            throw new IllegalArgumentException("feasibility thresholds must be in [0,1]");
        }
        if (path.hardFailed()) {
            return new FeasibilityResult(0.0D, 1.0D, ResolutionStatus.COMPLETE, true, false, java.util.List.of());
        }
        double configuredWeight = factorWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (configuredWeight <= 0.0D) {
            throw new IllegalArgumentException("at least one feasibility factor weight must be positive");
        }
        double knownWeight = 0.0D;
        double weightedScore = 0.0D;
        ArrayList<String> missing = new ArrayList<>();
        for (Map.Entry<String, Double> entry : factorWeights.entrySet()) {
            if (entry.getValue() == 0.0D) {
                continue;
            }
            EconomicFactor factor = path.feasibilityFactors().get(entry.getKey());
            if (factor == null || !factor.isKnown()) {
                missing.add(entry.getKey());
                continue;
            }
            knownWeight += entry.getValue();
            weightedScore += factor.value() * entry.getValue();
        }
        double coverage = knownWeight / configuredWeight;
        if (knownWeight == 0.0D) {
            return new FeasibilityResult(null, coverage, ResolutionStatus.UNKNOWN, false, false, missing);
        }
        ResolutionStatus status = knownWeight == configuredWeight ? ResolutionStatus.COMPLETE : ResolutionStatus.PARTIAL;
        double score = weightedScore / knownWeight;
        boolean coverageEligible = coverage >= minimumCoverage || allowPartial;
        boolean eligible = coverageEligible && score >= minimumFeasibility && (status == ResolutionStatus.COMPLETE || allowPartial);
        return new FeasibilityResult(score, coverage, status, false, eligible, missing);
    }

    private static void validateWeights(Map<String, Double> weights) {
        for (double weight : weights.values()) {
            if (!Double.isFinite(weight) || weight < 0.0D) {
                throw new IllegalArgumentException("feasibility weights must be finite and non-negative");
            }
        }
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= 1.0D;
    }
}