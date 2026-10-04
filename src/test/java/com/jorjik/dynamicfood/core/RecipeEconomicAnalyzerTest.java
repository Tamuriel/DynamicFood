package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import com.jorjik.dynamicfood.graph.RecipeGraph;
import com.jorjik.dynamicfood.graph.RecipeNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecipeEconomicAnalyzerTest {
    @Test
    void knownIngredientAlternativeResolvesPartiallyWhenSiblingAlternativeIsUnknown() {
        RecipeGraph graph = new RecipeGraph();
        RecipeNode recipe = recipe("test:output_from_tag", "test:output",
            List.of(new AcquisitionIngredient(
                List.of("test:known_input", "test:unknown_input"), 1)));
        graph.add(recipe);
        EconomicCostEvidenceProvider evidence = providerFor("test:known_input", 0.4D);

        RecipeEconomicResult result = new RecipeEconomicAnalyzer(graph, evidence)
            .resolveUsingRecipe(recipe, 10);
        AcquisitionPath path = new RecipeGraphAcquisitionAnalyzer(graph, evidence, 1.0D, 100.0D)
            .analyze("test:output").getFirst();
        EconomicCostResolution resolution = EconomicCostResolver.resolve("test:output", List.of(path),
            100, PrimaryPathStrategy.BEST_REPEATABLE_COST, Map.of("reliability", 1.0D),
            0.0D, 1.0D, false);

        assertEquals(0.4D, result.economicCost(), 0.0D);
        assertEquals(ResolutionStatus.PARTIAL, result.status());
        assertTrue(result.missingInputs().stream().anyMatch(reason -> reason.contains("test:unknown_input")));
        assertEquals(null, resolution.economicCost());
        assertEquals(ResolutionStatus.UNKNOWN, resolution.status());
    }

    @Test
    void unresolvedRecipeAlternativeDoesNotPoisonAnotherResolvedRecipePath() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(recipe("test:a_unpriced_recipe", "test:output",
            List.of(new AcquisitionIngredient(List.of("test:unknown_input"), 1))));
        graph.add(recipe("test:z_priced_recipe", "test:output",
            List.of(new AcquisitionIngredient(List.of("test:known_input"), 1))));

        RecipeEconomicResult result = new RecipeEconomicAnalyzer(graph,
            providerFor("test:known_input", 0.7D)).resolve("test:output", 10);

        assertEquals(0.7D, result.economicCost(), 0.0D);
        assertEquals(ResolutionStatus.PARTIAL, result.status());
        assertTrue(result.recipePath().contains("test:z_priced_recipe"));
        assertTrue(result.missingInputs().stream().anyMatch(reason -> reason.contains("test:unknown_input")));
    }

    @Test
    void unknownAcquisitionPathDoesNotPoisonAResolvedAlternativePath() {
        AcquisitionPath unknown = new AcquisitionPath("test:resource", "loot", "test:unknown",
            1.0D, 1.0D, 0.0D, true, false,
            Map.of("reliability", EconomicFactor.known(1.0D)), Map.of());
        Map<String, EconomicFactor> factors = new java.util.LinkedHashMap<>();
        factors.put("quantity_cost", EconomicFactor.known(0.7D));
        factors.put("time_cost", EconomicFactor.notApplicable("not modeled"));
        factors.put("material_cost", EconomicFactor.notApplicable("not modeled"));
        factors.put("equipment_cost", EconomicFactor.notApplicable("not modeled"));
        AcquisitionPath known = new AcquisitionPath("test:resource", "loot", "test:known",
            1.0D, 1.0D, 0.0D, true, false,
            Map.of("reliability", EconomicFactor.known(1.0D)),
            Map.of(10, new CostVector(10, factors)),
            new AcquisitionEvidence(Map.of(),
                Map.of("source_availability_classification", "TRUE")));

        EconomicCostResolution result = EconomicCostResolver.resolve("test:resource",
            List.of(unknown, known), 10, PrimaryPathStrategy.BEST_REPEATABLE_COST,
            Map.of("reliability", 1.0D), 0.0D, 1.0D, false);

        assertEquals(0.7D, result.economicCost(), 0.0D);
        assertEquals(ResolutionStatus.PARTIAL, result.status());
        assertEquals("test:known", result.primaryPath().sourceId());
    }

    private static RecipeNode recipe(String recipeId, String output,
        List<AcquisitionIngredient> ingredients) {
        return new RecipeNode(recipeId, "minecraft:crafting", output, 1, List.of(), null, ingredients);
    }

    private static EconomicCostEvidenceProvider providerFor(String knownItem, double cost) {
        return (itemId, horizon) -> knownItem.equals(itemId)
            ? EconomicCost.known(cost, "test:primitive", horizon, "test evidence")
            : EconomicCost.unknown("no test evidence for " + itemId);
    }
}
