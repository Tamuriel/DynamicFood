package com.jorjik.dynamicfood.core;

import java.util.List;
import java.util.Set;

public final class SurvivalAcquirabilityResolver {
    private static final Set<String> SURVIVAL_ACQUISITION_SOURCES = Set.of(
        "recipe", "mob_drop", "fishing", "crop", "block_loot", "worldgen_feature", "villager_trade"
    );
    private static final Set<String> UNAVAILABLE_SOURCES = Set.of(
        "creative_only", "command_only", "debug_only", "test_only", "operator_only", "event_only", "disabled"
    );

    public Result resolve(List<AcquisitionPath> paths) {
        if (paths.isEmpty()) {
            return new Result(SurvivalAcquirability.UNKNOWN,
                "no indexed acquisition path proves survival availability");
        }
        List<String> sourceTypes = paths.stream().map(AcquisitionPath::sourceType).distinct().sorted().toList();
        List<String> unavailable = sourceTypes.stream().filter(UNAVAILABLE_SOURCES::contains).toList();
        if (!unavailable.isEmpty() && unavailable.size() == sourceTypes.size()) {
            return new Result(SurvivalAcquirability.FALSE,
                "all discovered paths are explicitly unavailable in normal survival: " + unavailable);
        }
        List<String> proven = sourceTypes.stream().filter(SURVIVAL_ACQUISITION_SOURCES::contains).toList();
        if (!proven.isEmpty()) {
            return new Result(SurvivalAcquirability.TRUE,
                "survival acquisition is demonstrated by indexed mechanics: " + proven);
        }
        return new Result(SurvivalAcquirability.UNKNOWN,
            "indexed paths do not establish survival availability: " + sourceTypes);
    }

    public record Result(SurvivalAcquirability state, String explanation) {
        public Result {
            if (state == null || explanation == null || explanation.isBlank()) {
                throw new IllegalArgumentException("survival resolution requires a state and explanation");
            }
        }
    }
}
