package com.jorjik.dynamicfood.core;

public record NormalizerAnchor(double raw, double normalized) {
    public NormalizerAnchor {
        if (!Double.isFinite(raw) || !Double.isFinite(normalized) || normalized < 0.0D || normalized > 1.0D) {
            throw new IllegalArgumentException("normalizer anchors must be finite and normalized value in [0,1]");
        }
    }
}