package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.core.EconomicGenerationPublisher;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

public final class RecipeOperation {
    private final String recipeId;
    private final String recipeType;
    private final String station;
    private final int stationDifficulty;
    private final List<ItemInput> itemInputs;
    private final List<FluidInput> fluidInputs;
    private final List<Output> outputs;
    private final Map<String, Double> processingMetadata;
    private final RuntimeEconomicContext runtimeEconomicContext;

    public RecipeOperation(String recipeId, String recipeType, String station, int stationDifficulty,
        List<ItemInput> itemInputs, List<FluidInput> fluidInputs, List<Output> outputs,
        Map<String, Double> processingMetadata) {
        this(recipeId, recipeType, station, stationDifficulty, itemInputs, fluidInputs, outputs,
            processingMetadata, null);
    }

    private RecipeOperation(String recipeId, String recipeType, String station, int stationDifficulty,
        List<ItemInput> itemInputs, List<FluidInput> fluidInputs, List<Output> outputs,
        Map<String, Double> processingMetadata, RuntimeEconomicContext runtimeEconomicContext) {
        this.recipeId = recipeId == null ? "unknown" : recipeId;
        this.recipeType = recipeType == null ? "unknown" : recipeType;
        this.station = station == null ? "unknown" : station;
        this.stationDifficulty = Math.max(0, Math.min(5, stationDifficulty));
        this.itemInputs = List.copyOf(itemInputs);
        this.fluidInputs = List.copyOf(fluidInputs);
        this.outputs = List.copyOf(outputs);
        this.processingMetadata = Map.copyOf(processingMetadata);
        this.runtimeEconomicContext = runtimeEconomicContext;
        if (this.processingMetadata.values().stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("processing metadata must contain only finite values");
        }
    }

    public String recipeId() { return recipeId; }
    public String recipeType() { return recipeType; }
    public String station() { return station; }
    public int stationDifficulty() { return stationDifficulty; }
    public List<ItemInput> itemInputs() { return itemInputs; }
    public List<FluidInput> fluidInputs() { return fluidInputs; }
    public List<Output> outputs() { return outputs; }
    public Map<String, Double> processingMetadata() { return processingMetadata; }
    public Optional<RuntimeEconomicContext> runtimeEconomicContext() {
        return Optional.ofNullable(runtimeEconomicContext);
    }

    public RecipeOperation withRuntimeEconomicContext(RuntimeEconomicContext context) {
        if (context == null) {
            throw new IllegalArgumentException("captured runtime economic context is required");
        }
        if (runtimeEconomicContext != null && !runtimeEconomicContext.equals(context)) {
            throw new IllegalStateException("recipe operation already has a different captured generation");
        }
        return runtimeEconomicContext == context ? this
            : new RecipeOperation(recipeId, recipeType, station, stationDifficulty, itemInputs, fluidInputs,
                outputs, processingMetadata, context);
    }

    public RecipeOperation captureRuntimeEconomicContext(
        EconomicGenerationPublisher publisher
    ) {
        return runtimeEconomicContext == null
            ? withRuntimeEconomicContext(RuntimeEconomicContext.capture(publisher))
            : this;
    }

    public record ItemInput(ItemStack stack, int consumedCount) {
        public ItemInput {
            stack = stack.copy();
            consumedCount = Math.max(0, consumedCount);
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }

    public record FluidInput(FluidStack stack) {
        public FluidInput {
            stack = stack.copy();
        }

        @Override
        public FluidStack stack() {
            return stack.copy();
        }
    }

    public record Output(ItemStack stack, Double allocationWeight) {
        public Output {
            if (stack == null || stack.isEmpty()) {
                throw new IllegalArgumentException("recipe operation outputs must be non-empty");
            }
            if (allocationWeight != null && (!Double.isFinite(allocationWeight) || allocationWeight < 0.0D)) {
                throw new IllegalArgumentException("output allocation weight must be finite and non-negative");
            }
        }
    }
}