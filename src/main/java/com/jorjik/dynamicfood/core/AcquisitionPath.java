package com.jorjik.dynamicfood.core;

import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;

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
    EconomicCostSchedule economicCostSchedule,
    PathIdentity declaredIdentity
) {
    private static final Set<String> MECHANICAL_IDENTITY_ATTRIBUTES = Set.of(
        "worldgen_block_id", "worldgen_requires_correct_tool", "block_loot_table",
        "extraction_operation", "extraction_source_path", "canonical_quantity_unit",
        "canonical_quantity_semantics", "recipe_type", "crop_cycle_classification", "trade_offer_id"
    );

    public AcquisitionPath(String itemId, String sourceType, String sourceId, double confidence,
        Double renewability, Double risk, Boolean repeatable, boolean hardFailed,
        Map<String, EconomicFactor> feasibilityFactors, Map<Integer, CostVector> costsByHorizon,
        AcquisitionEvidence evidence, EconomicCost economicCost, EconomicCostSchedule economicCostSchedule) {
        this(itemId, sourceType, sourceId, confidence, renewability, risk, repeatable, hardFailed,
            feasibilityFactors, costsByHorizon, evidence, economicCost, economicCostSchedule, null);
    }

    public AcquisitionPath(String itemId, String sourceType, String sourceId, double confidence,
        Double renewability, Double risk, Boolean repeatable, boolean hardFailed,
        Map<String, EconomicFactor> feasibilityFactors, Map<Integer, CostVector> costsByHorizon) {
        this(itemId, sourceType, sourceId, confidence, renewability, risk, repeatable, hardFailed,
            feasibilityFactors, costsByHorizon, AcquisitionEvidence.empty(),
            EconomicCost.unknown("acquisition path has no independently evidenced economic primitive"), null, null);
    }

    public AcquisitionPath(String itemId, String sourceType, String sourceId, double confidence,
        Double renewability, Double risk, Boolean repeatable, boolean hardFailed,
        Map<String, EconomicFactor> feasibilityFactors, Map<Integer, CostVector> costsByHorizon,
        AcquisitionEvidence evidence) {
        this(itemId, sourceType, sourceId, confidence, renewability, risk, repeatable, hardFailed,
            feasibilityFactors, costsByHorizon, evidence,
            EconomicCost.unknown("acquisition path has no independently evidenced economic primitive"), null, null);
    }

    public AcquisitionPath(String itemId, String sourceType, String sourceId, double confidence,
        Double renewability, Double risk, Boolean repeatable, boolean hardFailed,
        Map<String, EconomicFactor> feasibilityFactors, Map<Integer, CostVector> costsByHorizon,
        AcquisitionEvidence evidence, EconomicCost economicCost) {
        this(itemId, sourceType, sourceId, confidence, renewability, risk, repeatable, hardFailed,
            feasibilityFactors, costsByHorizon, evidence, economicCost, null, null);
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
        if (declaredIdentity != null && (!itemId.equals(declaredIdentity.resourceId())
            || !sourceType.equals(declaredIdentity.sourceType())
            || !sourceId.equals(declaredIdentity.sourceId()))) {
            throw new IllegalArgumentException("declared path identity must match resource and source identity");
        }
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

    public AcquisitionPath withEvidence(AcquisitionEvidence updatedEvidence) {
        return new AcquisitionPath(itemId, sourceType, sourceId, confidence, renewability, risk, repeatable,
            hardFailed, feasibilityFactors, costsByHorizon, updatedEvidence, economicCost, economicCostSchedule,
            declaredIdentity);
    }

    public AcquisitionPath withPathIdentity(PathIdentity identity) {
        return new AcquisitionPath(itemId, sourceType, sourceId, confidence, renewability, risk, repeatable,
            hardFailed, feasibilityFactors, costsByHorizon, evidence, economicCost, economicCostSchedule, identity);
    }

    public PathIdentity pathIdentity() {
        PathIdentity base = declaredIdentity == null
            ? PathIdentity.sourceOnly(itemId, sourceType, sourceId)
            : declaredIdentity;
        return base.withPathCycleAndEvidence(repeatable, renewability, mechanicalEvidenceKey(evidence));
    }

    private static String mechanicalEvidenceKey(AcquisitionEvidence evidence) {
        StringBuilder key = new StringBuilder();
        ArrayList<String> inputs = new ArrayList<>();
        evidence.inputs().forEach(input -> {
            StringBuilder inputKey = new StringBuilder();
            append(inputKey, input.alternatives().size());
            input.alternatives().forEach(value -> append(inputKey, value));
            append(inputKey, input.count());
            append(inputKey, input.inputUse().name());
            inputs.add(inputKey.toString());
        });
        inputs.sort(Comparator.naturalOrder());
        append(key, inputs.size());
        inputs.forEach(value -> append(key, value));

        Map<String, String> identityAttributes = new java.util.TreeMap<>();
        evidence.attributes().forEach((name, value) -> {
            if (isMechanicalIdentityAttribute(name)) {
                identityAttributes.put(name, value);
            }
        });
        append(key, identityAttributes.size());
        identityAttributes.forEach((name, value) -> {
            append(key, name);
            append(key, value);
        });
        append(key, evidence.worldgenCausalEvidence().mechanicalIdentityKey());
        return key.toString();
    }

    private static boolean isMechanicalIdentityAttribute(String name) {
        return name.startsWith("mechanical_identity.")
            || MECHANICAL_IDENTITY_ATTRIBUTES.contains(name)
            || name.startsWith("requirement_")
            || name.startsWith("satisfier_")
            || name.startsWith("capability_")
            || name.contains("conditional_branch")
            || name.contains("conditional_context");
    }

    private static void append(StringBuilder target, Object value) {
        String text = String.valueOf(value);
        target.append(text.length()).append(':').append(text);
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= 1.0D;
    }
}