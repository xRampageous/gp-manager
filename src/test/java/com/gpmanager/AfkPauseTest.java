package com.gpmanager;

import org.junit.Test;

import static com.gpmanager.ReviewFixesTest.T0;
import static com.gpmanager.ReviewFixesTest.engine;
import static com.gpmanager.ReviewFixesTest.plugin;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** AFK pause (owner 1.5.0): off by default, pauses from the moment it fires, any play lifts it. */
public class AfkPauseTest
{
    @Test
    public void afkPausePausesFromNowAndPlayLiftsIt()
    {
        Engine engine = engine();
        engine.pauseIdle(T0 + 120_000L);
        Session session = engine.getActiveSession();
        assertTrue(session.paused);
        assertEquals(PauseReason.IDLE, session.getPauseReason());
        assertEquals("the timeout window still counts as active", T0 + 120_000L, session.pausedAtEpochMillis);
        assertTrue(engine.isIdlePaused());
        assertTrue("movement or play resumes it", engine.resume(T0 + 180_000L, PauseReason.IDLE));
        assertFalse(session.paused);
    }

    @Test
    public void afkPauseNeverReplacesAnotherPause()
    {
        for (PauseReason held : new PauseReason[] {PauseReason.MANUAL, PauseReason.WORLD, PauseReason.LIFECYCLE})
        {
            Engine engine = engine();
            engine.getActiveSession().pause(T0 + 1_000L, held);
            engine.pauseIdle(T0 + 2_000L);
            assertEquals(held, engine.getActiveSession().getPauseReason());
            assertEquals(T0 + 1_000L, engine.getActiveSession().pausedAtEpochMillis);
        }
    }

    @Test
    public void anyActivityRestartsTheIdleCount()
    {
        ReviewFixesTest.Plugin p = plugin(engine(), new int[] {11});
        p.idleTicks = 150;
        p.markActivity();
        assertEquals(0, p.idleTicks);
        p.idleTicks = 150;
        p.markGameplayActivity();
        assertEquals(0, p.idleTicks);
    }

    /** Owner 1.5.0: clicking the status dot lifts AWAY for good, not until the next tick. */
    @Test
    public void theStatusDotResumeRestartsTheAfkClock()
    {
        Engine engine = engine();
        ReviewFixesTest.Plugin p = plugin(engine, new int[] {11});
        p.idleTicks = 99;
        p.idleTick(100);
        assertTrue(engine.isIdlePaused());
        p.idleTick(100);
        assertTrue("still away while nothing happens", engine.isIdlePaused());
        engine.togglePause(T0 + 300_000L);
        assertFalse(engine.getActiveSession().paused);
        p.idleTick(100);
        assertFalse("the next still tick does not pause again", engine.getActiveSession().paused);
        assertEquals(0, p.idleTicks);
        for (int i = 0; i < 100; i++) p.idleTick(100);
        assertTrue("a full AFK window later it pauses again", engine.isIdlePaused());
    }

    @Test
    public void afkPauseIsThreeMinutesByDefault()
    {
        assertEquals(3, new GpManagerConfig() {}.afkPauseMinutes());
    }
}
