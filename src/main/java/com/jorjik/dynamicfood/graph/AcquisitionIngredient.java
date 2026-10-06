package com.jorjik.dynamicfood.graph;

import java.util.List;
import java.util.Objects;

public record AcquisitionIngredient(List<String> alternatives, int count, InputUse inputUse,
    String unresolvedReason) {
    public AcquisitionIngredient(List<String> alternatives, int count) {
        this(alternatives, count, InputUse.CONSUMED, defaultUnresolvedReason(alternatives));
    }

    public AcquisitionIngredient(List<String> alternatives, int count, InputUse inputUse) {
        this(alternatives, count, inputUse, defaultUnresolvedReason(alternatives));
    }

    public AcquisitionIngredient {
        Objects.requireNonNull(alternatives, "alternatives");
        alternatives = alternatives.stream().filter(id -> id != null && !id.isBlank()).distinct().sorted().toList();
        if (count < 1) {
            throw new IllegalArgumentException("acquisition ingredient count must be positive");
        }
        if (inputUse == null) {
            throw new IllegalArgumentException("acquisition ingredient use classification is required");
        }
        unresolvedReason = Objects.requireNonNull(unresolvedReason, "unresolvedReason").strip();
    }

    private static String defaultUnresolvedReason(List<String> alternatives) {
        return alternatives == null || alternatives.isEmpty()
            ? "no concrete item alternatives were resolved"
            : "";
    }

    public enum InputUse {
        CONSUMED,
        REUSABLE,
        UNKNOWN
    }
}
