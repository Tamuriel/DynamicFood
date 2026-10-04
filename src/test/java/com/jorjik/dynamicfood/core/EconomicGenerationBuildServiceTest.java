package com.jorjik.dynamicfood.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jorjik.dynamicfood.config.EconomicProfileOverride;
import com.jorjik.dynamicfood.graph.RecipeNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EconomicGenerationBuildServiceTest {
    @Test
    void buildsAndPublishesOneCoherentProductionGeneration() {
        DynamicFoodEngine engine = readyEngine();
        EconomicGenerationPublisher publisher = new EconomicGenerationPublisher();
        EconomicGenerationBuildService builder = new EconomicGenerationBuildService();

        PublishedEconomicGeneration published = builder.rebuildAndPublish(engine, publisher,
            List.of(configuredWheat()), settings(), List.of("minecraft:wheat"));

        assertSame(published, publisher.current().orElseThrow());
        assertEquals(1L, published.generation());
        assertEquals(1L, published.economicSnapshot().generation());
        assertEquals(published.economicSnapshot().signature(),
            published.calibrationSnapshot().economicContentSignature());
        assertEquals(published.generation(), published.calibrationSnapshot().economicGeneration());
        assertEquals(1.0D, published.economicSnapshot().resource("minecraft:wheat").orElseThrow()
            .economicCost().value(), 0.0D);
        assertTrue(published.calibrationSnapshot().calibratedValues().containsKey("minecraft:wheat"));
        EconomicSnapshotAudit audit = EconomicSnapshotAudit.inspect(published);
        assertEquals(1, audit.totalResources());
        assertEquals(1, audit.resolvedBySource().get("explicit_override"));
        assertEquals(1, audit.calibrationCandidates());
        assertEquals(1, audit.calibratedResources());
        assertEquals(1.0D, audit.calibrationCoverage(), 0.0D);
        assertEquals(0, audit.notApplicableResources(),
            "NOT_APPLICABLE is not a status in the current economic resolution contract");
    }

    @Test
    void disabledCalibrationStillPublishesAnExplicitEmptyLinkedSnapshot() {
        DynamicFoodEngine engine = readyEngine();
        EconomicGenerationPublisher publisher = new EconomicGenerationPublisher();
        FoodCalibrationSettings disabled = new FoodCalibrationSettings(false, "vanilla", 1,
            0.70D, 0.30D, 0.05D, 0.95D, 0.50D, 0.80D, "medium",
            List.of(), List.of(), 2.0D, 0.0D);

        PublishedEconomicGeneration published = new EconomicGenerationBuildService().rebuildAndPublish(
            engine, publisher, List.of(configuredWheat()), disabled, List.of("minecraft:wheat"));

        assertEquals(CalibrationStatus.DISABLED, published.calibrationSnapshot().status());
        assertTrue(published.calibrationSnapshot().calibratedValues().isEmpty());
        assertEquals(published.economicSnapshot().signature(),
            published.calibrationSnapshot().economicContentSignature());
        assertSame(published, publisher.current().orElseThrow());
    }

    @Test
    void unresolvedConfiguredResourceRemainsUnknownInPublishedSnapshot() {
        DynamicFoodEngine engine = readyEngine();
        EconomicGenerationPublisher publisher = new EconomicGenerationPublisher();
        ResourceEconomicProfile unresolved = new ResourceEconomicProfile("minecraft:bread",
            "minecraft:bread", null, 1.0D, 1.0D, true, true,
            SurvivalAcquirability.UNKNOWN, false, false);

        PublishedEconomicGeneration published = new EconomicGenerationBuildService().rebuildAndPublish(
            engine, publisher, List.of(unresolved), settings(), List.of("minecraft:bread"));
        EconomicSnapshot.ResourceResult resource = published.economicSnapshot()
            .resource("minecraft:bread").orElseThrow();

        assertEquals(ResolutionStatus.UNKNOWN, resource.economicResolution().status());
        assertTrue(!resource.economicCost().isKnown());
        assertTrue(published.calibrationSnapshot().calibratedValues().isEmpty());
    }

    @Test
    void explicitEconomicCostOverrideSeedsRecursiveRecipeInputsWithoutBecomingAnAcquisitionPath() {
        DynamicFoodEngine engine = readyEngine();
        engine.replaceStaticRecipes(List.of(
            new RecipeNode("test:wheat_from_seed", "minecraft:crafting",
                "test:wheat", 1, List.of(IngredientContribution.of("test:seed", 0.0D, 0.0D, 1, true))),
            new RecipeNode("test:bread_from_wheat", "minecraft:crafting",
                "test:bread", 1, List.of(IngredientContribution.of("test:wheat", 0.0D, 0.0D, 3, true)))
        ));
        List<ResourceEconomicProfile> configuredProfiles = List.of(
            EconomicProfileOverride.parse("test:seed|1|1|-|UNKNOWN").toProfile(),
            EconomicProfileOverride.parse("test:wheat|0.25|1|-|UNKNOWN").toProfile()
        );
        Map<String, ResourceEconomicProfile> profilesByItem = configuredProfiles.stream()
            .collect(java.util.stream.Collectors.toMap(ResourceEconomicProfile::resourceId, profile -> profile));
        EconomicCostEvidenceProvider configuredInputs = (itemId, horizon) -> {
            ResourceEconomicProfile profile = profilesByItem.get(itemId);
            return profile == null || profile.economicCost() == null
                ? EconomicCost.unknown("no explicit configured EconomicCost override for " + itemId)
                : EconomicCost.known(profile.economicCost(), "configured_profile", horizon,
                    "explicit authoritative EconomicCost override");
        };
        AcquisitionPath derivedBreadPath = new RecipeGraphAcquisitionAnalyzer(
            engine.graph(), configuredInputs, 1.0D, 100.0D).analyze("test:bread").getFirst();
        EconomicCostResolution breadFromInputs = EconomicCostResolver.resolve("test:bread",
            List.of(derivedBreadPath), 100, PrimaryPathStrategy.BEST_REPEATABLE_COST,
            java.util.Map.of("reliability", 1.0D), 0.0D, 1.0D, false);
        assertEquals(ResolutionStatus.UNKNOWN, breadFromInputs.status(),
            "a recursive amount does not bypass unknown survival eligibility or unknown core time");
        EconomicGenerationPublisher publisher = new EconomicGenerationPublisher();

        PublishedEconomicGeneration published = new EconomicGenerationBuildService().rebuildAndPublish(
            engine, publisher, configuredProfiles, disabledSettings(),
            List.of("test:seed", "test:wheat", "test:bread"));

        EconomicSnapshot.ResourceResult seed = published.economicSnapshot().resource("test:seed").orElseThrow();
        EconomicSnapshot.ResourceResult wheat = published.economicSnapshot().resource("test:wheat").orElseThrow();
        EconomicSnapshot.ResourceResult bread = published.economicSnapshot().resource("test:bread").orElseThrow();
        assertTrue(published.economicSnapshot().inputSet().recursiveDependencyIds().contains("test:wheat")
                && published.economicSnapshot().inputSet().candidateResourceIds().contains("test:wheat"),
            "a configured override remains a candidate and a recursive dependency when recipes consume it");
        assertTrue(seed.acquisitionPaths().isEmpty(),
            "an explicit EconomicCost override must not synthesize an acquisition path");
        assertEquals(0.25D, wheat.economicCost().value(), 0.0D,
            "the explicit cost remains authoritative over a derived recipe path");
        assertEquals(1.0D, wheat.acquisitionPaths().getFirst().economicCost().value(), 0.0D,
            "a recipe-derived path may remain as diagnostic evidence without replacing the override");
        assertEquals(0.75D, bread.acquisitionPaths().getFirst().economicCost().value(), 0.0D,
            "three wheat at a policy cost of 0.25 must yield a recursive amount of 0.75");
        EconomicCostResolution partialFeasibilityResolution = EconomicCostResolver.resolve("test:bread",
            bread.acquisitionPaths(), 100, PrimaryPathStrategy.BEST_REPEATABLE_COST,
            com.jorjik.dynamicfood.config.DynamicFoodConfig.feasibilityFactorWeights(),
            0.0D, 0.80D, true);
        assertEquals(ResolutionStatus.UNKNOWN, partialFeasibilityResolution.status(),
            "survival eligibility and core completeness remain required despite a recursive diagnostic amount");
        assertEquals(EstimateKind.EXACT, bread.acquisitionPaths().getFirst().evidence()
            .measurement("expected_units_per_attempt").estimateKind().orElseThrow());
    }

    @Test
    void productionBoundaryUsesIndexedCandidatesAndRecipeDependenciesInsteadOfTheItemRegistry() {
        DynamicFoodEngine engine = readyEngine();
        engine.replaceStaticRecipes(List.of(
            new RecipeNode("test:bread_from_wheat", "minecraft:crafting", "test:bread", 1,
                List.of(), null, List.of(new com.jorjik.dynamicfood.graph.AcquisitionIngredient(
                    List.of("test:wheat"), 3)))
        ));
        engine.registerAcquisitionAnalyzer(new AcquisitionAnalyzer() {
            @Override
            public boolean supports(String itemId) {
                return itemId.equals("test:direct_source");
            }

            @Override
            public List<AcquisitionPath> analyze(String itemId) {
                return List.of(new AcquisitionPath(itemId, "test_source", "test:source", 1.0D,
                    1.0D, 0.0D, true, false,
                    Map.of("reliability", EconomicFactor.known(1.0D)), Map.of()));
            }

            @Override
            public Set<String> indexedItemIds() {
                return Set.of("test:direct_source");
            }
        });

        PublishedEconomicGeneration published = new EconomicGenerationBuildService().rebuildAndPublish(
            engine, new EconomicGenerationPublisher(),
            List.of(EconomicProfileOverride.parse("test:wheat|0.25|1|-|UNKNOWN").toProfile()),
            disabledSettings(), List.of("test:bread"));

        assertEquals(Set.of("test:bread", "test:direct_source", "test:wheat"),
            published.economicSnapshot().resources().keySet());
        assertEquals(Set.of("test:bread", "test:direct_source", "test:wheat"),
            published.economicSnapshot().inputSet().candidateResourceIds());
        assertEquals(Set.of("test:wheat"),
            published.economicSnapshot().inputSet().recursiveDependencyIds());
        assertFalse(published.economicSnapshot().resources().containsKey("minecraft:air"));
        assertTrue(published.economicSnapshot().resource("test:bread").orElseThrow()
            .acquisitionPaths().stream().anyMatch(path -> path.sourceType().equals("recipe")),
            "the configured override and its recipe output must both be represented in the scoped snapshot");
    }

    @Test
    void unresolvedRecipeInputsRemainUnknownForTheResourceAndItsDependentOutput() {
        DynamicFoodEngine engine = readyEngine();
        engine.replaceStaticRecipes(List.of(
            new RecipeNode("test:a_from_unknown", "minecraft:crafting",
                "test:a", 1, List.of(IngredientContribution.of("test:unpriced", 0.0D, 0.0D, 1, true))),
            new RecipeNode("test:b_from_a", "minecraft:crafting",
                "test:b", 1, List.of(IngredientContribution.of("test:a", 0.0D, 0.0D, 2, true)))
        ));
        EconomicSnapshot snapshot = new EconomicGenerationBuildService().rebuildAndPublish(
            engine, new EconomicGenerationPublisher(), List.of(), disabledSettings(),
            List.of("test:unpriced", "test:a", "test:b")).economicSnapshot();

        EconomicSnapshot.ResourceResult resourceA = snapshot.resource("test:a").orElseThrow();
        EconomicSnapshot.ResourceResult resourceB = snapshot.resource("test:b").orElseThrow();
        assertEquals(ResolutionStatus.UNKNOWN, resourceA.economicResolution().status());
        assertEquals(ResolutionStatus.UNKNOWN, resourceB.economicResolution().status());
        assertTrue(!resourceA.economicCost().isKnown());
        assertTrue(!resourceB.economicCost().isKnown());
        assertTrue(!resourceB.acquisitionPaths().isEmpty());
        assertTrue(!resourceB.acquisitionPaths().getFirst().evidence().attributes()
            .getOrDefault("missing_inputs", "").isBlank());
    }

    @Test
    void failuresKeepOldGenerationAndDoNotConsumeGenerationNumber() {
        DynamicFoodEngine engine = readyEngine();
        AtomicBoolean fail = new AtomicBoolean();
        engine.registerAcquisitionAnalyzer(new AcquisitionAnalyzer() {
            @Override
            public boolean supports(String itemId) {
                if (fail.get()) {
                    throw new IllegalStateException("test acquisition failure");
                }
                return false;
            }

            @Override
            public List<AcquisitionPath> analyze(String itemId) {
                return List.of();
            }

            @Override
            public Set<String> indexedItemIds() {
                return Set.of();
            }
        });
        EconomicGenerationPublisher publisher = new EconomicGenerationPublisher();
        EconomicGenerationBuildService builder = new EconomicGenerationBuildService();
        fail.set(true);

        assertThrows(IllegalStateException.class, () -> builder.rebuildAndPublish(engine, publisher,
            List.of(configuredWheat()), settings(), List.of("minecraft:wheat")));
        assertTrue(publisher.current().isEmpty(),
            "a failed initial build must not publish a synthetic generation");

        fail.set(false);
        PublishedEconomicGeneration first = builder.rebuildAndPublish(engine, publisher,
            List.of(configuredWheat()), settings(), List.of("minecraft:wheat"));
        fail.set(true);
        assertThrows(IllegalStateException.class, () -> builder.rebuildAndPublish(engine, publisher,
            List.of(configuredWheat()), settings(), List.of("minecraft:wheat")));
        assertSame(first, publisher.current().orElseThrow(),
            "a failed replacement must leave the old valid pair published");

        fail.set(false);
        PublishedEconomicGeneration second = builder.rebuildAndPublish(engine, publisher,
            List.of(configuredWheat()), settings(), List.of("minecraft:wheat"));

        assertEquals(2L, second.generation(),
            "a failed attempt must not increment the successful generation counter");
        assertEquals(first.economicSnapshot().signature(), second.economicSnapshot().signature(),
            "identical economic contents may be published under a new generation");
    }

    @Test
    void previousGenerationRemainsVisibleWhileReplacementIsBuilt() throws Exception {
        DynamicFoodEngine engine = readyEngine();
        AtomicBoolean block = new AtomicBoolean();
        CountDownLatch buildEnteredAnalyzer = new CountDownLatch(1);
        CountDownLatch continueBuild = new CountDownLatch(1);
        engine.registerAcquisitionAnalyzer(new AcquisitionAnalyzer() {
            @Override
            public boolean supports(String itemId) {
                if (block.get()) {
                    buildEnteredAnalyzer.countDown();
                    await(continueBuild);
                }
                return false;
            }

            @Override
            public List<AcquisitionPath> analyze(String itemId) {
                return List.of();
            }

            @Override
            public Set<String> indexedItemIds() {
                return Set.of();
            }
        });
        EconomicGenerationPublisher publisher = new EconomicGenerationPublisher();
        EconomicGenerationBuildService builder = new EconomicGenerationBuildService();
        PublishedEconomicGeneration first = builder.rebuildAndPublish(engine, publisher,
            List.of(configuredWheat()), settings(), List.of("minecraft:wheat"));
        block.set(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PublishedEconomicGeneration> rebuild = executor.submit(() ->
                builder.rebuildAndPublish(engine, publisher, List.of(configuredWheat()), settings(),
                    List.of("minecraft:wheat")));
            assertTrue(buildEnteredAnalyzer.await(5, TimeUnit.SECONDS));
            assertSame(first, publisher.current().orElseThrow(),
                "the old complete generation remains published while the replacement is built");
            continueBuild.countDown();
            PublishedEconomicGeneration second = rebuild.get(5, TimeUnit.SECONDS);
            assertEquals(2L, second.generation());
            assertSame(second, publisher.current().orElseThrow());
        } finally {
            continueBuild.countDown();
            executor.shutdownNow();
        }
    }

    private static DynamicFoodEngine readyEngine() {
        DynamicFoodEngine engine = new DynamicFoodEngine();
        engine.markStaticAcquisitionInputsReady();
        return engine;
    }

    private static ResourceEconomicProfile configuredWheat() {
        return new ResourceEconomicProfile("minecraft:wheat", "minecraft:wheat", 1.0D,
            1.0D, 1.0D, true, true, SurvivalAcquirability.TRUE, false, false);
    }

    private static FoodCalibrationSettings settings() {
        return new FoodCalibrationSettings(true, "vanilla", 1, 0.70D, 0.30D,
            0.05D, 0.95D, 0.50D, 0.80D, "medium", List.of(), List.of(), 2.0D, 0.0D);
    }

    private static FoodCalibrationSettings disabledSettings() {
        return new FoodCalibrationSettings(false, "vanilla", 1, 0.70D, 0.30D,
            0.05D, 0.95D, 0.50D, 0.80D, "medium", List.of(), List.of(), 2.0D, 0.0D);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test build did not receive release signal");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test build interrupted", exception);
        }
    }
}
