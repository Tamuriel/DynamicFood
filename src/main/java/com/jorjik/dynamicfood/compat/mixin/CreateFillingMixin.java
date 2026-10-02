package com.jorjik.dynamicfood.compat.mixin;

import com.jorjik.dynamicfood.compat.OptionalTransactionSupport;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.simibubi.create.content.fluids.spout.FillingBySpout", remap = false)
public abstract class CreateFillingMixin {
    @Inject(method = "fillItem(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/item/ItemStack;Lnet/neoforged/neoforge/fluids/FluidStack;)Lnet/minecraft/world/item/ItemStack;",
        at = @At(value = "HEAD", remap = false), remap = false)
    private static void dynamicfood$capture(Level level, int amount, ItemStack input, FluidStack fluid,
        CallbackInfoReturnable<ItemStack> callback) {
        OptionalTransactionSupport.beginCreateFilling(level, amount, input, fluid);
    }

    @Inject(method = "fillItem(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/item/ItemStack;Lnet/neoforged/neoforge/fluids/FluidStack;)Lnet/minecraft/world/item/ItemStack;",
        at = @At(value = "RETURN", remap = false), remap = false)
    private static void dynamicfood$publish(Level level, int amount, ItemStack input, FluidStack fluid,
        CallbackInfoReturnable<ItemStack> callback) {
        OptionalTransactionSupport.completeCreateFilling(callback.getReturnValue());
    }
}
