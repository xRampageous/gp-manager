package com.gpmanager;

import java.util.*;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static net.runelite.api.GrandExchangeOfferState.*;
import static org.junit.Assert.*;

/** Claims made here count; snapshots of claims made elsewhere are baselines only. */
public class GeDeviceCollectionTest {
    static final int COINS=995, RUNE=561;
    static final long START=1_000_000_000_000L;

    static class H {
        Engine engine;
        final OfferSlots slots=new OfferSlots();
        Map<Integer,Long> inventory=new HashMap<>(Map.of(COINS,100_000L,RUNE,20L));
        long now=START;
        H() {
            JsonCodec.bind(new com.google.gson.Gson());
            engine=new Engine(delta->{
                var flows=new ArrayList<Flow>();
                delta.forEach((id,q)->flows.add(new Flow(id,id==COINS?"Coins":"Nature rune",q,
                    id==COINS?1:210,q*(id==COINS?1:210),id==COINS?PriceSource.FACE_VALUE:PriceSource.GRAND_EXCHANGE)));
                return flows;
            },new TransactionClassifier(),new GpManagerConfig(){public int stabilizationTicks(){return 1;}});
            engine.ensureSession(now);
            engine.setBaseline(new ContainerSnapshot(inventory));
        }
        void event(GrandExchangeOfferState state,int item,int total,int traded,int price,int spent) {
            now+=600;
            slots.observe(new OfferSlots.Snapshot(0,state,item,total,traded,price,spent))
                .ifPresent(t->engine.noteGeOffer(t,"Nature rune",now));
        }
        void seed(GrandExchangeOfferState state,boolean late) {
            slots.beginSeed();
            var initial=new OfferSlots.Snapshot(0,late?EMPTY:state,late?0:RUNE,late?0:10,late?0:10,late?0:210,late?0:2100);
            slots.observe(initial);slots.finishSeed();engine.seedGeSlots(Map.of(0,initial),now);
            if(late) event(state,RUNE,10,10,210,2100);
        }
        void settle() {
            engine.markDirty();
            for(int i=0;i<4;i++){now+=600;engine.processIfDirty(new ContainerSnapshot(inventory),now,null);}
        }
        void collect(boolean buy,boolean bank) {
            engine.noteGeCollect(now);
            event(EMPTY,0,0,0,0,0);
            if(!bank) {
                if(buy)inventory.merge(RUNE,10L,Long::sum);else inventory.merge(COINS,2060L,Long::sum);
                settle();
            }
            now+=GeCustodyLedger.BANK_COLLECT_MILLIS+1;
            engine.tickGe(now);
        }
        long trades(){return engine.session().transactions().stream().filter(t->t.type()==TransactionType.TRADE).count();}
        long reviews(){return engine.session().transactions().stream().filter(ReviewRules::needsReview).count();}
        long net(){return engine.metrics(now).net;}
        void absentLogin(int mode) {
            now+=100_000;
            engine.exchange.clearPending();
            if(mode==0) engine.seedGeSlots(Map.of(0,new OfferSlots.Snapshot(0,EMPTY,0,0,0,0,0)),now);
            else if(mode==1) engine.seedGeSlots(Map.of(0,new OfferSlots.Snapshot(0,SELLING,RUNE,10,0,210,0)),now);
            else {
                engine.seedGeSlots(Map.of(0,new OfferSlots.Snapshot(0,EMPTY,0,0,0,0,0)),now);
                event(SELLING,RUNE,10,0,210,0);
            }
            now+=GeCustodyLedger.UNOBSERVED_GRACE_MILLIS+GeCustodyLedger.LOGIN_REPLAY_MILLIS+1000;
            engine.tickGe(now);
        }
        void listAndFill(int total,int filled) {
            event(SELLING,RUNE,total,0,210,0);
            inventory.put(RUNE,20L-total);settle();
            event(filled==total?SOLD:SELLING,RUNE,total,filled,210,filled*210);
        }
    }

    @Test public void remoteSaleCollectedHereBooksOnceForBothLoginOrders() {
        for(boolean late:new boolean[]{false,true}) {
            H h=new H();h.seed(SOLD,late);assertEquals(0,h.trades());
            h.collect(false,false);
            assertEquals(1,h.trades());assertEquals(0,h.reviews());assertEquals(-40,h.net());
            h.now+=700_000;h.engine.tickGe(h.now);assertEquals(1,h.trades());
        }
    }
    @Test public void remotePurchaseCollectedHereBooksOnceForBothLoginOrders() {
        for(boolean late:new boolean[]{false,true}) {
            H h=new H();h.seed(BOUGHT,late);h.collect(true,false);
            assertEquals(1,h.trades());assertEquals(0,h.reviews());assertEquals(0,h.net());
        }
    }
    @Test public void remoteOffersCollectedHereToBankBookOnce() {
        for(boolean buy:new boolean[]{false,true})for(boolean late:new boolean[]{false,true}) {
            H h=new H();h.seed(buy?BOUGHT:SOLD,late);h.collect(buy,true);
            assertEquals(1,h.trades());assertEquals(0,h.reviews());assertEquals(buy?0:-40,h.net());
            h.now+=700_000;h.engine.tickGe(h.now);assertEquals(1,h.trades());
        }
    }
    @Test public void claimsElsewhereStaySilentForEmptyChangedAndLateLoginSlots() {
        for(int mode=0;mode<3;mode++) {
            H h=new H();h.listAndFill(10,10);h.absentLogin(mode);
            assertEquals("login mode "+mode,0,h.trades());assertEquals(0,h.reviews());assertEquals(0,h.net());
        }
    }
    @Test public void alreadyCollectedPortionSurvivesWhenTheRemainderIsClaimedElsewhere() {
        H h=new H();h.listAndFill(20,4);
        h.engine.noteGeCollect(h.now);h.inventory.merge(COINS,824L,Long::sum);h.settle();
        h.now+=3000;h.engine.tickGe(h.now);assertEquals(1,h.trades());
        String id=h.engine.session().transactions().stream().filter(t->t.type()==TransactionType.TRADE).findFirst().get().id();
        h.event(SOLD,RUNE,20,20,210,4200);long net=h.net();h.absentLogin(0);
        assertEquals(1,h.trades());assertEquals(0,h.reviews());assertEquals(net,h.net());
        assertTrue(h.engine.exchange.records().stream().anyMatch(r->r.settlementId.equals(id)&&r.settledQty==4));
    }
    @Test public void awayClaimsNeverReturnReservedStockAsFreshKnownCoverage() {
        H h=new H();var pool=h.engine.basis.pool(RUNE);pool.availableQty=10;pool.availableBasisGp=2100;pool.acquiredAt=START;
        h.listAndFill(10,10);assertEquals(0,pool.availableQty);h.absentLogin(0);
        assertEquals(0,pool.availableQty);assertTrue(pool.fencedAt>START);assertEquals(0,h.trades());
    }
    @Test public void anUncollectedRemotePurchaseRemainsAvailableUntilItsLocalClaim() {
        H h=new H();h.seed(BOUGHT,false);h.now+=700_000;h.engine.tickGe(h.now);
        assertEquals(1,h.engine.exchange.records().size());assertEquals(0,h.trades());assertEquals(0,h.reviews());
        h.collect(true,false);assertEquals(1,h.trades());assertEquals(0,h.reviews());
    }
    @Test public void aFirstSeenSaleWithoutPrincipalBooksOnlyWhenCollectedHere() {
        H h=new H();h.seed(EMPTY,true);
        // First terminal snapshot after an EMPTY seed has no observed placement loss.
        h.event(SOLD,RUNE,10,10,210,2100);
        assertEquals(0,h.trades());h.collect(false,false);
        assertEquals(1,h.trades());assertEquals(0,h.reviews());assertEquals(-40,h.net());
    }
    @Test public void remoteCancellationReturnsPrincipalWithoutCreatingIncome() {
        for(boolean buy:new boolean[]{false,true}) {
            H h=new H();h.slots.beginSeed();
            var snapshot=new OfferSlots.Snapshot(0,buy?CANCELLED_BUY:CANCELLED_SELL,RUNE,10,0,210,0);
            h.slots.observe(snapshot);h.slots.finishSeed();h.engine.seedGeSlots(Map.of(0,snapshot),h.now);
            h.engine.noteGeCollect(h.now);h.event(EMPTY,0,0,0,0,0);
            h.inventory.merge(buy?COINS:RUNE,buy?2100L:10L,Long::sum);h.settle();
            assertEquals(0,h.reviews());assertEquals(0,h.trades());assertEquals(0,h.net());
        }
    }
    @Test public void anUnrelatedCoinPickupCannotClaimTheRemoteSale() {
        H h=new H();h.seed(SOLD,false);h.inventory.merge(COINS,777L,Long::sum);h.settle();
        assertEquals(0,h.trades());assertEquals(0,h.reviews());assertEquals(777,h.net());
        h.collect(false,false);assertEquals(1,h.trades());assertEquals(0,h.reviews());assertEquals(737,h.net());
    }
}
