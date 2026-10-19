package com.gpmanager;

import static com.gpmanager.TargetFreshStartTest.HOUR;
import static com.gpmanager.TargetFreshStartTest.coins;
import static com.gpmanager.TargetFreshStartTest.live;
import static com.gpmanager.TargetFreshStartTest.recentTotal;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Owner 1.5.2: one reset flow. The ACTIVE clock starts a new Live session (Net, Recent, GP/h and
 * the clock at zero); a target starts one too; the run keeps every row and its whole total.
 */
public class LiveSessionTest
{
    @Test
    public void aNewSessionStartsLiveAtZeroAndTheRunKeepsEverything()
    {
        Engine engine = TargetFreshStartTest.engine();
        coins(engine, 5L, 1_000L);
        engine.newLiveSession(HOUR);
        LiveSnapshot fresh = live(engine, HOUR);
        assertEquals(0L, fresh.net);
        assertEquals(0L, fresh.gains);
        assertEquals(0L, fresh.elapsedMillis);
        assertEquals(0L, fresh.gpPerHour);
        assertTrue("Recent starts empty", fresh.recent.isEmpty());
        assertTrue("the run total line shows", fresh.fresh);
        assertEquals("the run keeps its whole total", 1_000L, fresh.runNet);

        coins(engine, HOUR + 5L, 500L);
        LiveSnapshot later = live(engine, 2 * HOUR);
        assertEquals(500L, later.net);
        assertEquals(500L, recentTotal(later));
        assertEquals(HOUR, later.elapsedMillis);
        assertEquals(500L, later.gpPerHour);
        assertEquals(2, engine.getActiveSession().getTransactions().size());
        assertEquals(1_500L, engine.getMetrics(2 * HOUR).net);
    }

    @Test
    public void aNewSessionDuringATargetStartsTheTargetAgain()
    {
        Engine engine = TargetFreshStartTest.engine();
        engine.setTargets(5_000_000L, null, 0L);
        coins(engine, 5L, 1_000L);
        engine.newLiveSession(HOUR);
        assertEquals(0L, live(engine, HOUR).goal.current);
        coins(engine, HOUR + 5L, 300L);
        assertEquals(300L, live(engine, 2 * HOUR).goal.current);
    }

    /** The run total under the Net brings the whole run back to Live; a running target stays. */
    @Test
    public void theRunTotalShowsTheWholeRunAgain()
    {
        Engine engine = TargetFreshStartTest.engine();
        coins(engine, 5L, 1_000L);
        engine.newLiveSession(HOUR);
        coins(engine, HOUR + 5L, 500L);
        engine.showWholeRun();
        LiveSnapshot whole = live(engine, 2 * HOUR);
        assertFalse(whole.fresh);
        assertEquals(1_500L, whole.net);
        assertEquals(1_500L, recentTotal(whole));
        assertEquals(2 * HOUR, whole.elapsedMillis);

        engine.setTargets(5_000_000L, null, 2 * HOUR);
        engine.showWholeRun();
        assertTrue("a running target keeps its session", live(engine, 2 * HOUR).fresh);
        engine.setTargets(null, null, 2 * HOUR);
        engine.showWholeRun();
        assertEquals("after Done it can", 1_500L, live(engine, 2 * HOUR).net);
    }

    @Test
    public void theWholeRunHasNoTotalLine()
    {
        Engine engine = TargetFreshStartTest.engine();
        coins(engine, 5L, 1_000L);
        assertFalse(live(engine, HOUR).fresh);
    }

    @Test
    public void theClockKeepsNewSessionAlongsideTheRateRestart() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.ensureSession(System.currentTimeMillis() - HOUR);
        SidebarPanel panel = MilestoneTest.onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        Shell shell = panel.shell();
        GrindsController grinds = new GrindsController(panel);
        assertEquals("Session controls", MilestoneTest.onEdt(() ->
        {
            grinds.newSession();
            String title = ShellProbe.sheetTitle(shell);
            ShellProbe.pressSheet(shell, "New session");
            return title;
        }));
        assertTrue(engine.getActiveSession().sessionStartAt != null);
    }
}
