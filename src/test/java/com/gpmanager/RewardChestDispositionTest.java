package com.gpmanager;

import java.lang.reflect.Proxy;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Reward dispositions must invalidate unconfirmed Bank-all evidence before settlement. */
public class RewardChestDispositionTest {
    static FracturedArchiveReadinessTest.Fixture fixture() {
        var f = new FracturedArchiveReadinessTest.Fixture();
        f.plugin.targets = GpManagerPluginProbe.tracker(f.plugin.client, f.engine, f.plugin.config);
        f.plugin.charges = new ChargeIntake(f.plugin.client, null, f.plugin.config, f.engine, null);
        return f;
    }
    static MenuOptionClicked menu(String option, int group) {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),new Class<?>[]{MenuEntry.class},
            (p,m,a) -> {
                switch (m.getName()) {
                    case "getOption": return option;
                    case "getTarget": return "Ascension shards";
                    case "getType": return MenuAction.CC_OP;
                    case "getParam1": return group << 16;
                    case "getItemId": return FracturedArchiveReadinessTest.SHARD;
                    default:
                        if (m.getReturnType() == int.class) return 0;
                        if (m.getReturnType() == boolean.class) return false;
                        return null;
                }
            });
        return new MenuOptionClicked(entry);
    }
    @Test public void refusedBankAllFollowedByDestroyCannotBecomeReceivedLoot() {
        var f=fixture();
        f.plugin.bankAll(InterfaceID.TFA_CHESTS);
        f.plugin.chestChanged(net.runelite.api.gameval.InventoryID.TFA_CHESTS,f.chest);
        assertEquals(0,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        f.plugin.onMenuOptionClicked(menu("Destroy",InterfaceID.TFA_CHESTS));
        f.shrink(0);
        assertEquals("destroyed rewards were not banked",0,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
    }
    @Test public void actualBankAllStillBooksItsMeasuredDecrease() {
        var f=fixture();f.plugin.onMenuOptionClicked(menu("Bank-all",InterfaceID.TFA_CHESTS));f.shrink(0);
        assertEquals(5000,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
    }
    @Test public void destructionWithoutBankIntentIsNotIncome() {
        var f=fixture();f.plugin.onMenuOptionClicked(menu("Destroy",InterfaceID.TFA_CHESTS));f.shrink(0);
        assertEquals(0,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
    }
    @Test public void unrelatedComponentActionsDoNotCancelMeasuredBankClaim() {
        var f=fixture();f.plugin.bankAll(InterfaceID.TFA_CHESTS);
        f.plugin.onMenuOptionClicked(menu("Destroy",42));f.shrink(0);
        assertEquals(5000,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
    }
    @Test public void consumedChestActionsCannotInvalidateAnExistingClaim() {
        var f=fixture();f.plugin.bankAll(InterfaceID.TFA_CHESTS);
        var event=menu("Destroy",InterfaceID.TFA_CHESTS);event.consume();f.plugin.onMenuOptionClicked(event);f.shrink(0);
        assertEquals(5000,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
    }
    @Test public void anotherMappedRewardChestDoesNotCancelThisClaim() {
        var f=fixture();f.plugin.bankAll(InterfaceID.TFA_CHESTS);
        f.plugin.onMenuOptionClicked(menu("Destroy",InterfaceID.TOB_CHESTS));f.shrink(0);
        assertEquals(5000,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
    }
    @Test public void takeAfterRefusedBankAllMustUseTheInventoryReceiptOnly() {
        var f=fixture();f.observe();f.plugin.bankAll(InterfaceID.TFA_CHESTS);
        f.plugin.onMenuOptionClicked(menu("Take-all",InterfaceID.TFA_CHESTS));f.shrink(0);
        f.settle(java.util.Map.of(FracturedArchiveReadinessTest.SHARD,50L));
        assertEquals(5000,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        assertEquals(50,RewardChestBankAllTest.received(f.engine,FracturedArchiveReadinessTest.SHARD));
    }
}
