package com.jorjik.dynamicfood.core;

public record EconomicFactor(FactorState state, Double value, String reason) {
    public EconomicFactor(Double normalizedValue, String reason) {
        this(normalizedValue == null ? FactorState.UNKNOWN : FactorState.KNOWN, normalizedValue, reason);
    }

    public EconomicFactor {
        if (state == null) {
            throw new IllegalArgumentException("factor state is required");
        }
        reason = reason == null || reason.isBlank() ? "unspecified" : reason;
        if (state == FactorState.KNOWN) {
            if (value == null || !Double.isFinite(value) || value < 0.0D || value > 1.0D) {
                throw new IllegalArgumentException("known normalized economic factors must be finite and in [0,1]");
            }
        } else if (value != null) {
            throw new IllegalArgumentException("unknown and not-applicable factors cannot have numeric values");
        }
    }

    public static EconomicFactor known(double normalizedValue) {
        return new EconomicFactor(FactorState.KNOWN, normalizedValue, "observed");
    }

    public static EconomicFactor unknown(String reason) {
        return new EconomicFactor(FactorState.UNKNOWN, null, reason);
    }

    public static EconomicFactor notApplicable(String reason) {
        return new EconomicFactor(FactorState.NOT_APPLICABLE, null, reason);
    }

    public boolean isKnown() {
        return state == FactorState.KNOWN;
    }

    public boolean isUnknown() {
        return state == FactorState.UNKNOWN;
    }

    public boolean isNotApplicable() {
        return state == FactorState.NOT_APPLICABLE;
    }

    public boolean isApplicable() {
        return state != FactorState.NOT_APPLICABLE;
    }
}