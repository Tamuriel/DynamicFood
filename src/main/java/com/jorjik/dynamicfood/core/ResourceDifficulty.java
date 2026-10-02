package com.jorjik.dynamicfood.core;

import java.util.List;

public record ResourceDifficulty(
    String itemId,
    Double score,
    double confidence,
    EconomicCostResolution economicResolution,
    List<AcquisitionPathDiagnostic> paths,
    boolean manualOverride,
    Double manualOverrideValue,
    List<String> reasons
) {
    public ResourceDifficulty {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId is required");
        }
        if (score != null && (!Double.isFinite(score) || score < 0.0D || score > 5.0D)) {
            throw new IllegalArgumentException("resolved difficulty must be finite and in [0,5]");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0D || confidence > 1.0D) {
            throw new IllegalArgumentException("confidence must be finite and in [0,1]");
        }
        if (manualOverride != (manualOverrideValue != null)) {
            throw new IllegalArgumentException("manual override flag and value must agree");
        }
        paths = List.copyOf(paths);
        reasons = List.copyOf(reasons);
    }
}
