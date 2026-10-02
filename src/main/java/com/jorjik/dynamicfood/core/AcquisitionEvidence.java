package com.jorjik.dynamicfood.core;

import java.util.Map;

public record AcquisitionEvidence(
    Map<String, AcquisitionMeasurement> measurements,
    Map<String, String> attributes
) {
    public AcquisitionEvidence {
        measurements = Map.copyOf(measurements);
        attributes = Map.copyOf(attributes);
    }

    public static AcquisitionEvidence empty() {
        return new AcquisitionEvidence(Map.of(), Map.of());
    }

    public AcquisitionMeasurement measurement(String name) {
        return measurements.getOrDefault(name,
            AcquisitionMeasurement.unknown("not exposed by this acquisition source"));
    }
}
