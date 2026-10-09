package com.jorjik.dynamicfood.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class AcquisitionCostResolver {
    private AcquisitionCostResolver() {}

    public static AcquisitionCost resolve(CostVector vector, Map<String, Double> factorWeights) {
        if (vector == null || factorWeights == null) {
            throw new IllegalArgumentException("cost vector and factor weights are required");
        }
        factorWeights.forEach((name, weight) -> {
            if (name == null || weight == null || !Double.isFinite(weight) || weight < 0.0D) {
                throw new IllegalArgumentException("factor weights must be finite and non-negative");
            }
            if (EconomicChannel.fromId(name).isEmpty()) {
                throw new IllegalArgumentException("unsupported economic policy factor: " + name);
            }
        });
        double maxIncludedWeight = 0.0D;
        Map<String, Double> used = new LinkedHashMap<>();
        Map<String, String> missing = new LinkedHashMap<>();
        Map<String, String> notApplicable = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : new TreeMap<>(factorWeights).entrySet()) {
            double weight = entry.getValue();
            if (!Double.isFinite(weight) || weight < 0.0D) {
                throw new IllegalArgumentException("factor weights must be finite and non-negative");
            }
            EconomicFactor factor = EconomicChannel.fromId(entry.getKey())
                .map(vector::factor).orElse(null);
            if (factor != null && factor.isNotApplicable()) {
                notApplicable.put(entry.getKey(), factor.reason());
                continue;
            }
            if (factor == null || factor.isUnknown()) {
                missing.put(entry.getKey(), factor == null
                    ? "applicability was not reported by the acquisition analyzer" : factor.reason());
                continue;
            }
            used.put(entry.getKey(), factor.value());
            maxIncludedWeight = Math.max(maxIncludedWeight, weight);
        }
        Map<String, String> unknownCore = vector.unknownCoreFactors();
        unknownCore.forEach(missing::putIfAbsent);
        if (!unknownCore.isEmpty() || maxIncludedWeight <= 0.0D) {
            return new AcquisitionCost(null, ResolutionStatus.UNKNOWN, vector.economicHorizon(), used,
                missing, notApplicable);
        }
        double weightedCost = 0.0D;
        double includedWeight = 0.0D;
        for (Map.Entry<String, Double> entry : used.entrySet()) {
            double relativeWeight = factorWeights.get(entry.getKey()) / maxIncludedWeight;
            weightedCost += entry.getValue() * relativeWeight;
            includedWeight += relativeWeight;
        }
        return new AcquisitionCost(weightedCost / includedWeight, ResolutionStatus.COMPLETE,
            vector.economicHorizon(), used, missing, notApplicable);
    }
}