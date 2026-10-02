package com.jorjik.dynamicfood.adapter;

import com.jorjik.dynamicfood.config.DynamicFoodConfig;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RecipeAdapterRegistry {
    private final List<RecipeAdapter> adapters = new CopyOnWriteArrayList<>();

    public RecipeAdapterRegistry() {
        adapters.addAll(List.of(
            adapter("minecraft:crafting", 0),
            adapter("minecraft:crafting_shaped", 0),
            adapter("minecraft:crafting_shapeless", 0),
            adapter("minecraft:smelting", 1),
            adapter("minecraft:smoking", 1),
            adapter("minecraft:campfire_cooking", 1),
            adapter("farmersdelight:cooking", 2),
            adapter("farmersdelight:cutting", 2),
            adapter("farmersdelight:dough", 0),
            adapter("farmersdelight:food_serving", 0),
            adapter("create:mixing", 3),
            adapter("create:filling", 3),
            adapter("create:deploying", 3),
            adapter("create:item_application", 3),
            adapter("create:compacting", 3),
            adapter("create:milling", 2),
            adapter("create:pressing", 2),
            adapter("create:cutting", 2),
            adapter("create:emptying", 2),
            adapter("create:splashing", 2),
            adapter("aquaculture:crafting_special_fish_fillet", 0),
            adapter("fruitsdelight:jam_craft_shapeless", 0),
            adapter("brewinandchewin:fermenting", 2),
            adapter("hearthandharvest:aging", 2),
            adapter("hearthandharvest:stomping", 2),
            adapter("kaleidoscope_cookery:millstone", 2),
            adapter("ratatouille:squeezing", 2),
            adapter("ratatouille:threshing", 2),
            adapter("ratatouille_fried_delights:coating", 2),
            adapter("ratatouille_fried_delights:frying", 2),
            adapter("expandeddelight:juicing", 2),
            adapter("immersiveengineering:cloche", 2),
            adapter("immersiveengineering:crusher", 2),
            adapter("immersiveengineering:fermenter", 2),
            adapter("immersiveengineering:metal_press", 2),
            adapter("immersiveengineering:squeezer", 2)
        ));
    }

    public void register(RecipeAdapter adapter) {
        adapters.add(adapter);
    }

    public Optional<RecipeAdapter> find(String recipeType) {
        if (!DynamicFoodConfig.allowsRecipeType(recipeType)) {
            return Optional.empty();
        }
        return adapters.stream()
            .filter(adapter -> adapter.supports(recipeType))
            .max(Comparator.comparingInt(RecipeAdapter::stationDifficulty));
    }

    public List<RecipeAdapter> adapters() {
        return adapters;
    }

    private static RecipeAdapter adapter(String type, int difficulty) {
        return new RecipeAdapter() {
            @Override public boolean supports(String candidate) { return type.equals(candidate); }
            @Override public int stationDifficulty() { return difficulty; }
        };
    }
}
