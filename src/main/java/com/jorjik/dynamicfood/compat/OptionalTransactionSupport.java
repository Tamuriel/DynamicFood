package com.jorjik.dynamicfood.compat;

import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.provenance.RecipeTransactionEvent;
import com.jorjik.dynamicfood.provenance.RecipeOperation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.common.NeoForge;

public final class OptionalTransactionSupport {
    private OptionalTransactionSupport() {}

    public static void post(Level level, Recipe<?> recipe, ItemStack result, List<ItemStack> inputs,
        String fallbackRecipeId, int outputCount) {
        if (result == null || result.isEmpty()) {
            return;
        }
        post(level, recipe, List.of(result), inputs, fallbackRecipeId, outputCount);
    }

    public static void post(Level level, Recipe<?> recipe, List<ItemStack> outputs, List<ItemStack> inputs,
        String fallbackRecipeId) {
        post(level, recipe, outputs, inputs, fallbackRecipeId, 0);
    }

    private static void post(Level level, Recipe<?> recipe, List<ItemStack> outputs, List<ItemStack> inputs,
        String fallbackRecipeId, int outputCount) {
        if (level == null || recipe == null || outputs == null || outputs.isEmpty()) {
            return;
        }
        String recipeId = findRecipeId(level, recipe).orElse(fallbackRecipeId);
        String recipeType = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
        if (outputCount > 0 && outputs.size() == 1) {
            NeoForge.EVENT_BUS.post(new RecipeTransactionEvent(outputs.getFirst(), recipeId, recipeType, inputs, outputCount));
        } else {
            NeoForge.EVENT_BUS.post(new RecipeTransactionEvent(outputs, recipeId, recipeType, inputs));
        }
    }

    public static void post(Level level, Recipe<?> recipe, List<ItemStack> outputs, List<ItemStack> inputs,
        List<FluidStack> fluidInputs, String fallbackRecipeId, String station) {
        if (level == null || recipe == null || outputs == null || outputs.isEmpty()) {
            return;
        }
        String recipeId = findRecipeId(level, recipe).orElse(fallbackRecipeId);
        String recipeType = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
        RecipeOperation operation = DynamicFood.ENGINE.createRecipeOperation(recipe, recipeId, recipeType,
            station, inputs, fluidInputs, outputs, java.util.Map.of());
        NeoForge.EVENT_BUS.post(new RecipeTransactionEvent(operation));
    }

    public static List<ItemStack> snapshotInventory(Object inventory) {
        List<ItemStack> inputs = new ArrayList<>();
        try {
            Method slotsMethod = inventory.getClass().getMethod("getSlots");
            Method stackMethod = inventory.getClass().getMethod("getStackInSlot", int.class);
            int slots = (int) slotsMethod.invoke(inventory);
            for (int slot = 0; slot < slots; slot++) {
                Object value = stackMethod.invoke(inventory, slot);
                if (value instanceof ItemStack stack && !stack.isEmpty()) {
                    inputs.add(stack.copy());
                }
            }
        } catch (ReflectiveOperationException exception) {
            DynamicFood.LOGGER.warn("Could not snapshot optional recipe inventory", exception);
        }
        return List.copyOf(inputs);
    }

    public static List<ItemStack> snapshotCreateBasinInputs(Object basin, Recipe<?> recipe) {
        try {
            Method inputMethod = basin.getClass().getMethod("getInputInventory");
            List<ItemStack> available = snapshotInventory(inputMethod.invoke(basin));
            int[] consumed = new int[available.size()];
            List<ItemStack> inputs = new ArrayList<>();
            for (Ingredient ingredient : recipe.getIngredients()) {
                int matchedSlot = -1;
                for (int slot = 0; slot < available.size(); slot++) {
                    ItemStack stack = available.get(slot);
                    if (consumed[slot] < stack.getCount() && ingredient.test(stack)) {
                        matchedSlot = slot;
                        break;
                    }
                }
                if (matchedSlot < 0) {
                    return List.of();
                }
                ItemStack input = available.get(matchedSlot).copy();
                input.setCount(1);
                inputs.add(input);
                consumed[matchedSlot]++;
            }
            return List.copyOf(inputs);
        } catch (ReflectiveOperationException exception) {
            DynamicFood.LOGGER.warn("Could not snapshot Create basin inputs", exception);
            return List.of();
        }
    }

    public static List<FluidStack> snapshotCreateBasinFluids(Object basin, Recipe<?> recipe) {
        try {
            Method fluidIngredientsMethod = recipe.getClass().getMethod("getFluidIngredients");
            Object inputTank = basin.getClass().getField("inputTank").get(basin);
            IFluidHandler fluidHandler = (IFluidHandler) inputTank.getClass().getMethod("getCapability").invoke(inputTank);
            int tankCount = fluidHandler.getTanks();
            int[] consumed = new int[tankCount];
            List<FluidStack> captured = new ArrayList<>();
            for (Object ingredient : (List<?>) fluidIngredientsMethod.invoke(recipe)) {
                int amount = requiredFluidAmount(ingredient);
                Method testMethod = ingredient.getClass().getMethod("test", FluidStack.class);
                int matchedTank = -1;
                FluidStack matchedStack = null;
                for (int tank = 0; tank < tankCount; tank++) {
                    FluidStack available = fluidHandler.getFluidInTank(tank);
                    if (available.getAmount() - consumed[tank] >= amount
                        && (boolean) testMethod.invoke(ingredient, available)) {
                        matchedTank = tank;
                        matchedStack = available;
                        break;
                    }
                }
                if (matchedTank < 0 || matchedStack == null) {
                    DynamicFood.LOGGER.warn("Could not match required Create basin fluid input for recipe {}", recipe.getType());
                    return List.of();
                }
                consumed[matchedTank] += amount;
                captured.add(matchedStack.copyWithAmount(amount));
            }
            return List.copyOf(captured);
        } catch (ReflectiveOperationException exception) {
            DynamicFood.LOGGER.warn("Could not snapshot actual Create basin fluid inputs", exception);
            return List.of();
        }
    }

    public static void beginCreateBasin(Object basin, Recipe<?> recipe) {
        CREATE_BASIN_CONTEXT.set(new CreateBasinContext(
            levelOf(basin), recipe, snapshotCreateBasinInputs(basin, recipe), snapshotCreateBasinFluids(basin, recipe)));
    }

    public static Optional<CreateBasinContext> createBasinContext() {
        return Optional.ofNullable(CREATE_BASIN_CONTEXT.get());
    }

    public static void clearCreateBasin() {
        CREATE_BASIN_CONTEXT.remove();
    }

    public static void beginCreateFilling(Level level, int fluidAmount, ItemStack input, FluidStack fluid) {
        CREATE_FILLING_CONTEXT.remove();
        if (level == null || fluidAmount <= 0 || input == null || input.isEmpty()
            || fluid == null || fluid.isEmpty()) {
            return;
        }

        try {
            var type = BuiltInRegistries.RECIPE_TYPE.getOptional(ResourceLocation.parse("create:filling")).orElse(null);
            if (type == null) {
                return;
            }
            SingleRecipeInput recipeInput = new SingleRecipeInput(input);
            RecipeHolder<?> match = fillingRecipes(level, type, recipeInput).stream()
                .sorted(java.util.Comparator.comparing(holder -> holder.id().toString()))
                .filter(holder -> matches(holder.value(), recipeInput, level))
                .filter(holder -> fluidMatches(holder.value(), fluid, fluidAmount))
                .findFirst().orElse(null);
            if (match == null) {
                DynamicFood.LOGGER.debug("No Create filling recipe matches input {} and fluid {}",
                    BuiltInRegistries.ITEM.getKey(input.getItem()), BuiltInRegistries.FLUID.getKey(fluid.getFluid()));
                return;
            }

            Object sizedIngredient = match.value().getClass().getMethod("getRequiredFluid").invoke(match.value());
            int requiredAmount = requiredFluidAmount(sizedIngredient);
            ItemStack consumedInput = input.copyWithCount(1);
            CREATE_FILLING_CONTEXT.set(new CreateFillingContext(level, match.value(), match.id().toString(),
                consumedInput, fluid.copyWithAmount(requiredAmount)));
        } catch (ReflectiveOperationException exception) {
            DynamicFood.LOGGER.warn("Could not capture actual Create filling inputs", exception);
        }
    }

    public static void completeCreateFilling(ItemStack output) {
        CreateFillingContext context = CREATE_FILLING_CONTEXT.get();
        try {
            if (context == null || output == null || output.isEmpty()) {
                return;
            }
            post(context.level(), context.recipe(), List.of(output), List.of(context.input()),
                List.of(context.fluid()), context.recipeId(), "create:spout");
        } finally {
            CREATE_FILLING_CONTEXT.remove();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<RecipeHolder<?>> fillingRecipes(Level level, RecipeType<?> type, RecipeInput input) {
        return (List) level.getRecipeManager().getRecipesFor((RecipeType) type, input, level);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean matches(Recipe<?> recipe, RecipeInput input, Level level) {
        return ((Recipe) recipe).matches(input, level);
    }

    private static boolean fluidMatches(Recipe<?> recipe, FluidStack fluid, int amount) {
        try {
            Object required = recipe.getClass().getMethod("getRequiredFluid").invoke(recipe);
            int requiredAmount = requiredFluidAmount(required);
            return amount >= requiredAmount && (boolean) required.getClass()
                .getMethod("test", FluidStack.class).invoke(required, fluid);
        } catch (ReflectiveOperationException exception) {
            DynamicFood.LOGGER.warn("Could not inspect Create filling recipe fluid requirement", exception);
            return false;
        }
    }

    private static int requiredFluidAmount(Object ingredient) throws ReflectiveOperationException {
        try {
            return (int) ingredient.getClass().getMethod("amount").invoke(ingredient);
        } catch (NoSuchMethodException modernApiUnavailable) {
            return (int) ingredient.getClass().getMethod("getRequiredAmount").invoke(ingredient);
        }
    }

    public static void beginCreateDeployer(Object deployer, ItemStack target) {
        CREATE_DEPLOYER_CONTEXT.remove();
        if (deployer == null || target == null || target.isEmpty()) {
            return;
        }
        try {
            // Primary path: deployer.getPlayer().getMainHandItem()
            ItemStack heldItem = null;
            try {
                Method playerMethod = deployer.getClass().getMethod("getPlayer");
                Object player = playerMethod.invoke(deployer);
                if (player != null) {
                    try {
                        Method heldItemMethod = player.getClass().getMethod("getMainHandItem");
                        Object heldValue = heldItemMethod.invoke(player);
                        if (heldValue instanceof ItemStack hs && !hs.isEmpty()) {
                            heldItem = hs;
                        }
                    } catch (ReflectiveOperationException ignored) {
                        // fallthrough to field scanning
                    }
                }
            } catch (NoSuchMethodException ignored) {
                // fallthrough to field scanning
            }

            // Fallbacks: try common methods on the deployer that may directly return the held item
            if (heldItem == null) {
                String[] candidateMethodNames = new String[] {"getHeldItem", "getHeldStack", "getItemInHand", "getMainHandItem", "getItem"};
                for (String methodName : candidateMethodNames) {
                    try {
                        Method m = deployer.getClass().getMethod(methodName);
                        Object val = m.invoke(deployer);
                        if (val instanceof ItemStack is && !is.isEmpty()) {
                            heldItem = is;
                            break;
                        }
                        if (val instanceof ItemStack[] arr && arr.length > 0 && arr[0] != null && !arr[0].isEmpty()) {
                            heldItem = arr[0];
                            break;
                        }
                    } catch (ReflectiveOperationException ignored) {
                        // try next
                    }
                }
            }

            // Last-resort: scan deployer fields for ItemStack or for objects that expose accessors returning ItemStack
            if (heldItem == null) {
                for (Class<?> c = deployer.getClass(); c != null; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        f.setAccessible(true);
                        Object value = f.get(deployer);
                        if (value == null) continue;

                        Class<?> t = f.getType();
                        if (ItemStack.class.isAssignableFrom(t)) {
                            ItemStack is = (ItemStack) value;
                            if (!is.isEmpty()) {
                                heldItem = is;
                                break;
                            }
                            continue;
                        }
                        if (ItemStack[].class.isAssignableFrom(t)) {
                            ItemStack[] arr = (ItemStack[]) value;
                            if (arr.length > 0 && arr[0] != null && !arr[0].isEmpty()) {
                                heldItem = arr[0];
                                break;
                            }
                            continue;
                        }

                        // Try invoking player-like/accessor methods on the field value
                        String[] accessorNames = new String[] {"getMainHandItem", "getHeldItem", "getItemInHand", "getInventory", "getHeldStack"};
                        for (String acc : accessorNames) {
                            try {
                                Method am = value.getClass().getMethod(acc);
                                Object held = am.invoke(value);
                                if (held instanceof ItemStack is2 && !is2.isEmpty()) {
                                    heldItem = is2;
                                    break;
                                }
                                if (held instanceof ItemStack[] arr2 && arr2.length > 0 && arr2[0] != null && !arr2[0].isEmpty()) {
                                    heldItem = arr2[0];
                                    break;
                                }
                            } catch (ReflectiveOperationException ignored) {
                                // continue trying other accessors
                            }
                        }

                        if (heldItem != null) break;
                    }
                    if (heldItem != null) break;
                }
            }

            if (heldItem != null) {
                List<ItemStack> inputs = List.of(target.copy(), heldItem.copy());
                CREATE_DEPLOYER_CONTEXT.set(new CreateDeployerContext(levelOf(deployer), inputs));
                try {
                    DynamicFood.LOGGER.debug("Captured CreateDeployerContext target={} held={}",
                        BuiltInRegistries.ITEM.getKey(target.getItem()),
                        BuiltInRegistries.ITEM.getKey(heldItem.getItem()));
                } catch (Exception ignored) {
                    // best-effort logging only
                }
            } else {
                DynamicFood.LOGGER.debug("Could not find held item for Create deployer instance of {}",
                    deployer.getClass().getName());
            }
        } catch (ReflectiveOperationException exception) {
            DynamicFood.LOGGER.warn("Could not snapshot actual Create deployer target and held item", exception);
        }
    }

    public static Optional<CreateDeployerContext> createDeployerContext() {
        return Optional.ofNullable(CREATE_DEPLOYER_CONTEXT.get());
    }

    public static void clearCreateDeployer() {
        CREATE_DEPLOYER_CONTEXT.remove();
    }

    public static void beginCutting(ItemStack input, RecipeHolder<?> recipe, Level level) {
        ItemStack consumed = input.copy();
        consumed.setCount(1);
        CUTTING_CONTEXT.set(new CuttingContext(consumed, recipe, level));
    }

    public static Optional<CuttingContext> cuttingContext() {
        return Optional.ofNullable(CUTTING_CONTEXT.get());
    }

    public static void clearCutting() {
        CUTTING_CONTEXT.remove();
    }

    public static Optional<Recipe<?>> activeCreateRecipe(Object basin) {
        try {
            Method operatorMethod = basin.getClass().getDeclaredMethod("getOperator");
            operatorMethod.setAccessible(true);
            Object operatorValue = operatorMethod.invoke(basin);
            if (operatorValue instanceof Optional<?> optional && optional.isPresent()) {
                Object operator = optional.get();
                for (Class<?> type = operator.getClass(); type != null; type = type.getSuperclass()) {
                    try {
                        Field recipeField = type.getDeclaredField("currentRecipe");
                        recipeField.setAccessible(true);
                        Object recipe = recipeField.get(operator);
                        if (recipe instanceof Recipe<?> typedRecipe) {
                            return Optional.of(typedRecipe);
                        }
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            }
        } catch (ReflectiveOperationException exception) {
            DynamicFood.LOGGER.debug("Could not inspect active Create basin recipe", exception);
        }
        return Optional.empty();
    }

    public static Level levelOf(Object blockEntity) {
        if (blockEntity instanceof net.minecraft.world.level.block.entity.BlockEntity entity) {
            return entity.getLevel();
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Optional<String> findRecipeId(Level level, Recipe<?> recipe) {
        List<RecipeHolder<?>> holders = level.getRecipeManager().getAllRecipesFor((net.minecraft.world.item.crafting.RecipeType) recipe.getType());
        return holders.stream()
            .filter(holder -> holder.value() == recipe)
            .map(RecipeHolder::id)
            .map(Object::toString)
            .findFirst();
    }

    public record CuttingContext(ItemStack input, RecipeHolder<?> recipe, Level level) {
    }

    public record CreateBasinContext(Level level, Recipe<?> recipe, List<ItemStack> inputs, List<FluidStack> fluidInputs) {
    }

    public record CreateFillingContext(Level level, Recipe<?> recipe, String recipeId, ItemStack input,
        FluidStack fluid) {
        public CreateFillingContext {
            input = input.copy();
            fluid = fluid.copy();
        }

        @Override
        public ItemStack input() {
            return input.copy();
        }

        @Override
        public FluidStack fluid() {
            return fluid.copy();
        }
    }
    public record CreateDeployerContext(Level level, List<ItemStack> inputs) {
        public CreateDeployerContext {
            inputs = inputs.stream().map(ItemStack::copy).toList();
        }
    }

    private static final ThreadLocal<CuttingContext> CUTTING_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<CreateBasinContext> CREATE_BASIN_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<CreateDeployerContext> CREATE_DEPLOYER_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<CreateFillingContext> CREATE_FILLING_CONTEXT = new ThreadLocal<>();
}
