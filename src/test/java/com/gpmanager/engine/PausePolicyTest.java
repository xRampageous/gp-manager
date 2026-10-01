package com.gpmanager;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PausePolicyTest
{
    private Engine engine(boolean autoStart)
    {
        GpManagerConfig config = new GpManagerConfig()
        {

            @Override
            public boolean autoStartSession()
            {
                return autoStart;
            }

            @Override
            public int stabilizationTicks()
            {
                return 0;
            }
        };
        return new Engine(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            config);
    }

    @Test
    public void manualPauseSurvivesGameplayActivityEvenWhenLegacyAutoResumeEnabled()
    {
        Engine engine = engine(true);
        engine.ensureSession(1_000L);
        engine.togglePause(2_000L);
        assertEquals(PauseReason.MANUAL, engine.getActiveSession().getPauseReason());
        engine.resume(3_000L, PauseReason.IDLE);
        engine.resume(4_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        engine.resume(5_000L, PauseReason.LIFECYCLE);
        assertTrue(engine.getActiveSession().paused);
        assertEquals(PauseReason.MANUAL, engine.getActiveSession().getPauseReason());
        assertEquals(1_000L, engine.getMetrics(5_000L).elapsedMillis);
    }

    @Test
    public void idlePauseResumesOnActivityButManualPauseDoesNot()
    {
        Engine engine = engine(true);
        engine.ensureSession(1_000L);
        engine.pauseForIdle(2_000L, 2_000L);
        engine.resume(3_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        assertFalse(engine.getActiveSession().paused);

        engine.togglePause(4_000L);
        engine.resume(5_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        engine.resume(6_000L, PauseReason.IDLE);
        engine.resume(7_000L, PauseReason.LIFECYCLE);
        assertTrue(engine.getActiveSession().paused);
    }

    @Test
    public void recoveredPauseResumesOnActivityOrExplicitResume()
    {
        Engine engine = engine(true);
        engine.ensureSession(1_000L);
        engine.togglePause(2_000L);
        engine.restore(engine.createSavedState());
        assertTrue(engine.getActiveSession().recoveredFromCrash);
        assertEquals(PauseReason.RECOVERY, engine.getActiveSession().getPauseReason());
        engine.resume(2_500L, PauseReason.LIFECYCLE);
        assertTrue("Login must not clear Recovery", engine.getActiveSession().paused);
        engine.resume(3_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        assertFalse(engine.getActiveSession().paused);

        engine = engine(true);
        engine.ensureSession(1_000L);
        engine.togglePause(2_000L);
        engine.restore(engine.createSavedState());
        engine.togglePause(4_000L);
        assertFalse(engine.getActiveSession().paused);
    }

    @Test
    public void inventoryChangesDuringManualPauseDoNotBecomeCatchUpProfit()
    {
        Engine engine = new Engine(
            deltas -> Collections.singletonList(
                new Flow(1511, "Oak logs", deltas.getOrDefault(1511, 0L), 39,
                    deltas.getOrDefault(1511, 0L) * 39L)),
            new TransactionClassifier(),
            new GpManagerConfig()
            {
                @Override
                public int stabilizationTicks()
                {
                    return 0;
                }
            });
        engine.ensureSession(1_000L);
        Map<Integer, Long> empty = new HashMap<>();
        Map<Integer, Long> logs = new HashMap<>();
        logs.put(1511, 5L);
        engine.setBaseline(new ContainerSnapshot(empty));
        engine.togglePause(2_000L);
        assertNull(engine.processIfDirty(new ContainerSnapshot(logs), 3_000L));
        assertEquals(0L, engine.getMetrics(3_000L).net);
        engine.togglePause(4_000L);
        assertNull(engine.processIfDirty(new ContainerSnapshot(logs), 5_000L));
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void theActionThatResumesAnIdlePauseStillCounts()
    {
        Engine engine = new Engine(
            deltas -> Collections.singletonList(
                new Flow(1511, "Oak logs", deltas.getOrDefault(1511, 0L), 39,
                    deltas.getOrDefault(1511, 0L) * 39L)),
            new TransactionClassifier(),
            new GpManagerConfig()
            {
                @Override
                public int stabilizationTicks()
                {
                    return 0;
                }
            });
        engine.ensureSession(1_000L);
        Map<Integer, Long> empty = new HashMap<>();
        Map<Integer, Long> logs = new HashMap<>();
        logs.put(1511, 5L);
        engine.setBaseline(new ContainerSnapshot(empty));
        engine.pauseForIdle(2_000L, 2_000L);
        assertTrue(engine.getActiveSession().paused);
        assertEquals(PauseReason.IDLE, engine.getActiveSession().getPauseReason());
        // The pause aligns its baseline on the next tick; then the action's gain settles
        // while still paused, and that gain is what wakes the session.
        engine.processIfDirty(new ContainerSnapshot(empty), 2_500L);
        engine.processIfDirty(new ContainerSnapshot(logs), 3_000L);
        assertFalse("the resuming gain wakes the session", engine.getActiveSession().paused);
        engine.processIfDirty(new ContainerSnapshot(logs), 4_000L);
        assertEquals("the action that resumed the session counts", 195L,
            engine.getMetrics(4_000L).net);
    }

    @Test
    public void automaticStartupCreatesGeneralOnlyWhenEnabledAndMissing()
    {
        Engine disabled = engine(false);
        assertFalse(disabled.startGeneralFromActivityIfNeeded(1_000L));
        assertNull(disabled.getActiveSession());
        assertNull(disabled.processIfDirty(new ContainerSnapshot(Collections.emptyMap()), 1_100L));

        Engine enabled = engine(true);
        assertTrue(enabled.startGeneralFromActivityIfNeeded(1_000L));
        assertNotNull(enabled.getActiveSession());
        assertFalse(enabled.startGeneralFromActivityIfNeeded(1_200L));
    }

    @Test
    public void lifecycleResumeDoesNotCancelManualPause()
    {
        Engine engine = engine(true);
        engine.ensureSession(1_000L);
        engine.pauseForLifecycle(2_000L);
        engine.togglePause(2_500L); // explicit manual while... actually if already paused lifecycle, toggle resumes then we'd pause manual
        // Start fresh: live -> manual
        engine = engine(true);
        engine.ensureSession(1_000L);
        engine.togglePause(2_000L);
        engine.resume(3_000L, PauseReason.LIFECYCLE);
        assertEquals(PauseReason.MANUAL, engine.getActiveSession().getPauseReason());
    }

    /**
     * Login init resumes LIFECYCLE pauses only (see GpManagerPlugin.initializeLoggedInState).
     * Hop/relog clears LIFECYCLE; Idle and Recovery stay until activity; Stop stays sticky.
     */
    @Test
    public void loginResumePolicyClearsLifecycleOnly()
    {
        Engine lifecycle = engine(true);
        lifecycle.ensureSession(1_000L);
        lifecycle.pauseForLifecycle(2_000L);
        lifecycle.resume(3_000L, PauseReason.LIFECYCLE);
        assertFalse(lifecycle.getActiveSession().paused);

        Engine idle = engine(true);
        idle.ensureSession(1_000L);
        idle.pauseForIdle(2_000L, 2_000L);
        idle.resume(3_000L, PauseReason.LIFECYCLE);
        assertTrue(idle.getActiveSession().paused);
        assertEquals(PauseReason.IDLE, idle.getActiveSession().getPauseReason());
        idle.resume(4_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        assertFalse(idle.getActiveSession().paused);
    }

    /** The boolean is the plugin's signal to install a trusted baseline right after a resume. */
    @Test
    public void resumeReportsWhetherItActuallyClearedAPause()
    {
        Engine engine = engine(true);
        engine.ensureSession(1_000L);
        assertFalse("a live session does not resume", engine.resume(2_000L, PauseReason.IDLE));
        engine.pauseForIdle(3_000L, 3_000L);
        assertTrue("an idle pause clears", engine.resume(4_000L, PauseReason.IDLE));
        assertFalse("an already-live session reports false", engine.resume(5_000L, PauseReason.IDLE));
        engine.togglePause(6_000L);
        assertFalse("manual pause stays", engine.resume(7_000L, PauseReason.LIFECYCLE));
    }
}
