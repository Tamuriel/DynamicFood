package com.jorjik.dynamicfood.core;

/** One immutable, internally consistent economic and calibration generation. */
public record PublishedEconomicGeneration(
    EconomicSnapshot economicSnapshot,
    CalibrationSnapshot calibrationSnapshot
) {
    public PublishedEconomicGeneration {
        if (economicSnapshot == null || calibrationSnapshot == null) {
            throw new IllegalArgumentException("economic and calibration snapshots are required");
        }
        economicSnapshot.validateForCalibration();
        calibrationSnapshot.validateForPublication(economicSnapshot);
    }

    public long generation() {
        return economicSnapshot.generation();
    }

    public String economicContentSignature() {
        return economicSnapshot.signature();
    }
}
