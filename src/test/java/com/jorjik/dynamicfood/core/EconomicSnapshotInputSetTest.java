package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import com.jorjik.dynamicfood.graph.RecipeGraph;
import com.jorjik.dynamicfood.graph.RecipeNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EconomicSnapshotInputSetTest {
    @Test
    void includesOnlyIndexedCandidatesAndTheirTransitiveConsumedRecipeDependencies() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(recipe("test:bread", "test:wheat", AcquisitionIngredient.InputUse.CONSUMED));
        graph.add(recipe("test:wheat", "test:seed", AcquisitionIngredient.InputUse.CONSUMED));
        EconomicSnapshotInputSet inputSet = EconomicSnapshotInputSet.derive(
            List.of("test:bread", "test:technical", "test:bread"),
            List.of(),
            graph,
            "test:technical"::equals);

        assertEquals(Set.of("test:bread"), inputSet.candidateResourceIds());
        assertEquals(Set.of("test:wheat", "test:seed"), inputSet.recursiveDependencyIds());
        assertEquals(Set.of("test:technical"), inputSet.technicalCandidateExclusionIds());
        assertEquals(Set.of("test:bread", "test:wheat", "test:seed"), inputSet.resourceIds());
        assertFalse(inputSet.resourceIds().contains("test:registry_only"),
            "registered resources without indexed acquisition evidence or a dependency edge are outside this interim boundary");
    }

    @Test
    void retainsTechnicalItemWhenItIsAConsumedEconomicDependency() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(recipe("test:output", "test:technical", AcquisitionIngredient.InputUse.CONSUMED));

        EconomicSnapshotInputSet inputSet = EconomicSnapshotInputSet.derive(
            List.of("test:output", "test:technical"), List.of(), graph,
            "test:technical"::equals);

        assertEquals(Set.of("test:output"), inputSet.candidateResourceIds());
        assertEquals(Set.of("test:technical"), inputSet.recursiveDependencyIds());
        assertEquals(Set.of("test:technical"), inputSet.technicalDependencyIds());
        assertTrue(inputSet.resourceIds().contains("test:technical"),
            "technical classification excludes unrelated roots but cannot discard a required consumed input");
    }

    @Test
    void preservesConfiguredOverrideAsCandidateAndIgnoresReusableOrUnknownUseAsCostDependencies() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:recipe", "minecraft:crafting", "test:food", 1,
            List.of(), null, List.of(
                new AcquisitionIngredient(List.of("test:override"), 1),
                new AcquisitionIngredient(List.of("test:tool"), 1, AcquisitionIngredient.InputUse.REUSABLE),
                new AcquisitionIngredient(List.of("test:uncertain"), 1, AcquisitionIngredient.InputUse.UNKNOWN)
            )));

        EconomicSnapshotInputSet inputSet = EconomicSnapshotInputSet.derive(
            List.of("test:food"), List.of("test:override"), graph, ignored -> false);

        assertEquals(Set.of("test:food", "test:override"), inputSet.candidateResourceIds());
        assertEquals(Set.of("test:override"), inputSet.recursiveDependencyIds());
        assertTrue(inputSet.resourceIds().contains("test:override"));
        assertFalse(inputSet.resourceIds().contains("test:tool"));
        assertFalse(inputSet.resourceIds().contains("test:uncertain"));
        assertEquals(Set.of("test:tool"), inputSet.reusableInputIds());
        assertEquals(Set.of("test:uncertain"), inputSet.unresolvedUseInputIds());
    }

    @Test
    void inputSetOrderingAndSnapshotSignatureAreDeterministicAndGenerationIndependent() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(recipe("test:food", "test:material", AcquisitionIngredient.InputUse.CONSUMED));
        EconomicSnapshotInputSet firstInputs = EconomicSnapshotInputSet.derive(
            List.of("test:food", "test:other"), List.of(), graph, ignored -> false);
        EconomicSnapshotInputSet repeatedInputs = EconomicSnapshotInputSet.derive(
            List.of("test:other", "test:food", "test:food"), List.of(), graph, ignored -> false);
        EconomicSnapshot first = build(1, firstInputs);
        EconomicSnapshot nextGeneration = build(2, repeatedInputs);

        assertEquals(firstInputs, repeatedInputs);
        assertEquals(first.resources().keySet(), nextGeneration.resources().keySet());
        assertEquals(first.signature(), nextGeneration.signature());
        assertEquals(Set.of("test:food", "test:material", "test:other"), first.resources().keySet());
        assertEquals(1, first.inputSet().dependencyOnlyCount());
    }

    private static RecipeNode recipe(String output, String input, AcquisitionIngredient.InputUse inputUse) {
        return new RecipeNode("test:" + output.substring("test:".length()), "minecraft:crafting",
            output, 1, List.of(), null,
            List.of(new AcquisitionIngredient(List.of(input), 1, inputUse)));
    }

    private static EconomicSnapshot build(long generation, EconomicSnapshotInputSet inputSet) {
        return EconomicSnapshotBuilder.build(generation, inputSet, List.of(),
            (resourceId, paths) -> new EconomicCostResolution(
                EconomicCost.unknown("test economic value unavailable"),
                null, ResolutionStatus.UNKNOWN, 100, null, paths, 0.0D, List.of("test unknown")));
    }
}
