package com.gpmanager;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.runelite.api.GrandExchangeOfferState.*;

/** A proven custody splitter must leave complete, source-backed preparation eligible. */
public class ProductionCustodyRecipeTest {
    static final Map<Integer,Long> DOUGH_BEFORE=Map.of(1933,1L,1937,1L);
    static final Map<Integer,Long> DOUGH_AFTER=Map.of(2307,1L,1931,1L,1935,1L);

    static Map<Integer,Long> coins(Map<Integer,Long> items,long amount) {
        var result=new HashMap<>(items);result.put(995,amount);return result;
    }
    static ProductionRecentTest.Fixture refund(Map<Integer,Long> before) {
        var f=new ProductionRecentTest.Fixture(coins(before,1000));
        f.engine.valuation=deltas->{
            var flows=new ArrayList<Flow>();
            deltas.forEach((id,q)->{
                long price=id==995?1:ProductionRecentTest.price(id);
                flows.add(new Flow(id,id==995?"Coins":ProductionRecentTest.NAMES.getOrDefault(id,"Other"),q,
                    price,SafeMath.multiply(q,price),id==995?PriceSource.FACE_VALUE:PriceSource.GRAND_EXCHANGE));
            });return flows;
        };
        var slots=new OfferSlots();
        f.engine.noteGeOffer(slots.observe(new OfferSlots.Snapshot(0,BUYING,561,1,0,100,0)).get(),"Nature rune",f.at);
        f.become(coins(before,900));
        assertEquals(0,f.live().net);
        f.engine.noteGeOffer(slots.observe(new OfferSlots.Snapshot(0,CANCELLED_BUY,561,1,0,100,0)).get(),"Nature rune",f.at);
        f.engine.noteGeCollect(f.at);
        return f;
    }
    static void recipe(ProductionRecentTest.Fixture f,Transaction made,Map<Integer,Long> before,Map<Integer,Long> after) {
        assertEquals(TransactionType.PROCESSING,made.type());
        assertEquals("Cooking",made.activityName());
        assertFalse(ReviewRules.needsReview(made));
        assertTrue(made.flows().stream().noneMatch(flow->flow.item==995));
        assertNotNull(ProductionRecipes.match(made.flows()));
        assertEquals(100,f.engine.exchange.records.get(0).refundedGp);
        assertEquals(ProductionRecentTest.net(before,after),f.live().net);
        assertEquals(1,f.live().recent.size());
        var row=f.live().recent.get(0);
        assertEquals(f.live().net,row.net());
        var detail=LedgerData.capture(f.engine,f.at,LivePage.entryFor(row));
        // The canceled offer can retain incomplete Market coverage; its neutral refund is no profit.
        assertEquals(f.live().net,detail.net);
        assertEquals(f.live().net,detail.detail.group.value);
        assertEquals(made.flows().size(),detail.detail.group.entries.size());
    }
    @Test public void refundedCoinsAndDoughCountInEitherEvidenceOrdering() {
        for(boolean inventoryFirst:new boolean[]{false,true}) {
            var f=refund(DOUGH_BEFORE);
            if(inventoryFirst) {
                f.engine.markDirty();
                assertNull(f.engine.processIfDirty(new ContainerSnapshot(coins(DOUGH_AFTER,1000)),f.at+=600,null));
            }
            f.use(false);
            recipe(f,f.become(coins(DOUGH_AFTER,1000)),DOUGH_BEFORE,DOUGH_AFTER);
        }
    }
    @Test public void pizzaPreparationOutranksOnlyTheInferredPartialFoodFallback() {
        for(int[] step:new int[][]{{2283,1982,2285},{2285,1985,2287},{2289,319,2297}}) {
            var before=Map.of(step[0],1L,step[1],1L);var after=Map.of(step[2],1L);
            var f=refund(before);f.use(false);
            recipe(f,f.become(coins(after,1000)),before,after);
        }
    }
    @Test public void separatelySettledRefundAndAlreadyArmedProductionKeepWorking() {
        for(boolean separate:new boolean[]{false,true}) {
            var f=refund(DOUGH_BEFORE);
            if(separate)f.become(coins(DOUGH_BEFORE,1000));
            else f.engine.markContext(Context.PRODUCTION,6,"Cooking");
            f.use(false);
            recipe(f,f.become(coins(DOUGH_AFTER,1000)),DOUGH_BEFORE,DOUGH_AFTER);
        }
    }
    @Test public void missingExpiredOrOwnerFencedPreparationDoesNotBecomeProduction() {
        for(String boundary:List.of("missing","expired","owner","tracking")) {
            var f=refund(DOUGH_BEFORE);
            if(!boundary.equals("missing"))f.use(false);
            if(boundary.equals("expired"))f.engine.events.tick(100);
            if(boundary.equals("owner"))f.engine.events.owner(1);
            if(boundary.equals("tracking")) {
                f.engine.resetTracking(false);f.engine.setBaseline(new ContainerSnapshot(coins(DOUGH_BEFORE,900)));
            }
            assertNotEquals(boundary,TransactionType.PROCESSING,f.become(coins(DOUGH_AFTER,1000)).type());
        }
    }
    @Test public void incompleteWrongRatioAndUnrelatedResidualsStayUnmerged() {
        for(Map<Integer,Long> after:List.of(Map.of(2307,1L,1931,1L),Map.of(2307,2L,1931,1L,1935,1L),
            Map.of(2307,1L,1931,1L,1935,1L,560,1L))) {
            var f=refund(DOUGH_BEFORE);f.use(false);
            assertNotEquals(TransactionType.PROCESSING,f.become(coins(after,1000)).type());
        }
    }
    @Test public void explicitConsumeAndDropKeepTheirOwnership() {
        for(boolean drop:new boolean[]{false,true}) {
            var f=refund(Map.of(2289,1L,319,1L));f.use(false);
            if(drop)f.engine.noteDrop(2289,6);
            else f.engine.noteConsume(2289,6,false,ActionKind.EAT,null);
            Transaction made=f.become(coins(Map.of(2297,1L),1000));
            assertNotEquals("drop="+drop,TransactionType.PROCESSING,made.type());
        }
    }
    @Test public void transferMarketAndLootContextsDoNotGainRecipeAuthority() {
        for(Context context:new Context[]{Context.TRANSFER,Context.MARKET,Context.LOOT}) {
            var f=refund(DOUGH_BEFORE);f.use(false);
            f.engine.markContext(context,6,context==Context.MARKET?"Grand Exchange":"fixture");
            assertNotEquals(context.name(),TransactionType.PROCESSING,f.become(coins(DOUGH_AFTER,1000)).type());
        }
    }
    @Test public void residualGroupRemovalUndoAndReloadKeepOriginalPrices() {
        var f=refund(DOUGH_BEFORE);f.use(false);
        var made=f.become(coins(DOUGH_AFTER,1000));recipe(f,made,DOUGH_BEFORE,DOUGH_AFTER);
        String original=JsonCodec.gson().toJson(made.flows());
        assertTrue(f.engine.correctEntries(f.live().recent.get(0).receipts,Correction.IGNORE));
        assertEquals(0,f.live().net);
        var restored=ProductionRecipeTest.engine();restored.restore(SaveCopies.copy(f.engine.savedState()),f.at);
        assertTrue(restored.undoCorrection());
        assertEquals(73,restored.metrics(f.at+1).net);
        assertEquals(original,JsonCodec.gson().toJson(restored.session().findTransaction(made.id()).flows()));
    }
}
