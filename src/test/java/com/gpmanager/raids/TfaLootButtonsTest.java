package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.LongSupplier;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import org.junit.*;
import static org.junit.Assert.*;

/** Native TFA button labels, accounting and raid proof must describe the same collection. */
public class TfaLootButtonsTest {
    LongSupplier previous;
    @Before public void clock() { previous=SafeMath.clock;SafeMath.clock=()->FracturedArchiveReadinessTest.T0; }
    @After public void restoreClock() { SafeMath.clock=previous; }

    static MenuOptionClicked button(String option,int widget) {
        int operation=option.equals("Bank-all")||option.equals("Discard-all")?6:1;
        MenuAction action=option.equals("Examine")?MenuAction.EXAMINE_ITEM:
                operation>5?MenuAction.CC_OP_LOW_PRIORITY:MenuAction.CC_OP;
        MenuEntry entry=(MenuEntry)Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),new Class<?>[]{MenuEntry.class},
                (p,m,a)->{
                    switch(m.getName()) {
                        case "getOption":return option;
                        case "getTarget":return "";
                        case "getType":return action;
                        case "getParam1":return widget;
                        case "getParam0":case "getItemId":return -1;
                        case "getIdentifier":return operation;
                        default:
                            if(m.getReturnType()==int.class)return 0;
                            if(m.getReturnType()==boolean.class)return false;
                            return null;
                    }
                });
        return new MenuOptionClicked(entry);
    }
    static MenuOptionClicked bank() {return button("Bank-all",InterfaceID.TfaChests.BANK);}
    static MenuOptionClicked take() {return button("Take-all",InterfaceID.TfaChests.INVENTORY);}
    static MenuOptionClicked discard() {return button("Discard-all",InterfaceID.TfaChests.DISCARD);}
    static FracturedArchiveReadinessTest.Fixture core() {return RewardChestDispositionTest.fixture();}
    static RaidHistoryTest nativeClaim(boolean bank) {
        var f=new RaidHistoryTest();
        f.bits.put(16416,1);f.widgets.add(InterfaceID.TfaChests.UNIVERSE);
        f.containers.put(InventoryID.TFA_CHESTS,f.chest(50));
        f.nativeRaids.menu(bank?bank():take(),f.now());
        assertNotNull(f.nativeRaids.request);
        return f;
    }
    static void deplete(RaidHistoryTest f,int remaining) {
        f.containers.put(InventoryID.TFA_CHESTS,f.chest(remaining));
        f.nativeRaids.container(InventoryID.TFA_CHESTS,f.containers.get(InventoryID.TFA_CHESTS));
    }
    static void collected(RaidHistoryTest f,boolean bank) {
        if(!bank)f.inv();
        deplete(f,0);
        var row=f.receipt();f.nativeRaids.booked(row,bank,bank?InventoryID.TFA_CHESTS:0);
        assertNotNull(row.flows().get(0).raid);
        assertEquals(5000,f.net(f.id()));
    }

    @Test public void sourceLabelsMatchThePinnedNamedWidgetConstants() throws Exception {
        var rows=GameData.rows("tfa_native_buttons");assertEquals(3,rows.size());
        assertEquals(List.of("Bank-all","Take-all","Discard-all"),rows.stream().map(r->r[2])
                .collect(java.util.stream.Collectors.toList()));
        for(var row:rows) {
            int widget=Integer.parseInt(row[0]);
            assertEquals(widget,InterfaceID.TfaChests.class.getField(row[1]).getInt(null));
            var click=button(row[2],widget);
            assertEquals(MenuAction.valueOf(row[4]),click.getMenuAction());
            assertEquals(Integer.parseInt(row[3]),click.getId());
            assertEquals(-1,click.getItemId());assertEquals(widget,click.getParam1());
            assertEquals(InterfaceID.TFA_CHESTS,widget>>>16);
        }
    }
    @Test public void nativeBankAllBooksOnlyActualPartialAndRepeatedDecreases() {
        var f=core();f.observe();f.plugin.onMenuOptionClicked(bank());
        assertEquals(0,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        f.shrink(20);assertEquals(3000,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        f.shrink(20);assertEquals(3000,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        f.shrink(0);assertEquals(5000,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        assertEquals(50,RewardChestBankAllTest.received(f.engine,FracturedArchiveReadinessTest.SHARD));
    }
    @Test public void nativeTakeAfterRefusedBankAllRequiresAnInventoryReceipt() {
        var f=core();f.observe();f.plugin.onMenuOptionClicked(bank());f.plugin.onMenuOptionClicked(take());
        f.shrink(0);assertEquals(0,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        f.settle(Map.of(FracturedArchiveReadinessTest.SHARD,50L));
        assertEquals(5000,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        assertEquals(50,RewardChestBankAllTest.received(f.engine,FracturedArchiveReadinessTest.SHARD));
    }
    @Test public void nativeDiscardAfterRefusedBankAllNeverBecomesBankedLoot() {
        var f=core();f.observe();f.plugin.onMenuOptionClicked(bank());
        f.plugin.onMenuOptionClicked(discard());f.shrink(0);
        assertEquals(0,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        assertEquals(0,RewardChestBankAllTest.received(f.engine,FracturedArchiveReadinessTest.SHARD));
    }
    @Test public void discardingRemainderKeepsOnlyTheAlreadyBankedPortion() {
        var f=core();f.observe();f.plugin.onMenuOptionClicked(bank());f.shrink(20);
        f.plugin.onMenuOptionClicked(discard());f.shrink(0);
        assertEquals(3000,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        assertEquals(30,RewardChestBankAllTest.received(f.engine,FracturedArchiveReadinessTest.SHARD));
    }
    @Test public void canceledDiscardDoesNotBookAndANewBankClickCanStillCollect() {
        var f=core();f.plugin.onMenuOptionClicked(discard());
        f.plugin.chestChanged(InventoryID.TFA_CHESTS,f.chest);
        assertEquals(0,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        f.plugin.onMenuOptionClicked(bank());f.shrink(0);
        assertEquals(5000,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
    }
    @Test public void consumedAndForeignDiscardClicksCannotCancelGlobalBankEvidence() {
        for(boolean consumed:new boolean[]{true,false}) {
            var f=core();f.plugin.onMenuOptionClicked(bank());
            var event=consumed?discard():button("Discard-all",InterfaceID.TOA_CHESTS<<16);
            if(consumed)event.consume();
            f.plugin.onMenuOptionClicked(event);f.shrink(0);
            assertEquals(5000,f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        }
    }
    @Test public void consumedTakeBankAndDiscardPreserveExistingNativeProof() {
        for(boolean bank:new boolean[]{true,false}) for(var event:List.of(take(),bank(),discard())) {
            var f=nativeClaim(bank);var previous=f.nativeRaids.request;
            event.consume();f.nativeRaids.menu(event,f.now());
            assertSame(previous,f.nativeRaids.request);
            collected(f,bank);
        }
    }
    @Test public void examineInsideTheChestCannotDropPendingNativeCollection() {
        for(boolean bank:new boolean[]{true,false}) {
            var f=nativeClaim(bank);var previous=f.nativeRaids.request;
            f.nativeRaids.menu(button("Examine",InterfaceID.TfaChests.ITEMS),f.now());
            assertSame(previous,f.nativeRaids.request);
            collected(f,bank);
        }
    }
    @Test public void nativeTakeAndBankSupportBothCallbackOrdersWithoutRetaggingTwice() {
        for(boolean bank:new boolean[]{true,false}) for(boolean first:new boolean[]{true,false}) {
            var f=nativeClaim(bank);if(!bank)f.inv();var row=f.receipt();
            if(first)f.nativeRaids.booked(row,bank,bank?InventoryID.TFA_CHESTS:0);
            deplete(f,0);
            if(!first)f.nativeRaids.booked(row,bank,bank?InventoryID.TFA_CHESTS:0);
            assertNotNull(row.flows().get(0).raid);assertEquals(5000,f.net(f.id()));
            f.nativeRaids.booked(row,bank,bank?InventoryID.TFA_CHESTS:0);
            assertEquals(5000,f.net(f.id()));
        }
    }
    @Test public void discardStopsFutureProofWithoutRemovingEarlierPartialRaidLoot() {
        var f=nativeClaim(true);deplete(f,20);
        var banked=f.row(565,30,100,TransactionType.LOOT);
        f.nativeRaids.booked(banked,true,InventoryID.TFA_CHESTS);
        assertNotNull(banked.flows().get(0).raid);assertEquals(3000,f.net(f.id()));
        f.nativeRaids.menu(discard(),f.now());assertNull(f.nativeRaids.request);
        deplete(f,0);var unrelated=f.row(565,20,100,TransactionType.LOOT);
        f.nativeRaids.booked(unrelated,true,InventoryID.TFA_CHESTS);
        assertNull(unrelated.flows().get(0).raid);assertEquals(3000,f.net(f.id()));
    }
    @Test public void aConsumedNativeClickWithoutAnEarlierRequestCannotArmOne() {
        var f=new RaidHistoryTest();
        f.bits.put(16416,1);f.widgets.add(InterfaceID.TfaChests.UNIVERSE);
        f.containers.put(InventoryID.TFA_CHESTS,f.chest(50));
        var event=bank();event.consume();f.nativeRaids.menu(event,f.now());
        assertNull(f.nativeRaids.request);assertTrue(f.engine.raids.state.runs.isEmpty());
    }
}
