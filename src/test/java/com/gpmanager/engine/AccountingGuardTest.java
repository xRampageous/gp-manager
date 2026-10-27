package com.gpmanager;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.runelite.api.GrandExchangeOfferState.*;
public class AccountingGuardTest {
    @Test public void nonnegativeCountAdditionRetainsItsClamp() {
        var random = new Random(20261022);
        for (int left : new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE})
            for (int right : new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE})
                countClamp(left, right);
        for (int i = 0; i < 10000; i++) countClamp(random.nextInt(), random.nextInt());
    }

    static void countClamp(int left, int right) {
        long total = (long) Math.max(0, left) + Math.max(0, right);
        assertEquals(total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total,
                SafeMath.safeAddCount(left, right));
    }

    @Test public void reservedBasisRetainsItsSignedOverflowAndNegativeQuantityRules() {
        var random = new Random(20261022);
        for (long price : new long[]{Long.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE, Long.MAX_VALUE})
            for (long quantity : new long[]{Long.MIN_VALUE, -1, 0, 1, 2, Long.MAX_VALUE})
                reservedBasis(price, quantity);
        for (int i = 0; i < 10000; i++) reservedBasis(random.nextLong(), random.nextLong());
    }

    static void reservedBasis(long price, long quantity) {
        long expected;
        try { expected = Math.multiplyExact(price, Math.max(0, quantity)); }
        catch (ArithmeticException overflow) { expected = price >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE; }
        var offer = new GeOffer();
        offer.basisUnitPrice = price;
        assertEquals(expected, offer.basisFor(quantity));
    }

    @Test public void terminalReplacementCannotInheritAnotherItemsProgress() {
        for(var terminal:new net.runelite.api.GrandExchangeOfferState[]{SOLD,CANCELLED_SELL,BOUGHT,CANCELLED_BUY}) for(int item:new int[]{10,11}) {
            if(item==10 && OfferSlots.sideOf(terminal)==GeOffer.Side.SELL)continue;
            var custody=new GeCustodyLedger();var empty=new OfferSlots.Snapshot(0,EMPTY,0,0,0,0,0);
            var first=new OfferSlots.Snapshot(0,SELLING,10,5,0,7,0);
            custody.observeTransition(new OfferSlots.Transition(empty,first),"Old item",1000,"s1");
            var old=custody.records().get(0);
            var replacement=new OfferSlots.Snapshot(0,terminal,item,5,3,7,21);
            custody.observeTransition(new OfferSlots.Transition(first,replacement),"New item",1600,"s1");
            assertFalse("terminal replacement "+terminal,custody.records().contains(old));
            var current=custody.records().get(custody.records().size()-1);
            assertEquals(item,current.itemId);assertEquals(3,current.getFilledQty());
            assertEquals(GeOffer.CustodyConfidence.AMBIGUOUS,current.getConfidence());
        }
    }
    @Test public void pkDeathBooksOnlyObservedLossesAndNeverKeptEquipment() {
        var engine=ReviewFixesTest.engine();long now=ReviewFixesTest.T0;
        engine.setBaseline(new ContainerSnapshot(Map.of(4151,1L,385,4L,995,50L)));
        engine.markPkDeath(now, java.util.Collections.emptyMap(), null, engine.events.owner);
        var kept=new ContainerSnapshot(Map.of(4151,1L,995,50L));
        engine.processIfDirty(kept,now+600,null);engine.processIfDirty(kept,now+1200,null);
        assertEquals(-400,engine.session().metrics(now+1200).net);
        for(var row:engine.session().transactions())for(var flow:row.flows())assertEquals(385,flow.item);
        assertNull(engine.processIfDirty(kept,now+1800,null));assertEquals(-400,engine.session().metrics(now+1800).net);
    }
    @Test public void pvmWhitelistIsDetachedFromTheLiveContainerMap() {
        var engine=ReviewFixesTest.engine();long now=ReviewFixesTest.T0;
        var held=new HashMap<Integer,Long>();held.put(4151,1L);
        engine.setBaseline(new ContainerSnapshot(held));engine.markPvmDeath(20,held, Map.of(), engine.events.owner);held.clear();
        var empty=ContainerSnapshot.empty();engine.processIfDirty(empty,now+600,null);engine.processIfDirty(empty,now+1200,null);
        assertEquals(0,engine.session().metrics(now+1200).net);assertTrue(engine.deathStatus().pending);
    }
}
