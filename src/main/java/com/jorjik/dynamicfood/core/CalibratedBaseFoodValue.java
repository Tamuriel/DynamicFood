package com.jorjik.dynamicfood.core;

public record CalibratedBaseFoodValue(
    double nutrition,
    double effectiveSaturation,
    double foodIndex,
    double economicCost,
    double difficulty,
    CalibrationStatus calibrationStatus,
    String source
) {
    public CalibratedBaseFoodValue {
        if (!finiteNonNegative(nutrition) || !finiteNonNegative(effectiveSaturation)
            || !unit(foodIndex) || !unit(economicCost) || !Double.isFinite(difficulty)
            || difficulty < 0.0D || difficulty > 5.0D) {
            throw new IllegalArgumentException("invalid calibrated base food value");
        }
        source = source == null ? "unknown" : source;
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0D;
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= 1.0D;
    }
}