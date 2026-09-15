package com.gpmanager.ui;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.PauseReason;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
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
        ProfitSession session = new ProfitSession("Test", 0L);
        session.addTransaction(
            new ProfitTransaction(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                TrackingContext.GENERIC,
                "",
                true,
                Collections.singletonList(new ItemFlow(526, "Bones", 1, 100, 100L))),
            1);

        SessionMetrics before = session.metrics(10_000L, 60_000L);
        CharacterIdleModel idle = new CharacterIdleModel();
        idle.setDelayMillis(2_000L);
        assertFalse(idle.tick(false, false, 10_000L));
        assertTrue(idle.tick(false, false, 12_000L));
        assertTrue(idle.isCharacterIdle());

        SessionMetrics after = session.metrics(10_000L, 60_000L);
        assertEquals(before.getElapsedMillis(), after.getElapsedMillis());
        assertEquals(before.getProfitPerHour(), after.getProfitPerHour());
        assertEquals(before.getNet(), after.getNet());
        assertFalse(session.isPaused());
    }

    @Test
    public void afkPauseStillFreezesRateAndLabelsAfk()
    {
        ProfitSession session = new ProfitSession("Test", 0L);
        session.addTransaction(
            new ProfitTransaction(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                TrackingContext.GENERIC,
                "",
                true,
                Collections.singletonList(new ItemFlow(526, "Bones", 1, 100, 100L))),
            1);

        session.pause(2_000L, PauseReason.IDLE);
        SessionMetrics atPause = session.metrics(2_000L, 60_000L);
        SessionMetrics later = session.metrics(62_000L, 60_000L);
        assertEquals(atPause.getElapsedMillis(), later.getElapsedMillis());
        assertEquals(atPause.getProfitPerHour(), later.getProfitPerHour());
        assertEquals(PauseReason.IDLE, session.getPauseReason());
        assertEquals("AFK", TrackingStatus.compactHudPlus("AFK", true));
        assertEquals("AFK", TrackingStatus.compactHudPlus("Idle", true));
    }
}
