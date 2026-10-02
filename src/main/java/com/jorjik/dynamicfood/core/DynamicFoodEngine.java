package com.jorjik.dynamicfood.core;

import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.adapter.RecipeAdapter;
import com.jorjik.dynamicfood.adapter.RecipeAdapterRegistry;
import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import com.jorjik.dynamicfood.graph.RecipeGraph;
import com.jorjik.dynamicfood.graph.RecipeNode;
import com.jorjik.dynamicfood.graph.RecipeResolver;
import com.jorjik.dynamicfood.graph.RecipeValueCache;
import com.jorjik.dynamicfood.provenance.DynamicFoodValue;
import com.jorjik.dynamicfood.provenance.RuntimeProvenance;
import com.jorjik.dynamicfood.provenance.RecipeOperation;
import java.util.ArrayList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.entity.npc.VillagerTrades.ItemListing;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public final class DynamicFoodEngine {
    private final RecipeGraph graph = new RecipeGraph();
    private final RecipeValueCache cache = new RecipeValueCache();
    private final RecipeAdapterRegistry adapters = new RecipeAdapterRegistry();
    private final List<AcquisitionAnalyzer> acquisitionAnalyzers = new CopyOnWriteArrayList<>();
    private volatile CalibrationSnapshot calibrationSnapshot;
    private volatile Map<String, ResourceEconomicProfile> economicProfiles = Map.of();
    private volatile FoodCalibrationSettings calibrationSettings = FoodCalibrationSettings.defaults();
    private volatile RecipeEconomicAnalyzer recipeEconomicAnalyzer;
    private volatile AcquisitionAnalyzer recipeGraphAcquisitionAnalyzer;
    private volatile LootTableAcquisitionAnalyzer lootTableAcquisitionAnalyzer;
    private volatile WorldgenAcquisitionAnalyzer worldgenAcquisitionAnalyzer;
    private volatile VillagerTradeAcquisitionAnalyzer villagerTradeAcquisitionAnalyzer =
        VillagerTradeAcquisitionAnalyzer.empty();
    private final Map<String, EconomicCostResolution> economicResolutionCache = new ConcurrentHashMap<>();
    private final Map<String, List<AcquisitionPath>> acquisitionPathCache = new ConcurrentHashMap<>();
    private volatile List<String> calibrationDiscoveryDiagnostics = List.of("Calibration discovery has not run");

    public FoodValue resolve(RuntimeProvenance provenance) {
        RuntimeProvenance effectiveProvenance = provenance;
        if (!provenance.hasActualInputs()) {
            var fallback = graph.recipeFor(provenance.resultItemId(), provenance.recipeId());
            if (fallback.isPresent()) {
                RecipeNode recipe = fallback.get();
                effectiveProvenance = new RuntimeProvenance(provenance.resultItemId(), recipe.recipeId(),
                    recipe.recipeType(), recipe.outputCount(), List.of(), provenance.stationDifficulty());
            }
        }
        return new RecipeResolver(graph, cache, adapters).resolve(effectiveProvenance);
    }

    public FoodValue resolveOrSnapshot(RuntimeProvenance provenance, DynamicFoodValue existingValue) {
        if (existingValue != null) {
            return new FoodValue(existingValue.rawNutrition(), existingValue.nutrition(), existingValue.rawSaturation(),
                existingValue.saturation(), existingValue.difficulty(), existingValue.sourceRecipe(), existingValue.outputCount(),
                existingValue.components().stream().map(component -> new IngredientContribution(
                    component.itemId(), component.nutrition(), component.saturation(), component.count(),
                    component.foodComponent(), component.sourceRecipe(), component.difficulty()
                )).toList());
        }
        return resolve(provenance);
    }

    public void replaceStaticRecipes(List<RecipeNode> recipes) {
        graph.clear();
        recipes.forEach(graph::add);
        cache.clear();
        recipeEconomicAnalyzer = null;
        recipeGraphAcquisitionAnalyzer = null;
        acquisitionPathCache.clear();
        economicResolutionCache.clear();
    }

    public void registerAdapter(RecipeAdapter adapter) {
        adapters.register(adapter);
    }

    public RecipeOperation createRecipeOperation(Recipe<?> recipe, String recipeId, String recipeType, String station,
        List<ItemStack> itemInputs, List<FluidStack> fluidInputs, List<ItemStack> outputs,
        java.util.Map<String, Double> processingMetadata) {
        RecipeAdapter adapter = adapters.find(recipeType).orElse(null);
        if (adapter != null && (recipe == null || adapter.supports(recipe))) {
            return adapter.createRecipeOperation(recipeId, recipeType, station, itemInputs, fluidInputs, outputs,
                processingMetadata);
        }
        return new RecipeOperation(recipeId, recipeType, station, 0,
            itemInputs.stream().filter(stack -> !stack.isEmpty())
                .map(stack -> new RecipeOperation.ItemInput(stack, stack.getCount())).toList(),
            fluidInputs.stream().filter(stack -> !stack.isEmpty()).map(RecipeOperation.FluidInput::new).toList(),
            outputs.stream().filter(stack -> !stack.isEmpty())
                .map(stack -> new RecipeOperation.Output(stack, null)).toList(), processingMetadata);
    }

    public RecipeGraph graph() {
        return graph;
    }

    public void invalidate() {
        cache.clear();
        economicResolutionCache.clear();
        acquisitionPathCache.clear();
    }

    public synchronized void rebuildCalibration(Collection<ResourceEconomicProfile> profiles,
        FoodCalibrationSettings settings) {
        economicResolutionCache.clear();
        acquisitionPathCache.clear();
        calibrationSettings = settings;
        Map<String, ResourceEconomicProfile> byItem = new HashMap<>();
        profiles.forEach(profile -> byItem.put(profile.resourceId(), profile));
        economicProfiles = Map.copyOf(byItem);
        recipeEconomicAnalyzer = null;
        recipeGraphAcquisitionAnalyzer = null;
        if (lootTableAcquisitionAnalyzer != null) {
            lootTableAcquisitionAnalyzer = lootTableAcquisitionAnalyzer.withNormalization(
                com.jorjik.dynamicfood.config.DynamicFoodConfig.lootAttemptsReference(),
                com.jorjik.dynamicfood.config.DynamicFoodConfig.lootAttemptsCap());
        }
        calibrationSnapshot = settings.enabled()
            ? settings.calibrator().calibrate(profiles)
                .withFoodConfiguration(settings)
                .withConfigurationSignature(settings.signatureContext())
            : null;
    }

    public synchronized void rebuildCalibrationWithDiscovery(Collection<ResourceEconomicProfile> configuredProfiles,
        FoodCalibrationSettings settings) {
        Map<String, ResourceEconomicProfile> configuredByItem = new HashMap<>();
        configuredProfiles.forEach(profile -> configuredByItem.put(profile.resourceId(), profile));
        economicProfiles = Map.copyOf(configuredByItem);
        recipeEconomicAnalyzer = null;
        recipeGraphAcquisitionAnalyzer = null;
        economicResolutionCache.clear();
        acquisitionPathCache.clear();

        List<TagKey<Item>> populationTags = parseCalibrationPopulationTags(settings.populationTags());
        List<ResourceEconomicProfile> population = new ArrayList<>();
        Map<String, ResourceEconomicProfile> allProfiles = new HashMap<>(configuredByItem);
        int foodItems = 0;
        int derivedExcluded = 0;
        int sourceExcluded = 0;
        int unknownCostExcluded = 0;
        int automaticallyIncluded = 0;
        int configuredScopeExcluded = 0;
        int automaticScopeExcluded = 0;
        for (ResourceEconomicProfile profile : configuredByItem.values()) {
            Item item = itemForId(profile.resourceId());
            boolean matchesTag = item != null && matchesAnyTag(item, populationTags);
            if (settings.allowsConfiguredProfile(matchesTag)) {
                population.add(profile);
            } else {
                configuredScopeExcluded++;
            }
        }
        if (settings.enabled()) {
            java.util.Set<String> candidates = new java.util.TreeSet<>(graph.resultItemIds());
            LootTableAcquisitionAnalyzer lootAnalyzer = lootTableAcquisitionAnalyzer;
            if (lootAnalyzer != null) {
                candidates.addAll(lootAnalyzer.indexedItemIds());
            }
            WorldgenAcquisitionAnalyzer worldgenAnalyzer = worldgenAcquisitionAnalyzer;
            if (worldgenAnalyzer != null) {
                candidates.addAll(worldgenAnalyzer.indexedItemIds());
            }
            candidates.addAll(villagerTradeAcquisitionAnalyzer.indexedItemIds());
            for (String itemId : settings.allowsAutomaticDiscovery() ? candidates : java.util.Set.<String>of()) {
                if (configuredByItem.containsKey(itemId)) {
                    continue;
                }
                Item item = itemForId(itemId);
                if (item == null || !isFoodPopulationCandidate(
                    new ItemStack(item).get(DataComponents.FOOD) != null, hasConfiguredFoodTag(item))) {
                    continue;
                }
                foodItems++;
                if (!settings.allowsAutomaticCandidate(matchesAnyTag(item, populationTags))) {
                    automaticScopeExcluded++;
                    continue;
                }
                List<AcquisitionPath> discoveredPaths = acquisitionPaths(itemId);
                if (discoveredPaths.isEmpty()) {
                    sourceExcluded++;
                    continue;
                }
                if (!hasIndependentAcquisitionPath(discoveredPaths)) {
                    derivedExcluded++;
                    continue;
                }
                EconomicCostResolution resolution = economicCostResolution(itemId);
                if (resolution.status() != ResolutionStatus.COMPLETE || resolution.economicCost() == null) {
                    unknownCostExcluded++;
                    continue;
                }
                ResourceEconomicProfile profile = new ResourceEconomicProfile(itemId, itemId,
                    resolution.economicCost(), resolution.confidence(), 1.0D, true, true,
                    SurvivalAcquirability.TRUE, false, false);
                population.add(profile);
                allProfiles.put(itemId, profile);
                automaticallyIncluded++;
            }
        }

        rebuildCalibration(population, settings);
        economicProfiles = Map.copyOf(allProfiles);
        economicResolutionCache.clear();
        acquisitionPathCache.clear();
        Map<String, Integer> sourceCounts = lootTableAcquisitionAnalyzer == null
            ? new HashMap<>() : new HashMap<>(lootTableAcquisitionAnalyzer.sourceCounts());
        if (worldgenAcquisitionAnalyzer != null) {
            sourceCounts.putAll(worldgenAcquisitionAnalyzer.sourceCounts());
        }
        sourceCounts.putAll(villagerTradeAcquisitionAnalyzer.sourceCounts());
        calibrationDiscoveryDiagnostics = List.of(
            "Population scope: " + settings.populationScope()
                + (settings.populationScope().equals("configured_tag")
                    ? "; configured item tags: " + settings.populationTags() : ""),
            "Discovery scope: registered items with vanilla FOOD components and indexed standard recipe, loot, biome/placed-feature worldgen, and fixed-output villager-trade data",
            "Configured profiles: " + configuredByItem.size(),
            "Configured profiles excluded by population scope: " + configuredScopeExcluded,
            "Edible registry items considered for automatic discovery: " + foodItems,
            "Automatic candidates excluded by population scope: " + automaticScopeExcluded,
            "Automatically discovered terminal profiles: " + automaticallyIncluded,
            "Excluded derived recipe outputs: " + derivedExcluded,
            "Excluded without indexed acquisition paths: " + sourceExcluded,
            "Excluded with partial/unknown economic cost: " + unknownCostExcluded,
            "Identity grouping: exact item registry ID; no equivalence inferred without explicit configuration",
            "Indexed acquisition source entries by category: " + new java.util.TreeMap<>(sourceCounts),
            "Villager trade listings left unindexed because outputs are not safely observable: "
                + villagerTradeAcquisitionAnalyzer.unindexedListingCount()
        );
    }

    private static List<TagKey<Item>> parseCalibrationPopulationTags(List<String> tagIds) {
        List<TagKey<Item>> tags = new ArrayList<>();
        for (String tagId : tagIds) {
            ResourceLocation location = ResourceLocation.tryParse(tagId);
            if (location == null) {
                DynamicFood.LOGGER.warn("Ignoring invalid food calibration population item tag: {}", tagId);
                continue;
            }
            tags.add(TagKey.create(Registries.ITEM, location));
        }
        return List.copyOf(tags);
    }

    private static Item itemForId(String itemId) {
        ResourceLocation location = ResourceLocation.tryParse(itemId);
        return location == null ? null : BuiltInRegistries.ITEM.getOptional(location).orElse(null);
    }

    private static boolean matchesAnyTag(Item item, List<TagKey<Item>> tags) {
        if (tags.isEmpty()) {
            return false;
        }
        ItemStack stack = new ItemStack(item);
        return tags.stream().anyMatch(stack::is);
    }

    static boolean hasIndependentAcquisitionPath(List<AcquisitionPath> paths) {
        return paths.stream().anyMatch(path -> !path.sourceType().equals("recipe"));
    }

    static boolean isFoodPopulationCandidate(boolean hasVanillaFood, boolean hasConfiguredFoodTag) {
        return hasVanillaFood || hasConfiguredFoodTag;
    }

    private static boolean hasConfiguredFoodTag(Item item) {
        ItemStack stack = new ItemStack(item);
        return DynamicFoodConfig.strings(DynamicFoodConfig.FOOD_COMPONENT_TAGS, List.of()).stream()
            .map(ResourceLocation::tryParse)
            .filter(java.util.Objects::nonNull)
            .map(location -> TagKey.create(Registries.ITEM, location))
            .anyMatch(stack::is);
    }

    public List<String> calibrationDiscoveryDiagnostics() {
        return calibrationDiscoveryDiagnostics;
    }

    public Optional<CalibratedBaseFoodValue> calibratedBaseFoodValue(String itemId) {
        FoodCalibrationSettings settings = calibrationSettings;
        if (!settings.enabled()) {
            if (settings.disabledMode().equals("configured_fallback")) {
                return Optional.of(new CalibratedBaseFoodValue(settings.configuredFallbackNutrition(),
                    settings.configuredFallbackSaturation(), 0.0D, 0.0D, 0.0D,
                    CalibrationStatus.EMPTY, "configured_disabled_fallback"));
            }
            return Optional.empty();
        }
        CalibrationSnapshot snapshot = calibrationSnapshot;
        ResourceEconomicProfile profile = economicProfiles.get(itemId);
        if (snapshot == null || profile == null) {
            return Optional.empty();
        }
        CalibratedFoodValue calibrated = snapshot.calibratedValues().get(itemId);
        if (calibrated == null) {
            return Optional.empty();
        }
        return Optional.of(new CalibratedBaseFoodValue(
            snapshot.hungerCurve().evaluate(calibrated.foodIndex()),
            snapshot.saturationCurve().evaluate(calibrated.foodIndex()),
            calibrated.foodIndex(), calibrated.economicCost(), calibrated.economicCost() * 5.0D,
            snapshot.status(), "self_calibrated"));
    }

    public Optional<CalibrationSnapshot> calibrationSnapshot() {
        return Optional.ofNullable(calibrationSnapshot);
    }

    public FoodCalibrationSettings calibrationSettings() {
        return calibrationSettings;
    }

    public RecipeEconomicResult recipeEconomicResult(String itemId) {
        RecipeEconomicAnalyzer analyzer = recipeEconomicAnalyzer;
        if (analyzer == null) {
            synchronized (this) {
                analyzer = recipeEconomicAnalyzer;
                if (analyzer == null) {
                    Map<String, Double> terminalCosts = new HashMap<>();
                    economicProfiles.forEach((id, profile) -> {
                        if (profile.economicCost() != null) {
                            terminalCosts.put(id, profile.economicCost());
                        }
                    });
                    analyzer = new RecipeEconomicAnalyzer(graph, terminalCosts,
                        com.jorjik.dynamicfood.config.DynamicFoodConfig.materialCostReference(),
                        com.jorjik.dynamicfood.config.DynamicFoodConfig.materialCostCap());
                    recipeEconomicAnalyzer = analyzer;
                }
            }
        }
        return analyzer.resolve(itemId);
    }

    public List<AcquisitionPath> acquisitionPaths(String itemId) {
        return acquisitionPathCache.computeIfAbsent(itemId, this::buildAcquisitionPaths);
    }

    private List<AcquisitionPath> buildAcquisitionPaths(String itemId) {
        AcquisitionAnalyzer analyzer = recipeGraphAcquisitionAnalyzer;
        if (analyzer == null) {
            synchronized (this) {
                analyzer = recipeGraphAcquisitionAnalyzer;
                if (analyzer == null) {
                    Map<String, Double> terminalCosts = new HashMap<>();
                    economicProfiles.forEach((id, profile) -> {
                        if (profile.economicCost() != null) {
                            terminalCosts.put(id, profile.economicCost());
                        }
                    });
                    analyzer = new RecipeGraphAcquisitionAnalyzer(graph, terminalCosts,
                        com.jorjik.dynamicfood.config.DynamicFoodConfig.materialCostReference(),
                        com.jorjik.dynamicfood.config.DynamicFoodConfig.materialCostCap(),
                        com.jorjik.dynamicfood.config.DynamicFoodConfig.lootAttemptsReference(),
                        com.jorjik.dynamicfood.config.DynamicFoodConfig.lootAttemptsCap(),
                        com.jorjik.dynamicfood.config.DynamicFoodConfig.timeCostReferenceTicks(),
                        com.jorjik.dynamicfood.config.DynamicFoodConfig.timeCostCapTicks());
                    recipeGraphAcquisitionAnalyzer = analyzer;
                }
            }
        }
        ArrayList<AcquisitionPath> paths = new ArrayList<>(analyzer.analyze(itemId));
        LootTableAcquisitionAnalyzer lootAnalyzer = lootTableAcquisitionAnalyzer;
        if (lootAnalyzer != null) {
            paths.addAll(lootAnalyzer.analyze(itemId));
        }
        WorldgenAcquisitionAnalyzer worldgenAnalyzer = worldgenAcquisitionAnalyzer;
        if (worldgenAnalyzer != null) {
            paths.addAll(worldgenAnalyzer.analyze(itemId));
        }
        paths.addAll(villagerTradeAcquisitionAnalyzer.analyze(itemId));
        for (AcquisitionAnalyzer additional : acquisitionAnalyzers) {
            if (additional.supports(itemId)) {
                paths.addAll(additional.analyze(itemId));
            }
        }
        return paths.stream().sorted(java.util.Comparator.comparing(AcquisitionPath::sourceId)).toList();
    }

    public ResourceDifficulty resourceDifficulty(String itemId) {
        return resourceDifficulty(itemId, DynamicFoodConfig.itemDifficultyOverride(itemId));
    }

    ResourceDifficulty resourceDifficulty(String itemId, java.util.OptionalDouble override) {
        EconomicCostResolution resolution = economicCostResolution(itemId);
        List<AcquisitionPathDiagnostic> diagnostics = acquisitionPaths(itemId).stream().map(path -> {
            FeasibilityResult feasibility = FeasibilityResolver.resolve(path,
                DynamicFoodConfig.feasibilityFactorWeights(),
                DynamicFoodConfig.minimumFeasibilityCoverage(),
                DynamicFoodConfig.minimumFeasibility(),
                DynamicFoodConfig.allowPartialFeasibility());
            CostVector vector = path.costsByHorizon().get(resolution.economicHorizon());
            AcquisitionCost cost = vector == null
                ? new AcquisitionCost(null, ResolutionStatus.UNKNOWN, resolution.economicHorizon(),
                    Map.of(), Map.of("cost_vector", "horizon is unavailable for this path"))
                : AcquisitionCostResolver.resolve(vector, DynamicFoodConfig.costFactorWeights());
            return new AcquisitionPathDiagnostic(path, feasibility, cost);
        }).toList();
        Double score = override.isPresent() ? Double.valueOf(override.getAsDouble()) : resolution.difficulty();
        List<String> reasons = new ArrayList<>(resolution.reasons());
        RecipeEconomicResult recipeEconomics = recipeEconomicResult(itemId);
        reasons.addAll(recipeEconomics.detectedCycles());
        reasons.addAll(recipeEconomics.missingInputs());
        graph.valueIncreasingEconomicCyclesFor(itemId).stream()
            .forEach(cycle -> reasons.add("invalid economic duplication cycle: " + cycle));
        if (diagnostics.isEmpty()) {
            reasons.add("unknown acquisition source");
        }
        if (override.isPresent()) {
            reasons.add("manual item difficulty override is active");
        }
        return new ResourceDifficulty(itemId, score, resolution.confidence(), resolution, diagnostics,
            override.isPresent(), override.isPresent() ? override.getAsDouble() : null, reasons);
    }

    public EconomicCostResolution economicCostResolution(String itemId) {
        EconomicCostResolution cached = economicResolutionCache.get(itemId);
        if (cached != null) {
            return cached;
        }
        EconomicCostResolution resolved = EconomicCostResolver.resolve(itemId, acquisitionPaths(itemId),
            DynamicFoodConfig.acquisitionEconomicHorizon(),
            DynamicFoodConfig.acquisitionStrategy(),
            DynamicFoodConfig.feasibilityFactorWeights(),
            DynamicFoodConfig.costFactorWeights(),
            DynamicFoodConfig.minimumFeasibility(),
            DynamicFoodConfig.minimumFeasibilityCoverage(),
            DynamicFoodConfig.allowPartialFeasibility(),
            DynamicFoodConfig.allowPartialCost());
        return economicResolutionCache.computeIfAbsent(itemId, ignored -> resolved);
    }

    public void registerAcquisitionAnalyzer(AcquisitionAnalyzer analyzer) {
        acquisitionAnalyzers.add(java.util.Objects.requireNonNull(analyzer, "analyzer"));
        economicResolutionCache.clear();
        acquisitionPathCache.clear();
    }

    public synchronized void rebuildLootTableAnalyzer(net.minecraft.server.packs.resources.ResourceManager resources) {
        lootTableAcquisitionAnalyzer = LootTableAcquisitionAnalyzer.fromResourceManager(resources,
            com.jorjik.dynamicfood.config.DynamicFoodConfig.lootAttemptsReference(),
            com.jorjik.dynamicfood.config.DynamicFoodConfig.lootAttemptsCap());
        worldgenAcquisitionAnalyzer = WorldgenAcquisitionAnalyzer.fromResourceManager(resources);
        economicResolutionCache.clear();
        acquisitionPathCache.clear();
    }

    public synchronized void rebuildLootTableAnalyzer(LootTableAcquisitionAnalyzer analyzer,
        double attemptsReference, double attemptsCap) {
        if (analyzer == null) {
            throw new IllegalArgumentException("loot table analyzer is required");
        }
        if (!Double.isFinite(attemptsReference) || attemptsReference <= 0.0D
            || !Double.isFinite(attemptsCap) || attemptsCap <= 0.0D) {
            throw new IllegalArgumentException("loot normalization values must be finite and positive");
        }
        lootTableAcquisitionAnalyzer = analyzer.withNormalization(attemptsReference, attemptsCap);
        economicResolutionCache.clear();
        acquisitionPathCache.clear();
    }

    public synchronized void rebuildWorldgenAnalyzer(WorldgenAcquisitionAnalyzer analyzer) {
        if (analyzer == null) {
            throw new IllegalArgumentException("worldgen analyzer is required");
        }
        worldgenAcquisitionAnalyzer = analyzer;
        economicResolutionCache.clear();
        acquisitionPathCache.clear();
    }

    public synchronized void replaceVillagerTradeListings(String professionId,
        Map<Integer, List<ItemListing>> trades) {
        villagerTradeAcquisitionAnalyzer =
            villagerTradeAcquisitionAnalyzer.withProfession(professionId, trades);
        economicResolutionCache.clear();
        acquisitionPathCache.clear();
    }

    public List<String> valueIncreasingEconomicCycles() {
        return graph.valueIncreasingEconomicCycles();
    }

    public List<String> recipeTree(String itemId) {
        return graph.describeTree(itemId);
    }
}
