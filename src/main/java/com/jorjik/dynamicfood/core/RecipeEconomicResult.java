package com.jorjik.dynamicfood.core;

import java.util.List;

public record RecipeEconomicResult(
    String itemId,
    Double economicCost,
    ResolutionStatus status,
    List<String> recipePath,
    List<String> detectedCycles,
    List<String> missingInputs
) {
    public RecipeEconomicResult {
        recipePath = List.copyOf(recipePath);
        detectedCycles = List.copyOf(detectedCycles);
        missingInputs = List.copyOf(missingInputs);
        if (economicCost != null && (!Double.isFinite(economicCost) || economicCost < 0.0D || economicCost > 1.0D)) {
            throw new IllegalArgumentException("economic cost must be in [0,1]");
        }
    }
}