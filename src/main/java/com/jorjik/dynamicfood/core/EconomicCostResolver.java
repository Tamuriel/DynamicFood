package com.jorjik.dynamicfood.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class EconomicCostResolver {
    private EconomicCostResolver() {}

    @Deprecated(forRemoval = false)
    public static EconomicCostResolution resolve(String itemId, List<AcquisitionPath> paths, int horizon,
        PrimaryPathStrategy strategy, Map<String, Double> feasibilityWeights, Map<String, Double> ignoredCostWeights,
        double minimumFeasibility, double minimumFeasibilityCoverage, boolean allowPartialFeasibility,
        boolean ignoredAllowPartialCost) {
        return resolve(itemId, paths, horizon, strategy, feasibilityWeights, minimumFeasibility,
            minimumFeasibilityCoverage, allowPartialFeasibility);
    }

    public static EconomicCostResolution resolve(String itemId, List<AcquisitionPath> paths, int horizon,
        PrimaryPathStrategy strategy, Map<String, Double> feasibilityWeights,
        double minimumFeasibility, double minimumFeasibilityCoverage, boolean allowPartialFeasibility) {
        if (horizon < 1) {
            throw new IllegalArgumentException("economic horizon must be positive");
        }
        List<AcquisitionPath> itemPaths = paths.stream()
            .filter(path -> path.itemId().equals(itemId))
            .sorted(Comparator.comparing(AcquisitionPath::sourceId))
            .toList();
        List<ResolvedPath> eligible = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        boolean incompleteCoverage = false;
        for (AcquisitionPath path : itemPaths) {
            FeasibilityResult feasibility = FeasibilityResolver.resolve(path, feasibilityWeights,
                minimumFeasibilityCoverage, minimumFeasibility, allowPartialFeasibility);
            if (!feasibility.eligibleForPrimary()) {
                reasons.add(path.sourceId() + ": excluded by feasibility (" + feasibility.status()
                    + ", coverage=" + feasibility.coverage() + ")");
                continue;
            }
            if (feasibility.status() == ResolutionStatus.PARTIAL) {
                incompleteCoverage = true;
            }
            EconomicCost target = path.economicCostAt(horizon);
            if (!target.isKnown()) {
                incompleteCoverage = true;
                reasons.add(path.sourceId() + ": EconomicCost UNKNOWN: " + target.evidence());
                continue;
            }
            eligible.add(new ResolvedPath(path, feasibility, target));
        }

        if (eligible.isEmpty()) {
            reasons.add("no eligible acquisition path has independently evidenced EconomicCost at horizon " + horizon);
            return unknown(horizon, null, itemPaths, 0.0D, reasons);
        }

        EconomicCost basis = eligible.getFirst().target();
        List<ResolvedPath> incompatible = eligible.stream()
            .filter(value -> !basis.hasCompatibleBasis(value.target()))
            .toList();
        if (!incompatible.isEmpty()) {
            reasons.add("eligible acquisition paths use incompatible economic primitives or horizons");
            return unknown(horizon, null, itemPaths, 0.0D, reasons);
        }

        eligible.sort(Comparator.comparingDouble((ResolvedPath value) -> value.target().value())
            .thenComparing(Comparator.comparingDouble((ResolvedPath value) -> value.path().confidence()).reversed())
            .thenComparing(value -> value.path().renewability(), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(value -> value.path().risk(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(value -> value.path().sourceId()));

        List<ResolvedPath> strategyPaths = eligible;
        if (strategy == PrimaryPathStrategy.BEST_REPEATABLE_COST) {
            strategyPaths = eligible.stream().filter(value -> Boolean.TRUE.equals(value.path().repeatable())).toList();
            if (strategyPaths.isEmpty()) {
                reasons.add("no eligible repeatable path with an evidenced EconomicCost");
                return unknown(horizon, null, itemPaths, 0.0D, reasons);
            }
        }

        double value;
        ResolvedPath representative;
        if (strategy == PrimaryPathStrategy.WEIGHTED_AVERAGE) {
            double totalFeasibility = strategyPaths.stream()
                .mapToDouble(path -> path.feasibility().feasibility()).sum();
            if (totalFeasibility == 0.0D) {
                reasons.add("weighted_average has zero total feasibility");
                return unknown(horizon, null, itemPaths, 0.0D, reasons);
            }
            double accumulatedFeasibility = 0.0D;
            value = 0.0D;
            for (ResolvedPath path : strategyPaths) {
                double pathFeasibility = path.feasibility().feasibility();
                if (pathFeasibility == 0.0D) {
                    continue;
                }
                double nextFeasibility = accumulatedFeasibility + pathFeasibility;
                value += (path.target().value() - value) * (pathFeasibility / nextFeasibility);
                accumulatedFeasibility = nextFeasibility;
            }
            double selectedValue = value;
            representative = strategyPaths.stream().min(Comparator
                .comparingDouble((ResolvedPath path) -> Math.abs(path.target().value() - selectedValue))
                .thenComparing(path -> path.path().sourceId())).orElseThrow();
        } else if (strategy == PrimaryPathStrategy.MEDIAN_FEASIBLE) {
            int middle = strategyPaths.size() / 2;
            value = strategyPaths.size() % 2 == 1 ? strategyPaths.get(middle).target().value()
                : strategyPaths.get(middle - 1).target().value()
                    + (strategyPaths.get(middle).target().value()
                        - strategyPaths.get(middle - 1).target().value()) / 2.0D;
            double selectedValue = value;
            representative = strategyPaths.stream().min(Comparator
                .comparingDouble((ResolvedPath path) -> Math.abs(path.target().value() - selectedValue))
                .thenComparing(path -> path.path().sourceId())).orElseThrow();
        } else {
            representative = strategyPaths.getFirst();
            value = representative.target().value();
        }
        if (!Double.isFinite(value) || value < 0.0D) {
            reasons.add("eligible acquisition path aggregation produced an invalid EconomicCost");
            return unknown(horizon, null, itemPaths, 0.0D, reasons);
        }

        EconomicCost target = EconomicCost.known(value, basis.primitiveId(), horizon,
            "resolved from " + strategyPaths.size() + " eligible acquisition path(s); " + basis.evidence());
        ResolutionStatus status = incompleteCoverage ? ResolutionStatus.PARTIAL : ResolutionStatus.COMPLETE;
        double confidence = representative.path().confidence();
        if (representative.feasibility().status() == ResolutionStatus.PARTIAL) {
            confidence *= representative.feasibility().coverage();
        }
        List<AcquisitionPath> alternatives = itemPaths.stream()
            .filter(path -> !path.sourceId().equals(representative.path().sourceId())).toList();
        reasons.add("EconomicCost resolved from independently evidenced primitive '" + basis.primitiveId()
            + "' at observation horizon " + horizon);
        return new EconomicCostResolution(target, null, status, horizon, representative.path(), alternatives,
            Math.max(0.0D, Math.min(1.0D, confidence)), reasons);
    }

    private static EconomicCostResolution unknown(int horizon, AcquisitionPath primaryPath,
        List<AcquisitionPath> paths, double confidence, List<String> reasons) {
        return new EconomicCostResolution(EconomicCost.unknown(
            "no compatible independently evidenced EconomicCost could be resolved"), null,
            ResolutionStatus.UNKNOWN, horizon, primaryPath, paths, confidence, reasons);
    }

    private record ResolvedPath(AcquisitionPath path, FeasibilityResult feasibility, EconomicCost target) {
    }
}
