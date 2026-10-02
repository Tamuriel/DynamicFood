package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.core.FoodValue;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

public final class OutputValueAllocator {
    private OutputValueAllocator() {}

    public static <T> List<OutputTarget<T>> foodTargets(
        List<T> outputs,
        Predicate<T> isFoodComponent,
        ToIntFunction<T> quantity
    ) {
        return foodTargets(outputs, isFoodComponent, quantity, ignored -> null);
    }

    public static <T> List<OutputTarget<T>> foodTargets(
        List<T> outputs,
        Predicate<T> isFoodComponent,
        ToIntFunction<T> quantity,
        Function<T, Double> allocationWeight
    ) {
        List<OutputTarget<T>> targets = new ArrayList<>();
        for (T output : outputs) {
            if (isFoodComponent.test(output)) {
                int count = Math.max(0, quantity.applyAsInt(output));
                Double weight = allocationWeight.apply(output);
                if (weight != null && (!Double.isFinite(weight) || weight < 0.0D)) {
                    throw new IllegalArgumentException("output allocation weight must be finite and non-negative");
                }
                if (count > 0 && (weight == null || weight > 0.0D)) {
                    targets.add(new OutputTarget<>(output, count, weight));
                }
            }
        }
        return List.copyOf(targets);
    }

    public static <T> int totalFoodOutputCount(List<OutputTarget<T>> targets) {
        long total = targets.stream().mapToLong(OutputTarget::quantity).sum();
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("total food output count exceeds integer range");
        }
        return (int) total;
    }

    public static <T> List<AllocatedOutput<T>> allocate(FoodValue operationValue, List<OutputTarget<T>> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        double totalWeight = targets.stream().mapToDouble(target -> target.allocationWeight() == null
            ? target.quantity() : target.allocationWeight()).sum();
        if (!Double.isFinite(totalWeight) || totalWeight <= 0.0D) {
            throw new IllegalArgumentException("food output allocation requires a finite positive total weight");
        }
        double operationNutrition = operationValue.rawNutrition() * operationValue.outputCount();
        double operationSaturation = operationValue.rawSaturation() * operationValue.outputCount();
        return targets.stream().map(target -> {
            double weight = target.allocationWeight() == null ? target.quantity() : target.allocationWeight();
            double share = weight / totalWeight;
            return new AllocatedOutput<>(target,
                operationNutrition * share / target.quantity(),
                operationSaturation * share / target.quantity());
        }).toList();
    }

    public record OutputTarget<T>(T output, int quantity, Double allocationWeight) {
        public OutputTarget(T output, int quantity) {
            this(output, quantity, null);
        }

        public OutputTarget {
            quantity = Math.max(0, quantity);
            if (allocationWeight != null && (!Double.isFinite(allocationWeight) || allocationWeight < 0.0D)) {
                throw new IllegalArgumentException("output allocation weight must be finite and non-negative");
            }
        }
    }

    public record AllocatedOutput<T>(OutputTarget<T> target, double nutritionPerUnit, double saturationPerUnit) {
    }
}