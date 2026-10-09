package com.jorjik.dynamicfood.core;

import com.mojang.authlib.GameProfile;
import com.jorjik.dynamicfood.DynamicFood;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(DynamicFood.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OakLogDirectAcquisitionGameTest {
    private OakLogDirectAcquisitionGameTest() {}

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void activeOakTreeEvidenceMatchesARealSurvivalBlockBreak(GameTestHelper helper) {
        PublishedEconomicGeneration generation = DynamicFood.ECONOMIC_GENERATIONS.current().orElse(null);
        helper.assertTrue(generation != null,
            "Oak acquisition verification requires the normal production economic generation");
        EconomicSnapshot snapshot = generation.economicSnapshot();
        var oak = snapshot.resource("minecraft:oak_log").orElse(null);
        helper.assertTrue(oak != null, "the production snapshot must retain oak log");

        AcquisitionPath oakSource = oak == null ? null : oak.acquisitionPaths().stream()
            .filter(path -> path.sourceType().equals("worldgen_feature"))
            .filter(path -> "minecraft:oak_log".equals(
                path.evidence().attributes().get("worldgen_block_id")))
            .filter(path -> "minecraft:plains".equals(
                path.evidence().attributes().get("biome_restriction")))
            .filter(path -> "minecraft:trees_plains".equals(
                path.evidence().attributes().get("placed_feature")))
            .findFirst().orElse(null);
        helper.assertTrue(oakSource != null,
            "the production snapshot must retain the active plains tree-to-oak-log path");
        helper.assertTrue(oakSource != null && "TRUE".equals(
                oakSource.evidence().attributes().get("source_availability_classification")),
            "the path is TRUE only after active-biome, positive-feature, block, tool, and loot evidence resolve");
        helper.assertTrue(oakSource != null && "TRUE".equals(
                oakSource.evidence().attributes().get("ordinary_player_break_output_evidence")),
            "the linked loot table must prove the direct ordinary-break output");
        helper.assertTrue(oakSource != null && "false".equals(
                oakSource.evidence().attributes().get("worldgen_requires_correct_tool")),
            "oak log's registered block state must not require a correct tool");
        CostVector oakCosts = oakSource == null ? null : oakSource.costsByHorizon().get(
            com.jorjik.dynamicfood.config.DynamicFoodConfig.acquisitionEconomicHorizon());
        helper.assertTrue(oakCosts != null
                && oakCosts.factors().get("quantity_cost").isKnown()
                && oakCosts.factor(EconomicChannel.PROBABILITY_BURDEN).isUnknown()
                && oakCosts.factors().get("material_cost").isNotApplicable()
                && oakCosts.factors().get("equipment_cost").isNotApplicable()
                && !oakCosts.factors().containsKey("time_cost")
                && !oakCosts.isCoreComplete(),
            "oak quantity is known; unresolved source availability burden blocks completeness; duration is diagnostic");
        FeasibilityResult oakFeasibility = oakSource == null ? null : FeasibilityResolver.resolve(oakSource,
            com.jorjik.dynamicfood.config.DynamicFoodConfig.feasibilityFactorWeights(),
            com.jorjik.dynamicfood.config.DynamicFoodConfig.minimumFeasibilityCoverage(),
            com.jorjik.dynamicfood.config.DynamicFoodConfig.minimumFeasibility(),
            com.jorjik.dynamicfood.config.DynamicFoodConfig.allowPartialFeasibility());
        helper.assertTrue(oakFeasibility != null
                && oakFeasibility.status() == ResolutionStatus.UNKNOWN
                && oakFeasibility.notApplicableFactors().stream()
                    .anyMatch(factor -> factor.startsWith("equipment_availability:"))
                && oakFeasibility.missingFactors().stream()
                    .anyMatch(factor -> factor.startsWith("repeatability:"))
                && oakFeasibility.missingFactors().stream()
                    .anyMatch(factor -> factor.startsWith("renewability:"))
                && oakFeasibility.missingFactors().stream()
                    .anyMatch(factor -> factor.startsWith("danger:"))
                && oakFeasibility.missingFactors().stream()
                    .anyMatch(factor -> factor.startsWith("reliability:")),
            "feasibility remains UNKNOWN for explicit unresolved worldgen lifecycle and access evidence");
        helper.assertTrue(oak != null && !oak.economicCost().isKnown(),
            "availability evidence must not manufacture an EconomicCost");

        assertRecipeInputAvailable(helper, snapshot, "minecraft:oak_planks", "minecraft:oak_log");
        assertRecipeInputAvailable(helper, snapshot, "minecraft:oak_boat", "minecraft:oak_planks");

        var acacia = snapshot.resource("minecraft:acacia_log").orElse(null);
        helper.assertTrue(acacia != null, "the production snapshot must retain acacia log");
        AcquisitionPath acaciaWorldgen = acacia == null ? null : acacia.acquisitionPaths().stream()
            .filter(path -> path.sourceType().equals("worldgen_feature"))
            .filter(path -> "minecraft:acacia_log".equals(
                path.evidence().attributes().get("worldgen_block_id")))
            .findFirst().orElse(null);
        helper.assertTrue(acaciaWorldgen != null && "UNKNOWN".equals(
                acaciaWorldgen.evidence().attributes().get("source_availability_classification")),
            "inactive savanna generation must remain UNKNOWN");

        var ironOreResource = snapshot.resource("minecraft:iron_ore").orElse(null);
        AcquisitionPath ironOre = ironOreResource == null ? null : ironOreResource.acquisitionPaths().stream()
            .filter(path -> path.sourceType().equals("worldgen_feature"))
            .filter(path -> "minecraft:iron_ore".equals(
                path.evidence().attributes().get("worldgen_block_id")))
            .findFirst().orElse(null);
        helper.assertTrue(ironOre != null && "UNKNOWN".equals(
                ironOre.evidence().attributes().get("source_availability_classification")),
            "tool-gated ore extraction must remain UNKNOWN");
        helper.assertTrue(ironOre != null && "true".equals(
                ironOre.evidence().attributes().get("worldgen_requires_correct_tool")),
            "the ore path must retain the registered correct-tool requirement");

        BlockPos blockPos = new BlockPos(1, 1, 1);
        helper.setBlock(blockPos, Blocks.OAK_LOG);
        BlockPos absolutePos = helper.absolutePos(blockPos);
        ServerPlayer player = createSurvivalServerPlayer(helper);
        try {
            helper.assertTrue(player.gameMode.isSurvival() && !player.isCreative(),
                "the block-break fixture must use a non-creative server player");
            helper.assertTrue(!Blocks.OAK_LOG.defaultBlockState().requiresCorrectToolForDrops(),
                "the target block must be harvestable without a correct-tool requirement");
            boolean destroyed = player.gameMode.destroyBlock(absolutePos);
            helper.assertTrue(destroyed, "the server player game mode must complete the survival block break");
            helper.assertTrue(helper.getLevel().getBlockState(absolutePos).isAir(),
                "the real server-side player break must remove the oak-log block");
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(
                ItemEntity.class, new AABB(absolutePos).inflate(2.0D));
            helper.assertTrue(drops.stream().anyMatch(entity -> entity.getItem().is(Items.OAK_LOG)),
                "the ordinary survival block break must actually emit an oak log");
        } finally {
            player.connection.disconnect(Component.literal("Oak-log GameTest complete"));
        }

        helper.succeed();
    }

    private static void assertRecipeInputAvailable(GameTestHelper helper, EconomicSnapshot snapshot,
        String outputItem, String inputItem) {
        var output = snapshot.resource(outputItem).orElse(null);
        helper.assertTrue(output != null, "the production snapshot must retain " + outputItem);
        AcquisitionPath recipe = output == null ? null : output.acquisitionPaths().stream()
            .filter(path -> path.sourceType().equals("recipe"))
            .filter(path -> path.evidence().inputs().stream()
                .anyMatch(input -> input.alternatives().contains(inputItem)))
            .findFirst().orElse(null);
        helper.assertTrue(recipe != null,
            "the production recipe path for " + outputItem + " must retain input " + inputItem);
        helper.assertTrue(recipe != null && "TRUE".equals(
                recipe.evidence().attributes().get("source_availability_classification")),
            "the recipe path for " + outputItem + " must inherit proven survival availability");
    }

    private static ServerPlayer createSurvivalServerPlayer(GameTestHelper helper) {
        var level = helper.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
            new GameProfile(UUID.randomUUID(), "dynamicfood-oak-break"), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
            cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }
}
