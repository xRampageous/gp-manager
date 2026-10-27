package com.gpmanager;

import java.util.*;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native ToA 20261025-031552: status1, one-based local slot, two retries then a real IRS wipe. */
public class RaidLiveDeathTest {
    static final Map<Integer,Long> HELD=Map.of(4151,1L,385,2L,27297,100L);
    @Test public void recordedToaParticipantIsRecognisedWithoutARewardClaim() {
        var f=new RaidHistoryTest();f.nativeRaids.pulse(f.now());
        f.instance=true;f.bits.put(14345,1);f.bits.put(14354,1);f.bits.put(14380,200);
        f.names.put(1099,"Player");f.widgets.add(481<<16);f.tick++;f.nativeRaids.pulse(f.now());
        assertNotNull(f.engine.raids.active);assertEquals(2,RaidHistory.kind(f.engine.raids.activeId()));
        assertEquals("Level 200",f.engine.raids.active.mode);assertFalse(f.engine.raids.active.partial);
    }
    @Test public void retryDeathKeepsGearWithoutArmingAGraveOrInventingLoss() {
        var f=new RaidHistoryTest();f.start();f.engine.setBaseline(new ContainerSnapshot(HELD));
        f.engine.markPvmDeath(10,HELD,Map.of(),f.engine.events.owner);
        assertTrue(f.engine.raids.active.died);assertEquals(f.id(),f.engine.raids.state.death);
        assertFalse(f.engine.death.wipePending());assertFalse(f.engine.death.isAwaitingReclaim());
        f.nativeRaids.chat("Your party failed to complete the challenge. You have 1 attempt remaining...",f.now());
        LmsBorrowedItemsTest.settle(f.engine,HELD,f.now()+1);
        assertTrue(f.engine.session().transactions().isEmpty());assertTrue(f.engine.getReviewRows(f.now()).isEmpty());
    }
    @Test public void exclusiveRaidSuppliesNeverEnterMoneyOrReview() {
        var e=LmsBorrowedItemsTest.engine();
        var held=Map.of(27297,100L,27315,2L,20996,1L,25730,3L);
        LmsBorrowedItemsTest.settle(e,held,2000);
        e.noteConsume(27315,6,false,ActionKind.DRINK,null);
        LmsBorrowedItemsTest.settle(e,Map.of(27297,99L,27317,1L,25730,2L),3000);
        LmsBorrowedItemsTest.settle(e,Map.of(),4000);
        assertTrue(e.session().transactions().isEmpty());assertTrue(e.getReviewRows(5000).isEmpty());
        assertEquals(0,e.metrics(5000).net);
    }
    @Test public void savedPhantomRaidOnlyRecoveryIsDroppedWithoutErasingRealOwnedGear() {
        var life=new DeathReclaimLifecycle();
        life.onPvmDeath(HELD);
        life.removed(List.of(new Flow(27297,"Neutralising potion",-99,0,0),new Flow(4151,"Whip",-1,100,-100)));
        var restored=new DeathReclaimLifecycle();restored.restore(life.snapshot());
        assertEquals(Map.of(4151,1L),restored.items);assertEquals(1,restored.outstanding());
        life=new DeathReclaimLifecycle();life.onPvmDeath(Map.of(27297,99L));
        life.removed(List.of(new Flow(27297,"Neutralising potion",-99,0,0)));
        restored.restore(life.snapshot());assertFalse(restored.isAwaitingReclaim());assertEquals(0,restored.outstanding());
    }
    static ItemContainer held(Map<Integer,Long> items) {
        return RaidHistoryTest.proxy(ItemContainer.class,(p,m,a)->m.getName().equals("getItems")
                ?items.entrySet().stream().map(e->new Item(e.getKey(),e.getValue().intValue())).toArray(Item[]::new):null);
    }
    @Test public void finalToaWipeAndReclaimIncludeTheBorrowedHammerWithoutBookingIt() {
        var f=new RaidHistoryTest(RaidSupplyLoanTest.engine());f.start();
        var gear=Map.of(4151,1L,2347,1L,27297,99L);
        f.containers.put(93,held(gear));f.engine.setBaseline(new ContainerSnapshot(gear));
        f.engine.raids.state.supplies.put(2347,1L);
        f.nativeRaids.chat("You failed to survive the Tombs of Amascut.",f.now());
        assertNull(f.engine.raids.active);assertTrue(f.engine.death.wipePending());
        RaidSupplyLoanTest.settle(f,Map.of());assertEquals(2,f.engine.death.outstanding());
        RaidSupplyLoanTest.settle(f,Map.of(4151,1L,2347,1L));
        assertEquals(0,f.engine.death.outstanding());assertTrue(f.engine.session().transactions().isEmpty());
        var fee=f.engine.bookDeathFee(500000,null,f.now());
        assertEquals(f.id(),fee.flows().get(0).raid);assertEquals(-500000,f.net(f.id()));
        assertTrue(f.engine.getReviewRows(f.now()).isEmpty());
    }
    @Test public void actualOutsideSampleCannotHideANewPveDeathDuringTheRaidExitGrace() {
        for(int kind:new int[]{0,2}){
            var f=new RaidHistoryTest();f.engine.raids.observe(new RaidHistory.Sample(kind,true,"Test",new int[]{0}),f.now(),1);
            var old=f.engine.raids.active;
            f.engine.raids.observe(new RaidHistory.Sample(kind,false,"Test",new int[]{0}),f.now()+600,2);
            f.engine.markPvmDeath(10,Map.of(4151,1L),Map.of(),f.engine.events.owner);
            assertTrue(f.engine.death.wipePending());assertNull(f.engine.raids.state.death);assertFalse(old.died);
            assertNull(f.engine.bookDeathFee(1000,null,f.now()).flows().get(0).raid);
        }
    }
    @Test public void coxDeathIsSafeButOrdinaryPveStillCreatesItsNeutralGrave() {
        var f=new RaidHistoryTest();f.engine.raids.observe(new RaidHistory.Sample(0,true,"Normal",new int[]{0}),f.now(),1);
        f.engine.markPvmDeath(10,HELD,Map.of(),f.engine.events.owner);
        assertFalse(f.engine.death.wipePending());assertTrue(f.engine.raids.active.died);
        f.engine.raids.end(f.now());f.engine.markPvmDeath(10,Map.of(4151,1L),Map.of(),f.engine.events.owner);
        assertTrue(f.engine.death.wipePending());assertNull(f.engine.raids.state.death);
    }
    @Test public void tobAndToaRetrievalStoresDoNotExpireAsFifteenMinuteGraves() {
        for(int kind:new int[]{1,2,3,-1}){
            var e=RaidSupplyLoanTest.engine();
            if(kind>=0){var run=e.raids.start(kind,"Test",2000,false);e.raids.state.death=run.id;}
            e.setBaseline(new ContainerSnapshot(Map.of(4151,1L)));e.armDeathReclaim(10,Map.of(4151,1L),Map.of());
            LmsBorrowedItemsTest.settle(e,Map.of(),3000);
            for(int i=0;i<1600;i++)e.processIfDirty(ContainerSnapshot.empty(),4000+i*600L,null);
            assertEquals(1,e.death.outstanding()); // Expired grave gear remains owned at Death's Office.
            assertEquals(kind==1||kind==2,e.death.isAwaitingReclaim());
        }
    }
    @Test public void retainedTobDeathsDoNotBookGearOrADeathFeeAndOwnFoodStillCosts() {
        for(int status:new int[]{2,3}){
            var f=RaidSupplyLoanTest.fixture(1);f.bits.put(6440,status);f.nativeRaids.pulse(f.now());
            var gear=Map.of(4151,1L,RaidSupplyLoanTest.FOOD,2L);f.engine.setBaseline(new ContainerSnapshot(gear));
            f.engine.markPvmDeath(10,gear,Map.of(),f.engine.events.owner);RaidSupplyLoanTest.settle(f,gear);
            assertTrue(f.engine.session().transactions().isEmpty());assertEquals(0,f.engine.death.outstanding());
            f.engine.noteConsume(RaidSupplyLoanTest.FOOD,6,false,ActionKind.EAT,null);
            RaidSupplyLoanTest.settle(f,Map.of(4151,1L,RaidSupplyLoanTest.FOOD,1L));
            assertEquals(100,f.engine.metrics(f.now()).cost);assertEquals(0,f.net(f.id()));
            assertTrue(f.engine.getReviewRows(f.now()).isEmpty());
        }
    }
}
