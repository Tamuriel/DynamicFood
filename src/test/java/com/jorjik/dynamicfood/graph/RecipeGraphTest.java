package com.jorjik.dynamicfood.graph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jorjik.dynamicfood.core.IngredientContribution;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipeGraphTest {
    @Test
    void cycleIndexIsCachedByExactItemAndInvalidatedWhenGraphChanges() {
        RecipeGraph graph = new RecipeGraph();

        assertTrue(graph.valueIncreasingEconomicCycles().isEmpty());
        assertTrue(graph.valueIncreasingEconomicCyclesFor("test:a").isEmpty());

        graph.add(new RecipeNode("test:a_from_b", "minecraft:crafting", "test:a", 2,
            List.of(IngredientContribution.of("test:b", 1.0D, 0.0D, 1, true))));
        graph.add(new RecipeNode("test:b_from_a", "minecraft:crafting", "test:b", 1,
            List.of(IngredientContribution.of("test:a", 1.0D, 0.0D, 1, true))));

        assertFalse(graph.valueIncreasingEconomicCyclesFor("test:a").isEmpty());
        assertTrue(graph.valueIncreasingEconomicCyclesFor("test:ab").isEmpty());

        graph.clear();
        assertTrue(graph.valueIncreasingEconomicCycles().isEmpty());
        assertTrue(graph.valueIncreasingEconomicCyclesFor("test:a").isEmpty());
    }

    @Test
    void balancedCycleWithCompensatingRecipeYieldsIsNotReportedAsValueIncreasing() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:a_from_b", "minecraft:crafting", "test:a", 2,
            List.of(IngredientContribution.of("test:b", 1.0D, 0.0D, 1, true))));
        graph.add(new RecipeNode("test:b_from_a", "minecraft:crafting", "test:b", 1,
            List.of(IngredientContribution.of("test:a", 1.0D, 0.0D, 2, true))));

        assertTrue(graph.valueIncreasingEconomicCycles().isEmpty());
        assertFalse(graph.cycleFrom("test:a").isEmpty());
    }

    @Test
    void repeatedConcreteRecipeInputsAreCountedTogetherForCycleYield() {
        RecipeGraph graph = new RecipeGraph();
        List<AcquisitionIngredient> inputsForA = List.of(
            new AcquisitionIngredient(List.of("test:b"), 1),
            new AcquisitionIngredient(List.of("test:b"), 1));
        List<AcquisitionIngredient> inputsForB = List.of(
            new AcquisitionIngredient(List.of("test:a"), 1));
        graph.add(new RecipeNode("test:a_from_b", "minecraft:crafting", "test:a", 2,
            List.of(IngredientContribution.of("test:b", 1.0D, 0.0D, 1, true)),
            null, inputsForA));
        graph.add(new RecipeNode("test:b_from_a", "minecraft:crafting", "test:b", 1,
            List.of(IngredientContribution.of("test:a", 1.0D, 0.0D, 1, true)),
            null, inputsForB));

        assertTrue(graph.valueIncreasingEconomicCycles().isEmpty());
    }
}
