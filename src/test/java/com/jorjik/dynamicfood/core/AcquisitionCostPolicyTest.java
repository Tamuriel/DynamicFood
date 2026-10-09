package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import com.jorjik.dynamicfood.graph.RecipeGraph;
import com.jorjik.dynamicfood.graph.RecipeNode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AcquisitionCostPolicyTest {
    @Test
    void applicableUnknownProbabilityBurdenBlocksCoreCost() {
        CostVector vector = vector(EconomicFactor.known(0.2D),
            EconomicFactor.unknown("no bounded probability normalization"),
            EconomicFactor.notApplicable("no input materials"), EconomicFactor.notApplicable("no equipment"));

        AcquisitionCost cost = AcquisitionCostResolver.resolve(vector, unitWeights());

        assertFalse(vector.isCoreComplete());
        assertEquals(ResolutionStatus.UNKNOWN, cost.status());
        assertEquals(null, cost.cost());
        assertEquals("no bounded probability normalization",
            cost.missingFactors().get(EconomicChannel.PROBABILITY_BURDEN.id()));
    }

    @Test
    void notApplicableCoreChannelsDoNotBlockCompleteness() {
        CostVector vector = vector(EconomicFactor.known(0.2D),
            EconomicFactor.notApplicable("selection is represented by expected quantity"),
            EconomicFactor.notApplicable("no input materials"), EconomicFactor.notApplicable("no equipment"));

        AcquisitionCost cost = AcquisitionCostResolver.resolve(vector, unitWeights());

        assertTrue(vector.isCoreComplete());
        assertEquals(ResolutionStatus.COMPLETE, cost.status());
        assertEquals(0.2D, cost.cost(), 0.0D);
        assertTrue(cost.notApplicableFactors().containsKey(EconomicChannel.PROBABILITY_BURDEN.id()));
    }

    @Test
    void diagnosticDurationCannotEnterCostVectorOrDefaultAggregation() {
        assertThrows(IllegalArgumentException.class, () -> new CostVector(100,
            Map.of("time_cost", EconomicFactor.unknown("duration is diagnostic"))));
        assertThrows(IllegalArgumentException.class, () -> AcquisitionCostResolver.resolve(
            vector(EconomicFactor.known(0.2D), EconomicFactor.notApplicable("none"),
                EconomicFactor.notApplicable("none"), EconomicFactor.notApplicable("none")),
            Map.of("time_cost", 1.0D)));
    }

    @Test
    void structuralFactorsCannotEnterCostVectorOrDefaultAggregation() {
        assertThrows(IllegalArgumentException.class, () -> new CostVector(100,
            Map.of("resource_consumption_cost", EconomicFactor.known(0.8D))));
        assertThrows(IllegalArgumentException.class, () -> AcquisitionCostResolver.resolve(
            vector(EconomicFactor.known(0.2D), EconomicFactor.notApplicable("none"),
                EconomicFactor.notApplicable("none"), EconomicFactor.notApplicable("none")),
            Map.of("progression_cost", 1.0D)));
    }

    @Test
    void knownZeroRemainsDistinctFromNotApplicable() {
        CostVector vector = vector(EconomicFactor.known(0.0D),
            EconomicFactor.notApplicable("no separate probability burden"),
            EconomicFactor.notApplicable("no input materials"), EconomicFactor.notApplicable("no equipment"));

        AcquisitionCost cost = AcquisitionCostResolver.resolve(vector, unitWeights());

        assertEquals(0.0D, cost.cost(), 0.0D);
        assertTrue(cost.normalizedFactors().containsKey(EconomicChannel.QUANTITY.id()));
        assertFalse(cost.notApplicableFactors().containsKey(EconomicChannel.QUANTITY.id()));
    }

    @Test
    void deterministicWeightedAggregateUsesOnlyCanonicalChannels() {
        CostVector vector = vector(EconomicFactor.known(0.2D), EconomicFactor.notApplicable("no independent burden"),
            EconomicFactor.known(0.8D), EconomicFactor.notApplicable("no equipment"));
        Map<String, Double> quantityEmphasis = Map.of(
            EconomicChannel.QUANTITY.id(), 3.0D,
            EconomicChannel.PROBABILITY_BURDEN.id(), 1.0D,
            EconomicChannel.MATERIAL_CONSUMPTION.id(), 1.0D,
            EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN.id(), 1.0D);
        Map<String, Double> materialEmphasis = Map.of(
            EconomicChannel.QUANTITY.id(), 1.0D,
            EconomicChannel.PROBABILITY_BURDEN.id(), 1.0D,
            EconomicChannel.MATERIAL_CONSUMPTION.id(), 3.0D,
            EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN.id(), 1.0D);

        AcquisitionCost first = AcquisitionCostResolver.resolve(vector, quantityEmphasis);
        AcquisitionCost second = AcquisitionCostResolver.resolve(vector, materialEmphasis);

        assertEquals(0.35D, first.cost(), 1.0E-15D);
        assertEquals(0.65D, second.cost(), 1.0E-15D);
        assertEquals(first, AcquisitionCostResolver.resolve(vector, quantityEmphasis));
    }

    @Test
    void centralConfigurationExposesExactlyCanonicalChannels() {
        Map<String, Double> configured = DynamicFoodConfig.costFactorWeights();

        assertEquals(List.of(EconomicChannel.values()).size(), configured.size());
        assertTrue(configured.keySet().containsAll(
            java.util.Arrays.stream(EconomicChannel.values()).map(EconomicChannel::id).toList()));
        assertTrue(configured.values().stream().allMatch(weight -> Double.isFinite(weight) && weight >= 0.0D));
    }

    @Test
    void recursiveChildEconomicCostIsUsedDirectlyWithoutRenormalization() {
        RecipeGraph graph = new RecipeGraph();
        RecipeNode recipe = new RecipeNode("test:child_to_output", "minecraft:crafting",
            "test:output", 1, List.of(), 200.0D,
            List.of(new AcquisitionIngredient(List.of("test:child"), 1)));
        graph.add(recipe);
        EconomicCostEvidenceProvider child = (itemId, horizon) -> "test:child".equals(itemId)
            ? EconomicCost.known(0.25D, "dynamicfood:economic-policy-v2", horizon, "child policy cost")
            : EconomicCost.unknown("missing child economics");

        AcquisitionPath path = new RecipeGraphAcquisitionAnalyzer(graph, child, 1.0D, 100.0D)
            .analyze("test:output").getFirst();

        assertEquals(0.25D,
            path.costsByHorizon().get(100).factor(EconomicChannel.MATERIAL_CONSUMPTION).value(), 0.0D);
    }

    private static CostVector vector(EconomicFactor quantity, EconomicFactor probability,
        EconomicFactor material, EconomicFactor equipment) {
        Map<EconomicChannel, EconomicFactor> factors = new EnumMap<>(EconomicChannel.class);
        factors.put(EconomicChannel.QUANTITY, quantity);
        factors.put(EconomicChannel.PROBABILITY_BURDEN, probability);
        factors.put(EconomicChannel.MATERIAL_CONSUMPTION, material);
        factors.put(EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN, equipment);
        return new CostVector(100, factors);
    }

    private static Map<String, Double> unitWeights() {
        return Map.of(
            EconomicChannel.QUANTITY.id(), 1.0D,
            EconomicChannel.PROBABILITY_BURDEN.id(), 1.0D,
            EconomicChannel.MATERIAL_CONSUMPTION.id(), 1.0D,
            EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN.id(), 1.0D);
    }
}
