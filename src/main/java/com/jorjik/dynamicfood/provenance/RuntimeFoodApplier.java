package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import com.jorjik.dynamicfood.core.IngredientContribution;
import com.jorjik.dynamicfood.core.FoodValue;
import com.jorjik.dynamicfood.core.EconomicGenerationPublisher;
import com.jorjik.dynamicfood.data.DynamicFoodDataComponents;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

public final class RuntimeFoodApplier {
    private RuntimeFoodApplier() {}

    public static void apply(ItemStack result, RuntimeProvenance provenance) {
        apply(result, provenance, DynamicFood.ECONOMIC_GENERATIONS);
    }

    public static void apply(
        ItemStack result,
        RuntimeProvenance provenance,
        EconomicGenerationPublisher generationPublisher
    ) {
        apply(result, provenance, RuntimeEconomicContext.capture(generationPublisher));
    }

    public static void apply(ItemStack result, RuntimeProvenance provenance,
        RuntimeEconomicContext economicContext) {
        if (result.isEmpty() || !DynamicFoodConfig.allowsRecipeType(provenance.recipeType())) {
            return;
        }

        FoodValue resolved = DynamicFood.ENGINE.resolve(provenance, economicContext);
        DynamicFoodValue value = economicContext.publishedGeneration().isPresent()
            ? DynamicFoodValue.snapshot(resolved,
                OperationFoodSnapshot.from(provenance, resolved, 1.0D, economicContext))
            : DynamicFoodValue.snapshot(resolved);
        applyValue(result, value);
    }

    public static void applyOperation(
        List<OutputValueAllocator.OutputTarget<ItemStack>> outputs,
        RuntimeProvenance provenance
    ) {
        applyOperation(outputs, provenance, DynamicFood.ECONOMIC_GENERATIONS);
    }

    public static void applyOperation(
        List<OutputValueAllocator.OutputTarget<ItemStack>> outputs,
        RuntimeProvenance provenance,
        EconomicGenerationPublisher generationPublisher
    ) {
        applyOperation(outputs, provenance, RuntimeEconomicContext.capture(generationPublisher));
    }

    public static void applyOperation(
        List<OutputValueAllocator.OutputTarget<ItemStack>> outputs,
        RuntimeProvenance provenance,
        RuntimeEconomicContext economicContext
    ) {
        if (outputs.isEmpty() || !DynamicFoodConfig.allowsRecipeType(provenance.recipeType())) {
            return;
        }
        FoodValue operationValue = DynamicFood.ENGINE.resolve(provenance, economicContext);
        List<OutputValueAllocator.AllocatedOutput<ItemStack>> allocated =
            OutputValueAllocator.allocate(operationValue, outputs);
        double totalWeight = outputs.stream().mapToDouble(target -> target.allocationWeight() == null
            ? target.quantity() : target.allocationWeight()).sum();
        for (OutputValueAllocator.AllocatedOutput<ItemStack> allocation : allocated) {
            OutputValueAllocator.OutputTarget<ItemStack> target = allocation.target();
            double weight = target.allocationWeight() == null ? target.quantity() : target.allocationWeight();
            double share = weight / totalWeight;
            FoodValue outputValue = new FoodValue(
                allocation.nutritionPerUnit(),
                (int) Math.ceil(allocation.nutritionPerUnit()),
                allocation.saturationPerUnit(),
                allocation.saturationPerUnit(),
                operationValue.difficulty(),
                provenance.recipeId(),
                provenance.outputCount(),
                operationValue.components()
            );
            applyValue(target.output(), DynamicFoodValue.snapshot(outputValue,
                OperationFoodSnapshot.from(provenance, operationValue, share, economicContext)));
        }
    }

    public static void applyOperation(RecipeOperation operation) {
        applyOperation(operation, DynamicFood.ECONOMIC_GENERATIONS);
    }

    public static void applyOperation(
        RecipeOperation operation,
        EconomicGenerationPublisher generationPublisher
    ) {
        if (operation.outputs().isEmpty() || !DynamicFoodConfig.allowsRecipeType(operation.recipeType())) {
            return;
        }
        RecipeOperation capturedOperation = operation.captureRuntimeEconomicContext(generationPublisher);
        RuntimeEconomicContext economicContext = capturedOperation.runtimeEconomicContext().orElseThrow();
        applyOperation(capturedOperation, economicContext);
    }

    public static void applyOperation(RecipeOperation operation, RuntimeEconomicContext economicContext) {
        if (operation.outputs().isEmpty() || !DynamicFoodConfig.allowsRecipeType(operation.recipeType())) {
            return;
        }
        RecipeOperation capturedOperation = operation.withRuntimeEconomicContext(economicContext);
        List<IngredientContribution> inputs = new ArrayList<>(capturedOperation.itemInputs().stream()
            .filter(input -> input.consumedCount() > 0)
            .map(input -> contribution(input.stack(), input.consumedCount(), economicContext))
            .toList());
        inputs.addAll(capturedOperation.fluidInputs().stream()
            .map(RecipeOperation.FluidInput::stack)
            .map(RuntimeFoodApplier::fluidContribution)
            .toList());
        List<OutputValueAllocator.OutputTarget<ItemStack>> targets = OutputValueAllocator.foodTargets(
            capturedOperation.outputs().stream().map(RecipeOperation.Output::stack).toList(),
            stack -> ItemStackFoodResolver.resolve(stack, economicContext).foodComponent(),
            ItemStack::getCount,
            stack -> capturedOperation.outputs().stream()
                .filter(output -> output.stack() == stack)
                .findFirst()
                .map(RecipeOperation.Output::allocationWeight)
                .orElse(null)
        );
        if (targets.isEmpty()) {
            return;
        }
        int outputCount = OutputValueAllocator.totalFoodOutputCount(targets);
        RuntimeProvenance provenance = RuntimeProvenance.fromOperation(capturedOperation, outputCount, inputs);
        FoodValue operationValue = DynamicFood.ENGINE.resolve(provenance, economicContext);
        List<OutputValueAllocator.AllocatedOutput<ItemStack>> allocated = OutputValueAllocator.allocate(operationValue, targets);
        double totalWeight = targets.stream().mapToDouble(target -> target.allocationWeight() == null
            ? target.quantity() : target.allocationWeight()).sum();
        for (OutputValueAllocator.AllocatedOutput<ItemStack> allocation : allocated) {
            OutputValueAllocator.OutputTarget<ItemStack> target = allocation.target();
            double weight = target.allocationWeight() == null ? target.quantity() : target.allocationWeight();
            double share = weight / totalWeight;
            FoodValue outputValue = new FoodValue(
                allocation.nutritionPerUnit(),
                (int) Math.ceil(allocation.nutritionPerUnit()),
                allocation.saturationPerUnit(),
                allocation.saturationPerUnit(),
                operationValue.difficulty(),
                capturedOperation.recipeId(),
                outputCount,
                operationValue.components()
            );
            applyValue(target.output(), DynamicFoodValue.snapshot(outputValue,
                OperationFoodSnapshot.from(capturedOperation, operationValue, share)));
        }
    }

    private static IngredientContribution fluidContribution(FluidStack stack) {
        String fluidId = BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString();
        return DynamicFoodConfig.fluidProfile(fluidId)
            .map(profile -> profile.contribution(stack))
            .orElseGet(() -> new IngredientContribution(fluidId, 0.0D, 0.0D, 1, false, "unknown_fluid"));
    }

    public static ItemStack decorateCandidate(ItemStack result, RuntimeProvenance provenance) {
        if (result.isEmpty() || !DynamicFoodConfig.allowsRecipeType(provenance.recipeType())) {
            return result;
        }
        applyValue(result, DynamicFoodValue.snapshot(DynamicFood.ENGINE.resolve(provenance)));
        return result;
    }

    private static void applyValue(ItemStack result, DynamicFoodValue value) {
        result.set(DynamicFoodDataComponents.VALUE.get(), value);
        if (DynamicFoodConfig.flag(DynamicFoodConfig.OVERRIDE_EXISTING_FOOD, true)) {
            FoodProperties food = result.get(DataComponents.FOOD);
            if (food != null) {
                result.set(DataComponents.FOOD, FoodPropertiesUpdater.withDynamicValue(food, value));
            }
        }
    }

    public static RuntimeProvenance unresolvedSmelting(ItemStack result) {
        return new RuntimeProvenance(
            itemId(result), "runtime:smelting_unresolved", "minecraft:smelting", result.getCount(), List.of());
    }

    public static List<IngredientContribution> contributions(List<ItemStack> inputs) {
        return contributions(inputs, null);
    }

    public static List<IngredientContribution> contributions(List<ItemStack> inputs,
        RuntimeEconomicContext economicContext) {
        List<IngredientContribution> contributions = new ArrayList<>();
        for (ItemStack input : inputs) {
            if (!input.isEmpty()) {
                contributions.add(contribution(input, input.getCount(), economicContext));
            }
        }
        return List.copyOf(contributions);
    }

    public static IngredientContribution contribution(ItemStack stack, int consumedCount) {
        return contribution(stack, consumedCount, null);
    }

    public static IngredientContribution contribution(ItemStack stack, int consumedCount,
        RuntimeEconomicContext economicContext) {
        DynamicFoodValue inherited = stack.get(DynamicFoodDataComponents.VALUE.get());
        if (inherited != null) {
            return inherited.asIngredientContribution(itemId(stack), consumedCount);
        }

        IngredientContribution resolved = ItemStackFoodResolver.resolve(stack, economicContext);
        return new IngredientContribution(resolved.itemId(), resolved.nutrition(), resolved.saturation(), consumedCount,
            resolved.foodComponent(), resolved.sourceRecipe(), resolved.difficulty());
    }

    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}