package com.gpmanager;

import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

/** Recorded attempts reveal the tab; every navigation choice remains manual. */
public class RaidNavigationTest {
    static void start(Engine e,int kind,int tick) {
        e.raids.observe(new RaidHistory.Sample(kind,true,"Test",new int[]{0}),ReviewFixesTest.T0+tick*600L,tick);
    }
    static void complete(Engine e,int kind,int tick) {
        e.raids.observe(new RaidHistory.Sample(kind,true,"Test",new int[]{1}),ReviewFixesTest.T0+tick*600L,tick);
    }
    static SidebarPanel panel(Engine e) {return new RaidHistoryUiTest().panel(e);}
    @Test public void entryRetryFailureAndPaidDeathRevealWithoutSwitchingTheTab() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var e=ReviewFixesTest.engine();var p=panel(e);p.refreshNow();p.shell.show(Shell.LEDGER);
            for(int kind=0;kind<4;kind++){
                start(e,kind,1+kind*3);String run=e.raids.activeId();e.raids.death();
                var fee=e.bookDeathFee(1000,null,ReviewFixesTest.T0+2000);
                assertEquals(-1000,fee.net());assertEquals(run,fee.flows().get(0).raid);
                p.refreshNow();assertEquals(Shell.LEDGER,p.shell.current);assertEquals(4,p.shell.tabs.getComponentCount());
                e.raids.end(ReviewFixesTest.T0+3000);p.refreshNow();assertEquals(Shell.LEDGER,p.shell.current);
                assertEquals(4,p.shell.tabs.getComponentCount());assertFalse(e.raids.find(run).complete);
                assertTrue(e.raids.find(run).died);
            }
        });
    }
    @Test public void firstAndLaterCompletionsNeverSwitchTheSelectedTab() throws Exception {
        SwingUtilities.invokeAndWait(()->{for(int first=0;first<4;first++){
            var e=ReviewFixesTest.engine();var p=panel(e);p.refreshNow();start(e,first,1);p.refreshNow();
            p.shell.show(Shell.GRINDS);complete(e,first,2);p.refreshNow();p.refreshNow();
            assertEquals(4,p.shell.tabs.getComponentCount());assertEquals(Shell.GRINDS,p.shell.current);
            p.shell.show(Shell.LEDGER);p.refreshNow();assertEquals(Shell.LEDGER,p.shell.current);
            e.raids.end(ReviewFixesTest.T0+2000);start(e,(first+1)%4,3);p.refreshNow();
            assertEquals(Shell.LEDGER,p.shell.current);complete(e,(first+1)%4,4);p.refreshNow();
            assertEquals(Shell.LEDGER,p.shell.current);assertEquals(4,p.shell.tabs.getComponentCount());
        }});
    }
    @Test public void confirmedRewardClaimCanBeTheFirstCompletionWithoutAnyGpValue() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var e=ReviewFixesTest.engine();var p=panel(e);p.refreshNow();start(e,2,1);p.refreshNow();
            e.raids.received(e.raids.activeId(),e.session(),ReviewFixesTest.T0+2000);p.refreshNow();
            assertEquals(4,p.shell.tabs.getComponentCount());assertEquals(Shell.LIVE,p.shell.current);
            assertTrue(e.raids.find(e.raids.activeId()).complete);p.shell.show(Shell.LIVE);
            e.raids.received(e.raids.activeId(),e.session(),ReviewFixesTest.T0+2500);p.refreshNow();
            assertEquals(Shell.LIVE,p.shell.current);assertTrue(e.session().transactions().isEmpty());
        });
    }
    @Test public void restoredAndPrunedCompletionHistoryUnlocksWithoutAnIntroduction() throws Exception {
        SwingUtilities.invokeAndWait(()->{for(boolean pruned:new boolean[]{false,true}){
            var e=ReviewFixesTest.engine();start(e,2,1);complete(e,2,2);var saved=e.raids.snapshot();
            if(pruned){saved.runs.clear();saved.seen=0;saved.clears[2]=1;}
            e.raids.restore(saved);var p=panel(e);p.refreshNow();p.refreshNow();
            assertEquals(4,p.shell.tabs.getComponentCount());assertEquals(Shell.LIVE,p.shell.current);
            start(e,0,3);complete(e,0,4);p.refreshNow();assertEquals(Shell.LIVE,p.shell.current);
        }});
    }
    @Test public void anOpenSheetAndLaterRefreshesPreserveManualNavigation() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var e=ReviewFixesTest.engine();var p=panel(e);p.refreshNow();p.shell.sheetHolder.setVisible(true);
            start(e,2,1);complete(e,2,2);p.refreshNow();assertEquals(Shell.LIVE,p.shell.current);
            assertEquals(4,p.shell.tabs.getComponentCount());p.shell.sheetHolder.setVisible(false);p.refreshNow();
            assertEquals(Shell.LIVE,p.shell.current);
        });
    }
    @Test public void firstCompletionWhileHiddenRevealsWithoutSwitchingWhenShown() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var e=ReviewFixesTest.engine();var p=panel(e);p.refreshNow();p.active=false;
            start(e,2,1);complete(e,2,2);p.refreshNow();assertEquals(Shell.LIVE,p.shell.current);
            p.active=true;p.refreshNow();assertEquals(Shell.LIVE,p.shell.current);
            assertEquals(4,p.shell.tabs.getComponentCount());
            p.shell.show(Shell.GRINDS);p.refreshNow();assertEquals(Shell.GRINDS,p.shell.current);
        });
    }
    @Test public void swappingOwnersCannotReplayAHistoricalIntroduction() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var e=ReviewFixesTest.engine();var p=panel(e);p.refreshNow();start(e,2,1);complete(e,2,2);
            var saved=e.raids.snapshot();p.suspend();e.raids.restore(saved);p.resume();
            assertEquals(4,p.shell.tabs.getComponentCount());assertEquals(Shell.LIVE,p.shell.current);
            p.suspend();e.raids.restore(null);p.resume();assertEquals(3,p.shell.tabs.getComponentCount());
            start(e,1,3);complete(e,1,4);p.refreshNow();assertEquals(Shell.LIVE,p.shell.current);
        });
    }
    @Test public void fullEntryAndMidRaidPartialCaptureBothRevealWithoutSwitching() throws Exception {
        SwingUtilities.invokeAndWait(()->{for(boolean partial:new boolean[]{false,true}){
            var engine=ReviewFixesTest.engine();var sidebar=panel(engine);sidebar.refreshNow();
            sidebar.shell.show(Shell.GRINDS);
            if(!partial)engine.raids.observe(new RaidHistory.Sample(2,false,"Test",new int[]{0}),ReviewFixesTest.T0,0);
            start(engine,2,1);assertEquals(partial,engine.raids.active.partial);
            sidebar.refreshNow();assertEquals(4,sidebar.shell.tabs.getComponentCount());
            assertEquals(Shell.GRINDS,sidebar.shell.current);assertFalse(engine.raids.active.complete);
        }});
    }
    @Test public void restoredFailedOrPartialOnlyHistoryRevealsWithoutSwitching() throws Exception {
        SwingUtilities.invokeAndWait(()->{for(boolean partial:new boolean[]{false,true}){
            var source=ReviewFixesTest.engine();var run=source.raids.start(2,"Test",ReviewFixesTest.T0,partial);
            source.raids.active=run;
            if(!partial){source.raids.death();source.bookDeathFee(500000,null,ReviewFixesTest.T0+600);}
            source.raids.end(ReviewFixesTest.T0+1200);
            var saved=JsonCodec.gson().fromJson(JsonCodec.gson().toJson(source.savedState()),SavedState.class);
            var restored=ReviewFixesTest.engine();restored.restore(saved,ReviewFixesTest.T0+2000);
            var sidebar=panel(restored);sidebar.shell.show(Shell.LEDGER);sidebar.refreshNow();sidebar.refreshNow();
            assertEquals(4,sidebar.shell.tabs.getComponentCount());assertEquals(Shell.LEDGER,sidebar.shell.current);
            assertFalse(restored.raids.find(run.id).complete);
            assertEquals(partial?0:-500000,restored.savedSession("raid:all").session.metrics(ReviewFixesTest.T0+3000).net);
        }});
    }
    @Test public void spectatorAndOtherPlayersClearCannotRevealTheTab() throws Exception {
        var fixture=new RaidHistoryTest();fixture.insideToa();fixture.names.put(1099,"Other");
        fixture.nativeRaids.pulse(fixture.now());fixture.vars.put(3645,1);fixture.tick++;
        fixture.nativeRaids.pulse(fixture.now());assertTrue(fixture.engine.raids.state.runs.isEmpty());
        SwingUtilities.invokeAndWait(()->{
            var sidebar=panel(fixture.engine);sidebar.shell.show(Shell.LEDGER);sidebar.refreshNow();
            assertEquals(3,sidebar.shell.tabs.getComponentCount());assertEquals(Shell.LEDGER,sidebar.shell.current);
        });
    }
    @Test public void unrelatedDeathsFeesAndClearCountersCannotUnlockWithoutRaidEntry() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var engine=ReviewFixesTest.engine();var sidebar=panel(engine);sidebar.refreshNow();
            sidebar.shell.show(Shell.LEDGER);
            for(int kind=0;kind<4;kind++){
                engine.raids.observe(new RaidHistory.Sample(kind,false,"Test",new int[]{0}),ReviewFixesTest.T0,0);
                engine.raids.observe(new RaidHistory.Sample(kind,false,"Test",new int[]{1}),ReviewFixesTest.T0+600,1);
            }
            engine.raids.death();var fee=engine.bookDeathFee(500000,null,ReviewFixesTest.T0+1200);
            sidebar.refreshNow();assertEquals(3,sidebar.shell.tabs.getComponentCount());
            assertEquals(Shell.LEDGER,sidebar.shell.current);assertTrue(engine.raids.state.runs.isEmpty());
            assertEquals(0,engine.raids.state.seen);assertNull(fee.flows().get(0).raid);
            assertEquals(-500000,fee.net());
        });
    }
    @Test public void retainedAttemptFactsStayVisibleAfterMetadataPruningWithoutInventingAClear() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var engine=ReviewFixesTest.engine();start(engine,2,1);var saved=engine.raids.snapshot();
            saved.runs.clear();engine.raids.restore(saved);var sidebar=panel(engine);sidebar.refreshNow();
            assertEquals(4,sidebar.shell.tabs.getComponentCount());assertEquals(Shell.LIVE,sidebar.shell.current);
            assertTrue(engine.raids.state.runs.isEmpty());assertEquals(0,Arrays.stream(engine.raids.state.clears).sum());
            assertEquals(0,engine.savedSession("raid:all").session.metrics(ReviewFixesTest.T0).net);
        });
    }
}
