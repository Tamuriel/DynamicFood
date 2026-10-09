package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import com.jorjik.dynamicfood.graph.RecipeGraph;
import com.jorjik.dynamicfood.graph.RecipeNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipeGraphAcquisitionAnalyzerTest {
    @Test
    void craftingDurationIsDiagnosticAndInputEvidenceIsPreserved() {
        RecipeGraph graph = new RecipeGraph();
        RecipeNode recipe = new RecipeNode("test:craft", "minecraft:crafting", "test:output", 4,
            List.of(), null, List.of(new AcquisitionIngredient(List.of("test:input"), 2)), true);
        graph.add(recipe);

        AcquisitionPath path = new RecipeGraphAcquisitionAnalyzer(graph,
            (itemId, horizon) -> EconomicCost.unknown("no independent EconomicCost"),
            1.0D, 100.0D).analyze("test:output").getFirst();

        assertTrue(!path.costsByHorizon().get(100).factors().containsKey("time_cost"));
        assertTrue(!path.evidence().measurement("processing_time_ticks").isKnown());
        assertTrue(path.costsByHorizon().get(100)
            .factor(EconomicChannel.PROBABILITY_BURDEN).isNotApplicable());
        assertEquals(List.of(new AcquisitionIngredient(List.of("test:input"), 2)),
            path.evidence().inputs());
        assertEquals("TRUE", path.evidence().attributes().get("recipe_operation_availability"));
        assertEquals("UNKNOWN", path.evidence().attributes().get("source_availability_classification"));
    }

    @Test
    void processingRecipeUsesMeasuredProcessingDuration() {
        RecipeGraph graph = new RecipeGraph();
        RecipeNode recipe = new RecipeNode("test:smelt", "minecraft:smelting", "test:output", 1,
            List.of(), 200.0D, List.of(new AcquisitionIngredient(List.of("test:input"), 1)), true);
        graph.add(recipe);

        AcquisitionPath path = new RecipeGraphAcquisitionAnalyzer(graph,
            (itemId, horizon) -> EconomicCost.unknown("no independent EconomicCost"),
            1.0D, 100.0D).analyze("test:output").getFirst();

        assertTrue(!path.costsByHorizon().get(100).factors().containsKey("time_cost"));
        assertEquals(200.0D, path.evidence().measurement("processing_time_ticks").value());
    }

    @Test
    void unresolvedEmptyIngredientIsNotSilentlyDropped() {
        RecipeGraph graph = new RecipeGraph();
        RecipeNode recipe = new RecipeNode("test:unknown_input", "minecraft:crafting", "test:output", 1,
            List.of(), null, List.of(new AcquisitionIngredient(
                List.of(), 1, AcquisitionIngredient.InputUse.UNKNOWN)), true);
        graph.add(recipe);

        AcquisitionPath path = new RecipeGraphAcquisitionAnalyzer(graph,
            (itemId, horizon) -> EconomicCost.unknown("no independent EconomicCost"),
            1.0D, 100.0D).analyze("test:output").getFirst();

        assertEquals(1, path.evidence().inputs().size());
        assertTrue(path.costsByHorizon().get(100).factors().get("material_cost").isUnknown());
        assertTrue(!path.costsByHorizon().get(100).factors().containsKey("resource_consumption_cost"));
    }

    @Test
    void unresolvedInputReasonIsPreservedInAcquisitionEvidence() {
        String reason = "ingredient tag(s) resolved to no item alternatives: #test:missing";
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:missing_recipe", "minecraft:crafting", "test:output", 1,
            List.of(), null, List.of(new AcquisitionIngredient(
                List.of(), 1, AcquisitionIngredient.InputUse.UNKNOWN, reason)), true));

        AcquisitionPath path = new RecipeGraphAcquisitionAnalyzer(graph,
            (itemId, horizon) -> EconomicCost.unknown("no independent EconomicCost"),
            1.0D, 100.0D).analyze("test:output").getFirst();

        assertEquals(reason, path.evidence().attributes().get("input_0_unresolved_reason"));
    }
}
