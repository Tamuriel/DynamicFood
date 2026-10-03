package com.jorjik.dynamicfood.core;

import java.util.List;

public record RecipeEconomicResult(
    String itemId,
    EconomicCost target,
    ResolutionStatus status,
    List<String> recipePath,
    List<String> detectedCycles,
    List<String> missingInputs
) {
    public RecipeEconomicResult {
        if (target == null) {
            throw new IllegalArgumentException("economic target is required");
        }
        recipePath = List.copyOf(recipePath);
        detectedCycles = List.copyOf(detectedCycles);
        missingInputs = List.copyOf(missingInputs);
        if ((status == ResolutionStatus.UNKNOWN) == target.isKnown()) {
            throw new IllegalArgumentException("economic target and resolution status disagree");
        }
    }

    public Double economicCost() {
        return target.value();
    }
}
