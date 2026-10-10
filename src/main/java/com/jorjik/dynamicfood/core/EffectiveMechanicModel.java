package com.jorjik.dynamicfood.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record EffectiveMechanicModel(
    Map<String, Map<String, Object>> mechanics,
    Map<String, List<ProviderContribution>> history,
    List<String> conflicts,
    Map<String, List<String>> mechanicConflicts,
    Map<String, String> providerVersions
) {
    public EffectiveMechanicModel(Map<String, Map<String, Object>> mechanics,
        Map<String, List<ProviderContribution>> history, List<String> conflicts) {
        this(mechanics, history, conflicts, Map.of(), Map.of());
    }

    public EffectiveMechanicModel {
        mechanics = normalizeMap(mechanics);
        history = normalizeHistory(history);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        mechanicConflicts = normalizeConflicts(mechanicConflicts);
        providerVersions = providerVersions == null ? Map.of()
            : Collections.unmodifiableMap(new TreeMap<>(providerVersions));
    }

    public static EffectiveMechanicModel empty() {
        return new EffectiveMechanicModel(Map.of(), Map.of(), List.of(), Map.of(), Map.of());
    }

    public boolean hasMechanic(String mechanicId) {
        return mechanics.containsKey(mechanicId);
    }

    public Map<String, Object> mechanic(String mechanicId) {
        return mechanics.getOrDefault(mechanicId, Map.of());
    }

    public boolean hasContributions() {
        return !history.isEmpty();
    }

    public List<String> conflicts(String mechanicId) {
        return mechanicConflicts.getOrDefault(mechanicId, List.of());
    }

    public List<ProviderContribution> contributionsFor(AcquisitionPath path) {
        if (path == null) {
            return List.of();
        }
        return history.entrySet().stream().flatMap(entry -> entry.getValue().stream())
            .filter(contribution -> contribution.sourceType().equals(path.sourceType())
                && contribution.sourceId().equals(path.sourceId()))
            .sorted(java.util.Comparator.comparing(ProviderContribution::canonicalKey)
                .thenComparing(EffectiveMechanicModel::payloadSortKey))
            .toList();
    }

    public AcquisitionPath applyTo(AcquisitionPath path) {
        List<ProviderContribution> contributions = contributionsFor(path);
        if (contributions.isEmpty()) {
            return path;
        }

        Map<String, String> attributes = new TreeMap<>(path.evidence().attributes());
        Map<String, List<ProviderContribution>> byMechanic = contributions.stream()
            .collect(java.util.stream.Collectors.groupingBy(ProviderContribution::mechanicId,
                TreeMap::new, java.util.stream.Collectors.toList()));
        byMechanic.forEach((mechanicId, mechanicContributions) -> {
            String prefix = "provider_contribution." + mechanicId;
            List<String> failures = conflicts(mechanicId);
            if (!failures.isEmpty()) {
                attributes.put(prefix + ".status", "CONFLICT");
                attributes.put(prefix + ".conflict", String.join(" | ", failures));
                return;
            }
            Map<String, Object> effectiveMechanic = mechanics.get(mechanicId);
            if (effectiveMechanic == null) {
                attributes.put(prefix + ".status", "REMOVED");
            } else {
                attributes.put(prefix + ".status", "RESOLVED");
                ProviderContribution.canonicalPayloadObject(effectiveMechanic).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(payload -> attributes.put(prefix + ".effective_payload." + payload.getKey(),
                        payload.getValue().toString()));
            }
            for (int index = 0; index < mechanicContributions.size(); index++) {
                ProviderContribution contribution = mechanicContributions.get(index);
                String entry = prefix + ".contribution_" + index;
                attributes.put(entry + ".provider_id", contribution.providerId());
                attributes.put(entry + ".provider_version",
                    providerVersions.getOrDefault(contribution.providerId(), "UNKNOWN"));
                attributes.put(entry + ".operation", contribution.operation().name());
                attributes.put(entry + ".source_type", contribution.sourceType());
                attributes.put(entry + ".source_id", contribution.sourceId());
                contribution.provenance().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(provenance -> attributes.put(entry + ".provenance." + provenance.getKey(),
                        provenance.getValue()));
            }
        });
        return path.withEvidence(new AcquisitionEvidence(path.evidence().measurements(),
            attributes, path.evidence().inputs(), path.evidence().worldgenCausalEvidence()));
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

    private static Map<String, List<String>> normalizeConflicts(Map<String, List<String>> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<String>> normalized = new LinkedHashMap<>();
        new TreeMap<>(input).forEach((key, value) -> normalized.put(key,
            value == null ? List.of() : value.stream().distinct().sorted().toList()));
        return Collections.unmodifiableMap(normalized);
    }

    private static String payloadSortKey(ProviderContribution contribution) {
        try {
            return contribution.canonicalPayload();
        } catch (IllegalArgumentException exception) {
            return "!unsupported:" + exception.getMessage();
        }
    }
}
