package com.jorjik.dynamicfood.core;

public record AcquisitionMeasurement(Double value, String unknownReason) {
    public AcquisitionMeasurement {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException("acquisition measurement must be finite when known");
        }
        if (value == null && (unknownReason == null || unknownReason.isBlank())) {
            throw new IllegalArgumentException("unknown acquisition measurements require a reason");
        }
    }

    public static AcquisitionMeasurement known(double value) {
        return new AcquisitionMeasurement(value, null);
    }

    public static AcquisitionMeasurement unknown(String reason) {
        return new AcquisitionMeasurement(null, reason);
    }

    public boolean isKnown() {
        return value != null;
    }
}
