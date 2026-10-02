package com.jorjik.dynamicfood.core;

public record AcquisitionPathDiagnostic(
    AcquisitionPath path,
    FeasibilityResult feasibility,
    AcquisitionCost cost
) {
}
