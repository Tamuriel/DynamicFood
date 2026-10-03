package com.jorjik.dynamicfood.core;

import java.util.Objects;

public record ResourceEconomicProfile(
    String resourceId,
    String economicResourceIdentity,
    Double economicCost,
    double confidence,
    double calibrationWeight,
    boolean terminal,
    boolean calibrationEligible,
    SurvivalAcquirability survivalAcquirability,
    boolean technical,
    boolean derived
) {
    public ResourceEconomicProfile {
        Objects.requireNonNull(resourceId, "resourceId");
        economicResourceIdentity = economicResourceIdentity == null || economicResourceIdentity.isBlank()
            ? resourceId : economicResourceIdentity;
        Objects.requireNonNull(survivalAcquirability, "survivalAcquirability");
        if (economicCost != null && (!Double.isFinite(economicCost) || economicCost < 0.0D)) {
            throw new IllegalArgumentException("economicCost must be finite and non-negative");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0D || confidence > 1.0D) {
            throw new IllegalArgumentException("confidence must be finite and in [0,1]");
        }
        if (!Double.isFinite(calibrationWeight) || calibrationWeight <= 0.0D) {
            throw new IllegalArgumentException("calibrationWeight must be finite and greater than zero");
        }
    }

    public boolean isCalibrationCandidate() {
        return terminal && calibrationEligible && survivalAcquirability == SurvivalAcquirability.TRUE
            && !technical && !derived;
    }
}