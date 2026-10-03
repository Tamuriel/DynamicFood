package com.jorjik.dynamicfood.core;

public record EconomicCostComponent(
    EconomicComponentState state,
    Double amount,
    String primitiveId,
    String evidence
) {
    public EconomicCostComponent {
        if (state == null) {
            throw new IllegalArgumentException("economic component state is required");
        }
        if (state == EconomicComponentState.KNOWN) {
            if (amount == null || !Double.isFinite(amount) || amount < 0.0D
                || primitiveId == null || primitiveId.isBlank()) {
                throw new IllegalArgumentException("known component requires a finite amount and primitive");
            }
        } else if (amount != null || primitiveId != null) {
            throw new IllegalArgumentException("unknown and not-applicable components cannot have a value or primitive");
        }
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("economic component requires evidence or an unresolved reason");
        }
    }

    public static EconomicCostComponent known(double amount, String primitiveId, String evidence) {
        return new EconomicCostComponent(EconomicComponentState.KNOWN, amount, primitiveId, evidence);
    }

    public static EconomicCostComponent unknown(String reason) {
        return new EconomicCostComponent(EconomicComponentState.UNKNOWN, null, null, reason);
    }

    public static EconomicCostComponent notApplicable(String reason) {
        return new EconomicCostComponent(EconomicComponentState.NOT_APPLICABLE, null, null, reason);
    }
}
