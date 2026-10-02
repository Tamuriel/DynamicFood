package com.jorjik.dynamicfood.graph;

import java.util.List;

public record AcquisitionIngredient(List<String> alternatives, int count) {
    public AcquisitionIngredient {
        alternatives = alternatives.stream().filter(id -> id != null && !id.isBlank()).distinct().sorted().toList();
        if (count < 1) {
            throw new IllegalArgumentException("acquisition ingredient count must be positive");
        }
    }
}
