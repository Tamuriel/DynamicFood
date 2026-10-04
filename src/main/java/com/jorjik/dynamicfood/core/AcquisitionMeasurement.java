package com.jorjik.dynamicfood.core;

import java.util.Optional;

/** An unnormalized observed metric; it is not an EconomicFactor or an economic value. */
public record AcquisitionMeasurement(
    Double value,
    String unknownReason,
    Optional<EstimateKind> estimateKind,
    Optional<EstimateMetadata> estimateMetadata
) {
    public AcquisitionMeasurement(Double value, String unknownReason) {
        this(value, unknownReason, value == null
            ? Optional.of(EstimateKind.UNKNOWN) : Optional.empty(), Optional.empty());
    }

    public AcquisitionMeasurement {
        estimateKind = estimateKind == null ? Optional.empty() : estimateKind;
        estimateMetadata = estimateMetadata == null ? Optional.empty() : estimateMetadata;
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException("acquisition measurement must be finite when known");
        }
        if (value == null && (unknownReason == null || unknownReason.isBlank())) {
            throw new IllegalArgumentException("unknown acquisition measurements require a reason");
        }
        if (value == null && (estimateKind.orElse(null) != EstimateKind.UNKNOWN || estimateMetadata.isPresent())) {
            throw new IllegalArgumentException("unknown measurements must use UNKNOWN without estimate metadata");
        }
        if (value != null && (unknownReason != null || estimateKind.orElse(null) == EstimateKind.UNKNOWN)) {
            throw new IllegalArgumentException("known measurements cannot use unknown state or reason");
        }
        if (estimateKind.isEmpty() && estimateMetadata.isPresent()) {
            throw new IllegalArgumentException("estimate metadata requires an estimate kind");
        }
        if (estimateKind.orElse(null) == EstimateKind.ANALYTICAL && estimateMetadata.isEmpty()) {
            throw new IllegalArgumentException("analytical estimates require method metadata");
        }
        if (estimateKind.orElse(null) == EstimateKind.APPROXIMATED
            && (estimateMetadata.isEmpty() || estimateMetadata.get().assumptions().isEmpty())) {
            throw new IllegalArgumentException("approximated estimates require reproducibility metadata and assumptions");
        }
    }

    public static AcquisitionMeasurement known(double value) {
        return new AcquisitionMeasurement(value, null);
    }

    public static AcquisitionMeasurement exact(double value) {
        return new AcquisitionMeasurement(value, null, Optional.of(EstimateKind.EXACT), Optional.empty());
    }

    public static AcquisitionMeasurement analytical(double value, EstimateMetadata metadata) {
        return new AcquisitionMeasurement(value, null, Optional.of(EstimateKind.ANALYTICAL),
            Optional.of(metadata));
    }

    public static AcquisitionMeasurement approximated(double value, EstimateMetadata metadata) {
        return new AcquisitionMeasurement(value, null, Optional.of(EstimateKind.APPROXIMATED),
            Optional.of(metadata));
    }

    public static AcquisitionMeasurement unknown(String reason) {
        return new AcquisitionMeasurement(null, reason, Optional.of(EstimateKind.UNKNOWN), Optional.empty());
    }

    public boolean isKnown() {
        return value != null;
    }
}
