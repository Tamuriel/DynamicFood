package com.jorjik.dynamicfood.provenance;

import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import com.jorjik.dynamicfood.core.IngredientContribution;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public final class CraftingProvenanceHandler {
    public void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        ItemStack result = event.getCrafting();
        if (result.isEmpty()) {
            return;
        }

        List<IngredientContribution> actualInputs = new ArrayList<>();
        for (int slot = 0; slot < event.getInventory().getContainerSize(); slot++) {
            ItemStack input = event.getInventory().getItem(slot);
            if (input.isEmpty()) {
                continue;
            }
            actualInputs.add(RuntimeFoodApplier.contribution(input, 1));
        }
        if (actualInputs.isEmpty()) {
            return;
        }

        String itemId = RuntimeFoodApplier.itemId(result);
        String recipeId = "runtime:crafting_unresolved";
        String recipeType = "minecraft:crafting";
        if (event.getInventory() instanceof CraftingContainer craftingContainer) {
            var match = event.getEntity().level().getRecipeManager().getRecipeFor(
                RecipeType.CRAFTING,
                craftingContainer.asCraftInput(),
                event.getEntity().level()
            );
            if (match.isPresent()) {
                RecipeHolder<?> holder = match.get();
                recipeId = holder.id().toString();
                recipeType = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()).toString();
            } else {
                DynamicFood.LOGGER.warn("Could not resolve the crafting RecipeHolder for {}; recording actual inputs with a crafting fallback", itemId);
            }
        }
        RuntimeProvenance provenance = new RuntimeProvenance(
            itemId,
            recipeId,
            recipeType,
            result.getCount(),
            actualInputs
        );
        RuntimeFoodApplier.apply(result, provenance);
    }

}
