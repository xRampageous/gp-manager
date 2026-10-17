package com.gpmanager;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SessionOwnershipTest
{
    private Engine engine(int historyLimit)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public int maxHistorySessions() { return historyLimit; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new Engine(deltas ->
            Collections.singletonList(new Flow(995, "Coins", deltas.get(995), 1, deltas.get(995))),
            new TransactionClassifier(), config);
    }

    @Test
    public void customSessionSuspendsGeneralAndOwnsEachObservedTransaction()
    {
        Engine engine = engine(10);
        engine.ensureSession(0L);
        Session general = engine.getGeneralSession();
        assertEquals("Overall", general.getName());
        engine.setBaseline(snapshot(0L));

        engine.startCustom("Vorkath", 100L);
        Session custom = engine.getActiveSession();
        assertNotEquals(general.getId(), custom.getId());
        assertTrue(general.paused);
        engine.setBaseline(snapshot(0L));
        engine.markDirty();
        engine.processIfDirty(snapshot(250L), 200L, null);
        Transaction transaction = engine.processIfDirty(snapshot(250L), 201L, null);

        assertEquals(250L, transaction.getNet());
        assertEquals(1, custom.getTransactions().size());
        assertEquals(0, general.getTransactions().size());
        assertTrue(engine.finishCustom(1_000L));
        assertEquals(general.getId(), engine.getActiveSession().getId());
        assertFalse(general.paused);
        assertEquals(1, engine.getHistory().size());
        assertEquals(custom.getId(), engine.getHistory().get(0).getId());
        assertEquals(1_100L, engine.getMetrics(2_000L).elapsedMillis);
    }

    @Test
    public void shutdownKeepsGeneralDurableAndRestoresItAsRecovery()
    {
        Engine engine = engine(10);
        engine.ensureSession(1_000L);
        String generalId = engine.getGeneralSession().getId();

        engine.pauseForLifecycle(2_000L);
        SavedState saved = engine.savedState();

        assertEquals(generalId, saved.generalSession.getId());
        assertEquals(0, saved.getHistory().size());
        Engine restored = engine(10);
        restored.restore(saved, SafeMath.now());
        assertEquals(generalId, restored.getActiveSession().getId());
        assertEquals(PauseReason.RECOVERY, restored.getActiveSession().getPauseReason());
    }

    @Test
    public void startCreatesARunningGeneralOwnerWhenAutoStartWasDisabled()
    {
        Engine engine = engine(10);

        engine.togglePause(1_000L);

        assertEquals("Overall", engine.getActiveSession().getName());
        assertEquals(engine.getGeneralSession().getId(), engine.getActiveSession().getId());
        assertFalse("Start begins tracking; it never creates a paused owner", engine.getActiveSession().paused);
    }

    @Test
    public void customTransitionPreservesEveryGeneralPauseOwner()
    {
        for (PauseReason reason : new PauseReason[] {PauseReason.IDLE, PauseReason.MANUAL, PauseReason.RECOVERY})
        {
            Engine engine = engine(10);
            engine.ensureSession(1_000L);
            Session general = engine.getGeneralSession();
            if (reason == PauseReason.IDLE) engine.getActiveSession().pause(1_100L, PauseReason.IDLE);
            if (reason == PauseReason.MANUAL) engine.togglePause(1_100L);
            if (reason == PauseReason.RECOVERY) general.pause(1_100L, PauseReason.RECOVERY);
            engine.startCustom("Custom", 1_200L);
            engine.finishCustom(1_300L);
            assertEquals(reason, general.getPauseReason());
            if (reason == PauseReason.IDLE || reason == PauseReason.RECOVERY)
            {
                engine.resume(1_400L, PauseReason.IDLE, PauseReason.RECOVERY);
                assertFalse(general.paused);
            }
            else
            {
                engine.resume(1_400L, PauseReason.IDLE, PauseReason.RECOVERY);
                assertTrue(general.paused);
            }
        }
    }

    @Test
    public void startingSecondCustomDoesNotArchiveTheFirst()
    {
        Engine engine = engine(10);
        engine.ensureSession(0L);
        engine.startCustom("First", 1L);
        String first = engine.getActiveSession().getId();
        engine.startCustom("Second", 2L);
        assertEquals(first, engine.getActiveSession().getId());
        assertEquals(0, engine.getHistory().size());
    }

    @Test
    public void idleGeneralPauseOwnerSurvivesCustomPersistenceAndFinish()
    {
        Engine engine = engine(10);
        engine.ensureSession(1_000L);
        engine.getActiveSession().pause(1_100L, PauseReason.IDLE);
        engine.startCustom("Raid", 1_200L);
        SavedState saved = new Gson().fromJson(new Gson().toJson(engine.savedState()), SavedState.class);

        Engine restored = engine(10);
        restored.restore(saved, SafeMath.now());
        restored.finishCustom(1_300L);

        assertEquals(PauseReason.IDLE, restored.getGeneralSession().getPauseReason());
        restored.resume(1_400L, PauseReason.IDLE, PauseReason.RECOVERY);
        assertFalse(restored.getGeneralSession().paused);
    }

    @Test
    public void explicitOwnersSurviveRestartAndCustomRecoveryWithoutMerging()
    {
        Engine engine = engine(10);
        engine.ensureSession(1_000L);
        String generalId = engine.getGeneralSession().getId();
        engine.startCustom("Raid", 2_000L);
        String customId = engine.getActiveSession().getId();

        SavedState saved = new Gson().fromJson(new Gson().toJson(engine.savedState()), SavedState.class);
        Engine restored = engine(10);
        restored.restore(saved, SafeMath.now());

        assertEquals(generalId, restored.getGeneralSession().getId());
        assertEquals(customId, restored.getActiveSession().getId());
        assertTrue(restored.isCustomActive());
        assertEquals(PauseReason.RECOVERY, restored.getActiveSession().getPauseReason());
        assertTrue(restored.getGeneralSession().paused);
    }

    @Test
    public void pauseLogoutAndRetentionDoNotCreateASecondOwner()
    {
        Engine engine = engine(2);
        engine.ensureSession(1_000L);
        String generalId = engine.getGeneralSession().getId();
        engine.togglePause(2_000L);
        engine.pauseForLifecycle(3_000L);
        engine.resume(4_000L, PauseReason.LIFECYCLE);
        assertTrue(engine.getActiveSession().paused);
        engine.resume(6_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        assertTrue("a manual pause waits for Resume", engine.getActiveSession().paused);

        engine.startCustom("First", 7_000L);
        engine.finishCustom(8_000L);
        engine.startCustom("Second", 9_000L);
        engine.finishCustom(10_000L);
        engine.startCustom("Third", 11_000L);
        engine.finishCustom(12_000L);

        assertEquals(generalId, engine.getGeneralSession().getId());
        assertEquals(generalId, engine.getActiveSession().getId());
        assertEquals(2, engine.getHistory().size());
        assertEquals(PauseReason.MANUAL, engine.getGeneralSession().getPauseReason());
    }

    private static ContainerSnapshot snapshot(long coins)
    {
        return new ContainerSnapshot(Collections.singletonMap(995, coins));
    }
}
