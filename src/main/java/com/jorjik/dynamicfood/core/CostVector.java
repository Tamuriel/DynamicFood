package com.jorjik.dynamicfood.core;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public record CostVector(int economicHorizon, Map<String, EconomicFactor> factors) {
    public static final Set<String> CORE_FACTORS = Set.of(
        "quantity_cost", "time_cost", "material_cost", "equipment_cost");
    public static final Set<String> ADDITIONAL_FACTORS = Set.of(
        "progression_cost", "prerequisite_cost", "intermediate_cost", "danger_cost",
        "transport_cost", "resource_consumption_cost");

    public CostVector {
        if (economicHorizon < 1) {
            throw new IllegalArgumentException("economicHorizon must be positive");
        }
        factors = Map.copyOf(factors);
        if (factors.keySet().stream().anyMatch(name ->
            !CORE_FACTORS.contains(name) && !ADDITIONAL_FACTORS.contains(name))) {
            throw new IllegalArgumentException("CostVector contains a non-policy economic factor");
        }
    }

    public Map<String, String> unknownCoreFactors() {
        Map<String, String> unknown = new TreeMap<>();
        for (String name : CORE_FACTORS) {
            EconomicFactor factor = factors.get(name);
            if (factor == null || factor.isUnknown()) {
                unknown.put(name, factor == null
                    ? "core factor applicability was not reported"
                    : factor.reason());
            }
        }
        return Map.copyOf(unknown);
    }

    public boolean isCoreComplete() {
        return unknownCoreFactors().isEmpty();
    }

    public double additionalCoverage(Map<String, Double> factorWeights) {
        double maxWeight = 0.0D;
        for (String name : ADDITIONAL_FACTORS) {
            double weight = factorWeights.getOrDefault(name, 0.0D);
            EconomicFactor factor = factors.get(name);
            if (weight <= 0.0D || factor != null && factor.isNotApplicable()) {
                continue;
            }
            maxWeight = Math.max(maxWeight, weight);
        }
        if (maxWeight == 0.0D) {
            return 1.0D;
        }
        double applicableWeight = 0.0D;
        double knownWeight = 0.0D;
        for (String name : ADDITIONAL_FACTORS) {
            double weight = factorWeights.getOrDefault(name, 0.0D);
            EconomicFactor factor = factors.get(name);
            if (weight <= 0.0D || factor != null && factor.isNotApplicable()) {
                continue;
            }
            double scaledWeight = weight / maxWeight;
            applicableWeight += scaledWeight;
            if (factor != null && factor.isKnown()) {
                knownWeight += scaledWeight;
            }
        }
        return knownWeight / applicableWeight;
    }
}