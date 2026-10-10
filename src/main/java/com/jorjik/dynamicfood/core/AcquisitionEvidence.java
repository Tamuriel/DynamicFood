package com.jorjik.dynamicfood.core;

import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import java.util.List;
import java.util.Map;

public record AcquisitionEvidence(
    Map<String, AcquisitionMeasurement> measurements,
    Map<String, String> attributes,
    List<AcquisitionIngredient> inputs,
    WorldgenCausalEvidence worldgenCausalEvidence
) {
    public AcquisitionEvidence(Map<String, AcquisitionMeasurement> measurements, Map<String, String> attributes) {
        this(measurements, attributes, List.of(), WorldgenCausalEvidence.empty());
    }

    public AcquisitionEvidence(Map<String, AcquisitionMeasurement> measurements, Map<String, String> attributes,
        List<AcquisitionIngredient> inputs) {
        this(measurements, attributes, inputs, WorldgenCausalEvidence.empty());
    }

    public AcquisitionEvidence {
        measurements = Map.copyOf(measurements);
        attributes = Map.copyOf(attributes);
        inputs = List.copyOf(inputs);
        worldgenCausalEvidence = worldgenCausalEvidence == null
            ? WorldgenCausalEvidence.empty() : worldgenCausalEvidence;
    }

    public static AcquisitionEvidence empty() {
        return new AcquisitionEvidence(Map.of(), Map.of(), List.of(), WorldgenCausalEvidence.empty());
    }

    public AcquisitionMeasurement measurement(String name) {
        return measurements.getOrDefault(name,
            AcquisitionMeasurement.unknown("not exposed by this acquisition source"));
    }
}
