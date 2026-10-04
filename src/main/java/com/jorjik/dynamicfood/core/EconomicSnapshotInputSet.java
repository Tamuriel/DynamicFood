package com.jorjik.dynamicfood.core;

import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import com.jorjik.dynamicfood.graph.RecipeGraph;
import com.jorjik.dynamicfood.graph.RecipeNode;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/** The provisional economic resource boundary before Phase 03 population classification. */
public record EconomicSnapshotInputSet(
    Set<String> indexedResourceIds,
    Set<String> configuredResourceIds,
    Set<String> candidateResourceIds,
    Set<String> recursiveDependencyIds,
    Set<String> technicalCandidateExclusionIds,
    Set<String> technicalDependencyIds,
    Set<String> reusableInputIds,
    Set<String> unresolvedUseInputIds
) {
    public EconomicSnapshotInputSet(
        Set<String> candidateResourceIds,
        Set<String> recursiveDependencyIds,
        Set<String> technicalCandidateExclusionIds,
        Set<String> technicalDependencyIds,
        Set<String> reusableInputIds,
        Set<String> unresolvedUseInputIds
    ) {
        this(candidateResourceIds, Set.of(), candidateResourceIds, recursiveDependencyIds,
            technicalCandidateExclusionIds, technicalDependencyIds, reusableInputIds, unresolvedUseInputIds);
    }

    public EconomicSnapshotInputSet {
        indexedResourceIds = immutableSorted(indexedResourceIds);
        configuredResourceIds = immutableSorted(configuredResourceIds);
        candidateResourceIds = immutableSorted(candidateResourceIds);
        recursiveDependencyIds = immutableSorted(recursiveDependencyIds);
        technicalCandidateExclusionIds = immutableSorted(technicalCandidateExclusionIds);
        technicalDependencyIds = immutableSorted(technicalDependencyIds);
        reusableInputIds = immutableSorted(reusableInputIds);
        unresolvedUseInputIds = immutableSorted(unresolvedUseInputIds);
        boolean candidatesHaveSource = true;
        for (String resourceId : candidateResourceIds) {
            candidatesHaveSource &= indexedResourceIds.contains(resourceId)
                || configuredResourceIds.contains(resourceId);
        }
        if (!candidateResourceIds.containsAll(configuredResourceIds)
            || !candidatesHaveSource
            || !indexedResourceIds.containsAll(technicalCandidateExclusionIds)
            || !Collections.disjoint(candidateResourceIds, technicalCandidateExclusionIds)) {
            throw new IllegalArgumentException("candidate and technical-exclusion roles must match indexed/configured inputs");
        }
        if (!recursiveDependencyIds.containsAll(technicalDependencyIds)) {
            throw new IllegalArgumentException("technical dependency classifications must be recipe dependencies");
        }
    }

    public static EconomicSnapshotInputSet derive(
        Collection<String> discoveredResourceIds,
        Collection<String> configuredResourceIds,
        RecipeGraph graph,
        Predicate<String> technicalResource
    ) {
        if (discoveredResourceIds == null || configuredResourceIds == null || graph == null
            || technicalResource == null) {
            throw new IllegalArgumentException("discovery, configuration, graph, and technical classifier are required");
        }

        Set<String> indexed = new TreeSet<>();
        discoveredResourceIds.forEach(resourceId -> {
            requireResourceId(resourceId);
            indexed.add(resourceId);
        });
        Set<String> configured = new TreeSet<>();
        configuredResourceIds.forEach(resourceId -> {
            requireResourceId(resourceId);
            configured.add(resourceId);
        });
        Set<String> candidateIds = new TreeSet<>();
        Set<String> excludedTechnical = new TreeSet<>();
        for (String resourceId : indexed) {
            if (!configured.contains(resourceId) && technicalResource.test(resourceId)) {
                excludedTechnical.add(resourceId);
            } else {
                candidateIds.add(resourceId);
            }
        }
        for (String resourceId : configured) {
            requireResourceId(resourceId);
            candidateIds.add(resourceId);
        }

        Set<String> dependencies = new TreeSet<>();
        Set<String> reusableInputs = new TreeSet<>();
        Set<String> unresolvedUseInputs = new TreeSet<>();
        Set<String> visited = new TreeSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(candidateIds);
        while (!pending.isEmpty()) {
            String resultId = pending.removeFirst();
            if (!visited.add(resultId)) {
                continue;
            }
            for (RecipeNode recipe : graph.recipesFor(resultId)) {
                for (AcquisitionIngredient ingredient : recipe.acquisitionIngredients()) {
                    switch (ingredient.inputUse()) {
                        case CONSUMED -> ingredient.alternatives().forEach(inputId -> {
                            dependencies.add(inputId);
                            if (!visited.contains(inputId)) {
                                pending.addLast(inputId);
                            }
                        });
                        case REUSABLE -> reusableInputs.addAll(ingredient.alternatives());
                        case UNKNOWN -> unresolvedUseInputs.addAll(ingredient.alternatives());
                    }
                }
            }
        }
        Set<String> technicalDependencies = new TreeSet<>();
        dependencies.stream().filter(technicalResource).forEach(technicalDependencies::add);
        return new EconomicSnapshotInputSet(indexed, configured, candidateIds, dependencies,
            excludedTechnical, technicalDependencies, reusableInputs, unresolvedUseInputs);
    }

    public Set<String> resourceIds() {
        Set<String> resourceIds = new TreeSet<>(candidateResourceIds);
        resourceIds.addAll(recursiveDependencyIds);
        return Collections.unmodifiableSet(resourceIds);
    }

    public int dependencyOnlyCount() {
        return (int) recursiveDependencyIds.stream()
            .filter(resourceId -> !candidateResourceIds.contains(resourceId))
            .count();
    }

    private static Set<String> immutableSorted(Collection<String> values) {
        if (values == null) {
            throw new IllegalArgumentException("snapshot input classifications are required");
        }
        TreeSet<String> sorted = new TreeSet<>();
        values.forEach(EconomicSnapshotInputSet::requireResourceId);
        sorted.addAll(values);
        return Collections.unmodifiableSet(sorted);
    }

    private static void requireResourceId(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("snapshot resource IDs must be non-empty");
        }
    }
}
