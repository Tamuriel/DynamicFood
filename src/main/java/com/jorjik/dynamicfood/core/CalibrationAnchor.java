package com.jorjik.dynamicfood.core;

public record CalibrationAnchor(double index, double value) {
    public CalibrationAnchor {
        if (!Double.isFinite(index) || index < 0.0D || index > 1.0D) {
            throw new IllegalArgumentException("anchor index must be finite and in [0,1]");
        }
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException("anchor value must be finite and non-negative");
        }
    }
}