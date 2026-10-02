package com.jorjik.dynamicfood.core;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AcquisitionCostResolver {
    private AcquisitionCostResolver() {}

    public static AcquisitionCost resolve(CostVector vector, Map<String, Double> factorWeights) {
        double weightedCost = 0.0D;
        double knownWeight = 0.0D;
        double configuredWeight = 0.0D;
        Map<String, Double> used = new LinkedHashMap<>();
        Map<String, String> missing = new LinkedHashMap<>();
        EconomicFactor canonicalQuantity = vector.factors().get("quantity_cost");
        boolean quantityKnown = canonicalQuantity != null && canonicalQuantity.isKnown();
        for (Map.Entry<String, Double> entry : factorWeights.entrySet()) {
            double weight = entry.getValue();
            if (!Double.isFinite(weight) || weight < 0.0D) {
                throw new IllegalArgumentException("factor weights must be finite and non-negative");
            }
            if (weight == 0.0D) {
                continue;
            }
            if (quantityKnown && (entry.getKey().equals("probability_cost")
                || entry.getKey().equals("yield_cost"))) {
                missing.put(entry.getKey(), "diagnostic only: already represented by canonical quantity_cost");
                continue;
            }
            configuredWeight += weight;
            EconomicFactor factor = vector.factors().get(entry.getKey());
            if (factor == null || !factor.isKnown()) {
                missing.put(entry.getKey(), factor == null ? "factor not provided" : factor.reason());
                continue;
            }
            weightedCost += factor.value() * weight;
            knownWeight += weight;
            used.put(entry.getKey(), factor.value());
        }
        if (configuredWeight <= 0.0D) {
            throw new IllegalArgumentException("at least one acquisition cost factor weight must be positive");
        }
        if (knownWeight == 0.0D) {
            return new AcquisitionCost(null, ResolutionStatus.UNKNOWN, vector.economicHorizon(), used, missing);
        }
        ResolutionStatus status = knownWeight == configuredWeight ? ResolutionStatus.COMPLETE : ResolutionStatus.PARTIAL;
        return new AcquisitionCost(weightedCost / knownWeight, status, vector.economicHorizon(), used, missing);
    }
}