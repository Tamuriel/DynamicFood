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
    private final double materialReference;
    private final double materialCap;

    public RecipeGraphAcquisitionAnalyzer(RecipeGraph graph, EconomicCostEvidenceProvider independentEvidence,
        double materialReference, double materialCap) {
        this(graph, independentEvidence, materialReference, materialCap,
            com.jorjik.dynamicfood.config.DynamicFoodConfig.lootAttemptsReference(),
            com.jorjik.dynamicfood.config.DynamicFoodConfig.lootAttemptsCap(),
            com.jorjik.dynamicfood.config.DynamicFoodConfig.timeCostReferenceTicks(),
            com.jorjik.dynamicfood.config.DynamicFoodConfig.timeCostCapTicks());
    }

    public RecipeGraphAcquisitionAnalyzer(RecipeGraph graph, EconomicCostEvidenceProvider independentEvidence,
        double materialReference, double materialCap, double quantityReference, double quantityCap,
        double timeReferenceTicks, double timeCapTicks) {
        if (!positiveFinite(quantityReference) || !positiveFinite(quantityCap)
            || !positiveFinite(timeReferenceTicks) || !positiveFinite(timeCapTicks)) {
            throw new IllegalArgumentException("quantity and time normalization references must be finite and positive");
        }
        this.graph = graph;
        if (!positiveFinite(materialReference) || !positiveFinite(materialCap)) {
            throw new IllegalArgumentException("material normalization references must be finite and positive");
        }
        this.economics = new RecipeEconomicAnalyzer(graph, independentEvidence);
        this.quantityReference = quantityReference;
        this.quantityCap = quantityCap;
        this.timeReferenceTicks = timeReferenceTicks;
        this.timeCapTicks = timeCapTicks;
        this.materialReference = materialReference;
        this.materialCap = materialCap;
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
            Map<Integer, CostVector> costsByHorizon = new HashMap<>();
            for (int horizon : supportedHorizons()) {
                RecipeEconomicResult resolved = economics.resolveUsingRecipe(recipe, horizon);
                Map<String, EconomicFactor> costFactors = new java.util.LinkedHashMap<>();
                costFactors.put("quantity_cost", FactorNormalizer.quantityCostForHorizon(recipe.outputCount(),
                    horizon, quantityReference, quantityCap));
                costFactors.put("time_cost", recipe.processingTimeTicks() == null
                    ? EconomicFactor.unknown("processing duration is not exposed by this recipe type")
                    : FactorNormalizer.logarithmic(recipe.processingTimeTicks() / recipe.outputCount() * horizon,
                        timeReferenceTicks, timeCapTicks));
                costFactors.put("material_cost", !resolved.target().isKnown()
                    ? EconomicFactor.unknown("EconomicCost is UNKNOWN: " + resolved.target().evidence())
                    : EconomicFactor.notApplicable(
                        "recursive material EconomicCost is the canonical magnitude and is not normalized again"));
                costFactors.put("probability_cost", EconomicFactor.notApplicable(
                    "recipe output quantity is deterministic and represented by quantity_cost"));
                costFactors.put("yield_cost", EconomicFactor.notApplicable(
                    "recipe output quantity is represented by quantity_cost"));
                costFactors.put("startup_cost", EconomicFactor.notApplicable(
                    "static recipe data exposes no separate one-time setup operation"));
                costFactors.put("recurring_cost", EconomicFactor.notApplicable(
                    "recurring inputs and processing are represented by material_cost and time_cost"));
                costFactors.put("prerequisite_cost", EconomicFactor.notApplicable(
                    "recipe definition exposes no separate acquisition prerequisite"));
                costFactors.put("progression_cost", EconomicFactor.notApplicable(
                    "recipe definition exposes no progression-gated acquisition"));
                costFactors.put("equipment_cost", recipe.recipeType().startsWith("minecraft:crafting")
                    && recipe.acquisitionIngredients().stream().noneMatch(input ->
                        input.inputUse() != com.jorjik.dynamicfood.graph.AcquisitionIngredient.InputUse.CONSUMED)
                    ? EconomicFactor.notApplicable("vanilla crafting recipe metadata has no required machine")
                    : EconomicFactor.unknown("recipe metadata does not expose machine acquisition cost"));
                costFactors.put("danger_cost", EconomicFactor.notApplicable(
                    "recipe processing has no world-danger mechanic in the recipe definition"));
                costFactors.put("transport_cost", EconomicFactor.notApplicable(
                    "recipe definition contains no transport mechanic"));
                costFactors.put("intermediate_cost", EconomicFactor.notApplicable(
                    "recursive intermediate inputs are included in material_cost"));
                costFactors.put("resource_consumption_cost", recipe.acquisitionIngredients().stream()
                    .anyMatch(input -> input.inputUse() ==
                        com.jorjik.dynamicfood.graph.AcquisitionIngredient.InputUse.UNKNOWN)
                    ? EconomicFactor.unknown("recipe input consumption or remainder is unresolved")
                    : EconomicFactor.notApplicable("consumed recipe inputs are included in material_cost"));
                costsByHorizon.put(horizon, new CostVector(horizon, costFactors));
            }
            RecipeEconomicResult resolved = economics.resolveUsingRecipe(recipe,
                com.jorjik.dynamicfood.config.DynamicFoodConfig.acquisitionEconomicHorizon());
            Map<String, AcquisitionMeasurement> measurements = new HashMap<>();
            measurements.put("expected_units_per_attempt", AcquisitionMeasurement.known(recipe.outputCount()));
            measurements.put("expected_attempts_per_unit", AcquisitionMeasurement.known(1.0D / recipe.outputCount()));
            measurements.put("processing_time_ticks", recipe.processingTimeTicks() == null
                ? AcquisitionMeasurement.unknown("processing duration is not exposed by this recipe type")
                : AcquisitionMeasurement.known(recipe.processingTimeTicks()));
            measurements.put("material_cost_per_output", !resolved.target().isKnown()
                ? AcquisitionMeasurement.unknown("EconomicCost is UNKNOWN: " + resolved.target().evidence())
                : AcquisitionMeasurement.known(resolved.target().value()));
            for (int horizon : List.of(1, 10, 100,
                com.jorjik.dynamicfood.config.DynamicFoodConfig.acquisitionEconomicHorizon())) {
                measurements.put("expected_attempts_to_obtain_" + horizon,
                    AcquisitionMeasurement.known(horizon / (double) recipe.outputCount()));
            }
            Map<String, String> attributes = new HashMap<>();
            attributes.put("recipe_type", recipe.recipeType());
            attributes.put("canonical_quantity_source", "recipe outputCount");
            attributes.put("canonical_quantity_unit", "item per completed recipe operation");
            attributes.put("canonical_quantity_semantics", "guaranteed deterministic output count per operation");
            attributes.put("survival_availability",
                "unknown: recipe registration does not prove player access or progression requirements");
            attributes.put("source_availability_classification", "UNKNOWN");
            attributes.put("intermediate_steps", String.join(" -> ", resolved.recipePath()));
            attributes.put("missing_inputs", String.join("; ", resolved.missingInputs()));
            for (int index = 0; index < recipe.acquisitionIngredients().size(); index++) {
                var input = recipe.acquisitionIngredients().get(index);
                attributes.put("input_" + index + "_alternatives", String.join("|", input.alternatives()));
                attributes.put("input_" + index + "_quantity", Integer.toString(input.count()));
                attributes.put("input_" + index + "_use", input.inputUse().name().toLowerCase(java.util.Locale.ROOT));
            }
            paths.add(new AcquisitionPath(itemId, "recipe", recipe.recipeId(), 1.0D,
                null, null, true, false, recipeFeasibilityFactors(recipe), costsByHorizon,
                new AcquisitionEvidence(measurements, attributes), resolved.target()));
        }
        return List.copyOf(paths);
    }

    private static Map<String, EconomicFactor> recipeFeasibilityFactors(RecipeNode recipe) {
        boolean vanillaCrafting = recipe.recipeType().startsWith("minecraft:crafting");
        boolean hasReusableOrUnresolvedInputs = recipe.acquisitionIngredients().stream()
            .anyMatch(input -> input.inputUse() !=
                com.jorjik.dynamicfood.graph.AcquisitionIngredient.InputUse.CONSUMED);
        boolean hasUnresolvedInputUse = recipe.acquisitionIngredients().stream()
            .anyMatch(input -> input.inputUse() ==
                com.jorjik.dynamicfood.graph.AcquisitionIngredient.InputUse.UNKNOWN);
        return Map.ofEntries(
            Map.entry("probability", EconomicFactor.notApplicable("recipe output is deterministic")),
            Map.entry("expected_yield", EconomicFactor.notApplicable(
                "deterministic output quantity is represented by canonical quantity")),
            Map.entry("repeatability", EconomicFactor.known(1.0D)),
            Map.entry("renewability", EconomicFactor.unknown(
                "renewability depends on the recursively resolved input acquisition sources")),
            Map.entry("startup_cost", EconomicFactor.notApplicable("recipe requires no separately modeled startup")),
            Map.entry("recurring_cost", EconomicFactor.notApplicable(
                "per-operation costs are represented by material and time cost factors")),
            Map.entry("prerequisite_cost", EconomicFactor.notApplicable(
                "recipe definition exposes no separate acquisition prerequisite")),
            Map.entry("processing_requirements", EconomicFactor.known(1.0D)),
            Map.entry("progression_requirement", EconomicFactor.notApplicable(
                "recipe operation has no progression gate in its recipe definition")),
            Map.entry("danger", EconomicFactor.notApplicable("recipe processing has no world-danger mechanic")),
            Map.entry("resource_consumption", hasUnresolvedInputUse
                ? EconomicFactor.unknown("recipe input consumption or remainder is unresolved")
                : EconomicFactor.notApplicable(
                    "consumed recipe inputs are included in recursive material economics")),
            Map.entry("intermediate_steps", EconomicFactor.notApplicable(
                "recursive input steps are recorded in acquisition evidence")),
            Map.entry("equipment_availability", vanillaCrafting
                && !hasReusableOrUnresolvedInputs
                ? EconomicFactor.notApplicable("vanilla crafting does not require a machine")
                : EconomicFactor.unknown("recipe metadata does not expose machine availability")),
            Map.entry("reliability", EconomicFactor.known(1.0D))
        );
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