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
        Engine engine = engine();
        engine.ensureSession(T0);
        assertNotNull(engine.session());
        engine.resetData(T0 + 1_000L);
        assertNull("a reset clears the session", engine.session());

        engine.tickAutoStart(T0 + 1_500L);
        assertNull("still warming up", engine.session());
        engine.tickAutoStart(T0 + 5_001L);
        assertNotNull("the reset resumes tracking by itself", engine.session());
    }

    @Test
    public void aManualPauseCancelsThePendingResume()
    {
        Engine engine = engine();
        engine.resetData(T0);
        engine.togglePause(T0 + 1_000L);
        assertNotNull(engine.session());
        engine.togglePause(T0 + 2_000L);
        assertTrue("the second toggle is a manual pause", engine.session().paused);

        String id = engine.session().id();
        engine.tickAutoStart(T0 + 9_999L);
        assertEquals("a manual pause is respected", id, engine.session().id());
        assertTrue(engine.session().paused);
    }

    private static Engine engine()
    {
        return new Engine(deltas -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig() {});
    }
}
