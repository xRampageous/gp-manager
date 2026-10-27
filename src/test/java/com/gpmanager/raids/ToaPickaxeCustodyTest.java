package com.gpmanager;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

/** A raid statue/cavity holds the player's pickaxe; one measured move is never income/spend. */
public class ToaPickaxeCustodyTest {
    @Before public void codec(){JsonCodec.bind(new com.google.gson.Gson());}
    static final int[] PICKS={1265,1267,1269,1271,1273,1275,12297,11920,12797,13243,13244,20014,23276,23680,23682,23684,25063,25112,25369,25376,30345,30346,30351};
    static RaidHistoryTest fixture(){var f=new RaidHistoryTest(RaidSupplyLoanTest.engine());f.start();return f;}
    static void settle(RaidHistoryTest f,Map<Integer,Long> items){RaidSupplyLoanTest.settle(f,items);}
    @Test public void nativeStatueAndLobbyCavityReturnEverySupportedTierAtZero() {
        for(int item:PICKS)for(int source:new int[]{45468,49567}){
            var f=fixture();f.nativeRaids.menu(RaidSupplyLoanTest.tool(source,"Take-pickaxe",false),f.now());
            settle(f,Map.of(item,1L));assertTrue("Pickaxe "+item+" from "+source,f.engine.session().transactions().isEmpty());
            assertTrue(f.engine.raids.state.supplies.isEmpty());assertTrue(f.engine.getReviewRows(f.now()).isEmpty());
        }
    }
    @Test public void depositingAndTakingAnOwnedPickaxePreservesItsExactBasis() {
        for(int item:PICKS){
            var f=fixture();settle(f,Map.of(item,1L));long money=f.engine.metrics(f.now()).net;
            var before=f.engine.savedState().trackedBasis;int count=f.engine.session().transactions().size();
            f.nativeRaids.menu(RaidSupplyLoanTest.tool(45468,"Deposit-pickaxe",false),f.now());settle(f,Map.of());
            f.nativeRaids.menu(RaidSupplyLoanTest.tool(45468,"Take-pickaxe",false),f.now());settle(f,Map.of(item,1L));
            assertEquals(money,f.engine.metrics(f.now()).net);assertEquals(count,f.engine.session().transactions().size());
            assertEquals(JsonCodec.gson().toJson(before),JsonCodec.gson().toJson(f.engine.savedState().trackedBasis));
            assertTrue(f.engine.raids.state.supplies.isEmpty());
        }
    }
    @Test public void lobbyCustodyWorksWithoutAnActiveRaidOrRaidHud() {
        var f=new RaidHistoryTest(RaidSupplyLoanTest.engine());
        f.nativeRaids.menu(RaidSupplyLoanTest.tool(49567,"Take-pickaxe",false),f.now());settle(f,Map.of(1275,1L));
        assertTrue(f.engine.session().transactions().isEmpty());assertNull(f.engine.raids.active);
    }
    @Test public void unrelatedLootStillBooksBesideBothDirectionsOfCustody() {
        var f=fixture();f.nativeRaids.menu(RaidSupplyLoanTest.tool(45468,"Take-pickaxe",false),f.now());
        settle(f,Map.of(1275,1L,565,2L));assertEquals(200,f.engine.metrics(f.now()).income);
        f.nativeRaids.menu(RaidSupplyLoanTest.tool(45468,"Deposit-pickaxe",false),f.now());
        settle(f,Map.of(565,3L));assertEquals(300,f.engine.metrics(f.now()).income);assertEquals(0,f.engine.metrics(f.now()).cost);
        assertTrue(f.engine.session().transactions().stream().flatMap(t->t.flows().stream()).noneMatch(flo->flo.item==1275));
    }
    @Test public void oneRequestCannotHideExtraPickaxesOrUnknownItems() {
        var f=fixture();f.nativeRaids.menu(RaidSupplyLoanTest.tool(45468,"Take-pickaxe",false),f.now());
        settle(f,Map.of(1275,2L,77777,1L));assertEquals(200,f.engine.metrics(f.now()).income);
    }
    @Test public void confirmedIndependentLootIsProtectedFromAStaleTake() {
        var f=fixture();f.nativeRaids.menu(RaidSupplyLoanTest.tool(45468,"Take-pickaxe",false),f.now());
        f.engine.markLoot(Map.of(1275,1L),6,"Loot from NPC","NPC");settle(f,Map.of(1275,1L));
        assertEquals(100,f.engine.metrics(f.now()).income);
    }
    @Test public void consumedWrongActionWrongObjectExamineExpiredAndForeignRequestsFailClosed() {
        for(int scenario=0;scenario<7;scenario++){
            var f=fixture();MenuOptionClicked click=RaidSupplyLoanTest.tool(scenario==2?123:45468,scenario==3?"Examine":"Take-pickaxe",scenario==1);
            if(scenario==0)click.consume();f.nativeRaids.menu(click,f.now());
            if(scenario==4)f.engine.events.now+=20;if(scenario==5)f.engine.events.owner++;
            if(scenario==6)f.engine.raids.restore(null);
            settle(f,Map.of(1275,1L));assertEquals("Scenario "+scenario,100,f.engine.metrics(f.now()).income);
        }
    }
    @Test public void aTakeCannotHideADepositAndARefusedClickCannotHideLaterLoot() {
        var f=fixture();f.engine.setBaseline(new ContainerSnapshot(Map.of(1275,1L)));
        f.nativeRaids.menu(RaidSupplyLoanTest.tool(45468,"Take-pickaxe",false),f.now());settle(f,Map.of());
        assertEquals(100,f.engine.metrics(f.now()).cost);
        f=fixture();f.nativeRaids.menu(RaidSupplyLoanTest.tool(45468,"Take-pickaxe",false),f.now());
        settle(f,Map.of());f.engine.events.now+=20;settle(f,Map.of(1275,1L));assertEquals(100,f.engine.metrics(f.now()).income);
    }
    @Test public void bothNamedCataloguesMatchThePinnedGamevals() throws Exception {
        var items=GameData.byId("raid_pickaxes");assertEquals(PICKS.length,items.size());
        for(int id:PICKS)assertEquals(id,net.runelite.api.gameval.ItemID.class.getField(items.get(id)).getInt(null));
        var sources=GameData.byId("raid_pickaxe_stores");assertEquals(2,sources.size());
        assertTrue(sources.containsKey(45468));assertTrue(sources.containsKey(49567));
        for(var entry:sources.entrySet()){
            var field=net.runelite.api.gameval.ObjectID.class.getField(entry.getValue());field.setAccessible(true);
            assertEquals(entry.getKey().intValue(),field.getInt(null));
        }
    }
}
