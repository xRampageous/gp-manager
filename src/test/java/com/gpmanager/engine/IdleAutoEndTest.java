package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** "End Grind after AFK" (owner 1.2): a named Grind held by AFK pause ends; Free play never does. */
public class IdleAutoEndTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final long MINUTE = 60_000L;

    private static Am grind()
    {
        Am engine = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig()
        {
        });
        engine.rm(T0);
        engine.ajl("Zulrah", Cx.GENERAL, T0 + 1_000L);
        return engine;
    }

    @Test
    public void anAfkGrindEndsAtItsPauseOnceTheLimitPasses()
    {
        Am engine = grind();
        Ad zulrah = engine.customSession;
        long pausedAt = T0 + 5 * MINUTE;
        engine.adh(pausedAt, pausedAt - 2 * MINUTE);

        assertFalse("not before the limit", engine.idleEnd(pausedAt + 10 * MINUTE - 1L, 10));
        assertSame(zulrah, engine.getActiveSession());

        assertTrue(engine.idleEnd(pausedAt + 10 * MINUTE, 10));
        assertNull("the Grind is closed", engine.customSession);
        assertSame("it is saved to history like an End", zulrah, engine.history.get(0));
        assertEquals("it closes when the pause began", pausedAt, zulrah.endedAtEpochMillis);
        assertEquals(SessionEndReason.IDLE, zulrah.endReason);
        assertSame("Free play takes over", engine.generalSession, engine.getActiveSession());
    }

    @Test
    public void offManualPausesAndFreePlayNeverEnd()
    {
        Am off = grind();
        off.adh(T0 + MINUTE, T0 + MINUTE);
        assertFalse("0 is off", off.idleEnd(T0 + 600 * MINUTE, 0));

        Am manual = grind();
        manual.togglePause(T0 + MINUTE);
        assertEquals(Ed.MANUAL, manual.getActiveSession().getPauseReason());
        assertFalse("only AFK pause counts", manual.idleEnd(T0 + 600 * MINUTE, 10));

        Am free = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig()
        {
        });
        free.rm(T0);
        free.adh(T0 + MINUTE, T0 + MINUTE);
        assertFalse("Free play is never auto-ended", free.idleEnd(T0 + 600 * MINUTE, 10));
        assertTrue(free.getActiveSession().paused);
    }

    @Test
    public void activityBeforeTheLimitKeepsTheGrind()
    {
        Am engine = grind();
        Ad zulrah = engine.customSession;
        engine.adh(T0 + 5 * MINUTE, T0 + 5 * MINUTE);
        engine.resume(T0 + 9 * MINUTE, Ed.IDLE);
        assertFalse(engine.idleEnd(T0 + 60 * MINUTE, 10));
        assertSame(zulrah, engine.getActiveSession());
    }
}
