package com.jorjik.dynamicfood.core;

import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiFunction;

public final class EconomicSnapshotBuilder {
    public static final int SCHEMA_VERSION = 5;
    static final Comparator<AcquisitionPath> PATH_ORDER = Comparator
        .comparing(AcquisitionPath::itemId)
        .thenComparing(AcquisitionPath::sourceType)
        .thenComparing(AcquisitionPath::sourceId);

    private EconomicSnapshotBuilder() {}

    public static EconomicSnapshot build(
        long generation,
        Collection<String> resourceIds,
        Collection<? extends AcquisitionAnalyzer> analyzers,
        BiFunction<String, List<AcquisitionPath>, EconomicCostResolution> resolver
    ) {
        Objects.requireNonNull(resourceIds, "resourceIds");
        return build(generation, new EconomicSnapshotInputSet(
            new java.util.TreeSet<>(resourceIds), java.util.Set.of(), java.util.Set.of(),
            java.util.Set.of(), java.util.Set.of(), java.util.Set.of()), analyzers, resolver);
    }

    public static EconomicSnapshot build(
        long generation,
        EconomicSnapshotInputSet inputSet,
        Collection<? extends AcquisitionAnalyzer> analyzers,
        BiFunction<String, List<AcquisitionPath>, EconomicCostResolution> resolver
    ) {
        if (generation < 1) {
            throw new IllegalArgumentException("snapshot generation must be positive");
        }
        Objects.requireNonNull(inputSet, "inputSet");
        Objects.requireNonNull(analyzers, "analyzers");
        Objects.requireNonNull(resolver, "resolver");
        List<? extends AcquisitionAnalyzer> analyzerSnapshot = analyzers.stream()
            .map(analyzer -> Objects.requireNonNull(analyzer, "analyzer"))
            .toList();

        java.util.Set<String> analysisResourceIds = new java.util.TreeSet<>(inputSet.resourceIds());
        analyzerSnapshot.forEach(analyzer -> analysisResourceIds.addAll(analyzer.indexedItemIds()));
        Map<String, List<AcquisitionPath>> pathsByResource = new TreeMap<>();
        analysisResourceIds.forEach(resourceId -> {
                List<AcquisitionPath> paths = new ArrayList<>();
                for (AcquisitionAnalyzer analyzer : analyzerSnapshot) {
                    if (analyzer.supports(resourceId)) {
                        paths.addAll(analyzer.analyze(resourceId));
                    }
                }
                pathsByResource.put(resourceId, paths.stream().sorted(PATH_ORDER).toList());
            });

        Map<String, List<AcquisitionPath>> resolvedPaths =
            new SurvivalAcquirabilityResolver().resolvePathAvailability(pathsByResource);
        Map<String, EconomicSnapshot.ResourceResult> resources = new TreeMap<>();
        inputSet.resourceIds().stream().sorted().forEach(resourceId -> {
            List<AcquisitionPath> immutablePaths = resolvedPaths.getOrDefault(resourceId, List.of());
            EconomicCostResolution resolution = Objects.requireNonNull(
                resolver.apply(resourceId, immutablePaths), "resolver result");
            resources.put(resourceId,
                new EconomicSnapshot.ResourceResult(resourceId, immutablePaths, resolution));
        });

        String signature = signature(resources, inputSet);
        return new EconomicSnapshot(SCHEMA_VERSION, generation, signature, resources, inputSet);
    }

    // Generation is assigned by the future publication lifecycle; the signature fingerprints contents only.
    static String signature(Map<String, EconomicSnapshot.ResourceResult> resources) {
        return signature(resources, new EconomicSnapshotInputSet(resources.keySet(), java.util.Set.of(),
            java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of()));
    }

    static String signature(Map<String, EconomicSnapshot.ResourceResult> resources,
        EconomicSnapshotInputSet inputSet) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, Integer.toString(SCHEMA_VERSION));
        append(canonical, DynamicFoodConfig.economicPolicySignature());
        appendSet(canonical, inputSet.indexedResourceIds());
        appendSet(canonical, inputSet.configuredResourceIds());
        appendSet(canonical, inputSet.candidateResourceIds());
        appendSet(canonical, inputSet.recursiveDependencyIds());
        appendSet(canonical, inputSet.technicalCandidateExclusionIds());
        appendSet(canonical, inputSet.technicalDependencyIds());
        appendSet(canonical, inputSet.reusableInputIds());
        appendSet(canonical, inputSet.unresolvedUseInputIds());
        append(canonical, Integer.toString(resources.size()));
        new TreeMap<>(resources).forEach((resourceId, resource) -> {
            append(canonical, resourceId);
            append(canonical, Integer.toString(resource.acquisitionPaths().size()));
            resource.acquisitionPaths().stream().sorted(PATH_ORDER)
                .forEach(path -> appendPath(canonical, path));
            appendResolution(canonical, resource.economicResolution());
        });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for deterministic snapshot signatures", exception);
        }
    }

    private static void appendSet(StringBuilder target, java.util.Set<String> values) {
        append(target, Integer.toString(values.size()));
        values.stream().sorted().forEach(value -> append(target, value));
    }

    private static void appendPath(StringBuilder target, AcquisitionPath path) {
        append(target, path.itemId());
        append(target, path.sourceType());
        append(target, path.sourceId());
        append(target, Double.toHexString(path.confidence()));
        append(target, nullable(path.renewability()));
        append(target, nullable(path.risk()));
        append(target, path.repeatable() == null ? null : path.repeatable().toString());
        append(target, Boolean.toString(path.hardFailed()));
        append(target, Integer.toString(path.feasibilityFactors().size()));
        appendFactorMap(target, path.feasibilityFactors());
        append(target, Integer.toString(path.costsByHorizon().size()));
        path.costsByHorizon().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            append(target, Integer.toString(entry.getKey()));
            append(target, Integer.toString(entry.getValue().economicHorizon()));
            appendFactorMap(target, entry.getValue().factors());
        });
        append(target, Integer.toString(path.evidence().measurements().size()));
        path.evidence().measurements().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                append(target, entry.getKey());
                appendMeasurement(target, entry.getValue());
            });
        append(target, Integer.toString(path.evidence().attributes().size()));
        path.evidence().attributes().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                append(target, entry.getKey());
                append(target, entry.getValue());
            });
        append(target, Integer.toString(path.evidence().inputs().size()));
        path.evidence().inputs().forEach(input -> {
            append(target, Integer.toString(input.count()));
            append(target, input.inputUse().name());
            append(target, Integer.toString(input.alternatives().size()));
            input.alternatives().forEach(alternative -> append(target, alternative));
        });
        appendCost(target, path.economicCost());
        EconomicCostSchedule schedule = path.economicCostSchedule();
        append(target, schedule == null ? null : schedule.evidence());
        if (schedule != null) {
            appendComponent(target, schedule.startup());
            appendComponent(target, schedule.recurringPerOutput());
        }
    }

    private static void appendFactorMap(StringBuilder target, Map<String, EconomicFactor> factors) {
        append(target, Integer.toString(factors.size()));
        factors.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            append(target, entry.getKey());
            EconomicFactor factor = entry.getValue();
            append(target, factor.state().name());
            append(target, nullable(factor.value()));
            append(target, factor.reason());
        });
    }

    private static void appendMeasurement(StringBuilder target, AcquisitionMeasurement measurement) {
        append(target, nullable(measurement.value()));
        append(target, measurement.unknownReason());
        append(target, measurement.estimateKind().map(Enum::name).orElse(null));
        EstimateMetadata metadata = measurement.estimateMetadata().orElse(null);
        append(target, metadata == null ? null : metadata.method());
        append(target, metadata == null ? null : metadata.evaluatorVersion());
        if (metadata != null) {
            append(target, Integer.toString(metadata.parameters().size()));
            metadata.parameters().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                append(target, entry.getKey());
                append(target, entry.getValue());
            });
            append(target, Integer.toString(metadata.assumptions().size()));
            metadata.assumptions().forEach(value -> append(target, value));
        }
    }

    private static void appendResolution(StringBuilder target, EconomicCostResolution resolution) {
        appendCost(target, resolution.target());
        append(target, nullable(resolution.difficulty()));
        append(target, resolution.status().name());
        append(target, Integer.toString(resolution.economicHorizon()));
        AcquisitionPath primaryPath = resolution.primaryPath();
        append(target, primaryPath == null ? null : primaryPath.itemId());
        append(target, primaryPath == null ? null : primaryPath.sourceType());
        append(target, primaryPath == null ? null : primaryPath.sourceId());
        append(target, Integer.toString(resolution.alternatives().size()));
        resolution.alternatives().stream().sorted(PATH_ORDER)
            .forEach(path -> append(target, path.itemId() + "\u0000" + path.sourceType() + "\u0000" + path.sourceId()));
        append(target, Double.toHexString(resolution.confidence()));
        append(target, Integer.toString(resolution.reasons().size()));
        resolution.reasons().forEach(value -> append(target, value));
    }

    private static void appendCost(StringBuilder target, EconomicCost cost) {
        append(target, nullable(cost.value()));
        append(target, cost.primitiveId());
        append(target, cost.observationHorizon() == null ? null : cost.observationHorizon().toString());
        append(target, cost.evidence());
    }

    private static void appendComponent(StringBuilder target, EconomicCostComponent component) {
        append(target, component.state().name());
        append(target, nullable(component.amount()));
        append(target, component.primitiveId());
        append(target, component.evidence());
    }

    private static String nullable(Number number) {
        return number == null ? null : Double.toHexString(number.doubleValue());
    }

    private static void append(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }
    }
}
