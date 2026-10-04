package com.jorjik.dynamicfood.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable static economic results for one caller-assigned generation. */
public record EconomicSnapshot(
    int schemaVersion,
    long generation,
    String signature,
    Map<String, ResourceResult> resources,
    EconomicSnapshotInputSet inputSet
) {
    public EconomicSnapshot(int schemaVersion, long generation, String signature,
        Map<String, ResourceResult> resources) {
        this(schemaVersion, generation, signature, resources,
            new EconomicSnapshotInputSet(resources.keySet(), java.util.Set.of(), java.util.Set.of(),
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of()));
    }

    public EconomicSnapshot {
        if (schemaVersion < 1 || generation < 1 || signature == null || signature.isBlank()
            || inputSet == null) {
            throw new IllegalArgumentException("snapshot schema, generation, signature, and input set are required");
        }
        TreeMap<String, ResourceResult> ordered = new TreeMap<>(resources);
        ordered.forEach((resourceId, result) -> {
            if (!resourceId.equals(result.resourceId())) {
                throw new IllegalArgumentException("resource map key must match result identity");
            }
        });
        if (!ordered.keySet().equals(inputSet.resourceIds())) {
            throw new IllegalArgumentException("snapshot resources must exactly match its candidate/dependency input set");
        }
        if (!signature.equals(EconomicSnapshotBuilder.signature(ordered, inputSet))) {
            throw new IllegalArgumentException("snapshot signature does not match immutable economic contents");
        }
        resources = Collections.unmodifiableMap(ordered);
    }

    public void validateForCalibration() {
        if (schemaVersion != EconomicSnapshotBuilder.SCHEMA_VERSION || generation < 1) {
            throw new IllegalArgumentException("unsupported or invalid EconomicSnapshot schema/generation");
        }
        resources.forEach((resourceId, result) -> {
            if (!resourceId.equals(result.resourceId())
                || result.acquisitionPaths().stream().anyMatch(path -> !resourceId.equals(path.itemId()))) {
                throw new IllegalArgumentException("EconomicSnapshot contains inconsistent resource identities");
            }
            EconomicCost cost = result.economicResolution().target();
            if (result.economicResolution().status() == ResolutionStatus.UNKNOWN && cost.isKnown()
                || result.economicResolution().status() != ResolutionStatus.UNKNOWN && !cost.isKnown()) {
                throw new IllegalArgumentException("EconomicSnapshot contains inconsistent resolution status");
            }
        });
        if (!signature.equals(EconomicSnapshotBuilder.signature(resources, inputSet))) {
            throw new IllegalArgumentException("EconomicSnapshot content signature validation failed");
        }
    }

    public Optional<ResourceResult> resource(String resourceId) {
        return Optional.ofNullable(resources.get(resourceId));
    }

    public record ResourceResult(
        String resourceId,
        List<AcquisitionPath> acquisitionPaths,
        EconomicCostResolution economicResolution
    ) {
        public ResourceResult {
            if (resourceId == null || resourceId.isBlank() || economicResolution == null) {
                throw new IllegalArgumentException("resource identity and economic resolution are required");
            }
            acquisitionPaths = acquisitionPaths.stream()
                .sorted(EconomicSnapshotBuilder.PATH_ORDER)
                .toList();
            if (acquisitionPaths.stream().anyMatch(path -> !resourceId.equals(path.itemId()))) {
                throw new IllegalArgumentException("all acquisition paths must match the resource identity");
            }
        }

        public EconomicCost economicCost() {
            return economicResolution.target();
        }
    }
}
