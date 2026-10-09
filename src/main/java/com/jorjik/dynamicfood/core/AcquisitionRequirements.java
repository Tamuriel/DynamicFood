package com.jorjik.dynamicfood.core;

import java.util.List;

public record AcquisitionRequirements(List<RequirementExpression> requirements) {
    public AcquisitionRequirements {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    public static AcquisitionRequirements empty() {
        return new AcquisitionRequirements(List.of());
    }

    public String canonicalKey() {
        return requirements.stream()
            .map(RequirementExpression::canonicalKey)
            .sorted()
            .toList()
            .toString();
    }
}
