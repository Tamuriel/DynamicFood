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
    void providerEvidenceFlowsThroughProductionAnalyzerAndSurvivesInvalidationAndReload() {
        DynamicFoodEngine baselineEngine = providerTestEngine();
        EconomicSnapshot.ResourceResult baseline = buildProviderSnapshot(baselineEngine)
            .economicSnapshot().resource("test:provider_output").orElseThrow();

        DynamicFoodEngine engine = providerTestEngine();
        engine.registerProvider(provider("provider-test", "provider-mechanic", "provider-state-a"));
        engine.invalidate();
        engine.replaceStaticRecipes(providerTestRecipes());
        engine.rebuildLootTableAnalyzer(LootTableAcquisitionAnalyzer.fromParsedTables(
            List.of(), 1.0D, 100.0D), 1.0D, 100.0D);
        PublishedEconomicGeneration published = buildProviderSnapshot(engine);
        EconomicSnapshot.ResourceResult result = published.economicSnapshot()
            .resource("test:provider_output").orElseThrow();
        AcquisitionPath path = result.acquisitionPaths().stream()
            .filter(candidate -> candidate.sourceId().equals("test:provider_recipe"))
            .findFirst().orElseThrow();

        assertEquals("RESOLVED", path.evidence().attributes()
            .get("provider_contribution.provider-mechanic.status"));
        assertEquals("\"provider-state-a\"", path.evidence().attributes()
            .get("provider_contribution.provider-mechanic.effective_payload.state"));
        assertEquals("provider-test", path.evidence().attributes()
            .get("provider_contribution.provider-mechanic.contribution_0.provider_id"));
        assertEquals(baseline.acquisitionPaths().getFirst().costsByHorizon(),
            path.costsByHorizon(), "opaque provider evidence must not invent economic semantics");
        assertFalse(published.economicSnapshot().signature().equals(
            buildProviderSnapshot(providerTestEngineWithProvider(
                "provider-test", "provider-mechanic", "provider-state-b"))
                .economicSnapshot().signature()),
            "provider-derived evidence changes must affect the content signature");
        assertFalse(baseline.acquisitionPaths().getFirst().evidence().attributes().keySet().stream()
            .anyMatch(key -> key.startsWith("provider_contribution.")),
            "the no-provider baseline remains unchanged");
    }

    @Test
    void providerConflictsAreOrderIndependentAndLocalizedInPublishedPaths() {
        MechanicProvider first = provider("provider-a", "conflicting-mechanic", "state-a");
        MechanicProvider second = provider("provider-b", "conflicting-mechanic", "state-b");
        PublishedEconomicGeneration forward = buildProviderSnapshot(
            providerTestEngineWithProviders(List.of(first, second)));
        PublishedEconomicGeneration reverse = buildProviderSnapshot(
            providerTestEngineWithProviders(List.of(second, first)));

        EconomicSnapshot.ResourceResult affected = forward.economicSnapshot()
            .resource("test:provider_output").orElseThrow();
        AcquisitionPath affectedPath = affected.acquisitionPaths().stream()
            .filter(candidate -> candidate.sourceId().equals("test:provider_recipe"))
            .findFirst().orElseThrow();
        EconomicSnapshot.ResourceResult unrelated = forward.economicSnapshot()
            .resource("test:unrelated_output").orElseThrow();
        AcquisitionPath unrelatedPath = unrelated.acquisitionPaths().stream()
            .filter(candidate -> candidate.sourceId().equals("test:unrelated_recipe"))
            .findFirst().orElseThrow();

        assertEquals("CONFLICT", affectedPath.evidence().attributes()
            .get("provider_contribution.conflicting-mechanic.status"));
        assertFalse(affectedPath.evidence().attributes().keySet().stream()
            .anyMatch(key -> key.startsWith("provider_contribution.conflicting-mechanic.contribution_")),
            "conflicting payloads must not leak an arbitrary winner into acquisition evidence");
        assertFalse(unrelatedPath.evidence().attributes().keySet().stream()
            .anyMatch(key -> key.startsWith("provider_contribution.")),
            "a source-local conflict must not contaminate unrelated resources");
        assertEquals(forward.economicSnapshot().signature(), reverse.economicSnapshot().signature());
        assertEquals(affectedPath.evidence().attributes(), reverse.economicSnapshot()
            .resource("test:provider_output").orElseThrow().acquisitionPaths().stream()
            .filter(candidate -> candidate.sourceId().equals("test:provider_recipe"))
            .findFirst().orElseThrow().evidence().attributes());

        DynamicFoodEngine conflictEngine = providerTestEngineWithProviders(List.of(first, second));
        AcquisitionPath existingRecipePath = conflictEngine.acquisitionPaths("test:provider_output").stream()
            .filter(candidate -> candidate.sourceId().equals("test:provider_recipe"))
            .findFirst().orElseThrow();
        conflictEngine.registerAcquisitionAnalyzer(fixedAnalyzer(List.of(existingRecipePath)));
        EconomicSnapshot afterDeduplication = buildProviderSnapshot(conflictEngine).economicSnapshot();
        List<AcquisitionPath> deduplicated = afterDeduplication.resource("test:provider_output")
            .orElseThrow().acquisitionPaths().stream()
            .filter(candidate -> candidate.sourceId().equals("test:provider_recipe")).toList();
        assertEquals(1, deduplicated.size());
        assertEquals("CONFLICT", deduplicated.getFirst().evidence().attributes()
            .get("provider_contribution.conflicting-mechanic.status"),
            "provider conflict status must survive duplicate-path reconciliation");
    }

    @Test
    void worldgenCausalEvidenceFlowsThroughExtractionAndProductionSnapshotSignature() {
        PublishedEconomicGeneration first = buildWorldgenSnapshot(worldgenSource("configured-v1"));
        PublishedEconomicGeneration changed = buildWorldgenSnapshot(worldgenSource("configured-v2"));
        PublishedEconomicGeneration differentBranch =
            buildWorldgenSnapshot(worldgenSource("configured-v1", "stage=11"));
        PublishedEconomicGeneration conflict = buildWorldgenSnapshot(List.of(
            worldgenSource("configured-v1"), worldgenSource("configured-v2")));
        PublishedEconomicGeneration conflictReversed = buildWorldgenSnapshot(List.of(
            worldgenSource("configured-v2"), worldgenSource("configured-v1")));

        AcquisitionPath firstPath = first.economicSnapshot().resource("test:worldgen_output").orElseThrow()
            .acquisitionPaths().stream().filter(path -> path.sourceType().equals("worldgen_feature"))
            .findFirst().orElseThrow();
        AcquisitionPath changedPath = changed.economicSnapshot().resource("test:worldgen_output").orElseThrow()
            .acquisitionPaths().stream().filter(path -> path.sourceType().equals("worldgen_feature"))
            .findFirst().orElseThrow();
        AcquisitionPath differentBranchPath = differentBranch.economicSnapshot()
            .resource("test:worldgen_output").orElseThrow().acquisitionPaths().stream()
            .filter(path -> path.sourceType().equals("worldgen_feature")).findFirst().orElseThrow();
        AcquisitionPath conflictPath = conflict.economicSnapshot().resource("test:worldgen_output").orElseThrow()
            .acquisitionPaths().stream().filter(path -> path.sourceType().equals("worldgen_feature"))
            .findFirst().orElseThrow();

        assertFalse(firstPath.evidence().worldgenCausalEvidence().nodes().isEmpty());
        assertTrue(firstPath.evidence().worldgenCausalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.EXTRACTED_BY));
        assertTrue(firstPath.evidence().worldgenCausalEvidence().relationships().stream().anyMatch(relationship ->
            relationship.type() == WorldgenCausalEvidence.RelationshipType.YIELDS_ITEM
                && relationship.to().identifier().equals("test:worldgen_output")));
        assertEquals(firstPath.pathIdentity(), changedPath.pathIdentity(),
            "raw configured-feature evidence is diagnostic and must not change mechanical identity");
        assertFalse(firstPath.pathIdentity().equals(differentBranchPath.pathIdentity()),
            "changing the feature-stage relationship must change mechanical identity");
        assertFalse(first.economicSnapshot().signature().equals(changed.economicSnapshot().signature()),
            "meaningful worldgen source evidence must change the published snapshot signature");
        assertEquals("CONFLICT", conflictPath.evidence().attributes()
            .get("acquisition_path_deduplication_status"));
        assertTrue(conflictPath.evidence().attributes().get("acquisition_path_deduplication_conflicts")
            .contains("worldgen_causal_definition"));
        assertEquals(conflict.economicSnapshot().signature(), conflictReversed.economicSnapshot().signature(),
            "conflicting raw definitions and diagnostics must be independent of source order");
    }

    @Test
    void duplicateAnalyzerPathsDeduplicateInPublishedSnapshotAndRetainProvenance() {
        MechanicProvider firstProvider = providerForSource("provider-a", "shared-mechanic",
            "same-mechanics", "test_source", "test:duplicate_source", Map.of("reported_by", "first"));
        MechanicProvider secondProvider = providerForSource("provider-b", "shared-mechanic",
            "same-mechanics", "test_source", "test:duplicate_source", Map.of("reported_by", "second"));
        AcquisitionPath first = duplicatePath("first-analyzer", "first-provider");
        AcquisitionPath second = duplicatePath("second-analyzer", "second-provider");

        PublishedEconomicGeneration published = buildDuplicateGeneration(List.of(first, second),
            List.of(firstProvider, secondProvider));
        List<AcquisitionPath> paths = published.economicSnapshot()
            .resource("test:dedup_output").orElseThrow().acquisitionPaths();

        assertEquals(1, paths.size());
        AcquisitionPath path = paths.getFirst();
        assertTrue(path.evidence().attributes().get("deduplication.provenance."
            + encoded("source_provenance.analyzer") + ".values").contains("first-analyzer"));
        assertTrue(path.evidence().attributes().get("deduplication.provenance."
            + encoded("source_provenance.analyzer") + ".values").contains("second-analyzer"));
        assertEquals("provider-a", path.evidence().attributes()
            .get("provider_contribution.shared-mechanic.contribution_0.provider_id"));
        assertEquals("provider-b", path.evidence().attributes()
            .get("provider_contribution.shared-mechanic.contribution_1.provider_id"));
        assertFalse("CONFLICT".equals(path.evidence().attributes()
            .get("acquisition_path_deduplication_status")));
    }

    @Test
    void conflictingDuplicateEvidenceIsUnknownAndSnapshotIsIndependentOfAnalyzerOrder() {
        AcquisitionPath first = duplicatePath("source-a", "provider-a", 1.0D, 0.2D);
        AcquisitionPath second = duplicatePath("source-b", "provider-b", 2.0D, 0.8D);

        EconomicSnapshot forward = buildDuplicateGeneration(List.of(first, second), List.of())
            .economicSnapshot();
        EconomicSnapshot reverse = buildDuplicateGeneration(List.of(second, first), List.of())
            .economicSnapshot();
        AcquisitionPath merged = forward.resource("test:dedup_output").orElseThrow()
            .acquisitionPaths().getFirst();

        assertEquals(1, forward.resource("test:dedup_output").orElseThrow().acquisitionPaths().size());
        assertEquals("CONFLICT", merged.evidence().attributes()
            .get("acquisition_path_deduplication_status"));
        assertFalse(merged.evidence().measurement("quantity_measurement").isKnown());
        assertEquals(FactorState.UNKNOWN, merged.costsByHorizon().get(100)
            .factor(EconomicChannel.QUANTITY).state());
        assertEquals(ResolutionStatus.UNKNOWN,
            forward.resource("test:dedup_output").orElseThrow().economicResolution().status());
        assertEquals(forward.signature(), reverse.signature());
        assertEquals(merged.evidence().attributes(), reverse.resource("test:dedup_output").orElseThrow()
            .acquisitionPaths().getFirst().evidence().attributes());
    }

    @Test
    void equivalentReorderedDuplicatesHaveStableSignatureAndEvidenceChangesFingerprint() {
        AcquisitionPath first = duplicatePath("analyzer-a", "provider-a");
        AcquisitionPath second = duplicatePath("analyzer-b", "provider-b");
        EconomicSnapshot forward = buildDuplicateGeneration(List.of(first, second), List.of()).economicSnapshot();
        EconomicSnapshot reverse = buildDuplicateGeneration(List.of(second, first), List.of()).economicSnapshot();
        EconomicSnapshot shuffledInput = buildDuplicateGenerationSingleAnalyzer(
            List.of(second, first), List.of()).economicSnapshot();
        AcquisitionPath changed = new AcquisitionPath("test:dedup_output", "test_source",
            "test:duplicate_source", 1.0D, null, null, true, false, Map.of(), Map.of(),
            new AcquisitionEvidence(first.evidence().measurements(),
                Map.of("source_availability_classification", "TRUE",
                    "source_provenance.analyzer", "analyzer-a",
                    "source_provenance.provider", "provider-a",
                    "mechanical_identity.operation", "different-extraction"),
                first.evidence().inputs()));
        EconomicSnapshot changedSnapshot =
            buildDuplicateGeneration(List.of(changed, second), List.of()).economicSnapshot();

        assertEquals(forward.signature(), reverse.signature());
        assertEquals(forward.signature(), shuffledInput.signature());
        assertFalse(forward.signature().equals(changedSnapshot.signature()),
            "a mechanical operation evidence change must alter the published content signature");
    }

    @Test
    void noProviderSnapshotKeepsDistinctAcquisitionAlternativesAndSinglePaths() {
        AcquisitionPath first = new AcquisitionPath("test:dedup_output", "test_source", "test:path_a",
            1.0D, null, null, true, false, Map.of(), Map.of());
        AcquisitionPath second = new AcquisitionPath("test:dedup_output", "test_source", "test:path_b",
            1.0D, null, null, true, false, Map.of(), Map.of());
        EconomicSnapshot snapshot = buildDuplicateGeneration(List.of(first, second), List.of()).economicSnapshot();

        assertEquals(2, snapshot.resource("test:dedup_output").orElseThrow().acquisitionPaths().size());
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

    private static DynamicFoodEngine providerTestEngine() {
        DynamicFoodEngine engine = readyEngine();
        engine.replaceStaticRecipes(providerTestRecipes());
        return engine;
    }

    private static DynamicFoodEngine providerTestEngineWithProvider(String providerId, String mechanicId,
        String state) {
        return providerTestEngineWithProviders(List.of(provider(providerId, mechanicId, state)));
    }

    private static DynamicFoodEngine providerTestEngineWithProviders(List<MechanicProvider> providers) {
        DynamicFoodEngine engine = providerTestEngine();
        providers.forEach(engine::registerProvider);
        return engine;
    }

    private static List<RecipeNode> providerTestRecipes() {
        return List.of(
            new RecipeNode("test:provider_recipe", "minecraft:crafting", "test:provider_output",
                1, List.of(), null, List.of(), true),
            new RecipeNode("test:unrelated_recipe", "minecraft:crafting", "test:unrelated_output",
                1, List.of(), null, List.of(), true)
        );
    }

    private static MechanicProvider provider(String providerId, String mechanicId, String state) {
        return new MechanicProvider() {
            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public String providerVersion() {
                return "test-v1";
            }

            @Override
            public List<ProviderContribution> contributions() {
                return List.of(new ProviderContribution(providerId, mechanicId,
                    ProviderContributionOperation.ADD, "recipe", "test:provider_recipe",
                    Map.of("state", state), Map.of("fixture", "production-analyzer-integration")));
            }
        };
    }

    private static MechanicProvider providerForSource(String providerId, String mechanicId, String state,
        String sourceType, String sourceId, Map<String, String> provenance) {
        return new MechanicProvider() {
            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public String providerVersion() {
                return "dedup-test-v1";
            }

            @Override
            public List<ProviderContribution> contributions() {
                return List.of(new ProviderContribution(providerId, mechanicId,
                    ProviderContributionOperation.ADD, sourceType, sourceId,
                    Map.of("state", state), provenance));
            }
        };
    }

    private static AcquisitionPath duplicatePath(String analyzer, String provider) {
        return duplicatePath(analyzer, provider, 1.0D, 0.5D);
    }

    private static AcquisitionPath duplicatePath(String analyzer, String provider,
        double measurementValue, double quantityFactor) {
        return new AcquisitionPath("test:dedup_output", "test_source", "test:duplicate_source",
            1.0D, null, null, true, false, Map.of(), duplicateCostVector(quantityFactor),
            evidenceWithQuantity(measurementValue, analyzer, provider));
    }

    private static AcquisitionEvidence evidenceWithQuantity(double value, String analyzer, String provider) {
        return new AcquisitionEvidence(
            Map.of("quantity_measurement", AcquisitionMeasurement.exact(value)),
            Map.of("source_availability_classification", "TRUE",
                "source_provenance.analyzer", analyzer,
                "source_provenance.provider", provider),
            List.of());
    }

    private static Map<Integer, CostVector> duplicateCostVector(double quantity) {
        return Map.of(100, new CostVector(100, Map.of(
            EconomicChannel.QUANTITY, EconomicFactor.known(quantity),
            EconomicChannel.PROBABILITY_BURDEN, EconomicFactor.notApplicable("fixture"),
            EconomicChannel.MATERIAL_CONSUMPTION, EconomicFactor.notApplicable("fixture"),
            EconomicChannel.EQUIPMENT_ECONOMIC_BURDEN, EconomicFactor.notApplicable("fixture"))));
    }

    private static String encoded(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static AcquisitionAnalyzer fixedAnalyzer(List<AcquisitionPath> paths) {
        Set<String> itemIds = paths.stream().map(AcquisitionPath::itemId).collect(java.util.stream.Collectors.toSet());
        return new AcquisitionAnalyzer() {
            @Override
            public boolean supports(String itemId) {
                return itemIds.contains(itemId);
            }

            @Override
            public List<AcquisitionPath> analyze(String itemId) {
                return paths.stream().filter(path -> path.itemId().equals(itemId)).toList();
            }

            @Override
            public Set<String> indexedItemIds() {
                return itemIds;
            }
        };
    }

    private static PublishedEconomicGeneration buildDuplicateGeneration(List<AcquisitionPath> paths,
        List<MechanicProvider> providers) {
        DynamicFoodEngine engine = readyEngine();
        providers.forEach(engine::registerProvider);
        engine.registerAcquisitionAnalyzer(fixedAnalyzer(List.of(paths.getFirst())));
        if (paths.size() > 1) {
            engine.registerAcquisitionAnalyzer(fixedAnalyzer(paths.subList(1, paths.size())));
        }
        return new EconomicGenerationBuildService().rebuildAndPublish(engine,
            new EconomicGenerationPublisher(), List.of(), disabledSettings(), List.of("test:dedup_output"));
    }

    private static PublishedEconomicGeneration buildDuplicateGenerationSingleAnalyzer(
        List<AcquisitionPath> paths, List<MechanicProvider> providers) {
        DynamicFoodEngine engine = readyEngine();
        providers.forEach(engine::registerProvider);
        engine.registerAcquisitionAnalyzer(fixedAnalyzer(paths));
        return new EconomicGenerationBuildService().rebuildAndPublish(engine,
            new EconomicGenerationPublisher(), List.of(), disabledSettings(), List.of("test:dedup_output"));
    }

    private static PublishedEconomicGeneration buildProviderSnapshot(DynamicFoodEngine engine) {
        return new EconomicGenerationBuildService().rebuildAndPublish(engine,
            new EconomicGenerationPublisher(), List.of(), disabledSettings(),
            List.of("test:provider_output", "test:unrelated_output"));
    }

    private static PublishedEconomicGeneration buildWorldgenSnapshot(
        WorldgenAcquisitionAnalyzer.WorldgenBlockSource source) {
        return buildWorldgenSnapshot(List.of(source));
    }

    private static PublishedEconomicGeneration buildWorldgenSnapshot(
        List<WorldgenAcquisitionAnalyzer.WorldgenBlockSource> sources) {
        LootTableAcquisitionAnalyzer.ParsedTable table = new LootTableAcquisitionAnalyzer.ParsedTable(
            "minecraft:blocks/stone", "block_loot", Map.of("test:worldgen_output", 1.0D), Set.of(),
            Map.of("test:worldgen_output", new LootTableAcquisitionAnalyzer.LootEvidence(1.0D, 1.0D)));
        LootTableAcquisitionAnalyzer lootAnalyzer = LootTableAcquisitionAnalyzer.fromParsedTables(
            List.of(table), 1.0D, 100.0D);
        WorldgenAcquisitionAnalyzer worldgenAnalyzer = WorldgenAcquisitionAnalyzer.fromResolvedBlockSources(
            Map.of("minecraft:stone", sources),
            Map.of("minecraft:stone", new WorldgenAcquisitionAnalyzer.BlockExtraction(
                "minecraft:stone", "minecraft:blocks/stone", false)),
            lootAnalyzer);
        DynamicFoodEngine engine = readyEngine();
        engine.registerAcquisitionAnalyzer(worldgenAnalyzer);
        return new EconomicGenerationBuildService().rebuildAndPublish(engine,
            new EconomicGenerationPublisher(), List.of(), disabledSettings(), List.of("test:worldgen_output"));
    }

    private static WorldgenAcquisitionAnalyzer.WorldgenBlockSource worldgenSource(String rawEvidence) {
        return worldgenSource(rawEvidence, "stage=10");
    }

    private static WorldgenAcquisitionAnalyzer.WorldgenBlockSource worldgenSource(
        String rawEvidence, String featureStageEvidence) {
        WorldgenCausalEvidence.NodeRef biome = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.BIOME, "test:forest");
        WorldgenCausalEvidence.NodeRef placed = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.PLACED_FEATURE, "test:trees");
        WorldgenCausalEvidence.NodeRef configured = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.CONFIGURED_FEATURE, "test:oak_tree");
        WorldgenCausalEvidence.NodeRef state = new WorldgenCausalEvidence.NodeRef(
            WorldgenCausalEvidence.NodeType.BLOCK_STATE, "minecraft:stone|{\"Name\":\"minecraft:stone\"}");
        WorldgenCausalEvidence.Builder graph = new WorldgenCausalEvidence.Builder();
        graph.addNode(biome.type(), biome.identifier(), "test:worldgen/biome/forest.json", "{}")
            .addNode(placed.type(), placed.identifier(), "test:worldgen/placed_feature/trees.json", "{}")
            .addNode(configured.type(), configured.identifier(),
                "test:worldgen/configured_feature/oak_tree.json", rawEvidence)
            .addNode(state.type(), state.identifier(),
                "test:worldgen/configured_feature/oak_tree.json", "{\"Name\":\"minecraft:stone\"}")
            .addRelationship(biome, WorldgenCausalEvidence.RelationshipType.CONTAINS_PLACED_FEATURE,
                placed, WorldgenCausalEvidence.BranchKind.NONE, featureStageEvidence, "test:forest", "")
            .addRelationship(placed, WorldgenCausalEvidence.RelationshipType.PLACES_CONFIGURED_FEATURE,
                configured, WorldgenCausalEvidence.BranchKind.NONE, "", "test:trees", "")
            .addRelationship(configured, WorldgenCausalEvidence.RelationshipType.MAY_GENERATE_BLOCK,
                state, WorldgenCausalEvidence.BranchKind.NONE, "", "test:oak_tree", "");
        return new WorldgenAcquisitionAnalyzer.WorldgenBlockSource("test:forest/test:trees",
            "test:forest", "test:trees", Map.of(), Map.of(), graph.build());
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
