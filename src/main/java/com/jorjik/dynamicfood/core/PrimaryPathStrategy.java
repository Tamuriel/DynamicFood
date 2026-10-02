package com.jorjik.dynamicfood.core;

public enum PrimaryPathStrategy {
    BEST_REPEATABLE_COST,
    WEIGHTED_AVERAGE,
    MINIMUM_FEASIBLE,
    MEDIAN_FEASIBLE;

    public static PrimaryPathStrategy parse(String value) {
        if (value == null) {
            return BEST_REPEATABLE_COST;
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "weighted_average" -> WEIGHTED_AVERAGE;
            case "minimum_feasible" -> MINIMUM_FEASIBLE;
            case "median_feasible" -> MEDIAN_FEASIBLE;
            default -> BEST_REPEATABLE_COST;
        };
    }
}