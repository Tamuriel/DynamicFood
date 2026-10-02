package com.jorjik.dynamicfood.core;

public record EconomicFactor(Double value, String reason) {
    public EconomicFactor {
        reason = reason == null ? "unspecified" : reason;
        if (value != null && (!Double.isFinite(value) || value < 0.0D || value > 1.0D)) {
            throw new IllegalArgumentException("normalized economic factor must be finite and in [0,1]");
        }
    }

    public static EconomicFactor known(double normalizedValue) {
        return new EconomicFactor(normalizedValue, "observed");
    }

    public static EconomicFactor unknown(String reason) {
        return new EconomicFactor(null, reason);
    }

    public boolean isKnown() {
        return value != null;
    }
}