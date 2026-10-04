package com.jorjik.dynamicfood.core;

import java.util.List;
import java.util.Map;

public record EstimateMetadata(
    String method,
    String evaluatorVersion,
    Map<String, String> parameters,
    List<String> assumptions
) {
    public EstimateMetadata {
        if (method == null || method.isBlank() || evaluatorVersion == null || evaluatorVersion.isBlank()) {
            throw new IllegalArgumentException("estimate method and evaluator version are required");
        }
        parameters = Map.copyOf(parameters);
        assumptions = List.copyOf(assumptions);
        if (parameters.entrySet().stream().anyMatch(entry ->
            entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null)) {
            throw new IllegalArgumentException("estimate parameters require non-empty keys and non-null values");
        }
        if (assumptions.stream().anyMatch(assumption -> assumption == null || assumption.isBlank())) {
            throw new IllegalArgumentException("estimate assumptions must be non-empty");
        }
    }
}
