package com.jorjik.dynamicfood.graph;

import com.jorjik.dynamicfood.DynamicFood;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(DynamicFood.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecipeRemainderGameTest {
    private RecipeRemainderGameTest() {
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void classifiesStackSensitiveRecipeRemainders(GameTestHelper helper) {
        helper.assertTrue(
            RecipeGraphReloadListener.classifyInputUse(new ItemStack[] {new ItemStack(Items.WHEAT)})
                == AcquisitionIngredient.InputUse.CONSUMED,
            "Input without a remainder should be classified as consumed");
        helper.assertTrue(
            RecipeGraphReloadListener.classifyInputUse(new ItemStack[] {new ItemStack(Items.WATER_BUCKET)})
                == AcquisitionIngredient.InputUse.UNKNOWN,
            "Transformed remainder should remain unknown");
        helper.assertTrue(
            RecipeGraphReloadListener.isReusableRemainder(
                new ItemStack(Items.WHEAT, 2), new ItemStack(Items.WHEAT, 2)),
            "Same item, components, and quantity should count as reusable");
        helper.assertTrue(!RecipeGraphReloadListener.isReusableRemainder(
                new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.BUCKET)),
            "Transformed item should not count as reusable");
        helper.succeed();
    }
}
