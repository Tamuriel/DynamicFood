package com.jorjik.dynamicfood.core;

import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import java.util.List;
import java.util.Map;

public record AcquisitionEvidence(
    Map<String, AcquisitionMeasurement> measurements,
    Map<String, String> attributes,
    List<AcquisitionIngredient> inputs
) {
    public AcquisitionEvidence(Map<String, AcquisitionMeasurement> measurements, Map<String, String> attributes) {
        this(measurements, attributes, List.of());
    }

    public AcquisitionEvidence {
        measurements = Map.copyOf(measurements);
        attributes = Map.copyOf(attributes);
        inputs = List.copyOf(inputs);
    }

    public static AcquisitionEvidence empty() {
        return new AcquisitionEvidence(Map.of(), Map.of(), List.of());
    }

    public AcquisitionMeasurement measurement(String name) {
        return measurements.getOrDefault(name,
            AcquisitionMeasurement.unknown("not exposed by this acquisition source"));
    }
}
