package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Owner 1.5.2: a target starts a new Live session. Net, Gains, Recent, the clock and GP/h count
 * from it while the run keeps every booking; Done leaves the session counting.
 */
public class TargetFreshStartTest
{
    static final long MINUTE = 60_000L;
    static final long HOUR = 60L * MINUTE;

    static Engine engine()
    {
        Engine engine = new Engine(deltas -> java.util.Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
        engine.ensureSession(0L);
        return engine;
    }

    static void coins(Engine engine, long at, long gp)
    {
        engine.session().addTransaction(new Transaction(at, TransactionType.GAIN, Context.GENERIC, "",
            "General", true, java.util.List.of(new Flow(995, "Coins", gp, 1, gp, PriceSource.FACE_VALUE)),
            Confidence.LIKELY, "", null), 2_000, false);
    }

    /** What Recent adds up to: same-item rows merge, so the sum, not the count, is the check. */
    static long recentTotal(LiveSnapshot live)
    {
        return live.recent.stream().filter(row -> !row.open).mapToLong(LiveSnapshot.Recent::net).sum();
    }

    static LiveSnapshot live(Engine engine, long now)
    {
        return LiveSnapshot.capture(engine, now, null);
    }

    @Test
    public void aTargetStartsLiveFreshAndDoneBringsTheWholeRunBack()
    {
        Engine engine = engine();
        Session run = engine.session();
        coins(engine, 5L, 1_000L);
        engine.setTargets(5_000_000L, null, HOUR);

        LiveSnapshot started = live(engine, HOUR);
        assertEquals("Net starts at zero", 0L, started.net);
        assertEquals("so do Gains", 0L, started.income);
        assertEquals("and the clock", 0L, started.elapsed);
        assertEquals("the hero's aim pair agrees", 0L, started.goal.current);
        assertTrue("Recent starts fresh for the target", started.recent.isEmpty());

        coins(engine, HOUR + 5L, 500L);
        LiveSnapshot later = live(engine, 2 * HOUR);
        assertEquals("what came after counts", 500L, later.net);
        assertEquals(500L, later.income);
        assertEquals(HOUR, later.elapsed);
        assertEquals(500L, later.rate);
        assertEquals(500L, later.goal.current);
        assertEquals("Net plus the base is the whole run", 1_500L, later.net + later.netBase);
        assertSame("the same run", run, engine.session());
        assertEquals("the run keeps every booking", 2, run.transactions().size());
        assertEquals(1_500L, engine.metrics(2 * HOUR).net);

        Long session = run.sessionStartAt;
        engine.setTargets(null, null, 2 * HOUR);
        LiveSnapshot done = live(engine, 2 * HOUR);
        assertEquals("Done keeps the session counting", session, run.sessionStartAt);
        assertEquals(500L, done.net);
        assertEquals(500L, recentTotal(done));
        assertEquals(HOUR, done.elapsed);
        assertNull(done.goal);
    }

    @Test
    public void reSavingKeepsTheCountAndAChangedTargetStartsAgain()
    {
        Engine engine = engine();
        coins(engine, 5L, 1_000L);
        engine.setTargets(5_000_000L, HOUR, HOUR);
        coins(engine, HOUR + 5L, 500L);

        engine.setTargets(5_000_000L, HOUR, HOUR + 10L * MINUTE);
        assertEquals("re-saving the same target keeps its count", 500L, live(engine, HOUR + 10L * MINUTE).net);

        engine.setTargets(5_000_000L, 2 * HOUR, HOUR + 20L * MINUTE);
        LiveSnapshot changed = live(engine, HOUR + 20L * MINUTE);
        assertEquals("a changed time starts the whole target again", 0L, changed.net);
        assertEquals(0L, changed.goal.current);
        assertEquals(0L, engine.session().timeWindowMillis(HOUR + 20L * MINUTE));
    }

    /** A row timed before the target (a login recheck, a Review fix) stays out of the target run. */
    @Test
    public void rowsBookedBeforeTheTargetStayOut()
    {
        Engine engine = engine();
        coins(engine, 5L, 1_000L);
        engine.setTargets(5_000_000L, null, HOUR);
        coins(engine, 10L, 700L);
        assertEquals(0L, live(engine, HOUR + MINUTE).net);
        assertEquals(0L, engine.session().targetProgress(engine.metrics(HOUR + MINUTE).net));
        assertEquals("the run counts it", 1_700L, engine.metrics(HOUR + MINUTE).net);
    }

    @Test
    public void aGrindKeepsItsWholeRun()
    {
        Engine engine = engine();
        engine.startCustom("Vorkath", 0L);
        coins(engine, 5L, 1_000L);
        engine.setTargets(5_000_000L, null, HOUR);
        coins(engine, HOUR + 5L, 500L);
        assertEquals(500L, live(engine, 2 * HOUR).net);
        assertEquals("the Grind's own numbers (best run, average GP/h) keep the whole run",
            1_500L, engine.session().metrics(2 * HOUR).net);
    }

    @Test
    public void anOlderSaveWithoutAStartReadsTheWholeRun()
    {
        Engine engine = engine();
        coins(engine, 5L, 1_000L);
        engine.setTargets(5_000_000L, null, HOUR);
        engine.session().sessionStartAt = null;
        coins(engine, HOUR + 5L, 500L);
        LiveSnapshot live = live(engine, 2 * HOUR);
        assertEquals(1_500L, live.net);
        assertEquals("the aim keeps its own baseline", 500L, live.goal.current);
    }

    @Test
    public void aTimeTargetCountsDownWithoutAProjection()
    {
        Engine engine = engine();
        coins(engine, 5L, 1_000_000L);
        engine.setTargets(null, HOUR, HOUR);
        coins(engine, HOUR + 5L * MINUTE, 100_000L);
        LiveSnapshot live = live(engine, HOUR + 30L * MINUTE);
        assertNull("the hero's GP/h already says the pace", live.goal.eta);
        assertEquals(30L * MINUTE, live.goal.remaining);
    }

    /** Engine.restartTarget (Again) starts a new session with the same target. */
    @Test
    public void againAndResetStartTheTargetFresh()
    {
        Engine engine = engine();
        engine.setTargets(5_000_000L, 10L * MINUTE, 0L);
        coins(engine, 5L, 1_000L);
        assertTrue(engine.restartTarget(HOUR));
        assertEquals(0L, live(engine, HOUR).net);
        coins(engine, HOUR + 5L, 400L);
        assertTrue(engine.restartTarget(2 * HOUR));
        assertEquals(0L, live(engine, 2 * HOUR).net);
        assertEquals(1_400L, engine.metrics(2 * HOUR).net);
    }
}
