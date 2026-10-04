package com.jorjik.dynamicfood.core;

import java.util.ArrayList;
import java.util.List;

public record EconomicCostSchedule(
    EconomicCostComponent startup,
    EconomicCostComponent recurringPerOutput,
    String evidence
) {
    public EconomicCostSchedule {
        if (startup == null || recurringPerOutput == null) {
            throw new IllegalArgumentException("startup and recurring applicability must both be reported");
        }
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("schedule evidence is required");
        }
    }

    public EconomicCost resolve(int observationHorizon) {
        if (observationHorizon < 1) {
            throw new IllegalArgumentException("observation horizon must be positive");
        }
        if (startup.state() == EconomicComponentState.UNKNOWN
            || recurringPerOutput.state() == EconomicComponentState.UNKNOWN) {
            return EconomicCost.unknown("startup/recurring schedule is unresolved: "
                + unresolvedReasons());
        }
        String primitiveId = null;
        if (startup.state() == EconomicComponentState.KNOWN) {
            primitiveId = startup.primitiveId();
        }
        if (recurringPerOutput.state() == EconomicComponentState.KNOWN) {
            if (primitiveId != null && !primitiveId.equals(recurringPerOutput.primitiveId())) {
                return EconomicCost.unknown("startup and recurring components use incompatible primitives: "
                    + primitiveId + " vs " + recurringPerOutput.primitiveId());
            }
            primitiveId = recurringPerOutput.primitiveId();
        }
        if (primitiveId == null) {
            return EconomicCost.unknown("startup and recurring components are both not applicable");
        }

        double total = startup.state() == EconomicComponentState.KNOWN ? startup.amount() : 0.0D;
        if (recurringPerOutput.state() == EconomicComponentState.KNOWN) {
            total += recurringPerOutput.amount() * observationHorizon;
        }
        if (!Double.isFinite(total) || total < 0.0D || total > 1.0D) {
            return EconomicCost.unknown("horizon aggregation is outside the policy scale [0,1]");
        }
        return EconomicCost.known(total, primitiveId, observationHorizon,
            "startup once + recurring per output × observation horizon; " + evidence);
    }

    private List<String> unresolvedReasons() {
        ArrayList<String> reasons = new ArrayList<>();
        if (startup.state() == EconomicComponentState.UNKNOWN) {
            reasons.add("startup: " + startup.evidence());
        }
        if (recurringPerOutput.state() == EconomicComponentState.UNKNOWN) {
            reasons.add("recurring: " + recurringPerOutput.evidence());
        }
        return List.copyOf(reasons);
    }
}
