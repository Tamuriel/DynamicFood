package com.jorjik.dynamicfood.compat.mixin;

import com.jorjik.dynamicfood.compat.OptionalTransactionSupport;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.simibubi.create.foundation.recipe.RecipeApplier", remap = false)
public abstract class CreateRecipeApplierMixin {
    @Inject(method = "applyRecipeOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/Recipe;Z)Ljava/util/List;", at = @At(value = "RETURN", remap = false), remap = false)
    private static void dynamicfood$publish(Level level, ItemStack input, Recipe<?> recipe, boolean simulate,
        CallbackInfoReturnable<List<ItemStack>> callback) {
        dynamicfood$publishOperation(level, input, recipe, simulate, callback.getReturnValue());
    }

    @Inject(method = "applyRecipeOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/Recipe;)Ljava/util/List;", at = @At(value = "RETURN", remap = false), remap = false)
    private static void dynamicfood$publishLegacy(Level level, ItemStack input, Recipe<?> recipe,
        CallbackInfoReturnable<List<ItemStack>> callback) {
        dynamicfood$publishOperation(level, input, recipe, false, callback.getReturnValue());
    }

    private static void dynamicfood$publishOperation(Level level, ItemStack input, Recipe<?> recipe,
        boolean simulate, List<ItemStack> outputs) {
        if (simulate || level == null || recipe == null) {
            OptionalTransactionSupport.clearCreateDeployer();
            return;
        }
        OptionalTransactionSupport.CreateDeployerContext context =
            OptionalTransactionSupport.createDeployerContext().orElse(null);
        try {
            String type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
            if (context != null && context.level() == level && type.equals("create:deploying")
                && !context.inputs().isEmpty()
                && ItemStack.isSameItemSameComponents(input, context.inputs().getFirst())) {
                OptionalTransactionSupport.post(level, recipe, outputs, context.inputs(), List.of(),
                    "runtime:create_deploying", "create:deployer");
            } else {
                OptionalTransactionSupport.post(level, recipe, outputs, List.of(input),
                    "runtime:create_recipe_applier");
            }
        } finally {
            if (context != null) {
                OptionalTransactionSupport.clearCreateDeployer();
            }
        }
    }
}