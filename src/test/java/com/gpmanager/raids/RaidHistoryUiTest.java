package com.gpmanager;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RaidHistoryUiTest {
    void edt(Runnable task) throws Exception {SwingUtilities.invokeAndWait(task);}
    SidebarPanel panel(Engine engine) {var panel=new SidebarPanel(engine,ReviewFixesTest.CONFIG,null,null,null);panel.active=true;return panel;}
    @Test public void noHistoryHidesFourthTab() throws Exception {edt(()->{var panel=panel(ReviewFixesTest.engine());panel.refreshNow();assertEquals(3,panel.shell.tabs.getComponentCount());});}
    @Test public void anObservedAttemptRevealsTheTabAndKeepsNavigationManual() throws Exception {edt(()->{var engine=ReviewFixesTest.engine();var panel=panel(engine);engine.raids.active=engine.raids.start(2,"Normal",ReviewFixesTest.T0,false);panel.refreshNow();panel.refreshNow();assertEquals(Shell.LIVE,panel.shell.currentPage());assertEquals(4,panel.shell.tabs.getComponentCount());});}
    @Test public void activeFormPreventsAutoNavigation() throws Exception {edt(()->{var engine=ReviewFixesTest.engine();var panel=panel(engine);panel.shell.sheetHolder.setVisible(true);engine.raids.active=engine.raids.start(2,"Normal",ReviewFixesTest.T0,false);panel.refreshNow();assertEquals(Shell.LIVE,panel.shell.currentPage());});}
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
        Object[][] loot={{cox,21034,"Dexterous prayer scroll",38_400_000},{tob,22486,"Scythe of vitur (uncharged)",1_210_000_000},{toa,25975,"Lightbearer",6_250_000}};
        for(var l:loot){var run=(RaidHistory.Run)l[0];int price=(int)l[3];var flow=new Flow((int)l[1],(String)l[2],1,price,price);flow.raid=run.id;
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
            var image=new java.awt.image.BufferedImage(242,760,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics=image.createGraphics();panel.printAll(graphics);graphics.dispose();
            javax.imageio.ImageIO.write(image,"png",new java.io.File(out,"raids-readme.png"));
        } finally {frame.dispose();}
    }
}
