package com.jorjik.dynamicfood.compat.mixin;

import com.jorjik.dynamicfood.compat.OptionalTransactionSupport;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity", remap = false)
public abstract class CreateDeployerMixin {
    @Inject(method = "getRecipe(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/crafting/RecipeHolder;",
        at = @At(value = "HEAD", remap = false), remap = false)
    private void dynamicfood$captureActualInputs(ItemStack target,
        CallbackInfoReturnable<?> callback) {
        OptionalTransactionSupport.beginCreateDeployer(this, target);
    }

    @Inject(method = "getRecipe(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/crafting/RecipeHolder;",
        at = @At(value = "RETURN", remap = false), remap = false)
    private void dynamicfood$clearFailedLookup(ItemStack target,
        CallbackInfoReturnable<?> callback) {
        if (callback.getReturnValue() == null) {
            OptionalTransactionSupport.clearCreateDeployer();
        }
    }
}
