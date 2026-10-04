package com.jorjik.dynamicfood.core;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record CalibrationSnapshot(
    List<ResourceEconomicProfile> population,
    int populationSize,
    double candidatePopulationWeight,
    double resolvedPopulationWeight,
    double calibrationCoverage,
    CalibrationCoverageStatus coverageStatus,
    double p05,
    double p50,
    double p95,
    double magnitudeWeight,
    double rankWeight,
    CalibrationStatus status,
    Map<String, CalibratedFoodValue> calibratedValues,
    String signature,
    String gameplayPreset,
    FoodValueCurve hungerCurve,
    FoodValueCurve saturationCurve,
    String rankTieMode,
    long economicGeneration,
    String economicContentSignature
) {
    public CalibrationSnapshot(
        List<ResourceEconomicProfile> population,
        int populationSize,
        double candidatePopulationWeight,
        double resolvedPopulationWeight,
        double calibrationCoverage,
        CalibrationCoverageStatus coverageStatus,
        double p05,
        double p50,
        double p95,
        double magnitudeWeight,
        double rankWeight,
        CalibrationStatus status,
        Map<String, CalibratedFoodValue> calibratedValues,
        String signature,
        String gameplayPreset,
        FoodValueCurve hungerCurve,
        FoodValueCurve saturationCurve,
        String rankTieMode
    ) {
        this(population, populationSize, candidatePopulationWeight, resolvedPopulationWeight,
            calibrationCoverage, coverageStatus, p05, p50, p95, magnitudeWeight, rankWeight, status,
            calibratedValues, signature, gameplayPreset, hungerCurve, saturationCurve, rankTieMode, 0L, null);
    }

    public CalibrationSnapshot {
        population = List.copyOf(population);
        calibratedValues = Map.copyOf(calibratedValues);
        if (gameplayPreset == null || gameplayPreset.isBlank()
            || hungerCurve == null || saturationCurve == null
            || rankTieMode == null || rankTieMode.isBlank()) {
            throw new IllegalArgumentException("calibration snapshot configuration is required");
        }
        if (economicGeneration < 0
            || (economicGeneration == 0) != (economicContentSignature == null)
            || economicGeneration > 0 && economicContentSignature.isBlank()) {
            throw new IllegalArgumentException("economic snapshot linkage must contain both a positive generation "
                + "and a non-empty content signature, or neither");
        }
    }

    public List<CalibrationAnchor> hungerAnchors() {
        return hungerCurve.anchors();
    }

    public List<CalibrationAnchor> saturationAnchors() {
        return saturationCurve.anchors();
    }

    public OptionalDouble foodIndexFor(String resourceId) {
        CalibratedFoodValue value = calibratedValues.get(resourceId);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value.foodIndex());
    }

    public CalibrationSnapshot withConfigurationSignature(String configuration) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest((signature + "|" + configuration).getBytes(StandardCharsets.UTF_8));
            return new CalibrationSnapshot(population, populationSize, candidatePopulationWeight,
                resolvedPopulationWeight, calibrationCoverage, coverageStatus, p05, p50, p95,
                magnitudeWeight, rankWeight, status, calibratedValues, HexFormat.of().formatHex(hash),
                gameplayPreset, hungerCurve, saturationCurve, rankTieMode, economicGeneration,
                economicContentSignature);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public CalibrationSnapshot withFoodConfiguration(FoodCalibrationSettings settings) {
        return new CalibrationSnapshot(population, populationSize, candidatePopulationWeight,
            resolvedPopulationWeight, calibrationCoverage, coverageStatus, p05, p50, p95,
            magnitudeWeight, rankWeight, status, calibratedValues, signature,
            settings.gameplayPreset(), settings.hungerCurve(), settings.saturationCurve(),
            "weighted_midrank_exact_cost_ties", economicGeneration, economicContentSignature);
    }

    public CalibrationSnapshot withStatus(CalibrationStatus replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("calibration status is required");
        }
        if (replacement == CalibrationStatus.DISABLED && !calibratedValues.isEmpty()) {
            throw new IllegalArgumentException("disabled calibration cannot contain calibrated values");
        }
        return new CalibrationSnapshot(population, populationSize, candidatePopulationWeight,
            resolvedPopulationWeight, calibrationCoverage, coverageStatus, p05, p50, p95,
            magnitudeWeight, rankWeight, replacement, calibratedValues, signature, gameplayPreset,
            hungerCurve, saturationCurve, rankTieMode, economicGeneration, economicContentSignature);
    }

    public CalibrationSnapshot withEconomicSnapshotLink(EconomicSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("economic snapshot linkage is required");
        }
        snapshot.validateForCalibration();
        if (economicGeneration != 0
            && (economicGeneration != snapshot.generation()
                || !economicContentSignature.equals(snapshot.signature()))) {
            throw new IllegalArgumentException("calibration snapshot is already linked to a different "
                + "economic snapshot generation or signature");
        }
        return new CalibrationSnapshot(population, populationSize, candidatePopulationWeight,
            resolvedPopulationWeight, calibrationCoverage, coverageStatus, p05, p50, p95,
            magnitudeWeight, rankWeight, status, calibratedValues, signature, gameplayPreset,
            hungerCurve, saturationCurve, rankTieMode, snapshot.generation(), snapshot.signature());
    }

    public void validateForPublication(EconomicSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("economic snapshot is required");
        }
        snapshot.validateForCalibration();
        if (economicGeneration != snapshot.generation()
            || !snapshot.signature().equals(economicContentSignature)) {
            throw new IllegalArgumentException("calibration snapshot must be linked to the exact economic "
                + "generation and content signature");
        }
        if (status == CalibrationStatus.DISABLED && !calibratedValues.isEmpty()) {
            throw new IllegalArgumentException("disabled calibration snapshot cannot contain calibrated values");
        }

        for (ResourceEconomicProfile profile : population) {
            EconomicSnapshot.ResourceResult result = snapshot.resources().get(profile.resourceId());
            if (profile.isCalibrationCandidate() && result == null) {
                throw new IllegalArgumentException("calibration candidate is absent from EconomicSnapshot: "
                    + profile.resourceId());
            }
            Double expectedCost = result == null || result.economicResolution().status() != ResolutionStatus.COMPLETE
                || !result.economicCost().isKnown()
                ? null : result.economicCost().value();
            if (!java.util.Objects.equals(profile.economicCost(), expectedCost)) {
                throw new IllegalArgumentException("calibration population cost does not match EconomicSnapshot: "
                    + profile.resourceId());
            }
        }
        calibratedValues.forEach((resourceId, value) -> {
            EconomicSnapshot.ResourceResult result = snapshot.resources().get(resourceId);
            if (status == CalibrationStatus.DISABLED || result == null
                || result.economicResolution().status() != ResolutionStatus.COMPLETE
                || !result.economicCost().isKnown()
                || Double.compare(value.economicCost(), result.economicCost().value()) != 0) {
                throw new IllegalArgumentException("calibrated value does not match EconomicSnapshot: "
                    + resourceId);
            }
        });
    }
}