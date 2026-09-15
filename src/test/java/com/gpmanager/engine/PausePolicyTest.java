package com.gpmanager.engine;

import com.google.gson.Gson;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.PauseReason;
import com.gpmanager.persistence.SavedState;
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
    private GpManagerEngine engine(boolean autoStart)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public boolean autoResumeOnActivity()
            {
                return true; // legacy key must never cancel Manual Pause
            }

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
        return new GpManagerEngine(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            config);
    }

    @Test
    public void manualPauseSurvivesGameplayActivityEvenWhenLegacyAutoResumeEnabled()
    {
        GpManagerEngine engine = engine(true);
        engine.ensureSession(1_000L);
        engine.togglePause(2_000L);
        assertEquals(PauseReason.MANUAL, engine.getActiveSession().getPauseReason());
        engine.resumeAfterIdle(3_000L);
        engine.resumeAfterActivity(4_000L);
        engine.resumeAfterLifecycle(5_000L);
        assertTrue(engine.getActiveSession().isPaused());
        assertEquals(PauseReason.MANUAL, engine.getActiveSession().getPauseReason());
        assertEquals(1_000L, engine.getMetrics(5_000L).getElapsedMillis());
    }

    @Test
    public void idlePauseResumesOnActivityButStopDoesNot()
    {
        GpManagerEngine engine = engine(true);
        engine.ensureSession(1_000L);
        engine.pauseForIdle(2_000L);
        engine.resumeAfterActivity(3_000L);
        assertFalse(engine.getActiveSession().isPaused());

        engine.stop(4_000L);
        engine.resumeAfterActivity(5_000L);
        engine.resumeAfterIdle(6_000L);
        engine.resumeAfterLifecycle(7_000L);
        assertTrue(engine.isStopped());
    }

    @Test
    public void stopSurvivesActivityLifecycleAndSaveReload()
    {
        GpManagerEngine engine = engine(true);
        engine.ensureSession(1_000L);
        engine.stop(2_000L);
        engine.resumeAfterActivity(3_000L);
        engine.pauseForLifecycle(4_000L);
        engine.resumeAfterLifecycle(5_000L);
        assertTrue(engine.isStopped());
        Gson gson = new Gson();
        SavedState saved = gson.fromJson(gson.toJson(engine.createSavedState()), SavedState.class);
        GpManagerEngine restored = engine(true);
        restored.restore(saved);
        restored.resumeAfterActivity(6_000L);
        assertTrue(restored.isStopped());
        restored.togglePause(7_000L);
        assertFalse(restored.isStopped());
        assertFalse(restored.getActiveSession().isPaused());
    }

    @Test
    public void recoveredPauseResumesOnActivityOrExplicitResume()
    {
        GpManagerEngine engine = engine(true);
        engine.ensureSession(1_000L);
        engine.togglePause(2_000L);
        engine.restore(engine.createSavedState());
        assertTrue(engine.getActiveSession().isRecoveredFromCrash());
        assertEquals(PauseReason.RECOVERY, engine.getActiveSession().getPauseReason());
        engine.resumeAfterLifecycle(2_500L);
        assertTrue("Login must not clear Recovery", engine.getActiveSession().isPaused());
        engine.resumeAfterActivity(3_000L);
        assertFalse(engine.getActiveSession().isPaused());

        engine = engine(true);
        engine.ensureSession(1_000L);
        engine.togglePause(2_000L);
        engine.restore(engine.createSavedState());
        engine.togglePause(4_000L);
        assertFalse(engine.getActiveSession().isPaused());
    }

    @Test
    public void inventoryChangesDuringManualPauseDoNotBecomeCatchUpProfit()
    {
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.singletonList(
                new ItemFlow(1511, "Oak logs", deltas.getOrDefault(1511, 0L), 39,
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
        assertEquals(0L, engine.getMetrics(3_000L).getNet());
        engine.togglePause(4_000L);
        assertNull(engine.processIfDirty(new ContainerSnapshot(logs), 5_000L));
        assertEquals(0L, engine.getMetrics(5_000L).getNet());
    }

    @Test
    public void automaticStartupCreatesGeneralOnlyWhenEnabledAndMissing()
    {
        GpManagerEngine disabled = engine(false);
        assertFalse(disabled.startGeneralFromActivityIfNeeded(1_000L));
        assertNull(disabled.getActiveSession());
        assertNull(disabled.processIfDirty(new ContainerSnapshot(Collections.emptyMap()), 1_100L));

        GpManagerEngine enabled = engine(true);
        assertTrue(enabled.startGeneralFromActivityIfNeeded(1_000L));
        assertNotNull(enabled.getActiveSession());
        assertFalse(enabled.startGeneralFromActivityIfNeeded(1_200L));
    }

    @Test
    public void lifecycleResumeDoesNotCancelManualPause()
    {
        GpManagerEngine engine = engine(true);
        engine.ensureSession(1_000L);
        engine.pauseForLifecycle(2_000L);
        engine.togglePause(2_500L); // explicit manual while... actually if already paused lifecycle, toggle resumes then we'd pause manual
        // Start fresh: live -> manual
        engine = engine(true);
        engine.ensureSession(1_000L);
        engine.togglePause(2_000L);
        engine.resumeAfterLifecycle(3_000L);
        assertEquals(PauseReason.MANUAL, engine.getActiveSession().getPauseReason());
    }

    /**
     * Login init calls resumeAfterLifecycle only (see GpManagerPlugin.initializeLoggedInState).
     * Hop/relog clears LIFECYCLE; Idle and Recovery stay until activity; Stop stays sticky.
     */
    @Test
    public void loginResumePolicyClearsLifecycleOnly()
    {
        GpManagerEngine lifecycle = engine(true);
        lifecycle.ensureSession(1_000L);
        lifecycle.pauseForLifecycle(2_000L);
        lifecycle.resumeAfterLifecycle(3_000L);
        assertFalse(lifecycle.getActiveSession().isPaused());

        GpManagerEngine idle = engine(true);
        idle.ensureSession(1_000L);
        idle.pauseForIdle(2_000L);
        idle.resumeAfterLifecycle(3_000L);
        assertTrue(idle.getActiveSession().isPaused());
        assertEquals(PauseReason.IDLE, idle.getActiveSession().getPauseReason());
        idle.resumeAfterActivity(4_000L);
        assertFalse(idle.getActiveSession().isPaused());

        GpManagerEngine stopped = engine(true);
        stopped.ensureSession(1_000L);
        stopped.stop(2_000L);
        stopped.resumeAfterLifecycle(3_000L);
        stopped.resumeAfterActivity(4_000L);
        assertTrue(stopped.isStopped());
    }
}
