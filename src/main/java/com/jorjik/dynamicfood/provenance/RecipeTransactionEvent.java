package com.jorjik.dynamicfood.provenance;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;

/**
 * Optional integrations post this event after a recipe operation has consumed its inputs.
 */
public final class RecipeTransactionEvent extends Event {
    private final ItemStack result;
    private final List<ItemStack> outputs;
    private final String recipeId;
    private final String recipeType;
    private final List<ItemStack> inputs;
    private final int outputCount;
    private final RecipeOperation operation;

    public RecipeTransactionEvent(ItemStack result, String recipeId, String recipeType, List<ItemStack> inputs) {
        this(result, recipeId, recipeType, inputs, result.getCount());
    }

    public RecipeTransactionEvent(ItemStack result, String recipeId, String recipeType,
        List<ItemStack> inputs, int outputCount) {
        this.result = result;
        this.outputs = result == null || result.isEmpty() ? List.of() : List.of(result);
        this.recipeId = recipeId;
        this.recipeType = recipeType;
        this.inputs = inputs == null ? List.of() : inputs.stream().map(ItemStack::copy).toList();
        this.outputCount = Math.max(1, outputCount);
        this.operation = null;
    }

    public RecipeTransactionEvent(List<ItemStack> outputs, String recipeId, String recipeType, List<ItemStack> inputs) {
        this.outputs = outputs == null ? List.of() : outputs.stream().filter(output -> output != null && !output.isEmpty()).toList();
        this.result = this.outputs.isEmpty() ? ItemStack.EMPTY : this.outputs.getFirst();
        this.recipeId = recipeId;
        this.recipeType = recipeType;
        this.inputs = inputs == null ? List.of() : inputs.stream().map(ItemStack::copy).toList();
        this.outputCount = 0;
        this.operation = null;
    }

    public RecipeTransactionEvent(RecipeOperation operation) {
        this.operation = operation;
        this.outputs = operation.outputs().stream().map(RecipeOperation.Output::stack).toList();
        this.result = this.outputs.isEmpty() ? ItemStack.EMPTY : this.outputs.getFirst();
        this.recipeId = operation.recipeId();
        this.recipeType = operation.recipeType();
        this.inputs = operation.itemInputs().stream().map(input -> input.stack()).toList();
        this.outputCount = 0;
    }

    public ItemStack result() {
        return result;
    }

    public List<ItemStack> outputs() {
        return outputs;
    }

    public String recipeId() {
        return recipeId;
    }

    public String recipeType() {
        return recipeType;
    }

    public List<ItemStack> inputs() {
        return inputs;
    }

    public int outputCount() {
        return outputCount;
    }

    public RecipeOperation operation() {
        return operation;
    }
}