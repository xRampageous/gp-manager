package com.gpmanager;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeathBagDetailTest {
    @Test public void aSingleIncompleteBagStillShowsItsContentsAndBackNavigation() throws Exception {
        SwingTestSupport.onEdt(() -> {
            var bag=DeathRowGroupingTest.loss("death:unknown",true,
                    new Flow(999999,"Unknown",-1,0,0,PriceSource.UNPRICED));
            var engine=ReviewFixesTest.engine(); engine.session().addTransaction(bag,2000,false);
            var group=DeathRowGroupingTest.capture(bag).groups.get(0);
            var data=LedgerData.capture(engine,10000,LedgerData.Entry.current(null,null).withSelection(null,null,group.id));
            var page=new LedgerPage(ReviewFixesTest.proxy(LedgerPage.Actions.class,Map.of()),id->null);
            page.apply(data);
            assertTrue(Arrays.asList(page.detail.getComponents()).contains(page.detailItems));
            var row=page.items(group).get(0);
            assertEquals("Looting bag",row.name);
            assertEquals("?",row.value); assertEquals("",row.exact);
            row.open.run();
            assertEquals("Unknown",page.items(group).get(0).name);
            for (java.awt.Component part : page.detail.getComponents()) {
                if (!(part instanceof java.awt.Container)) continue;
                for (var child : ((java.awt.Container)part).getComponents())
                    if (child instanceof javax.swing.JButton && ((javax.swing.JButton)child).getText().equals("‹ Death: PvP"))
                        ((javax.swing.JButton)child).doClick();
            }
            assertEquals("Looting bag",page.items(group).get(0).name);
            return null;
        });
    }
    @Test public void oneNestedBagOpensExactContentsAndSurvivesRefresh() throws Exception {
        SwingTestSupport.onEdt(() -> {
            var bag = DeathRowGroupingTest.bag("death:1");
            var gear = DeathRowGroupingTest.gear("death:1");
            var engine = ReviewFixesTest.engine();
            engine.session().addTransaction(bag, 2000, false);
            engine.session().addTransaction(gear, 2000, false);
            var group = DeathRowGroupingTest.capture(bag, gear).groups.get(0);
            var data = LedgerData.capture(engine, 10000,
                    LedgerData.Entry.current(null, null).withSelection(null, null, group.id));
            assertNotNull(data.detail);
            var page = new LedgerPage(ReviewFixesTest.proxy(LedgerPage.Actions.class, Map.of()), id -> null);
            page.apply(data);
            var outer = page.items(group);
            assertEquals(2, outer.size());
            var bagRow = outer.stream().filter(row -> row.name.equals("Looting bag")).findFirst().orElseThrow();
            assertNotNull(bagRow.open);
            assertFalse(outer.stream().anyMatch(row -> row.name.equals("Rune platelegs")));
            assertEquals(Fmt.exactSigned(-76443) + " gp", bagRow.exact);
            bagRow.open.run();
            assertEquals(Set.of("Coins", "Rune platelegs", "Looting bag"),
                    page.items(group).stream().map(row -> row.name).collect(java.util.stream.Collectors.toSet()));
            page.apply(data);
            assertEquals(3, page.items(group).size());
            page.prepareEntry();
            assertEquals(2, page.items(group).size());
            assertEquals(-77158, engine.metrics(10000).net);
            return null;
        });
    }
}
