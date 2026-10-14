package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Owner live 2026-10-13: a time target counts from when it was set. */
public class SessionTargetCountTest
{
    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void settingAChangedTargetRestartsItsCountAndTheSameValueKeepsIt()
    {
        Session session = new Session("Vorkath", T0 - 60_000L);
        assertEquals("no target, no count baseline", 0L,
            session.getActiveTimeTargetSetMillis());

        assertTrue(session.setActiveTimeTargetMillis(3L * 3_600_000L, T0));
        long first = session.getActiveTimeTargetSetMillis();
        assertEquals("the baseline is the Active Time at the set moment",
            session.getElapsedMillis(T0), first);
        assertTrue("the baseline is non-zero mid-run", first > 0L);

        assertFalse("re-saving the same value keeps the running count",
            session.setActiveTimeTargetMillis(3L * 3_600_000L, T0 + 30_000L));
        assertEquals(first, session.getActiveTimeTargetSetMillis());

        assertTrue(session.setActiveTimeTargetMillis(1L * 3_600_000L, T0 + 30_000L));
        assertEquals("a changed value restarts the count",
            session.getElapsedMillis(T0 + 30_000L),
            session.getActiveTimeTargetSetMillis());

        assertTrue(session.setActiveTimeTargetMillis(null, T0 + 30_000L));
        assertEquals("clearing forgets the count", 0L,
            session.getActiveTimeTargetSetMillis());

        // A completed window re-saves as a fresh start (owner live 2026-10-13: Edit > Save).
        Session done = new Session("Vorkath", T0 - 60_000L);
        done.setActiveTimeTargetMillis(120_000L, T0);
        done.activeTimeTargetSetMillis = 0L;
        assertTrue("re-saving a completed aim begins again",
            done.setActiveTimeTargetMillis(120_000L, T0 + 180_000L));
        assertEquals("the count restarts at the save moment",
            done.getElapsedMillis(T0 + 180_000L), done.getActiveTimeTargetSetMillis());

        // The timer pauses without the run (owner live 2026-10-14).
        Session timed = new Session("Vorkath", T0 - 60_000L);
        timed.setActiveTimeTargetMillis(120_000L, T0);
        timed.activeTimeTargetSetMillis = 0L;
        assertTrue(timed.pauseTimeTarget(T0 + 30_000L));
        long atPause = timed.getElapsedMillis(T0 + 30_000L);
        assertEquals("the window freezes", atPause,
            timed.getActiveTimeTargetWindowMillis(timed.getElapsedMillis(T0 + 90_000L)));
        assertEquals("and stays frozen as the run runs on", atPause,
            timed.getActiveTimeTargetWindowMillis(timed.getElapsedMillis(T0 + 200_000L)));
        assertTrue(timed.resumeTimeTarget(T0 + 200_000L));
        assertEquals("resume keeps the counted window", atPause,
            timed.getActiveTimeTargetWindowMillis(timed.getElapsedMillis(T0 + 200_000L)));
        assertTrue(timed.pauseTimeTarget(T0 + 210_000L));
        timed.rearmTimeTarget(T0 + 220_000L);
        assertFalse("re-arming clears the timer pause", timed.isTimeTargetPaused());
    }

    /** Owner live 2026-10-14: a Net aim counts what the run earns after it is set. */
    @Test
    public void aNetAimCountsFromTheMomentItIsSet()
    {
        Session session = new Session("Vorkath", T0);
        assertTrue(session.setProfitTargetGp(200_000L, 10_000_000L));
        assertEquals("a set aim starts at zero", 0L, session.profitTargetProgress(10_000_000L));

        assertFalse("re-saving while working keeps the count",
            session.setProfitTargetGp(200_000L, 10_100_000L));
        assertEquals(100_000L, session.profitTargetProgress(10_100_000L));

        assertTrue(session.setProfitTargetGp(300_000L, 10_150_000L));
        assertEquals("a changed aim counts from its own set", 0L,
            session.profitTargetProgress(10_150_000L));

        assertTrue("re-saving a reached aim begins again",
            session.setProfitTargetGp(300_000L, 10_500_000L));
        assertEquals(0L, session.profitTargetProgress(10_500_000L));

        assertTrue(session.setProfitTargetGp(null, 10_500_000L));
        assertEquals("clearing forgets the baseline", 0L, session.getProfitTargetSetNet());
    }
}
