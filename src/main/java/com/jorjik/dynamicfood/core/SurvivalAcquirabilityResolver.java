package com.jorjik.dynamicfood.core;

import java.util.List;
import java.util.Set;

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

    public record Result(SurvivalAcquirability state, String explanation) {
        public Result {
            if (state == null || explanation == null || explanation.isBlank()) {
                throw new IllegalArgumentException("survival resolution requires a state and explanation");
            }
        }
    }
}
