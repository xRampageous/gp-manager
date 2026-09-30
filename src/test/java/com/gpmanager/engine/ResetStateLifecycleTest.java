package com.gpmanager;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A data / factory reset leaves the profile exactly as a new install: no session, nothing
 * accruing. Only Start or the plugin's genuine-activity start
 * ({@link Am#ait}, when Automatic tracking is on)
 * begins tracking, each exactly once; the passive work around a reset (priming, ticks with
 * unchanged containers, saved-state snapshots, restore) never does.
 */
public class ResetStateLifecycleTest
{
    private static final int OAK_LOGS = 1521;

    private static Am engine(AtomicBoolean autoStart)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public boolean autoStartSession() { return autoStart.get(); }
            @Override public int stabilizationTicks() { return 0; }
        };
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> e : deltas.entrySet())
            {
                flows.add(new Ab(e.getKey(), "Oak logs", e.getValue(), 40, e.getValue() * 40L));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static Am resetEngine(AtomicBoolean autoStart)
    {
        Am engine = engine(autoStart);
        engine.rm(1_000L);
        engine.setBaseline(new Cc(new HashMap<>()));
        engine.getActiveSession().kf(Tx.of(1_500L, Ai.GAIN,
            Aj.GENERIC, "Old", true,
            Collections.singletonList(new Ab(OAK_LOGS, "Oak logs", 5L, 40, 200L))), 100);
        engine.agr(2_000L);
        return engine;
    }

    @Test
    public void resetLeavesANewInstall()
    {
        Am engine = resetEngine(new AtomicBoolean(true));
        assertNull("no session until play or Start", engine.getActiveSession());
        assertNull(engine.getGeneralSession());
        assertEquals("no history survives", 0, engine.getHistory().size());
        assertEquals(0L, engine.getMetrics(2_000L).net);
        assertEquals(0L, engine.getMetrics(3_600_000L).elapsedMillis);
        assertFalse("no custom session starts because of reset", engine.wb());
    }

    @Test
    public void startAfterResetBeginsTrackingOnce()
    {
        Am engine = resetEngine(new AtomicBoolean(false));
        engine.togglePause(3_000L);
        assertNotNull("Start creates Free play", engine.getActiveSession());
        assertFalse("and it runs, never born paused", engine.getActiveSession().paused);
        assertEquals("active time starts at Start", 60_000L, engine.getActiveSession().getElapsedMillis(63_000L));
        String id = engine.getActiveSession().getId();
        assertFalse("no second start from the activity path", engine.ait(3_100L));
        assertEquals(id, engine.getActiveSession().getId());
    }

    @Test
    public void genuineActivityStartsTrackingOnceAndThenBooks()
    {
        Am engine = resetEngine(new AtomicBoolean(true));
        assertTrue(engine.ait(3_000L));
        assertFalse("already running: no duplicate start", engine.ait(3_100L));

        engine.setBaseline(new Cc(new HashMap<>()));
        engine.yz();
        Map<Integer, Long> logs = new HashMap<>();
        logs.put(OAK_LOGS, 1L);
        assertNull(engine.adj(new Cc(logs), 3_200L));
        Ac gain = engine.adj(new Cc(logs), 3_800L);
        assertNotNull(gain);
        assertEquals(40L, gain.getNet());
        assertEquals(40L, engine.getMetrics(4_000L).net);
    }

    @Test
    public void passiveResetWorkNeverStartsTracking()
    {
        Am engine = resetEngine(new AtomicBoolean(true));
        engine.lp();
        Cc unchanged = new Cc(new HashMap<>());
        for (int tick = 0; tick < 20; tick++)
        {
            assertNull(engine.adj(unchanged, 2_200L + tick * 600L));
        }
        engine.resume(15_000L, Ed.LIFECYCLE);
        engine.resume(15_000L, Ed.IDLE, Ed.RECOVERY);
        engine.resume(15_000L, Ed.IDLE);
        assertNull("resumes have nothing to resume", engine.getActiveSession());

        SavedState committed = engine.qm();
        assertTrue(committed.getHistory().isEmpty());
        Am reloaded = engine(new AtomicBoolean(true));
        reloaded.restore(new Gson().fromJson(new Gson().toJson(committed), SavedState.class), 16_000L);
        assertNull("a reload of the reset profile is still a new install", reloaded.getActiveSession());
        assertEquals(0L, reloaded.getMetrics(3_600_000L).net);
    }

    @Test
    public void activityStartHonoursAutomaticTracking()
    {
        AtomicBoolean autoStart = new AtomicBoolean(false);
        Am engine = resetEngine(autoStart);
        assertFalse("Automatic tracking off: activity cannot start", engine.ait(3_000L));
        assertNull(engine.getActiveSession());
        autoStart.set(true);
        assertTrue(engine.ait(4_000L));
        assertFalse(engine.ait(4_100L));
    }
}
