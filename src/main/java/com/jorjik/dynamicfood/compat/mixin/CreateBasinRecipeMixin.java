package com.jorjik.dynamicfood.compat.mixin;

import com.jorjik.dynamicfood.compat.OptionalTransactionSupport;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.simibubi.create.content.processing.basin.BasinRecipe", remap = false)
public abstract class CreateBasinRecipeMixin {
    @Inject(method = "apply(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;Lnet/minecraft/world/item/crafting/Recipe;Z)Z", at = @At(value = "HEAD", remap = false), remap = false)
    private static void dynamicfood$capture(@Coerce Object basin, Recipe<?> recipe, boolean simulate,
        CallbackInfoReturnable<Boolean> callback) {
        if (!simulate) {
            OptionalTransactionSupport.beginCreateBasin(basin, recipe);
        }
    }

    @Inject(method = "apply(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;Lnet/minecraft/world/item/crafting/Recipe;Z)Z", at = @At(value = "RETURN", remap = false), remap = false)
    private static void dynamicfood$clearOnFailure(@Coerce Object basin, Recipe<?> recipe, boolean simulate,
        CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue()) {
            OptionalTransactionSupport.clearCreateBasin();
        }
    }
}