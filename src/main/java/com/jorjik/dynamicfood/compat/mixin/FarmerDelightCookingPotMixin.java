package com.jorjik.dynamicfood.compat.mixin;

import com.jorjik.dynamicfood.compat.OptionalTransactionSupport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity", remap = false)
public abstract class FarmerDelightCookingPotMixin {
    @Shadow @Final private ItemStackHandler inventory;

    @Unique
    private List<ItemStack> dynamicfood$inputs = List.of();

    @Inject(method = "processCooking(Lnet/minecraft/world/item/crafting/RecipeHolder;Lvectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity;)Z",
        at = @At(value = "HEAD", remap = false), remap = false)
    private void dynamicfood$captureInputs(RecipeHolder<?> recipe, @Coerce Object pot,
        CallbackInfoReturnable<Boolean> callback) {
        List<ItemStack> inputs = new ArrayList<>();
        for (int slot = 0; slot < 6; slot++) {
            ItemStack input = inventory.getStackInSlot(slot);
            if (!input.isEmpty()) {
                ItemStack consumed = input.copy();
                consumed.setCount(1);
                inputs.add(consumed);
            }
        }
        dynamicfood$inputs = List.copyOf(inputs);
    }

    @Inject(method = "processCooking(Lnet/minecraft/world/item/crafting/RecipeHolder;Lvectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity;)Z",
        at = @At(value = "RETURN", remap = false), remap = false)
    private void dynamicfood$publish(RecipeHolder<?> recipe, @Coerce Object pot,
        CallbackInfoReturnable<Boolean> callback) {
        try {
            if (!callback.getReturnValue() || dynamicfood$inputs.isEmpty()) {
                return;
            }
            BlockEntity blockEntity = (BlockEntity) (Object) this;
            Level level = blockEntity.getLevel();
            if (level == null || recipe == null) {
                return;
            }
            ItemStack output = inventory.getStackInSlot(6);
            OptionalTransactionSupport.post(level, recipe.value(), List.of(output), dynamicfood$inputs, List.of(),
                recipe.id().toString(), "farmersdelight:cooking_pot");
        } finally {
            dynamicfood$inputs = List.of();
        }
    }
}