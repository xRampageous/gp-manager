package com.gpmanager;

import com.google.gson.Gson;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
import static com.gpmanager.LiveCategoryLayoutTest.*;

/** Death is a read-only subset: its receipts still belong to their financial tables. */
public class DeathViewTest {
    java.util.function.LongSupplier previous;
    @Before public void clock() { previous=SafeMath.clock;SafeMath.clock=()->NOW; }
    @After public void restoreClock() { SafeMath.clock=previous; }
    static Semantics.Table death() { return Semantics.Table.valueOf("DEATH"); }
    static Transaction grave(boolean recovered) {
        return new Transaction(3000, TransactionType.TRANSFER, Context.TRANSFER,
                GameData.msg(recovered ? "death-reclaim-items" : "death-items"), "Death reclaim", false,
                List.of(new Flow(4151,"Abyssal whip",recovered?1:-1,2_000_000,recovered?2_000_000:-2_000_000)),
                Confidence.CONFIRMED,"Owned items",null);
    }
    static Engine populated() {
        var e=engine();
        e.session().addTransaction(DeathRowGroupingTest.gear("death:1"),2000,false);
        e.session().addTransaction(DeathRowGroupingTest.bag("death:1"),2000,false);
        e.session().addTransaction(grave(false),2000,false);
        e.session().addTransaction(grave(true),2000,false);
        e.bookDeathFee(100_000,"Zulrah retrieval",4000);
        e.bookCofferDrain(72,5000);
        gain(e,1,6000);
        return e;
    }
    @Test public void reclaimFeesRemainLossWithoutChangingOtherFees() {
        var e=engine();var fee=e.bookDeathFee(100_000,"Zulrah retrieval",4000);
        e.bookCofferDrain(72,5000);
        assertEquals(CostKind.LOSS,CostKind.of(fee,fee.flows().get(0)));
        var money=e.metrics(NOW);
        assertEquals(100_072,money.other);assertEquals(0,money.supplies);
        assertEquals(-100_072,money.net);
    }
    @Test public void viewContainsOnlyDeathsAndNeutralGravesWithoutChangingMoney() {
        var e=populated();String before=new Gson().toJson(e.exportState());
        var data=LedgerData.capture(e,NOW,category(death()));
        assertEquals(4,data.cost.groups.size());
        assertEquals(-177_158,data.cost.total);
        assertEquals(2,data.cost.groups.stream().filter(g->g.neutral&&g.value==0).count());
        assertEquals(-177_230,LedgerData.capture(e,NOW,category(Semantics.Table.COSTS_LOSS)).cost.total);
        assertEquals(177_230,LiveSnapshot.capture(e,NOW,null).loss);
        assertEquals(before,new Gson().toJson(e.exportState()));
        var group=data.cost.groups.stream().filter(g->g.name.equals("Death: PvP")).findFirst().orElseThrow();
        var selected=LedgerData.capture(e,NOW,category(death()).withSelection(null,null,group.id));
        assertEquals(4,selected.detail.entries.size());
    }
    @Test public void categoryTogglesAndDoesNotReplaceDeathTotalWithSupplyTotal() throws Exception {
        MilestoneTest.onEdt(()->{
            var panel=SavedSessionTest.panel(populated());
            panel.bindPvp(()->new PvpState(true,false,false,0));SidebarPanelProbe.refresh(panel);
            var cell=panel.live.strips(panel.snapshot).stream().flatMap(Collection::stream)
                    .filter(c->c.label.equals("DEATHS")).findFirst().orElseThrow();
            assertNotNull(cell.open);cell.open.run();SidebarPanelProbe.refresh(panel);
            assertEquals(death(),panel.category.table);
            assertEquals(-177_158,focused(panel).data.cost.total);
            assertTrue(focused(panel).cost.title.getText().startsWith("DEATH"));
            var page=focused(panel);
            page.cost.rows.stream().filter(r->r.name.equals("Death: PvP")).findFirst().orElseThrow().open.run();
            SidebarPanelProbe.refresh(panel);assertNotNull(page.data.detail);
            assertEquals(death(),page.data.entry.table);
            page.actions.selectionChanged(null,null,null);SidebarPanelProbe.refresh(panel);
            assertNull(page.data.detail);assertEquals(-177_158,page.data.cost.total);
            cell.open.run();SidebarPanelProbe.refresh(panel);
            assertNull(panel.category);assertTrue(panel.live.recent.isVisible());return null;
        });
    }
    @Test public void correctionsUnknownPricesSearchAndReloadKeepTheirExactReceipts() {
        var e=engine();var row=DeathRowGroupingTest.loss("death:unknown",false,
                new Flow(999999,"Unknown gear",-1,0,0,PriceSource.UNPRICED));
        e.session().addTransaction(row,2000,false);
        var fee=e.bookDeathFee(100_000,"Zulrah retrieval",4000);
        e.correctTransaction(fee.id(),Correction.IGNORE,"test");
        var data=LedgerData.capture(e,NOW,category(death()));
        assertEquals(2,data.cost.groups.size());assertEquals(0,data.cost.total);
        assertTrue(data.cost.groups.stream().anyMatch(Semantics.Group::incomplete));
        assertEquals(1,LedgerData.capture(e,NOW,category(death()).withSearch("Unknown gear")).cost.groups.size());
        var gson=new Gson();var next=engine();next.restore(gson.fromJson(gson.toJson(e.savedState()),SavedState.class),NOW);
        assertEquals(2,LedgerData.capture(next,NOW,category(death())).cost.groups.size());
    }
    @Test public void emptyAndGrindWideDeathTableStayBoundedWithoutInventingDeaths() {
        var e=engine();assertTrue(LedgerData.capture(e,NOW,category(death())).cost.groups.isEmpty());
        e.session().addTransaction(grave(false),2000,false);
        var before=LedgerData.capture(e,NOW,category(death()));assertEquals(1,before.cost.groups.size());
        e.newLive(60_000);var after=LedgerData.capture(e,NOW,category(death()));
        assertEquals(1,after.cost.groups.size());assertEquals(before.cost.total,after.cost.total);
        assertEquals(1,DeathRowGroupingTest.capture(grave(false)).groups.size());
    }
    @Test public void anUnrelatedCoinLossInANamedGrindIsNotADeathFee() {
        var e=engine();var row=new Transaction(3000,TransactionType.CONSUMPTION,Context.GENERIC,
                "Dropped","Death reclaim",true,List.of(new Flow(995,"Coins",-100,1,-100,PriceSource.FACE_VALUE)),
                Confidence.LIKELY,"Measured stock reduction",null);
        e.session().addTransaction(row,2000,false);
        assertTrue(LedgerData.capture(e,NOW,category(death())).cost.groups.isEmpty());
        assertEquals(-100,e.metrics(NOW).net);
    }
    @Test public void expiryIsVisibleAtZeroAndReclassificationDoesNotDoubleCount() {
        var e=engine();var expiry=new Transaction(3000,TransactionType.TRANSFER,Context.TRANSFER,
                GameData.msg("reclaim-expired"),"Death reclaim",false,List.of(),Confidence.CONFIRMED,"Still owned",null);
        e.session().addTransaction(expiry,2000,false);
        var fee=e.bookDeathFee(100_000,"Zulrah retrieval",4000);
        assertTrue(e.correctTransaction(fee.id(),Correction.REVENUE,"Owner correction"));
        var data=LedgerData.capture(e,NOW,category(death()));
        assertEquals(2,data.cost.groups.size());assertEquals(100_000,data.cost.total);
        assertEquals(100_000,e.metrics(NOW).income);assertEquals(0,e.metrics(NOW).cost);
    }

    static void previews(java.io.File out) throws java.io.IOException {
        var previous=SafeMath.clock;
        try {
        for(int width:new int[]{213,225,300}) {
            var e=populated();e.session().addTransaction(SavedSessionTest.trade(7000),2000,false);
            var panel=SavedSessionTest.panel(e);
            panel.bindPvp(()->new PvpState(true,false,false,0));SidebarPanelProbe.refresh(panel);
            var frame=new javax.swing.JFrame("Offline DEATH view preview");
            frame.setUndecorated(true);frame.setFocusableWindowState(false);frame.setLocation(-10_000,-10_000);
            try {
                frame.add(panel);frame.pack();frame.setSize(width,760);frame.setVisible(true);
                open(panel,"DEATH");
                for(int i=0;i<2;i++){SidebarPanelProbe.refresh(panel);frame.validate();}
                for(var row:panel.live.strips(panel.snapshot)) {
                    var strip=Hero.strip(row);DirectHeaderControlsTest.size(strip,width-12);
                    for(var box:strip.getComponents())for(var label:((java.awt.Container)box).getComponents())
                        if(label instanceof javax.swing.JLabel) {
                            var text=(javax.swing.JLabel)label;
                            assertTrue(text.getText()+" clipped at "+width,text.getFontMetrics(text.getFont()).stringWidth(text.getText())<=text.getWidth());
                        }
                }
                var image=new java.awt.image.BufferedImage(width,760,java.awt.image.BufferedImage.TYPE_INT_RGB);
                var g=image.createGraphics();panel.printAll(g);g.dispose();
                javax.imageio.ImageIO.write(image,"png",new java.io.File(out,"death-view-"+width+".png"));
            } finally {frame.dispose();}
        }
        } finally {SafeMath.clock=previous;}
    }
}
