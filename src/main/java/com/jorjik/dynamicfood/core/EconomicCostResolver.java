package com.jorjik.dynamicfood.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class EconomicCostResolver {
    private EconomicCostResolver() {}

    public static EconomicCostResolution resolve(String itemId, List<AcquisitionPath> paths, int horizon,
        PrimaryPathStrategy strategy, Map<String, Double> feasibilityWeights, Map<String, Double> costWeights,
        double minimumFeasibility, double minimumFeasibilityCoverage, boolean allowPartialFeasibility,
        boolean allowPartialCost) {
        if (horizon < 1) {
            throw new IllegalArgumentException("economic horizon must be positive");
        }
        List<ResolvedPath> eligible = new ArrayList<>();
        ArrayList<String> reasons = new ArrayList<>();
        List<AcquisitionPath> orderedPaths = paths.stream()
            .sorted(Comparator.comparing(AcquisitionPath::sourceId)).toList();
        for (AcquisitionPath path : orderedPaths) {
            if (!path.itemId().equals(itemId)) {
                continue;
            }
            FeasibilityResult feasibility = FeasibilityResolver.resolve(path, feasibilityWeights,
                minimumFeasibilityCoverage, minimumFeasibility, allowPartialFeasibility);
            CostVector vector = path.costsByHorizon().get(horizon);
            if (vector == null) {
                reasons.add(path.sourceId() + ": missing cost vector for horizon " + horizon);
                continue;
            }
            if (vector.economicHorizon() != horizon) {
                reasons.add(path.sourceId() + ": cost vector horizon mismatch");
                continue;
            }
            AcquisitionCost cost = AcquisitionCostResolver.resolve(vector, costWeights);
            if (!feasibility.eligibleForPrimary()) {
                reasons.add(path.sourceId() + ": excluded by feasibility (" + feasibility.status() + ", coverage="
                    + feasibility.coverage() + ")");
                continue;
            }
            if (cost.status() == ResolutionStatus.UNKNOWN || cost.status() == ResolutionStatus.PARTIAL && !allowPartialCost) {
                reasons.add(path.sourceId() + ": excluded by cost status " + cost.status());
                continue;
            }
            eligible.add(new ResolvedPath(path, feasibility, cost));
        }

        if (eligible.isEmpty()) {
            return new EconomicCostResolution(null, null, ResolutionStatus.UNKNOWN, horizon,
                null, List.of(), 0.0D, reasons);
        }
        eligible.sort(Comparator.comparingDouble((ResolvedPath value) -> value.cost().cost())
            .thenComparing(Comparator.comparingDouble((ResolvedPath value) -> value.path().confidence()).reversed())
            .thenComparing(value -> value.path().renewability(), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(value -> value.path().risk(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(value -> value.path().sourceId()));

        List<ResolvedPath> strategyPaths = eligible;
        if (strategy == PrimaryPathStrategy.BEST_REPEATABLE_COST) {
            strategyPaths = eligible.stream().filter(value -> Boolean.TRUE.equals(value.path().repeatable())).toList();
            if (strategyPaths.isEmpty()) {
                return new EconomicCostResolution(null, null, ResolutionStatus.UNKNOWN, horizon,
                    null, eligible.stream().map(ResolvedPath::path).toList(), 0.0D,
                    append(reasons, "no eligible repeatable path"));
            }
        }

        double economicCost;
        ResolvedPath representative;
        if (strategy == PrimaryPathStrategy.WEIGHTED_AVERAGE) {
            double totalFeasibility = strategyPaths.stream().mapToDouble(value -> value.feasibility().feasibility()).sum();
            if (totalFeasibility == 0.0D) {
                return new EconomicCostResolution(null, null, ResolutionStatus.UNKNOWN, horizon,
                    null, strategyPaths.stream().map(ResolvedPath::path).toList(), 0.0D,
                    append(reasons, "weighted_average has zero total feasibility"));
            }
            economicCost = strategyPaths.stream()
                .mapToDouble(value -> value.cost().cost() * value.feasibility().feasibility()).sum() / totalFeasibility;
            representative = strategyPaths.stream().min(Comparator
                .comparingDouble((ResolvedPath value) -> Math.abs(value.cost().cost() - economicCost))
                .thenComparing(value -> value.path().sourceId())).orElseThrow();
        } else if (strategy == PrimaryPathStrategy.MEDIAN_FEASIBLE) {
            List<ResolvedPath> byCost = strategyPaths.stream()
                .sorted(Comparator.comparingDouble((ResolvedPath value) -> value.cost().cost())
                    .thenComparing(value -> value.path().sourceId())).toList();
            int middle = byCost.size() / 2;
            economicCost = byCost.size() % 2 == 1 ? byCost.get(middle).cost().cost()
                : (byCost.get(middle - 1).cost().cost() + byCost.get(middle).cost().cost()) / 2.0D;
            representative = byCost.stream().min(Comparator
                .comparingDouble((ResolvedPath value) -> Math.abs(value.cost().cost() - economicCost))
                .thenComparing(value -> value.path().sourceId())).orElseThrow();
        } else {
            representative = strategyPaths.getFirst();
            economicCost = representative.cost().cost();
        }

        ResolutionStatus status = strategyPaths.stream().allMatch(value -> value.cost().status() == ResolutionStatus.COMPLETE)
            ? ResolutionStatus.COMPLETE : ResolutionStatus.PARTIAL;
        double confidence = representative.path().confidence();
        if (representative.cost().status() == ResolutionStatus.PARTIAL) {
            confidence *= 0.75D;
        }
        if (representative.feasibility().status() == ResolutionStatus.PARTIAL) {
            confidence *= representative.feasibility().coverage();
        }
        List<AcquisitionPath> alternatives = eligible.stream().map(ResolvedPath::path)
            .filter(path -> !path.sourceId().equals(representative.path().sourceId())).toList();
        reasons.add("economic cost resolved from " + strategyPaths.size() + " path(s) at horizon " + horizon);
        return new EconomicCostResolution(economicCost, Math.min(5.0D, 5.0D * economicCost), status, horizon,
            representative.path(), alternatives, Math.max(0.0D, Math.min(1.0D, confidence)), reasons);
    }

    private static List<String> append(List<String> values, String extra) {
        ArrayList<String> result = new ArrayList<>(values);
        result.add(extra);
        return result;
    }

    private record ResolvedPath(AcquisitionPath path, FeasibilityResult feasibility, AcquisitionCost cost) {
    }
}