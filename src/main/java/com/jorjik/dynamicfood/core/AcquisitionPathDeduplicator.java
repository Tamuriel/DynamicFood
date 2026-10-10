package com.jorjik.dynamicfood.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

public final class AcquisitionPathDeduplicator {
    static final Comparator<AcquisitionPath> PATH_ORDER = Comparator
        .comparing(AcquisitionPath::itemId)
        .thenComparing(AcquisitionPath::sourceType)
        .thenComparing(AcquisitionPath::sourceId)
        .thenComparing(path -> path.pathIdentity().canonicalKey());

    private AcquisitionPathDeduplicator() {
    }

    public static List<AcquisitionPath> deduplicate(List<AcquisitionPath> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        Map<String, List<AcquisitionPath>> groups = new TreeMap<>();
        for (AcquisitionPath path : paths) {
            Objects.requireNonNull(path, "acquisition path is required");
            groups.computeIfAbsent(path.pathIdentity().canonicalKey(), ignored -> new ArrayList<>()).add(path);
        }

        List<AcquisitionPath> result = new ArrayList<>();
        groups.values().forEach(group -> result.add(group.size() == 1 ? group.getFirst() : merge(group)));
        result.sort(PATH_ORDER);
        return List.copyOf(result);
    }

    private static AcquisitionPath merge(List<AcquisitionPath> paths) {
        AcquisitionPath first = paths.getFirst();
        TreeMap<String, String> attributes = new TreeMap<>();
        TreeSet<String> conflicts = new TreeSet<>();
        mergeAttributes(paths, attributes, conflicts);

        Map<String, AcquisitionMeasurement> measurements = mergeMeasurements(paths, attributes, conflicts);
        Map<String, EconomicFactor> feasibility = mergeFactors(
            paths.stream().map(AcquisitionPath::feasibilityFactors).toList(),
            "feasibility", attributes, conflicts);
        Map<Integer, CostVector> costs = mergeCosts(paths, attributes, conflicts);
        WorldgenCausalEvidence causalEvidence = paths.stream()
            .map(path -> path.evidence().worldgenCausalEvidence())
            .reduce(WorldgenCausalEvidence.empty(), WorldgenCausalEvidence::merge);
        if (causalEvidence.hasConflictingDefinitions()) {
            conflicts.add("worldgen_causal_definition");
        }
        EconomicCost economicCost = mergeEconomicCosts(paths, attributes, conflicts);
        EconomicCostSchedule schedule = mergeSchedules(paths, attributes, conflicts);
        if (schedule == null && paths.stream().anyMatch(path -> path.economicCostSchedule() != null)) {
            economicCost = EconomicCost.unknown("conflicting duplicate acquisition path economic schedules");
        }
        if (!conflicts.isEmpty()) {
            attributes.put("acquisition_path_deduplication_status", "CONFLICT");
            attributes.put("acquisition_path_deduplication_conflicts", jsonStrings(conflicts));
            attributes.put("acquisition_path_deduplication_reason",
                "duplicate paths share mechanical identity but contain conflicting evidence");
        }

        TreeSet<String> confidenceValues = new TreeSet<>();
        paths.forEach(path -> confidenceValues.add(Double.toHexString(path.confidence())));
        if (confidenceValues.size() > 1) {
            attributes.put("deduplication.confidence_values", jsonStrings(confidenceValues));
        }
        TreeSet<String> riskValues = new TreeSet<>();
        paths.forEach(path -> {
            riskValues.add(path.risk() == null ? "<unknown>" : Double.toHexString(path.risk()));
        });
        boolean riskConflict = riskValues.size() > 1;
        if (riskConflict) {
            attributes.put("deduplication.risk_values", jsonStrings(riskValues));
            conflicts.add("risk");
            attributes.put("acquisition_path_deduplication_status", "CONFLICT");
            attributes.put("acquisition_path_deduplication_conflicts", jsonStrings(conflicts));
        }
        TreeSet<String> hardFailedValues = new TreeSet<>();
        paths.forEach(path -> hardFailedValues.add(Boolean.toString(path.hardFailed())));
        if (hardFailedValues.size() > 1) {
            attributes.put("deduplication.hard_failed_values", jsonStrings(hardFailedValues));
            conflicts.add("hard_failed");
            attributes.put("acquisition_path_deduplication_status", "CONFLICT");
            attributes.put("acquisition_path_deduplication_conflicts", jsonStrings(conflicts));
        }

        List<AcquisitionIngredient> inputs = first.evidence().inputs();
        AcquisitionEvidence evidence = new AcquisitionEvidence(measurements, attributes, inputs, causalEvidence);
        return new AcquisitionPath(first.itemId(), first.sourceType(), first.sourceId(),
            paths.stream().mapToDouble(AcquisitionPath::confidence).min().orElse(first.confidence()),
            first.renewability(), riskConflict ? null : first.risk(), first.repeatable(),
            paths.stream().anyMatch(AcquisitionPath::hardFailed), feasibility, costs, evidence,
            economicCost, schedule, first.declaredIdentity());
    }

    private static void mergeAttributes(List<AcquisitionPath> paths, Map<String, String> target,
        TreeSet<String> conflicts) {
        TreeSet<String> keys = new TreeSet<>();
        paths.forEach(path -> keys.addAll(path.evidence().attributes().keySet()));
        for (String key : keys) {
            TreeSet<String> values = new TreeSet<>();
            paths.forEach(path -> {
                String value = path.evidence().attributes().get(key);
                if (value != null) {
                    values.add(value);
                }
            });
            if (values.size() == 1) {
                target.put(key, values.first());
            } else if (!values.isEmpty() && isProvenance(key)) {
                putVariants(target, "deduplication.provenance.", key, values);
            } else if (!values.isEmpty()) {
                preserveConflictValues(target, "attribute", key, values);
                conflicts.add("attribute:" + key);
                String state = conflictState(key);
                if (state != null) {
                    target.put(key, state);
                }
            }
        }
    }

    private static Map<String, AcquisitionMeasurement> mergeMeasurements(List<AcquisitionPath> paths,
        Map<String, String> attributes, TreeSet<String> conflicts) {
        TreeMap<String, AcquisitionMeasurement> merged = new TreeMap<>();
        TreeSet<String> names = new TreeSet<>();
        paths.forEach(path -> names.addAll(path.evidence().measurements().keySet()));
        for (String name : names) {
            List<AcquisitionMeasurement> measurements = paths.stream()
                .map(path -> path.evidence().measurements().get(name))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
            if (measurements.size() == 1) {
                merged.put(name, measurements.getFirst());
                continue;
            }
            TreeSet<String> variants = new TreeSet<>();
            measurements.forEach(measurement -> variants.add(measurementJson(measurement)));
            preserveConflictValues(attributes, "measurement", name, variants);
            conflicts.add("measurement:" + name);
            merged.put(name, AcquisitionMeasurement.unknown(
                "conflicting duplicate acquisition path measurement: " + name));
        }
        return Map.copyOf(merged);
    }

    private static Map<String, EconomicFactor> mergeFactors(List<Map<String, EconomicFactor>> maps,
        String name, Map<String, String> attributes, TreeSet<String> conflicts) {
        TreeSet<String> keys = new TreeSet<>();
        maps.forEach(map -> keys.addAll(map.keySet()));
        TreeMap<String, EconomicFactor> merged = new TreeMap<>();
        for (String key : keys) {
            List<EconomicFactor> factors = maps.stream().map(map -> map.get(key))
                .filter(Objects::nonNull).distinct().toList();
            if (factors.size() == 1) {
                merged.put(key, factors.getFirst());
                continue;
            }
            TreeSet<String> variants = new TreeSet<>();
            factors.forEach(factor -> variants.add(factor.state() + ":"
                + (factor.value() == null ? "" : Double.toHexString(factor.value())) + ":" + factor.reason()));
            preserveConflictValues(attributes, name + "_factor", key, variants);
            conflicts.add(name + "_factor:" + key);
            merged.put(key, EconomicFactor.unknown("conflicting duplicate acquisition path factor: " + key));
        }
        return Map.copyOf(merged);
    }

    private static Map<Integer, CostVector> mergeCosts(List<AcquisitionPath> paths,
        Map<String, String> attributes, TreeSet<String> conflicts) {
        TreeSet<Integer> horizons = new TreeSet<>();
        paths.forEach(path -> horizons.addAll(path.costsByHorizon().keySet()));
        TreeMap<Integer, CostVector> result = new TreeMap<>();
        for (Integer horizon : horizons) {
            List<Map<String, EconomicFactor>> factorMaps = paths.stream()
                .map(path -> path.costsByHorizon().get(horizon))
                .filter(Objects::nonNull)
                .map(CostVector::factors)
                .toList();
            Map<String, EconomicFactor> factors = mergeFactors(factorMaps, "cost_vector",
                attributes, conflicts);
            result.put(horizon, new CostVector(horizon, factors));
        }
        return Map.copyOf(result);
    }

    private static EconomicCost mergeEconomicCosts(List<AcquisitionPath> paths,
        Map<String, String> attributes, TreeSet<String> conflicts) {
        List<EconomicCost> values = paths.stream().map(AcquisitionPath::economicCost).distinct().toList();
        if (values.size() == 1) {
            return values.getFirst();
        }
        TreeSet<String> variants = new TreeSet<>();
        values.forEach(value -> variants.add(value.toString()));
        preserveConflictValues(attributes, "economic_cost", "value", variants);
        conflicts.add("economic_cost");
        return EconomicCost.unknown("conflicting duplicate acquisition path EconomicCost evidence");
    }

    private static EconomicCostSchedule mergeSchedules(List<AcquisitionPath> paths,
        Map<String, String> attributes, TreeSet<String> conflicts) {
        List<EconomicCostSchedule> schedules = paths.stream().map(AcquisitionPath::economicCostSchedule)
            .distinct().toList();
        if (schedules.size() == 1) {
            return schedules.getFirst();
        }
        TreeSet<String> variants = new TreeSet<>();
        schedules.forEach(schedule -> variants.add(schedule == null ? "<none>" : schedule.toString()));
        preserveConflictValues(attributes, "economic_schedule", "value", variants);
        conflicts.add("economic_schedule");
        return null;
    }

    private static String conflictState(String key) {
        if (key.equals("source_availability_classification")
            || key.equals("recipe_operation_availability")) {
            return "UNKNOWN";
        }
        if (key.endsWith(".status")) {
            return "CONFLICT";
        }
        return null;
    }

    private static boolean isProvenance(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("provenance") || lower.endsWith(".provider_id")
            || lower.endsWith(".provider_version");
    }

    private static void preserveConflictValues(Map<String, String> attributes, String kind, String key,
        Collection<String> values) {
        putVariants(attributes, "deduplication.conflicting_evidence." + kind + ".", key, values);
    }

    private static void putVariants(Map<String, String> attributes, String prefix, String key,
        Collection<String> values) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.getBytes(StandardCharsets.UTF_8));
        attributes.put(prefix + encoded + ".key", key);
        attributes.put(prefix + encoded + ".values", jsonStrings(values));
    }

    private static String jsonStrings(Collection<String> values) {
        JsonArray array = new JsonArray();
        new TreeSet<>(values).forEach(value -> array.add(new JsonPrimitive(value)));
        return array.toString();
    }

    private static String measurementJson(AcquisitionMeasurement measurement) {
        JsonObject object = new JsonObject();
        object.add("value", measurement.value() == null
            ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(Double.toHexString(measurement.value())));
        object.addProperty("unknown_reason", measurement.unknownReason());
        object.addProperty("estimate_kind", measurement.estimateKind().map(Enum::name).orElse(null));
        measurement.estimateMetadata().ifPresent(metadata -> {
            JsonObject details = new JsonObject();
            details.addProperty("method", metadata.method());
            details.addProperty("evaluator_version", metadata.evaluatorVersion());
            JsonObject parameters = new JsonObject();
            new TreeMap<>(metadata.parameters()).forEach(parameters::addProperty);
            details.add("parameters", parameters);
            JsonArray assumptions = new JsonArray();
            metadata.assumptions().forEach(assumptions::add);
            details.add("assumptions", assumptions);
            object.add("metadata", details);
        });
        return object.toString();
    }
}
