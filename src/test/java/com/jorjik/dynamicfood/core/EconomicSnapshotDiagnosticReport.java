package com.jorjik.dynamicfood.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** GameTest-only serializer for an immutable production economic generation. */
public final class EconomicSnapshotDiagnosticReport {
    private static final List<FactorDefinition> FACTORS = List.of(
        new FactorDefinition("quantity", "quantity_cost", true,
            List.of("expected_units_per_attempt", "crop_harvest_expected_units_per_loot_invocation")),
        new FactorDefinition("probability_burden", "probability_burden", true,
            List.of("probability_burden_raw", "worldgen_occurrence_probability")),
        new FactorDefinition("material", "material_cost", true,
            List.of("material_cost_per_output")),
        new FactorDefinition("equipment", "equipment_cost", true, List.of("equipment_cost")));

    private EconomicSnapshotDiagnosticReport() {}

    public static JsonObject create(PublishedEconomicGeneration generation,
        StructureContainerAcquisitionAnalyzer.Summary structureSummary) {
        if (generation == null) {
            throw new IllegalArgumentException("published economic generation is required");
        }
        EconomicSnapshot snapshot = generation.economicSnapshot();
        snapshot.validateForCalibration();
        int horizon = DynamicFoodConfig.acquisitionEconomicHorizon();
        Map<String, Double> costWeights = DynamicFoodConfig.costFactorWeights();
        Map<String, Double> feasibilityWeights = DynamicFoodConfig.feasibilityFactorWeights();
        Counts counts = new Counts();
        JsonArray resources = new JsonArray();
        List<PathObservation> observations = new ArrayList<>();
        Map<String, AnalyzerCounts> byAnalyzer = new TreeMap<>();

        snapshot.resources().forEach((resourceId, resource) -> {
            JsonObject resourceJson = new JsonObject();
            resourceJson.addProperty("resourceId", resourceId);
            resourceJson.add("resourceResolution", resourceResolution(resource.economicResolution()));
            JsonArray paths = new JsonArray();
            counts.resources++;
            if (resource.acquisitionPaths().isEmpty()) {
                counts.resourcesWithoutPath++;
            } else {
                counts.resourcesWithPath++;
            }
            if (resource.economicCost().isKnown()) {
                counts.economicCostResolved++;
            } else {
                counts.economicCostUnknown++;
            }
            for (AcquisitionPath path : resource.acquisitionPaths()) {
                PathObservation observation = inspectPath(resource, path, snapshot, horizon,
                    costWeights, feasibilityWeights);
                observations.add(observation);
                paths.add(observation.json());
                counts.add(observation);
                byAnalyzer.computeIfAbsent(analyzer(path.sourceType()), ignored -> new AnalyzerCounts())
                    .add(resourceId, observation);
            }
            resourceJson.add("paths", paths);
            resources.add(resourceJson);
        });

        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        report.addProperty("economicSnapshotSchema", snapshot.schemaVersion());
        report.addProperty("generation", snapshot.generation());
        report.addProperty("economicPolicySignature", DynamicFoodConfig.economicPolicySignature());
        report.addProperty("snapshotSignature", snapshot.signature());
        report.addProperty("horizon", horizon);
        JsonObject policyConfiguration = new JsonObject();
        policyConfiguration.addProperty("weightSource", "DynamicFoodConfig.costFactorWeights()");
        policyConfiguration.add("costFactorWeights", numberMap(costWeights));
        policyConfiguration.add("feasibilityFactorWeights", numberMap(feasibilityWeights));
        policyConfiguration.addProperty("pathStrategy", DynamicFoodConfig.acquisitionStrategy().name());
        policyConfiguration.addProperty("minimumFeasibility", DynamicFoodConfig.minimumFeasibility());
        policyConfiguration.addProperty("minimumFeasibilityCoverage",
            DynamicFoodConfig.minimumFeasibilityCoverage());
        policyConfiguration.addProperty("allowPartialFeasibility", DynamicFoodConfig.allowPartialFeasibility());
        report.add("policyConfiguration", policyConfiguration);
        report.add("summary", counts.toJson());
        report.add("resources", resources);
        report.add("perAnalyzer", analyzersJson(byAnalyzer));
        if (structureSummary != null) {
            report.add("structureAnalysis", structureAnalysisJson(structureSummary));
        }
        report.add("policyAudit", policyAudit(observations, snapshot, costWeights));
        return report;
    }

    private static JsonObject structureAnalysisJson(StructureContainerAcquisitionAnalyzer.Summary summary) {
        JsonObject result = new JsonObject();
        result.addProperty("structureSets", summary.structureSets());
        result.addProperty("structures", summary.structures());
        result.addProperty("jigsawStructures", summary.jigsawStructures());
        result.addProperty("templatePools", summary.templatePools());
        result.addProperty("templateResources", summary.templateResources());
        result.addProperty("referencedTemplates", summary.referencedTemplates());
        result.addProperty("loadedTemplates", summary.loadedTemplates());
        result.addProperty("templateBlockEntries", summary.templateBlockEntries());
        result.addProperty("blockEntityTags", summary.blockEntityTags());
        result.addProperty("lootTableTags", summary.lootTableTags());
        result.addProperty("invalidContainerReferences", summary.invalidContainerReferences());
        result.addProperty("containerPlacements", summary.containerPlacements());
        result.addProperty("staticContainerInventories", summary.staticContainerInventories());
        result.addProperty("containersWithLootTables", summary.containersWithLootTables());
        result.addProperty("lootTableIds", summary.lootTableIds());
        result.addProperty("candidateRoutes", summary.candidateRoutes());
        result.addProperty("candidatePaths", summary.candidatePaths());
        result.addProperty("completeCandidates", summary.completeCandidates());
        result.addProperty("partialCandidates", summary.partialCandidates());
        result.addProperty("unresolvedReferences", summary.unresolvedReferences());
        result.addProperty("cycleReferences", summary.cycleReferences());
        result.addProperty("aliasEntries", summary.aliasEntries());
        result.addProperty("unsupportedPoolElements", summary.unsupportedPoolElements());
        result.addProperty("unresolvedBiomeTags", summary.unresolvedBiomeTags());
        JsonArray unsupportedTypes = new JsonArray();
        summary.unsupportedStructureTypes().forEach(unsupportedTypes::add);
        result.add("unsupportedStructureTypes", unsupportedTypes);
        return result;
    }

    private static PathObservation inspectPath(EconomicSnapshot.ResourceResult resource, AcquisitionPath path,
        EconomicSnapshot snapshot, int horizon, Map<String, Double> costWeights,
        Map<String, Double> feasibilityWeights) {
        CostVector vector = path.costsByHorizon().get(horizon);
        EconomicCostResolution resolution = resource.economicResolution();
        FeasibilityResult feasibility = FeasibilityResolver.resolve(path, feasibilityWeights,
            DynamicFoodConfig.minimumFeasibilityCoverage(), DynamicFoodConfig.minimumFeasibility(),
            DynamicFoodConfig.allowPartialFeasibility());
        String availability = path.evidence().attributes()
            .getOrDefault("source_availability_classification", "UNKNOWN").toUpperCase(java.util.Locale.ROOT);
        AcquisitionCost acquisitionCost = vector == null
            ? null : AcquisitionCostResolver.resolve(vector, costWeights);
        String selectedId = resolution.primaryPath() == null ? null : pathId(resolution.primaryPath());
        boolean selected = pathId(path).equals(selectedId);
        boolean available = availability.equals("TRUE");
        boolean feasible = available && feasibility.eligibleForPrimary();
        boolean vectorReached = feasible && vector != null;
        boolean acquisitionReached = vectorReached;
        boolean acquisitionResolved = acquisitionCost != null
            && acquisitionCost.status() != ResolutionStatus.UNKNOWN;
        boolean selectionEligible = acquisitionReached && acquisitionResolved
            && (DynamicFoodConfig.acquisitionStrategy() != PrimaryPathStrategy.BEST_REPEATABLE_COST
                || Boolean.TRUE.equals(path.repeatable()));
        PathBlocker blocker = firstBlocker(availability, feasibility, vector, acquisitionCost,
            selectionEligible, path);

        JsonObject pathJson = new JsonObject();
        pathJson.addProperty("pathId", pathId(path));
        pathJson.addProperty("sourceType", path.sourceType());
        pathJson.add("availability", availabilityJson(path));
        pathJson.add("feasibility", feasibilityJson(feasibility));
        pathJson.add("costVector", costVectorJson(path, vector, horizon, costWeights, acquisitionCost));
        pathJson.add("acquisitionCost", acquisitionCostJson(vector, acquisitionCost, costWeights));
        JsonObject economicResolution = new JsonObject();
        economicResolution.addProperty("eligibleForSelection", selectionEligible);
        economicResolution.addProperty("selected", selected);
        economicResolution.addProperty("status", resolution.status().name());
        addNullable(economicResolution, "value", resolution.economicCost());
        economicResolution.addProperty("source", resolution.primaryPath() == null
            ? resolution.target().primitiveId() : pathId(resolution.primaryPath()));
        pathJson.add("economicResolution", economicResolution);
        pathJson.add("pipeline", pipelineJson(availability, feasible, vector, acquisitionCost,
            selectionEligible, resolution, selected, blocker));
        JsonObject diagnostics = new JsonObject();
        diagnostics.addProperty("firstBlocker", blocker.code());
        diagnostics.addProperty("blockerStage", blocker.stage());
        diagnostics.add("missingInputs", missingInputs(path, snapshot));
        diagnostics.add("evidence", evidenceJson(path));
        diagnostics.add("recipeInputs", recipeInputs(path, snapshot));
        pathJson.add("diagnostics", diagnostics);
        return new PathObservation(resource.resourceId(), path, vector, acquisitionCost, feasibility,
            availability, feasible, acquisitionReached, selectionEligible, selected,
            resolution.target().isKnown(), blocker, pathJson);
    }

    private static PathBlocker firstBlocker(String availability, FeasibilityResult feasibility,
        CostVector vector, AcquisitionCost cost, boolean selectionEligible, AcquisitionPath path) {
        if (!availability.equals("TRUE")) {
            return new PathBlocker(availability.equals("FALSE")
                ? "SURVIVAL_AVAILABILITY_FALSE" : "SURVIVAL_AVAILABILITY_UNKNOWN", "AVAILABILITY_CHECK");
        }
        if (!feasibility.eligibleForPrimary()) {
            if (feasibility.hardFailed()) {
                return new PathBlocker("FEASIBILITY_HARD_FAILED", "FEASIBILITY_CHECK");
            }
            if (feasibility.status() == ResolutionStatus.PARTIAL) {
                return new PathBlocker("FEASIBILITY_PARTIAL", "FEASIBILITY_CHECK");
            }
            if (feasibility.status() == ResolutionStatus.UNKNOWN) {
                return new PathBlocker("FEASIBILITY_UNKNOWN", "FEASIBILITY_CHECK");
            }
            return new PathBlocker("FEASIBILITY_THRESHOLD_REJECTED", "FEASIBILITY_CHECK");
        }
        if (vector == null) {
            return new PathBlocker("COST_VECTOR_MISSING_AT_HORIZON", "COST_VECTOR");
        }
        if (cost == null || cost.status() == ResolutionStatus.UNKNOWN) {
            if (vector != null) {
                for (EconomicChannel channel : EconomicChannel.values()) {
                    String factor = channel.id();
                    EconomicFactor value = vector.factors().get(factor);
                    if (value == null || value.isUnknown()) {
                        return new PathBlocker("UNKNOWN_CORE_FACTOR_" + factor.substring(0,
                            factor.length() - "_cost".length()).toUpperCase(java.util.Locale.ROOT),
                            "ACQUISITION_COST");
                    }
                }
            }
            return new PathBlocker("ACQUISITION_COST_UNRESOLVED", "ACQUISITION_COST");
        }
        if (!selectionEligible) {
            return new PathBlocker("PATH_SELECTION_REQUIREMENT_NOT_MET", "PATH_SELECTION");
        }
        if (path.repeatable() == null && DynamicFoodConfig.acquisitionStrategy()
            == PrimaryPathStrategy.BEST_REPEATABLE_COST) {
            return new PathBlocker("REPEATABILITY_UNKNOWN", "PATH_SELECTION");
        }
        if (Boolean.FALSE.equals(path.repeatable())
            && DynamicFoodConfig.acquisitionStrategy() == PrimaryPathStrategy.BEST_REPEATABLE_COST) {
            return new PathBlocker("PATH_NOT_REPEATABLE", "PATH_SELECTION");
        }
        return new PathBlocker("NONE", "NONE");
    }

    private static JsonObject pipelineJson(String availability, boolean feasible, CostVector vector,
        AcquisitionCost cost, boolean selectionEligible, EconomicCostResolution resolution,
        boolean selected, PathBlocker blocker) {
        JsonObject pipeline = new JsonObject();
        JsonArray stages = new JsonArray();
        stages.add(stage("DISCOVERED", "PASSED", "Path is present in the generated snapshot."));
        String availabilityState = availability.equals("TRUE") ? "PASSED" : "FAILED";
        stages.add(stage("AVAILABILITY_CHECK", availabilityState,
            availability.equals("TRUE") ? "Survival availability is TRUE."
                : "Survival availability is " + availability + "."));
        stages.add(stage("FEASIBILITY_CHECK",
            !availability.equals("TRUE") ? "NOT_REACHED" : feasible ? "PASSED" : "FAILED",
            !availability.equals("TRUE") ? "Availability gate stopped the production resolver."
                : feasible ? "Feasibility is eligible for primary selection."
                    : blocker.stage().equals("FEASIBILITY_CHECK") ? blocker.code()
                        : "Feasibility rejected the path."));
        stages.add(stage("COST_VECTOR", !feasible ? "NOT_REACHED" : vector == null ? "FAILED" : "PASSED",
            !feasible ? "Earlier availability or feasibility gate stopped the production resolver."
                : vector == null ? "No CostVector exists at the configured horizon."
                    : "CostVector exists; core completeness is reported separately."));
        boolean acquisitionReached = feasible && vector != null;
        stages.add(stage("ACQUISITION_COST",
            !acquisitionReached ? "NOT_REACHED" : cost == null
                || cost.status() == ResolutionStatus.UNKNOWN ? "FAILED" : "PASSED",
            !acquisitionReached ? "Earlier gate stopped the production resolver."
                : cost == null ? "AcquisitionCost could not be computed."
                    : cost.status() == ResolutionStatus.UNKNOWN
                        ? "AcquisitionCost is UNKNOWN; inspect unknownCoreFactors and weights."
                        : "Numeric AcquisitionCost was computed."));
        stages.add(stage("PATH_SELECTION",
            !acquisitionReached || cost == null || cost.status() == ResolutionStatus.UNKNOWN
                ? "NOT_REACHED" : selectionEligible ? "PASSED" : "FAILED",
            !acquisitionReached || cost == null || cost.status() == ResolutionStatus.UNKNOWN
                ? "AcquisitionCost stage did not produce a numeric candidate."
                : selectionEligible ? selected ? "Candidate selected as representative path."
                    : "Candidate satisfies path gates but was not selected as representative."
                    : "Path selection requirements are not satisfied."));
        stages.add(stage("ECONOMIC_COST", resolution.target().isKnown()
            ? selectionEligible ? "PASSED" : "NOT_REACHED"
            : "NOT_REACHED", resolution.target().isKnown()
                ? selectionEligible ? "Resource EconomicCost is resolved." :
                    "Resource value came from another source or this path did not qualify."
                : "Resource EconomicCost remains UNKNOWN."));
        pipeline.add("stages", stages);
        pipeline.addProperty("firstBlocker", blocker.code());
        pipeline.addProperty("firstBlockerStage", blocker.stage());
        return pipeline;
    }

    private static JsonObject stage(String name, String result, String reason) {
        JsonObject stage = new JsonObject();
        stage.addProperty("stage", name);
        stage.addProperty("result", result);
        stage.addProperty("reason", reason);
        return stage;
    }

    private static JsonObject availabilityJson(AcquisitionPath path) {
        JsonObject result = new JsonObject();
        result.addProperty("classification", path.evidence().attributes()
            .getOrDefault("source_availability_classification", "UNKNOWN"));
        result.addProperty("reason", path.evidence().attributes()
            .getOrDefault("survival_availability", "No survival-availability reason was exposed."));
        return result;
    }

    private static JsonObject feasibilityJson(FeasibilityResult feasibility) {
        JsonObject result = new JsonObject();
        result.addProperty("status", feasibility.status().name());
        result.addProperty("coverage", feasibility.coverage());
        addNullable(result, "score", feasibility.feasibility());
        result.addProperty("eligible", feasibility.eligibleForPrimary());
        result.addProperty("hardFailed", feasibility.hardFailed());
        JsonArray unknown = new JsonArray();
        feasibility.missingFactors().forEach(unknown::add);
        result.add("unknownFactors", unknown);
        JsonArray notApplicable = new JsonArray();
        feasibility.notApplicableFactors().forEach(notApplicable::add);
        result.add("notApplicableFactors", notApplicable);
        result.addProperty("reason", feasibilityReason(feasibility));
        return result;
    }

    private static String feasibilityReason(FeasibilityResult result) {
        if (result.hardFailed()) {
            return "Path is marked hard-failed.";
        }
        if (result.eligibleForPrimary()) {
            return "Feasibility thresholds and partial-feasibility policy permit primary selection.";
        }
        return "status=" + result.status() + ", coverage=" + result.coverage()
            + ", score=" + result.feasibility() + ", minimumCoverage="
            + DynamicFoodConfig.minimumFeasibilityCoverage() + ", minimumScore="
            + DynamicFoodConfig.minimumFeasibility() + ", allowPartial="
            + DynamicFoodConfig.allowPartialFeasibility() + ", unknownFactors=" + result.missingFactors();
    }

    private static JsonObject costVectorJson(AcquisitionPath path, CostVector vector, int horizon,
        Map<String, Double> weights, AcquisitionCost acquisitionCost) {
        JsonObject result = new JsonObject();
        result.addProperty("horizon", horizon);
        result.addProperty("present", vector != null);
        result.addProperty("coreComplete", vector != null && vector.isCoreComplete());
        JsonObject coverage = new JsonObject();
        int knownApplicable = 0;
        int applicable = 0;
        for (EconomicChannel channel : EconomicChannel.values()) {
            EconomicFactor factor = vector == null ? null : vector.factor(channel);
            if (factor == null || !factor.isNotApplicable()) {
                applicable++;
                if (factor != null && factor.isKnown()) {
                    knownApplicable++;
                }
            }
        }
        coverage.addProperty("knownApplicable", knownApplicable);
        coverage.addProperty("applicable", applicable);
        if (vector == null) {
            coverage.add("ratio", JsonNull.INSTANCE);
        } else {
            coverage.addProperty("ratio", applicable == 0 ? 1.0D : (double) knownApplicable / applicable);
        }
        result.add("coreCoverage", coverage);
        JsonObject factors = new JsonObject();
        for (FactorDefinition definition : FACTORS) {
            factors.add(definition.name(), factorJson(path, vector, definition, weights,
                acquisitionCost));
        }
        result.add("factors", factors);
        return result;
    }

    private static JsonObject factorJson(AcquisitionPath path, CostVector vector,
        FactorDefinition definition, Map<String, Double> weights, AcquisitionCost acquisitionCost) {
        EconomicFactor factor = vector == null ? null : vector.factors().get(definition.key());
        String state = factor == null ? "UNKNOWN" : factor.state().name();
        JsonObject result = new JsonObject();
        result.addProperty("state", state);
        result.addProperty("applicable", !"NOT_APPLICABLE".equals(state));
        addNullable(result, "normalizedValue", factor == null ? null : factor.value());
        double weight = weights.getOrDefault(definition.key(), 0.0D);
        result.addProperty("configuredWeight", weight);
        boolean included = acquisitionCost != null
            && acquisitionCost.normalizedFactors().containsKey(definition.key());
        result.addProperty("includedInAcquisitionCost", included);
        String reason = factor == null
            ? vector == null ? "CostVector is absent at horizon " + DynamicFoodConfig.acquisitionEconomicHorizon()
                : "Factor applicability was not reported by the analyzer."
            : factor.reason();
        result.addProperty("diagnosticReason", reason);
        result.addProperty("evidenceSource", path.sourceType() + ":" + path.sourceId());
        JsonArray rawMatches = new JsonArray();
        List<String> aliases = definition.measurementAliases();
        new TreeMap<>(path.evidence().measurements()).forEach((name, measurement) -> {
            if (aliases.stream().anyMatch(alias -> name.equals(alias) || name.startsWith(alias))) {
                rawMatches.add(measurementJson(name, measurement));
            }
        });
        result.add("rawEvidenceMeasurements", rawMatches);
        AcquisitionMeasurement rawMeasurement = path.evidence().measurements().entrySet().stream()
            .filter(entry -> aliases.stream().anyMatch(alias -> entry.getKey().equals(alias)
                || entry.getKey().startsWith(alias)))
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .findFirst().orElse(null);
        addNullable(result, "rawValue", rawMeasurement == null ? null : rawMeasurement.value());
        result.addProperty("rawEvidenceState", rawMeasurement == null ? "NOT_EXPOSED"
            : rawMeasurement.isKnown() ? "KNOWN" : "UNKNOWN");
        result.addProperty("rawValueSemantics", rawMatches.size() == 0
            ? "No directly mapped raw measurement is exposed; see path evidence."
            : definition.name().equals("material")
                ? "Material evidence may be a canonical child EconomicCost, not a raw mechanical measurement."
                : "Raw acquisition measurement; it has not been substituted for the normalized factor.");
        if (factor != null && factor.isNotApplicable()) {
            result.addProperty("excludedReason", factor.reason());
        } else if (factor == null || factor.isUnknown()) {
            result.addProperty("excludedReason", reason);
        } else if (!included) {
            result.addProperty("excludedReason", acquisitionCost == null
                ? "AcquisitionCost is unavailable because the CostVector is absent."
                : "Known factor was not included by the AcquisitionCost resolver.");
        } else {
            result.add("excludedReason", JsonNull.INSTANCE);
        }
        if (factor != null && factor.isKnown()) {
            result.addProperty("configuredWeightedValue", factor.value() * weight);
            result.addProperty("acquisitionCostScaledWeight", scaledWeight(definition.key(),
                vector, weights));
            result.addProperty("acquisitionCostWeightedContribution", factor.value()
                * scaledWeight(definition.key(), vector, weights));
        } else {
            result.add("configuredWeightedValue", JsonNull.INSTANCE);
            result.add("acquisitionCostScaledWeight", JsonNull.INSTANCE);
            result.add("acquisitionCostWeightedContribution", JsonNull.INSTANCE);
        }
        return result;
    }

    private static double scaledWeight(String factor, CostVector vector, Map<String, Double> weights) {
        double maxIncludedWeight = 0.0D;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            EconomicFactor value = vector.factors().get(entry.getKey());
            if (value != null && value.isKnown()) {
                maxIncludedWeight = Math.max(maxIncludedWeight, entry.getValue());
            }
        }
        return maxIncludedWeight == 0.0D ? 0.0D : weights.getOrDefault(factor, 0.0D) / maxIncludedWeight;
    }

    private static JsonObject acquisitionCostJson(CostVector vector, AcquisitionCost cost,
        Map<String, Double> weights) {
        JsonObject result = new JsonObject();
        result.addProperty("status", cost == null ? ResolutionStatus.UNKNOWN.name() : cost.status().name());
        addNullable(result, "value", cost == null ? null : cost.cost());
        double rawWeightSum = 0.0D;
        double maxWeight = cost == null ? 0.0D : cost.normalizedFactors().keySet().stream()
            .mapToDouble(name -> weights.getOrDefault(name, 0.0D)).max().orElse(0.0D);
        JsonArray included = new JsonArray();
        JsonArray excluded = new JsonArray();
        JsonArray unknownCore = new JsonArray();
        if (cost != null) {
            for (FactorDefinition definition : FACTORS) {
                EconomicFactor factor = vector.factors().get(definition.key());
                if (factor != null && factor.isNotApplicable()) {
                    excluded.add(definition.name());
                } else if (factor == null || factor.isUnknown()) {
                    unknownCore.add(definition.name());
                } else {
                    double weight = weights.getOrDefault(definition.key(), 0.0D);
                    rawWeightSum += weight;
                    JsonObject item = new JsonObject();
                    item.addProperty("factor", definition.name());
                    item.addProperty("normalizedValue", factor.value());
                    item.addProperty("weight", weight);
                    item.addProperty("weightedValue", factor.value() * weight);
                    double scaledWeight = maxWeight == 0.0D ? 0.0D : weight / maxWeight;
                    item.addProperty("scaledWeight", scaledWeight);
                    item.addProperty("scaledWeightedContribution", factor.value() * scaledWeight);
                    included.add(item);
                }
            }
            double denominator = maxWeight == 0.0D ? 0.0D
                : cost.normalizedFactors().keySet().stream()
                    .mapToDouble(name -> weights.getOrDefault(name, 0.0D) / maxWeight).sum();
            result.addProperty("denominatorWeight", denominator);
        } else {
            result.addProperty("denominatorWeight", 0.0D);
        }
        result.addProperty("sumOfConfiguredIncludedWeights", rawWeightSum);
        result.add("includedFactors", included);
        result.add("excludedNotApplicableFactors", excluded);
        result.add("unknownCoreFactors", unknownCore);
        result.add("resolverMissingFactors", stringMap(cost == null ? Map.of() : cost.missingFactors()));
        result.add("resolverNotApplicableFactors", stringMap(
            cost == null ? Map.of() : cost.notApplicableFactors()));
        return result;
    }

    private static JsonObject resourceResolution(EconomicCostResolution resolution) {
        JsonObject result = new JsonObject();
        result.addProperty("status", resolution.status().name());
        addNullable(result, "economicCost", resolution.economicCost());
        result.addProperty("source", resolution.primaryPath() == null
            ? resolution.target().primitiveId() : pathId(resolution.primaryPath()));
        result.addProperty("horizon", resolution.economicHorizon());
        JsonArray reasons = new JsonArray();
        resolution.reasons().forEach(reasons::add);
        result.add("reasons", reasons);
        return result;
    }

    private static JsonArray missingInputs(AcquisitionPath path, EconomicSnapshot snapshot) {
        JsonArray result = new JsonArray();
        Set<String> ids = new HashSet<>();
        recipeInputGroups(path).forEach(group -> group.alternatives().forEach(inputId -> {
            EconomicSnapshot.ResourceResult child = snapshot.resources().get(inputId);
            if (child == null || !child.economicCost().isKnown()) {
                ids.add(inputId);
            }
        }));
        String diagnostic = path.evidence().attributes().getOrDefault("missing_inputs", "");
        if (!diagnostic.isBlank()) {
            for (String inputId : ids) {
                if (diagnostic.contains(inputId)) {
                    result.add(inputId);
                }
            }
        }
        ids.stream().filter(id -> !contains(result, id)).sorted().forEach(result::add);
        return result;
    }

    private static JsonArray recipeInputs(AcquisitionPath path, EconomicSnapshot snapshot) {
        JsonArray result = new JsonArray();
        for (InputGroup group : recipeInputGroups(path)) {
            JsonObject input = new JsonObject();
            input.addProperty("requiredQuantity", group.quantity());
            input.addProperty("inputUse", group.use());
            JsonArray children = new JsonArray();
            for (String inputId : group.alternatives()) {
                JsonObject childJson = new JsonObject();
                childJson.addProperty("inputItem", inputId);
                childJson.addProperty("requiredQuantity", group.quantity());
                EconomicSnapshot.ResourceResult child = snapshot.resources().get(inputId);
                if (child == null) {
                    childJson.addProperty("childEconomicCostStatus", "UNKNOWN");
                    childJson.add("childEconomicCostValue", JsonNull.INSTANCE);
                    childJson.addProperty("childResolutionSource", "not_present_in_generation_input");
                } else {
                    childJson.addProperty("childEconomicCostStatus", child.economicResolution().status().name());
                    addNullable(childJson, "childEconomicCostValue", child.economicCost().value());
                    childJson.addProperty("childResolutionSource", child.economicResolution().primaryPath() == null
                        ? child.economicCost().primitiveId()
                        : pathId(child.economicResolution().primaryPath()));
                    childJson.addProperty("childResolutionEvidence", child.economicCost().evidence());
                }
                children.add(childJson);
            }
            input.add("alternatives", children);
            result.add(input);
        }
        return result;
    }

    private static List<InputGroup> recipeInputGroups(AcquisitionPath path) {
        if (!path.sourceType().equals("recipe")) {
            return List.of();
        }
        Map<String, String> attributes = path.evidence().attributes();
        List<InputGroup> groups = new ArrayList<>();
        new TreeMap<>(attributes).entrySet().stream()
            .filter(entry -> entry.getKey().startsWith("input_")
                && entry.getKey().endsWith("_alternatives"))
            .forEach(entry -> {
                String index = entry.getKey().substring("input_".length(),
                    entry.getKey().length() - "_alternatives".length());
                List<String> alternatives = java.util.Arrays.stream(entry.getValue().split("\\|"))
                    .filter(value -> !value.isBlank()).toList();
                int quantity;
                quantity = parseQuantity(attributes.get("input_" + index + "_quantity"));
                groups.add(new InputGroup(alternatives, quantity,
                    attributes.getOrDefault("input_" + index + "_use", "unknown")));
            });
        return List.copyOf(groups);
    }

    private static Integer parseQuantity(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static JsonObject evidenceJson(AcquisitionPath path) {
        JsonObject evidence = new JsonObject();
        JsonObject measurements = new JsonObject();
        new TreeMap<>(path.evidence().measurements()).forEach((name, measurement) ->
            measurements.add(name, measurementJson(name, measurement)));
        evidence.add("measurements", measurements);
        evidence.add("attributes", stringMap(path.evidence().attributes()));
        WorldgenCausalEvidence causalEvidence = path.evidence().worldgenCausalEvidence();
        if (path.sourceType().equals("worldgen_structure_container")) {
            JsonObject summary = new JsonObject();
            summary.addProperty("nodeCount", causalEvidence.nodes().size());
            summary.addProperty("relationshipCount", causalEvidence.relationships().size());
            summary.add("nodeTypes", enumCounts(causalEvidence.nodes().stream()
                .map(node -> node.ref().type().name()).toList()));
            summary.add("relationshipTypes", enumCounts(causalEvidence.relationships().stream()
                .map(relationship -> relationship.type().name()).toList()));
            evidence.add("worldgenCausalEvidenceSummary", summary);
        } else {
            evidence.add("worldgenCausalEvidence", worldgenCausalEvidenceJson(causalEvidence));
        }
        return evidence;
    }

    private static JsonObject enumCounts(List<String> values) {
        JsonObject result = new JsonObject();
        Map<String, Long> counts = values.stream().collect(java.util.stream.Collectors.groupingBy(
            value -> value, TreeMap::new, java.util.stream.Collectors.counting()));
        counts.forEach(result::addProperty);
        return result;
    }

    private static JsonObject worldgenCausalEvidenceJson(WorldgenCausalEvidence causalEvidence) {
        JsonObject result = new JsonObject();
        JsonArray nodes = new JsonArray();
        causalEvidence.nodes().forEach(node -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", node.ref().type().name());
            entry.addProperty("id", node.ref().identifier());
            entry.addProperty("sourceResource", node.sourceResource());
            entry.addProperty("rawEvidence", node.rawEvidence());
            nodes.add(entry);
        });
        JsonArray relationships = new JsonArray();
        causalEvidence.relationships().forEach(relationship -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("fromType", relationship.from().type().name());
            entry.addProperty("fromId", relationship.from().identifier());
            entry.addProperty("type", relationship.type().name());
            entry.addProperty("toType", relationship.to().type().name());
            entry.addProperty("toId", relationship.to().identifier());
            entry.addProperty("branchKind", relationship.branchKind().name());
            entry.addProperty("branchEvidence", relationship.branchEvidence());
            entry.addProperty("sourceResource", relationship.sourceResource());
            entry.addProperty("unresolvedReason", relationship.unresolvedReason());
            relationships.add(entry);
        });
        result.add("nodes", nodes);
        result.add("relationships", relationships);
        return result;
    }

    private static JsonObject measurementJson(String name, AcquisitionMeasurement measurement) {
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        result.addProperty("state", measurement.isKnown() ? "KNOWN" : "UNKNOWN");
        addNullable(result, "value", measurement.value());
        result.addProperty("estimateKind", measurement.estimateKind()
            .map(Enum::name).orElse("UNSPECIFIED"));
        result.addProperty("unknownReason", measurement.unknownReason() == null ? "" : measurement.unknownReason());
        if (measurement.estimateMetadata().isPresent()) {
            EstimateMetadata metadata = measurement.estimateMetadata().orElseThrow();
            JsonObject details = new JsonObject();
            details.addProperty("method", metadata.method());
            details.addProperty("evaluatorVersion", metadata.evaluatorVersion());
            details.add("parameters", stringMap(metadata.parameters()));
            JsonArray assumptions = new JsonArray();
            metadata.assumptions().forEach(assumptions::add);
            details.add("assumptions", assumptions);
            result.add("estimateMetadata", details);
        } else {
            result.add("estimateMetadata", JsonNull.INSTANCE);
        }
        return result;
    }

    private static JsonObject policyAudit(List<PathObservation> observations, EconomicSnapshot snapshot,
        Map<String, Double> costWeights) {
        List<Boolean> notApplicable = new ArrayList<>();
        List<Boolean> unknownCore = new ArrayList<>();
        List<Boolean> durationExcluded = new ArrayList<>();
        List<Boolean> aggregateMatches = new ArrayList<>();
        for (PathObservation observation : observations) {
            if (observation.vector() == null || observation.acquisitionCost() == null) {
                continue;
            }
            CostVector vector = observation.vector();
            AcquisitionCost cost = observation.acquisitionCost();
            for (FactorDefinition definition : FACTORS) {
                EconomicFactor factor = vector.factors().get(definition.key());
                if (factor == null) {
                    continue;
                }
                if (factor.isNotApplicable()) {
                    notApplicable.add(!cost.normalizedFactors().containsKey(definition.key())
                        && cost.notApplicableFactors().containsKey(definition.key()));
                }
            }
            if (!vector.unknownCoreFactors().isEmpty()) {
                unknownCore.add(cost.status() == ResolutionStatus.UNKNOWN);
            }
            durationExcluded.add(!vector.factors().containsKey("time_cost"));
            if (cost.status() != ResolutionStatus.UNKNOWN) {
                aggregateMatches.add(aggregateMatches(vector, cost, costWeights));
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("notApplicableExcluded", auditState(notApplicable));
        result.addProperty("unknownCoreBlocksAcquisitionCost", auditState(unknownCore));
        result.addProperty("operationDurationExcludedFromDefaultCost", auditState(durationExcluded));
        Set<String> expectedWeightKeys = java.util.Arrays.stream(EconomicChannel.values())
            .map(EconomicChannel::id).collect(java.util.stream.Collectors.toSet());
        result.addProperty("weightSourceIsCentralConfig",
            costWeights.keySet().equals(expectedWeightKeys) ? "PASS" : "FAIL");
        result.addProperty("weightSource", "DynamicFoodConfig.costFactorWeights()");
        List<Double> resolvedValues = new ArrayList<>();
        observations.stream().filter(item -> item.acquisitionCost() != null
            && item.acquisitionCost().cost() != null)
            .forEach(item -> resolvedValues.add(item.acquisitionCost().cost()));
        snapshot.resources().values().stream().filter(item -> item.economicCost().isKnown())
            .forEach(item -> resolvedValues.add(item.economicCost().value()));
        result.addProperty("economicCostRangeValid", resolvedValues.isEmpty() ? "UNKNOWN"
            : resolvedValues.stream().allMatch(value -> Double.isFinite(value) && value >= 0.0D && value <= 1.0D)
                ? "PASS" : "FAIL");
        result.addProperty("noDoubleNormalizationObserved", auditState(aggregateMatches));
        return result;
    }

    private static boolean aggregateMatches(CostVector vector, AcquisitionCost cost,
        Map<String, Double> weights) {
        double maxWeight = cost.normalizedFactors().keySet().stream()
            .mapToDouble(name -> weights.getOrDefault(name, 0.0D)).max().orElse(0.0D);
        if (maxWeight <= 0.0D) {
            return false;
        }
        double numerator = 0.0D;
        double denominator = 0.0D;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            if (!cost.normalizedFactors().containsKey(entry.getKey())) {
                continue;
            }
            EconomicFactor factor = vector.factors().get(entry.getKey());
            if (factor == null || !factor.isKnown()) {
                return false;
            }
            double scaledWeight = entry.getValue() / maxWeight;
            numerator += factor.value() * scaledWeight;
            denominator += scaledWeight;
        }
        return denominator > 0.0D && Math.abs(numerator / denominator - cost.cost()) <= 1.0E-12D;
    }

    private static String auditState(List<Boolean> checks) {
        if (checks.isEmpty()) {
            return "UNKNOWN";
        }
        return checks.stream().allMatch(Boolean::booleanValue) ? "PASS" : "FAIL";
    }

    private static JsonObject analyzersJson(Map<String, AnalyzerCounts> counts) {
        JsonObject result = new JsonObject();
        for (String name : List.of("recipe", "loot", "crop", "worldgen", "trade", "other")) {
            AnalyzerCounts value = counts.getOrDefault(name, new AnalyzerCounts());
            JsonObject item = new JsonObject();
            item.addProperty("resources", value.resources.size());
            item.addProperty("paths", value.paths);
            item.addProperty("coreCompletePaths", value.coreComplete);
            item.addProperty("incompletePaths", value.incomplete);
            item.addProperty("availabilityRejected", value.availabilityRejected);
            item.addProperty("feasibilityRejected", value.feasibilityRejected);
            item.addProperty("acquisitionCostResolved", value.acquisitionResolved);
            item.addProperty("economicCostResolved", value.economicResolved);
            item.add("firstBlockerCounts", longMap(value.firstBlockers));
            result.add(name, item);
        }
        return result;
    }

    private static String analyzer(String sourceType) {
        return switch (sourceType) {
            case "recipe" -> "recipe";
            case "crop" -> "crop";
            case "worldgen_feature", "worldgen", "worldgen_structure_container" -> "worldgen";
            case "villager_trade" -> "trade";
            case "mob_drop", "fishing", "block_loot", "loot_table" -> "loot";
            default -> "other";
        };
    }

    private static String pathId(AcquisitionPath path) {
        return path.sourceType() + ":" + path.sourceId();
    }

    private static JsonObject stringMap(Map<String, String> values) {
        JsonObject result = new JsonObject();
        new TreeMap<>(values).forEach(result::addProperty);
        return result;
    }

    private static JsonObject longMap(Map<String, Long> values) {
        JsonObject result = new JsonObject();
        new TreeMap<>(values).forEach(result::addProperty);
        return result;
    }

    private static JsonObject numberMap(Map<String, Double> values) {
        JsonObject result = new JsonObject();
        new TreeMap<>(values).forEach(result::addProperty);
        return result;
    }

    private static void addNullable(JsonObject object, String name, Double value) {
        if (value == null) {
            object.add(name, JsonNull.INSTANCE);
        } else {
            object.addProperty(name, value);
        }
    }

    private static boolean contains(JsonArray array, String value) {
        for (int index = 0; index < array.size(); index++) {
            if (array.get(index).getAsString().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static final class Counts {
        private long resources;
        private long resourcesWithPath;
        private long resourcesWithoutPath;
        private long paths;
        private long pathsPassingAvailability;
        private long pathsRejectedByAvailability;
        private long pathsPassingFeasibility;
        private long pathsRejectedByFeasibility;
        private long coreCompleteVectors;
        private long coreIncompleteVectors;
        private long acquisitionCostResolved;
        private long acquisitionCostUnknown;
        private long acquisitionCostReached;
        private long economicCostResolved;
        private long economicCostUnknown;
        private long economicCostReached;
        private long coreCompleteVectorsLostBeforeAcquisitionCost;
        private long coreCompleteVectorsReachedAcquisitionCost;
        private final Map<String, long[]> factorStates = new LinkedHashMap<>();
        private final Map<String, Long> firstBlockers = new TreeMap<>();

        private void add(PathObservation item) {
            paths++;
            boolean available = item.availability().equals("TRUE");
            pathsPassingAvailability += available ? 1 : 0;
            pathsRejectedByAvailability += available ? 0 : 1;
            pathsPassingFeasibility += item.feasible() ? 1 : 0;
            pathsRejectedByFeasibility += available && !item.feasibility().eligibleForPrimary() ? 1 : 0;
            boolean coreComplete = item.vector() != null && item.vector().isCoreComplete();
            coreCompleteVectors += coreComplete ? 1 : 0;
            coreIncompleteVectors += coreComplete ? 0 : 1;
            if (item.acquisitionCost() == null
                || item.acquisitionCost().status() == ResolutionStatus.UNKNOWN) {
                acquisitionCostUnknown++;
            } else {
                acquisitionCostResolved++;
            }
            acquisitionCostReached += item.acquisitionReached() ? 1 : 0;
            economicCostReached += item.selectionEligible() ? 1 : 0;
            if (coreComplete && !item.acquisitionReached()) {
                coreCompleteVectorsLostBeforeAcquisitionCost++;
            }
            if (coreComplete && item.acquisitionReached()) {
                coreCompleteVectorsReachedAcquisitionCost++;
            }
            firstBlockers.merge(item.blocker().code(), 1L, Long::sum);
            for (FactorDefinition definition : FACTORS) {
                EconomicFactor factor = item.vector() == null ? null
                    : item.vector().factors().get(definition.key());
                String state = factor == null ? "UNKNOWN" : factor.state().name();
                long[] stateCounts = factorStates.computeIfAbsent(definition.name(), ignored -> new long[3]);
                stateCounts[switch (state) {
                    case "KNOWN" -> 0;
                    case "NOT_APPLICABLE" -> 2;
                    default -> 1;
                }]++;
            }
        }

        private JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("resources", resources);
            result.addProperty("resourcesWithAtLeastOnePath", resourcesWithPath);
            result.addProperty("resourcesWithoutPath", resourcesWithoutPath);
            result.addProperty("paths", paths);
            result.addProperty("pathsPassingAvailability", pathsPassingAvailability);
            result.addProperty("pathsRejectedByAvailability", pathsRejectedByAvailability);
            result.addProperty("pathsPassingFeasibility", pathsPassingFeasibility);
            result.addProperty("pathsRejectedByFeasibility", pathsRejectedByFeasibility);
            result.addProperty("coreCompleteVectors", coreCompleteVectors);
            result.addProperty("coreIncompleteVectors", coreIncompleteVectors);
            for (FactorDefinition definition : FACTORS) {
                long[] values = factorStates.getOrDefault(definition.name(), new long[3]);
                result.addProperty(definition.name() + "Known", values[0]);
                result.addProperty(definition.name() + "Unknown", values[1]);
                result.addProperty(definition.name() + "NA", values[2]);
            }
            long additionalKnown = 0;
            long additionalUnknown = 0;
            long additionalNA = 0;
            for (FactorDefinition definition : FACTORS) {
                if (!definition.core()) {
                    long[] values = factorStates.getOrDefault(definition.name(), new long[3]);
                    additionalKnown += values[0];
                    additionalUnknown += values[1];
                    additionalNA += values[2];
                }
            }
            result.addProperty("additionalKnown", additionalKnown);
            result.addProperty("additionalUnknown", additionalUnknown);
            result.addProperty("additionalNA", additionalNA);
            result.addProperty("acquisitionCostResolved", acquisitionCostResolved);
            result.addProperty("acquisitionCostUnknown", acquisitionCostUnknown);
            result.addProperty("acquisitionCostReached", acquisitionCostReached);
            result.addProperty("economicCostResolved", economicCostResolved);
            result.addProperty("economicCostUnknown", economicCostUnknown);
            result.addProperty("economicCostReached", economicCostReached);
            result.addProperty("coreCompleteVectorsLostBeforeAcquisitionCost",
                coreCompleteVectorsLostBeforeAcquisitionCost);
            result.addProperty("coreCompleteVectorsReachedAcquisitionCost",
                coreCompleteVectorsReachedAcquisitionCost);
            result.add("factorStates", factorStateJson());
            result.add("firstBlockerCounts", longMap(firstBlockers));
            return result;
        }

        private JsonObject factorStateJson() {
            JsonObject result = new JsonObject();
            factorStates.forEach((name, values) -> {
                JsonObject states = new JsonObject();
                states.addProperty("KNOWN", values[0]);
                states.addProperty("UNKNOWN", values[1]);
                states.addProperty("NOT_APPLICABLE", values[2]);
                result.add(name, states);
            });
            return result;
        }
    }

    private static final class AnalyzerCounts {
        private final Set<String> resources = new HashSet<>();
        private long paths;
        private long coreComplete;
        private long incomplete;
        private long availabilityRejected;
        private long feasibilityRejected;
        private long acquisitionResolved;
        private long economicResolved;
        private final Map<String, Long> firstBlockers = new TreeMap<>();

        private void add(String resourceId, PathObservation item) {
            resources.add(resourceId);
            paths++;
            boolean complete = item.vector() != null && item.vector().isCoreComplete();
            coreComplete += complete ? 1 : 0;
            incomplete += complete ? 0 : 1;
            availabilityRejected += item.availability().equals("TRUE") ? 0 : 1;
            feasibilityRejected += item.availability().equals("TRUE")
                && !item.feasibility().eligibleForPrimary() ? 1 : 0;
            acquisitionResolved += item.acquisitionCost() != null
                && item.acquisitionCost().status() != ResolutionStatus.UNKNOWN ? 1 : 0;
            economicResolved += item.selected() && item.resourceResolutionKnown() ? 1 : 0;
            firstBlockers.merge(item.blocker().code(), 1L, Long::sum);
        }
    }

    private record FactorDefinition(String name, String key, boolean core, List<String> measurementAliases) {}
    private record PathBlocker(String code, String stage) {}
    private record InputGroup(List<String> alternatives, Integer quantity, String use) {}
    private record PathObservation(String resourceId, AcquisitionPath path, CostVector vector,
        AcquisitionCost acquisitionCost, FeasibilityResult feasibility, String availability,
        boolean feasible, boolean acquisitionReached, boolean selectionEligible, boolean selected,
        boolean resourceResolutionKnown, PathBlocker blocker, JsonObject json) {}
}
