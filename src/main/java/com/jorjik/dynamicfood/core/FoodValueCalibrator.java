package com.jorjik.dynamicfood.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class FoodValueCalibrator {
    private final EconomicResourceIdentityResolver identityResolver = new EconomicResourceIdentityResolver();
    private final int minimumPopulation;
    private final double magnitudeWeight;
    private final double rankWeight;
    private final double lowerQuantile;
    private final double upperQuantile;
    private final double lowCoverageThreshold;
    private final double mediumCoverageThreshold;

    public FoodValueCalibrator(int minimumPopulation, double magnitudeWeight, double rankWeight,
        double lowerQuantile, double upperQuantile, double lowCoverageThreshold, double mediumCoverageThreshold) {
        if (minimumPopulation < 1) {
            throw new IllegalArgumentException("minimumPopulation must be at least one");
        }
        if (!Double.isFinite(magnitudeWeight) || !Double.isFinite(rankWeight)
            || magnitudeWeight < 0.0D || rankWeight < 0.0D
            || Math.abs(magnitudeWeight + rankWeight - 1.0D) > 1.0E-9D) {
            throw new IllegalArgumentException("magnitudeWeight and rankWeight must be non-negative and sum to one");
        }
        if (!Double.isFinite(lowerQuantile) || !Double.isFinite(upperQuantile)
            || lowerQuantile <= 0.0D || upperQuantile >= 1.0D || lowerQuantile >= upperQuantile) {
            throw new IllegalArgumentException("quantiles must satisfy 0 < lower < upper < 1");
        }
        if (!Double.isFinite(lowCoverageThreshold) || !Double.isFinite(mediumCoverageThreshold)
            || lowCoverageThreshold < 0.0D || mediumCoverageThreshold > 1.0D
            || lowCoverageThreshold > mediumCoverageThreshold) {
            throw new IllegalArgumentException("coverage thresholds must satisfy 0 <= low <= medium <= 1");
        }
        this.minimumPopulation = minimumPopulation;
        this.magnitudeWeight = magnitudeWeight;
        this.rankWeight = rankWeight;
        this.lowerQuantile = lowerQuantile;
        this.upperQuantile = upperQuantile;
        this.lowCoverageThreshold = lowCoverageThreshold;
        this.mediumCoverageThreshold = mediumCoverageThreshold;
    }

    public CalibrationSnapshot calibrate(Collection<ResourceEconomicProfile> resources) {
        Map<String, List<ResourceEconomicProfile>> groups = new TreeMap<>();
        for (ResourceEconomicProfile resource : resources) {
            if (resource.isCalibrationCandidate()) {
                String identity = identityResolver.resolve(resource).value();
                groups.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(resource);
            }
        }

        List<Entity> candidates = new ArrayList<>();
        List<ResourceEconomicProfile> population = groups.values().stream()
            .map(aliases -> aliases.stream().sorted(Comparator.comparing(ResourceEconomicProfile::resourceId))
                .findFirst().orElseThrow())
            .toList();
        double candidateWeight = 0.0D;
        double resolvedWeight = 0.0D;
        for (Map.Entry<String, List<ResourceEconomicProfile>> grouped : groups.entrySet()) {
            List<ResourceEconomicProfile> aliases = grouped.getValue().stream()
                .sorted(Comparator.comparing(ResourceEconomicProfile::resourceId)).toList();
            double weight = aliases.getFirst().calibrationWeight();
            Double cost = null;
            for (ResourceEconomicProfile alias : aliases) {
                if (Double.compare(alias.calibrationWeight(), weight) != 0) {
                    throw new IllegalArgumentException("Grouped resource aliases must have identical calibration weights: " + grouped.getKey());
                }
                if (alias.economicCost() != null) {
                    if (cost != null && Double.compare(cost, alias.economicCost()) != 0) {
                        throw new IllegalArgumentException("Grouped resource aliases must have identical economic costs: " + grouped.getKey());
                    }
                    cost = alias.economicCost();
                }
            }
            candidateWeight += weight;
            if (cost != null) {
                resolvedWeight += weight;
                candidates.add(new Entity(grouped.getKey(), cost, weight, aliases));
            }
        }
        if (!Double.isFinite(candidateWeight) || !Double.isFinite(resolvedWeight)) {
            throw new IllegalArgumentException("total calibration weights must remain finite");
        }

        candidates.sort(Comparator.comparingDouble(Entity::economicCost).thenComparing(Entity::identity));
        double coverage = candidateWeight == 0.0D ? 0.0D : resolvedWeight / candidateWeight;
        CalibrationCoverageStatus coverageStatus = coverage < lowCoverageThreshold
            ? CalibrationCoverageStatus.LOW
            : coverage < mediumCoverageThreshold ? CalibrationCoverageStatus.MEDIUM : CalibrationCoverageStatus.HIGH;

        if (candidates.isEmpty()) {
            return new CalibrationSnapshot(population, population.size(), candidateWeight, resolvedWeight,
                coverage, coverageStatus,
                0.0D, 0.0D, 0.0D, magnitudeWeight, rankWeight, CalibrationStatus.EMPTY, Map.of(),
                signature(groups), "medium", FoodValueCurve.preset("medium", false),
                FoodValueCurve.preset("medium", true), "weighted_midrank_exact_cost_ties");
        }

        double p05 = weightedQuantile(candidates, lowerQuantile, resolvedWeight);
        double p50 = weightedQuantile(candidates, 0.50D, resolvedWeight);
        double p95 = weightedQuantile(candidates, upperQuantile, resolvedWeight);
        boolean degenerate = Double.compare(p05, p95) == 0
            || candidates.stream().map(Entity::economicCost).distinct().count() == 1;
        CalibrationStatus status = candidates.size() < minimumPopulation
            ? CalibrationStatus.LOW_SAMPLE
            : degenerate ? CalibrationStatus.DEGENERATE : CalibrationStatus.VALID;

        Map<Double, Double> ranks = weightedRanks(candidates, resolvedWeight);
        Map<String, CalibratedFoodValue> calibratedValues = new LinkedHashMap<>();
        for (Entity candidate : candidates) {
            double rank = ranks.get(candidate.economicCost());
            double magnitude = degenerate ? rank : clamp(
                (Math.log1p(candidate.economicCost()) - p05) / (p95 - p05), 0.0D, 1.0D);
            if (status == CalibrationStatus.LOW_SAMPLE || status == CalibrationStatus.DEGENERATE) {
                magnitude = rank;
            }
            double index = clamp(magnitudeWeight * magnitude + rankWeight * rank, 0.0D, 1.0D);
            CalibratedFoodValue value = new CalibratedFoodValue(candidate.economicCost(), magnitude, rank, index);
            for (ResourceEconomicProfile alias : candidate.aliases()) {
                calibratedValues.put(alias.resourceId(), value);
            }
        }

        return new CalibrationSnapshot(population, population.size(), candidateWeight, resolvedWeight, coverage,
            coverageStatus, p05, p50, p95, magnitudeWeight, rankWeight, status, calibratedValues,
            signature(groups), "medium", FoodValueCurve.preset("medium", false),
            FoodValueCurve.preset("medium", true), "weighted_midrank_exact_cost_ties");
    }

    private double weightedQuantile(List<Entity> population, double quantile, double totalWeight) {
        double threshold = quantile * totalWeight;
        double cumulative = 0.0D;
        for (Entity resource : population) {
            cumulative += resource.weight();
            if (cumulative >= threshold) {
                return Math.log1p(resource.economicCost());
            }
        }
        return Math.log1p(population.getLast().economicCost());
    }

    private static Map<Double, Double> weightedRanks(List<Entity> population, double totalWeight) {
        Map<Double, Double> ranks = new java.util.HashMap<>();
        double lessWeight = 0.0D;
        for (int first = 0; first < population.size();) {
            double cost = population.get(first).economicCost();
            double equalWeight = 0.0D;
            int afterTie = first;
            while (afterTie < population.size()
                && Double.compare(population.get(afterTie).economicCost(), cost) == 0) {
                equalWeight += population.get(afterTie).weight();
                afterTie++;
            }
            ranks.put(cost, clamp((lessWeight + 0.5D * equalWeight) / totalWeight, 0.0D, 1.0D));
            lessWeight += equalWeight;
            first = afterTie;
        }
        return ranks;
    }

    private String signature(Map<String, List<ResourceEconomicProfile>> groups) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(minimumPopulation).append('|').append(Double.toHexString(magnitudeWeight)).append('|')
            .append(Double.toHexString(rankWeight)).append('|').append(Double.toHexString(lowerQuantile)).append('|')
            .append(Double.toHexString(upperQuantile)).append('|')
            .append(Double.toHexString(lowCoverageThreshold)).append('|')
            .append(Double.toHexString(mediumCoverageThreshold)).append('|');
        groups.forEach((identity, aliases) -> {
            canonical.append(identity).append(':');
            aliases.stream().sorted(Comparator.comparing(ResourceEconomicProfile::resourceId)).forEach(resource ->
                canonical.append(resource.resourceId()).append('=')
                    .append(resource.economicCost() == null ? "UNKNOWN" : Double.toHexString(resource.economicCost()))
                    .append('@').append(Double.toHexString(resource.calibrationWeight())).append(';'));
            canonical.append('|');
        });
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Entity(String identity, double economicCost, double weight, List<ResourceEconomicProfile> aliases) {
    }
}