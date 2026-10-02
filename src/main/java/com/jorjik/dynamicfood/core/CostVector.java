package com.jorjik.dynamicfood.core;

import java.util.Map;

public record CostVector(int economicHorizon, Map<String, EconomicFactor> factors) {
    public CostVector {
        if (economicHorizon < 1) {
            throw new IllegalArgumentException("economicHorizon must be positive");
        }
        factors = Map.copyOf(factors);
    }
}