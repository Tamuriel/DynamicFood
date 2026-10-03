package com.jorjik.dynamicfood.core;

public final class CropCycleResolver {
    private CropCycleResolver() {}

    public static Assessment resolve(AcquisitionMeasurement seedReturnPerCycle,
        AcquisitionMeasurement seedsRequiredPerCycle, AcquisitionMeasurement growthTimeTicks) {
        if (seedReturnPerCycle == null || seedsRequiredPerCycle == null) {
            throw new IllegalArgumentException("crop-cycle seed measurements are required");
        }
        AcquisitionMeasurement growth = growthTimeTicks == null
            ? AcquisitionMeasurement.unknown("crop growth time was not measured")
            : growthTimeTicks;
        if (!seedReturnPerCycle.isKnown() || !seedsRequiredPerCycle.isKnown()) {
            return new Assessment(RenewalState.UNKNOWN,
                AcquisitionMeasurement.unknown("seed return or replant requirement is unresolved"), growth);
        }
        double returned = seedReturnPerCycle.value();
        double required = seedsRequiredPerCycle.value();
        if (returned < 0.0D || required < 0.0D) {
            return new Assessment(RenewalState.UNKNOWN,
                AcquisitionMeasurement.unknown("seed quantities cannot be negative"), growth);
        }
        if (required == 0.0D) {
            return new Assessment(RenewalState.NO_REPLANT_REQUIRED,
                AcquisitionMeasurement.known(0.0D), growth);
        }
        double externalSeeds = Math.max(0.0D, required - returned);
        RenewalState state = returned == 0.0D ? RenewalState.CONSUMPTIVE
            : returned < required ? RenewalState.PARTIALLY_RENEWING : RenewalState.SELF_RENEWING;
        return new Assessment(state, AcquisitionMeasurement.known(externalSeeds), growth);
    }

    public enum RenewalState {
        SELF_RENEWING,
        PARTIALLY_RENEWING,
        CONSUMPTIVE,
        NO_REPLANT_REQUIRED,
        UNKNOWN
    }

    public record Assessment(RenewalState renewalState,
        AcquisitionMeasurement externalSeedsRequiredPerCycle,
        AcquisitionMeasurement growthTimeTicks) {
        public Assessment {
            if (renewalState == null || externalSeedsRequiredPerCycle == null || growthTimeTicks == null) {
                throw new IllegalArgumentException("crop-cycle assessment fields are required");
            }
        }
    }
}
