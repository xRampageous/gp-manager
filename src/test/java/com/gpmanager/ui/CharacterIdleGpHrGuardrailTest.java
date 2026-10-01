package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Character idle is presentation-only — must not pause the session or change GP/hr.
 */
public class CharacterIdleGpHrGuardrailTest
{
    @Test
    public void characterIdleDoesNotPauseSessionOrChangeRate()
    {
        Session session = new Session("Test", 0L);
        session.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                Context.GENERIC,
                "",
                true,
                Collections.singletonList(new Flow(526, "Bones", 1, 100, 100L))),
            1);

        SessionMetrics before = session.metrics(10_000L);
        CharacterIdleModel idle = new CharacterIdleModel();
        idle.delayMillis = 2_000L;
        assertFalse(idle.tick(false, false, 10_000L));
        assertTrue(idle.tick(false, false, 12_000L));
        assertTrue(idle.characterIdle);

        SessionMetrics after = session.metrics(10_000L);
        assertEquals(before.elapsedMillis, after.elapsedMillis);
        assertEquals(before.profitPerHour, after.profitPerHour);
        assertEquals(before.net, after.net);
        assertFalse(session.paused);
    }

    @Test
    public void afkPauseStillFreezesRateAndLabelsAfk()
    {
        Session session = new Session("Test", 0L);
        session.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                Context.GENERIC,
                "",
                true,
                Collections.singletonList(new Flow(526, "Bones", 1, 100, 100L))),
            1);

        session.pause(2_000L, PauseReason.IDLE);
        SessionMetrics atPause = session.metrics(2_000L);
        SessionMetrics later = session.metrics(62_000L);
        assertEquals(atPause.elapsedMillis, later.elapsedMillis);
        assertEquals(atPause.profitPerHour, later.profitPerHour);
        assertEquals(PauseReason.IDLE, session.getPauseReason());
    }
}
