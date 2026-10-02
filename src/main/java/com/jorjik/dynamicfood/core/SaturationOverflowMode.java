package com.jorjik.dynamicfood.core;

public enum SaturationOverflowMode {
    PRESERVE_COMPONENTS,
    NORMALIZE_COMPONENTS;

    public static SaturationOverflowMode parse(String value) {
        if (value == null) {
            return PRESERVE_COMPONENTS;
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "normalize_components" -> NORMALIZE_COMPONENTS;
            default -> PRESERVE_COMPONENTS;
        };
    }
}
