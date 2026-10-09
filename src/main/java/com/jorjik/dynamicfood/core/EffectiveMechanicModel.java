package com.jorjik.dynamicfood.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EffectiveMechanicModel(
    Map<String, Map<String, Object>> mechanics,
    Map<String, List<ProviderContribution>> history,
    List<String> conflicts
) {
    public EffectiveMechanicModel {
        mechanics = normalizeMap(mechanics);
        history = normalizeHistory(history);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public static EffectiveMechanicModel empty() {
        return new EffectiveMechanicModel(Map.of(), Map.of(), List.of());
    }

    public boolean hasMechanic(String mechanicId) {
        return mechanics.containsKey(mechanicId);
    }

    public Map<String, Object> mechanic(String mechanicId) {
        return mechanics.getOrDefault(mechanicId, Map.of());
    }

    private static Map<String, Map<String, Object>> normalizeMap(Map<String, Map<String, Object>> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Map<String, Object>> normalized = new LinkedHashMap<>();
        input.forEach((key, value) -> normalized.put(key, value == null ? Map.of() : Map.copyOf(value)));
        return Collections.unmodifiableMap(normalized);
    }

    private static Map<String, List<ProviderContribution>> normalizeHistory(Map<String, List<ProviderContribution>> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<ProviderContribution>> normalized = new LinkedHashMap<>();
        input.forEach((key, value) -> normalized.put(key, value == null ? List.of() : List.copyOf(value)));
        return Collections.unmodifiableMap(normalized);
    }
}
