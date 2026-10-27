package com.gpmanager;
import com.google.gson.Gson;
import javax.swing.*;
import org.junit.*;
import static org.junit.Assert.*;
import static com.gpmanager.MilestoneTest.onEdt;
import static com.gpmanager.TargetFreshStartTest.*;
public class ManualRecapTest {
    java.util.function.LongSupplier original;
    @Before public void clock(){original=SafeMath.clock;SafeMath.clock=()->2*MINUTE;}
    @After public void restore(){SafeMath.clock=original;}
    @Test public void openingClosingAndReopeningNeverAddsHistory() throws Exception {
        onEdt(()->{
            var engine=SavedSessionTest.window();var panel=RateRestartTest.panel(engine);
            String before=new Gson().toJson(engine.exportState());
            panel.actions.viewRecap();assertEquals("Session recap",panel.shell.heading);
            assertTrue(ShellProbe.sheetButtonTexts(panel.shell).contains("Save session"));
            assertTrue(ShellProbe.sheetButtonTexts(panel.shell).contains("Close"));
            ShellProbe.pressSheet(panel.shell,"Close");panel.actions.viewRecap();panel.shell.show(Shell.GRINDS);
            assertEquals(before,new Gson().toJson(engine.exportState()));assertTrue(engine.saved.isEmpty());
            assertTrue(engine.history().isEmpty());assertTrue(engine.plans.isEmpty());return null;
        });
    }
    @Test public void saveKeepsWhatWasViewedWithoutChangingClockOrAllTime() throws Exception {
        onEdt(()->{
            var engine=SavedSessionTest.window();var panel=RateRestartTest.panel(engine);
            panel.actions.viewRecap();coins(engine,2*MINUTE+5,900);SafeMath.clock=()->4*MINUTE;
            ShellProbe.pressSheet(panel.shell,"Save session");assertEquals(1,engine.saved.size());
            var copy=engine.saved.get(0).session;assertEquals(500,copy.metrics(0).net);assertEquals(MINUTE,copy.elapsed(4*MINUTE));
            assertEquals(Long.valueOf(MINUTE),engine.session().sessionStartAt);
            assertEquals(2400,GrindsData.allTime(engine,4*MINUTE).net);assertTrue(engine.plans.isEmpty());return null;
        });
    }
    @Test public void repeatedSaveActionIsIdempotent() throws Exception {
        onEdt(()->{
            var engine=SavedSessionTest.window();var source=engine.session();var copy=engine.captureLiveSession(source,source.sessionStartAt,2*MINUTE);
            assertNotNull(EngineProbe.saveLiveSession(engine, source,source.sessionStartAt,copy));
            assertNull(EngineProbe.saveLiveSession(engine, source,source.sessionStartAt,copy));assertEquals(1,engine.saved.size());return null;
        });
    }
    @Test public void newSessionAndAccountChangesRejectAnOldRecap() throws Exception {
        onEdt(()->{
            var engine=SavedSessionTest.window();var source=engine.session();Long start=source.sessionStartAt;
            var copy=engine.captureLiveSession(source,start,2*MINUTE);engine.newLive(3*MINUTE);
            assertNull(EngineProbe.saveLiveSession(engine, source,start,copy));engine.restore(null,4*MINUTE);
            assertNull(EngineProbe.saveLiveSession(engine, source,start,copy));assertTrue(engine.saved.isEmpty());return null;
        });
    }
    @Test public void aRecapSavedAfterANewSessionSaysNothingWasSaved() throws Exception {
        onEdt(()->{
            var engine=SavedSessionTest.window();var panel=RateRestartTest.panel(engine);
            panel.actions.viewRecap();engine.newLive(3*MINUTE);ShellProbe.pressSheet(panel.shell,"Save session");
            assertTrue(engine.saved.isEmpty());
            assertTrue(ShellProbe.noticeText(panel.shell),ShellProbe.noticeText(panel.shell).contains("nothing was saved"));return null;
        });
    }
    @Test public void visualRemainsFrozenWhileBookingsContinue() throws Exception {
        onEdt(()->{
            var engine=SavedSessionTest.window();var panel=RateRestartTest.panel(engine);
            panel.actions.viewRecap();var labels=new java.util.ArrayList<String>();RaidHistoryUiTest.labels(panel.shell.sheet,labels);
            assertTrue(labels.toString(),labels.stream().anyMatch(text->text.contains("Net: +500 gp")));
            coins(engine,2*MINUTE+5,900);SafeMath.clock=()->4*MINUTE;panel.refreshNow();
            var after=new java.util.ArrayList<String>();RaidHistoryUiTest.labels(panel.shell.sheet,after);
            assertEquals(labels,after);assertTrue(engine.saved.isEmpty());return null;
        });
    }
    @Test public void absentSessionDisablesTheMenuAndCreatesNothing() throws Exception {
        onEdt(()->{
            var engine=engine();engine.resetData(0);var panel=RateRestartTest.panel(engine);
            var menu=panel.actions.dataMenu();boolean found=false;
            for(var component:menu.getComponents())if(component instanceof JMenuItem && ((JMenuItem)component).getText().equals("View recap")){
                found=true;assertFalse(component.isEnabled());
            }
            assertTrue(found);panel.actions.viewRecap();assertTrue(engine.saved.isEmpty());return null;
        });
    }
    @Test public void marketIsSeparatedExactlyAsInLive() throws Exception {
        onEdt(()->{
            var engine=engine();engine.session().addTransaction(SavedSessionTest.trade(1),2000,false);
            var panel=RateRestartTest.panel(engine);panel.actions.viewRecap();
            var labels=new java.util.ArrayList<String>();RaidHistoryUiTest.labels(panel.shell.sheet,labels);
            assertTrue(labels.toString(),labels.stream().anyMatch(text->text.contains("Market: +100 gp")));
            assertTrue(labels.stream().anyMatch(text->text.contains("Gains: 0 gp")));
            ShellProbe.pressSheet(panel.shell,"Save session");assertEquals(100,engine.saved.get(0).session.metrics(0).net);
            return null;
        });
    }
    @Test public void restartedClockAndSeenFiguresAreFrozenTogether() throws Exception {
        onEdt(()->{
            var engine=SavedSessionTest.window();engine.restartRate(2*MINUTE, true);SafeMath.clock=()->3*MINUTE;
            var panel=RateRestartTest.panel(engine);panel.actions.viewRecap();
            coins(engine,3*MINUTE+5,900);SafeMath.clock=()->5*MINUTE;ShellProbe.pressSheet(panel.shell,"Save session");
            var saved=engine.saved.get(0).session;assertEquals(500,saved.metrics(0).net);assertEquals(MINUTE,saved.elapsed(0));
            assertEquals(3*MINUTE,engine.session().liveElapsed(5*MINUTE));return null;
        });
    }
    @Test public void oldButtonCannotSaveIntoAnotherAccountOrCloseItsNewForm() throws Exception {
        onEdt(()->{
            var engine=SavedSessionTest.window();var panel=RateRestartTest.panel(engine);panel.actions.viewRecap();
            var button=ShellProbe.sheetButtons(panel.shell).stream().filter(item->item.getText().equals("Save session")).findFirst().orElseThrow();
            panel.suspend();panel.shell.sheet("New account form",null,null);button.doClick();
            assertTrue(engine.saved.isEmpty());assertEquals("New account form",panel.shell.heading);return null;
        });
    }
    @Test public void doubleClickingSaveCreatesOneEntryAndNoNewGrindDefinition() throws Exception {
        onEdt(()->{
            var engine=SavedSessionTest.window();var panel=RateRestartTest.panel(engine);panel.actions.viewRecap();
            var button=ShellProbe.sheetButtons(panel.shell).stream().filter(item->item.getText().equals("Save session")).findFirst().orElseThrow();
            button.doClick();button.doClick();assertEquals(1,engine.saved.size());assertTrue(engine.plans.isEmpty());return null;
        });
    }
}
