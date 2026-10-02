package com.jorjik.dynamicfood.compat.mixin;

import com.jorjik.dynamicfood.compat.OptionalTransactionSupport;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.simibubi.create.content.processing.basin.BasinBlockEntity", remap = false)
public abstract class CreateBasinMixin {
    @Inject(method = "acceptOutputs", at = @At(value = "HEAD", remap = false), remap = false)
    private void dynamicfood$publish(List<ItemStack> outputs, List<FluidStack> fluids, boolean simulate,
        CallbackInfoReturnable<Boolean> callback) {
        if (simulate) {
            return;
        }
        try {
            if (outputs == null || outputs.isEmpty()) {
                return;
            }
            OptionalTransactionSupport.createBasinContext().ifPresent(context -> {
                OptionalTransactionSupport.post(context.level(), context.recipe(), outputs, context.inputs(),
                    context.fluidInputs(), "runtime:create_basin", "create:basin");
            });
        } finally {
            OptionalTransactionSupport.clearCreateBasin();
        }
    }
}