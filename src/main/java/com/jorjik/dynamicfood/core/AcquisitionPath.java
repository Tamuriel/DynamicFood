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
    AcquisitionEvidence evidence,
    EconomicCost economicCost,
    EconomicCostSchedule economicCostSchedule
) {
    public AcquisitionPath(String itemId, String sourceType, String sourceId, double confidence,
        Double renewability, Double risk, Boolean repeatable, boolean hardFailed,
        Map<String, EconomicFactor> feasibilityFactors, Map<Integer, CostVector> costsByHorizon) {
        this(itemId, sourceType, sourceId, confidence, renewability, risk, repeatable, hardFailed,
            feasibilityFactors, costsByHorizon, AcquisitionEvidence.empty(),
            EconomicCost.unknown("acquisition path has no independently evidenced economic primitive"), null);
    }

    public AcquisitionPath(String itemId, String sourceType, String sourceId, double confidence,
        Double renewability, Double risk, Boolean repeatable, boolean hardFailed,
        Map<String, EconomicFactor> feasibilityFactors, Map<Integer, CostVector> costsByHorizon,
        AcquisitionEvidence evidence) {
        this(itemId, sourceType, sourceId, confidence, renewability, risk, repeatable, hardFailed,
            feasibilityFactors, costsByHorizon, evidence,
            EconomicCost.unknown("acquisition path has no independently evidenced economic primitive"), null);
    }

    public AcquisitionPath(String itemId, String sourceType, String sourceId, double confidence,
        Double renewability, Double risk, Boolean repeatable, boolean hardFailed,
        Map<String, EconomicFactor> feasibilityFactors, Map<Integer, CostVector> costsByHorizon,
        AcquisitionEvidence evidence, EconomicCost economicCost) {
        this(itemId, sourceType, sourceId, confidence, renewability, risk, repeatable, hardFailed,
            feasibilityFactors, costsByHorizon, evidence, economicCost, null);
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
        economicCost = economicCost == null
            ? EconomicCost.unknown("acquisition path has no independently evidenced economic primitive")
            : economicCost;
    }

    public EconomicCost economicCostAt(int observationHorizon) {
        if (economicCostSchedule != null) {
            return economicCostSchedule.resolve(observationHorizon);
        }
        if (!economicCost.isKnown()) {
            return economicCost;
        }
        return economicCost.observationHorizon() == observationHorizon
            ? economicCost
            : EconomicCost.unknown("EconomicCost is evidenced at horizon "
                + economicCost.observationHorizon() + ", not " + observationHorizon);
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= 1.0D;
    }
}