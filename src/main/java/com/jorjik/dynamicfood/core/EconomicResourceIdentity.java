package com.jorjik.dynamicfood.core;

public record EconomicResourceIdentity(String value) {
    public EconomicResourceIdentity {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("economic resource identity must not be blank");
        }
    }
}
