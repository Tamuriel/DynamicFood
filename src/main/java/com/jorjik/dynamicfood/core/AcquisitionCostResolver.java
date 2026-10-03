package com.jorjik.dynamicfood.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class AcquisitionCostResolver {
    private AcquisitionCostResolver() {}

    public static AcquisitionCost resolve(CostVector vector, Map<String, Double> factorWeights) {
        double weightedCost = 0.0D;
        double knownWeight = 0.0D;
        double configuredWeight = 0.0D;
        Map<String, Double> used = new LinkedHashMap<>();
        Map<String, String> missing = new LinkedHashMap<>();
        Map<String, String> notApplicable = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : new TreeMap<>(factorWeights).entrySet()) {
            double weight = entry.getValue();
            if (!Double.isFinite(weight) || weight < 0.0D) {
                throw new IllegalArgumentException("factor weights must be finite and non-negative");
            }
            if (weight == 0.0D) {
                continue;
            }
            EconomicFactor factor = vector.factors().get(entry.getKey());
            if (factor != null && factor.isNotApplicable()) {
                notApplicable.put(entry.getKey(), factor.reason());
                continue;
            }
            configuredWeight += weight;
            if (factor == null || !factor.isKnown()) {
                missing.put(entry.getKey(), factor == null
                    ? "applicability was not reported by the acquisition analyzer" : factor.reason());
                continue;
            }
            weightedCost += factor.value() * weight;
            knownWeight += weight;
            used.put(entry.getKey(), factor.value());
        }
        if (configuredWeight <= 0.0D) {
            return new AcquisitionCost(null, ResolutionStatus.UNKNOWN, vector.economicHorizon(), used,
                missing, notApplicable);
        }
        if (knownWeight == 0.0D) {
            return new AcquisitionCost(null, ResolutionStatus.UNKNOWN, vector.economicHorizon(), used,
                missing, notApplicable);
        }
        ResolutionStatus status = knownWeight == configuredWeight ? ResolutionStatus.COMPLETE : ResolutionStatus.PARTIAL;
        return new AcquisitionCost(weightedCost / knownWeight, status, vector.economicHorizon(), used,
            missing, notApplicable);
    }
}