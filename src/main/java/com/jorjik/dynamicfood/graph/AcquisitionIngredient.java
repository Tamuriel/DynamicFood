package com.jorjik.dynamicfood.graph;

import java.util.List;

public record AcquisitionIngredient(List<String> alternatives, int count, InputUse inputUse) {
    public AcquisitionIngredient(List<String> alternatives, int count) {
        this(alternatives, count, InputUse.CONSUMED);
    }

    public AcquisitionIngredient {
        alternatives = alternatives.stream().filter(id -> id != null && !id.isBlank()).distinct().sorted().toList();
        if (count < 1) {
            throw new IllegalArgumentException("acquisition ingredient count must be positive");
        }
        if (inputUse == null) {
            throw new IllegalArgumentException("acquisition ingredient use classification is required");
        }
    }

    public enum InputUse {
        CONSUMED,
        REUSABLE,
        UNKNOWN
    }
}
