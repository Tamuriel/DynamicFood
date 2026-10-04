package com.jorjik.dynamicfood.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class SurvivalAcquirabilityResolver {
    private static final Set<String> UNAVAILABLE_SOURCES = Set.of(
        "creative_only", "command_only", "debug_only", "test_only", "operator_only", "event_only", "disabled"
    );

    public Result resolve(List<AcquisitionPath> paths) {
        if (paths.isEmpty()) {
            return new Result(SurvivalAcquirability.UNKNOWN,
                "no indexed acquisition path proves survival availability");
        }
        List<String> sourceTypes = paths.stream().map(AcquisitionPath::sourceType).distinct().sorted().toList();
        List<AcquisitionPath> provenSurvivalPaths = paths.stream()
            .filter(path -> "TRUE".equalsIgnoreCase(
                path.evidence().attributes().get("source_availability_classification")))
            .toList();
        if (!provenSurvivalPaths.isEmpty()) {
            List<String> proven = provenSurvivalPaths.stream()
                .map(AcquisitionPath::sourceType).distinct().sorted().toList();
            return new Result(SurvivalAcquirability.TRUE,
                "survival availability is explicitly evidenced for acquisition paths: " + proven);
        }
        List<AcquisitionPath> unavailablePaths = paths.stream().filter(path ->
            UNAVAILABLE_SOURCES.contains(path.sourceType())
                || "FALSE".equalsIgnoreCase(
                    path.evidence().attributes().get("source_availability_classification")))
            .toList();
        if (!unavailablePaths.isEmpty() && unavailablePaths.size() == paths.size()) {
            List<String> unavailable = unavailablePaths.stream()
                .map(AcquisitionPath::sourceType).distinct().sorted().toList();
            return new Result(SurvivalAcquirability.FALSE,
                "all discovered paths are explicitly unavailable in normal survival: " + unavailable);
        }
        return new Result(SurvivalAcquirability.UNKNOWN,
            "acquisition-source discovery does not prove survival availability: " + sourceTypes);
    }

    public Map<String, List<AcquisitionPath>> resolvePathAvailability(
        Map<String, List<AcquisitionPath>> pathsByResource
    ) {
        if (pathsByResource == null) {
            throw new IllegalArgumentException("acquisition paths by resource are required");
        }
        Map<String, List<AcquisitionPath>> input = new TreeMap<>();
        pathsByResource.forEach((resourceId, paths) ->
            input.put(resourceId, List.copyOf(paths)));
        ResolutionContext context = new ResolutionContext(input);
        Map<String, List<AcquisitionPath>> resolved = new TreeMap<>();
        input.forEach((resourceId, paths) -> {
            List<AcquisitionPath> classified = paths.stream().map(path -> {
                Result availability = context.resolvePath(path);
                Map<String, String> attributes = new TreeMap<>(path.evidence().attributes());
                attributes.put("source_availability_classification", availability.state().name());
                attributes.put("survival_availability", availability.explanation());
                return path.withEvidence(new AcquisitionEvidence(path.evidence().measurements(),
                    attributes, path.evidence().inputs()));
            }).toList();
            resolved.put(resourceId, classified);
        });
        return Map.copyOf(resolved);
    }

    public record Result(SurvivalAcquirability state, String explanation) {
        public Result {
            if (state == null || explanation == null || explanation.isBlank()) {
                throw new IllegalArgumentException("survival resolution requires a state and explanation");
            }
        }
    }

    private static final class ResolutionContext {
        private final Map<String, List<AcquisitionPath>> pathsByResource;
        private final Map<String, Result> resourceResults = new TreeMap<>();
        private final Set<String> visiting = new java.util.HashSet<>();

        private ResolutionContext(Map<String, List<AcquisitionPath>> pathsByResource) {
            this.pathsByResource = pathsByResource;
        }

        private Result resolveResource(String resourceId) {
            Result cached = resourceResults.get(resourceId);
            if (cached != null) {
                return cached;
            }
            if (!visiting.add(resourceId)) {
                return new Result(SurvivalAcquirability.UNKNOWN,
                    "recipe dependency cycle prevents survival availability proof for " + resourceId);
            }
            List<AcquisitionPath> paths = pathsByResource.getOrDefault(resourceId, List.of());
            List<Result> pathResults = paths.stream().map(this::resolvePath).toList();
            visiting.remove(resourceId);

            Result result;
            if (pathResults.stream().anyMatch(path ->
                path.state() == SurvivalAcquirability.TRUE)) {
                result = new Result(SurvivalAcquirability.TRUE,
                    "at least one acquisition path has explicit survival evidence");
            } else if (!pathResults.isEmpty() && pathResults.stream().allMatch(path ->
                path.state() == SurvivalAcquirability.FALSE)) {
                result = new Result(SurvivalAcquirability.FALSE,
                    "all discovered acquisition paths are explicitly unavailable in survival");
            } else if (pathResults.isEmpty()) {
                result = new Result(SurvivalAcquirability.UNKNOWN,
                    "no acquisition path is indexed for required recipe input " + resourceId);
            } else {
                Result unresolved = pathResults.stream()
                    .filter(path -> path.state() == SurvivalAcquirability.UNKNOWN)
                    .findFirst().orElseThrow();
                result = new Result(SurvivalAcquirability.UNKNOWN,
                    "no survival-valid path is proven for recipe input " + resourceId
                        + ": " + unresolved.explanation());
            }
            resourceResults.put(resourceId, result);
            return result;
        }

        private Result resolvePath(AcquisitionPath path) {
            Map<String, String> attributes = path.evidence().attributes();
            if (!"recipe".equals(path.sourceType())
                || !attributes.containsKey("recipe_operation_availability")) {
                return explicitAvailability(path);
            }

            Result operation = classification(attributes.get("recipe_operation_availability"),
                attributes.getOrDefault("recipe_operation_availability_reason",
                    "recipe operation access evidence is missing"));
            if (operation.state() == SurvivalAcquirability.FALSE) {
                return operation;
            }
            List<com.jorjik.dynamicfood.graph.AcquisitionIngredient> inputs = path.evidence().inputs();
            if (inputs.isEmpty()) {
                return new Result(SurvivalAcquirability.UNKNOWN,
                    "active recipe source has no resolved input evidence");
            }

            List<String> unresolved = new java.util.ArrayList<>();
            for (var input : inputs) {
                if (input.alternatives().isEmpty()) {
                    unresolved.add("recipe input alternatives are unresolved");
                    continue;
                }
                List<Result> alternatives = input.alternatives().stream()
                    .map(this::resolveResource).toList();
                if (alternatives.stream().noneMatch(value ->
                    value.state() == SurvivalAcquirability.TRUE)) {
                    if (alternatives.stream().allMatch(value ->
                        value.state() == SurvivalAcquirability.FALSE)) {
                        return new Result(SurvivalAcquirability.FALSE,
                            "required recipe input is unavailable through every alternative: "
                                + input.alternatives());
                    }
                    unresolved.add("no alternative is proven survival-accessible for " + input.alternatives());
                }
            }
            if (!unresolved.isEmpty()) {
                return new Result(SurvivalAcquirability.UNKNOWN,
                    "recipe source is active, but " + String.join("; ", unresolved));
            }
            if (operation.state() != SurvivalAcquirability.TRUE) {
                return operation;
            }
            return new Result(SurvivalAcquirability.TRUE,
                "active server recipe source and every required input have survival evidence");
        }

        private Result explicitAvailability(AcquisitionPath path) {
            String classification = path.evidence().attributes()
                .get("source_availability_classification");
            if (classification == null && UNAVAILABLE_SOURCES.contains(path.sourceType())) {
                return new Result(SurvivalAcquirability.FALSE,
                    "acquisition source is explicitly unavailable in normal survival: " + path.sourceType());
            }
            return classification(classification,
                "acquisition source does not provide survival-availability evidence: "
                    + path.sourceType() + "/" + path.sourceId());
        }

        private static Result classification(String value, String reason) {
            if ("TRUE".equalsIgnoreCase(value)) {
                return new Result(SurvivalAcquirability.TRUE, reason);
            }
            if ("FALSE".equalsIgnoreCase(value)) {
                return new Result(SurvivalAcquirability.FALSE, reason);
            }
            return new Result(SurvivalAcquirability.UNKNOWN, reason);
        }
    }
}
