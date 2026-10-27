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
        Session session = new Session("Vorkath", T0 - 60_000L, SessionMode.GENERAL);
        assertEquals("no target, no count baseline", 0L,
            session.timeSetMillis());

        assertTrue(session.setTimeTarget(3L * 3_600_000L, T0));
        long first = session.timeSetMillis();
        assertEquals("the baseline is the Active Time at the set moment",
            session.elapsed(T0), first);
        assertTrue("the baseline is non-zero mid-run", first > 0L);

        assertFalse("re-saving the same value keeps the running count",
            session.setTimeTarget(3L * 3_600_000L, T0 + 30_000L));
        assertEquals(first, session.timeSetMillis());

        assertTrue(session.setTimeTarget(1L * 3_600_000L, T0 + 30_000L));
        assertEquals("a changed value restarts the count",
            session.elapsed(T0 + 30_000L),
            session.timeSetMillis());

        assertTrue(session.setTimeTarget(null, T0 + 30_000L));
        assertEquals("clearing forgets the count", 0L,
            session.timeSetMillis());

        // A completed window re-saves as a fresh start (owner live 2026-10-13: Edit > Save).
        Session done = new Session("Vorkath", T0 - 60_000L, SessionMode.GENERAL);
        done.setTimeTarget(120_000L, T0);
        done.activeTimeTargetSetMillis = 0L;
        done.sessionStartAt = null;
        assertTrue("re-saving a completed aim begins again",
            done.setTimeTarget(120_000L, T0 + 180_000L));
        assertEquals("the count restarts at the save moment",
            done.elapsed(T0 + 180_000L), done.timeSetMillis());

        // The timer pauses without the run (owner live 2026-10-14).
        Session timed = new Session("Vorkath", T0 - 60_000L, SessionMode.GENERAL);
        timed.setTimeTarget(120_000L, T0);
        timed.activeTimeTargetSetMillis = 0L;
        timed.sessionStartAt = null;
        assertTrue(timed.pauseTimeTarget(T0 + 30_000L));
        long atPause = timed.elapsed(T0 + 30_000L);
        assertEquals("the window freezes", atPause,
            timed.timeWindowMillis(timed.elapsed(T0 + 90_000L)));
        assertEquals("and stays frozen as the run runs on", atPause,
            timed.timeWindowMillis(timed.elapsed(T0 + 200_000L)));
        assertTrue(timed.resumeTimeTarget(T0 + 200_000L));
        assertEquals("resume keeps the counted window", atPause,
            timed.timeWindowMillis(timed.elapsed(T0 + 200_000L)));
        assertTrue(timed.pauseTimeTarget(T0 + 210_000L));
        timed.rearmTimeTarget(T0 + 220_000L);
        assertFalse("re-arming clears the timer pause", timed.timeTargetPaused());
    }

    /** Owner live 2026-10-14: a Net aim counts what the run earns after it is set. */
    @Test
    public void aNetAimCountsFromTheMomentItIsSet()
    {
        Session session = new Session("Vorkath", T0, SessionMode.GENERAL);
        assertTrue(session.setNetTarget(200_000L, 10_000_000L, 0L));
        assertEquals("a set aim starts at zero", 0L, session.targetProgress(10_000_000L));

        assertFalse("re-saving while working keeps the count",
            session.setNetTarget(200_000L, 10_100_000L, 0L));
        assertEquals(100_000L, session.targetProgress(10_100_000L));

        assertTrue(session.setNetTarget(300_000L, 10_150_000L, 0L));
        assertEquals("a changed aim counts from its own set", 0L,
            session.targetProgress(10_150_000L));

        assertTrue("re-saving a reached aim begins again",
            session.setNetTarget(300_000L, 10_500_000L, 0L));
        assertEquals(0L, session.targetProgress(10_500_000L));

        assertTrue(session.setNetTarget(null, 10_500_000L, 0L));
        assertEquals("clearing forgets the baseline", 0L, session.profitTargetSetNet());
    }

    /** Owner 2026-10-16: an aim set after an hour of free play paces from its own start. */
    @Test
    public void aNetAimPacesFromWhenItWasSet()
    {
        Session session = new Session("General", 0L, SessionMode.GENERAL);
        long hour = 3_600_000L;
        assertTrue(session.setNetTarget(200_000L, 0L, hour));
        long elapsed = hour + 10L * 60_000L;
        LiveSnapshot.Goal goal = LiveSnapshot.goal(session,
            new Metrics("General", false, elapsed, 100_000L, 0L, 100_000L, 85_714L, 0, 0, false), elapsed);
        assertEquals("100k in 10 minutes is 600k/h: 10 more minutes, not the run's 1h 10m",
            "~10m" + GameData.msg("play-left"), goal.eta);
        assertEquals("the aim's own clock", 10L * 60_000L, session.profitTargetActiveMillis(elapsed));
    }
}
