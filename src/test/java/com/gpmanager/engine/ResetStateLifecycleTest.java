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
 * ({@link Engine#autoStart}, when Automatic tracking is on)
 * begins tracking, each exactly once; the passive work around a reset (priming, ticks with
 * unchanged containers, saved-state snapshots, restore) never does.
 */
public class ResetStateLifecycleTest
{
    private static final int OAK_LOGS = 1521;

    private static Engine engine(AtomicBoolean autoStart)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public boolean autoStartSession() { return autoStart.get(); }
            @Override public int stabilizationTicks() { return 0; }
        };
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> e : deltas.entrySet())
            {
                flows.add(new Flow(e.getKey(), "Oak logs", e.getValue(), 40, e.getValue() * 40L));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static Engine resetEngine(AtomicBoolean autoStart)
    {
        Engine engine = engine(autoStart);
        engine.ensureSession(1_000L);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>()));
        engine.session().addTransaction(Tx.of(1_500L, TransactionType.GAIN,
            Context.GENERIC, "Old", true,
            Collections.singletonList(new Flow(OAK_LOGS, "Oak logs", 5L, 40, 200L))), 100, false);
        engine.resetData(2_000L);
        return engine;
    }

    @Test
    public void resetLeavesANewInstall()
    {
        Engine engine = resetEngine(new AtomicBoolean(true));
        assertNull("no session until play or Start", engine.session());
        assertNull(engine.freePlay());
        assertEquals("no history survives", 0, engine.history().size());
        assertEquals(0L, engine.metrics(2_000L).net);
        assertEquals(0L, engine.metrics(3_600_000L).elapsed);
        assertFalse("no custom session starts because of reset", engine.customActive());
    }

    @Test
    public void startAfterResetBeginsTrackingOnce()
    {
        Engine engine = resetEngine(new AtomicBoolean(false));
        engine.togglePause(3_000L);
        assertNotNull("Start creates Free play", engine.session());
        assertFalse("and it runs, never born paused", engine.session().paused);
        assertEquals("active time starts at Start", 60_000L, engine.session().elapsed(63_000L));
        String id = engine.session().id();
        assertFalse("no second start from the activity path", engine.autoStart(3_100L));
        assertEquals(id, engine.session().id());
    }

    @Test
    public void genuineActivityStartsTrackingOnceAndThenBooks()
    {
        Engine engine = resetEngine(new AtomicBoolean(true));
        assertTrue(engine.autoStart(3_000L));
        assertFalse("already running: no duplicate start", engine.autoStart(3_100L));

        engine.setBaseline(new ContainerSnapshot(new HashMap<>()));
        engine.markDirty();
        Map<Integer, Long> logs = new HashMap<>();
        logs.put(OAK_LOGS, 1L);
        assertNull(engine.processIfDirty(new ContainerSnapshot(logs), 3_200L, null));
        Transaction gain = engine.processIfDirty(new ContainerSnapshot(logs), 3_800L, null);
        assertNotNull(gain);
        assertEquals(40L, gain.net());
        assertEquals(40L, engine.metrics(4_000L).net);
    }

    @Test
    public void passiveResetWorkNeverStartsTracking()
    {
        Engine engine = resetEngine(new AtomicBoolean(true));
        engine.beginPriming();
        ContainerSnapshot unchanged = new ContainerSnapshot(new HashMap<>());
        for (int tick = 0; tick < 20; tick++)
        {
            assertNull(engine.processIfDirty(unchanged, 2_200L + tick * 600L, null));
        }
        engine.resume(15_000L, PauseReason.LIFECYCLE);
        engine.resume(15_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        engine.resume(15_000L, PauseReason.IDLE);
        assertNull("resumes have nothing to resume", engine.session());

        SavedState committed = engine.savedState();
        assertTrue(committed.history().isEmpty());
        Engine reloaded = engine(new AtomicBoolean(true));
        reloaded.restore(new Gson().fromJson(new Gson().toJson(committed), SavedState.class), 16_000L);
        assertNull("a reload of the reset profile is still a new install", reloaded.session());
        assertEquals(0L, reloaded.metrics(3_600_000L).net);
    }

    @Test
    public void activityStartHonoursAutomaticTracking()
    {
        AtomicBoolean autoStart = new AtomicBoolean(false);
        Engine engine = resetEngine(autoStart);
        assertFalse("Automatic tracking off: activity cannot start", engine.autoStart(3_000L));
        assertNull(engine.session());
        autoStart.set(true);
        assertTrue(engine.autoStart(4_000L));
        assertFalse(engine.autoStart(4_100L));
    }
}
