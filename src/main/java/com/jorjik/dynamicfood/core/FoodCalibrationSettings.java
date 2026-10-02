package com.jorjik.dynamicfood.core;

import java.util.List;
import java.util.Locale;

public record FoodCalibrationSettings(
    boolean enabled,
    String disabledMode,
    int minimumPopulation,
    double magnitudeWeight,
    double rankWeight,
    double lowerQuantile,
    double upperQuantile,
    double lowCoverageThreshold,
    double mediumCoverageThreshold,
    String gameplayPreset,
    List<CalibrationAnchor> customHungerAnchors,
    List<CalibrationAnchor> customSaturationAnchors,
    double configuredFallbackNutrition,
    double configuredFallbackSaturation,
    String populationScope,
    List<String> populationTags
) {
    public FoodCalibrationSettings {
        disabledMode = "configured_fallback".equalsIgnoreCase(disabledMode) ? "configured_fallback" : "vanilla";
        populationScope = switch (populationScope == null ? "all_survival_economic_resources"
            : populationScope.toLowerCase(Locale.ROOT)) {
            case "configured_tag", "manual" -> populationScope.toLowerCase(Locale.ROOT);
            default -> "all_survival_economic_resources";
        };
        populationTags = List.copyOf(populationTags);
        gameplayPreset = switch (gameplayPreset == null ? "medium" : gameplayPreset.toLowerCase(java.util.Locale.ROOT)) {
            case "easy", "hard", "very_hard" -> gameplayPreset.toLowerCase(java.util.Locale.ROOT);
            default -> "medium";
        };
        customHungerAnchors = List.copyOf(customHungerAnchors);
        customSaturationAnchors = List.copyOf(customSaturationAnchors);
        if (!Double.isFinite(configuredFallbackNutrition) || configuredFallbackNutrition < 0.0D
            || !Double.isFinite(configuredFallbackSaturation) || configuredFallbackSaturation < 0.0D) {
            throw new IllegalArgumentException("configured fallback values must be finite and non-negative");
        }
        if (!customHungerAnchors.isEmpty()) {
            new FoodValueCurve(customHungerAnchors);
        }
        if (!customSaturationAnchors.isEmpty()) {
            new FoodValueCurve(customSaturationAnchors);
        }
    }

    public FoodCalibrationSettings(boolean enabled, String disabledMode, int minimumPopulation,
        double magnitudeWeight, double rankWeight, double lowerQuantile, double upperQuantile,
        double lowCoverageThreshold, double mediumCoverageThreshold, String gameplayPreset,
        List<CalibrationAnchor> customHungerAnchors, List<CalibrationAnchor> customSaturationAnchors,
        double configuredFallbackNutrition, double configuredFallbackSaturation) {
        this(enabled, disabledMode, minimumPopulation, magnitudeWeight, rankWeight, lowerQuantile, upperQuantile,
            lowCoverageThreshold, mediumCoverageThreshold, gameplayPreset, customHungerAnchors,
            customSaturationAnchors, configuredFallbackNutrition, configuredFallbackSaturation,
            "all_survival_economic_resources", List.of());
    }

    public FoodValueCurve hungerCurve() {
        return customHungerAnchors.isEmpty()
            ? FoodValueCurve.preset(gameplayPreset, false)
            : new FoodValueCurve(customHungerAnchors);
    }

    public FoodValueCurve saturationCurve() {
        return customSaturationAnchors.isEmpty()
            ? FoodValueCurve.preset(gameplayPreset, true)
            : new FoodValueCurve(customSaturationAnchors);
    }

    public FoodValueCalibrator calibrator() {
        return new FoodValueCalibrator(minimumPopulation, magnitudeWeight, rankWeight,
            lowerQuantile, upperQuantile, lowCoverageThreshold, mediumCoverageThreshold);
    }

    public String signatureContext() {
        return enabled + "|" + disabledMode + "|" + gameplayPreset + "|"
            + populationScope + "|" + populationTags + "|"
            + customHungerAnchors + "|" + customSaturationAnchors + "|"
            + Double.toHexString(configuredFallbackNutrition) + "|"
            + Double.toHexString(configuredFallbackSaturation);
    }

    public static FoodCalibrationSettings defaults() {
        return new FoodCalibrationSettings(true, "vanilla", 16, 0.70D, 0.30D,
            0.05D, 0.95D, 0.50D, 0.80D, "medium", List.of(), List.of(), 2.0D, 0.0D);
    }

    public boolean allowsAutomaticDiscovery() {
        return !populationScope.equals("manual");
    }

    public boolean allowsConfiguredProfile(boolean matchesConfiguredTag) {
        return !populationScope.equals("configured_tag") || matchesConfiguredTag;
    }

    public boolean allowsAutomaticCandidate(boolean matchesConfiguredTag) {
        return allowsAutomaticDiscovery()
            && (!populationScope.equals("configured_tag") || matchesConfiguredTag);
    }
}