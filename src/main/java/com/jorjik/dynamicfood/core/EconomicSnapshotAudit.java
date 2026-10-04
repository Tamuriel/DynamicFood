package com.jorjik.dynamicfood.core;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;

/** Bounded aggregate diagnostics for one published economic/calibration generation. */
public record EconomicSnapshotAudit(
    int totalResources,
    int resolvedResources,
    int completeResources,
    int partialResources,
    int unknownResources,
    int notApplicableResources,
    Map<String, Integer> resolvedBySource,
    Map<String, Integer> unknownByRootCause,
    int calibrationCandidates,
    int calibratedResources,
    double calibrationCoverage,
    ResourceScopeAudit resourceScope,
    Map<String, String> resolvedExamples,
    List<String> unknownExamples
) {
    private static final List<String> SOURCES =
        List.of("explicit_override", "recipe", "loot", "crop", "worldgen", "trade", "other");
    private static final int MAX_EXAMPLES_PER_ROOT_CAUSE = 3;
    private static final int MAX_EXAMPLE_LENGTH = 900;

    public EconomicSnapshotAudit {
        resolvedBySource = Collections.unmodifiableMap(new TreeMap<>(resolvedBySource));
        unknownByRootCause = Collections.unmodifiableMap(new TreeMap<>(unknownByRootCause));
        resolvedExamples = Collections.unmodifiableMap(new TreeMap<>(resolvedExamples));
        unknownExamples = List.copyOf(unknownExamples);
    }

    public record ResourceScopeAudit(
        int indexedAcquisitionResources,
        int configuredResources,
        int directCandidates,
        int recursiveDependencies,
        int technicalExcludedFromCandidates,
        int technicalDependencies,
        int containerInfrastructureResources,
        int damageableTechnicalResources,
        int machineInfrastructureResources,
        int itemContainerResources,
        int reusableInputs,
        int unresolvedUseInputs,
        int pureDerivedResources,
        int survivalFalseResources,
        int survivalUnknownResources,
        int unsupportedPotentialResources,
        Map<String, List<String>> examples
    ) {
        public ResourceScopeAudit {
            Map<String, List<String>> immutableExamples = new TreeMap<>();
            examples.forEach((category, values) -> immutableExamples.put(category, List.copyOf(values)));
            examples = Collections.unmodifiableMap(immutableExamples);
        }
    }

    public static EconomicSnapshotAudit inspect(PublishedEconomicGeneration generation) {
        if (generation == null) {
            throw new IllegalArgumentException("published economic generation is required");
        }
        Map<String, Integer> resolvedBySource = new TreeMap<>();
        SOURCES.forEach(source -> resolvedBySource.put(source, 0));
        Map<String, Integer> unknownByRootCause = new TreeMap<>();
        Map<String, String> resolvedExamples = new TreeMap<>();
        Map<String, List<String>> unknownExamplesByRootCause = new TreeMap<>();
        int resolved = 0;
        int complete = 0;
        int partial = 0;
        int unknown = 0;
        int pureDerived = 0;
        int survivalFalse = 0;
        int survivalUnknown = 0;
        int unsupportedPotential = 0;
        Map<String, List<String>> scopeExamples = new TreeMap<>();
        SurvivalAcquirabilityResolver survivalResolver = new SurvivalAcquirabilityResolver();

        for (EconomicSnapshot.ResourceResult resource : generation.economicSnapshot().resources().values()) {
            EconomicCostResolution resolution = resource.economicResolution();
            boolean isPureDerived = !resource.acquisitionPaths().isEmpty()
                && resource.acquisitionPaths().stream().allMatch(path -> path.sourceType().equals("recipe"));
            if (isPureDerived) {
                pureDerived++;
                addExample(scopeExamples, "pure_derived", resource.resourceId());
            }
            SurvivalAcquirability availability = survivalResolver.resolve(resource.acquisitionPaths()).state();
            if (availability == SurvivalAcquirability.FALSE) {
                survivalFalse++;
                addExample(scopeExamples, "survival_false", resource.resourceId());
            } else if (availability == SurvivalAcquirability.UNKNOWN) {
                survivalUnknown++;
                addExample(scopeExamples, "survival_unknown", resource.resourceId());
            }
            if (generation.economicSnapshot().inputSet().candidateResourceIds().contains(resource.resourceId())
                && !resource.economicCost().isKnown()) {
                unsupportedPotential++;
                addExample(scopeExamples, "unsupported_potential", resource.resourceId());
            }
            if (resource.economicCost().isKnown()) {
                resolved++;
                if (resolution.status() == ResolutionStatus.COMPLETE) {
                    complete++;
                } else if (resolution.status() == ResolutionStatus.PARTIAL) {
                    partial++;
                } else {
                    throw new IllegalStateException("known EconomicCost has unresolved status for "
                        + resource.resourceId());
                }
                String source = sourceOf(resolution);
                resolvedBySource.compute(source, (key, value) -> value + 1);
                resolvedExamples.putIfAbsent(source, resource.resourceId() + "="
                    + resource.economicCost().value() + " (" + resolution.target().primitiveId() + ")");
                continue;
            }

            unknown++;
            String rootCause = rootCause(resource);
            unknownByRootCause.merge(rootCause, 1, Integer::sum);
            List<String> examples = unknownExamplesByRootCause.computeIfAbsent(rootCause,
                ignored -> new ArrayList<>());
            if (examples.size() < MAX_EXAMPLES_PER_ROOT_CAUSE) {
                examples.add(diagnosticExample(resource, rootCause));
            }
        }

        EconomicSnapshotInputSet inputSet = generation.economicSnapshot().inputSet();
        inputSet.candidateResourceIds().stream()
            .limit(MAX_EXAMPLES_PER_ROOT_CAUSE)
            .forEach(id -> addExample(scopeExamples, "direct_candidate", id));
        inputSet.recursiveDependencyIds().stream()
            .filter(id -> !inputSet.candidateResourceIds().contains(id))
            .limit(MAX_EXAMPLES_PER_ROOT_CAUSE)
            .forEach(id -> addExample(scopeExamples, "recursive_dependency", id));
        Set<String> knownTechnical = new java.util.TreeSet<>(inputSet.technicalCandidateExclusionIds());
        knownTechnical.addAll(inputSet.technicalDependencyIds());
        Map<String, Integer> technicalKinds = new TreeMap<>();
        knownTechnical.forEach(id -> {
            String kind = technicalKind(id);
            if (kind != null) {
                technicalKinds.merge(kind, 1, Integer::sum);
                addExample(scopeExamples, kind, id);
            }
        });
        inputSet.technicalCandidateExclusionIds().stream()
            .limit(MAX_EXAMPLES_PER_ROOT_CAUSE)
            .forEach(id -> addExample(scopeExamples, "technical_excluded", id));
        inputSet.technicalDependencyIds().stream()
            .limit(MAX_EXAMPLES_PER_ROOT_CAUSE)
            .forEach(id -> addExample(scopeExamples, "technical_dependency", id));
        inputSet.reusableInputIds().stream()
            .limit(MAX_EXAMPLES_PER_ROOT_CAUSE)
            .forEach(id -> addExample(scopeExamples, "reusable_input", id));
        inputSet.unresolvedUseInputIds().stream()
            .limit(MAX_EXAMPLES_PER_ROOT_CAUSE)
            .forEach(id -> addExample(scopeExamples, "unknown_input_use", id));

        CalibrationSnapshot calibration = generation.calibrationSnapshot();
        return new EconomicSnapshotAudit(
            generation.economicSnapshot().resources().size(),
            resolved,
            complete,
            partial,
            unknown,
            0,
            resolvedBySource,
            unknownByRootCause,
            calibration.populationSize(),
            calibration.calibratedValues().size(),
            calibration.calibrationCoverage(),
            new ResourceScopeAudit(
                inputSet.indexedResourceIds().size(),
                inputSet.configuredResourceIds().size(),
                inputSet.candidateResourceIds().size(),
                inputSet.dependencyOnlyCount(),
                inputSet.technicalCandidateExclusionIds().size(),
                inputSet.technicalDependencyIds().size(),
                technicalKinds.getOrDefault("block_entity_infrastructure", 0)
                    + technicalKinds.getOrDefault("item_container", 0),
                technicalKinds.getOrDefault("damageable_equipment", 0),
                technicalKinds.getOrDefault("block_entity_infrastructure", 0),
                technicalKinds.getOrDefault("item_container", 0),
                inputSet.reusableInputIds().size(),
                inputSet.unresolvedUseInputIds().size(),
                pureDerived,
                survivalFalse,
                survivalUnknown,
                unsupportedPotential,
                scopeExamples),
            resolvedExamples,
            unknownExamplesByRootCause.values().stream().flatMap(List::stream).toList()
        );
    }

    public String countsSummary() {
        return "resources=" + totalResources + ", resolved=" + resolvedResources
            + " (complete=" + completeResources + ", partial=" + partialResources + ")"
            + ", unknown=" + unknownResources + ", not_applicable=" + notApplicableResources
            + ", indexed_acquisition_resources=" + resourceScope.indexedAcquisitionResources()
            + ", configured_resources=" + resourceScope.configuredResources()
            + ", direct_candidates=" + resourceScope.directCandidates()
            + ", recursive_dependencies=" + resourceScope.recursiveDependencies()
            + ", technical_excluded_from_candidates=" + resourceScope.technicalExcludedFromCandidates()
            + ", technical_dependencies=" + resourceScope.technicalDependencies()
            + ", container_infrastructure=" + resourceScope.containerInfrastructureResources()
            + ", damageable_technical=" + resourceScope.damageableTechnicalResources()
            + ", machine_infrastructure=" + resourceScope.machineInfrastructureResources()
            + ", item_containers=" + resourceScope.itemContainerResources()
            + ", reusable_inputs=" + resourceScope.reusableInputs()
            + ", unresolved_input_use=" + resourceScope.unresolvedUseInputs()
            + ", pure_derived=" + resourceScope.pureDerivedResources()
            + ", survival_false=" + resourceScope.survivalFalseResources()
            + ", survival_unknown=" + resourceScope.survivalUnknownResources()
            + ", unsupported_candidates=" + resourceScope.unsupportedPotentialResources()
            + ", calibration_candidates=" + calibrationCandidates
            + ", calibrated=" + calibratedResources + ", calibration_coverage=" + calibrationCoverage;
    }

    public String diagnosticsSummary() {
        return "resolved_by_source=" + resolvedBySource
            + ", unknown_root_causes=" + unknownByRootCause
            + ", resolved_examples=" + resolvedExamples
            + ", unknown_examples=" + unknownExamples
            + ", scope_examples=" + resourceScope.examples();
    }

    private static void addExample(Map<String, List<String>> examples, String category, String itemId) {
        List<String> values = examples.computeIfAbsent(category, ignored -> new ArrayList<>());
        if (values.size() < MAX_EXAMPLES_PER_ROOT_CAUSE && !values.contains(itemId)) {
            values.add(itemId);
        }
    }

    private static String technicalKind(String itemId) {
        ResourceLocation location = ResourceLocation.tryParse(itemId);
        Item item = location == null ? null : BuiltInRegistries.ITEM.getOptional(location).orElse(null);
        if (item == null) {
            return null;
        }
        ItemStack stack = new ItemStack(item);
        if (stack.isDamageableItem()) {
            return "damageable_equipment";
        }
        if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof EntityBlock) {
            return "block_entity_infrastructure";
        }
        return stack.has(DataComponents.CONTAINER) ? "item_container" : null;
    }

    private static String sourceOf(EconomicCostResolution resolution) {
        if (resolution.primaryPath() == null) {
            return "explicit_override";
        }
        AcquisitionPath path = resolution.primaryPath();
        return switch (path.sourceType()) {
            case "recipe" -> "recipe";
            case "crop" -> "crop";
            case "worldgen_feature" -> "worldgen";
            case "villager_trade" -> "trade";
            case "mob_drop", "fishing", "block_loot", "worldgen" -> "loot";
            default -> "other";
        };
    }

    private static String rootCause(EconomicSnapshot.ResourceResult resource) {
        List<AcquisitionPath> paths = resource.acquisitionPaths();
        if (paths.isEmpty()) {
            return "no_acquisition_path";
        }
        if (paths.stream().anyMatch(path -> path.sourceType().equals("recipe")
            && !path.evidence().attributes().getOrDefault("missing_inputs", "").isBlank())) {
            return "unresolved_recursive_recipe_input";
        }
        if (resource.economicResolution().reasons().stream()
            .anyMatch(reason -> reason.contains("excluded by feasibility"))) {
            return "feasibility_rejection";
        }
        if (paths.stream().anyMatch(path -> path.sourceType().equals("villager_trade"))) {
            return "unresolved_trade_economics";
        }
        if (paths.stream().anyMatch(path -> path.sourceType().equals("worldgen_feature"))) {
            return "worldgen_extraction_not_resolved";
        }
        if (paths.stream().anyMatch(path -> path.sourceType().equals("crop"))) {
            return "crop_cycle_economics_not_resolved";
        }
        if (paths.stream().anyMatch(path -> List.of("mob_drop", "fishing", "block_loot", "worldgen")
            .contains(path.sourceType()))) {
            return "loot_economic_primitive_not_resolved";
        }
        if (paths.stream().anyMatch(path -> path.sourceType().equals("recipe"))) {
            return "recipe_has_no_independent_economic_primitive";
        }
        return "acquisition_path_economic_cost_unknown";
    }

    private static String firstReason(EconomicSnapshot.ResourceResult resource) {
        for (AcquisitionPath path : resource.acquisitionPaths()) {
            String missingInputs = path.evidence().attributes().getOrDefault("missing_inputs", "");
            if (!missingInputs.isBlank()) {
                return "missing_inputs=" + missingInputs;
            }
        }
        if (!resource.economicResolution().reasons().isEmpty()) {
            return resource.economicResolution().reasons().getFirst();
        }
        return resource.economicCost().evidence();
    }

    private static String diagnosticExample(EconomicSnapshot.ResourceResult resource, String rootCause) {
        StringBuilder example = new StringBuilder(resource.resourceId())
            .append(" [").append(rootCause).append("] status=")
            .append(resource.economicResolution().status())
            .append("; paths=[");
        List<AcquisitionPath> paths = resource.acquisitionPaths();
        for (AcquisitionPath path : paths.stream().limit(2).toList()) {
            FeasibilityResult feasibility = FeasibilityResolver.resolve(path,
                com.jorjik.dynamicfood.config.DynamicFoodConfig.feasibilityFactorWeights(),
                com.jorjik.dynamicfood.config.DynamicFoodConfig.minimumFeasibilityCoverage(),
                com.jorjik.dynamicfood.config.DynamicFoodConfig.minimumFeasibility(),
                com.jorjik.dynamicfood.config.DynamicFoodConfig.allowPartialFeasibility());
            example.append(path.sourceType()).append(':').append(path.sourceId())
                .append("{cost=").append(shorten(path.economicCostAt(
                    resource.economicResolution().economicHorizon()).evidence(), 100))
                .append("; feasibility=").append(feasibility.status())
                .append("(coverage=").append(round(feasibility.coverage()))
                .append(",score=").append(feasibility.feasibility() == null
                    ? "UNKNOWN" : round(feasibility.feasibility()))
                .append(",eligible=").append(feasibility.eligibleForPrimary())
                .append("); missingFactors=").append(feasibility.missingFactors().stream()
                    .map(reason -> reason.substring(0, reason.indexOf(':') < 0
                        ? reason.length() : reason.indexOf(':'))).toList())
                .append("; inputs=").append(inputSummary(path))
                .append("; quantity=").append(measurement(path, "expected_units_per_attempt"))
                .append("; material=").append(measurement(path, "material_cost_per_output"))
                .append("} ")
                .append(" | ");
        }
        example.append("morePaths=").append(Math.max(0, paths.size() - 2))
            .append("]; resolver=").append(shorten(String.join("; ",
                resource.economicResolution().reasons().stream().limit(2).toList()), 160));
        if (example.length() <= MAX_EXAMPLE_LENGTH) {
            return example.toString();
        }
        return example.substring(0, MAX_EXAMPLE_LENGTH - 3) + "...";
    }

    private static String measurement(AcquisitionPath path, String name) {
        AcquisitionMeasurement value = path.evidence().measurement(name);
        return value.isKnown()
            ? value.value() + "[" + value.estimateKind().orElse(EstimateKind.UNKNOWN) + "]"
            : "UNKNOWN(" + shorten(value.unknownReason(), 90) + ")";
    }

    private static List<String> inputSummary(AcquisitionPath path) {
        Map<String, String> attributes = path.evidence().attributes();
        return new TreeMap<>(attributes).entrySet().stream()
            .filter(entry -> entry.getKey().startsWith("input_")
                && entry.getKey().endsWith("_alternatives"))
            .map(entry -> {
                String index = entry.getKey().substring("input_".length(),
                    entry.getKey().length() - "_alternatives".length());
                return shorten(entry.getValue(), 50) + "x"
                    + attributes.getOrDefault("input_" + index + "_quantity", "?") + "/"
                    + attributes.getOrDefault("input_" + index + "_use", "unknown");
            })
            .toList();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }

    private static String shorten(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
    }
}
