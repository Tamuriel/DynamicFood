package com.jorjik.dynamicfood.compat;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import net.neoforged.fml.ModList;

public final class OptionalMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains("Create")) {
            return isModPresent("create");
        }
        if (mixinClassName.contains("Farmer")) {
            return isModPresent("farmersdelight");
        }
        return mixinClassName.contains("Vanilla");
    }

    private static boolean isModPresent(String modId) {
        ModList loadedMods = ModList.get();
        if (loadedMods != null) {
            return loadedMods.isLoaded(modId);
        }
        LoadingModList loadingMods = LoadingModList.get();
        return loadingMods != null && loadingMods.getMods().stream()
            .anyMatch(modInfo -> modInfo.getModId().equals(modId));
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}