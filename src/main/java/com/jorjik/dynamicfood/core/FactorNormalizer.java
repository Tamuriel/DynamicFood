package com.jorjik.dynamicfood.core;

import java.util.List;

public final class FactorNormalizer {
    private FactorNormalizer() {}

    public static EconomicFactor linear(double raw, double cap) {
        if (!validMetric(raw) || !Double.isFinite(cap) || cap <= 0.0D) {
            return EconomicFactor.unknown("invalid linear metric or cap");
        }
        return EconomicFactor.known(clamp(raw / cap));
    }

    public static EconomicFactor logarithmic(double raw, double reference, double cap) {
        if (!validMetric(raw) || !Double.isFinite(reference) || reference <= 0.0D
            || !Double.isFinite(cap) || cap <= 0.0D) {
            return EconomicFactor.unknown("invalid logarithmic metric, reference or cap");
        }
        if (raw == 0.0D) {
            return EconomicFactor.known(0.0D);
        }
        if (raw >= cap) {
            return EconomicFactor.known(1.0D);
        }
        double denominator = softplus(Math.log(cap) - Math.log(reference));
        double normalized = softplus(Math.log(raw) - Math.log(reference)) / denominator;
        return Double.isFinite(normalized)
            ? EconomicFactor.known(clamp(normalized))
            : EconomicFactor.unknown("log normalization produced a non-finite value");
    }

    public static EconomicFactor binary(boolean enabled, double enabledValue) {
        if (!Double.isFinite(enabledValue) || enabledValue < 0.0D || enabledValue > 1.0D) {
            return EconomicFactor.unknown("invalid binary factor value");
        }
        return EconomicFactor.known(enabled ? enabledValue : 0.0D);
    }

    public static EconomicFactor piecewise(double raw, List<NormalizerAnchor> anchors) {
        if (!validMetric(raw) || anchors == null || anchors.size() < 2) {
            return EconomicFactor.unknown("invalid piecewise metric or anchors");
        }
        List<NormalizerAnchor> points = List.copyOf(anchors);
        for (int index = 1; index < points.size(); index++) {
            if (points.get(index).raw() <= points.get(index - 1).raw()
                || points.get(index).normalized() < points.get(index - 1).normalized()) {
                return EconomicFactor.unknown("piecewise anchors must increase in raw value and not decrease in normalized value");
            }
        }
        if (raw <= points.getFirst().raw()) {
            return EconomicFactor.known(points.getFirst().normalized());
        }
        if (raw >= points.getLast().raw()) {
            return EconomicFactor.known(points.getLast().normalized());
        }
        for (int index = 1; index < points.size(); index++) {
            NormalizerAnchor right = points.get(index);
            if (raw <= right.raw()) {
                NormalizerAnchor left = points.get(index - 1);
                double t = (raw - left.raw()) / (right.raw() - left.raw());
                return EconomicFactor.known(left.normalized() + t * (right.normalized() - left.normalized()));
            }
        }
        return EconomicFactor.known(points.getLast().normalized());
    }

    public static EconomicFactor quantityCost(double unconditionalExpectedUnitsPerAttempt,
        double attemptsReference, double attemptsCap) {
        if (!Double.isFinite(unconditionalExpectedUnitsPerAttempt) || unconditionalExpectedUnitsPerAttempt <= 0.0D) {
            return EconomicFactor.unknown("expected units per attempt are unknown or non-positive");
        }
        double expectedAttemptsPerUnit = 1.0D / unconditionalExpectedUnitsPerAttempt;
        return logarithmic(expectedAttemptsPerUnit, attemptsReference, attemptsCap);
    }

    public static EconomicFactor quantityCostForHorizon(double unconditionalExpectedUnitsPerAttempt,
        int economicHorizon, double attemptsReference, double attemptsCap) {
        if (!Double.isFinite(unconditionalExpectedUnitsPerAttempt) || unconditionalExpectedUnitsPerAttempt <= 0.0D
            || economicHorizon < 1) {
            return EconomicFactor.unknown("expected units per attempt and positive economic horizon are required");
        }
        return logarithmic(economicHorizon / unconditionalExpectedUnitsPerAttempt,
            attemptsReference, attemptsCap);
    }

    private static boolean validMetric(double value) {
        return Double.isFinite(value) && value >= 0.0D;
    }

    private static double softplus(double value) {
        return value > 40.0D ? value : Math.log1p(Math.exp(value));
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}