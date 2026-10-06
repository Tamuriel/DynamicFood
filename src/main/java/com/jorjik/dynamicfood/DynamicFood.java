package com.jorjik.dynamicfood;

import com.mojang.logging.LogUtils;
import com.jorjik.dynamicfood.command.DynamicFoodCommand;
import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import com.jorjik.dynamicfood.core.DynamicFoodEngine;
import com.jorjik.dynamicfood.core.EconomicGenerationBuildService;
import com.jorjik.dynamicfood.core.EconomicGenerationPublisher;
import com.jorjik.dynamicfood.core.EconomicSnapshotAudit;
import com.jorjik.dynamicfood.core.FoodCalibrationSettings;
import com.jorjik.dynamicfood.core.ResourceEconomicProfile;
import com.jorjik.dynamicfood.data.DynamicFoodDataComponents;
import com.jorjik.dynamicfood.graph.RecipeGraphReloadListener;
import com.jorjik.dynamicfood.provenance.CraftingProvenanceHandler;
import com.jorjik.dynamicfood.provenance.RawFoodStackInitializer;
import com.jorjik.dynamicfood.provenance.RecipeTransactionHandler;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;
import org.slf4j.Logger;

@Mod(DynamicFood.MOD_ID)
public class DynamicFood {
    public static final String MOD_ID = "dynamicfood";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DynamicFoodEngine ENGINE = new DynamicFoodEngine();
    public static final EconomicGenerationPublisher ECONOMIC_GENERATIONS = new EconomicGenerationPublisher();
    private final EconomicGenerationBuildService economicGenerationBuildService =
        new EconomicGenerationBuildService();
    private MinecraftServer activeServer;
    private boolean serverStarted;
    private String publishedEconomicConfigurationSignature;
    private FoodCalibrationSettings publishedCalibrationSettings;

    public DynamicFood(IEventBus modEventBus, ModContainer container) {
        DynamicFoodConfig.register(container);
        DynamicFoodDataComponents.REGISTRAR.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(DynamicFoodCommand::register);
        NeoForge.EVENT_BUS.addListener(new CraftingProvenanceHandler(ECONOMIC_GENERATIONS)::onItemCrafted);
        NeoForge.EVENT_BUS.addListener(RawFoodStackInitializer::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(RawFoodStackInitializer::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(new RecipeTransactionHandler(ECONOMIC_GENERATIONS)::onTransaction);
        NeoForge.EVENT_BUS.addListener(this::onReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onVillagerTrades);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        modEventBus.addListener(this::onConfigReloaded);
    }

    private void onReloadListeners(AddReloadListenerEvent event) {
        RecipeGraphReloadListener.register(event, ENGINE,
            () -> onEconomicInputsChanged("recipe/datapack reload"));
    }

    private void onVillagerTrades(VillagerTradesEvent event) {
        var professionId = BuiltInRegistries.VILLAGER_PROFESSION.getKey(event.getType());
        if (professionId == null) {
            LOGGER.warn("Ignoring villager trade listings for an unregistered profession");
            return;
        }
        ENGINE.replaceVillagerTradeListings(professionId.toString(), event.getTrades());
        onEconomicInputsChanged("villager trade definition update");
    }

    private void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (!MOD_ID.equals(event.getConfig().getModId())) {
            return;
        }
        ENGINE.invalidate();
        if (!serverStarted) {
            return;
        }

        Collection<ResourceEconomicProfile> profiles = DynamicFoodConfig.economicProfiles();
        FoodCalibrationSettings settings = DynamicFoodConfig.calibrationSettings();
        String currentEconomicConfiguration = economicConfigurationSignature(profiles);
        if (!Objects.equals(publishedEconomicConfigurationSignature, currentEconomicConfiguration)) {
            rebuildAndPublish("economic configuration reload", profiles, settings, false);
        } else if (!Objects.equals(publishedCalibrationSettings, settings)) {
            rebuildCalibrationAndPublish(settings, profiles);
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (activeServer != null && activeServer != server) {
            LOGGER.warn("A new Minecraft server started before the previous DynamicFood server lifecycle ended; "
                + "discarding the previous current generation");
            ECONOMIC_GENERATIONS.clearCurrent();
            ENGINE.invalidate();
            ENGINE.markStaticAcquisitionInputsNotReady();
            clearPublishedConfigurationIdentity();
        }
        activeServer = server;
        serverStarted = true;
        if (ECONOMIC_GENERATIONS.current().isPresent()) {
            return;
        }
        if (!ENGINE.staticAcquisitionInputsReady()) {
            throw new IllegalStateException(
                "DynamicFood static acquisition inputs were not prepared before server start");
        }
        rebuildAndPublish("initial server startup", DynamicFoodConfig.economicProfiles(),
            DynamicFoodConfig.calibrationSettings(), true);
    }

    private void onServerStopped(ServerStoppedEvent event) {
        if (activeServer != event.getServer()) {
            return;
        }
        ECONOMIC_GENERATIONS.clearCurrent();
        ENGINE.invalidate();
        ENGINE.markStaticAcquisitionInputsNotReady();
        activeServer = null;
        serverStarted = false;
        clearPublishedConfigurationIdentity();
    }

    private void onEconomicInputsChanged(String reason) {
        if (!serverStarted || !ENGINE.staticAcquisitionInputsReady()) {
            return;
        }
        rebuildAndPublish(reason, DynamicFoodConfig.economicProfiles(),
            DynamicFoodConfig.calibrationSettings(), false);
    }

    private boolean rebuildAndPublish(
        String reason,
        Collection<ResourceEconomicProfile> profiles,
        FoodCalibrationSettings settings,
        boolean initialPublication
    ) {
        try {
            if (activeServer != null) {
                ENGINE.updateActiveWorldgenDimensionBiomes(activeWorldgenDimensionBiomes(activeServer));
            }
            var generation = economicGenerationBuildService.rebuildAndPublish(
                ENGINE, ECONOMIC_GENERATIONS, profiles, settings);
            rememberPublishedConfiguration(profiles, settings);
            logPublishedGeneration(reason, generation);
            return true;
        } catch (RuntimeException exception) {
            var current = ECONOMIC_GENERATIONS.current();
            if (current.isPresent()) {
                LOGGER.error("Failed to rebuild DynamicFood economic generation after {}; keeping last valid "
                    + "generation {} (signature {})", reason, current.get().generation(),
                    current.get().economicContentSignature(), exception);
                return false;
            }
            LOGGER.error("Failed to build the initial DynamicFood economic generation after {}", reason, exception);
            if (initialPublication) {
                throw new IllegalStateException(
                    "DynamicFood initial economic generation publication failed", exception);
            }
            return false;
        }
    }

    private static Map<String, Set<String>> activeWorldgenDimensionBiomes(MinecraftServer server) {
        Map<String, Set<String>> biomesByDimension = new HashMap<>();
        for (var level : server.getAllLevels()) {
            Set<String> biomeIds = new TreeSet<>();
            level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().forEach(biome ->
                biome.unwrapKey().ifPresent(key -> biomeIds.add(key.location().toString())));
            biomesByDimension.put(level.dimension().location().toString(), Set.copyOf(biomeIds));
        }
        return Map.copyOf(biomesByDimension);
    }

    private void rebuildCalibrationAndPublish(
        FoodCalibrationSettings settings,
        Collection<ResourceEconomicProfile> profiles
    ) {
        try {
            var generation = economicGenerationBuildService.rebuildCalibrationAndPublish(
                ECONOMIC_GENERATIONS, profiles, settings);
            rememberPublishedConfiguration(profiles, settings);
            logPublishedGeneration("calibration configuration reload", generation);
        } catch (RuntimeException exception) {
            var current = ECONOMIC_GENERATIONS.current();
            if (current.isPresent()) {
                LOGGER.error("Failed to rebuild DynamicFood calibration snapshot; keeping last valid generation {} "
                    + "and calibration", current.get().generation(), exception);
                return;
            }
            LOGGER.error("Failed to publish DynamicFood calibration because there is no current economic generation",
                exception);
        }
    }

    private void rememberPublishedConfiguration(
        Collection<ResourceEconomicProfile> profiles,
        FoodCalibrationSettings settings
    ) {
        publishedEconomicConfigurationSignature = economicConfigurationSignature(profiles);
        publishedCalibrationSettings = settings;
    }

    private void clearPublishedConfigurationIdentity() {
        publishedEconomicConfigurationSignature = null;
        publishedCalibrationSettings = null;
    }

    private static String economicConfigurationSignature(Collection<ResourceEconomicProfile> profiles) {
        StringBuilder signature = new StringBuilder(DynamicFoodConfig.economicPolicySignature());
        profiles.stream()
            .sorted(Comparator.comparing(ResourceEconomicProfile::resourceId)
                .thenComparing(ResourceEconomicProfile::toString))
            .forEach(profile -> {
                String value = profile.toString();
                signature.append('|').append(value.length()).append(':').append(value);
            });
        return signature.toString();
    }

    private static void logPublishedGeneration(
        String reason,
        com.jorjik.dynamicfood.core.PublishedEconomicGeneration generation
    ) {
        var audit = EconomicSnapshotAudit.inspect(generation);
        LOGGER.info("Published DynamicFood economic generation {} after {} (signature {}; {}; calibration_status={})",
            generation.generation(), reason, generation.economicContentSignature(), audit.countsSummary(),
            generation.calibrationSnapshot().status());
        LOGGER.info("DynamicFood economic generation diagnostics: {}", audit.diagnosticsSummary());
    }
}
