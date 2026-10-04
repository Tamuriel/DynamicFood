package com.jorjik.dynamicfood.core;

import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;

/** Builds and atomically publishes one complete static economic/calibration generation. */
public final class EconomicGenerationBuildService {
    public PublishedEconomicGeneration rebuildAndPublish(
        DynamicFoodEngine engine,
        EconomicGenerationPublisher publisher,
        Collection<ResourceEconomicProfile> configuredProfiles,
        FoodCalibrationSettings settings
    ) {
        return rebuildAndPublish(engine, publisher, configuredProfiles, settings, List.of(),
            EconomicGenerationBuildService::isTechnicalResource);
    }

    public PublishedEconomicGeneration rebuildAndPublish(
        DynamicFoodEngine engine,
        EconomicGenerationPublisher publisher,
        Collection<ResourceEconomicProfile> configuredProfiles,
        FoodCalibrationSettings settings,
        Collection<String> indexedResourceIds
    ) {
        return rebuildAndPublish(engine, publisher, configuredProfiles, settings, indexedResourceIds,
            ignored -> false);
    }

    private PublishedEconomicGeneration rebuildAndPublish(
        DynamicFoodEngine engine,
        EconomicGenerationPublisher publisher,
        Collection<ResourceEconomicProfile> configuredProfiles,
        FoodCalibrationSettings settings,
        Collection<String> indexedResourceIds,
        java.util.function.Predicate<String> technicalResource
    ) {
        if (engine == null || publisher == null || configuredProfiles == null || settings == null) {
            throw new IllegalArgumentException("engine, publisher, configured profiles, and settings are required");
        }
        if (indexedResourceIds == null || technicalResource == null) {
            throw new IllegalArgumentException("indexed resource IDs are required");
        }
        if (!engine.staticAcquisitionInputsReady()) {
            throw new IllegalStateException("static recipe, loot, worldgen, and trade inputs are not ready");
        }

        Map<String, ResourceEconomicProfile> configuredByItem = new HashMap<>();
        configuredProfiles.forEach(profile -> configuredByItem.put(profile.resourceId(), profile));
        List<AcquisitionAnalyzer> analyzers =
            engine.acquisitionAnalyzersForSnapshot(configuredCostEvidence(configuredByItem));
        Set<String> discoveredResourceIds = new java.util.TreeSet<>(indexedResourceIds);
        analyzers.forEach(analyzer -> discoveredResourceIds.addAll(analyzer.indexedItemIds()));
        EconomicSnapshotInputSet inputSet = EconomicSnapshotInputSet.derive(
            discoveredResourceIds,
            configuredByItem.keySet(),
            engine.graph(),
            technicalResource);

        return publisher.rebuildAndPublish(generation -> {
            EconomicSnapshot economicSnapshot = EconomicSnapshotBuilder.build(generation, inputSet,
                analyzers, (resourceId, paths) -> resolveEconomicCost(resourceId, paths,
                    configuredByItem.get(resourceId)));
            List<ResourceEconomicProfile> population = calibrationPopulation(
                economicSnapshot, configuredByItem, settings);
            CalibrationSnapshot calibrationSnapshot = CalibrationSnapshotBuilder.build(
                economicSnapshot, population, settings);
            return new PublishedEconomicGeneration(economicSnapshot, calibrationSnapshot);
        });
    }

    private static EconomicCostEvidenceProvider configuredCostEvidence(
        Map<String, ResourceEconomicProfile> configuredByItem
    ) {
        return (resourceId, horizon) -> {
            ResourceEconomicProfile profile = configuredByItem.get(resourceId);
            if (profile == null || profile.economicCost() == null) {
                return EconomicCost.unknown(
                    "no explicit configured EconomicCost override is registered for " + resourceId);
            }
            return EconomicCost.known(profile.economicCost(), "configured_profile", horizon,
                "explicit authoritative EconomicCost override; not an acquisition path");
        };
    }

    private static EconomicCostResolution resolveEconomicCost(String resourceId, List<AcquisitionPath> paths,
        ResourceEconomicProfile configuredProfile) {
        int horizon = DynamicFoodConfig.acquisitionEconomicHorizon();
        if (configuredProfile != null && configuredProfile.economicCost() != null) {
            EconomicCost cost = EconomicCost.known(configuredProfile.economicCost(), "configured_profile",
                horizon, "explicit configured economic resource profile");
            return new EconomicCostResolution(cost, null, ResolutionStatus.COMPLETE, horizon,
                null, paths, configuredProfile.confidence(), List.of("explicit configured economic profile"));
        }
        return EconomicCostResolver.resolve(resourceId, paths, horizon,
            DynamicFoodConfig.acquisitionStrategy(), DynamicFoodConfig.feasibilityFactorWeights(),
            DynamicFoodConfig.minimumFeasibility(), DynamicFoodConfig.minimumFeasibilityCoverage(),
            DynamicFoodConfig.allowPartialFeasibility());
    }

    private static List<ResourceEconomicProfile> calibrationPopulation(
        EconomicSnapshot economicSnapshot,
        Map<String, ResourceEconomicProfile> configuredByItem,
        FoodCalibrationSettings settings
    ) {
        if (!settings.enabled()) {
            return List.of();
        }

        List<TagKey<Item>> populationTags = settings.populationTags().stream()
            .map(ResourceLocation::tryParse)
            .filter(java.util.Objects::nonNull)
            .map(location -> TagKey.create(Registries.ITEM, location))
            .toList();
        List<ResourceEconomicProfile> population = new ArrayList<>();
        configuredByItem.values().stream()
            .filter(profile -> settings.allowsConfiguredProfile(matchesAnyTag(profile.resourceId(), populationTags)))
            .forEach(population::add);

        SurvivalAcquirabilityResolver survivalResolver = new SurvivalAcquirabilityResolver();
        economicSnapshot.resources().forEach((resourceId, result) -> {
            if (configuredByItem.containsKey(resourceId)) {
                return;
            }
            Item item = itemForId(resourceId);
            if (item == null || !settings.allowsAutomaticCandidate(matchesAnyTag(item, populationTags))
                || DynamicFoodEngine.isTechnicalResource(new ItemStack(item))
                || result.acquisitionPaths().isEmpty()
                || result.acquisitionPaths().stream().allMatch(path -> path.sourceType().equals("recipe"))) {
                return;
            }
            SurvivalAcquirabilityResolver.Result survival = survivalResolver.resolve(result.acquisitionPaths());
            if (survival.state() != SurvivalAcquirability.TRUE) {
                return;
            }
            EconomicCostResolution resolution = result.economicResolution();
            Double calibrationCost = resolution.status() == ResolutionStatus.COMPLETE
                && resolution.economicCost() != null ? resolution.economicCost() : null;
            population.add(new ResourceEconomicProfile(resourceId, resourceId, calibrationCost,
                resolution.confidence(), 1.0D, true, true, survival.state(), false, false));
        });
        return population.stream()
            .sorted(java.util.Comparator.comparing(ResourceEconomicProfile::resourceId))
            .toList();
    }

    private static Item itemForId(String itemId) {
        ResourceLocation location = ResourceLocation.tryParse(itemId);
        return location == null ? null : BuiltInRegistries.ITEM.getOptional(location).orElse(null);
    }

    private static boolean isTechnicalResource(String itemId) {
        Item item = itemForId(itemId);
        return item != null && DynamicFoodEngine.isTechnicalResource(new ItemStack(item));
    }

    private static boolean matchesAnyTag(Item item, List<TagKey<Item>> tags) {
        if (tags.isEmpty()) {
            return false;
        }
        ItemStack stack = new ItemStack(item);
        return tags.stream().anyMatch(stack::is);
    }

    private static boolean matchesAnyTag(String itemId, List<TagKey<Item>> tags) {
        if (tags.isEmpty()) {
            return false;
        }
        Item item = itemForId(itemId);
        return item != null && matchesAnyTag(item, tags);
    }
}
