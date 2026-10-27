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
        Session session = new Session("Test", 0L, SessionMode.GENERAL);
        session.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                Context.GENERIC,
                "",
                true,
                Collections.singletonList(new Flow(526, "Bones", 1, 100, 100L))),
            1, false);

        Metrics before = session.metrics(10_000L);
        CharacterIdleModel idle = new CharacterIdleModel();
        idle.delay = 2_000L;
        assertFalse(idle.tick(false, false, 10_000L));
        assertTrue(idle.tick(false, false, 12_000L));
        assertTrue(idle.idle);

        Metrics after = session.metrics(10_000L);
        assertEquals(before.elapsed, after.elapsed);
        assertEquals(before.rate, after.rate);
        assertEquals(before.net, after.net);
        assertFalse(session.paused);
    }

    /** Owner live 1.5.2: the NPC's name outlasts the gap between kills (Activity label timeout). */
    @Test
    public void theActivityNameWaitsTheLabelTimeout()
    {
        CharacterIdleModel idle = new CharacterIdleModel();
        idle.delay = GpManagerPlugin.idleDelay(new GpManagerConfig() {});
        assertEquals(15_000L, idle.delay);
        assertFalse(idle.tick(true, true, 0L));
        assertFalse("still named 6 s after the kill", idle.tick(false, false, 1_000L));
        assertFalse(idle.tick(false, false, 7_000L));
        assertTrue("dropped at the timeout", idle.tick(false, false, 16_000L));
    }

    @Test
    public void afkPauseStillFreezesRateAndLabelsAfk()
    {
        Session session = new Session("Test", 0L, SessionMode.GENERAL);
        session.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                Context.GENERIC,
                "",
                true,
                Collections.singletonList(new Flow(526, "Bones", 1, 100, 100L))),
            1, false);

        session.pause(2_000L, PauseReason.IDLE);
        Metrics atPause = session.metrics(2_000L);
        Metrics later = session.metrics(62_000L);
        assertEquals(atPause.elapsed, later.elapsed);
        assertEquals(atPause.rate, later.rate);
        assertEquals(PauseReason.IDLE, session.pauseReason());
    }
}
