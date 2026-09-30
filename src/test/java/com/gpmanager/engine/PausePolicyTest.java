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
    private Am engine(boolean autoStart)
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
        return new Am(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            config);
    }

    @Test
    public void manualPauseSurvivesGameplayActivityEvenWhenLegacyAutoResumeEnabled()
    {
        Am engine = engine(true);
        engine.rm(1_000L);
        engine.togglePause(2_000L);
        assertEquals(Ed.MANUAL, engine.getActiveSession().getPauseReason());
        engine.resume(3_000L, Ed.IDLE);
        engine.resume(4_000L, Ed.IDLE, Ed.RECOVERY);
        engine.resume(5_000L, Ed.LIFECYCLE);
        assertTrue(engine.getActiveSession().paused);
        assertEquals(Ed.MANUAL, engine.getActiveSession().getPauseReason());
        assertEquals(1_000L, engine.getMetrics(5_000L).elapsedMillis);
    }

    @Test
    public void idlePauseResumesOnActivityButManualPauseDoesNot()
    {
        Am engine = engine(true);
        engine.rm(1_000L);
        engine.adh(2_000L, 2_000L);
        engine.resume(3_000L, Ed.IDLE, Ed.RECOVERY);
        assertFalse(engine.getActiveSession().paused);

        engine.togglePause(4_000L);
        engine.resume(5_000L, Ed.IDLE, Ed.RECOVERY);
        engine.resume(6_000L, Ed.IDLE);
        engine.resume(7_000L, Ed.LIFECYCLE);
        assertTrue(engine.getActiveSession().paused);
    }

    @Test
    public void recoveredPauseResumesOnActivityOrExplicitResume()
    {
        Am engine = engine(true);
        engine.rm(1_000L);
        engine.togglePause(2_000L);
        engine.restore(engine.qm());
        assertTrue(engine.getActiveSession().recoveredFromCrash);
        assertEquals(Ed.RECOVERY, engine.getActiveSession().getPauseReason());
        engine.resume(2_500L, Ed.LIFECYCLE);
        assertTrue("Login must not clear Recovery", engine.getActiveSession().paused);
        engine.resume(3_000L, Ed.IDLE, Ed.RECOVERY);
        assertFalse(engine.getActiveSession().paused);

        engine = engine(true);
        engine.rm(1_000L);
        engine.togglePause(2_000L);
        engine.restore(engine.qm());
        engine.togglePause(4_000L);
        assertFalse(engine.getActiveSession().paused);
    }

    @Test
    public void inventoryChangesDuringManualPauseDoNotBecomeCatchUpProfit()
    {
        Am engine = new Am(
            deltas -> Collections.singletonList(
                new Ab(1511, "Oak logs", deltas.getOrDefault(1511, 0L), 39,
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
        engine.rm(1_000L);
        Map<Integer, Long> empty = new HashMap<>();
        Map<Integer, Long> logs = new HashMap<>();
        logs.put(1511, 5L);
        engine.setBaseline(new Cc(empty));
        engine.togglePause(2_000L);
        assertNull(engine.adj(new Cc(logs), 3_000L));
        assertEquals(0L, engine.getMetrics(3_000L).net);
        engine.togglePause(4_000L);
        assertNull(engine.adj(new Cc(logs), 5_000L));
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void theActionThatResumesAnIdlePauseStillCounts()
    {
        Am engine = new Am(
            deltas -> Collections.singletonList(
                new Ab(1511, "Oak logs", deltas.getOrDefault(1511, 0L), 39,
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
        engine.rm(1_000L);
        Map<Integer, Long> empty = new HashMap<>();
        Map<Integer, Long> logs = new HashMap<>();
        logs.put(1511, 5L);
        engine.setBaseline(new Cc(empty));
        engine.adh(2_000L, 2_000L);
        assertTrue(engine.getActiveSession().paused);
        assertEquals(Ed.IDLE, engine.getActiveSession().getPauseReason());
        // The pause aligns its baseline on the next tick; then the action's gain settles
        // while still paused, and that gain is what wakes the session.
        engine.adj(new Cc(empty), 2_500L);
        engine.adj(new Cc(logs), 3_000L);
        assertFalse("the resuming gain wakes the session", engine.getActiveSession().paused);
        engine.adj(new Cc(logs), 4_000L);
        assertEquals("the action that resumed the session counts", 195L,
            engine.getMetrics(4_000L).net);
    }

    @Test
    public void automaticStartupCreatesGeneralOnlyWhenEnabledAndMissing()
    {
        Am disabled = engine(false);
        assertFalse(disabled.ait(1_000L));
        assertNull(disabled.getActiveSession());
        assertNull(disabled.adj(new Cc(Collections.emptyMap()), 1_100L));

        Am enabled = engine(true);
        assertTrue(enabled.ait(1_000L));
        assertNotNull(enabled.getActiveSession());
        assertFalse(enabled.ait(1_200L));
    }

    @Test
    public void lifecycleResumeDoesNotCancelManualPause()
    {
        Am engine = engine(true);
        engine.rm(1_000L);
        engine.acu(2_000L);
        engine.togglePause(2_500L); // explicit manual while... actually if already paused lifecycle, toggle resumes then we'd pause manual
        // Start fresh: live -> manual
        engine = engine(true);
        engine.rm(1_000L);
        engine.togglePause(2_000L);
        engine.resume(3_000L, Ed.LIFECYCLE);
        assertEquals(Ed.MANUAL, engine.getActiveSession().getPauseReason());
    }

    /**
     * Login init resumes LIFECYCLE pauses only (see GpManagerPlugin.ve).
     * Hop/relog clears LIFECYCLE; Idle and Recovery stay until activity; Stop stays sticky.
     */
    @Test
    public void loginResumePolicyClearsLifecycleOnly()
    {
        Am lifecycle = engine(true);
        lifecycle.rm(1_000L);
        lifecycle.acu(2_000L);
        lifecycle.resume(3_000L, Ed.LIFECYCLE);
        assertFalse(lifecycle.getActiveSession().paused);

        Am idle = engine(true);
        idle.rm(1_000L);
        idle.adh(2_000L, 2_000L);
        idle.resume(3_000L, Ed.LIFECYCLE);
        assertTrue(idle.getActiveSession().paused);
        assertEquals(Ed.IDLE, idle.getActiveSession().getPauseReason());
        idle.resume(4_000L, Ed.IDLE, Ed.RECOVERY);
        assertFalse(idle.getActiveSession().paused);
    }

    /** The boolean is the plugin's signal to install a trusted baseline right after a resume. */
    @Test
    public void resumeReportsWhetherItActuallyClearedAPause()
    {
        Am engine = engine(true);
        engine.rm(1_000L);
        assertFalse("a live session does not resume", engine.resume(2_000L, Ed.IDLE));
        engine.adh(3_000L, 3_000L);
        assertTrue("an idle pause clears", engine.resume(4_000L, Ed.IDLE));
        assertFalse("an already-live session reports false", engine.resume(5_000L, Ed.IDLE));
        engine.togglePause(6_000L);
        assertFalse("manual pause stays", engine.resume(7_000L, Ed.LIFECYCLE));
    }
}
