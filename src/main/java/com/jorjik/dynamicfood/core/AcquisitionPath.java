package com.jorjik.dynamicfood.core;

import java.util.Map;

public record AcquisitionPath(
    String itemId,
    String sourceType,
    String sourceId,
    double confidence,
    Double renewability,
    Double risk,
    Boolean repeatable,
    boolean hardFailed,
    Map<String, EconomicFactor> feasibilityFactors,
    Map<Integer, CostVector> costsByHorizon,
    AcquisitionEvidence evidence
) {
    public AcquisitionPath(String itemId, String sourceType, String sourceId, double confidence,
        Double renewability, Double risk, Boolean repeatable, boolean hardFailed,
        Map<String, EconomicFactor> feasibilityFactors, Map<Integer, CostVector> costsByHorizon) {
        this(itemId, sourceType, sourceId, confidence, renewability, risk, repeatable, hardFailed,
            feasibilityFactors, costsByHorizon, AcquisitionEvidence.empty());
    }

    public AcquisitionPath {
        if (itemId == null || sourceType == null || sourceId == null) {
            throw new IllegalArgumentException("acquisition path identity fields are required");
        }
        if (!unit(confidence) || renewability != null && !unit(renewability) || risk != null && !unit(risk)) {
            throw new IllegalArgumentException("known confidence, renewability and risk must be in [0,1]");
        }
        feasibilityFactors = Map.copyOf(feasibilityFactors);
        costsByHorizon = Map.copyOf(costsByHorizon);
        evidence = evidence == null ? AcquisitionEvidence.empty() : evidence;
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= 1.0D;
    }
}