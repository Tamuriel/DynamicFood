package com.jorjik.dynamicfood.core;

public record CalibratedFoodValue(
    double economicCost,
    double magnitudeComponent,
    double rankComponent,
    double foodIndex
) {
    public CalibratedFoodValue {
        if (!Double.isFinite(economicCost) || economicCost < 0.0D || economicCost > 1.0D
            || !unit(magnitudeComponent) || !unit(rankComponent) || !unit(foodIndex)) {
            throw new IllegalArgumentException("calibrated values must be finite and in their documented ranges");
        }
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= 1.0D;
    }
}