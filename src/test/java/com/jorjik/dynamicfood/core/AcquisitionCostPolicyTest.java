package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import com.jorjik.dynamicfood.graph.RecipeGraph;
import com.jorjik.dynamicfood.graph.RecipeNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AcquisitionCostPolicyTest {
    @Test
    void coreCompletenessDoesNotDependOnAdditionalCoverage() {
        CostVector vector = vector(EconomicFactor.known(0.2D), EconomicFactor.notApplicable("not used"),
            EconomicFactor.notApplicable("not used"), EconomicFactor.notApplicable("not used"),
            EconomicFactor.unknown("not measured"));

        AcquisitionCost cost = AcquisitionCostResolver.resolve(vector, weights(1.0D, 1.0D));

        assertTrue(vector.isCoreComplete());
        assertEquals(ResolutionStatus.COMPLETE, cost.status());
        assertEquals(0.2D, cost.cost(), 0.0D);
        assertEquals(0.0D, cost.additionalCoverage(), 0.0D);
        assertTrue(cost.missingFactors().containsKey("resource_consumption_cost"));
    }

    @Test
    void unknownCorePreventsNumericCostAndIsNotTreatedAsZero() {
        CostVector vector = vector(EconomicFactor.known(0.2D), EconomicFactor.unknown("time unavailable"),
            EconomicFactor.notApplicable("not used"), EconomicFactor.notApplicable("not used"),
            EconomicFactor.notApplicable("not used"));

        AcquisitionCost cost = AcquisitionCostResolver.resolve(vector, weights(1.0D, 1.0D));

        assertFalse(vector.isCoreComplete());
        assertEquals(ResolutionStatus.UNKNOWN, cost.status());
        assertEquals(null, cost.cost());
        assertTrue(cost.missingFactors().containsKey("time_cost"));
    }

    @Test
    void knownAdditionalFactorParticipatesButNotApplicableDoesNot() {
        CostVector vector = vector(EconomicFactor.known(0.2D), EconomicFactor.notApplicable("not used"),
            EconomicFactor.notApplicable("not used"), EconomicFactor.notApplicable("not used"),
            EconomicFactor.known(0.8D));
        Map<String, Double> allWeights = weights(1.0D, 1.0D);

        AcquisitionCost both = AcquisitionCostResolver.resolve(vector, allWeights);
        AcquisitionCost coreOnly = AcquisitionCostResolver.resolve(vector, weights(1.0D, 0.0D));

        assertEquals(0.5D, both.cost(), 0.0D);
        assertEquals(0.2D, coreOnly.cost(), 0.0D);
        assertTrue(both.notApplicableFactors().containsKey("time_cost"));
    }

    @Test
    void knownZeroAndZeroWeightRemainDistinctFromNotApplicable() {
        CostVector vector = vector(EconomicFactor.known(0.0D), EconomicFactor.notApplicable("not used"),
            EconomicFactor.notApplicable("not used"), EconomicFactor.notApplicable("not used"),
            EconomicFactor.notApplicable("not used"));
        AcquisitionCost included = AcquisitionCostResolver.resolve(vector, weights(1.0D, 0.0D));
        AcquisitionCost zeroWeight = AcquisitionCostResolver.resolve(vector, weights(0.0D, 0.0D));

        assertEquals(0.0D, included.cost(), 0.0D);
        assertTrue(included.normalizedFactors().containsKey("quantity_cost"));
        assertEquals(ResolutionStatus.UNKNOWN, zeroWeight.status());
        assertTrue(zeroWeight.normalizedFactors().containsKey("quantity_cost"));
        assertFalse(zeroWeight.notApplicableFactors().containsKey("quantity_cost"));
    }

    @Test
    void deterministicWeightedAggregateChangesWithConfiguredWeights() {
        CostVector vector = vector(EconomicFactor.known(0.2D), EconomicFactor.notApplicable("not used"),
            EconomicFactor.notApplicable("not used"), EconomicFactor.notApplicable("not used"),
            EconomicFactor.known(0.8D));

        AcquisitionCost quantityEmphasis = AcquisitionCostResolver.resolve(vector, weights(3.0D, 1.0D));
        AcquisitionCost materialEmphasis = AcquisitionCostResolver.resolve(vector, weights(1.0D, 3.0D));
        AcquisitionCost repeated = AcquisitionCostResolver.resolve(vector, weights(3.0D, 1.0D));

        assertEquals(0.35D, quantityEmphasis.cost(), 1.0E-15D);
        assertEquals(0.65D, materialEmphasis.cost(), 1.0E-15D);
        assertEquals(quantityEmphasis, repeated);
    }

    @Test
    void centralConfigurationProvidesWeightsForEveryPolicyFactor() {
        Map<String, Double> configured = DynamicFoodConfig.costFactorWeights();

        assertEquals(CostVector.CORE_FACTORS.size() + CostVector.ADDITIONAL_FACTORS.size(), configured.size());
        assertTrue(configured.keySet().containsAll(CostVector.CORE_FACTORS));
        assertTrue(configured.keySet().containsAll(CostVector.ADDITIONAL_FACTORS));
        assertTrue(configured.values().stream().allMatch(weight ->
            Double.isFinite(weight) && weight >= 0.0D));
    }

    @Test
    void eligiblePathWithUnknownAdditionalFactorRemainsSelectable() {
        CostVector vector = vector(EconomicFactor.known(0.2D), EconomicFactor.notApplicable("not used"),
            EconomicFactor.notApplicable("not used"), EconomicFactor.notApplicable("not used"),
            EconomicFactor.unknown("resource consumption is not measured"));
        AcquisitionPath path = new AcquisitionPath("test:resource", "recipe", "test:source", 1.0D,
            null, null, true, false, Map.of("reliability", EconomicFactor.known(1.0D)),
            Map.of(100, vector), new AcquisitionEvidence(Map.of(),
                Map.of("source_availability_classification", "TRUE")),
            EconomicCost.unknown("resolved from CostVector"));

        EconomicCostResolution result = EconomicCostResolver.resolve("test:resource", List.of(path), 100,
            PrimaryPathStrategy.BEST_REPEATABLE_COST, Map.of("reliability", 1.0D),
            Map.of("quantity_cost", 1.0D, "time_cost", 1.0D, "material_cost", 1.0D,
                "equipment_cost", 1.0D, "resource_consumption_cost", 1.0D),
            0.0D, 1.0D, false);

        assertEquals(ResolutionStatus.COMPLETE, result.status());
        assertEquals(0.2D, result.economicCost(), 0.0D);
        assertEquals("test:source", result.primaryPath().sourceId());
    }

    @Test
    void recursiveChildEconomicCostIsUsedDirectlyWithoutRenormalization() {
        RecipeGraph graph = new RecipeGraph();
        RecipeNode recipe = new RecipeNode("test:child_to_output", "minecraft:crafting",
            "test:output", 1, List.of(), 200.0D,
            List.of(new AcquisitionIngredient(List.of("test:child"), 1)));
        graph.add(recipe);
        EconomicCostEvidenceProvider child = (itemId, horizon) -> "test:child".equals(itemId)
            ? EconomicCost.known(0.25D, "dynamicfood:economic-policy-v1", horizon, "child policy cost")
            : EconomicCost.unknown("missing child economics");

        AcquisitionPath path = new RecipeGraphAcquisitionAnalyzer(graph, child, 1.0D, 100.0D)
            .analyze("test:output").getFirst();

        assertEquals(0.25D,
            path.costsByHorizon().get(100).factors().get("material_cost").value(), 0.0D);
    }

    private static CostVector vector(EconomicFactor quantity, EconomicFactor time, EconomicFactor material,
        EconomicFactor equipment, EconomicFactor resourceConsumption) {
        Map<String, EconomicFactor> factors = new LinkedHashMap<>();
        factors.put("quantity_cost", quantity);
        factors.put("time_cost", time);
        factors.put("material_cost", material);
        factors.put("equipment_cost", equipment);
        factors.put("resource_consumption_cost", resourceConsumption);
        return new CostVector(100, factors);
    }

    private static Map<String, Double> weights(double quantity, double resourceConsumption) {
        return Map.of("quantity_cost", quantity, "time_cost", 1.0D,
            "resource_consumption_cost", resourceConsumption);
    }
}
