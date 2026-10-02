package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jorjik.dynamicfood.graph.RecipeGraph;
import com.jorjik.dynamicfood.graph.AcquisitionIngredient;
import com.jorjik.dynamicfood.graph.RecipeNode;
import com.jorjik.dynamicfood.graph.RecipeResolver;
import com.jorjik.dynamicfood.graph.RecipeValueCache;
import com.jorjik.dynamicfood.config.ItemFoodProfile;
import com.jorjik.dynamicfood.config.EconomicProfileOverride;
import com.jorjik.dynamicfood.config.FluidFoodProfile;
import com.jorjik.dynamicfood.adapter.RecipeAdapterRegistry;
import com.jorjik.dynamicfood.provenance.DynamicFoodValue;
import com.jorjik.dynamicfood.provenance.IngredientFoodSnapshot;
import com.jorjik.dynamicfood.provenance.OutputValueAllocator;
import com.jorjik.dynamicfood.provenance.OperationFoodSnapshot;
import com.jorjik.dynamicfood.provenance.RuntimeProvenance;
import com.jorjik.dynamicfood.provenance.SaturationConverter;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class DynamicFoodEngineTest {
    @Test
    void factorNormalizerIsFixedAndQuantityDoesNotRequireSeparateProbabilityYieldScores() {
        EconomicFactor normalized = FactorNormalizer.logarithmic(10.0D, 1.0D, 100.0D);
        EconomicFactor quantity = FactorNormalizer.quantityCost(0.5D, 1.0D, 100.0D);
        EconomicFactor invalid = FactorNormalizer.logarithmic(Double.NaN, 1.0D, 100.0D);
        CostVector vector = new CostVector(100, Map.of(
            "quantity_cost", quantity,
            "probability_cost", EconomicFactor.known(1.0D),
            "yield_cost", EconomicFactor.known(1.0D)
        ));
        AcquisitionCost cost = AcquisitionCostResolver.resolve(vector, Map.of("quantity_cost", 1.0D));

        assertTrue(normalized.value() > 0.0D && normalized.value() < 1.0D);
        assertEquals(FactorNormalizer.logarithmic(10.0D, 1.0D, 100.0D), normalized);
        assertEquals(FactorNormalizer.logarithmic(2.0D, 1.0D, 100.0D).value(), quantity.value(), 0.0001D);
        assertEquals(ResolutionStatus.UNKNOWN, invalid.isKnown() ? ResolutionStatus.COMPLETE : ResolutionStatus.UNKNOWN);
        assertEquals(quantity.value(), cost.cost(), 0.0001D);
        assertEquals(Map.of("quantity_cost", quantity.value()), cost.normalizedFactors());
    }

    @Test
    void logarithmicNormalizerHandlesExtremeFiniteInputsDeterministically() {
        EconomicFactor normalized = FactorNormalizer.logarithmic(1.0E300D, 1.0E-300D, 1.0E301D);

        assertTrue(normalized.isKnown());
        assertTrue(normalized.value() > 0.0D && normalized.value() < 1.0D);
        assertEquals(1.0D, FactorNormalizer.logarithmic(1.0E300D, 1.0D, 1.0E200D).value(), 0.0D);
    }

    @Test
    void acquisitionCostDistinguishesCompletePartialAndUnknownFactors() {
        Map<String, Double> weights = Map.of("quantity_cost", 1.0D, "time_cost", 1.0D);
        AcquisitionCost complete = AcquisitionCostResolver.resolve(new CostVector(100, Map.of(
            "quantity_cost", EconomicFactor.known(0.2D), "time_cost", EconomicFactor.known(0.6D))), weights);
        AcquisitionCost partial = AcquisitionCostResolver.resolve(new CostVector(100, Map.of(
            "quantity_cost", EconomicFactor.known(0.2D), "time_cost", EconomicFactor.unknown("not observable"))), weights);
        AcquisitionCost unknown = AcquisitionCostResolver.resolve(new CostVector(100, Map.of(
            "quantity_cost", EconomicFactor.unknown("missing"))), Map.of("quantity_cost", 1.0D));

        assertEquals(ResolutionStatus.COMPLETE, complete.status());
        assertEquals(0.4D, complete.cost(), 0.0D);
        assertEquals(ResolutionStatus.PARTIAL, partial.status());
        assertEquals(0.2D, partial.cost(), 0.0D);
        assertEquals(ResolutionStatus.UNKNOWN, unknown.status());
        assertEquals(null, unknown.cost());
    }

    @Test
    void feasibilityCoverageAndHardFailuresGatePrimaryPaths() {
        Map<String, Double> weights = Map.of("probability", 1.0D, "equipment", 1.0D);
        AcquisitionPath partialPath = acquisitionPath("test:item", "test:partial", true, 0.8D,
            Map.of("probability", EconomicFactor.known(1.0D), "equipment", EconomicFactor.unknown("unknown")),
            Map.of("quantity_cost", EconomicFactor.known(0.2D)), 100);
        FeasibilityResult rejected = FeasibilityResolver.resolve(partialPath, weights, 0.8D, 0.1D, false);
        FeasibilityResult allowed = FeasibilityResolver.resolve(partialPath, weights, 0.8D, 0.1D, true);
        AcquisitionPath impossible = new AcquisitionPath("test:item", "loot", "test:impossible", 1.0D,
            0.0D, 1.0D, false, true, Map.of(), Map.of());
        FeasibilityResult hardFail = FeasibilityResolver.resolve(impossible, weights, 0.8D, 0.1D, true);

        assertEquals(0.5D, rejected.coverage(), 0.0D);
        assertEquals(ResolutionStatus.PARTIAL, rejected.status());
        assertTrue(!rejected.eligibleForPrimary());
        assertTrue(allowed.eligibleForPrimary());
        assertTrue(hardFail.hardFailed());
        assertTrue(!hardFail.eligibleForPrimary());
    }

    @Test
    void bestRepeatablePathUsesOneHorizonAndMonotonicDifficultyProjection() {
        AcquisitionPath oneOff = acquisitionPath("test:item", "test:one_off", false, 1.0D,
            Map.of("reliability", EconomicFactor.known(1.0D)),
            Map.of("quantity_cost", EconomicFactor.known(0.05D)), 100);
        AcquisitionPath repeatable = acquisitionPath("test:item", "test:repeatable", true, 0.8D,
            Map.of("reliability", EconomicFactor.known(1.0D)),
            Map.of("quantity_cost", EconomicFactor.known(0.4D)), 100);
        AcquisitionPath mismatchedHorizon = acquisitionPath("test:item", "test:wrong_horizon", true, 1.0D,
            Map.of("reliability", EconomicFactor.known(1.0D)),
            Map.of("quantity_cost", EconomicFactor.known(0.0D)), 10);
        EconomicCostResolution result = EconomicCostResolver.resolve("test:item",
            List.of(oneOff, mismatchedHorizon, repeatable), 100, PrimaryPathStrategy.BEST_REPEATABLE_COST,
            Map.of("reliability", 1.0D), Map.of("quantity_cost", 1.0D), 0.1D, 0.8D, false, false);

        assertEquals("test:repeatable", result.primaryPath().sourceId());
        assertEquals(0.4D, result.economicCost(), 0.0D);
        assertEquals(2.0D, result.difficulty(), 0.0D);
        assertEquals(100, result.economicHorizon());
        assertTrue(result.alternatives().stream().noneMatch(path -> path.sourceId().equals("test:wrong_horizon")));
    }

    @Test
    void weightedAverageWithZeroFeasibilityReturnsUnknownInsteadOfNanCost() {
        AcquisitionPath path = acquisitionPath("test:item", "test:zero", true, 1.0D,
            Map.of("reliability", EconomicFactor.known(0.0D)),
            Map.of("quantity_cost", EconomicFactor.known(0.5D)), 100);
        EconomicCostResolution result = EconomicCostResolver.resolve("test:item", List.of(path), 100,
            PrimaryPathStrategy.WEIGHTED_AVERAGE, Map.of("reliability", 1.0D),
            Map.of("quantity_cost", 1.0D), 0.0D, 0.8D, false, false);

        assertEquals(ResolutionStatus.UNKNOWN, result.status());
        assertEquals(null, result.economicCost());
    }

    @Test
    void engineUsesCalibrationSnapshotAndCustomCurveOverridesOnlyThatCurve() {
        DynamicFoodEngine engine = new DynamicFoodEngine();
        FoodCalibrationSettings settings = new FoodCalibrationSettings(true, "vanilla", 1,
            0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D, "medium",
            List.of(new CalibrationAnchor(0.0D, 2.0D), new CalibrationAnchor(1.0D, 6.0D)),
            List.of(), 2.0D, 0.0D);
        engine.rebuildCalibration(List.of(
            calibrationProfile("test:cheap", "test:cheap", 0.1D, 1.0D),
            calibrationProfile("test:expensive", "test:expensive", 0.9D, 1.0D)
        ), settings);

        CalibratedBaseFoodValue cheap = engine.calibratedBaseFoodValue("test:cheap").orElseThrow();
        CalibratedBaseFoodValue expensive = engine.calibratedBaseFoodValue("test:expensive").orElseThrow();

        assertTrue(cheap.nutrition() <= expensive.nutrition());
        assertTrue(cheap.effectiveSaturation() <= expensive.effectiveSaturation());
        assertEquals(FoodValueCurve.preset("medium", true).evaluate(cheap.foodIndex()), cheap.effectiveSaturation(), 0.0001D);
        assertEquals(0.075D, cheap.foodIndex(), 0.0001D);
    }

    @Test
    void calibrationSignatureChangesWhenGameplayPresetChanges() {
        DynamicFoodEngine engine = new DynamicFoodEngine();
        ResourceEconomicProfile resource = calibrationProfile("test:food", "test:food", 0.4D, 1.0D);
        FoodCalibrationSettings medium = new FoodCalibrationSettings(true, "vanilla", 1,
            0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D, "medium",
            List.of(), List.of(), 2.0D, 0.0D);
        FoodCalibrationSettings hard = new FoodCalibrationSettings(true, "vanilla", 1,
            0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D, "hard",
            List.of(), List.of(), 2.0D, 0.0D);
        engine.rebuildCalibration(List.of(resource), medium);
        String mediumSignature = engine.calibrationSnapshot().orElseThrow().signature();
        engine.rebuildCalibration(List.of(resource), hard);

        assertTrue(!mediumSignature.equals(engine.calibrationSnapshot().orElseThrow().signature()));
    }

    @Test
    void calibrationSnapshotStoresTheResolvedFoodCurvesAndTiePolicy() {
        FoodCalibrationSettings settings = new FoodCalibrationSettings(true, "vanilla", 1,
            0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D, "hard",
            List.of(new CalibrationAnchor(0.0D, 0.0D), new CalibrationAnchor(1.0D, 12.0D)),
            List.of(), 2.0D, 0.0D);
        DynamicFoodEngine engine = new DynamicFoodEngine();
        engine.rebuildCalibration(List.of(calibrationProfile("test:curve_food", "test:curve_food",
            0.5D, 1.0D)), settings);

        CalibrationSnapshot snapshot = engine.calibrationSnapshot().orElseThrow();
        CalibratedFoodValue calibrated = snapshot.calibratedValues().get("test:curve_food");

        assertEquals("hard", snapshot.gameplayPreset());
        assertEquals(List.of(new CalibrationAnchor(0.0D, 0.0D),
            new CalibrationAnchor(1.0D, 12.0D)), snapshot.hungerAnchors());
        assertEquals(FoodValueCurve.preset("hard", true).anchors(), snapshot.saturationAnchors());
        assertEquals("weighted_midrank_exact_cost_ties", snapshot.rankTieMode());
        assertEquals(12.0D * calibrated.foodIndex(),
            engine.calibratedBaseFoodValue("test:curve_food").orElseThrow().nutrition(), 0.0001D);
    }

    @Test
    void automaticCalibrationRecognizesIndependentAcquisitionBesideRecipePath() {
        AcquisitionPath recipePath = new AcquisitionPath("test:food", "recipe", "test:make_food",
            1.0D, null, null, true, false, Map.of(), Map.of());
        AcquisitionPath lootPath = new AcquisitionPath("test:food", "mob_drop", "test:mob_drop",
            1.0D, null, null, true, false, Map.of(), Map.of());

        assertTrue(!DynamicFoodEngine.hasIndependentAcquisitionPath(List.of(recipePath)));
        assertTrue(DynamicFoodEngine.hasIndependentAcquisitionPath(List.of(recipePath, lootPath)));
        assertTrue(DynamicFoodEngine.hasIndependentAcquisitionPath(List.of(lootPath)));
    }

    @Test
    void explicitlyConfiguredFoodComponentTagCanQualifyWithoutVanillaFoodProperties() {
        assertTrue(DynamicFoodEngine.isFoodPopulationCandidate(false, true));
        assertTrue(DynamicFoodEngine.isFoodPopulationCandidate(true, false));
        assertTrue(!DynamicFoodEngine.isFoodPopulationCandidate(false, false));
    }

    @Test
    void recipeEconomicAnalyzerUsesCurrentAssemblyEconomicsAndHandlesCycles() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:dirt_to_diamond", "minecraft:crafting", "examplemod:diamond", 1,
            List.of(IngredientContribution.of("examplemod:dirt", 0.0D, 0.0D, 1, true))));
        graph.add(new RecipeNode("test:a_from_b", "minecraft:crafting", "examplemod:a", 1,
            List.of(IngredientContribution.of("examplemod:b", 0.0D, 0.0D, 1, true))));
        graph.add(new RecipeNode("test:b_from_a", "minecraft:crafting", "examplemod:b", 1,
            List.of(IngredientContribution.of("examplemod:a", 0.0D, 0.0D, 1, true))));
        RecipeEconomicAnalyzer analyzer = new RecipeEconomicAnalyzer(graph,
            Map.of("examplemod:dirt", 0.05D, "minecraft:diamond", 0.95D), 1.0D, 100.0D);

        RecipeEconomicResult cheapDiamond = analyzer.resolve("examplemod:diamond");
        RecipeEconomicResult cycle = analyzer.resolve("examplemod:a");

        assertTrue(cheapDiamond.economicCost() < 0.95D);
        assertEquals("test:dirt_to_diamond", cheapDiamond.recipePath().getFirst());
        assertEquals(ResolutionStatus.UNKNOWN, cycle.status());
        assertTrue(!cycle.detectedCycles().isEmpty());
    }

    @Test
    void recipeEconomicAnalyzerIncludesNonFoodTechnicalInputs() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:technical_input_to_food", "minecraft:crafting",
            "examplemod:meal", 1, List.of(), null,
            List.of(new AcquisitionIngredient(List.of("minecraft:dirt"), 1))));
        RecipeEconomicResult result = new RecipeEconomicAnalyzer(graph,
            Map.of("minecraft:dirt", 0.05D), 1.0D, 100.0D).resolve("examplemod:meal");

        assertEquals(ResolutionStatus.COMPLETE, result.status());
        assertEquals("test:technical_input_to_food", result.recipePath().getFirst());
        assertNotNull(result.economicCost());
    }

    @Test
    void recipeEconomicAnalyzerDoesNotInventCostForAmbiguousTagInputs() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:tag_recipe", "minecraft:crafting", "examplemod:meal", 1,
            List.of(IngredientContribution.of("dynamicfood:ingredient_alternatives", 1.0D, 0.0D, 1, true))));
        RecipeEconomicResult result = new RecipeEconomicAnalyzer(graph, Map.of("examplemod:seed", 0.1D), 1.0D, 100.0D)
            .resolve("examplemod:meal");

        assertEquals(ResolutionStatus.UNKNOWN, result.status());
        assertEquals(null, result.economicCost());
        assertTrue(!result.missingInputs().isEmpty());
        List<AcquisitionPath> acquisitionPaths = new RecipeGraphAcquisitionAnalyzer(graph,
            Map.of("examplemod:seed", 0.1D), 1.0D, 100.0D).analyze("examplemod:meal");
        assertEquals(1, acquisitionPaths.size());
        assertTrue(!acquisitionPaths.getFirst().costsByHorizon().get(100)
            .factors().get("material_cost").isKnown());
    }

    @Test
    void standardRecipeAcquisitionAnalyzerSupportsArbitraryNamespaces() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("testmod:berry_to_food", "minecraft:crafting", "testmod:berry_food", 1,
            List.of(IngredientContribution.of("testmod:berry", 0.0D, 0.0D, 2, true))));
        RecipeGraphAcquisitionAnalyzer analyzer = new RecipeGraphAcquisitionAnalyzer(graph,
            Map.of("testmod:berry", 0.2D), 1.0D, 100.0D);

        List<AcquisitionPath> paths = analyzer.analyze("testmod:berry_food");

        assertTrue(analyzer.supports("testmod:berry_food"));
        assertEquals(1, paths.size());
        assertEquals("testmod:berry_to_food", paths.getFirst().sourceId());
        assertEquals("recipe", paths.getFirst().sourceType());
        assertEquals(FactorNormalizer.logarithmic(0.4D, 1.0D, 100.0D).value(), paths.getFirst()
            .costsByHorizon().get(100).factors().get("material_cost").value(), 0.0001D);
    }

    @Test
    void recipeAcquisitionUsesObservedOutputQuantityAndProcessingTime() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("testmod:cooked_meal", "minecraft:smelting", "testmod:meal", 2,
            List.of(IngredientContribution.of("testmod:raw_food", 1.0D, 0.2D, 1, true)), 600.0D));
        RecipeGraphAcquisitionAnalyzer analyzer = new RecipeGraphAcquisitionAnalyzer(graph,
            Map.of("testmod:raw_food", 0.2D), 1.0D, 100.0D, 1.0D, 10000.0D, 200.0D, 72000.0D);

        AcquisitionPath path = analyzer.analyze("testmod:meal").getFirst();
        CostVector vector = path.costsByHorizon().get(100);

        assertEquals(FactorNormalizer.quantityCost(2.0D, 1.0D, 10000.0D),
            vector.factors().get("quantity_cost"));
        assertEquals(FactorNormalizer.logarithmic(300.0D, 200.0D, 72000.0D),
            vector.factors().get("time_cost"));
        assertTrue(!vector.factors().get("danger_cost").isKnown());
    }

    @Test
    void recipeAcquisitionAnalyzerPreservesPathsWithUnknownInputCosts() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("testmod:unknown_recipe", "minecraft:crafting", "testmod:output", 1,
            List.of(IngredientContribution.of("testmod:unknown_input", 0.0D, 0.0D, 1, true))));
        RecipeGraphAcquisitionAnalyzer analyzer = new RecipeGraphAcquisitionAnalyzer(graph, Map.of(), 1.0D, 100.0D);

        List<AcquisitionPath> paths = analyzer.analyze("testmod:output");
        assertEquals(1, paths.size());
        assertTrue(!paths.getFirst().costsByHorizon().get(100)
            .factors().get("material_cost").isKnown());
        assertTrue(paths.getFirst().costsByHorizon().get(100)
            .factors().get("quantity_cost").isKnown());
    }

    @Test
    void engineCachesRecipeAcquisitionAnalyzerUntilRecipeReload() {
        DynamicFoodEngine engine = new DynamicFoodEngine();
        engine.rebuildCalibration(List.of(calibrationProfile("examplemod:seed", "examplemod:seed", 0.2D, 1.0D)),
            FoodCalibrationSettings.defaults());
        engine.replaceStaticRecipes(List.of(new RecipeNode("testmod:recipe", "minecraft:crafting",
            "testmod:output", 1, List.of(IngredientContribution.of("examplemod:seed", 0.0D, 0.0D, 1, true)))));

        assertEquals("testmod:recipe", engine.acquisitionPaths("testmod:output").getFirst().sourceId());
        engine.replaceStaticRecipes(List.of());

        assertTrue(engine.acquisitionPaths("testmod:output").isEmpty());
    }

    @Test
    void optionalAcquisitionAnalyzerExtendsStandardRecipeDiscovery() {
        DynamicFoodEngine engine = new DynamicFoodEngine();
        engine.registerAcquisitionAnalyzer(new AcquisitionAnalyzer() {
            @Override
            public boolean supports(String itemId) {
                return itemId.equals("uniquemod:ritual_food");
            }

            @Override
            public List<AcquisitionPath> analyze(String itemId) {
                return List.of(new AcquisitionPath(itemId, "ritual", "uniquemod:ritual", 0.6D,
                    0.2D, 0.3D, true, false,
                    Map.of("reliability", EconomicFactor.known(0.8D)), Map.of()));
            }
        });

        List<AcquisitionPath> paths = engine.acquisitionPaths("uniquemod:ritual_food");

        assertEquals(1, paths.size());
        assertEquals("ritual", paths.getFirst().sourceType());
    }

    @Test
    void lootAnalyzerCalculatesWeightedExpectedYieldForArbitraryNamespaceItems() {
        var table = JsonParser.parseString("""
            {"pools":[{"rolls":2,"entries":[
              {"type":"minecraft:item","name":"examplemod:fish","weight":1},
              {"type":"minecraft:item","name":"examplemod:rod","weight":3}
            ]}]}
            """).getAsJsonObject();
        LootTableAcquisitionAnalyzer.ParsedTable parsed = LootTableAcquisitionAnalyzer
            .parseTable("examplemod:loot/fishing", table).orElseThrow();
        LootTableAcquisitionAnalyzer analyzer = LootTableAcquisitionAnalyzer
            .fromParsedTables(List.of(parsed), 1.0D, 100.0D);

        List<AcquisitionPath> fishPaths = analyzer.analyze("examplemod:fish");
        List<AcquisitionPath> rodPaths = analyzer.analyze("examplemod:rod");

        assertEquals(1, fishPaths.size());
        assertEquals("examplemod:loot/fishing", fishPaths.getFirst().sourceId());
        assertEquals("fishing", fishPaths.getFirst().sourceType());
        assertEquals("fishing", rodPaths.getFirst().sourceType());
        assertEquals(FactorNormalizer.quantityCost(0.5D, 1.0D, 100.0D).value(),
            fishPaths.getFirst().costsByHorizon().get(100).factors().get("quantity_cost").value(), 0.0001D);
        assertEquals(FactorNormalizer.quantityCost(1.5D, 1.0D, 100.0D).value(),
            rodPaths.getFirst().costsByHorizon().get(100).factors().get("quantity_cost").value(), 0.0001D);
        assertEquals(null, fishPaths.getFirst().renewability());
    }

    @Test
    void lootAnalyzerRetainsExactProbabilityAndConditionalYieldWithoutDoubleCounting() {
        var table = JsonParser.parseString("""
            {"pools":[{"rolls":2,"entries":[
              {"type":"minecraft:item","name":"examplemod:fish","weight":1},
              {"type":"minecraft:item","name":"examplemod:rod","weight":3}
            ]}]}
            """).getAsJsonObject();
        var parsed = LootTableAcquisitionAnalyzer.parseTable("examplemod:gameplay/fishing", table).orElseThrow();
        var path = LootTableAcquisitionAnalyzer.fromParsedTables(List.of(parsed), 1.0D, 100.0D)
            .analyze("examplemod:fish").getFirst();
        double probability = 1.0D - Math.pow(0.75D, 2.0D);
        double expectedYieldOnSuccess = 0.5D / probability;

        assertEquals(probability, path.evidence().measurement("probability").value(), 1.0E-12D);
        assertEquals(expectedYieldOnSuccess,
            path.evidence().measurement("expected_yield_on_success").value(), 1.0E-12D);
        assertEquals(0.5D, path.evidence().measurement("expected_units_per_attempt").value(), 1.0E-12D);
        assertEquals(FactorNormalizer.quantityCost(0.5D, 1.0D, 100.0D),
            path.costsByHorizon().get(100).factors().get("quantity_cost"));

        AcquisitionCost combined = AcquisitionCostResolver.resolve(path.costsByHorizon().get(100),
            Map.of("quantity_cost", 1.0D, "probability_cost", 1.0D, "yield_cost", 1.0D));
        assertEquals(path.costsByHorizon().get(100).factors().get("quantity_cost").value(),
            combined.cost(), 1.0E-12D);
        assertEquals(ResolutionStatus.COMPLETE, combined.status());
    }

    @Test
    void recipeAnalyzerRetainsEveryKnownAcquisitionRoute() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("examplemod:craft_from_seed", "minecraft:crafting", "examplemod:meal", 1,
            List.of(IngredientContribution.of("examplemod:seed", 0.0D, 0.0D, 1, true))));
        graph.add(new RecipeNode("examplemod:craft_from_berry", "minecraft:crafting", "examplemod:meal", 2,
            List.of(IngredientContribution.of("examplemod:berry", 0.0D, 0.0D, 2, true))));
        RecipeGraphAcquisitionAnalyzer analyzer = new RecipeGraphAcquisitionAnalyzer(graph,
            Map.of("examplemod:seed", 0.2D, "examplemod:berry", 0.1D), 1.0D, 100.0D);

        List<AcquisitionPath> paths = analyzer.analyze("examplemod:meal");

        assertEquals(List.of("examplemod:craft_from_berry", "examplemod:craft_from_seed"),
            paths.stream().map(AcquisitionPath::sourceId).toList());
        assertTrue(paths.stream().allMatch(path ->
            path.evidence().measurement("material_cost_per_output").isKnown()));
    }

    @Test
    void lootAnalyzerPreservesConditionalSourcesAsUnknownInsteadOfInventingProbabilities() {
        var conditional = JsonParser.parseString("""
            {"pools":[{"rolls":1,"conditions":[{"condition":"minecraft:random_chance","chance":0.25}],
              "entries":[{"type":"minecraft:item","name":"examplemod:rare_drop"}]}]}
            """).getAsJsonObject();

        LootTableAcquisitionAnalyzer.ParsedTable parsed = LootTableAcquisitionAnalyzer
            .parseTable("examplemod:entities/rare_mob", conditional).orElseThrow();
        AcquisitionPath path = LootTableAcquisitionAnalyzer.fromParsedTables(List.of(parsed), 1.0D, 100.0D)
            .analyze("examplemod:rare_drop").getFirst();

        assertEquals("mob_drop", path.sourceType());
        assertTrue(path.feasibilityFactors().get("probability").value() == null);
        assertTrue(path.costsByHorizon().get(100).factors().get("quantity_cost").value() == null);
    }

    @Test
    void lootTableOverridesUseReloadResourceWinnerAndAppearInEngineAcquisitionPaths() {
        LootTableAcquisitionAnalyzer.ParsedTable table = new LootTableAcquisitionAnalyzer.ParsedTable(
            "examplemod:loot/fish", Map.of("examplemod:fish", 0.5D));
        LootTableAcquisitionAnalyzer analyzer = LootTableAcquisitionAnalyzer.fromParsedTables(
            List.of(table), 1.0D, 100.0D);
        DynamicFoodEngine engine = new DynamicFoodEngine();

        engine.replaceStaticRecipes(List.of());
        engine.rebuildLootTableAnalyzer(analyzer, 1.0D, 100.0D);

        assertEquals("examplemod:loot/fish", engine.acquisitionPaths("examplemod:fish").getFirst().sourceId());
    }

    @Test
    void lootAnalyzerClassifiesCanonicalSourceCategoriesAndKeepsTradesUnknown() {
        Map<String, Double> oneItem = Map.of("examplemod:food", 1.0D);
        LootTableAcquisitionAnalyzer analyzer = LootTableAcquisitionAnalyzer.fromParsedTables(List.of(
            new LootTableAcquisitionAnalyzer.ParsedTable("examplemod:entities/pig", oneItem),
            new LootTableAcquisitionAnalyzer.ParsedTable("examplemod:gameplay/fishing", oneItem),
            new LootTableAcquisitionAnalyzer.ParsedTable("examplemod:chests/ruined_portal", oneItem),
            new LootTableAcquisitionAnalyzer.ParsedTable("examplemod:blocks/wheat", "crop", oneItem)
        ), 1.0D, 100.0D);

        List<String> categories = analyzer.analyze("examplemod:food").stream()
            .map(AcquisitionPath::sourceType).sorted().toList();

        assertEquals(List.of("crop", "fishing", "mob_drop", "worldgen"), categories);
        assertTrue(analyzer.analyze("examplemod:food").stream()
            .allMatch(path -> path.costsByHorizon().get(100).factors().containsKey("quantity_cost")));
    }

    @Test
    void recipeEconomicCycleDoesNotPoisonIndependentAlternativePathCache() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:a_from_b", "minecraft:crafting", "examplemod:a", 1,
            List.of(IngredientContribution.of("examplemod:b", 0.0D, 0.0D, 1, true))));
        graph.add(new RecipeNode("test:a_from_seed", "minecraft:crafting", "examplemod:a", 1,
            List.of(IngredientContribution.of("examplemod:seed", 0.0D, 0.0D, 1, true))));
        graph.add(new RecipeNode("test:b_from_a", "minecraft:crafting", "examplemod:b", 1,
            List.of(IngredientContribution.of("examplemod:a", 0.0D, 0.0D, 1, true))));
        RecipeEconomicAnalyzer analyzer = new RecipeEconomicAnalyzer(graph,
            Map.of("examplemod:seed", 0.2D), 1.0D, 100.0D);

        RecipeEconomicResult resultA = analyzer.resolve("examplemod:a");
        RecipeEconomicResult resultB = analyzer.resolve("examplemod:b");

        assertEquals(ResolutionStatus.COMPLETE, resultA.status());
        assertEquals(ResolutionStatus.COMPLETE, resultB.status());
        assertTrue(resultB.economicCost() != null);
    }

    @Test
    void valueIncreasingEconomicCyclesAreReportedSeparatelyFromOrdinaryCycles() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:duplicate", "minecraft:crafting",
            "examplemod:a", 2, List.of(IngredientContribution.of("examplemod:b", 0.0D, 0.0D, 1, true))));
        graph.add(new RecipeNode("test:back", "minecraft:crafting",
            "examplemod:b", 1, List.of(IngredientContribution.of("examplemod:a", 0.0D, 0.0D, 1, true))));
        DynamicFoodEngine engine = new DynamicFoodEngine();
        engine.replaceStaticRecipes(List.of(
            new RecipeNode("test:duplicate", "minecraft:crafting",
                "examplemod:a", 2, List.of(IngredientContribution.of("examplemod:b", 0.0D, 0.0D, 1, true))),
            new RecipeNode("test:back", "minecraft:crafting",
                "examplemod:b", 1, List.of(IngredientContribution.of("examplemod:a", 0.0D, 0.0D, 1, true)))));

        assertTrue(engine.valueIncreasingEconomicCycles().stream()
            .anyMatch(cycle -> cycle.contains("output 2 > consumed 1")));
        assertTrue(graph.cycleFrom("examplemod:a").size() > 1);
    }

    @Test
    void disabledCalibrationUsesExplicitDisabledModeAndUnknownItemsStayUnresolved() {
        DynamicFoodEngine engine = new DynamicFoodEngine();
        FoodCalibrationSettings configuredFallback = new FoodCalibrationSettings(false, "configured_fallback", 16,
            0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D, "medium",
            List.of(), List.of(), 3.0D, 1.25D);
        engine.rebuildCalibration(List.of(), configuredFallback);

        CalibratedBaseFoodValue fallback = engine.calibratedBaseFoodValue("test:unknown").orElseThrow();
        assertEquals(3.0D, fallback.nutrition(), 0.0D);
        assertEquals(1.25D, fallback.effectiveSaturation(), 0.0D);
        assertEquals("configured_disabled_fallback", fallback.source());

        FoodCalibrationSettings vanilla = new FoodCalibrationSettings(false, "vanilla", 16,
            0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D, "medium",
            List.of(), List.of(), 3.0D, 1.25D);
        engine.rebuildCalibration(List.of(), vanilla);
        assertTrue(engine.calibratedBaseFoodValue("test:unknown").isEmpty());
        assertTrue(engine.calibrationSnapshot().isEmpty());
    }

    @Test
    void calibrationPopulationScopesSelectAutomaticAndConfiguredResourcesDeterministically() {
        FoodCalibrationSettings allResources = new FoodCalibrationSettings(true, "vanilla", 16,
            0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D, "medium",
            List.of(), List.of(), 2.0D, 0.0D, "all_survival_economic_resources", List.of());
        FoodCalibrationSettings configuredTag = new FoodCalibrationSettings(true, "vanilla", 16,
            0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D, "medium",
            List.of(), List.of(), 2.0D, 0.0D, "configured_tag", List.of("test:calibration_foods"));
        FoodCalibrationSettings manual = new FoodCalibrationSettings(true, "vanilla", 16,
            0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D, "medium",
            List.of(), List.of(), 2.0D, 0.0D, "manual", List.of());

        assertTrue(allResources.allowsAutomaticCandidate(false));
        assertTrue(allResources.allowsConfiguredProfile(false));
        assertTrue(configuredTag.allowsAutomaticCandidate(true));
        assertTrue(configuredTag.allowsConfiguredProfile(true));
        assertTrue(!configuredTag.allowsAutomaticCandidate(false));
        assertTrue(!configuredTag.allowsConfiguredProfile(false));
        assertTrue(!manual.allowsAutomaticDiscovery());
        assertTrue(!manual.allowsAutomaticCandidate(true));
        assertTrue(manual.allowsConfiguredProfile(false));
        assertTrue(!allResources.signatureContext().equals(configuredTag.signatureContext()));
    }

    @Test
    void economicProfileOverrideRequiresExplicitSurvivalEligibilityAndValidCost() {
        EconomicProfileOverride valid = EconomicProfileOverride.parse("examplemod:berry|0.42|2.0|fruit_group|TRUE");
        EconomicProfileOverride unknownSurvival = EconomicProfileOverride.parse("examplemod:unknown|0.42|1.0|-|UNKNOWN");

        assertEquals("fruit_group", valid.calibrationGroup());
        assertTrue(valid.toProfile().isCalibrationCandidate());
        assertTrue(!unknownSurvival.toProfile().isCalibrationCandidate());
        assertEquals(null, EconomicProfileOverride.parse("examplemod:bad|1.2|1.0|-|TRUE"));
        assertEquals(null, EconomicProfileOverride.parse("examplemod:bad|0.2|0.0|-|TRUE"));
    }

    @Test
    void fluidProfileUsesConfiguredMillibucketUnitAndIgnoresTechnicalFluids() {
        FluidFoodProfile milk = FluidFoodProfile.parse("examplemod:milk|4.0|2.5|1000|true|true");
        FluidFoodProfile water = FluidFoodProfile.parse("minecraft:water|10|10|1000|false|true");

        assertEquals(4.0D, milk.nutrition(), 0.0D);
        assertEquals(1000.0D, milk.valueUnitMb(), 0.0D);
        assertEquals(null, FluidFoodProfile.parse("examplemod:milk|1|2|0|true|true"));
        assertTrue(!water.foodComponent());
    }

    @Test
    void hybridCalibrationIsMonotonicAndUsesRobustLogMagnitude() {
        FoodValueCalibrator calibrator = new FoodValueCalibrator(1, 0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D);
        CalibrationSnapshot snapshot = calibrator.calibrate(List.of(
            calibrationProfile("test:cheap", "test:cheap", 0.01D, 1.0D),
            calibrationProfile("test:middle", "test:middle", 0.50D, 1.0D),
            calibrationProfile("test:costly", "test:costly", 0.75D, 1.0D),
            calibrationProfile("test:outlier", "test:outlier", 1.0D, 1.0D)
        ));

        double cheap = snapshot.foodIndexFor("test:cheap").orElseThrow();
        double middle = snapshot.foodIndexFor("test:middle").orElseThrow();
        double costly = snapshot.foodIndexFor("test:costly").orElseThrow();
        double outlier = snapshot.foodIndexFor("test:outlier").orElseThrow();
        assertTrue(cheap <= middle && middle <= costly && costly <= outlier);
        assertEquals(0.70D, snapshot.magnitudeWeight(), 0.0D);
        assertEquals(0.30D, snapshot.rankWeight(), 0.0D);
        assertEquals(Math.log1p(0.01D), snapshot.p05(), 0.0001D);
        assertEquals(Math.log1p(1.0D), snapshot.p95(), 0.0001D);
        assertEquals(1.0D, snapshot.calibratedValues().get("test:outlier").magnitudeComponent(), 0.0D);
    }

    @Test
    void weightedMidrankTiesAndCalibrationWeightsAreDeterministic() {
        FoodValueCalibrator calibrator = new FoodValueCalibrator(1, 0.0D, 1.0D, 0.05D, 0.95D, 0.50D, 0.80D);
        CalibrationSnapshot snapshot = calibrator.calibrate(List.of(
            calibrationProfile("test:a", "test:a", 0.25D, 1.0D),
            calibrationProfile("test:b", "test:b", 0.25D, 3.0D),
            calibrationProfile("test:c", "test:c", 0.75D, 1.0D)
        ));

        assertEquals(0.4D, snapshot.foodIndexFor("test:a").orElseThrow(), 0.0001D);
        assertEquals(snapshot.foodIndexFor("test:a").orElseThrow(), snapshot.foodIndexFor("test:b").orElseThrow(), 0.0D);
        assertEquals(0.9D, snapshot.foodIndexFor("test:c").orElseThrow(), 0.0001D);
    }

    @Test
    void weightedLogQuantilesAndCoverageExcludeUnknownAndIneligibleResources() {
        FoodValueCalibrator calibrator = new FoodValueCalibrator(1, 0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D);
        ResourceEconomicProfile technical = new ResourceEconomicProfile("test:machine", "test:machine", 0.1D,
            1.0D, 1.0D, true, true, SurvivalAcquirability.TRUE, true, false);
        ResourceEconomicProfile derived = new ResourceEconomicProfile("test:bread", "test:bread", 0.2D,
            1.0D, 1.0D, false, true, SurvivalAcquirability.TRUE, false, true);
        ResourceEconomicProfile survivalUnknown = new ResourceEconomicProfile("test:unknown_survival", "test:unknown_survival", 0.4D,
            1.0D, 1.0D, true, true, SurvivalAcquirability.UNKNOWN, false, false);
        CalibrationSnapshot snapshot = calibrator.calibrate(List.of(
            calibrationProfile("test:common", "test:common", 0.1D, 18.0D),
            calibrationProfile("test:rare", "test:rare", 0.9D, 2.0D),
            calibrationProfile("test:unresolved", "test:unresolved", null, 2.0D),
            technical, derived, survivalUnknown
        ));

        assertEquals(Math.log1p(0.1D), snapshot.p05(), 0.0001D);
        assertEquals(Math.log1p(0.9D), snapshot.p95(), 0.0001D);
        assertEquals(22.0D, snapshot.candidatePopulationWeight(), 0.0D);
        assertEquals(20.0D, snapshot.resolvedPopulationWeight(), 0.0D);
        assertEquals(20.0D / 22.0D, snapshot.calibrationCoverage(), 0.0001D);
        assertTrue(snapshot.calibratedValues().containsKey("test:common"));
        assertTrue(!snapshot.calibratedValues().containsKey("test:unresolved"));
        assertTrue(!snapshot.calibratedValues().containsKey("test:machine"));
        assertTrue(!snapshot.calibratedValues().containsKey("test:bread"));
        assertTrue(!snapshot.calibratedValues().containsKey("test:unknown_survival"));
    }

    @Test
    void explicitEconomicIdentityCreatesOneCalibrationPoint() {
        FoodValueCalibrator calibrator = new FoodValueCalibrator(1, 0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D);
        CalibrationSnapshot snapshot = calibrator.calibrate(List.of(
            calibrationProfile("test:berry_a", "test:berry_group", 0.4D, 1.0D),
            calibrationProfile("test:berry_b", "test:berry_group", 0.4D, 1.0D)
        ));

        assertEquals(1, snapshot.populationSize());
        assertEquals(1, snapshot.population().size());
        assertEquals(1.0D, snapshot.candidatePopulationWeight(), 0.0D);
        assertEquals(snapshot.foodIndexFor("test:berry_a").orElseThrow(),
            snapshot.foodIndexFor("test:berry_b").orElseThrow(), 0.0D);
        assertEquals(CalibrationStatus.DEGENERATE, snapshot.status());
    }

    @Test
    void sufficientlyLargeEqualCostPopulationIsDegenerate() {
        FoodValueCalibrator calibrator = new FoodValueCalibrator(2, 0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D);
        CalibrationSnapshot snapshot = calibrator.calibrate(List.of(
            calibrationProfile("test:a", "test:a", 0.4D, 1.0D),
            calibrationProfile("test:b", "test:b", 0.4D, 1.0D)
        ));

        assertEquals(CalibrationStatus.DEGENERATE, snapshot.status());
        assertEquals(0.5D, snapshot.foodIndexFor("test:a").orElseThrow(), 0.0D);
        assertEquals(0.5D, snapshot.foodIndexFor("test:b").orElseThrow(), 0.0D);
    }

    @Test
    void calibrationIsStableAcrossInputOrderAndUsesRankOnlyForSmallSamples() {
        FoodValueCalibrator calibrator = new FoodValueCalibrator(16, 0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D);
        ResourceEconomicProfile low = calibrationProfile("test:low", "test:low", 0.1D, 1.0D);
        ResourceEconomicProfile high = calibrationProfile("test:high", "test:high", 0.9D, 1.0D);
        CalibrationSnapshot first = calibrator.calibrate(List.of(low, high));
        CalibrationSnapshot second = calibrator.calibrate(List.of(high, low));

        assertEquals(CalibrationStatus.LOW_SAMPLE, first.status());
        assertEquals(0.25D, first.foodIndexFor("test:low").orElseThrow(), 0.0001D);
        assertEquals(0.75D, first.foodIndexFor("test:high").orElseThrow(), 0.0001D);
        assertEquals(first.signature(), second.signature());
        assertEquals(first.calibratedValues(), second.calibratedValues());
    }

    @Test
    void foodValueCurvesInterpolateMonotonicallyAndPresetsAreOrdered() {
        FoodValueCurve custom = new FoodValueCurve(List.of(
            new CalibrationAnchor(0.0D, 1.0D),
            new CalibrationAnchor(0.5D, 3.0D),
            new CalibrationAnchor(1.0D, 9.0D)
        ));
        assertEquals(2.0D, custom.evaluate(0.25D), 0.0001D);
        assertEquals(3.0D, custom.evaluate(0.5D), 0.0001D);
        assertEquals(9.0D, custom.evaluate(2.0D), 0.0001D);
        assertThrows(IllegalArgumentException.class, () -> new FoodValueCurve(List.of(
            new CalibrationAnchor(0.0D, 2.0D), new CalibrationAnchor(0.5D, 1.0D))));

        for (double index : List.of(0.0D, 0.25D, 0.5D, 0.75D, 1.0D)) {
            assertTrue(FoodValueCurve.preset("easy", false).evaluate(index)
                >= FoodValueCurve.preset("medium", false).evaluate(index));
            assertTrue(FoodValueCurve.preset("medium", false).evaluate(index)
                >= FoodValueCurve.preset("hard", false).evaluate(index));
            assertTrue(FoodValueCurve.preset("hard", false).evaluate(index)
                >= FoodValueCurve.preset("very_hard", false).evaluate(index));
        }
    }

    @Test
    void multiOutputAllocationCountsFoodUnitsAndExcludesTechnicalOutputs() {
        List<OutputValueAllocator.OutputTarget<String>> targets = OutputValueAllocator.foodTargets(
            List.of("pie_slice_a", "bowl", "pie_slice_b"),
            output -> !output.equals("bowl"),
            output -> output.equals("pie_slice_a") ? 2 : 1
        );

        assertEquals(2, targets.size());
        assertEquals(3, OutputValueAllocator.totalFoodOutputCount(targets));
    }

    @Test
    void outputWeightsRedistributeOneOperationTotalWithoutCreatingValue() {
        FoodValue operation = new FoodValue(5.0D, 5, 3.0D, 3.0D, 1.0D,
            "test:operation", 5, List.of());
        List<OutputValueAllocator.OutputTarget<String>> weighted = List.of(
            new OutputValueAllocator.OutputTarget<>("slice", 4, 1.0D),
            new OutputValueAllocator.OutputTarget<>("side", 1, 3.0D)
        );
        List<OutputValueAllocator.AllocatedOutput<String>> allocated = OutputValueAllocator.allocate(operation, weighted);
        double nutritionTotal = allocated.stream().mapToDouble(value ->
            value.nutritionPerUnit() * value.target().quantity()).sum();
        double saturationTotal = allocated.stream().mapToDouble(value ->
            value.saturationPerUnit() * value.target().quantity()).sum();

        assertEquals(25.0D, nutritionTotal, 0.0001D);
        assertEquals(15.0D, saturationTotal, 0.0001D);
        assertEquals(1.5625D, allocated.getFirst().nutritionPerUnit(), 0.0001D);
        assertEquals(18.75D, allocated.getLast().nutritionPerUnit(), 0.0001D);
    }

    @Test
    void saturationModifierConvertsToEffectivePointsAtBuilderBoundary() {
        assertEquals(9.0D, SaturationConverter.modifierToEffective(6, 0.75D), 0.0001D);
    }

    @Test
    void effectiveSaturationConvertsBackToBuilderModifier() {
        double modifier = SaturationConverter.effectiveToModifier(6, 9.0D);

        assertEquals(0.75D, modifier, 0.0001D);
        assertEquals(9.0D, SaturationConverter.modifierToEffective(6, modifier), 0.0001D);
    }

    @Test
    void builderSaturationModifierRoundTripsEffectivePoints() {
        double originalModifier = 0.375D;
        double effective = SaturationConverter.modifierToEffective(8, originalModifier);
        double modifier = SaturationConverter.effectiveToModifier(8, effective);

        assertEquals(originalModifier, modifier, 0.0001D);
        assertEquals(effective, SaturationConverter.modifierToEffective(8, modifier), 0.0001D);
    }

    @Test
    void builtFoodPropertiesSaturationIsAlreadyEffectiveAndStaysSo() {
        assertEquals(9.0D, SaturationConverter.foodPropertiesToEffective(6, 9.0D), 0.0D);
        assertEquals(9.0D, SaturationConverter.effectiveToFoodProperties(6, 9.0D), 0.0D);
        assertEquals(0.0D, SaturationConverter.effectiveToFoodProperties(0, 9.0D), 0.0D);
    }

    @Test
    void unversionedPersistedValueMigratesLegacySaturationModifier() {
        var legacy = JsonParser.parseString("""
            {"raw_nutrition":6.0,"nutrition":6,"raw_saturation":0.75,
             "saturation":0.75,"difficulty":1.0,"source_recipe":"legacy",
             "output_count":1,"components":[]}
            """);

        DynamicFoodValue migrated = DynamicFoodValue.CODEC.parse(JsonOps.INSTANCE, legacy)
            .result().orElseThrow();

        assertEquals(DynamicFoodValue.CURRENT_FORMAT_VERSION, migrated.formatVersion());
        assertEquals(9.0D, migrated.rawSaturation(), 0.0001D);
        assertEquals(9.0F, migrated.saturation(), 0.0001F);
    }

    @Test
    void recipeTreeIsDeterministicAndCycleSafe() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:bread", "minecraft:crafting", "test:bread", 1,
            List.of(IngredientContribution.of("test:dough", 0.0D, 0.0D, 1, true))));
        graph.add(new RecipeNode("test:dough", "minecraft:crafting", "test:dough", 1,
            List.of(IngredientContribution.of("test:wheat", 2.0D, 0.4D, 1, true))));
        graph.add(new RecipeNode("test:wheat_cycle", "minecraft:crafting", "test:wheat", 1,
            List.of(IngredientContribution.of("test:bread", 0.0D, 0.0D, 1, true))));

        List<String> tree = graph.describeTree("test:bread");

        assertTrue(tree.getFirst().contains("test:bread <- test:bread"));
        assertTrue(tree.stream().anyMatch(line -> line.contains("test:bread [cycle]")));
    }

    @Test
    void zeroNutritionAlwaysProducesZeroSaturationModifier() {
        assertEquals(0.0D, SaturationConverter.effectiveToModifier(0, 12.0D), 0.0D);
        assertEquals(0.0D, SaturationConverter.modifierToEffective(0, 5.0D), 0.0D);
    }

    @Test
    void fractionalAndHighEffectiveSaturationRemainRepresentable() {
        double effective = 37.25D;
        double modifier = SaturationConverter.effectiveToModifier(5, effective);

        assertEquals(3.725D, modifier, 0.0001D);
        assertEquals(effective, SaturationConverter.modifierToEffective(5, modifier), 0.0001D);
    }

    @Test
    void outputCountDistributesIngredientValue() {
        FoodValue value = FoodValueResolver.compute(
            List.of(IngredientContribution.of("minecraft:wheat", 2.0D, 0.4D, 3, true)),
            3, 0.0D, 0.0D, "test:three_dough"
        );

        assertEquals(2, value.nutrition());
        assertEquals(0.4D, value.saturation(), 0.0001D);
    }

    @Test
    void finalNutritionRoundsUpWithoutLosingComponentFloor() {
        FoodValue value = FoodValueResolver.compute(
            List.of(IngredientContribution.of("minecraft:apple", 2.25D, 0.5D, 1, true)),
            1, 0.0D, 0.0D, "test:rounding"
        );

        assertEquals(3, value.nutrition());
        assertTrue(value.rawNutrition() >= 2.25D);
    }

    @Test
    void graphChoosesDeterministicRecipeAndDetectsCycles() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:z_recipe", "minecraft:crafting_shapeless", "test:dough", 1, List.of()));
        graph.add(new RecipeNode("test:a_recipe", "minecraft:crafting_shaped", "test:dough", 1, List.of()));
        graph.add(new RecipeNode("test:cycle", "minecraft:crafting_shapeless", "test:flour", 1,
            List.of(IngredientContribution.of("test:dough", 1.0D, 0.1D, 1, true))));
        graph.add(new RecipeNode("test:back", "minecraft:crafting_shapeless", "test:dough", 1,
            List.of(IngredientContribution.of("test:flour", 1.0D, 0.1D, 1, true))));

        assertEquals("test:a_recipe", graph.deterministicRecipeFor("test:dough").orElseThrow().recipeId());
        assertTrue(graph.hasAmbiguousRecipes("test:dough"));
        assertTrue(graph.cycleFrom("test:dough").size() > 1);
    }

    @Test
    void knownRecipeIdOverridesLexicographicStaticFallback() {
        DynamicFoodEngine engine = new DynamicFoodEngine();
        engine.replaceStaticRecipes(List.of(
            new RecipeNode("test:a_recipe", "minecraft:crafting", "test:meal", 1,
                List.of(IngredientContribution.of("test:low", 1.0D, 0.1D, 1, true))),
            new RecipeNode("test:z_recipe", "minecraft:crafting", "test:meal", 1,
                List.of(IngredientContribution.of("test:high", 5.0D, 1.0D, 1, true)))
        ));

        FoodValue selected = engine.resolve(new RuntimeProvenance(
            "test:meal", "test:z_recipe", "minecraft:crafting", 1, List.of()
        ));

        assertTrue(selected.rawNutrition() >= 5.0D);
        assertEquals("test:z_recipe", selected.sourceRecipe());
        assertEquals("test:high", selected.components().getFirst().itemId());
    }

    @Test
    void sharedDependencyDoesNotLookLikeCycle() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:root", "minecraft:crafting_shapeless", "test:root_item", 1, List.of(
            IngredientContribution.of("test:left", 1.0D, 0.1D, 1, true),
            IngredientContribution.of("test:right", 1.0D, 0.1D, 1, true)
        )));
        graph.add(new RecipeNode("test:left_recipe", "minecraft:crafting_shapeless", "test:left", 1,
            List.of(IngredientContribution.of("test:shared", 1.0D, 0.1D, 1, true))));
        graph.add(new RecipeNode("test:right_recipe", "minecraft:crafting_shapeless", "test:right", 1,
            List.of(IngredientContribution.of("test:shared", 1.0D, 0.1D, 1, true))));

        assertTrue(graph.cycleFrom("test:root_item").isEmpty());
    }
    @Test
    void runtimeInputsOverrideStaticRecipeFallback() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:static", "minecraft:crafting_shapeless", "test:meal", 1,
            List.of(IngredientContribution.of("minecraft:apple", 1.0D, 0.1D, 1, true))));
        RecipeResolver resolver = new RecipeResolver(graph, new RecipeValueCache(), 0.0D, 0.0D);
        RuntimeProvenance runtime = new RuntimeProvenance("test:meal", "test:actual_recipe",
            "create:mixing", 1,
            List.of(IngredientContribution.of("minecraft:beef", 5.0D, 2.0D, 1, true)));

        FoodValue result = resolver.resolve(runtime);

        assertEquals(5, result.nutrition());
        assertEquals(2.0D, result.saturation(), 0.0001D);
        assertEquals("test:actual_recipe", result.sourceRecipe());
    }

    @Test
    void staticRecursiveFallbackPreservesBaseIngredientProfiles() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:dough_recipe", "minecraft:crafting", "test:dough", 3,
            List.of(IngredientContribution.of("minecraft:wheat", 2.0D, 0.4D, 3, true))));
        graph.add(new RecipeNode("test:bread_recipe", "minecraft:smelting", "test:bread", 1,
            List.of(IngredientContribution.of("test:dough", 0.0D, 0.0D, 1, true))));
        RecipeResolver resolver = new RecipeResolver(graph, new RecipeValueCache(), 0.0D, 0.0D);

        FoodValue bread = resolver.resolveStatic("test:bread");

        assertEquals(2.0D, bread.rawNutrition(), 0.0001D);
        assertEquals(0.4D, bread.rawSaturation(), 0.0001D);
    }

    @Test
    void recursiveRecipeDifficultyIsStoredOnProducedIntermediateValue() {
        RecipeGraph graph = new RecipeGraph();
        graph.add(new RecipeNode("test:dough_recipe", "minecraft:crafting", "test:dough", 1,
            List.of(new IngredientContribution("test:rare_ingredient", 2.0D, 0.4D, 1, true, "profile", 4.0D))));
        RecipeResolver resolver = new RecipeResolver(graph, new RecipeValueCache(), 0.0D, 0.0D);

        FoodValue dough = resolver.resolveStatic("test:dough");

        assertTrue(dough.difficulty() > 2.0D);
        assertTrue(dough.difficulty() <= 5.0D);
    }

    @Test
    void nonFoodContainerDoesNotContribute() {
        FoodValue value = FoodValueResolver.compute(
            List.of(IngredientContribution.of("minecraft:bowl", 0.0D, 0.0D, 1, false)),
            1, 0.0D, 0.0D, "test:stew"
        );

        assertEquals(0, value.nutrition());
        assertEquals(0.0D, value.saturation());
    }

    @Test
    void explicitlyDisabledFoodComponentDoesNotContributePositiveValues() {
        FoodValue value = FoodValueResolver.compute(
            List.of(IngredientContribution.of("test:technical", 8.0D, 4.0D, 1, false)),
            1, 0.0D, 0.0D, "test:technical_recipe"
        );

        assertEquals(0, value.nutrition());
        assertEquals(0.0D, value.saturation());
    }

    @Test
    void nonFoodIngredientDoesNotAffectRecipeDifficulty() {
        double difficulty = RecipeDifficultyResolver.estimate(List.of(
            IngredientContribution.of("test:technical_component", 12.0D, 8.0D, 1, false)
        ));

        assertEquals(0.0D, difficulty);
    }

    @Test
    void preserveModeNeverReducesComponentSaturationAboveLimit() {
        FoodValue value = FoodValueResolver.compute(
            List.of(IngredientContribution.of("test:rich_food", 4.0D, 24.0D, 1, true)),
            1, 0.0D, 1.0D, 20.0D, SaturationOverflowMode.PRESERVE_COMPONENTS, "test:preserve"
        );

        assertEquals(24.0D, value.saturation(), 0.0001D);
    }

    @Test
    void inheritedNutritionAboveAutomaticLimitIsPreserved() {
        FoodValue value = FoodValueResolver.compute(
            List.of(IngredientContribution.of("test:rich_food", 20.0D, 1.0D, 1, true)),
            1, 3.0D, 0.0D, 18.0D, 20.0D, SaturationOverflowMode.PRESERVE_COMPONENTS, "test:rich_nutrition"
        );

        assertEquals(20.0D, value.rawNutrition(), 0.0001D);
    }

    @Test
    void automaticNutritionBonusUsesOnlyRemainingLimit() {
        FoodValue value = FoodValueResolver.compute(
            List.of(IngredientContribution.of("test:food", 17.0D, 1.0D, 1, true)),
            1, 3.0D, 0.0D, 18.0D, 20.0D, SaturationOverflowMode.PRESERVE_COMPONENTS, "test:nutrition_headroom"
        );

        assertEquals(18.0D, value.rawNutrition(), 0.0001D);
    }

    @Test
    void normalizeModeCapsInheritedSaturationDeterministically() {
        FoodValue value = FoodValueResolver.compute(
            List.of(IngredientContribution.of("test:rich_food", 4.0D, 24.0D, 1, true)),
            1, 0.0D, 1.0D, 20.0D, SaturationOverflowMode.NORMALIZE_COMPONENTS, "test:normalize"
        );

        assertEquals(20.0D, value.saturation(), 0.0001D);
    }

    @Test
    void automaticSaturationBonusUsesOnlyRemainingLimit() {
        FoodValue value = FoodValueResolver.compute(
            List.of(IngredientContribution.of("test:food", 2.0D, 18.0D, 1, true)),
            1, 0.0D, 4.0D, 20.0D, SaturationOverflowMode.PRESERVE_COMPONENTS, "test:bonus_headroom"
        );

        assertEquals(20.0D, value.saturation(), 0.0001D);
    }

    @Test
    void configuredProfilesParseCustomItemsAndRejectInvalidDifficulty() {
        ItemFoodProfile profile = ItemFoodProfile.parse("examplemod:berries|1.5|0.8|4|true|true");
        ItemFoodProfile automatic = ItemFoodProfile.parse("examplemod:rare_food|-1|-1|4|true|true");

        assertEquals("examplemod:berries", profile.itemId());
        assertEquals(1.5D, profile.nutrition(), 0.0001D);
        assertEquals(4, profile.difficulty());
        assertEquals(4, automatic.difficulty());
        assertEquals(2.0D, automatic.nutritionOverrideOr(2.0D), 0.0001D);
        assertEquals(0.6D, automatic.saturationOverrideOr(0.6D), 0.0001D);
        assertEquals(null, ItemFoodProfile.parse("examplemod:invalid|-2|1|4|true|true"));
        assertEquals(null, ItemFoodProfile.parse("examplemod:bad|1|1|8|true|true"));
        assertEquals(null, ItemFoodProfile.parse("examplemod:nan|NaN|1|1|true|true"));
        assertEquals(null, ItemFoodProfile.parse("examplemod:infinity|1|Infinity|1|true|true"));
        assertEquals(null, ItemFoodProfile.parse("examplemod:fractional_auto|-0.5|1|1|true|true"));
        assertEquals(null, ItemFoodProfile.parse("examplemod:invalid_boolean|1|1|1|yes|true"));
        assertEquals(null, ItemFoodProfile.parse("Invalid:uppercase|1|1|1|true|true"));
        assertEquals(null, ItemFoodProfile.parse(null));
    }

    @Test
    void configuredFluidProfilesRejectInvalidNumbersBooleansAndIds() {
        assertEquals(null, FluidFoodProfile.parse("examplemod:milk|NaN|1|1000|true|true"));
        assertEquals(null, FluidFoodProfile.parse("examplemod:milk|1|1|Infinity|true|true"));
        assertEquals(null, FluidFoodProfile.parse("examplemod:milk|1|1|1000|yes|true"));
        assertEquals(null, FluidFoodProfile.parse("Invalid:milk|1|1|1000|true|true"));
    }

    @Test
    void itemDifficultyDoesNotReplaceCalibratedAutomaticProfileValues() {
        ItemFoodProfile automatic = ItemFoodProfile.parse("examplemod:rare_food|-1|-1|1|true|true");

        assertEquals(3.5D, automatic.nutritionOverrideOr(3.5D), 0.0001D);
        assertEquals(1.25D, automatic.saturationOverrideOr(1.25D), 0.0001D);
    }

    @Test
    void localFoodOverridesWinWhileUnspecifiedProfileValuesCanUseCalibration() {
        ItemFoodProfile mixed = ItemFoodProfile.parse("examplemod:food|4.0|-1|3|true|true");

        assertEquals(4.0D, mixed.nutritionOverrideOr(8.0D), 0.0D);
        assertEquals(2.5D, mixed.saturationOverrideOr(2.5D), 0.0D);
    }

    @Test
    void unresolvedAcquisitionDoesNotGuessFromItemNamesOrRarity() {
        DynamicFoodEngine engine = new DynamicFoodEngine();

        ResourceDifficulty netherFish = engine.resourceDifficulty("examplemod:nether_fish");
        ResourceDifficulty commonFish = engine.resourceDifficulty("examplemod:fish");

        assertEquals(null, netherFish.score());
        assertEquals(0.0D, netherFish.confidence());
        assertEquals(netherFish.score(), commonFish.score());
        assertTrue(netherFish.reasons().contains("unknown acquisition source"));
    }

    @Test
    void manualDifficultyOverrideWinsWithoutInventingAcquisitionEvidence() {
        ResourceDifficulty result = new DynamicFoodEngine().resourceDifficulty("examplemod:unknown_food",
            OptionalDouble.of(4.2D));

        assertEquals(4.2D, result.score());
        assertEquals(0.0D, result.confidence());
        assertTrue(result.manualOverride());
        assertTrue(result.reasons().contains("unknown acquisition source"));
    }

    @Test
    void installedPackProcessingRecipeTypesHaveStationMetadata() {
        RecipeAdapterRegistry adapters = new RecipeAdapterRegistry();

        assertEquals(3, adapters.find("create:mixing").orElseThrow().stationDifficulty());
        assertEquals(3, adapters.find("create:item_application").orElseThrow().stationDifficulty());
        assertEquals(2, adapters.find("create:milling").orElseThrow().stationDifficulty());
        assertEquals(2, adapters.find("ratatouille_fried_delights:frying").orElseThrow().stationDifficulty());
        assertEquals(2, adapters.find("immersiveengineering:cloche").orElseThrow().stationDifficulty());
        assertEquals(0, adapters.find("aquaculture:crafting_special_fish_fillet").orElseThrow().stationDifficulty());
    }

    @Test
    void recursiveRecipeDifficultyAddsOnlyItsConfiguredProcessingShare() {
        DynamicFoodEngine engine = new DynamicFoodEngine();
        FoodValue easy = engine.resolve(new RuntimeProvenance("test:meal", "test:easy", "minecraft:crafting", 1,
            List.of(new IngredientContribution("test:ingredient", 2.0D, 0.4D, 1, true, "profile", 0.5D))));
        FoodValue difficult = engine.resolve(new RuntimeProvenance("test:meal", "test:difficult", "minecraft:crafting", 1,
            List.of(new IngredientContribution("test:ingredient", 2.0D, 0.4D, 1, true, "profile", 5.0D))));

        assertTrue(difficult.difficulty() > easy.difficulty());
        assertTrue(difficult.rawNutrition() > easy.rawNutrition());
        assertTrue(difficult.rawNutrition() < 5.0D);
    }

    @Test
    void moreDifficultStationGetsLargerShareButStaysWithinSharedBudget() {
        DynamicFoodEngine engine = new DynamicFoodEngine();
        List<IngredientContribution> inputs = List.of(
            IngredientContribution.of("minecraft:wheat", 2.0D, 0.4D, 3, true)
        );
        FoodValue manual = engine.resolve(new RuntimeProvenance(
            "test:dough", "test:craft", "minecraft:crafting", 3, inputs));
        FoodValue mixing = engine.resolve(new RuntimeProvenance(
            "test:dough", "test:mix", "create:mixing", 3, inputs));

        assertTrue(mixing.rawNutrition() > manual.rawNutrition());
        assertTrue(mixing.rawNutrition() < 3.0D);
        assertTrue(mixing.rawSaturation() < 1.0D);
    }

    @Test
    void actualOperationStationDifficultyFeedsTheSharedBonusBudget() {
        RecipeGraph graph = new RecipeGraph();
        RecipeResolver resolver = new RecipeResolver(graph, new RecipeValueCache(), new RecipeAdapterRegistry());
        IngredientContribution ingredient = IngredientContribution.of("test:food", 1.0D, 0.0D, 1, true);
        FoodValue simple = resolver.resolve(new RuntimeProvenance("test:meal", "test:simple", "test:custom", 1,
            List.of(ingredient), 0));
        FoodValue difficult = resolver.resolve(new RuntimeProvenance("test:meal", "test:machine", "test:custom", 1,
            List.of(ingredient), 5));

        assertTrue(difficult.rawNutrition() > simple.rawNutrition());
        assertTrue(difficult.rawNutrition() <= 4.0D);
    }

    @Test
    void dynamicFoodSnapshotKeepsTheProducingRecipe() {
        FoodValue first = FoodValueResolver.compute(
            List.of(IngredientContribution.of("minecraft:wheat", 2.0D, 0.4D, 1, true)),
            1, 0.0D, 0.0D, "test:first_recipe"
        );
        FoodValue second = FoodValueResolver.compute(
            List.of(IngredientContribution.of("minecraft:beef", 5.0D, 2.0D, 1, true)),
            1, 0.0D, 0.0D, "test:second_recipe"
        );

        DynamicFoodValue firstSnapshot = DynamicFoodValue.snapshot(first);
        DynamicFoodValue secondSnapshot = DynamicFoodValue.snapshot(second);

        assertEquals("test:first_recipe", firstSnapshot.sourceRecipe());
        assertTrue(!firstSnapshot.equals(secondSnapshot));
    }

    @Test
    void dynamicFoodSnapshotRetainsComponentDifficultyForRecursiveUse() {
        FoodValue computed = FoodValueResolver.compute(
            List.of(new IngredientContribution("test:ingredient", 2.0D, 0.4D, 1, true, "test:source", 4.0D)),
            1, 0.0D, 0.0D, "test:recipe"
        );
        DynamicFoodValue snapshot = DynamicFoodValue.snapshot(computed);
        DynamicFoodEngine engine = new DynamicFoodEngine();

        FoodValue restored = engine.resolveOrSnapshot(
            new RuntimeProvenance("test:result", "test:recipe", "minecraft:crafting", 1, List.of()), snapshot
        );

        assertEquals(4.0D, restored.components().getFirst().difficulty(), 0.0001D);
    }

    @Test
    void runtimeIngredientContributionRetainsUnroundedNutritionSnapshot() {
        DynamicFoodValue snapshot = new DynamicFoodValue(
            2.25D, 3, 0.753D, 0.75F, 2.0D, "test:dough", 1, List.of());

        IngredientContribution contribution = snapshot.asIngredientContribution("test:dough", 1);

        assertEquals(2.25D, contribution.nutrition(), 0.0001D);
        assertEquals(0.753D, contribution.saturation(), 0.0001D);
        assertEquals("test:dough", contribution.sourceRecipe());
    }

    @Test
    void recipeReloadChangesFutureFallbackButKeepsExistingSnapshot() {
        DynamicFoodEngine engine = new DynamicFoodEngine();
        engine.replaceStaticRecipes(List.of(new RecipeNode("test:first", "minecraft:crafting", "test:meal", 1,
            List.of(IngredientContribution.of("minecraft:apple", 2.0D, 0.4D, 1, true)))));
        RuntimeProvenance noRuntimeInputs = new RuntimeProvenance("test:meal", "fallback", "minecraft:crafting", 1, List.of());
        DynamicFoodValue existing = DynamicFoodValue.snapshot(engine.resolve(noRuntimeInputs));

        engine.replaceStaticRecipes(List.of(new RecipeNode("test:second", "minecraft:crafting", "test:meal", 1,
            List.of(IngredientContribution.of("minecraft:beef", 5.0D, 2.0D, 1, true)))));

        FoodValue future = engine.resolve(noRuntimeInputs);
        FoodValue heldSnapshot = engine.resolveOrSnapshot(noRuntimeInputs, existing);

        assertTrue(future.rawNutrition() > heldSnapshot.rawNutrition());
        assertEquals(existing.nutrition(), heldSnapshot.nutrition());
        assertEquals("test:first", existing.sourceRecipe());
    }

    @Test
    void dynamicValueCodecRoundTripsRawValuesAndComponentTree() {
        FoodValue computed = FoodValueResolver.compute(
            List.of(IngredientContribution.of("minecraft:wheat", 2.25D, 0.4D, 2, true)),
            1, 0.0D, 0.0D, "test:codec"
        );
        DynamicFoodValue snapshot = DynamicFoodValue.snapshot(computed);
        var encoded = DynamicFoodValue.CODEC.encodeStart(JsonOps.INSTANCE, snapshot).result().orElseThrow();
        DynamicFoodValue decoded = DynamicFoodValue.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow();

        assertEquals(snapshot, decoded);
    }

    @Test
    void dynamicValueNetworkStreamCodecRoundTripsSnapshotAndProvenance() {
        DynamicFoodValue original = new DynamicFoodValue(2.25D, 3, 0.753D, 0.75F,
            2.0D, "test:network_recipe", 2,
            List.of(new IngredientFoodSnapshot("test:input", 2.25D, 0.753D, 1,
                true, "test:raw", 2.0D)));
        ByteBuf buffer = Unpooled.buffer();
        try {
            DynamicFoodValue.STREAM_CODEC.encode(buffer, original);
            DynamicFoodValue decoded = DynamicFoodValue.STREAM_CODEC.decode(buffer);

            assertEquals(original, decoded);
        } finally {
            buffer.release();
        }
    }

    @Test
    void operationBreakdownPersistsAndSynchronizesWithTheFoodSnapshot() {
        FoodValue computed = FoodValueResolver.compute(
            List.of(IngredientContribution.of("minecraft:wheat", 2.5D, 0.75D, 2, true)),
            2, 3.0D, 0.4D, "test:processing"
        );
        OperationFoodSnapshot breakdown = new OperationFoodSnapshot("test:mixing", "test:mill", 3,
            "minecraft:wheat x2", "minecraft:water 250mB", "duration_ticks=40.0", 1.0D,
            5.0D, 1.5D, 1.2D, 0.6D);
        DynamicFoodValue original = DynamicFoodValue.snapshot(computed, breakdown);
        var encoded = DynamicFoodValue.CODEC.encodeStart(JsonOps.INSTANCE, original).result().orElseThrow();

        assertEquals(original, DynamicFoodValue.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow());
        ByteBuf buffer = Unpooled.buffer();
        try {
            DynamicFoodValue.STREAM_CODEC.encode(buffer, original);
            assertEquals(original, DynamicFoodValue.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void ingredientSnapshotNetworkCodecPreservesDifficulty() {
        IngredientFoodSnapshot original = new IngredientFoodSnapshot(
            "test:ingredient", 2.0D, 0.4D, 1, true, "test:source", 4.0D
        );
        ByteBuf buffer = Unpooled.buffer();
        try {
            IngredientFoodSnapshot.STREAM_CODEC.encode(buffer, original);
            IngredientFoodSnapshot decoded = IngredientFoodSnapshot.STREAM_CODEC.decode(buffer);
            assertEquals(original, decoded);
        } finally {
            buffer.release();
        }
    }

    private static ResourceEconomicProfile calibrationProfile(String resourceId, String identity,
        Double economicCost, double weight) {
        return new ResourceEconomicProfile(resourceId, identity, economicCost, 1.0D, weight,
            true, true, SurvivalAcquirability.TRUE, false, false);
    }

    private static AcquisitionPath acquisitionPath(String itemId, String sourceId, boolean repeatable,
        double confidence, Map<String, EconomicFactor> feasibilityFactors, Map<String, EconomicFactor> costFactors,
        int horizon) {
        return new AcquisitionPath(itemId, "test", sourceId, confidence, repeatable ? 1.0D : 0.0D,
            0.0D, repeatable, false, feasibilityFactors,
            Map.of(horizon, new CostVector(horizon, costFactors)));
    }
}
