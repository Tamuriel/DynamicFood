package com.jorjik.dynamicfood;

import com.mojang.logging.LogUtils;
import com.jorjik.dynamicfood.command.DynamicFoodCommand;
import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import com.jorjik.dynamicfood.core.DynamicFoodEngine;
import com.jorjik.dynamicfood.core.EconomicGenerationBuildService;
import com.jorjik.dynamicfood.core.EconomicGenerationPublisher;
import com.jorjik.dynamicfood.core.EconomicSnapshotAudit;
import com.jorjik.dynamicfood.data.DynamicFoodDataComponents;
import com.jorjik.dynamicfood.graph.RecipeGraphReloadListener;
import com.jorjik.dynamicfood.provenance.CraftingProvenanceHandler;
import com.jorjik.dynamicfood.provenance.RawFoodStackInitializer;
import com.jorjik.dynamicfood.provenance.RecipeTransactionHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

@Mod(DynamicFood.MOD_ID)
public class DynamicFood {
    public static final String MOD_ID = "dynamicfood";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DynamicFoodEngine ENGINE = new DynamicFoodEngine();
    public static final EconomicGenerationPublisher ECONOMIC_GENERATIONS = new EconomicGenerationPublisher();
    private final EconomicGenerationBuildService economicGenerationBuildService =
        new EconomicGenerationBuildService();

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
        modEventBus.addListener(this::onConfigReloaded);
    }

    private void onReloadListeners(AddReloadListenerEvent event) {
        RecipeGraphReloadListener.register(event, ENGINE);
    }

    private void onVillagerTrades(VillagerTradesEvent event) {
        var professionId = BuiltInRegistries.VILLAGER_PROFESSION.getKey(event.getType());
        if (professionId == null) {
            LOGGER.warn("Ignoring villager trade listings for an unregistered profession");
            return;
        }
        ENGINE.replaceVillagerTradeListings(professionId.toString(), event.getTrades());
        ENGINE.rebuildCalibrationWithDiscovery(DynamicFoodConfig.economicProfiles(),
            DynamicFoodConfig.calibrationSettings());
    }

    private void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (!MOD_ID.equals(event.getConfig().getModId())) {
            return;
        }
        ENGINE.invalidate();
        ENGINE.rebuildCalibrationWithDiscovery(DynamicFoodConfig.economicProfiles(),
            DynamicFoodConfig.calibrationSettings());
    }

    private void onServerStarted(ServerStartedEvent event) {
        if (ECONOMIC_GENERATIONS.current().isPresent()) {
            return;
        }
        try {
            var generation = economicGenerationBuildService.rebuildAndPublish(ENGINE, ECONOMIC_GENERATIONS,
                DynamicFoodConfig.economicProfiles(), DynamicFoodConfig.calibrationSettings());
            var audit = EconomicSnapshotAudit.inspect(generation);
            LOGGER.info("Published DynamicFood economic generation {} (signature {}; {})",
                generation.generation(), generation.economicContentSignature(),
                audit.countsSummary() + ", calibration_status=" + generation.calibrationSnapshot().status());
            LOGGER.info("DynamicFood economic generation diagnostics: {}", audit.diagnosticsSummary());
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to build and publish the initial DynamicFood economic generation", exception);
            throw new IllegalStateException("DynamicFood initial economic generation publication failed", exception);
        }
    }
}
