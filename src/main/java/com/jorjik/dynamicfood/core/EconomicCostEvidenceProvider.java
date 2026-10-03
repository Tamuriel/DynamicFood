package com.jorjik.dynamicfood.core;

@FunctionalInterface
public interface EconomicCostEvidenceProvider {
    EconomicCost resolve(String itemId, int observationHorizon);

    static EconomicCostEvidenceProvider unknown() {
        return (itemId, horizon) -> EconomicCost.unknown(
            "no independent economic primitive evidence is registered for " + itemId);
    }
}
