package com.jorjik.dynamicfood.core;

import java.util.List;

public final class FoodValueCurve {
    private final List<CalibrationAnchor> anchors;

    public FoodValueCurve(List<CalibrationAnchor> anchors) {
        if (anchors == null || anchors.size() < 2) {
            throw new IllegalArgumentException("at least two anchors are required");
        }
        List<CalibrationAnchor> copy = List.copyOf(anchors);
        for (int index = 1; index < copy.size(); index++) {
            CalibrationAnchor previous = copy.get(index - 1);
            CalibrationAnchor current = copy.get(index);
            if (current.index() <= previous.index()) {
                throw new IllegalArgumentException("anchor indices must be strictly increasing");
            }
            if (current.value() < previous.value()) {
                throw new IllegalArgumentException("anchor values must be non-decreasing");
            }
        }
        this.anchors = copy;
    }

    public List<CalibrationAnchor> anchors() {
        return anchors;
    }

    public double evaluate(double foodIndex) {
        double index = Double.isFinite(foodIndex) ? Math.max(0.0D, Math.min(1.0D, foodIndex)) : 0.0D;
        if (index <= anchors.getFirst().index()) {
            return anchors.getFirst().value();
        }
        if (index >= anchors.getLast().index()) {
            return anchors.getLast().value();
        }
        for (int position = 1; position < anchors.size(); position++) {
            CalibrationAnchor right = anchors.get(position);
            if (index <= right.index()) {
                CalibrationAnchor left = anchors.get(position - 1);
                double fraction = (index - left.index()) / (right.index() - left.index());
                return left.value() + fraction * (right.value() - left.value());
            }
        }
        return anchors.getLast().value();
    }

    public static FoodValueCurve preset(String preset, boolean saturation) {
        String selected = preset == null ? "medium" : preset.toLowerCase(java.util.Locale.ROOT);
        double[][] values = switch (selected) {
            case "easy" -> saturation
                ? new double[][] {{0,0},{0.10,0},{0.25,0},{0.40,0.5},{0.60,2},{0.80,4},{0.95,6},{1,8}}
                : new double[][] {{0,1},{0.10,1.5},{0.25,2.5},{0.40,4},{0.60,6},{0.80,8},{0.95,10},{1,11}};
            case "hard" -> saturation
                ? new double[][] {{0,0},{0.10,0},{0.25,0},{0.40,0},{0.60,1},{0.80,2.5},{0.95,4},{1,6}}
                : new double[][] {{0,1},{0.10,1},{0.25,1.5},{0.40,2.5},{0.60,4},{0.80,6},{0.95,8},{1,9}};
            case "very_hard" -> saturation
                ? new double[][] {{0,0},{0.10,0},{0.25,0},{0.40,0},{0.60,0.5},{0.80,1.5},{0.95,3},{1,5}}
                : new double[][] {{0,1},{0.10,1},{0.25,1},{0.40,2},{0.60,3},{0.80,5},{0.95,7},{1,8}};
            default -> saturation
                ? new double[][] {{0,0},{0.10,0},{0.25,0},{0.40,0},{0.60,1.5},{0.80,3},{0.95,5},{1,7}}
                : new double[][] {{0,1},{0.10,1},{0.25,2},{0.40,3},{0.60,5},{0.80,7},{0.95,9},{1,10}};
        };
        return new FoodValueCurve(java.util.Arrays.stream(values)
            .map(point -> new CalibrationAnchor(point[0], point[1])).toList());
    }
}