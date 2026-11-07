package com.gpmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class UnpricedRecentTest {
    private static final long NOW=1_700_000_000_000L;

    private static Transaction row(long at,int id,String name,PriceSource source,long price) {
        return Tx.of(at,TransactionType.GAIN,Context.GENERIC,"",true,
                List.of(new Flow(id,name,1,price,price,source)));
    }

    private static Engine engine() {
        var engine=ReviewFixesTest.engine();engine.startCustom("Unloaded prices",NOW);return engine;
    }

    @Test public void ordinaryUnpricedItemsStayInLedgerWithoutCrowdingRecent() {
        var engine=engine();
        String[] names={"Iron dagger","Grain","Steel arrow"};
        for(int index=0;index<names.length;index++)engine.session().addTransaction(
                row(NOW+index,10_000+index,names[index],PriceSource.UNPRICED,0),2000,false);
        long revision=engine.revision();
        var snapshot=LiveSnapshot.capture(engine,NOW+100,LiveContext.NONE);
        assertTrue(snapshot.recent.isEmpty());
        var ledger=LedgerData.capture(engine,NOW+100,LedgerData.Entry.current());
        assertEquals(3,ledger.income.groups.size());
        assertTrue(ledger.income.groups.stream().allMatch(Semantics.Group::incomplete));
        assertEquals(3,engine.session().transactions().size());
        assertEquals(0,snapshot.net);assertEquals(revision,engine.revision());
        assertTrue(engine.session().transactions().stream().allMatch(value->value.flows().get(0).source()==PriceSource.UNPRICED));
    }

    @Test public void anExistingPriceReadyRefreshRevealsTheReceiptOnce() {
        var ready=new AtomicBoolean();
        var engine=new Engine(changes->{
            var flows=new ArrayList<Flow>();
            changes.forEach((id,quantity)->flows.add(new Flow(id,"Iron dagger",quantity,ready.get()?21:0,
                    ready.get()?21*quantity:0,ready.get()?PriceSource.GRAND_EXCHANGE:PriceSource.UNPRICED)));
            return flows;
        },new TransactionClassifier(),new GpManagerConfig(){});
        engine.startCustom("Price readiness",NOW);
        var receipt=row(NOW,10_000,"Iron dagger",PriceSource.UNPRICED,0);
        engine.session().addTransaction(receipt,2000,false);
        assertTrue(LiveSnapshot.capture(engine,NOW+100,LiveContext.NONE).recent.isEmpty());
        ready.set(true);engine.recheck();
        var snapshot=LiveSnapshot.capture(engine,NOW+200,LiveContext.NONE);
        assertEquals(1,snapshot.recent.size());assertFalse(snapshot.recent.get(0).unknown);
        assertEquals(21,snapshot.net);assertEquals(receipt.id(),snapshot.recent.get(0).receiptId);
        engine.recheck();
        assertEquals(1,engine.session().transactions().size());assertEquals(21,engine.metrics(NOW+300).net);
    }

    @Test public void knownZeroAndPricedReviewsRemainVisible() {
        var engine=engine();
        engine.session().addTransaction(row(NOW,10_000,"Known zero",PriceSource.NO_VALUE,0),2000,false);
        var review=new Transaction(NOW+1,TransactionType.UNCERTAIN,Context.GENERIC,"","Review",true,
                List.of(new Flow(526,"Bones",1,100,100,PriceSource.GRAND_EXCHANGE)),
                Confidence.UNCERTAIN,"No action evidence",null);
        engine.session().addTransaction(review,2000,false);
        var snapshot=LiveSnapshot.capture(engine,NOW+100,LiveContext.NONE);
        assertEquals(2,snapshot.recent.size());
        var zero=snapshot.recent.stream().filter(value->value.name.equals("Known zero")).findFirst().orElseThrow();
        assertFalse(zero.unknown);assertEquals("—",LivePage.valueText(zero));
        assertTrue(snapshot.recent.stream().anyMatch(value->"review".equals(value.tag)));
    }

    @Test public void hiddenUnpricedRowsDoNotUseUpTheRecentLimit() {
        var engine=engine();
        for(int index=0;index<35;index++) {
            engine.session().addTransaction(row(NOW+index,10_000+index,"Known "+index,PriceSource.GRAND_EXCHANGE,1),2000,false);
            engine.session().addTransaction(row(NOW+1000+index,20_000+index,"Unloaded "+index,PriceSource.UNPRICED,0),2000,false);
        }
        var snapshot=LiveSnapshot.capture(engine,NOW+2000,LiveContext.NONE);
        assertEquals(15,snapshot.recent.size());
        assertTrue(snapshot.recent.stream().allMatch(value->value.name.startsWith("Known ")));
        assertEquals(70,engine.session().transactions().size());assertEquals(35,snapshot.net);
    }

    @Test public void incompleteActionsKeepTheirKnownMoneyAndDetailedReceipt() {
        var engine=engine();
        var cast=Tx.of(NOW,TransactionType.CONSUMPTION,Context.GENERIC,"",true,List.of(
                new Flow(561,"Nature rune",-1,7,-7,PriceSource.GRAND_EXCHANGE),
                new Flow(554,"Fire rune",-1,0,0,PriceSource.UNPRICED)));
        cast.setAction(ActionKind.CAST);cast.setActionLabel(ActionLabel.of("High Level Alchemy"));
        engine.session().addTransaction(cast,2000,false);
        var snapshot=LiveSnapshot.capture(engine,NOW+100,LiveContext.NONE);
        assertTrue(snapshot.recent.isEmpty());assertEquals(-7,snapshot.net);
        var ledger=LedgerData.capture(engine,NOW+100,LedgerData.Entry.current());
        assertEquals(1,ledger.cost.groups.size());
        assertTrue(ledger.cost.groups.get(0).incomplete());assertEquals(-7,ledger.cost.total);
        assertEquals(-7,LedgerData.cards(ledger.cost.groups.get(0)).get(0).knownSubtotal);
        assertEquals(PriceSource.UNPRICED,cast.flows().get(1).source());
    }
}
