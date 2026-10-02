package com.jorjik.dynamicfood.compat.mixin;

import com.jorjik.dynamicfood.compat.OptionalTransactionSupport;
import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe", remap = false)
public abstract class FarmerDelightCuttingRecipeMixin {
    @Inject(method = "rollResults", at = @At(value = "RETURN", remap = false), remap = false)
    private void dynamicfood$publish(RandomSource random, int fortuneLevel,
        CallbackInfoReturnable<List<ItemStack>> callback) {
        OptionalTransactionSupport.cuttingContext().ifPresent(context -> {
            OptionalTransactionSupport.post(context.level(), context.recipe().value(), callback.getReturnValue(),
                List.of(context.input()), context.recipe().id().toString());
        });
    }
}