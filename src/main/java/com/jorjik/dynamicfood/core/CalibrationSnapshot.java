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
    String rankTieMode
) {
    public CalibrationSnapshot {
        population = List.copyOf(population);
        calibratedValues = Map.copyOf(calibratedValues);
        if (gameplayPreset == null || gameplayPreset.isBlank()
            || hungerCurve == null || saturationCurve == null
            || rankTieMode == null || rankTieMode.isBlank()) {
            throw new IllegalArgumentException("calibration snapshot configuration is required");
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
                gameplayPreset, hungerCurve, saturationCurve, rankTieMode);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public CalibrationSnapshot withFoodConfiguration(FoodCalibrationSettings settings) {
        return new CalibrationSnapshot(population, populationSize, candidatePopulationWeight,
            resolvedPopulationWeight, calibrationCoverage, coverageStatus, p05, p50, p95,
            magnitudeWeight, rankWeight, status, calibratedValues, signature,
            settings.gameplayPreset(), settings.hungerCurve(), settings.saturationCurve(),
            "weighted_midrank_exact_cost_ties");
    }
}