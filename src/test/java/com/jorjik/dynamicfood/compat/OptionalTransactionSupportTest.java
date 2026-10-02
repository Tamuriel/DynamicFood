package com.jorjik.dynamicfood.compat;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.Disabled;

@Disabled("Requires Minecraft registries/bootstrapped test environment; skip in headless unit test run")
public class OptionalTransactionSupportTest {
    @AfterEach
    void tearDown() {
        OptionalTransactionSupport.clearCreateDeployer();
    }

    @Test
    public void beginCreateDeployer_withPlayerMainHand_setsContext() {
        Object deployer = new Object() {
            public Object getPlayer() {
                return new Object() {
                    public ItemStack getMainHandItem() {
                        return new ItemStack(Items.APPLE);
                    }
                };
            }
        };

        ItemStack target = new ItemStack(Items.BREAD);
        OptionalTransactionSupport.beginCreateDeployer(deployer, target);
        var ctx = OptionalTransactionSupport.createDeployerContext();
        assertTrue(ctx.isPresent(), "CreateDeployerContext should be present when player main hand is available");
        var inputs = ctx.get().inputs();
        assertEquals(2, inputs.size());
        assertEquals(Items.BREAD, inputs.get(0).getItem());
        assertEquals(Items.APPLE, inputs.get(1).getItem());
    }

    @Test
    public void beginCreateDeployer_fieldFallback_findsHeldItemField() {
        class DeployerWithField {
            public ItemStack held = new ItemStack(Items.CARROT);
        }
        Object deployer = new DeployerWithField();
        ItemStack target = new ItemStack(Items.BREAD);
        OptionalTransactionSupport.beginCreateDeployer(deployer, target);
        var ctx = OptionalTransactionSupport.createDeployerContext();
        assertTrue(ctx.isPresent(), "CreateDeployerContext should be present when a deployer has an ItemStack field");
        var inputs = ctx.get().inputs();
        assertEquals(2, inputs.size());
        assertEquals(Items.BREAD, inputs.get(0).getItem());
        assertEquals(Items.CARROT, inputs.get(1).getItem());
    }
}
