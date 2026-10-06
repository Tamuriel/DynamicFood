package com.jorjik.dynamicfood.graph;

import com.jorjik.dynamicfood.DynamicFood;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
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

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void capturesRecipeTagAlternativesFromReloadContext(GameTestHelper helper) {
        ResourceLocation recipeId = ResourceLocation.fromNamespaceAndPath("minecraft", "acacia_planks");
        var holder = helper.getLevel().getRecipeManager().byKey(recipeId).orElse(null);
        helper.assertTrue(holder != null, "The active acacia planks recipe must be present");
        if (holder == null) {
            return;
        }

        var graphRecipe = DynamicFood.ENGINE.graph().recipesFor("minecraft:acacia_planks").stream()
            .filter(recipe -> recipe.recipeId().equals(recipeId.toString()))
            .findFirst()
            .orElse(null);
        helper.assertTrue(graphRecipe != null, "The acquisition graph must retain the active recipe");
        if (graphRecipe == null) {
            return;
        }

        List<Ingredient> ingredients = holder.value().getIngredients().stream()
            .filter(ingredient -> !ingredient.isEmpty())
            .toList();
        helper.assertTrue(ingredients.size() == graphRecipe.acquisitionIngredients().size(),
            "The acquisition graph must retain each non-empty recipe ingredient");
        if (ingredients.size() != graphRecipe.acquisitionIngredients().size()) {
            return;
        }

        for (int index = 0; index < ingredients.size(); index++) {
            Ingredient ingredient = ingredients.get(index);
            var captured = graphRecipe.acquisitionIngredients().get(index);
            if (ingredient.hasNoItems()) {
                helper.assertTrue(captured.alternatives().isEmpty()
                        && !captured.unresolvedReason().isBlank(),
                    "An actually empty ingredient must remain unresolved with an explicit reason");
                continue;
            }

            List<String> expected = Arrays.stream(ingredient.getItems())
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()))
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .distinct()
                .sorted()
                .toList();
            helper.assertTrue(captured.alternatives().equals(expected)
                    && captured.unresolvedReason().isBlank(),
                "Recipe tag alternatives must match the active server tag contents");
        }
        helper.succeed();
    }
}
