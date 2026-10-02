package com.jorjik.dynamicfood.compat.mixin;

import com.jorjik.dynamicfood.compat.OptionalTransactionSupport;
import java.util.Optional;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "vectorwing.farmersdelight.common.block.entity.CuttingBoardBlockEntity", remap = false)
public abstract class FarmerDelightCuttingBoardMixin {
    @Shadow(remap = false) public abstract ItemStack getStoredItem();
    @Shadow(remap = false) abstract Optional<?> getMatchingRecipe(ItemStack tool, Player player);

    @Inject(method = "processStoredItemUsingTool", at = @At(value = "HEAD", remap = false), remap = false)
    private void dynamicfood$capture(ItemStack tool, Player player, CallbackInfoReturnable<Boolean> callback) {
        Optional<?> matching = getMatchingRecipe(tool, player);
        if (matching.isPresent() && matching.get() instanceof RecipeHolder<?> holder) {
            OptionalTransactionSupport.beginCutting(getStoredItem(), holder, player.level());
        }
    }

    @Inject(method = "processStoredItemUsingTool", at = @At(value = "RETURN", remap = false), remap = false)
    private void dynamicfood$clear(ItemStack tool, Player player, CallbackInfoReturnable<Boolean> callback) {
        OptionalTransactionSupport.clearCutting();
    }
}