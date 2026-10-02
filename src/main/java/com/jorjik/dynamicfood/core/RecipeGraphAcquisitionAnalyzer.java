package com.jorjik.dynamicfood.core;

import com.jorjik.dynamicfood.graph.RecipeGraph;
import com.jorjik.dynamicfood.graph.RecipeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public final class RecipeGraphAcquisitionAnalyzer implements AcquisitionAnalyzer {
    private final RecipeEconomicAnalyzer economics;
    private final RecipeGraph graph;
    private final double quantityReference;
    private final double quantityCap;
    private final double timeReferenceTicks;
    private final double timeCapTicks;

    public RecipeGraphAcquisitionAnalyzer(RecipeGraph graph, Map<String, Double> terminalCosts,
        double materialReference, double materialCap) {
        this(graph, terminalCosts, materialReference, materialCap,
            com.jorjik.dynamicfood.config.DynamicFoodConfig.lootAttemptsReference(),
            com.jorjik.dynamicfood.config.DynamicFoodConfig.lootAttemptsCap(),
            com.jorjik.dynamicfood.config.DynamicFoodConfig.timeCostReferenceTicks(),
            com.jorjik.dynamicfood.config.DynamicFoodConfig.timeCostCapTicks());
    }

    public RecipeGraphAcquisitionAnalyzer(RecipeGraph graph, Map<String, Double> terminalCosts,
        double materialReference, double materialCap, double quantityReference, double quantityCap,
        double timeReferenceTicks, double timeCapTicks) {
        if (!positiveFinite(quantityReference) || !positiveFinite(quantityCap)
            || !positiveFinite(timeReferenceTicks) || !positiveFinite(timeCapTicks)) {
            throw new IllegalArgumentException("quantity and time normalization references must be finite and positive");
        }
        this.graph = graph;
        this.economics = new RecipeEconomicAnalyzer(graph, terminalCosts, materialReference, materialCap);
        this.quantityReference = quantityReference;
        this.quantityCap = quantityCap;
        this.timeReferenceTicks = timeReferenceTicks;
        this.timeCapTicks = timeCapTicks;
    }

    @Override
    public boolean supports(String itemId) {
        return !graph.recipesFor(itemId).isEmpty();
    }

    @Override
    public List<AcquisitionPath> analyze(String itemId) {
        if (!supports(itemId)) {
            return List.of();
        }
        List<AcquisitionPath> paths = new ArrayList<>();
        for (RecipeNode recipe : graph.recipesFor(itemId)) {
            RecipeEconomicResult resolved = economics.resolveUsingRecipe(recipe).orElse(null);
            if (resolved == null) {
                continue;
            }
            Map<String, EconomicFactor> costFactors = new java.util.LinkedHashMap<>();
            EconomicFactor quantity = FactorNormalizer.quantityCost(recipe.outputCount(),
                quantityReference, quantityCap);
            EconomicFactor time = recipe.processingTimeTicks() == null
                ? EconomicFactor.unknown("processing duration is not exposed by this recipe type")
                : FactorNormalizer.logarithmic(
                    recipe.processingTimeTicks() / recipe.outputCount(), timeReferenceTicks, timeCapTicks);
            costFactors.put("quantity_cost", quantity);
            costFactors.put("time_cost", time);
            costFactors.put("material_cost", resolved.economicCost() == null
                ? EconomicFactor.unknown("one or more recipe inputs have unknown economic cost")
                : EconomicFactor.known(resolved.economicCost()));
            for (String factor : List.of("probability_cost", "yield_cost", "startup_cost", "recurring_cost",
                "prerequisite_cost", "progression_cost", "equipment_cost", "danger_cost", "transport_cost",
                "intermediate_cost", "resource_consumption_cost")) {
                costFactors.put(factor, EconomicFactor.unknown(
                    "not observable from static recipe metadata and configured terminal profiles"));
            }
            Map<Integer, CostVector> costsByHorizon = new HashMap<>();
            for (int horizon : supportedHorizons()) {
                costsByHorizon.put(horizon, new CostVector(horizon, costFactors));
            }
            Map<String, AcquisitionMeasurement> measurements = new HashMap<>();
            measurements.put("expected_units_per_attempt", AcquisitionMeasurement.known(recipe.outputCount()));
            measurements.put("expected_attempts_per_unit", AcquisitionMeasurement.known(1.0D / recipe.outputCount()));
            measurements.put("processing_time_ticks", recipe.processingTimeTicks() == null
                ? AcquisitionMeasurement.unknown("processing duration is not exposed by this recipe type")
                : AcquisitionMeasurement.known(recipe.processingTimeTicks()));
            measurements.put("material_cost_per_output", resolved.economicCost() == null
                ? AcquisitionMeasurement.unknown("one or more recipe inputs have unknown economic cost")
                : AcquisitionMeasurement.known(resolved.economicCost()));
            for (int horizon : List.of(1, 10, 100,
                com.jorjik.dynamicfood.config.DynamicFoodConfig.acquisitionEconomicHorizon())) {
                measurements.put("expected_attempts_to_obtain_" + horizon,
                    AcquisitionMeasurement.known(horizon / (double) recipe.outputCount()));
            }
            paths.add(new AcquisitionPath(itemId, "recipe", recipe.recipeId(), 1.0D,
                null, null, true, false, Map.of(
                    "repeatability", EconomicFactor.known(1.0D),
                    "reliability", EconomicFactor.known(1.0D),
                    "processing_requirements", EconomicFactor.known(1.0D)
                ), costsByHorizon, new AcquisitionEvidence(measurements, Map.of(
                    "recipe_type", recipe.recipeType(),
                    "intermediate_steps", String.join(" -> ", resolved.recipePath()),
                    "missing_inputs", String.join("; ", resolved.missingInputs())
                ))));
        }
        return List.copyOf(paths);
    }

    private static List<Integer> supportedHorizons() {
        return java.util.stream.Stream.of(1, 10, 100,
                com.jorjik.dynamicfood.config.DynamicFoodConfig.acquisitionEconomicHorizon())
            .distinct().sorted().toList();
    }

    private static boolean positiveFinite(double value) {
        return Double.isFinite(value) && value > 0.0D;
    }
}