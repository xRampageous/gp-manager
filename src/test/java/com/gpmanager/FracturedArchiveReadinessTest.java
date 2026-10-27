package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Offline raid/reward contracts; native event delivery remains an owner acceptance check. */
public class FracturedArchiveReadinessTest {
    static final String RAID="Fractured Archive";
    static final int SHARD=ItemID.ASCENSION_SHARDS;
    static final long T0=ReviewFixesTest.T0;
    LongSupplier previous;
    @Before public void clock() { previous=SafeMath.clock; SafeMath.clock=()->T0; }
    @After public void restoreClock() { SafeMath.clock=previous; }

    static class Probe extends GpManagerPlugin {
        @Override boolean canIngest() { return true; }
        @Override void pulse() { }
    }
    static class Fixture {
        Engine engine=ReviewFixesTest.engine();
        int tick=100;
        boolean visible, hidden;
        Widget raidHud=(Widget)Proxy.newProxyInstance(Widget.class.getClassLoader(),new Class<?>[]{Widget.class},
            (p,m,a)->"isHidden".equals(m.getName())?hidden:null);
        Item[] items={new Item(SHARD,50)};
        ItemContainer chest=(ItemContainer)Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
            new Class<?>[]{ItemContainer.class},(p,m,a)->"getItems".equals(m.getName())?items:null);
        Probe plugin=new Probe();
        Fixture() {
            engine.session().mode=SessionMode.AUTO;
            plugin.engine=engine; plugin.config=ReviewFixesTest.CONFIG;
            plugin.client=(Client)Proxy.newProxyInstance(Client.class.getClassLoader(),new Class<?>[]{Client.class},
                (p,m,a)->"isClientThread".equals(m.getName())?true:
                    "getGameState".equals(m.getName())?net.runelite.api.GameState.LOGGED_IN:
                    ("getVarbitValue".equals(m.getName())||"getVarpValue".equals(m.getName()))?0:
                    "getTickCount".equals(m.getName())?tick:
                    "getItemContainer".equals(m.getName())&&((int)a[0])==InventoryID.TFA_CHESTS?chest:
                    "getWidget".equals(m.getName())&&a.length==1&&((int)a[0])==InterfaceID.TfaHud.UNIVERSE&&visible?raidHud:null);
            plugin.lookup=new ItemLookup(null);
            plugin.containers=new ContainerSnapshotFactory(plugin.client,null);
            plugin.valuation=new Valuation(plugin.config,id->null,(id,active)->100, id -> false, Valuation.Economy.NORMAL);
            plugin.hud=new HudBuilder(plugin.config,null);
            plugin.filter=new LootPresentationFilterService(GroundItemsPrefs.disabled());
            plugin.activity=new ActivityDetector(plugin.config,engine);
        }
        void observe() { plugin.onLootReceived(new LootReceived(RAID,0,LootRecordType.EVENT,
            List.of(new ItemStack(SHARD,50)),1,null)); }
        Transaction settle(Map<Integer,Long> held) {
            engine.markDirty();
            ContainerSnapshot snapshot=new ContainerSnapshot(held);
            Transaction first=engine.processIfDirty(snapshot,T0+600,null);
            Transaction second=engine.processIfDirty(snapshot,T0+1200,null);
            return second==null?first:second;
        }
        void shrink(int quantity) {
            items=quantity==0?new Item[0]:new Item[]{new Item(SHARD,quantity)};
            tick++; plugin.chestChanged(InventoryID.TFA_CHESTS,chest);
        }
    }

    @Test public void constantsAndRecognisedSourceAreExact() {
        assertEquals(973,InterfaceID.TFA_CHESTS);
        assertEquals(1029,InventoryID.TFA_CHESTS);
        assertEquals(Integer.valueOf(InventoryID.TFA_CHESTS),GpManagerPlugin.CHESTS.get(InterfaceID.TFA_CHESTS));
        assertTrue(RewardChestCatalogue.isRewardChest(RAID));
        assertFalse(RewardChestCatalogue.isRewardChest("Archive Guardian"));
    }
    @Test public void previewNamesTheActivityWithoutBookingAndDuplicateSignalsStaySingle() {
        Fixture f=new Fixture(); f.observe(); f.observe();
        assertEquals(RAID,f.engine.session().activityHint());
        assertEquals(0,f.engine.metrics(T0).net);
        assertTrue(f.engine.session().transactions().isEmpty());
        assertEquals(1,f.plugin.hud.tray.entries().size());
        assertEquals(HudTray.State.PENDING,f.plugin.hud.tray.entries().get(0).state);
        assertEquals(50,f.plugin.hud.tray.entries().get(0).quantity);
    }
    @Test public void inventoryClaimConsumesItsPreviewWithoutCountingTwice() {
        Fixture f=new Fixture(); f.observe();
        Transaction receipt=f.settle(Map.of(SHARD,50L));
        assertNotNull(receipt); f.plugin.transactionBooked(receipt,T0+600);
        assertEquals(5_000,f.engine.metrics(T0+600).net);
        assertEquals(1,f.plugin.hud.tray.entries().size());
        assertEquals(HudTray.State.CLAIMED,f.plugin.hud.tray.entries().get(0).state);
        assertEquals(50,f.plugin.hud.tray.entries().get(0).quantity);
        assertNull(f.settle(Map.of(SHARD,50L)));
    }
    @Test public void bankAllCountsOnlyTheMeasuredPartialRemovalAndRepeatedClicksAddNothing() {
        Fixture f=new Fixture(); f.observe(); f.plugin.bankAll(InterfaceID.TFA_CHESTS);
        f.plugin.chestChanged(InventoryID.TFA_CHESTS,f.chest);
        assertNull(f.settle(Map.of()));
        f.shrink(20);
        f.settle(Map.of());
        Transaction receipt=f.engine.session().transactions().get(0);
        assertNotNull(receipt); assertEquals(3_000,receipt.net());
        assertEquals(3_000,f.engine.metrics(T0+600).net);
        f.plugin.bankAll(InterfaceID.TFA_CHESTS);
        f.plugin.chestChanged(InventoryID.TFA_CHESTS,f.chest);
        assertNull(f.settle(Map.of()));
    }
    @Test public void bankContainerCallbackCannotHideTheMeasuredRewardClaim() {
        Fixture f=new Fixture(); f.observe(); f.plugin.bankAll(InterfaceID.TFA_CHESTS);
        f.engine.markContext(Context.TRANSFER,8,"Bank transfer");
        f.shrink(0); f.settle(Map.of());
        assertEquals(5_000,f.engine.metrics(T0+600).net);
    }
    @Test public void unrelatedAndExpiredChestChangesDoNotBook() {
        Fixture f=new Fixture(); f.plugin.bankAll(InterfaceID.TFA_CHESTS);
        f.items=new Item[0]; f.plugin.chestChanged(InventoryID.BANK,f.chest);
        assertNull(f.settle(Map.of()));
        f.tick+=11; f.plugin.chestChanged(InventoryID.TFA_CHESTS,f.chest);
        assertNull(f.settle(Map.of()));
        assertEquals(0,f.engine.metrics(T0+600).net);
    }
    @Test public void reloadingAnUnclaimedPreviewDoesNotCreateIncome() {
        Fixture f=new Fixture(); f.observe();
        Engine restored=ReviewFixesTest.engine(); restored.restore(f.engine.savedState(),T0+600);
        assertEquals(0,restored.metrics(T0+600).net);
        assertTrue(restored.session().transactions().isEmpty());
    }
    @Test public void genericRewardAndDisabledDetectionKeepTheExistingActivity() {
        Fixture f=new Fixture(); f.plugin.activity.observe("Mining",true);
        f.plugin.onLootReceived(new LootReceived("Reward",0,LootRecordType.EVENT,List.of(),1,null));
        assertEquals("Mining",f.engine.session().activityHint());
        GpManagerConfig off=new GpManagerConfig(){@Override public boolean autoActivityDetection(){return false;}};
        f.plugin.activity=new ActivityDetector(off,f.engine); f.observe();
        assertEquals("Mining",f.engine.session().activityHint());
    }
    @Test public void visibleRaidHudNamesTheActivityBeforeAnyRewardsWithoutBooking() {
        Fixture f=new Fixture(); f.visible=true; f.plugin.raidActivity();
        assertEquals(RAID,f.engine.session().activityHint());
        assertTrue(f.engine.session().transactions().isEmpty());
        assertEquals(0,f.engine.metrics(T0).net);
    }
    @Test public void missingHiddenDisabledAndFixedSessionHudStatesKeepTheirActivity() {
        Fixture f=new Fixture(); f.plugin.activity.observe("Mining",true);
        f.plugin.raidActivity(); assertEquals("Mining",f.engine.session().activityHint());
        f.visible=true; f.hidden=true; f.plugin.raidActivity();
        assertEquals("Mining",f.engine.session().activityHint());
        f.hidden=false;
        f.plugin.activity=new ActivityDetector(new GpManagerConfig(){@Override public boolean autoActivityDetection(){return false;}},f.engine);
        f.plugin.raidActivity(); assertEquals("Mining",f.engine.session().activityHint());
        f.engine.session().mode=SessionMode.GENERAL;
        f.plugin.activity=new ActivityDetector(f.plugin.config,f.engine); f.plugin.raidActivity();
        assertEquals("Mining",f.engine.session().activityHint());
    }
    @Test public void bankAllKeepsOrdinaryBankTransfersNeutral() {
        Fixture f=new Fixture();
        f.engine.markContext(Context.TRANSFER,8,"Bank transfer");
        f.settle(Map.of(SHARD,50L));
        assertEquals(0,f.engine.metrics(T0+1200).net);
    }
    @Test public void pausedAndOldOwnerChestRemovalsCannotBook() {
        Fixture paused=new Fixture(); paused.plugin.bankAll(InterfaceID.TFA_CHESTS);
        paused.engine.session().paused=true; paused.shrink(0);
        assertEquals(0,paused.engine.metrics(T0+1200).net);
        Fixture old=new Fixture(); old.plugin.bankAll(InterfaceID.TFA_CHESTS);
        old.engine.events.owner++; old.shrink(0);
        assertEquals(0,old.engine.metrics(T0+1200).net);
    }
    @Test public void aSavedBankAllClaimSurvivesReloadWithoutRebooking() {
        Fixture f=new Fixture(); f.observe(); f.plugin.bankAll(InterfaceID.TFA_CHESTS); f.shrink(0);
        assertEquals(5_000,f.engine.metrics(T0+1200).net);
        SavedState saved=f.engine.savedState(); f.engine.restore(saved,T0+1200);
        f.plugin.chestChanged(InventoryID.TFA_CHESTS,f.chest);
        assertEquals(5_000,f.engine.metrics(T0+1200).net);
        assertEquals(1,f.engine.session().transactions().size());
    }
}
