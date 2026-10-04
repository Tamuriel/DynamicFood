package com.jorjik.dynamicfood.core;

import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class EconomicCostResolver {
    public static final String POLICY_PRIMITIVE_ID = "dynamicfood:economic-policy-v1";

    private EconomicCostResolver() {}

    @Deprecated(forRemoval = false)
    public static EconomicCostResolution resolve(String itemId, List<AcquisitionPath> paths, int horizon,
        PrimaryPathStrategy strategy, Map<String, Double> feasibilityWeights, Map<String, Double> costWeights,
        double minimumFeasibility, double minimumFeasibilityCoverage, boolean allowPartialFeasibility,
        boolean ignoredAllowPartialCost) {
        return resolve(itemId, paths, horizon, strategy, feasibilityWeights, costWeights,
            minimumFeasibility, minimumFeasibilityCoverage, allowPartialFeasibility);
    }

    public static EconomicCostResolution resolve(String itemId, List<AcquisitionPath> paths, int horizon,
        PrimaryPathStrategy strategy, Map<String, Double> feasibilityWeights,
        double minimumFeasibility, double minimumFeasibilityCoverage, boolean allowPartialFeasibility) {
        return resolve(itemId, paths, horizon, strategy, feasibilityWeights, DynamicFoodConfig.costFactorWeights(),
            minimumFeasibility, minimumFeasibilityCoverage, allowPartialFeasibility);
    }

    public static EconomicCostResolution resolve(String itemId, List<AcquisitionPath> paths, int horizon,
        PrimaryPathStrategy strategy, Map<String, Double> feasibilityWeights, Map<String, Double> costWeights,
        double minimumFeasibility, double minimumFeasibilityCoverage, boolean allowPartialFeasibility) {
        if (itemId == null || paths == null || strategy == null || feasibilityWeights == null
            || costWeights == null || horizon < 1) {
            throw new IllegalArgumentException("resource, paths, strategy, weights and positive horizon are required");
        }
        List<AcquisitionPath> itemPaths = paths.stream()
            .filter(path -> path.itemId().equals(itemId))
            .sorted(Comparator.comparing(AcquisitionPath::sourceId))
            .toList();
        List<ResolvedPath> eligible = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        boolean incompleteCoverage = false;
        for (AcquisitionPath path : itemPaths) {
            String survival = path.evidence().attributes().get("source_availability_classification");
            if (!"TRUE".equalsIgnoreCase(survival)) {
                incompleteCoverage |= survival == null || !"FALSE".equalsIgnoreCase(survival);
                reasons.add(path.sourceId() + ": excluded because survival availability is "
                    + (survival == null ? "UNKNOWN" : survival));
                continue;
            }
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
            CostVector vector = path.costsByHorizon().get(horizon);
            if (vector == null) {
                reasons.add(path.sourceId() + ": no CostVector is available at economic horizon " + horizon);
                incompleteCoverage = true;
                continue;
            }
            AcquisitionCost acquisitionCost = AcquisitionCostResolver.resolve(vector, costWeights);
            if (acquisitionCost.status() == ResolutionStatus.UNKNOWN) {
                reasons.add(path.sourceId() + ": AcquisitionCost UNKNOWN; core missing="
                    + acquisitionCost.missingFactors());
                incompleteCoverage = true;
                continue;
            }
            EconomicCost target = EconomicCost.known(acquisitionCost.cost(), POLICY_PRIMITIVE_ID, horizon,
                "policy-defined scalar promoted from AcquisitionCost for path " + path.sourceId()
                    + "; additional factor coverage=" + acquisitionCost.additionalCoverage());
            eligible.add(new ResolvedPath(path, feasibility, acquisitionCost, target));
        }

        if (eligible.isEmpty()) {
            reasons.add("no survival-valid, feasible acquisition path has a CORE-COMPLETE AcquisitionCost at horizon "
                + horizon);
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
                reasons.add("no eligible repeatable path with a resolved AcquisitionCost");
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
                double weight = path.feasibility().feasibility();
                if (weight == 0.0D) {
                    continue;
                }
                double nextFeasibility = accumulatedFeasibility + weight;
                value += (path.target().value() - value) * (weight / nextFeasibility);
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
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            reasons.add("economic policy aggregation produced a value outside [0,1]");
            return unknown(horizon, null, itemPaths, 0.0D, reasons);
        }

        EconomicCost target = EconomicCost.known(value, POLICY_PRIMITIVE_ID, horizon,
            "resolved using " + strategy + " from " + strategyPaths.size()
                + " eligible path-level AcquisitionCost value(s)");
        ResolutionStatus status = incompleteCoverage ? ResolutionStatus.PARTIAL : ResolutionStatus.COMPLETE;
        double confidence = representative.path().confidence();
        if (representative.feasibility().status() == ResolutionStatus.PARTIAL) {
            confidence *= representative.feasibility().coverage();
        }
        List<AcquisitionPath> alternatives = itemPaths.stream()
            .filter(path -> !path.sourceId().equals(representative.path().sourceId())).toList();
        reasons.add("EconomicCost promoted from policy AcquisitionCost using " + strategy
            + " at economic horizon " + horizon);
        return new EconomicCostResolution(target, null, status, horizon, representative.path(), alternatives,
            Math.max(0.0D, Math.min(1.0D, confidence)), reasons);
    }

    private static EconomicCostResolution unknown(int horizon, AcquisitionPath primaryPath,
        List<AcquisitionPath> paths, double confidence, List<String> reasons) {
        return new EconomicCostResolution(EconomicCost.unknown(
            "no eligible acquisition path has a resolved policy AcquisitionCost"), null,
            ResolutionStatus.UNKNOWN, horizon, primaryPath, paths, confidence, reasons);
    }

    private record ResolvedPath(AcquisitionPath path, FeasibilityResult feasibility,
        AcquisitionCost acquisitionCost, EconomicCost target) {
    }
}
