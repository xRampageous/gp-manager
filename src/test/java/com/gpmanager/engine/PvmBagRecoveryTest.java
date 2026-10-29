package com.gpmanager;

import com.google.gson.Gson;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Wiki: a Wilderness PvM death sends known bag contents, including supplies, to the grave. */
public class PvmBagRecoveryTest {
    static final Map<Integer,Long> BAG=Map.of(1079,2L,995,693L,24595,2L);
    static Engine engine() {
        var e=PvpGravestoneTest.engine();e.setBaseline(FixtureReads.empty());return e;
    }
    static void mark(Engine e,Map<Integer,Long> held,Map<Integer,Long> bag,long owner) {
        try { Engine.class.getDeclaredMethod("markPvmDeath",int.class,Map.class,Map.class,long.class)
                .invoke(e,20,held,bag,owner); }
        catch(ReflectiveOperationException ex) { throw new AssertionError("Missing owner-fenced PvM bag custody",ex); }
    }
    static void mark(Engine e,Map<Integer,Long> held,Map<Integer,Long> bag) { mark(e,held,bag,e.events.owner); }
    @Test public void closedBankPollingCannotEraseRespawnAndGroundSupplyOwnership() {
        var e=new Engine(PvpGravestoneTest.engine().valuation,new TransactionClassifier(),new GpManagerConfig(){
            @Override public int stabilizationTicks(){return 0;}
        });e.ensureSession(1000);
        var held=Map.of(7946,27L,8013,9L);
        e.setBaseline(new ContainerSnapshot(held));mark(e,held,Map.of());
        for(int i=0;i<3;i++)e.bankClosed();
        PvpGravestoneTest.settle(e,Map.of(8013,3L),3000);
        assertEquals(33,FixtureReads.deathStatus(e).items);
        assertTrue(e.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Loot","Grave"),20));
        PvpGravestoneTest.settle(e,Map.of(8013,9L),4000);
        assertEquals(27,FixtureReads.deathStatus(e).items);
        for(int i=1;i<=27;i++){
            e.bankClosed();PvpGravestoneTest.settle(e,Map.of(8013,9L,7946,(long)i),5000+i*10);
        }
        assertEquals(0,e.metrics(6000).income);assertEquals(0,e.metrics(6000).net);
        assertNull(e.savedState().pendingDeathReclaim);
        assertTrue(e.session().transactions().stream().allMatch(Transaction::isTransfer));
    }
    @Test public void bagOnlySuppliesCoinsAndEquipmentReturnAtZero() {
        var e=engine();mark(e,Map.of(),BAG);
        assertEquals(697,FixtureReads.deathStatus(e).items);assertTrue(FixtureReads.deathStatus(e).pending);
        PvpGravestoneTest.settle(e,BAG,3000);
        assertEquals(0,e.metrics(4000).net);assertEquals(0,e.metrics(4000).income);
        assertNull(e.savedState().pendingDeathReclaim);
        assertEquals(1,e.session().transactions().stream().filter(Transaction::isTransfer).count());
        assertEquals(0,e.session().pk().deaths);
    }
    @Test public void sameItemInBagAndPackSupportsPartialReturnsAndRestart() {
        var e=engine();e.setBaseline(new ContainerSnapshot(Map.of(1079,1L)));
        mark(e,Map.of(1079,1L),BAG);PvpGravestoneTest.settle(e,Map.of(),3000);
        PvpGravestoneTest.settle(e,Map.of(1079,2L),4000);
        assertEquals(696,FixtureReads.deathStatus(e).items);
        var gson=new Gson();var state=gson.fromJson(gson.toJson(e.savedState()),SavedState.class);
        var next=engine();next.restore(state,5000);next.resume(5100,PauseReason.IDLE,PauseReason.RECOVERY);
        next.setBaseline(new ContainerSnapshot(Map.of(1079,2L)));
        assertTrue(next.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Loot","Grave"),20));
        var all=new HashMap<>(BAG);all.put(1079,3L);PvpGravestoneTest.settle(next,all,6000);
        assertEquals(0,next.metrics(7000).net);assertNull(next.savedState().pendingDeathReclaim);
    }
    @Test public void sourceBackedPickupStillCountsAndCannotSpendTheGraveWhitelist() {
        var e=engine();mark(e,Map.of(),Map.of(1079,2L));
        var flows=new ArrayList<>(e.valuation.value(Map.of(1079,3L)));
        e.settleReclaim(flows,3000,Map.of(1079,1L),true);
        assertEquals(1,flows.size());assertEquals(1,flows.get(0).quantity);
        assertNull(e.savedState().pendingDeathReclaim);
    }
    @Test public void unknownBagAndStaleOwnerGrantNoCustody() {
        var e=engine();mark(e,Map.of(),BAG,e.events.owner-1);
        assertNull(e.savedState().pendingDeathReclaim);
        mark(e,Map.of(),Map.of());PvpGravestoneTest.settle(e,Map.of(1079,2L),3000);
        assertEquals(200,e.metrics(4000).income);
    }
    @Test public void observedFeeStaysLossWhileBagReturnsStayNeutral() {
        var e=engine();mark(e,Map.of(),Map.of(1079,2L));
        e.setBaseline(new ContainerSnapshot(Map.of(995,100_000L)));
        assertTrue(e.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Loot","Grave"),20));
        PvpGravestoneTest.settle(e,Map.of(1079,2L,995,50_000L),3000);
        assertEquals(-50_000,e.metrics(4000).net);
        assertEquals(0,e.metrics(4000).income);
        assertEquals(e.metrics(4000).cost,e.metrics(4000).other);
    }
    @Test public void physicalBagIsDestroyedWhileItsContentsRemainOwned() {
        var e=engine();e.setBaseline(new ContainerSnapshot(Map.of(11941,1L)));
        mark(e,Map.of(11941,1L),BAG);PvpGravestoneTest.settle(e,Map.of(),3000);
        assertEquals(-6,e.metrics(4000).net);
        assertFalse(e.death.items.containsKey(11941));
        assertEquals(697,FixtureReads.deathStatus(e).items);
        assertTrue(LedgerData.capture(e,4000,LiveCategoryLayoutTest.category(DeathViewTest.death()))
                .cost.groups.isEmpty());
        var view=LedgerData.capture(e,4000,LedgerData.Entry.current().withCostView(LedgerData.CostView.LOSS));
        assertEquals(-6,view.cost.total);
        assertEquals("Death: PvE",view.cost.groups.get(0).name);
        PvpGravestoneTest.settle(e,BAG,5000);
        assertEquals(-6,e.metrics(6000).net);assertEquals(0,e.metrics(6000).income);
    }
    @Test public void aNewPvmDeathCannotDiscardOwnedItemsAlreadyAtDeathsOffice() {
        var e=engine();mark(e,Map.of(),Map.of(1079,2L));
        for(int i=0;i<DeathReclaimLifecycle.AWAIT_TICKS;i++)e.death.tick(false);
        assertFalse(FixtureReads.deathStatus(e).pending);
        mark(e,Map.of(),Map.of(24595,2L));
        assertEquals(4,FixtureReads.deathStatus(e).items);
        assertTrue(e.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Loot","Grave"),20));
        PvpGravestoneTest.settle(e,Map.of(1079,2L,24595,2L),3000);
        assertEquals(0,e.metrics(4000).income);
    }
    @Test public void nativeEventSeedsKnownStockOnceOnlyInWildernessPvmContext() {
        for(boolean wilderness:new boolean[]{true,false}) {
            var f=new LootingBagPkLossTest.Fixture();f.dangerous=wilderness;f.plugin.combat=0;
            f.plugin.targets.ready();f.plugin.targets.npc=true;f.engine.rememberBag(BAG);
            f.engine.setBaseline(new ContainerSnapshot(Map.of(f.bagId,1L)));
            f.die();
            assertEquals(wilderness?697:0,FixtureReads.deathStatus(f.engine).items);
            assertTrue(f.engine.bagStock().isEmpty());
            assertEquals(0,f.engine.session().pk().deaths);
            if(wilderness) {
                f.plugin.targets.ready();f.plugin.targets.npc=true;f.die();
                assertEquals("cleared bag stock cannot be seeded twice",697,FixtureReads.deathStatus(f.engine).items);
            }
        }
    }
    @Test public void aPkDeathCannotDiscardItemsAlreadyAtDeathsOffice() {
        var e=engine();mark(e,Map.of(),Map.of(1079,2L));
        for(int i=0;i<DeathReclaimLifecycle.AWAIT_TICKS;i++)e.death.tick(false);
        e.markPkDeath(3000,Map.of(),Map.of(),e.events.owner);
        assertEquals(2,FixtureReads.deathStatus(e).items);
        assertTrue(e.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Loot","Grave"),20));
        PvpGravestoneTest.settle(e,Map.of(1079,2L),4000);
        assertEquals(0,e.metrics(5000).income);assertEquals(1,e.session().pk().deaths);
    }
}

