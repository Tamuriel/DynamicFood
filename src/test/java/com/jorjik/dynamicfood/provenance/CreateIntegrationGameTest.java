package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.adapter.RecipeAdapter;
import com.jorjik.dynamicfood.core.DynamicFoodEngine;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(DynamicFood.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateIntegrationGameTest {
    private CreateIntegrationGameTest() {}

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void createBasinMixingCapturesActualItemsAndWater(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        var level = helper.getLevel();
        var recipeHolder = level.getRecipeManager()
            .byKey(ResourceLocation.parse("create:mixing/dough_by_mixing")).orElseThrow();
        Recipe<?> recipe = recipeHolder.value();
        Object basin = placeBasin(helper);
        Object inventory = basin.getClass().getMethod("getInputInventory").invoke(basin);
        List<Ingredient> ingredients = recipe.getIngredients();
        helper.assertTrue(!ingredients.isEmpty(), "the actual Create dough mixing recipe must require item ingredients");
        for (int slot = 0; slot < ingredients.size(); slot++) {
            ItemStack[] choices = ingredients.get(slot).getItems();
            helper.assertTrue(choices.length > 0, "the actual Create mixing ingredient must resolve to concrete items");
            insertBasinItem(helper, inventory, slot, choices[0].copyWithCount(1));
        }

        int accepted = fillBasin(basin, new FluidStack(Fluids.WATER, 1000));
        helper.assertTrue(accepted == 1000, "the Create Basin must accept the recipe's full 1000 mB water input");
        RecipeTransactionEvent[] captured = listenFor("create:mixing/dough_by_mixing");
        boolean applied = (boolean) Class.forName("com.simibubi.create.content.processing.basin.BasinRecipe")
            .getMethod("apply", basin.getClass(), Recipe.class).invoke(null, basin, recipe);

        helper.assertTrue(applied, "Create 6.0.10 must apply the real dough mixing recipe in the Basin");
        RecipeOperation operation = requireOperation(helper, captured[0], "Create Basin mixing");
        helper.assertTrue(operation.recipeType().equals("create:mixing") && operation.station().equals("create:basin"),
            "the published operation must identify Create mixing and the Basin station");
        helper.assertTrue(operation.itemInputs().size() == ingredients.size()
            && operation.itemInputs().stream().allMatch(input -> input.consumedCount() == 1
                && ingredients.stream().anyMatch(ingredient -> ingredient.test(input.stack()))),
            "the operation must contain the actual item stacks consumed by the mixing recipe");
        helper.assertTrue(operation.fluidInputs().size() == 1
            && operation.fluidInputs().getFirst().stack().is(Fluids.WATER)
            && operation.fluidInputs().getFirst().stack().getAmount() == 1000,
            "the operation must record the actual 1000 mB water ingredient");
        helper.assertTrue(outputContains(basin, "create:dough"),
            "the real Create Basin must emit the dough recipe output");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void createSpoutFillingCapturesActualFluidAndOutput(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        var level = helper.getLevel();
        var recipeHolder = level.getRecipeManager()
            .byKey(ResourceLocation.parse("create:filling/honeyed_apple")).orElseThrow();
        ItemStack apple = new ItemStack(Items.APPLE);
        var honey = BuiltInRegistries.FLUID.getOptional(ResourceLocation.parse("create:honey")).orElseThrow();
        FluidStack inputFluid = new FluidStack(honey, 1000);
        Class<?> filling = Class.forName("com.simibubi.create.content.fluids.spout.FillingBySpout");
        int required = (int) filling.getMethod("getRequiredAmountForItem",
            net.minecraft.world.level.Level.class, ItemStack.class, FluidStack.class)
            .invoke(null, level, apple, inputFluid);
        helper.assertTrue(required == 250,
            "Create 6.0.10 honeyed-apple recipe must request the actual 250 mB honey ingredient");

        RecipeTransactionEvent[] captured = listenFor("create:filling/honeyed_apple");
        ItemStack output = (ItemStack) filling.getMethod("fillItem",
            net.minecraft.world.level.Level.class, int.class, ItemStack.class, FluidStack.class)
            .invoke(null, level, required, apple, inputFluid);
        helper.assertTrue(output.is(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:honeyed_apple"))),
            "the actual Create Spout filling operation must produce honeyed apple");

        RecipeOperation operation = requireOperation(helper, captured[0], "Create Spout filling");
        helper.assertTrue(operation.recipeType().equals("create:filling") && operation.station().equals("create:spout"),
            "the published operation must identify Create filling and the Spout station");
        helper.assertTrue(operation.itemInputs().size() == 1
            && operation.itemInputs().getFirst().stack().is(Items.APPLE)
            && operation.itemInputs().getFirst().consumedCount() == 1,
            "the operation must retain the actual apple input consumed by Create");
        helper.assertTrue(operation.fluidInputs().size() == 1
            && operation.fluidInputs().getFirst().stack().is(honey)
            && operation.fluidInputs().getFirst().stack().getAmount() == required,
            "the operation must record the exact honey amount consumed by the Spout");
        helper.assertTrue(operation.outputs().size() == 1
            && operation.outputs().getFirst().stack().is(output.getItem()),
            "the operation must snapshot the actual filling output");
        helper.assertTrue(recipeHolder.id().toString().equals(operation.recipeId()),
            "the operation recipe identity must match the actual loaded filling recipe");
        helper.succeed();
    }

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void customAdapterCapturesCreateExtensionRecipeOperation(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        var loadedRecipe = helper.getLevel().getRecipeManager()
            .byKey(ResourceLocation.parse("create:mixing/dough_by_mixing")).orElseThrow();
        ItemStack actualItemInput = loadedRecipe.value().getIngredients().getFirst().getItems()[0].copyWithCount(1);
        ItemStack output = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:dough")));
        DynamicFoodEngine engine = new DynamicFoodEngine();
        engine.registerAdapter(new RecipeAdapter() {
            @Override
            public boolean supports(String candidate) {
                return candidate.equals("create:sequenced_assembly");
            }

            @Override
            public int stationDifficulty() {
                return 4;
            }
        });
        String fixtureType = "create:sequenced_assembly";
        String fixtureRecipeId = "create:sequenced_assembly/dynamicfood_adapter_fixture";
        var fluid = new FluidStack(Fluids.WATER, 1000);
        RecipeOperation operation = engine.createRecipeOperation(null, fixtureRecipeId, fixtureType,
            "create:sequenced_assembly", List.of(actualItemInput), List.of(fluid), List.of(output),
            Map.of("duration_ticks", 100.0D));

        helper.assertTrue(operation.recipeType().equals(fixtureType) && operation.stationDifficulty() == 4,
            "a non-built-in Create recipe type must select the explicitly registered adapter");
        helper.assertTrue(operation.recipeId().equals(fixtureRecipeId)
            && operation.itemInputs().size() == 1
            && ItemStack.isSameItemSameComponents(operation.itemInputs().getFirst().stack(), actualItemInput)
            && operation.itemInputs().getFirst().consumedCount() == actualItemInput.getCount(),
            "the extension adapter must retain recipe identity and actual item input quantity");
        helper.assertTrue(operation.fluidInputs().size() == 1
            && operation.fluidInputs().getFirst().stack().is(Fluids.WATER)
            && operation.fluidInputs().getFirst().stack().getAmount() == 1000
            && operation.outputs().size() == 1
            && operation.outputs().getFirst().stack().is(output.getItem())
            && operation.processingMetadata().get("duration_ticks") == 100.0D,
            "the extension adapter must capture item, fluid, output and processing metadata in one operation");
        helper.succeed();
    }
    private static List<net.minecraft.world.item.crafting.RecipeHolder<?>> allRecipesOfType(
        GameTestHelper helper, net.minecraft.world.item.crafting.RecipeType<?> type) {
        return (List) helper.getLevel().getRecipeManager().getAllRecipesFor((net.minecraft.world.item.crafting.RecipeType) type);
    }
    private static Object placeBasin(GameTestHelper helper) throws Exception {
        var basinBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:basin"));
        helper.assertTrue(!basinBlock.defaultBlockState().isAir(), "Create Basin block must be registered");
        helper.setBlock(1, 1, 1, basinBlock);
        return helper.getBlockEntity(new BlockPos(1, 1, 1));
    }

    private static void insertBasinItem(GameTestHelper helper, Object inventory, int slot, ItemStack input)
        throws Exception {
        Object remainder = inventory.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class)
            .invoke(inventory, slot, input, false);
        helper.assertTrue(remainder instanceof ItemStack stack && stack.isEmpty(),
            "the real Create Basin input inventory must accept the recipe ingredient");
    }

    private static int fillBasin(Object basin, FluidStack input) throws Exception {
        Object inputTank = basin.getClass().getField("inputTank").get(basin);
        IFluidHandler handler = (IFluidHandler) inputTank.getClass().getMethod("getCapability").invoke(inputTank);
        return handler.fill(input, IFluidHandler.FluidAction.EXECUTE);
    }

    private static RecipeTransactionEvent[] listenFor(String recipeId) {
        RecipeTransactionEvent[] captured = {null};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((RecipeTransactionEvent event) -> {
            if (recipeId.equals(event.recipeId())) {
                captured[0] = event;
            }
        });
        return captured;
    }

    private static RecipeOperation requireOperation(GameTestHelper helper, RecipeTransactionEvent event,
        String operationName) {
        helper.assertTrue(event != null && event.operation() != null,
            operationName + " must publish one immutable RecipeOperation");
        return event.operation();
    }

    private static boolean outputContains(Object basin, String itemId) throws Exception {
        Object inventory = basin.getClass().getMethod("getOutputInventory").invoke(basin);
        int slots = (int) inventory.getClass().getMethod("getSlots").invoke(inventory);
        var expected = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
        for (int slot = 0; slot < slots; slot++) {
            ItemStack output = (ItemStack) inventory.getClass().getMethod("getStackInSlot", int.class)
                .invoke(inventory, slot);
            if (output.is(expected)) {
                return true;
            }
        }
        return false;
    }
}