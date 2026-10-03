package com.jorjik.dynamicfood.core;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

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
        double configuredWeight = 0.0D;
        for (double weight : new TreeMap<>(factorWeights).values()) {
            configuredWeight += weight;
        }
        if (configuredWeight <= 0.0D) {
            throw new IllegalArgumentException("at least one feasibility factor weight must be positive");
        }
        double knownWeight = 0.0D;
        double applicableWeight = 0.0D;
        double weightedScore = 0.0D;
        ArrayList<String> missing = new ArrayList<>();
        ArrayList<String> notApplicable = new ArrayList<>();
        for (Map.Entry<String, Double> entry : new TreeMap<>(factorWeights).entrySet()) {
            if (entry.getValue() == 0.0D) {
                continue;
            }
            EconomicFactor factor = path.feasibilityFactors().get(entry.getKey());
            if (factor != null && factor.isNotApplicable()) {
                notApplicable.add(entry.getKey() + ": " + factor.reason());
                continue;
            }
            applicableWeight += entry.getValue();
            if (factor == null || !factor.isKnown()) {
                missing.add(entry.getKey() + (factor == null
                    ? ": applicability was not reported by the acquisition analyzer"
                    : ": " + factor.reason()));
                continue;
            }
            knownWeight += entry.getValue();
            weightedScore += factor.value() * entry.getValue();
        }
        if (applicableWeight == 0.0D) {
            return new FeasibilityResult(null, 1.0D, ResolutionStatus.UNKNOWN, false, false,
                missing, notApplicable);
        }
        double coverage = knownWeight / applicableWeight;
        if (knownWeight == 0.0D) {
            return new FeasibilityResult(null, coverage, ResolutionStatus.UNKNOWN, false, false,
                missing, notApplicable);
        }
        ResolutionStatus status = knownWeight == applicableWeight ? ResolutionStatus.COMPLETE : ResolutionStatus.PARTIAL;
        double score = weightedScore / knownWeight;
        boolean coverageEligible = coverage >= minimumCoverage || allowPartial;
        boolean eligible = coverageEligible && score >= minimumFeasibility && (status == ResolutionStatus.COMPLETE || allowPartial);
        return new FeasibilityResult(score, coverage, status, false, eligible, missing, notApplicable);
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