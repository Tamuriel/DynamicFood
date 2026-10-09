package com.jorjik.dynamicfood.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class ProviderContributionRegistry {
    private final List<MechanicProvider> providers = new ArrayList<>();

    public synchronized void register(MechanicProvider provider) {
        Objects.requireNonNull(provider, "provider is required");
        if (providers.stream().anyMatch(existing -> existing.providerId().equals(provider.providerId()))) {
            return;
        }
        providers.add(provider);
    }

    public synchronized void clear() {
        providers.clear();
    }

    public synchronized EffectiveMechanicModel resolve() {
        Map<String, Map<String, Object>> mechanics = new TreeMap<>();
        Map<String, List<ProviderContribution>> history = new TreeMap<>();
        List<String> conflicts = new ArrayList<>();
        List<ProviderContribution> allContributions = new ArrayList<>();
        for (MechanicProvider provider : providers.stream()
            .sorted(Comparator.comparing(MechanicProvider::providerId)).toList()) {
            provider.contributions().stream()
                .sorted(Comparator.comparing(ProviderContribution::canonicalKey))
                .forEach(allContributions::add);
        }

        for (ProviderContribution contribution : allContributions) {
            String mechanicId = contribution.mechanicId();
            history.computeIfAbsent(mechanicId, ignored -> new ArrayList<>()).add(contribution);
            Map<String, Object> current = mechanics.get(mechanicId);
            switch (contribution.operation()) {
                case ADD -> {
                    if (current == null) {
                        mechanics.put(mechanicId, new LinkedHashMap<>(contribution.payload()));
                    } else {
                        boolean compatible = true;
                        for (Map.Entry<String, Object> entry : contribution.payload().entrySet()) {
                            Object existing = current.get(entry.getKey());
                            if (existing != null && !existing.equals(entry.getValue())) {
                                compatible = false;
                                break;
                            }
                        }
                        if (!compatible) {
                            conflicts.add("duplicate-add conflict for " + mechanicId + ": " + current + " vs " + contribution.payload());
                        } else {
                            current.putAll(contribution.payload());
                        }
                    }
                }
                case REPLACE -> mechanics.put(mechanicId, new LinkedHashMap<>(contribution.payload()));
                case REMOVE -> {
                    mechanics.remove(mechanicId);
                    if (current != null && !current.isEmpty()) {
                        history.computeIfAbsent(mechanicId, ignored -> new ArrayList<>()).add(contribution);
                    }
                }
                case MODIFY -> {
                    LinkedHashMap<String, Object> merged = current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
                    merged.putAll(contribution.payload());
                    mechanics.put(mechanicId, merged);
                }
                default -> throw new IllegalStateException("unsupported operation: " + contribution.operation());
            }
        }

        for (Map.Entry<String, List<ProviderContribution>> entry : history.entrySet()) {
            Map<String, Object> state = mechanics.get(entry.getKey());
            if (state == null || state.isEmpty()) {
                continue;
            }
            List<String> sourceKeys = entry.getValue().stream()
                .map(contribution -> contribution.sourceType() + ":" + contribution.sourceId())
                .distinct()
                .sorted()
                .toList();
            if (sourceKeys.size() > 1) {
                conflicts.add("mechanic source conflict for " + entry.getKey() + " -> " + sourceKeys);
            }
        }

        return new EffectiveMechanicModel(
            Collections.unmodifiableMap(new TreeMap<>(mechanics)),
            Collections.unmodifiableMap(history.entrySet().stream().collect(
                LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), List.copyOf(entry.getValue())),
                LinkedHashMap::putAll
            )),
            List.copyOf(conflicts)
        );
    }
}
