package com.gpmanager;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;


/** Captured prices are synthetic boundary values; no receipt is repriced by a view. */
public class LedgerDisplayTotalsTest {
    private static final long NOW=1_000_000, UNIT=4_000_000_000_000_000_000L;

    private static Flow priced(int id,String name,long quantity,long unit) {
        return new Flow(id,name,quantity,unit,SafeMath.multiply(quantity,unit),PriceSource.MANUAL_OVERRIDE);
    }

    private static Transaction peaches(long at,List<Flow> flows) {
        var row=Tx.of(at,TransactionType.CONSUMPTION,Context.GENERIC,"",true,flows);
        row.setAction(ActionKind.CAST);
        row.setActionLabel(ActionLabel.of("Bones to Peaches"));
        return row;
    }

    private static Engine groupedSpells() {
        var engine=ReviewFixesTest.engine();
        engine.startCustom("Grouped named spells",NOW);
        for(int index=0;index<5;index++)engine.session().addTransaction(peaches(NOW+index,List.of(
                priced(6883,"Peach",1,UNIT),priced(561,"Nature rune",-2,UNIT/4),
                priced(526,"Bones",-1,0))),2000,false);
        return engine;
    }

    private static Engine independent(long... values) {
        var engine=ReviewFixesTest.engine();
        engine.startCustom("Independent values",NOW);
        for(int index=0;index<values.length;index++) {
            long value=values[index];
            engine.session().addTransaction(Tx.of(NOW+index,value<0?TransactionType.CONSUMPTION:
                    TransactionType.GAIN,Context.GENERIC,"",true,
                    List.of(priced(10_000+index,"Resource "+index,value<0?-1:1,Math.abs(value)))),2000,false);
        }
        return engine;
    }

    private static Engine market(long... values) {
        var rows=new java.util.ArrayList<MarketRows.Row>();
        var engine=new Engine(changes->List.of(),new TransactionClassifier(),new GpManagerConfig(){}) {
            @Override synchronized List<MarketRows.Row> marketRows(){return rows;}
        };
        engine.startCustom("Realized results",NOW);
        for(int index=0;index<values.length;index++) {
            long value=values[index];int item=10_000+index;
            var receipt=Tx.of(NOW+index,value<0?TransactionType.CONSUMPTION:TransactionType.GAIN,
                    Context.MARKET,"",true,List.of(priced(item,"Market item "+index,value<0?-1:1,Math.abs(value))));
            engine.session().addTransaction(receipt,2000,false);
            var row=LiveCategoryLayoutTest.marketRow(engine);
            row.presentationId="offer"+index;row.itemId=item;row.itemName="Market item "+index;
            row.settlementId=receipt.id();row.timestampEpochMillis=NOW+index;
            row.realizedResultGp=value;row.observedSettlementGp=1;
            row.trackedBasisConsumedGp=value<0?Math.abs(value)+1:0;
            rows.add(row);
        }
        return engine;
    }

    private static String recentTotal(LiveSnapshot snapshot) throws Exception {
        return SwingTestSupport.onEdt(()->{
            var page=new LivePage(ReviewFixesTest.proxy(LivePage.Actions.class,Map.of()),id->null);
            page.apply(snapshot);
            return page.recent.total.getText();
        });
    }

    private static String tradeTotal(LedgerData data) throws Exception {
        return SwingTestSupport.onEdt(()->{
            var page=new LedgerPage(ReviewFixesTest.proxy(LedgerPage.Actions.class,Map.of()),id->null);
            page.apply(data);
            return page.market.total.getText();
        });
    }

    @Test public void aMixedSpellHeadlineMustMatchTheCorrectCardForEitherFlowOrder() {
        long unit=2_000_000_000_000_000_000L;
        var gain=priced(6883,"Peach",1,2*unit);
        var nature=priced(561,"Nature rune",-2,unit/2);
        var water=priced(555,"Water rune",-4,unit/2);
        var earth=priced(557,"Earth rune",-4,unit/2);
        var bone=priced(526,"Bones",-1,0);
        boolean correct=true;
        for(var flows:List.of(List.of(gain,nature,water,earth,bone),List.of(nature,water,earth,bone,gain))) {
            var row=peaches(NOW,flows);
            var group=SemanticProbe.capture(List.of(row),List.of(),"",null).groups.get(0);
            var card=LedgerData.cards(group).get(0);
            System.out.println("mixed spell canonical="+row.net()+" headline="+group.value+" detail="+card.value);
            assertEquals("card is correct",row.net(),card.value);
            correct &= row.net()==group.value;
        }
        assertTrue("headline must match the counted spell in either flow order",correct);
    }
    @Test public void groupedSpellHeadlinesMustFoldTheIncomeAndCostBeforeSubtracting() {
        long unit=2_000_000_000_000_000_000L;
        var session=new Session();
        for(int index=0;index<5;index++)session.addTransaction(peaches(NOW+index,List.of(
                priced(6883,"Peach",1,2*unit),priced(561,"Nature rune",-2,unit/2),priced(526,"Bones",-1,0))),2000,false);
        var group=SemanticProbe.capture(session.transactions(),List.of(),"",null).groups.get(0);
        var items=LedgerData.itemTotals(group);
        System.out.println("grouped spell canonical="+session.metrics(NOW+10).net+" headline="+group.value
                +" items="+items.get(0).value+","+items.get(1).value);
        assertEquals(0,session.metrics(NOW+10).net);
        assertEquals("group must agree with the same five canonical rows",0,group.value);
    }
    @Test public void aGroupWithAPositiveNetCannotStayInCostsAfterAnOpposingCast() {
        var older=peaches(NOW,List.of(priced(6883,"Peach",1,100),priced(561,"Nature rune",-2,5),priced(526,"Bones",-1,0)));
        var newer=peaches(NOW+1000,List.of(priced(6883,"Peach",1,10),priced(561,"Nature rune",-2,10),priced(526,"Bones",-1,0)));
        var result=SemanticProbe.capture(List.of(older,newer),List.of(),"",null);
        long misplaced=result.groups.stream().filter(group->group.value>0&&group.table==Semantics.Table.COSTS_SUPPLIES)
                .mapToLong(group->group.value).sum();
        for(var group:result.groups)System.out.println("sign crossing group value="+group.value+" table="+group.table);
        assertEquals("opposite net signs need separate groups or a recomputed financial destination",0,misplaced);
    }

    @Test public void tradeOverviewSubtotalCannotWrapThreeKnownProfitsNegative() throws Exception {
        SwingTestSupport.onEdt(()->{
            var markets=new java.util.ArrayList<MarketRows.Row>();
            var engine=new Engine(changes->List.of(),new TransactionClassifier(),new GpManagerConfig(){}) {
                @Override synchronized List<MarketRows.Row> marketRows(){return markets;}
            };
            engine.startCustom("Large exchanges",NOW);
            int[] ids={555,556,557};String[] names={"Water rune","Air rune","Earth rune"};
            for(int index=0;index<3;index++) {
                var receipt=Tx.of(NOW+index,TransactionType.GAIN,Context.MARKET,"",true,
                        List.of(priced(ids[index],names[index],1,4_000_000_000_000_000_000L)));
                engine.session().addTransaction(receipt,2000,false);
                var market=LiveCategoryLayoutTest.marketRow(engine);
                market.presentationId="offer"+index;market.itemId=ids[index];market.itemName=names[index];
                market.settlementId=receipt.id();market.timestampEpochMillis=NOW+index;
                market.realizedResultGp=4_000_000_000_000_000_000L;
                market.observedSettlementGp=market.realizedResultGp;market.trackedBasisConsumedGp=0;
                markets.add(market);
            }
            var data=LedgerData.capture(engine,NOW+1000,LedgerData.Entry.current());
            var page=new LedgerPage(ReviewFixesTest.proxy(LedgerPage.Actions.class,Map.of()),id->null);
            page.apply(data);
            System.out.println("market canonical="+engine.metrics(NOW+1000).net+" model="+data.market.total
                    +" displayed="+page.market.total.getText());
            assertEquals(Long.MAX_VALUE,engine.metrics(NOW+1000).net);
            assertEquals(Long.MAX_VALUE,data.market.total);
            assertEquals(Fmt.signed(Long.MAX_VALUE),page.market.total.getText());
            return null;
        });
    }

    @Test public void recentSubtotalCannotWrapThreeKnownGainsNegative() throws Exception {
        SwingTestSupport.onEdt(()->{
            var engine=ReviewFixesTest.engine();engine.startCustom("Large independent gains",NOW);
            int[] ids={555,556,557};String[] names={"Water rune","Air rune","Earth rune"};
            for(int index=0;index<3;index++)engine.session().addTransaction(Tx.of(NOW+index,
                    TransactionType.GAIN,Context.GENERIC,"",true,
                    List.of(priced(ids[index],names[index],1,4_000_000_000_000_000_000L))),2000,false);
            var snapshot=LiveSnapshot.capture(engine,NOW+1000,LiveContext.NONE);
            assertEquals(3,snapshot.recent.size());
            var page=new LivePage(ReviewFixesTest.proxy(LivePage.Actions.class,Map.of()),id->null);
            page.apply(snapshot);
            System.out.println("recent canonical="+snapshot.net+" displayed="+page.recent.total.getText());
            assertEquals(Long.MAX_VALUE,snapshot.net);
            assertEquals(Fmt.signed(Long.MAX_VALUE),page.recent.total.getText());
            return null;
        });
    }

    @Test public void groupedSpellSummaryRetainsBothGrossDirections() {
        var engine=groupedSpells();
        var projection=SemanticProbe.capture(engine.session().transactions(),List.of(),"",null);
        assertEquals(1,projection.groups.size());
        assertEquals(0,engine.metrics(NOW+100).net);
        assertEquals(0,Semantics.summarize(projection.groups).net());
        assertEquals(0,LedgerData.tableData(projection.groups,false).total);
    }

    @Test public void recentKeepsGrossBucketsWhenAZeroNetGroupHasAnAdditionalGain() throws Exception {
        var engine=groupedSpells();
        engine.session().addTransaction(Tx.of(NOW+10,TransactionType.GAIN,Context.GENERIC,"",true,
                List.of(priced(526,"Bones",1,300))),2000,false);
        var snapshot=LiveSnapshot.capture(engine,NOW+100,LiveContext.NONE);
        assertEquals(2,snapshot.recent.size());
        assertEquals(0,snapshot.net);
        assertEquals(Fmt.signed(0),recentTotal(snapshot));
        var data=LedgerData.capture(engine,NOW+100,LedgerData.Entry.current());
        assertEquals(0,data.total.net());
        assertTrue(data.balanced);
    }

    @Test public void negativeSpellHeadlineClampsAtTheCanonicalCostMagnitude() {
        var row=Tx.of(NOW,TransactionType.CONSUMPTION,Context.GENERIC,"",true,List.of(
                priced(560,"Death rune",-1,UNIT),priced(565,"Blood rune",-1,UNIT),
                priced(555,"Water rune",-1,UNIT)));
        row.setAction(ActionKind.CAST);row.setActionLabel(ActionLabel.of("Ice Barrage"));
        var group=SemanticProbe.capture(List.of(row),List.of(),"",null).groups.get(0);
        assertEquals(-Long.MAX_VALUE,row.net());
        assertEquals(row.net(),group.value);
        assertEquals(group.value,LedgerData.cards(group).get(0).value);
    }

    @Test public void negativeRecentSubtotalClampsInsteadOfWrapping() throws Exception {
        var engine=independent(-UNIT,-UNIT,-UNIT);
        var snapshot=LiveSnapshot.capture(engine,NOW+100,LiveContext.NONE);
        assertEquals(-Long.MAX_VALUE,snapshot.net);
        assertEquals(Fmt.signed(snapshot.net),recentTotal(snapshot));
    }

    @Test public void recentMixedSubtotalsAreIndependentOfArrivalOrder() throws Exception {
        for(var values:new long[][]{{UNIT,UNIT,UNIT,-300},{-300,UNIT,UNIT,UNIT},
                {-UNIT,-UNIT,-UNIT,300},{300,-UNIT,-UNIT,-UNIT}}) {
            var engine=independent(values);
            var snapshot=LiveSnapshot.capture(engine,NOW+100,LiveContext.NONE);
            assertEquals(Fmt.signed(engine.metrics(NOW+100).net),recentTotal(snapshot));
        }
    }

    @Test public void tradeNegativeSubtotalUsesTheCorrectCostMagnitude() throws Exception {
        var engine=market(-UNIT,-UNIT,-UNIT);
        var data=LedgerData.capture(engine,NOW+100,LedgerData.Entry.current());
        assertEquals(-Long.MAX_VALUE,engine.metrics(NOW+100).net);
        assertEquals(-Long.MAX_VALUE,data.market.total);
        assertEquals(Fmt.signed(-Long.MAX_VALUE),tradeTotal(data));
    }

    @Test public void tradeMixedSubtotalsAreIndependentOfArrivalOrder() throws Exception {
        for(var values:new long[][]{{UNIT,UNIT,UNIT,-300},{-300,UNIT,UNIT,UNIT},
                {-UNIT,-UNIT,-UNIT,300},{300,-UNIT,-UNIT,-UNIT}}) {
            var engine=market(values);
            var data=LedgerData.capture(engine,NOW+100,LedgerData.Entry.current());
            assertEquals(engine.metrics(NOW+100).net,data.market.total);
            assertEquals(Fmt.signed(data.market.total),tradeTotal(data));
            assertEquals(data.net,data.total.net());
        }
    }

    @Test public void tradeSearchAndFocusedCategoryKeepOnlyTheirChosenRows() throws Exception {
        var engine=market(UNIT,UNIT,UNIT,-300);
        for(var entry:List.of(LedgerData.Entry.current().withSearch("Market item 1"),
                LedgerData.Entry.current().live(Semantics.Table.MARKET).withSearch("Market item 1"))) {
            var data=LedgerData.capture(engine,NOW+100,entry);
            assertEquals(1,data.market.groups.size());
            assertEquals(UNIT,data.market.total);
            assertEquals(Fmt.signed(UNIT),tradeTotal(data));
        }
    }

    @Test public void aNarrowedRecentListKeepsItsOwnSubtotal() throws Exception {
        var engine=independent(UNIT,UNIT,UNIT,-300);
        var snapshot=LiveSnapshot.capture(engine,NOW+100,LiveContext.NONE);
        snapshot.recent=snapshot.recent.stream().filter(row->row.name.equals("Resource 1"))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(1,snapshot.recent.size());
        assertEquals(Fmt.signed(UNIT),recentTotal(snapshot));
        assertEquals(Long.MAX_VALUE-300,snapshot.net);
    }

    @Test public void marketCashWithoutAKnownResultAddsNothingToRecent() throws Exception {
        var engine=market(0);
        var row=engine.marketRows().get(0);
        row.coverage=MarketRows.Coverage.FULLY_UNKNOWN;
        row.realizedResultGp=0;row.realizedResultCorrectionAware=false;row.observedSettlementGp=500;
        var snapshot=LiveSnapshot.capture(engine,NOW+100,LiveContext.NONE);
        assertEquals(1,snapshot.recent.size());
        assertTrue(snapshot.recent.get(0).amount);
        assertEquals(0,snapshot.recent.get(0).net());
        assertEquals(Fmt.signed(0),recentTotal(snapshot));
    }

    @Test public void oppositeNamedSpellsKeepTheirTablesInEitherNewestOrder() {
        for(boolean newestGain:new boolean[]{false,true}) {
            var gain=peaches(NOW+(newestGain?100:0),List.of(priced(6883,"Peach",1,100),
                    priced(561,"Nature rune",-2,5),priced(526,"Bones",-1,0)));
            var cost=peaches(NOW+(newestGain?0:100),List.of(priced(6883,"Peach",1,10),
                    priced(561,"Nature rune",-2,10),priced(526,"Bones",-1,0)));
            var result=SemanticProbe.capture(List.of(gain,cost),List.of(),"",null);
            assertEquals(2,result.groups.size());
            assertEquals(90,result.groups.stream().filter(group->group.table==Semantics.Table.GAINS)
                    .mapToLong(group->group.value).sum());
            assertEquals(-10,result.groups.stream().filter(group->group.table==Semantics.Table.COSTS_SUPPLIES)
                    .mapToLong(group->group.value).sum());
            assertEquals(80,Semantics.summarize(result.groups).net());
            assertEquals(2,Semantics.summarize(result.groups).receipts);
            assertEquals(100,gain.flows().get(0).unitPrice);
            assertEquals(10,cost.flows().get(0).unitPrice);
        }
    }

    @Test public void scopeRetainsCompactedGrossIncomeAndCostBeforeSubtracting() {
        var engine=independent(50,-20);
        var session=engine.session();
        session.retainedRevenue=Long.MAX_VALUE-10;
        session.retainedCosts=300;
        session.compactedTransactionCount=1;
        long revision=engine.revision();
        var data=LedgerData.capture(engine,NOW+100,LedgerData.Entry.current());
        assertEquals(Long.MAX_VALUE-320,engine.metrics(NOW+100).net);
        assertEquals(engine.metrics(NOW+100).net,data.net);
        assertTrue(data.compacted);
        assertEquals(revision,engine.revision());
        assertEquals(50,session.transactions().get(0).flows().get(0).value);
        assertEquals(-20,session.transactions().get(1).flows().get(0).value);
    }

    @Test public void multiSessionScopeKeepsOpposingGrossBucketsAndTheOriginalReceipts() {
        var engine=groupedSpells();
        engine.finishCustom(NOW+1000);
        engine.startCustom("Second run",NOW+2000);
        engine.session().addTransaction(Tx.of(NOW+2100,TransactionType.GAIN,Context.GENERIC,"",true,
                List.of(priced(526,"Bones",1,300))),2000,false);
        long revision=engine.revision();
        var scope=engine.summary().scope(engine.sessions());
        assertEquals(0,scope.net);
        assertEquals(6,scope.transactions.size());
        assertEquals(revision,engine.revision());
        assertEquals(300,engine.session().transactions().get(0).flows().get(0).value);
    }
}
