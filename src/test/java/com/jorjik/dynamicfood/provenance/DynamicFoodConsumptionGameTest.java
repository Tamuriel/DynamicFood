package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.compat.OptionalTransactionSupport;
import com.jorjik.dynamicfood.core.CalibrationAnchor;
import com.jorjik.dynamicfood.core.FoodCalibrationSettings;
import com.jorjik.dynamicfood.core.DynamicFoodEngine;
import com.jorjik.dynamicfood.core.EconomicFactor;
import com.jorjik.dynamicfood.core.FactorNormalizer;
import com.jorjik.dynamicfood.core.IngredientContribution;
import com.jorjik.dynamicfood.core.ResourceEconomicProfile;
import com.jorjik.dynamicfood.core.SurvivalAcquirability;
import com.jorjik.dynamicfood.core.AcquisitionPath;
import com.jorjik.dynamicfood.core.VillagerTradeAcquisitionAnalyzer;
import net.neoforged.fml.ModList;
import com.jorjik.dynamicfood.data.DynamicFoodDataComponents;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.BasicItemListing;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@GameTestHolder(DynamicFood.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DynamicFoodConsumptionGameTest {
    private DynamicFoodConsumptionGameTest() {}

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void stackFoodComponentDrivesRealConsumption(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FoodProperties built = new FoodProperties.Builder().nutrition(6).saturationModifier(0.75F).build();
        helper.assertTrue(Math.abs(built.saturation() - 9.0F) < 0.001F,
            "builder saturation modifier must be converted to stored effective points");
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getFoodData().setFoodLevel(10);
        player.getFoodData().setSaturation(0.0F);

        ItemStack bread = new ItemStack(Items.BREAD);
        FoodProperties existing = bread.get(DataComponents.FOOD);
        DynamicFoodValue dynamic = new DynamicFoodValue(6.0D, 6, 9.0D, 9.0F,
            2.0D, "gametest:runtime_recipe", 1, List.of());
        bread.set(DynamicFoodDataComponents.VALUE.get(), dynamic);
        bread.set(DataComponents.FOOD, FoodPropertiesUpdater.withDynamicValue(existing, dynamic));

        bread.finishUsingItem(level, player);
        helper.assertTrue(player.getFoodData().getFoodLevel() == 16,
            "Dynamic stack FOOD component must add its nutrition to the player");
        helper.assertTrue(Math.abs(player.getFoodData().getSaturationLevel() - 9.0F) < 0.001F,
            "effective saturation must be applied from the stack FoodProperties modifier");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void sameItemWithDifferentStackValuesProducesDifferentConsumption(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        ItemStack lowValueBread = new ItemStack(Items.BREAD);
        DynamicFoodValue lowValue = new DynamicFoodValue(2.0D, 2, 0.5D, 0.5F,
            1.0D, "gametest:low_value_recipe", 1, List.of());
        lowValueBread.set(DynamicFoodDataComponents.VALUE.get(), lowValue);
        lowValueBread.set(DataComponents.FOOD,
            FoodPropertiesUpdater.withDynamicValue(lowValueBread.get(DataComponents.FOOD), lowValue));

        player.getFoodData().setFoodLevel(10);
        player.getFoodData().setSaturation(0.0F);
        lowValueBread.finishUsingItem(level, player);
        helper.assertTrue(player.getFoodData().getFoodLevel() == 12
            && Math.abs(player.getFoodData().getSaturationLevel() - 0.5F) < 0.001F,
            "the first bread stack must apply its own nutrition and effective saturation snapshot");

        ItemStack highValueBread = new ItemStack(Items.BREAD);
        DynamicFoodValue highValue = new DynamicFoodValue(7.0D, 7, 4.0D, 4.0F,
            3.0D, "gametest:high_value_recipe", 1, List.of());
        highValueBread.set(DynamicFoodDataComponents.VALUE.get(), highValue);
        highValueBread.set(DataComponents.FOOD,
            FoodPropertiesUpdater.withDynamicValue(highValueBread.get(DataComponents.FOOD), highValue));

        player.getFoodData().setFoodLevel(10);
        player.getFoodData().setSaturation(0.0F);
        highValueBread.finishUsingItem(level, player);
        helper.assertTrue(player.getFoodData().getFoodLevel() == 17
            && Math.abs(player.getFoodData().getSaturationLevel() - 4.0F) < 0.001F,
            "the same Item ID with different provenance must apply its own distinct food snapshot");
        helper.assertTrue(!lowValue.sourceRecipe().equals(highValue.sourceRecipe()),
            "the test stacks must represent distinct recipe provenance");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void stackFoodPropertiesPreserveVanillaEffectsAndContainer(GameTestHelper helper) {
        FoodProperties original = new FoodProperties(4, 0.5F, true, 1.6F,
            Optional.of(new ItemStack(Items.BOWL)), List.of());
        DynamicFoodValue dynamic = new DynamicFoodValue(6.0D, 6, 9.0D, 9.0F,
            2.0D, "gametest:runtime_recipe", 1, List.of());
        FoodProperties updated = FoodPropertiesUpdater.withDynamicValue(original, dynamic);

        helper.assertTrue(updated.nutrition() == 6 && updated.canAlwaysEat(),
            "dynamic values must update nutrition and preserve always-edible state");
        helper.assertTrue(Math.abs(updated.eatSeconds() - 1.6F) < 0.001F,
            "dynamic values must preserve eat duration");
        helper.assertTrue(updated.usingConvertsTo().orElseThrow().is(Items.BOWL),
            "dynamic values must preserve container conversion");

        FoodProperties goldenApple = new ItemStack(Items.GOLDEN_APPLE).get(DataComponents.FOOD);
        FoodProperties updatedGoldenApple = FoodPropertiesUpdater.withDynamicValue(goldenApple, dynamic);
        helper.assertTrue(!goldenApple.effects().isEmpty()
            && updatedGoldenApple.effects().size() == goldenApple.effects().size(),
            "dynamic values must preserve vanilla food effects");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void foodPropertiesUseEffectiveSaturationPointsAtMinecraftBoundary(GameTestHelper helper) {
        float modifier = 0.375F;
        for (int nutrition : List.of(0, 1, 5, 10)) {
            FoodProperties built = new FoodProperties.Builder()
                .nutrition(nutrition)
                .saturationModifier(modifier)
                .build();
            double expected = 2.0D * nutrition * modifier;
            helper.assertTrue(Math.abs(built.saturation() - expected) < 0.0001D,
                "Minecraft builder must store effective saturation points for nutrition " + nutrition);
        }

        FoodProperties base = new FoodProperties.Builder().nutrition(5).saturationModifier(0.2F).build();
        DynamicFoodValue highEffectiveSaturation = new DynamicFoodValue(5.0D, 5, 37.25D, 37.25F,
            1.0D, "gametest:saturation_boundary", 1, List.of());
        FoodProperties updated = FoodPropertiesUpdater.withDynamicValue(base, highEffectiveSaturation);
        helper.assertTrue(Math.abs(updated.saturation() - 37.25F) < 0.0001F,
            "FoodProperties updater must write canonical effective points without converting them twice");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void rawCalibratedFoodGetsStackSnapshotBeforeConsumption(GameTestHelper helper) {
        DynamicFood.ENGINE.rebuildCalibration(List.of(new ResourceEconomicProfile(
            "minecraft:bread", "minecraft:bread", 0.5D, 1.0D, 1.0D,
            true, true, SurvivalAcquirability.TRUE, false, false)),
            new FoodCalibrationSettings(true, "vanilla", 1, 0.70D, 0.30D,
                0.05D, 0.95D, 0.50D, 0.80D, "medium",
                List.of(new CalibrationAnchor(0.0D, 6.0D), new CalibrationAnchor(1.0D, 6.0D)),
                List.of(new CalibrationAnchor(0.0D, 9.0D), new CalibrationAnchor(1.0D, 9.0D)),
                2.0D, 0.0D));

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getFoodData().setFoodLevel(10);
        player.getFoodData().setSaturation(0.0F);
        ItemStack bread = new ItemStack(Items.BREAD);
        player.setItemInHand(InteractionHand.MAIN_HAND, bread);

        RawFoodStackInitializer.onRightClickItem(new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND));
        helper.assertTrue(bread.has(DynamicFoodDataComponents.VALUE.get()),
            "raw calibrated food must receive a per-stack DynamicFoodValue snapshot");
        helper.assertTrue(bread.get(DataComponents.FOOD).nutrition() == 6
            && Math.abs(bread.get(DataComponents.FOOD).saturation() - 9.0F) < 0.001F,
            "raw stack FOOD must contain calibrated nutrition and stored effective saturation");

        bread.finishUsingItem(helper.getLevel(), player);
        helper.assertTrue(player.getFoodData().getFoodLevel() == 16,
            "raw calibrated stack must apply its nutrition when consumed");
        helper.assertTrue(Math.abs(player.getFoodData().getSaturationLevel() - 9.0F) < 0.001F,
            "raw calibrated stack must apply effective saturation when consumed");
        helper.succeed();
    }

    @GameTest(timeoutTicks = 260, template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void vanillaFurnaceAndSmokerApplySnapshotsToCookedOutputs(GameTestHelper helper) {
        BlockPos furnacePos = new BlockPos(1, 1, 1);
        BlockPos smokerPos = new BlockPos(3, 1, 1);
        helper.setBlock(furnacePos, Blocks.FURNACE);
        helper.setBlock(smokerPos, Blocks.SMOKER);
        AbstractFurnaceBlockEntity furnace = helper.getBlockEntity(furnacePos);
        AbstractFurnaceBlockEntity smoker = helper.getBlockEntity(smokerPos);
        furnace.setItem(0, new ItemStack(Items.COD));
        furnace.setItem(1, new ItemStack(Items.COAL));
        smoker.setItem(0, new ItemStack(Items.COD));
        smoker.setItem(1, new ItemStack(Items.COAL));

        helper.runAtTickTime(220, () -> {
            ItemStack furnaceOutput = furnace.getItem(2);
            ItemStack smokerOutput = smoker.getItem(2);
            helper.assertTrue(furnaceOutput.is(Items.COOKED_COD),
                "the real furnace must complete the vanilla cod smelting recipe");
            helper.assertTrue(smokerOutput.is(Items.COOKED_COD),
                "the real smoker must complete the vanilla cod smoking recipe");
            helper.assertTrue(furnaceOutput.has(DynamicFoodDataComponents.VALUE.get())
                && smokerOutput.has(DynamicFoodDataComponents.VALUE.get()),
                "both real cooking outputs must carry their runtime DynamicFoodValue snapshots");
            helper.succeed();
        });
    }

    @GameTest(timeoutTicks = 10, template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void vanillaCampfireAppliesSnapshotToCookedOutput(GameTestHelper helper) {
        BlockPos campfirePos = new BlockPos(1, 1, 1);
        helper.setBlock(campfirePos,
            Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        CampfireBlockEntity campfire = helper.getBlockEntity(campfirePos);
        helper.assertTrue(campfire.placeFood(null, new ItemStack(Items.COD), 1),
            "the real campfire must accept the cod input");

        helper.runAtTickTime(2, () -> {
            ItemEntity cookedOutput = helper.getEntities(EntityType.ITEM, campfirePos, 3.0D).stream()
                .filter(entity -> entity.getItem().is(Items.COOKED_COD))
                .findFirst()
                .orElse(null);
            helper.assertTrue(cookedOutput != null,
                "the real campfire must emit the vanilla cooked-cod recipe output");
            helper.assertTrue(cookedOutput.getItem().has(DynamicFoodDataComponents.VALUE.get()),
                "the actual campfire output must carry its runtime DynamicFoodValue snapshot");
            helper.succeed();
        });
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void recipeOperationStationDifficultyReachesResolver(GameTestHelper helper) {
        RecipeOperation operation = new RecipeOperation("gametest:mixing", "create:mixing", "test:machine", 5,
            List.of(new RecipeOperation.ItemInput(new ItemStack(Items.WHEAT), 1)), List.of(),
            List.of(new RecipeOperation.Output(new ItemStack(Items.BREAD), null)), Map.of());
        RuntimeProvenance provenance = RuntimeProvenance.fromOperation(operation, 1,
            List.of(new IngredientContribution("minecraft:wheat", 2.0D, 0.4D, 1, true, "gametest:raw", 1.0D)));
        var result = DynamicFood.ENGINE.resolve(provenance);

        helper.assertTrue(provenance.stationDifficulty() == 5,
            "RecipeOperation station difficulty must survive provenance conversion");
        helper.assertTrue(result.rawNutrition() > 2.0D,
            "operation station difficulty must contribute inside the shared processing budget");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void recipeOperationUsesDefaultOutputAllocationWeight(GameTestHelper helper) {
        ItemStack bread = new ItemStack(Items.BREAD);
        RecipeOperation operation = new RecipeOperation("gametest:default_weight", "minecraft:crafting",
            "test:crafting", 0,
            List.of(new RecipeOperation.ItemInput(new ItemStack(Items.WHEAT), 1)), List.of(),
            List.of(new RecipeOperation.Output(bread, null)), Map.of());

        RuntimeFoodApplier.applyOperation(operation);

        helper.assertTrue(bread.has(DynamicFoodDataComponents.VALUE.get()),
            "an output without an explicit allocation weight must use the quantity default");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void legacyTransactionAllocatesOneOperationAcrossFoodOutputs(GameTestHelper helper) {
        ItemStack input = new ItemStack(Items.WHEAT);
        DynamicFoodValue inputValue = new DynamicFoodValue(5.0D, 5, 2.0D, 2.0F,
            1.0D, "gametest:legacy_input", 1, List.of());
        input.set(DynamicFoodDataComponents.VALUE.get(), inputValue);
        ItemStack firstFood = new ItemStack(Items.BREAD);
        ItemStack secondFood = new ItemStack(Items.COOKED_BEEF);
        ItemStack technicalOutput = new ItemStack(Items.BOWL);
        List<ItemStack> outputs = List.of(firstFood, secondFood, technicalOutput);
        RuntimeProvenance provenance = RuntimeProvenance.fromStacks("minecraft:bread",
            "gametest:legacy_multi_output", "minecraft:crafting", 2, List.of(input));
        var operationValue = DynamicFood.ENGINE.resolve(provenance);

        new RecipeTransactionHandler().onTransaction(new RecipeTransactionEvent(
            outputs, "gametest:legacy_multi_output", "minecraft:crafting", List.of(input)));

        DynamicFoodValue firstValue = firstFood.get(DynamicFoodDataComponents.VALUE.get());
        DynamicFoodValue secondValue = secondFood.get(DynamicFoodDataComponents.VALUE.get());
        helper.assertTrue(firstValue != null && secondValue != null
            && technicalOutput.get(DynamicFoodDataComponents.VALUE.get()) == null,
            "legacy transactions must decorate only food outputs");
        double allocatedNutrition = firstValue.rawNutrition() * firstFood.getCount()
            + secondValue.rawNutrition() * secondFood.getCount();
        double allocatedSaturation = firstValue.rawSaturation() * firstFood.getCount()
            + secondValue.rawSaturation() * secondFood.getCount();
        helper.assertTrue(Math.abs(allocatedNutrition - operationValue.rawNutrition() * operationValue.outputCount())
            < 0.0001D
            && Math.abs(allocatedSaturation - operationValue.rawSaturation() * operationValue.outputCount())
                < 0.0001D,
            "the sum of legacy food output values must equal one operation total, not duplicate it");
        helper.assertTrue(Math.abs(firstValue.operationSnapshot().orElseThrow().allocationShare()
                + secondValue.operationSnapshot().orElseThrow().allocationShare() - 1.0D) < 0.0001D,
            "legacy output allocation shares must sum to one");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void dynamicFoodComponentSurvivesCopySplitAndWorldSerialization(GameTestHelper helper) {
        ItemStack original = new ItemStack(Items.BREAD, 2);
        DynamicFoodValue value = new DynamicFoodValue(3.5D, 4, 1.25D, 1.25F,
            2.0D, "gametest:provenance", 2,
            List.of(new IngredientFoodSnapshot("minecraft:wheat", 3.5D, 1.25D, 1, true, "gametest:raw", 1.0D)));
        original.set(DynamicFoodDataComponents.VALUE.get(), value);

        ItemStack copy = original.copy();
        ItemStack split = original.split(1);
        CompoundTag saved = (CompoundTag) copy.save(helper.getLevel().registryAccess());
        ItemStack loaded = ItemStack.parseOptional(helper.getLevel().registryAccess(), saved);
        ItemStack different = new ItemStack(Items.BREAD, 1);
        different.set(DynamicFoodDataComponents.VALUE.get(), new DynamicFoodValue(
            4.0D, 4, 2.0D, 2.0F, 2.0D, "gametest:other", 1, List.of()));

        helper.assertTrue(copy.get(DynamicFoodDataComponents.VALUE.get()).equals(value),
            "copy must preserve DynamicFoodValue");
        helper.assertTrue(split.get(DynamicFoodDataComponents.VALUE.get()).equals(value),
            "split must preserve DynamicFoodValue");
        helper.assertTrue(loaded.get(DynamicFoodDataComponents.VALUE.get()).equals(value),
            "ItemStack save/load must preserve DynamicFoodValue");
        helper.assertTrue(!ItemStack.isSameItemSameComponents(copy, different),
            "different per-stack food values must prevent component-equal stack merging");
        ItemStack sameValue = new ItemStack(Items.BREAD, 1);
        sameValue.set(DynamicFoodDataComponents.VALUE.get(), value);
        helper.assertTrue(ItemStack.isSameItemSameComponents(copy, sameValue),
            "stacks with matching DynamicFoodValue components must remain merge-compatible");
        DynamicFood.ENGINE.rebuildCalibration(List.of(), FoodCalibrationSettings.defaults());
        helper.assertTrue(DynamicFood.ENGINE.resolveOrSnapshot(
            new RuntimeProvenance("minecraft:bread", "gametest:reload", "minecraft:crafting", 1, List.of()),
            value).sourceRecipe().equals(value.sourceRecipe()),
            "an existing per-stack snapshot must remain authoritative after calibration reload");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void reloadIndexesVanillaCropMobFishingAndWorldgenSources(GameTestHelper helper) {
        List<AcquisitionPath> wheat = DynamicFood.ENGINE.acquisitionPaths("minecraft:wheat");
        List<AcquisitionPath> cod = DynamicFood.ENGINE.acquisitionPaths("minecraft:cod");
        List<AcquisitionPath> goldenApple = DynamicFood.ENGINE.acquisitionPaths("minecraft:golden_apple");
        List<AcquisitionPath> coalOre = DynamicFood.ENGINE.acquisitionPaths("minecraft:coal_ore");

        helper.assertTrue(wheat.stream().anyMatch(path -> path.sourceType().equals("crop")),
            "wheat block loot must be indexed as a crop source; observed types="
                + wheat.stream().map(AcquisitionPath::sourceType).distinct().toList()
                + "; discovery=" + DynamicFood.ENGINE.calibrationDiscoveryDiagnostics());
        helper.assertTrue(cod.stream().anyMatch(path -> path.sourceType().equals("mob_drop")),
            "cod entity loot must be indexed as a mob-drop source");
        helper.assertTrue(cod.stream().anyMatch(path -> path.sourceType().equals("fishing")),
            "fishing loot must be indexed as a fishing source");
        helper.assertTrue(goldenApple.stream().anyMatch(path -> path.sourceType().equals("worldgen")),
            "chest loot must be indexed as a worldgen source");
        helper.assertTrue(wheat.stream().filter(path -> path.sourceType().equals("crop"))
            .allMatch(path -> path.evidence().measurements().get("crop_max_age") != null
                && path.evidence().measurements().get("crop_growth_time_ticks") != null
                && path.evidence().measurements().get("crop_seed_return_per_cycle") != null),
            "crop paths must expose version-verified max age and explicit unknown growth/seed-loop measurements");
        helper.assertTrue(coalOre.stream().anyMatch(path -> path.sourceType().equals("worldgen_feature")),
            "vanilla coal ore must retain its discovered worldgen acquisition path");
        helper.assertTrue(coalOre.stream().filter(path -> path.sourceType().equals("worldgen_feature"))
            .allMatch(path -> path.costsByHorizon().get(100).factors().get("quantity_cost").isUnknown()
                && path.costsByHorizon().get(100).factors().get("material_cost").isNotApplicable()
                && path.costsByHorizon().get(100).factors().get("equipment_cost").isUnknown()),
            "worldgen must preserve measurable evidence while distinguishing inapplicable inputs from unknown mining costs");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void economicPopulationClassifiesNonFoodResourcesWithoutNameHeuristics(GameTestHelper helper) {
        helper.assertTrue(!DynamicFoodEngine.isTechnicalResource(new ItemStack(Items.DIAMOND)),
            "a non-food raw economic resource must not be excluded merely for lacking food properties");
        helper.assertTrue(DynamicFoodEngine.isTechnicalResource(new ItemStack(Items.DIAMOND_SWORD)),
            "damageable equipment must be excluded from the automatic economic calibration population");
        helper.assertTrue(DynamicFoodEngine.isTechnicalResource(new ItemStack(Items.CHEST)),
            "block-entity containers must be excluded from the automatic economic calibration population");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void villagerTradeIndexCapturesFixedListingsAndKeepsUnknownCosts(GameTestHelper helper) {
        VillagerTrades.ItemListing supportedModListing = new BasicItemListing(
            new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD, 2), 12, 1, 0.05F);
        VillagerTrades.ItemListing unsupportedListing = (entity, random) -> null;
        Map<Integer, List<VillagerTrades.ItemListing>> trades = Map.of(1, List.of(
            supportedModListing,
            new VillagerTrades.ItemsForEmeralds(Items.APPLE, 1, 1, 12),
            unsupportedListing
        ));
        VillagerTradeAcquisitionAnalyzer analyzer =
            VillagerTradeAcquisitionAnalyzer.empty().withProfession("minecraft:farmer", trades);
        VillagerTradeAcquisitionAnalyzer pricedAnalyzer = analyzer.withInputCosts(Map.of("minecraft:emerald", 0.5D));
        DynamicFoodEngine engine = new DynamicFoodEngine();
        engine.replaceVillagerTradeListings("minecraft:farmer", trades);

        List<AcquisitionPath> breadPaths = engine.acquisitionPaths("minecraft:bread");
        List<AcquisitionPath> applePaths = engine.acquisitionPaths("minecraft:apple");
        helper.assertTrue(breadPaths.size() == 1
            && breadPaths.getFirst().sourceType().equals("villager_trade")
            && breadPaths.getFirst().sourceId().equals("minecraft:farmer/level_1/listing_0"),
            "fixed NeoForge BasicItemListing output must have a deterministic source path");
        helper.assertTrue(breadPaths.getFirst().costsByHorizon().get(100)
            .factors().get("quantity_cost").isKnown()
            && !breadPaths.getFirst().costsByHorizon().get(100).factors().get("material_cost").isKnown()
            && breadPaths.getFirst().evidence().measurement("output_quantity_per_completed_offer").value() == 2.0D
            && breadPaths.getFirst().evidence().measurement("input_quantity:minecraft:emerald").value() == 1.0D
            && breadPaths.getFirst().evidence().measurement("maximum_uses").value() == 12.0D
            && breadPaths.getFirst().evidence().attributes().get("price_model")
                .contains("demand, reputation"),
            "observed trade output, inputs and restock limits must be retained while unknown input prices stay unknown");
        helper.assertTrue(applePaths.size() == 1
            && applePaths.getFirst().sourceId().equals("minecraft:farmer/level_1/listing_1")
            && applePaths.getFirst().evidence().measurement("input_quantity:minecraft:emerald").value() == 1.0D,
            "the verified vanilla trade must retain its base emerald input without invoking its Entity-dependent offer");
        EconomicFactor pricedMaterial = pricedAnalyzer.analyze("minecraft:bread").getFirst()
            .costsByHorizon().get(100).factors().get("material_cost");
        helper.assertTrue(pricedMaterial.isKnown()
            && Math.abs(pricedMaterial.value() - FactorNormalizer.logarithmic(25.0D, 1.0D, 100.0D).value()) < 0.0001D,
            "known input economics must be recursively counted and normalized for the requested output horizon");
        helper.assertTrue(analyzer.unindexedListingCount() == 1,
            "unsupported Java-only ItemListing implementations must remain visible as unindexed");
        helper.assertTrue(DynamicFood.ENGINE.acquisitionPaths("minecraft:bread").stream()
            .anyMatch(path -> path.sourceType().equals("villager_trade")),
            "the real NeoForge VillagerTradesEvent must populate the reload-scoped trade index");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void reloadCapturesVerifiedCookingDurations(GameTestHelper helper) {
        boolean vanillaCookingTimeFound = DynamicFood.ENGINE.graph().recipesFor("minecraft:iron_ingot").stream()
            .anyMatch(recipe -> recipe.recipeType().equals("minecraft:smelting")
                && recipe.processingTimeTicks() != null && recipe.processingTimeTicks() > 0.0D);
        helper.assertTrue(vanillaCookingTimeFound,
            "vanilla AbstractCookingRecipe time must be indexed from the 1.21.1 recipe object");

        if (ModList.get().isLoaded("farmersdelight")) {
            boolean farmerDelightCookingTimeFound =
                DynamicFood.ENGINE.graph().recipesFor("farmersdelight:apple_cider").stream()
                    .anyMatch(recipe -> recipe.processingTimeTicks() != null && recipe.processingTimeTicks() > 0.0D);
            helper.assertTrue(farmerDelightCookingTimeFound,
                "Farmer's Delight 1.2.11a getCookTime metadata must be indexed");
        }
        if (ModList.get().isLoaded("create")) {
            boolean createRecipeFound = DynamicFood.ENGINE.graph().recipesFor("create:dough").stream()
                .anyMatch(recipe -> recipe.recipeId().equals("create:mixing/dough_by_mixing")
                    && recipe.processingTimeTicks() == null);
            helper.assertTrue(createRecipeFound,
                "Create basin duration remains unknown unless its machine provides authoritative runtime duration");
        }
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void farmersDelightCookingPotPublishesConsumedInputs(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("farmersdelight")) {
            helper.succeed();
            return;
        }

        Block potBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("farmersdelight:cooking_pot"));
        helper.assertTrue(!potBlock.defaultBlockState().isAir(), "Farmer's Delight cooking pot block must be registered");
        helper.setBlock(1, 1, 1, potBlock);
        Object pot = helper.getBlockEntity(new BlockPos(1, 1, 1));
        helper.assertTrue(java.util.Arrays.stream(pot.getClass().getDeclaredFields())
            .anyMatch(field -> field.getName().contains("dynamicfood$inputs")),
            "the verified Farmer's Delight processCooking hook must transform its target block entity");
        Object inventoryValue = pot.getClass().getMethod("getInventory").invoke(pot);
        helper.assertTrue(inventoryValue instanceof ItemStackHandler,
            "Farmer's Delight cooking pot must expose its verified ItemStackHandler");
        ItemStackHandler inventory = (ItemStackHandler) inventoryValue;
        inventory.setStackInSlot(0,
            new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("farmersdelight:rice"))));

        var recipeHolder = helper.getLevel().getRecipeManager()
            .byKey(ResourceLocation.parse("farmersdelight:cooking/cooked_rice")).orElseThrow();
        Object recipe = recipeHolder.value();
        int cookTime = (int) recipe.getClass().getMethod("getCookTime").invoke(recipe);
        var processCooking = pot.getClass().getDeclaredMethod("processCooking", recipeHolder.getClass(), pot.getClass());
        processCooking.setAccessible(true);
        RecipeTransactionEvent[] capturedTransaction = {null};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            (RecipeTransactionEvent event) -> {
                if (event.recipeId().equals("farmersdelight:cooking/cooked_rice")) {
                    capturedTransaction[0] = event;
                }
            });
        boolean cooked = false;
        for (int tick = 0; tick < cookTime && !cooked; tick++) {
            cooked = (boolean) processCooking.invoke(pot, recipeHolder, pot);
        }

        helper.assertTrue(cooked, "the real Farmer's Delight cooking operation must complete");
        helper.assertTrue(capturedTransaction[0] != null,
            "the real cooking operation must publish its RecipeTransactionEvent");
        helper.assertTrue(capturedTransaction[0].inputs().size() == 1
            && capturedTransaction[0].inputs().getFirst().is(
                BuiltInRegistries.ITEM.get(ResourceLocation.parse("farmersdelight:rice"))),
            "the cooking transaction must publish the actual consumed rice input");
        ItemStack output = inventory.getStackInSlot(6);
        helper.assertTrue(output.has(DataComponents.FOOD),
            "the verified Farmer's Delight output must be eligible for DynamicFood values");
        DynamicFoodValue value = output.get(DynamicFoodDataComponents.VALUE.get());
        helper.assertTrue(output.is(BuiltInRegistries.ITEM.get(ResourceLocation.parse("farmersdelight:cooked_rice"))),
            "the actual Farmer's Delight recipe must produce its registered cooked-rice output");
        helper.assertTrue(value != null, "the actual cooking output must receive a DynamicFoodValue component");
        helper.assertTrue(value.sourceRecipe().equals("farmersdelight:cooking/cooked_rice"),
            "the cooking output provenance must identify its matched Farmer's Delight recipe");
        helper.assertTrue(inventory.getStackInSlot(0).isEmpty(),
            "the published operation must correspond to the ingredients actually consumed by the pot");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void farmersDelightCuttingBoardPublishesActualCuttingInputs(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("farmersdelight")) {
            helper.succeed();
            return;
        }

        Block boardBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("farmersdelight:cutting_board"));
        helper.assertTrue(!boardBlock.defaultBlockState().isAir(), "Farmer's Delight cutting board must be registered");
        helper.setBlock(1, 1, 1, boardBlock);
        BlockPos boardPos = helper.absolutePos(new BlockPos(1, 1, 1));
        Object board = helper.getBlockEntity(new BlockPos(1, 1, 1));
        Object inventoryValue = board.getClass().getMethod("getInventory").invoke(board);
        helper.assertTrue(inventoryValue instanceof ItemStackHandler,
            "Farmer's Delight cutting board must expose its verified item inventory");
        ItemStackHandler inventory = (ItemStackHandler) inventoryValue;
        ItemStack cabbage = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("farmersdelight:cabbage")));
        inventory.setStackInSlot(0, cabbage);

        ItemStack knife = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("farmersdelight:iron_knife")));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        boolean processed = (boolean) board.getClass()
            .getMethod("processStoredItemUsingTool", ItemStack.class, Player.class)
            .invoke(board, knife, player);
        helper.assertTrue(processed, "the real Farmer's Delight cutting recipe must match cabbage and its knife");
        helper.assertTrue(inventory.getStackInSlot(0).isEmpty(),
            "the cutting operation must consume the stored board input");

        var outputItem = BuiltInRegistries.ITEM.get(ResourceLocation.parse("farmersdelight:cabbage_leaf"));
        boolean outputHasProvenance = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(boardPos).inflate(3.0D)).stream()
            .map(ItemEntity::getItem)
            .filter(stack -> stack.is(outputItem))
            .map(stack -> stack.get(DynamicFoodDataComponents.VALUE.get()))
            .anyMatch(value -> value != null && value.sourceRecipe().equals("farmersdelight:cutting/cabbage"));
        helper.assertTrue(outputHasProvenance,
            "the actual cutting output must carry provenance for its matched Farmer's Delight recipe");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void createBasinPublishesActualConsumedInputs(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        Block basinBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:basin"));
        helper.assertTrue(!basinBlock.defaultBlockState().isAir(), "Create Basin block must be registered");
        helper.setBlock(1, 1, 1, basinBlock);
        Object basin = helper.getBlockEntity(new BlockPos(1, 1, 1));
        Object inputInventory = basin.getClass().getMethod("getInputInventory").invoke(basin);
        ItemStack snow = new ItemStack(Items.SNOW_BLOCK, 9);
        Object remainder = inputInventory.getClass()
            .getMethod("insertItem", int.class, ItemStack.class, boolean.class)
            .invoke(inputInventory, 0, snow, false);
        helper.assertTrue(remainder instanceof ItemStack stack && stack.isEmpty(),
            "the actual Create Basin input inventory must accept the recipe snow blocks");

        var recipeHolder = helper.getLevel().getRecipeManager()
            .byKey(ResourceLocation.parse("create:compacting/ice")).orElseThrow();
        Class<?> basinRecipe = Class.forName("com.simibubi.create.content.processing.basin.BasinRecipe");
        RecipeTransactionEvent[] capturedTransaction = {null};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            (RecipeTransactionEvent event) -> {
                if (event.recipeId().equals("create:compacting/ice")) {
                    capturedTransaction[0] = event;
                }
            });
        boolean applied = (boolean) basinRecipe.getMethod("apply", basin.getClass(),
            net.minecraft.world.item.crafting.Recipe.class).invoke(null, basin, recipeHolder.value());
        helper.assertTrue(applied, "the real Create Basin recipe application must consume matching inputs");
        helper.assertTrue(capturedTransaction[0] != null && capturedTransaction[0].operation() != null,
            "the Basin hook must publish an immutable RecipeOperation for its actual transaction");
        var inputs = capturedTransaction[0].operation().itemInputs();
        helper.assertTrue(inputs.size() == 9
            && inputs.stream().allMatch(input -> input.stack().is(Items.SNOW_BLOCK)
                && input.consumedCount() == 1),
            "the Basin transaction must preserve all nine one-block input consumptions");

        Object outputInventory = basin.getClass().getMethod("getOutputInventory").invoke(basin);
        int outputSlots = (int) outputInventory.getClass().getMethod("getSlots").invoke(outputInventory);
        boolean iceOutputFound = false;
        for (int slot = 0; slot < outputSlots; slot++) {
            ItemStack output = (ItemStack) outputInventory.getClass()
                .getMethod("getStackInSlot", int.class).invoke(outputInventory, slot);
            iceOutputFound |= output.is(Items.ICE);
        }
        helper.assertTrue(iceOutputFound, "the actual Create Basin must produce its recipe's ice output");
        helper.assertTrue(((ItemStack) inputInventory.getClass()
            .getMethod("getStackInSlot", int.class).invoke(inputInventory, 0)).isEmpty(),
            "the Basin must consume the actual snow blocks");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void createDeployerSelectsAndExecutesActualRecipe(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        Block deployerBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:deployer"));
        helper.assertTrue(!deployerBlock.defaultBlockState().isAir(), "Create Deployer block must be registered");
        helper.setBlock(1, 1, 1, deployerBlock);
        Object deployer = helper.getBlockEntity(new BlockPos(1, 1, 1));
        deployer.getClass().getMethod("initialize").invoke(deployer);
        Object deployerPlayer = deployer.getClass().getMethod("getPlayer").invoke(deployer);
        helper.assertTrue(deployerPlayer != null, "the server Deployer must initialize its verified fake player");

        var expectedHolder = helper.getLevel().getRecipeManager()
            .byKey(ResourceLocation.parse("create:deploying/cogwheel")).orElseThrow();
        Object recipe = expectedHolder.value();
        net.minecraft.world.item.crafting.Ingredient targetIngredient =
            (net.minecraft.world.item.crafting.Ingredient) recipe.getClass().getMethod("getProcessedItem").invoke(recipe);
        net.minecraft.world.item.crafting.Ingredient heldIngredient =
            (net.minecraft.world.item.crafting.Ingredient) recipe.getClass().getMethod("getRequiredHeldItem").invoke(recipe);
        helper.assertTrue(targetIngredient.getItems().length > 0 && heldIngredient.getItems().length > 0,
            "the verified Create deployer recipe must expose concrete target and held-item choices");
        ItemStack target = targetIngredient.getItems()[0].copyWithCount(1);
        ItemStack heldItem = heldIngredient.getItems()[0].copyWithCount(1);
        deployerPlayer.getClass().getMethod("setItemInHand", InteractionHand.class, ItemStack.class)
            .invoke(deployerPlayer, InteractionHand.MAIN_HAND, heldItem);
        var selectedHolder = (net.minecraft.world.item.crafting.RecipeHolder<?>) deployer.getClass()
            .getMethod("getRecipe", ItemStack.class).invoke(deployer, target);
        helper.assertTrue(selectedHolder != null,
            "the actual Create Deployer lookup must match its loaded recipe's real target and held-item ingredients");
        helper.assertTrue(selectedHolder.id().equals(expectedHolder.id()),
            "the Deployer lookup must select the actual Create cogwheel recipe");
        helper.assertTrue(OptionalTransactionSupport.createDeployerContext().isPresent(),
            "the real successful getRecipe hook must retain target and held-item context");

        RecipeTransactionEvent[] capturedTransaction = {null};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((RecipeTransactionEvent event) -> {
            if (event.recipeId().equals("create:deploying/cogwheel")) {
                capturedTransaction[0] = event;
            }
        });
        Class<?> recipeApplier = Class.forName("com.simibubi.create.foundation.recipe.RecipeApplier");
        Object outputValue;
        try {
            outputValue = recipeApplier.getMethod("applyRecipeOn", net.minecraft.world.level.Level.class,
                ItemStack.class, Recipe.class, boolean.class)
                .invoke(null, helper.getLevel(), target, selectedHolder.value(), false);
        } catch (NoSuchMethodException legacyApi) {
            outputValue = recipeApplier.getMethod("applyRecipeOn", net.minecraft.world.level.Level.class,
                ItemStack.class, Recipe.class)
                .invoke(null, helper.getLevel(), target, selectedHolder.value());
        }
        helper.assertTrue(outputValue instanceof List<?> outputs
            && outputs.stream().anyMatch(output -> output instanceof ItemStack stack
                && stack.is(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:cogwheel")))),
            "Create RecipeApplier must execute the selected recipe and produce a cogwheel");
        helper.assertTrue(capturedTransaction[0] != null && capturedTransaction[0].operation() != null,
            "the actual selected Deployer recipe must publish its RecipeOperation");
        var inputs = capturedTransaction[0].operation().itemInputs();
        helper.assertTrue(inputs.size() == 2
            && ItemStack.isSameItemSameComponents(inputs.get(0).stack(), target)
            && ItemStack.isSameItemSameComponents(inputs.get(1).stack(), heldItem)
            && inputs.stream().allMatch(input -> input.consumedCount() == 1),
            "deployer provenance must preserve the exact target and held-item input counts");
        helper.assertTrue(OptionalTransactionSupport.createDeployerContext().isEmpty(),
            "the completed Create Deployer operation must clear its thread-local context");
        helper.succeed();
    }
}