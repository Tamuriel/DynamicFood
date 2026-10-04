package com.jorjik.dynamicfood.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds calibration from one fixed economic generation and separate population-policy metadata. */
public final class CalibrationSnapshotBuilder {
    private CalibrationSnapshotBuilder() {
    }

    /**
     * The profile collection supplies population policy and identity only. Its economicCost fields are ignored;
     * every calibration candidate must have a result in the supplied snapshot.
     */
    public static CalibrationSnapshot build(
        EconomicSnapshot economicSnapshot,
        Collection<ResourceEconomicProfile> populationMetadata,
        FoodCalibrationSettings settings
    ) {
        if (economicSnapshot == null || populationMetadata == null || settings == null) {
            throw new IllegalArgumentException("economic snapshot, population metadata, and settings are required");
        }
        economicSnapshot.validateForCalibration();
        if (!settings.enabled()) {
            return settings.calibrator().calibrate(List.of())
                .withFoodConfiguration(settings)
                .withConfigurationSignature(settings.signatureContext())
                .withEconomicSnapshotLink(economicSnapshot)
                .withStatus(CalibrationStatus.DISABLED);
        }

        Set<String> resourceIds = new HashSet<>();
        List<ResourceEconomicProfile> calibrationProfiles = new ArrayList<>(populationMetadata.size());
        for (ResourceEconomicProfile metadata : populationMetadata) {
            if (metadata == null || !resourceIds.add(metadata.resourceId())) {
                throw new IllegalArgumentException("population metadata must contain unique, non-null resource profiles");
            }
            EconomicSnapshot.ResourceResult result = economicSnapshot.resources().get(metadata.resourceId());
            if (metadata.isCalibrationCandidate() && result == null) {
                throw new IllegalArgumentException("calibration candidate is absent from EconomicSnapshot: "
                    + metadata.resourceId());
            }

            Double snapshotCost = result == null
                || result.economicResolution().status() != ResolutionStatus.COMPLETE
                || !result.economicCost().isKnown()
                ? null : result.economicCost().value();
            calibrationProfiles.add(new ResourceEconomicProfile(
                metadata.resourceId(),
                metadata.economicResourceIdentity(),
                snapshotCost,
                metadata.confidence(),
                metadata.calibrationWeight(),
                metadata.terminal(),
                metadata.calibrationEligible(),
                metadata.survivalAcquirability(),
                metadata.technical(),
                metadata.derived()
            ));
        }

        return settings.calibrator().calibrate(List.copyOf(calibrationProfiles))
            .withFoodConfiguration(settings)
            .withConfigurationSignature(settings.signatureContext())
            .withEconomicSnapshotLink(economicSnapshot);
    }
}
