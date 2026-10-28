package com.gpmanager;

import com.google.gson.Gson;
import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RaidHistoryUiTest {
    @Test public void totalsHaveAProminentNetAndCompactLootFeeBreakdown() throws Exception {
        edt(() -> {
            for (long[] money : new long[][]{{1_000_000, 0}, {1_000_000, 500_000}, {0, 500_000}, {2_149_631_130_647L, 500_000}}) {
                var card = RaidsPage.card("All Time Raids", money);
                var labels = new ArrayList<JLabel>();
                moneyLabels(card, labels);
                var net = labels.stream().filter(label -> label.getText().equals(Fmt.signed(money[0]-money[1])))
                        .max(java.util.Comparator.comparingDouble(label -> label.getFont().getSize2D())).orElseThrow();
                var fees = labels.stream().filter(label -> label.getText().equals(Fmt.signed(-money[1]))
                        && label.getFont().getSize2D() < net.getFont().getSize2D()).findFirst().orElseThrow();
                assertNull("Loot is in the table, not the summary", button(card, "Loot "));
                assertTrue(labels.stream().anyMatch(label -> label.getText().equals("All Time Raids")));
                assertTrue("only the literal Net wording is gone",
                        labels.stream().noneMatch(label -> label.getText().startsWith("Net ")));
                assertTrue(labels.stream().anyMatch(label -> label.getText().equals("Death fees:")));
                assertTrue("the fee amount keeps the loss tone", fees.getForeground().equals(Kit.Tone.LOSS.color));
                assertTrue(net.getFont().getSize2D() > fees.getFont().getSize2D());
                assertTrue("death fees fit the narrow card", fees.getParent().getPreferredSize().width <= 197);
                assertTrue(net.getToolTipText().contains(Fmt.exactSigned(money[0]-money[1])));
            }
        });
    }
    static void moneyLabels(Component component, java.util.List<JLabel> labels) {
        if (component instanceof JLabel) labels.add((JLabel) component);
        if (component instanceof Container) for (var child : ((Container) component).getComponents()) moneyLabels(child, labels);
    }
    static String graph(Engine engine) {
        var clock = SafeMath.clock;
        try {
            SafeMath.clock = () -> ReviewFixesTest.T0;
            return new Gson().toJson(engine.exportState());
        } finally { SafeMath.clock = clock; }
    }
    static JButton button(Component component, String prefix) {
        if (component instanceof JButton && ((JButton) component).getText().startsWith(prefix)) return (JButton) component;
        if (component instanceof Container) for (var child : ((Container) component).getComponents()) {
            var found = button(child, prefix);
            if (found != null) return found;
        }
        return null;
    }
    @Test public void inlineLootAndAttemptFeesRemainReachable() throws Exception {
        var fixture = new RaidHistoryTest();
        fixture.claimed();
        fixture.engine.raids.death();
        fixture.engine.bookDeathFee(500_000, null, fixture.now());
        edt(() -> {
            var panel = panel(fixture.engine);
            panel.refreshNow();
            panel.shell.show(Shell.RAIDS);
            panel.raids.open(null, null);
            panel.refreshNow();
            assertNull("the overview has one receipt shortcut", button(panel.raids.overview, "View all receipts"));
            assertNull(button(panel.raids.overview, "Loot "));
            String before = graph(fixture.engine);
            assertNotNull(panel.raids.ledger.data);
            assertEquals(5_000, panel.raids.ledger.data.income.total);
            assertEquals(0, panel.raids.ledger.data.cost.total);
            panel.raids.open(null, null);
            panel.refreshNow();
            panel.raids.history.rows.get(0).open.run();
            panel.refreshNow();
            panel.raids.history.rows.get(0).open.run();
            panel.refreshNow();
            assertEquals(-500_000, panel.raids.ledger.data.cost.total);
            assertEquals(before, graph(fixture.engine));
        });
    }
    @Test public void lootDrilldownIsScopedReadOnlyKeyboardAccessibleAndFeeExcluded() throws Exception {
        var fixture = new RaidHistoryTest();
        fixture.claimed();
        String toa = fixture.id();
        fixture.engine.raids.death();
        fixture.engine.bookDeathFee(500_000L, null, fixture.now());
        var cox = fixture.engine.raids.start(0, "Normal", fixture.now() - 120_000L, false);
        var flow = new Flow(21034, "Dexterous prayer scroll", 1, 1_000, 1_000);
        flow.raid = cox.id;
        fixture.engine.add(new Transaction(fixture.now() - 60_000L, TransactionType.LOOT, Context.GENERIC,
                "", "General", true, java.util.List.of(flow), Confidence.CONFIRMED, "", null), false);
        fixture.engine.raids.received(cox.id, fixture.engine.session(), fixture.now() - 60_000L);
        edt(() -> {
            var panel = panel(fixture.engine);
            panel.refreshNow();
            panel.shell.show(Shell.RAIDS);
            panel.raids.open(null, null);
            panel.refreshNow();
            assertNull("no Loot button", button(panel.raids.overview, "Loot "));
            var allLoot = panel.raids.ledger.income;
            assertTrue(allLoot.isVisible());
            assertNotNull("loot rows are keyboard activatable", ((JComponent) allLoot.body.getComponent(0))
                    .getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("SPACE")));
            var labels = new ArrayList<String>();
            labels(panel.raids.overview, labels);
            assertTrue("Death fees remain a separate label", labels.stream().anyMatch(text -> text.contains("Death fees")));
            String before = graph(fixture.engine);
            allLoot.rows.get(0).open.run();
            panel.refreshNow();
            assertEquals(Semantics.Table.GAINS, panel.raids.entry.table);
            assertEquals("raid:all", panel.raids.entry.session);
            assertNotNull(panel.raids.ledger.data.detail);
            assertTrue(LedgerData.readOnly(panel.raids.ledger.data.entry));
            assertEquals(6_000, panel.raids.ledger.data.income.total);
            assertEquals(0, panel.raids.ledger.data.cost.total);
            assertTrue(panel.raids.ledger.data.income.groups.stream().noneMatch(group ->
                    group.name.contains("fee") || group.name.contains("Fee")));
            assertEquals(before, graph(fixture.engine));

            panel.raids.open(2, toa);
            panel.refreshNow();
            assertNull("no attempt Loot button", button(panel.raids.detail, "Loot "));
            assertTrue(SwingUtilities.isDescendingFrom(panel.raids.ledger.income, panel.raids.detail));
            assertEquals("raid:" + toa, panel.raids.entry.session);
            assertEquals(5_000, panel.raids.ledger.data.income.total);
            assertEquals(-500_000, panel.raids.ledger.data.cost.total);

            panel.raids.open(2, toa);
            panel.refreshNow();
            var stale = panel.raids.ledger.income.rows.get(0).open;
            panel.suspend();
            stale.run();
            assertNull("a stale Loot action cannot select a raid", panel.raids.selected);
            assertNotEquals("raid:" + toa, panel.raids.entry.session);
            assertEquals(before, graph(fixture.engine));
        });
    }
    @Test public void feeOnlyRaidsLootShowsNoFeeReceipt() throws Exception {
        var fixture = new RaidHistoryTest();
        fixture.start();
        fixture.engine.raids.death();
        fixture.engine.bookDeathFee(500_000L, null, fixture.now());
        edt(() -> {
            var panel = panel(fixture.engine);
            panel.refreshNow();
            panel.shell.show(Shell.RAIDS);
            panel.raids.open(null, null);
            panel.refreshNow();
            String before = graph(fixture.engine);
            assertNull(button(panel.raids.overview, "Loot "));
            assertNotNull(panel.raids.ledger.data);
            assertEquals(0, panel.raids.ledger.data.income.total);
            assertTrue(panel.raids.ledger.data.income.groups.isEmpty());
            assertEquals(0, panel.raids.ledger.data.cost.total);
            assertEquals(before, graph(fixture.engine));
        });
    }
    void edt(Runnable task) throws Exception {SwingUtilities.invokeAndWait(task);}
    SidebarPanel panel(Engine engine) {var panel=new SidebarPanel(engine,ReviewFixesTest.CONFIG,null,null,null);panel.active=true;return panel;}
    @Test public void noHistoryHidesFourthTab() throws Exception {edt(()->{var panel=panel(ReviewFixesTest.engine());panel.refreshNow();assertEquals(3,panel.shell.tabs.getComponentCount());});}
    @Test public void anObservedAttemptRevealsTheTabAndKeepsNavigationManual() throws Exception {edt(()->{var engine=ReviewFixesTest.engine();var panel=panel(engine);engine.raids.active=engine.raids.start(2,"Normal",ReviewFixesTest.T0,false);panel.refreshNow();panel.refreshNow();assertEquals(Shell.LIVE,panel.shell.current);assertEquals(4,panel.shell.tabs.getComponentCount());});}
    @Test public void activeFormPreventsAutoNavigation() throws Exception {edt(()->{var engine=ReviewFixesTest.engine();var panel=panel(engine);panel.shell.sheetHolder.setVisible(true);engine.raids.active=engine.raids.start(2,"Normal",ReviewFixesTest.T0,false);panel.refreshNow();assertEquals(Shell.LIVE,panel.shell.current);});}
    @Test public void pickerShowsOnlyObservedKinds() throws Exception {edt(()->{var engine=ReviewFixesTest.engine();var panel=panel(engine);engine.raids.active=engine.raids.start(2,"Normal",ReviewFixesTest.T0,false);engine.raids.received(engine.raids.activeId(),engine.session(),ReviewFixesTest.T0+600);panel.refreshNow();panel.shell.show(Shell.RAIDS);panel.refreshNow();var labels=new ArrayList<String>();labels(panel.raids.body,labels);assertTrue(labels.contains("Tombs of Amascut"));assertFalse(labels.contains("Chambers of Xeric"));assertFalse(labels.contains("Theatre of Blood"));});}
    @Test public void itemsRemainInsideRaidsWithoutLedgerLink() throws Exception {var fixture=new RaidHistoryTest();fixture.claimed();edt(()->{var panel=panel(fixture.engine);panel.refreshNow();panel.shell.show(Shell.RAIDS);panel.raids.open(2,fixture.id());panel.refreshNow();var labels=new ArrayList<String>();labels(panel.raids.body,labels);assertFalse(labels.contains("Open in Ledger"));assertTrue(labels.contains("Copy summary"));assertNotNull(panel.raids.ledger.data);assertTrue(LedgerData.readOnly(panel.raids.ledger.data.entry));});}
    @Test public void accountSuspendClearsRaidSelection() throws Exception {edt(()->{var engine=ReviewFixesTest.engine();var panel=panel(engine);var run=engine.raids.start(2,"Normal",ReviewFixesTest.T0,false);panel.raids.open(2,run.id);panel.suspend();assertNull(panel.raids.selected);assertEquals(3,panel.shell.tabs.getComponentCount());});}
    @Test public void otherRaidProgressKeepsTheSelectedReceiptsAndNotesInsideThePanel() throws Exception {
        var fixture=new RaidHistoryTest();fixture.claimed();String selected=fixture.id();
        fixture.engine.raids.start(0,"Challenge",fixture.now(),true);
        fixture.engine.raids.start(1,"Observed mode",fixture.now(),true);
        edt(()->{
            var panel=panel(fixture.engine);panel.refreshNow();panel.refreshNow();
            panel.shell.show(Shell.RAIDS);
            panel.raids.open(2,selected);panel.refreshNow();
            assertNotNull(panel.raids.ledger.data);assertEquals("raid:"+selected,panel.raids.entry.session);
            for(var holder:new JPanel[]{panel.raids.overview,panel.raids.detail})
                for(var component:holder.getComponents()) if(component instanceof JLabel)
                    assertTrue("wrapped raid note width "+component.getPreferredSize().width+" fits at 213px",component.getPreferredSize().width<=189);
            for(var component:panel.raids.ledger.body.getComponents()) if(component instanceof JLabel)
                assertTrue("embedded receipt notes fit at 213px",component.getPreferredSize().width<=177);
        });
    }
    @Test public void failedOutcomeUsesFailedAndActualDeathFeeOnly() throws Exception {
        var fixture=new TfaHistoryTest();fixture.syntheticParticipant();
        fixture.f.nativeRaids.chat("You failed to survive the Fractured Archive.",fixture.f.now());
        String id=fixture.f.engine.raids.state.runs.get(0).id;
        edt(()->{
            var panel=panel(fixture.f.engine);panel.refreshNow();panel.shell.show(Shell.RAIDS);
            panel.raids.open(3,id);panel.refreshNow();
            var labels=new ArrayList<String>();labels(panel.raids.detail,labels);
            assertTrue(labels.stream().anyMatch(text->text.contains("Failed")));
            assertFalse(labels.stream().anyMatch(text->text.contains("Incomplete")||text.contains("Death / reclaim")));
        });
        fixture.f.engine.bookDeathFee(125000,null,fixture.f.now());
        edt(()->{
            var panel=panel(fixture.f.engine);panel.refreshNow();panel.shell.show(Shell.RAIDS);
            panel.raids.open(3,id);panel.refreshNow();
            var labels=new ArrayList<String>();labels(panel.raids.detail,labels);
            assertTrue(labels.stream().anyMatch(text->text.contains("Death Fee")));
            assertFalse(labels.stream().anyMatch(text->text.contains("Failed")));
        });
    }
    static void labels(Component component,java.util.List<String> labels) {
        if (component instanceof JButton) labels.add(((JButton)component).getText());
        if (component instanceof JLabel) labels.add(((JLabel)component).getText());
        if (component instanceof Container) for (var child:((Container)component).getComponents()) labels(child,labels);
    }
    /** Synthetic receipts rendered through the real sidebar; no game/client input. */
    static void previews(java.io.File out) throws java.io.IOException {
        for (int width:new int[]{213,225,300}) for (int page=0;page<5;page++) {
            var fixture=new RaidHistoryTest();fixture.claimed();String selected=fixture.id();
            var cox=fixture.engine.raids.start(0,"Challenge",fixture.now()-120000,true);
            cox.ended=fixture.now();
            var tob=fixture.engine.raids.start(1,"Observed mode",fixture.now()-60000,true);
            tob.complete=true;tob.ended=fixture.now();
            var panel=new SidebarPanel(fixture.engine,ReviewFixesTest.CONFIG,null,null,null);
            panel.active=true;
            var frame=new JFrame("GP Manager offline raid preview");
            frame.setUndecorated(true);frame.setFocusableWindowState(false);frame.setLocation(-10000,-10000);
            try {
                frame.add(panel);frame.pack();frame.setSize(width,760);frame.setVisible(true);frame.validate();
                panel.refreshNow();panel.shell.show(Shell.RAIDS);
                panel.raids.open(page==0?null:2,page>=2?selected:null);
                for(int i=0;i<2;i++){panel.refreshNow();frame.validate();}
                if(page>=3){panel.raids.ledger.income.rows.get(0).open.run();panel.refreshNow();frame.validate();}
                if(page==4){panel.raids.ledger.receipts.rows.get(0).open.run();panel.refreshNow();frame.validate();}
                var image=new java.awt.image.BufferedImage(width,760,java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics=image.createGraphics();panel.printAll(graphics);graphics.dispose();
                javax.imageio.ImageIO.write(image,"png",new java.io.File(out,"raids-"+page+"-"+width+".png"));
            } finally {frame.dispose();}
        }
        readme(out);
    }
    /** README Raids overview: plausible ToB, CoX and ToA receipts booked through the real engine; no game input. */
    static void readme(java.io.File out) throws java.io.IOException {
        var engine=ReviewFixesTest.engine();long t=ReviewFixesTest.T0;
        var cox=engine.raids.start(0,"Normal",t,false);var tob=engine.raids.start(1,"Normal",t+3_600_000,false);var toa=engine.raids.start(2,"Expert",t+7_200_000,false);
        Object[][] loot={{cox,21034,"Dexterous prayer scroll",1,38_400_000},{tob,22486,"Scythe of vitur (uncharged)",1,1_210_000_000},{toa,25975,"Lightbearer",1,6_250_000},
            {tob,565,"Blood rune",800,190},{cox,560,"Death rune",700,160},{cox,7936,"Pure essence",8000,2}};
        for(var l:loot){var run=(RaidHistory.Run)l[0];int quantity=(int)l[3],price=(int)l[4];var flow=new Flow((int)l[1],(String)l[2],quantity,price,(long)quantity*price);flow.raid=run.id;
            engine.add(new Transaction(run.started+1_800_000,TransactionType.LOOT,Context.GENERIC,"","General",true,java.util.List.of(flow),Confidence.CONFIRMED,"",null),false);
            engine.raids.received(run.id,engine.session(),run.started+1_800_000);}
        engine.raids.state.death=toa.id;engine.bookDeathFee(500_000,null,t+9_000_000);
        var panel=new SidebarPanel(engine,ReviewFixesTest.CONFIG,null,null,null);panel.active=true;
        var frame=new JFrame("GP Manager offline raid preview");
        frame.setUndecorated(true);frame.setFocusableWindowState(false);frame.setLocation(-10000,-10000);
        try {
            frame.add(panel);frame.pack();frame.setSize(242,760);frame.setVisible(true);frame.validate();
            panel.refreshNow();panel.shell.show(Shell.RAIDS);panel.raids.open(null,null);
            for(int i=0;i<2;i++){panel.refreshNow();frame.validate();}
            var image=new java.awt.image.BufferedImage(242,340,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics=image.createGraphics();panel.printAll(graphics);graphics.dispose();
            javax.imageio.ImageIO.write(image,"png",new java.io.File(out,"raids-readme.png"));
        } finally {frame.dispose();}
    }
}
