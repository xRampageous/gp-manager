package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.*;

/** A factory reset resumes tracking by itself shortly after; a manual stop wins. */
public class FactoryResetResumeTest
{
    private static final long T0 = 10_000L;

    @Test
    public void factoryResetResumesByItselfShortly()
    {
        Am engine = engine();
        engine.rm(T0);
        assertNotNull(engine.getActiveSession());
        engine.agr(T0 + 1_000L);
        assertNull("a reset clears the session", engine.getActiveSession());

        engine.tickAutoStart(T0 + 1_500L);
        assertNull("still warming up", engine.getActiveSession());
        engine.tickAutoStart(T0 + 5_001L);
        assertNotNull("the reset resumes tracking by itself", engine.getActiveSession());
    }

    @Test
    public void aManualPauseCancelsThePendingResume()
    {
        Am engine = engine();
        engine.agr(T0);
        engine.togglePause(T0 + 1_000L);
        assertNotNull(engine.getActiveSession());
        engine.togglePause(T0 + 2_000L);
        assertTrue("the second toggle is a manual pause", engine.getActiveSession().paused);

        String id = engine.getActiveSession().getId();
        engine.tickAutoStart(T0 + 9_999L);
        assertEquals("a manual pause is respected", id, engine.getActiveSession().getId());
        assertTrue(engine.getActiveSession().paused);
    }

    private static Am engine()
    {
        return new Am(deltas -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig() {});
    }
}
