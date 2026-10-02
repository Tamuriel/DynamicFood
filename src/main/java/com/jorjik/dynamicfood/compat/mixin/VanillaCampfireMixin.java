package com.jorjik.dynamicfood.compat.mixin;

import com.jorjik.dynamicfood.compat.OptionalTransactionSupport;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(value = CampfireBlockEntity.class, remap = false)
public abstract class VanillaCampfireMixin {
    @Shadow @Final private int[] cookingProgress;
    @Shadow @Final private int[] cookingTime;

    @Unique
    private static final ThreadLocal<Queue<CampfireContext>> DYNAMICFOOD_CAMPFIRE_CONTEXT = new ThreadLocal<>();

    @Inject(method = "cookTick", at = @At(value = "HEAD", remap = false), remap = false)
    private static void dynamicfood$capture(Level level, BlockPos pos, BlockState state,
        CampfireBlockEntity campfire, CallbackInfo callback) {
        Queue<CampfireContext> contexts = new ArrayDeque<>();
        VanillaCampfireMixin self = (VanillaCampfireMixin) (Object) campfire;
        for (int slot = 0; slot < campfire.getItems().size(); slot++) {
            ItemStack input = campfire.getItems().get(slot);
            if (input.isEmpty() || self.cookingProgress[slot] + 1 < self.cookingTime[slot]) {
                continue;
            }
            RecipeHolder<CampfireCookingRecipe> holder = level.getRecipeManager()
                .getRecipeFor(RecipeType.CAMPFIRE_COOKING, new SingleRecipeInput(input), level).orElse(null);
            if (holder == null) {
                continue;
            }
            ItemStack output = holder.value().assemble(new SingleRecipeInput(input), level.registryAccess());
            if (output.isItemEnabled(level.enabledFeatures())) {
                ItemStack consumed = input.copy();
                consumed.setCount(1);
                contexts.add(new CampfireContext(level, holder, consumed, output.getCount()));
            }
        }
        DYNAMICFOOD_CAMPFIRE_CONTEXT.set(contexts);
    }

    @ModifyArgs(
        method = "cookTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/Containers;dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V", remap = false),
        remap = false
    )
    private static void dynamicfood$publish(Args args) {
        Queue<CampfireContext> contexts = DYNAMICFOOD_CAMPFIRE_CONTEXT.get();
        if (contexts == null || contexts.isEmpty()) {
            return;
        }
        CampfireContext context = contexts.remove();
        ItemStack output = args.get(4);
        OptionalTransactionSupport.post(context.level(), context.holder().value(), output,
            List.of(context.input()), context.holder().id().toString(), context.outputCount());
        args.set(4, output);
    }

    @Inject(method = "cookTick", at = @At(value = "RETURN", remap = false), remap = false)
    private static void dynamicfood$clear(Level level, BlockPos pos, BlockState state,
        CampfireBlockEntity campfire, CallbackInfo callback) {
        DYNAMICFOOD_CAMPFIRE_CONTEXT.remove();
    }

    private record CampfireContext(Level level, RecipeHolder<CampfireCookingRecipe> holder,
        ItemStack input, int outputCount) {
    }
}