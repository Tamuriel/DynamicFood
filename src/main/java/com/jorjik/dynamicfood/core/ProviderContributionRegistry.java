package com.jorjik.dynamicfood.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

public final class ProviderContributionRegistry {
    private final Map<String, MechanicProvider> providers = new TreeMap<>();

    public synchronized void register(MechanicProvider provider) {
        Objects.requireNonNull(provider, "provider is required");
        String providerId = requireText(provider.providerId(), "providerId");
        MechanicProvider existing = providers.get(providerId);
        if (existing != null && existing != provider) {
            throw new IllegalArgumentException("provider ID is already registered: " + providerId);
        }
        providers.putIfAbsent(providerId, provider);
    }

    public synchronized void clear() {
        providers.clear();
    }

    public synchronized EffectiveMechanicModel resolve() {
        Map<String, String> versions = new TreeMap<>();
        Map<String, List<ProviderContribution>> history = new TreeMap<>();
        Map<String, List<String>> mechanicConflicts = new TreeMap<>();

        for (MechanicProvider provider : providers.values()) {
            String providerId = requireText(provider.providerId(), "providerId");
            String version = requireText(provider.providerVersion(), "providerVersion for " + providerId);
            versions.put(providerId, version);
            Collection<ProviderContribution> contributions = Objects.requireNonNull(
                provider.contributions(), "provider contributions must not be null: " + providerId);
            List<ProviderContribution> ordered = contributions.stream()
                .map(contribution -> Objects.requireNonNull(contribution,
                    "provider contribution must not be null: " + providerId))
                .sorted(CONTRIBUTION_ORDER)
                .toList();
            for (ProviderContribution contribution : ordered) {
                if (!providerId.equals(contribution.providerId())) {
                    throw new IllegalArgumentException("contribution providerId does not match its provider: "
                        + contribution.canonicalKey());
                }
                history.computeIfAbsent(contribution.mechanicId(), ignored -> new ArrayList<>())
                    .add(contribution);
                try {
                    contribution.canonicalPayload();
                } catch (IllegalArgumentException exception) {
                    addConflict(mechanicConflicts, contribution.mechanicId(),
                        "unsupported contribution payload: " + exception.getMessage());
                }
            }
        }

        history.replaceAll((mechanicId, contributions) -> contributions.stream()
            .sorted(CONTRIBUTION_ORDER).toList());
        Map<String, Map<String, Object>> mechanics = new TreeMap<>();
        for (Map.Entry<String, List<ProviderContribution>> entry : history.entrySet()) {
            String mechanicId = entry.getKey();
            List<ProviderContribution> contributions = entry.getValue();
            List<String> conflicts = mechanicConflicts.computeIfAbsent(mechanicId, ignored -> new ArrayList<>());
            detectSourceConflicts(mechanicId, contributions, conflicts);
            detectPayloadConflicts(contributions, conflicts);

            if (!conflicts.isEmpty()) {
                conflicts.replaceAll(String::trim);
                conflicts.removeIf(String::isEmpty);
                conflicts.sort(String::compareTo);
                continue;
            }

            Map<String, Object> effective = new LinkedHashMap<>();
            boolean removed = false;
            for (ProviderContribution contribution : contributions) {
                switch (contribution.operation()) {
                    case ADD -> {
                        for (Map.Entry<String, Object> payload : contribution.payload().entrySet()) {
                            Object previous = effective.putIfAbsent(payload.getKey(), payload.getValue());
                            if (previous != null && !previous.equals(payload.getValue())) {
                                addConflict(mechanicConflicts, mechanicId,
                                    "conflicting ADD value for field " + payload.getKey());
                            }
                        }
                        removed = false;
                    }
                    case MODIFY -> {
                        effective.putAll(contribution.payload());
                        removed = false;
                    }
                    case REPLACE -> {
                        effective.clear();
                        effective.putAll(contribution.payload());
                        removed = false;
                    }
                    case REMOVE -> {
                        effective.clear();
                        removed = true;
                    }
                }
            }
            if (mechanicConflicts.getOrDefault(mechanicId, List.of()).isEmpty() && !removed) {
                mechanics.put(mechanicId, Collections.unmodifiableMap(new TreeMap<>(effective)));
            }
        }

        mechanicConflicts.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        mechanicConflicts.replaceAll((mechanicId, conflicts) -> conflicts.stream().distinct().sorted().toList());
        List<String> allConflicts = mechanicConflicts.entrySet().stream()
            .flatMap(entry -> entry.getValue().stream()
                .map(conflict -> entry.getKey() + ": " + conflict))
            .sorted().toList();

        return new EffectiveMechanicModel(
            Collections.unmodifiableMap(new TreeMap<>(mechanics)),
            Collections.unmodifiableMap(new LinkedHashMap<>(history)),
            allConflicts,
            mechanicConflicts,
            versions
        );
    }

    private static void detectSourceConflicts(String mechanicId, List<ProviderContribution> contributions,
        List<String> conflicts) {
        List<String> sources = contributions.stream()
            .map(contribution -> contribution.sourceType() + ":" + contribution.sourceId())
            .distinct().sorted().toList();
        if (sources.size() > 1) {
            conflicts.add("mechanic source conflict for " + mechanicId + " -> " + sources);
        }
    }

    private static void detectPayloadConflicts(List<ProviderContribution> contributions,
        List<String> conflicts) {
        Map<String, Map<String, String>> valuesByFieldAndProvider = new TreeMap<>();
        Map<String, Map<ProviderContributionOperation, String>> valuesByOperationAndProvider = new TreeMap<>();
        for (ProviderContribution contribution : contributions) {
            Map<String, com.google.gson.JsonElement> canonicalPayload;
            try {
                canonicalPayload = ProviderContribution.canonicalPayloadObject(contribution.payload()).asMap();
            } catch (IllegalArgumentException exception) {
                continue;
            }
            canonicalPayload.forEach((field, value) -> {
                valuesByFieldAndProvider.computeIfAbsent(field, ignored -> new TreeMap<>())
                    .put(contribution.providerId(), value.toString());
                valuesByOperationAndProvider.computeIfAbsent(field, ignored -> new TreeMap<>())
                    .put(contribution.operation(), value.toString());
            });
        }
        valuesByFieldAndProvider.forEach((field, providerValues) -> {
            if (new TreeSet<>(providerValues.values()).size() > 1) {
                conflicts.add("provider contributions disagree on field " + field);
            }
        });

        Map<ProviderContributionOperation, List<ProviderContribution>> byOperation =
            new TreeMap<>(Comparator.comparingInt(ProviderContributionRegistry::operationOrder));
        contributions.forEach(contribution -> byOperation
            .computeIfAbsent(contribution.operation(), ignored -> new ArrayList<>()).add(contribution));
        if (byOperation.containsKey(ProviderContributionOperation.REMOVE)
            && byOperation.keySet().stream().anyMatch(operation -> operation != ProviderContributionOperation.REMOVE)) {
            conflicts.add("REMOVE conflicts with another operation because no precedence was declared");
        }
        if (byOperation.getOrDefault(ProviderContributionOperation.ADD, List.of()).size() > 1
            && hasDifferentPayloads(byOperation.get(ProviderContributionOperation.ADD))) {
            conflicts.add("multiple ADD contributions disagree");
        }
        if (byOperation.getOrDefault(ProviderContributionOperation.REPLACE, List.of()).size() > 1
            && hasDifferentPayloads(byOperation.get(ProviderContributionOperation.REPLACE))) {
            conflicts.add("multiple REPLACE contributions disagree");
        }
    }

    private static boolean hasDifferentPayloads(List<ProviderContribution> contributions) {
        return contributions.stream().map(ProviderContribution::canonicalPayload).distinct().count() > 1;
    }

    private static int operationOrder(ProviderContributionOperation operation) {
        return switch (operation) {
            case ADD -> 0;
            case MODIFY -> 1;
            case REPLACE -> 2;
            case REMOVE -> 3;
        };
    }

    private static void addConflict(Map<String, List<String>> conflicts, String mechanicId, String reason) {
        conflicts.computeIfAbsent(mechanicId, ignored -> new ArrayList<>()).add(reason);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
        return value;
    }

    private static final Comparator<ProviderContribution> CONTRIBUTION_ORDER =
        Comparator.comparing(ProviderContribution::canonicalKey)
            .thenComparing(ProviderContributionRegistry::payloadSortKey);

    private static String payloadSortKey(ProviderContribution contribution) {
        try {
            return contribution.canonicalPayload();
        } catch (IllegalArgumentException exception) {
            return "!unsupported:" + exception.getMessage();
        }
    }
}
