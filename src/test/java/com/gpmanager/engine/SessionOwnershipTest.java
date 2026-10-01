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
    private Am engine(int historyLimit)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public int maxHistorySessions() { return historyLimit; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new Am(deltas ->
            Collections.singletonList(new Ab(995, "Coins", deltas.get(995), 1, deltas.get(995))),
            new TransactionClassifier(), config);
    }

    @Test
    public void customSessionSuspendsGeneralAndOwnsEachObservedTransaction()
    {
        Am engine = engine(10);
        engine.rm(0L);
        Ad general = engine.getGeneralSession();
        assertEquals("Overall", general.getName());
        engine.setBaseline(snapshot(0L));

        engine.ajl("Vorkath", Cx.AUTO, 100L);
        Ad custom = engine.getActiveSession();
        assertNotEquals(general.getId(), custom.getId());
        assertTrue(general.paused);
        engine.setBaseline(snapshot(0L));
        engine.yz();
        engine.adj(snapshot(250L), 200L);
        Ac transaction = engine.adj(snapshot(250L), 201L);

        assertEquals(250L, transaction.getNet());
        assertEquals(1, custom.getTransactions().size());
        assertEquals(0, general.getTransactions().size());
        assertTrue(engine.sx(1_000L));
        assertEquals(general.getId(), engine.getActiveSession().getId());
        assertFalse(general.paused);
        assertEquals(1, engine.getHistory().size());
        assertEquals(custom.getId(), engine.getHistory().get(0).getId());
        assertEquals(1_100L, engine.getMetrics(2_000L).elapsedMillis);
    }

    @Test
    public void shutdownKeepsGeneralDurableAndRestoresItAsRecovery()
    {
        Am engine = engine(10);
        engine.rm(1_000L);
        String generalId = engine.getGeneralSession().getId();

        engine.acu(2_000L);
        SavedState saved = engine.qm();

        assertEquals(generalId, saved.generalSession.getId());
        assertEquals(0, saved.getHistory().size());
        Am restored = engine(10);
        restored.restore(saved);
        assertEquals(generalId, restored.getActiveSession().getId());
        assertTrue(restored.getActiveSession().recoveredFromCrash);
    }

    @Test
    public void startCreatesARunningGeneralOwnerWhenAutoStartWasDisabled()
    {
        Am engine = engine(10);

        engine.togglePause(1_000L);

        assertEquals("Overall", engine.getActiveSession().getName());
        assertEquals(engine.getGeneralSession().getId(), engine.getActiveSession().getId());
        assertFalse("Start begins tracking; it never creates a paused owner", engine.getActiveSession().paused);
    }

    @Test
    public void customTransitionPreservesEveryGeneralPauseOwner()
    {
        for (Ed reason : new Ed[] {Ed.IDLE, Ed.MANUAL, Ed.RECOVERY})
        {
            Am engine = engine(10);
            engine.rm(1_000L);
            Ad general = engine.getGeneralSession();
            if (reason == Ed.IDLE) engine.adh(1_100L, 1_100L);
            if (reason == Ed.MANUAL) engine.togglePause(1_100L);
            if (reason == Ed.RECOVERY)
            {
                general.pause(1_100L, Ed.RECOVERY);
                general.zg();
            }
            engine.ajl("Custom", Cx.AUTO, 1_200L);
            engine.sx(1_300L);
            assertEquals(reason, general.getPauseReason());
            if (reason == Ed.IDLE || reason == Ed.RECOVERY)
            {
                engine.resume(1_400L, Ed.IDLE, Ed.RECOVERY);
                assertFalse(general.paused);
            }
            else
            {
                engine.resume(1_400L, Ed.IDLE, Ed.RECOVERY);
                assertTrue(general.paused);
            }
        }
    }

    @Test
    public void startingSecondCustomDoesNotArchiveTheFirst()
    {
        Am engine = engine(10);
        engine.rm(0L);
        engine.ajl("First", Cx.AUTO, 1L);
        String first = engine.getActiveSession().getId();
        engine.ajl("Second", Cx.AUTO, 2L);
        assertEquals(first, engine.getActiveSession().getId());
        assertEquals(0, engine.getHistory().size());
    }

    @Test
    public void idleGeneralPauseOwnerSurvivesCustomPersistenceAndFinish()
    {
        Am engine = engine(10);
        engine.rm(1_000L);
        engine.adh(1_100L, 1_100L);
        engine.ajl("Raid", Cx.AUTO, 1_200L);
        SavedState saved = new Gson().fromJson(new Gson().toJson(engine.qm()), SavedState.class);

        Am restored = engine(10);
        restored.restore(saved);
        restored.sx(1_300L);

        assertEquals(Ed.IDLE, restored.getGeneralSession().getPauseReason());
        restored.resume(1_400L, Ed.IDLE, Ed.RECOVERY);
        assertFalse(restored.getGeneralSession().paused);
    }

    @Test
    public void explicitOwnersSurviveRestartAndCustomRecoveryWithoutMerging()
    {
        Am engine = engine(10);
        engine.rm(1_000L);
        String generalId = engine.getGeneralSession().getId();
        engine.ajl("Raid", Cx.MIXED, 2_000L);
        String customId = engine.getActiveSession().getId();

        SavedState saved = new Gson().fromJson(new Gson().toJson(engine.qm()), SavedState.class);
        Am restored = engine(10);
        restored.restore(saved);

        assertEquals(generalId, restored.getGeneralSession().getId());
        assertEquals(customId, restored.getActiveSession().getId());
        assertTrue(restored.wb());
        assertTrue(restored.getActiveSession().recoveredFromCrash);
        assertTrue(restored.getGeneralSession().paused);
    }

    @Test
    public void pauseLogoutAndRetentionDoNotCreateASecondOwner()
    {
        Am engine = engine(2);
        engine.rm(1_000L);
        String generalId = engine.getGeneralSession().getId();
        engine.togglePause(2_000L);
        engine.acu(3_000L);
        engine.resume(4_000L, Ed.LIFECYCLE);
        assertTrue(engine.getActiveSession().paused);
        engine.resume(6_000L, Ed.IDLE, Ed.RECOVERY);
        assertTrue("a manual pause waits for Resume", engine.getActiveSession().paused);

        engine.ajl("First", Cx.AUTO, 7_000L);
        engine.sx(8_000L);
        engine.ajl("Second", Cx.AUTO, 9_000L);
        engine.sx(10_000L);
        engine.ajl("Third", Cx.AUTO, 11_000L);
        engine.sx(12_000L);

        assertEquals(generalId, engine.getGeneralSession().getId());
        assertEquals(generalId, engine.getActiveSession().getId());
        assertEquals(2, engine.getHistory().size());
        assertEquals(Ed.MANUAL, engine.getGeneralSession().getPauseReason());
    }

    private static Cc snapshot(long coins)
    {
        return new Cc(Collections.singletonMap(995, coins));
    }
}
