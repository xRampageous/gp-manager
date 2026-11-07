package com.gpmanager;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class LedgerDetailSaturationTest {
    private static final long NOW=1_000_000, UNIT=4_000_000_000_000_000_000L;

    @Test public void groupedItemDetailCannotWrapThreeKnownGainsNegative() throws Exception {
        SwingTestSupport.onEdt(() -> {
            var engine=ReviewFixesTest.engine();engine.startCustom("Large known gains",NOW);
            for(int i=0;i<3;i++)engine.session().addTransaction(Tx.of(NOW+1000+i,TransactionType.GAIN,
                    Context.GENERIC,"",true,List.of(new Flow(526,"Bones",1,UNIT,UNIT,PriceSource.MANUAL_OVERRIDE))),2000,false);
            assertEquals(Long.MAX_VALUE,engine.metrics(NOW+5000).net);
            var entry=LedgerData.Entry.current();
            var data=LedgerData.capture(engine,NOW+5000,entry);
            assertEquals(1,data.income.groups.size());
            var group=data.income.groups.get(0);
            data=LedgerData.capture(engine,NOW+5000,entry.withSelection(null,null,group.id));
            var page=new LedgerPage(ReviewFixesTest.proxy(LedgerPage.Actions.class,Map.of()),id->null);
            page.apply(data);
            var item=page.items(group).get(0);
            System.out.println("group="+group.value+" itemDetail="+item.value+" exact="+item.exact);
            assertEquals("item detail must retain the sign and saturated total",Fmt.worth(Long.MAX_VALUE),item.value);
            return null;
        });
    }

    @Test public void anEntryCardCannotWrapThreeKnownCostsPositive() {
        var row=Tx.of(NOW,TransactionType.CONSUMPTION,Context.GENERIC,"",true,List.of(
                new Flow(560,"Death rune",-1,UNIT,-UNIT,PriceSource.MANUAL_OVERRIDE),
                new Flow(565,"Blood rune",-1,UNIT,-UNIT,PriceSource.MANUAL_OVERRIDE),
                new Flow(555,"Water rune",-1,UNIT,-UNIT,PriceSource.MANUAL_OVERRIDE)));
        row.setAction(ActionKind.CAST);
        row.setActionLabel(ActionLabel.of("Ice Barrage"));
        assertEquals(-Long.MAX_VALUE,row.net());
        var result=SemanticProbe.capture(List.of(row),List.of(),"",null);
        assertEquals(1,result.groups.size());
        var card=LedgerData.cards(result.groups.get(0)).get(0);
        System.out.println("row="+row.net()+" entryCard="+card.value+" known="+card.knownSubtotal);
        assertEquals("the exact entry must not promise a positive gain",-Long.MAX_VALUE,card.value);
        assertEquals(-Long.MAX_VALUE,card.knownSubtotal);
    }

    private static Semantics.Receipt receipt(long value, boolean unknown, long quantity) {
        var transaction=Tx.of(NOW,TransactionType.GAIN,Context.GENERIC,"",true,List.of(
                new Flow(526,"Bones",value<0?-quantity:quantity,unknown?0:Math.abs(value)/quantity,value,
                        unknown?PriceSource.UNPRICED:PriceSource.MANUAL_OVERRIDE)));
        return SemanticProbe.capture(List.of(transaction),List.of(),"",null).groups.get(0).entries.get(0);
    }

    private static LedgerData.Card card(Semantics.Receipt... receipts) {
        var total=new LedgerData.ReceiptCard(receipts[0]);
        for(var receipt:receipts)total.add(receipt);
        return total.card();
    }

    @Test public void mixedEntryTotalsClampIncomeAndCostBeforeSubtractingInEveryOrder() {
        var gain=receipt(UNIT,false,1);var cost=receipt(-300,false,1);
        for(var receipts:new Semantics.Receipt[][]{
                {gain,gain,gain,cost},{gain,cost,gain,gain},{cost,gain,gain,gain}}) {
            var card=card(receipts);
            assertEquals(Long.MAX_VALUE-300,card.value);
            assertEquals(card.value,card.knownSubtotal);
            assertEquals(card.value,card.resources.get(0).value);
        }
    }

    @Test public void mixedCostsAndKnownSubtotalsDoNotWrapOrLoseTheirUnpricedChild() {
        var gain=receipt(300,false,1);var cost=receipt(-UNIT,false,1);var unknown=receipt(0,true,2);
        for(var receipts:new Semantics.Receipt[][]{
                {cost,cost,cost,gain,unknown},{cost,gain,cost,unknown,cost},{gain,unknown,cost,cost,cost}}) {
            var card=card(receipts);
            assertEquals(300-Long.MAX_VALUE,card.value);
            assertEquals(card.value,card.knownSubtotal);
            assertTrue(card.incomplete);
            assertTrue(card.resources.get(0).incomplete);
            assertEquals(card.value,card.resources.get(0).knownSubtotal);
            assertEquals(6,card.resources.get(0).quantity);
        }
        assertEquals(-UNIT,cost.value);
        assertTrue(unknown.unknown);
    }

    @Test public void quantityAndBothFinancialDirectionsSaturateIndependently() {
        var gain=receipt(Long.MAX_VALUE,false,Long.MAX_VALUE);
        var cost=receipt(-Long.MAX_VALUE,false,1);
        var total=card(gain,cost,gain,cost);
        assertEquals(0,total.value);
        assertEquals(0,total.knownSubtotal);
        assertEquals(Long.MAX_VALUE,total.resources.get(0).quantity);
        assertEquals(0,total.resources.get(0).value);
    }

    @Test public void ordinaryEntryOrderMetadataAndPricesAreUnchanged() {
        var first=receipt(100,false,2);first.at=NOW;first.name="zebra";
        var second=receipt(-30,false,1);second.item=555;second.name="Alpha";second.at=NOW+1000;
        var total=card(first,second);
        assertEquals(70,total.value);
        assertEquals(70,total.knownSubtotal);
        assertEquals(NOW+1000,total.at);
        assertEquals(first.id,total.id);
        assertEquals(List.of("Alpha","zebra"),total.resources.stream().map(row->row.name)
                .collect(java.util.stream.Collectors.toList()));
        assertEquals(100,first.value);
        assertEquals(-30,second.value);
    }

    @Test public void groupedResourcesKeepNewestMetadataAndDoNotMutateTheirReceipts() {
        var engine=ReviewFixesTest.engine();engine.startCustom("Grouped casts",NOW);
        for(int i=0;i<2;i++) {
            var row=Tx.of(NOW+i*1000,TransactionType.CONSUMPTION,Context.GENERIC,"",true,List.of(
                    new Flow(555,"Water rune",-1,10,-10,PriceSource.MANUAL_OVERRIDE),
                    new Flow(565,"Blood rune",-1,30,-30,PriceSource.MANUAL_OVERRIDE)));
            row.setAction(ActionKind.CAST);row.setActionLabel(ActionLabel.of("Ice Barrage"));
            engine.session().addTransaction(row,2000,false);
        }
        var data=LedgerData.capture(engine,NOW+5000,LedgerData.Entry.current());
        var group=data.cost.groups.get(0);
        for(var receipt:group.entries)receipt.uses=receipt.at==NOW?"older":"newer";
        long revision=engine.revision();
        var first=LedgerData.itemTotals(group);var second=LedgerData.itemTotals(group);
        assertEquals(List.of("Blood rune","Water rune"),first.stream().map(item->item.name)
                .collect(java.util.stream.Collectors.toList()));
        assertEquals(List.of("newer","newer"),first.stream().map(item->item.uses)
                .collect(java.util.stream.Collectors.toList()));
        assertEquals(-60,first.get(0).value);assertEquals(-20,first.get(1).value);
        assertEquals(2,first.get(0).quantity);
        assertEquals(first.get(0).value,second.get(0).value);
        assertEquals(4,group.entries.size());
        assertTrue(group.entries.stream().allMatch(receipt->receipt.value==-10||receipt.value==-30));
        assertEquals(-80,engine.metrics(NOW+5000).net);
        assertEquals(revision,engine.revision());
    }
}
