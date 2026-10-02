package com.jorjik.dynamicfood.compat.mixin;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import com.jorjik.dynamicfood.provenance.RuntimeFoodApplier;
import com.jorjik.dynamicfood.provenance.RuntimeProvenance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = AbstractFurnaceBlockEntity.class, remap = false)
public abstract class VanillaFurnaceMixin {
    @Redirect(
        method = "canBurn",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/crafting/AbstractCookingRecipe;assemble(Lnet/minecraft/world/item/crafting/SingleRecipeInput;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;", remap = false),
        remap = false
    )
    private static ItemStack dynamicfood$decorateComparison(AbstractCookingRecipe recipe, SingleRecipeInput input,
        HolderLookup.Provider registries, RegistryAccess access, RecipeHolder<?> holder,
        NonNullList<ItemStack> items, int maxStackSize, AbstractFurnaceBlockEntity furnace) {
        ItemStack result = recipe.assemble(input, registries);
        return RuntimeFoodApplier.decorateCandidate(result,
            provenance(holder, furnace, input, result.getCount()));
    }

    @Redirect(
        method = "burn",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/crafting/AbstractCookingRecipe;assemble(Lnet/minecraft/world/item/crafting/SingleRecipeInput;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;", remap = false),
        remap = false
    )
    private static ItemStack dynamicfood$decorateBurnedOutput(AbstractCookingRecipe recipe, SingleRecipeInput input,
        HolderLookup.Provider registries, RegistryAccess access, RecipeHolder<?> holder,
        NonNullList<ItemStack> items, int maxStackSize, AbstractFurnaceBlockEntity furnace) {
        ItemStack result = recipe.assemble(input, registries);
        return RuntimeFoodApplier.decorateCandidate(result,
            provenance(holder, furnace, input, result.getCount()));
    }

    private static RuntimeProvenance provenance(RecipeHolder<?> holder, AbstractFurnaceBlockEntity furnace,
        SingleRecipeInput input, int outputCount) {
        ItemStack consumed = input.getItem(0).copy();
        consumed.setCount(1);
        String recipeType = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()).toString();
        return RuntimeProvenance.fromStacks(
            BuiltInRegistries.ITEM.getKey(furnace.getLevel() == null
                ? consumed.getItem()
                : holder.value().getResultItem(furnace.getLevel().registryAccess()).getItem()).toString(),
            holder.id().toString(), recipeType, outputCount, java.util.List.of(consumed));
    }
}