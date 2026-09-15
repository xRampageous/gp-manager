package com.gpmanager.engine;

import com.google.gson.Gson;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.GoalDefinition;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.PauseReason;
import com.gpmanager.model.TileLayout;
import com.gpmanager.persistence.SavedState;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SessionOwnershipTest
{
    private GpManagerEngine engine(int historyLimit)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public int maxHistorySessions() { return historyLimit; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new GpManagerEngine(deltas ->
            Collections.singletonList(new ItemFlow(995, "Coins", deltas.get(995), 1, deltas.get(995))),
            new TransactionClassifier(), config);
    }

    @Test
    public void customSessionSuspendsGeneralAndOwnsEachObservedTransaction()
    {
        GpManagerEngine engine = engine(10);
        engine.ensureSession(0L);
        ProfitSession general = engine.getGeneralSession();
        assertEquals("Overall", general.getName());
        engine.setBaseline(snapshot(0L));

        engine.startCustomSession("Vorkath", SessionMode.AUTO, 100L);
        ProfitSession custom = engine.getActiveSession();
        assertNotEquals(general.getId(), custom.getId());
        assertTrue(general.isPaused());
        engine.setBaseline(snapshot(0L));
        engine.markInventoryDirty();
        engine.processIfDirty(snapshot(250L), 200L);
        ProfitTransaction transaction = engine.processIfDirty(snapshot(250L), 201L);

        assertEquals(250L, transaction.getNet());
        assertEquals(1, custom.getTransactions().size());
        assertEquals(0, general.getTransactions().size());
        assertTrue(engine.finishCustomSession(1_000L));
        assertEquals(general.getId(), engine.getActiveSession().getId());
        assertFalse(general.isPaused());
        assertEquals(1, engine.getHistory().size());
        assertEquals(custom.getId(), engine.getHistory().get(0).getId());
        assertEquals(1_100L, engine.getMetrics(2_000L).getElapsedMillis());
    }

    @Test
    public void shutdownKeepsGeneralDurableAndRestoresItAsRecovery()
    {
        GpManagerEngine engine = engine(10);
        engine.ensureSession(1_000L);
        String generalId = engine.getGeneralSession().getId();

        engine.prepareForShutdown(2_000L);
        SavedState saved = engine.createSavedState();

        assertEquals(generalId, saved.getGeneralSession().getId());
        assertEquals(0, saved.getHistory().size());
        GpManagerEngine restored = engine(10);
        restored.restore(saved);
        assertEquals(generalId, restored.getActiveSession().getId());
        assertTrue(restored.getActiveSession().isRecoveredFromCrash());
    }

    @Test
    public void manualPauseCreatesTheGeneralOwnerWhenAutoStartWasDisabled()
    {
        GpManagerEngine engine = engine(10);

        engine.togglePause(1_000L);

        assertEquals("Overall", engine.getActiveSession().getName());
        assertEquals(engine.getGeneralSession().getId(), engine.getActiveSession().getId());
        assertTrue(engine.getActiveSession().isPaused());
    }

    @Test
    public void restoreMigratesLegacyGeneralNameToOverall()
    {
        GpManagerEngine engine = engine(10);
        engine.ensureSession(1_000L);
        engine.getGeneralSession().rename("General");
        SavedState saved = engine.createSavedState();
        assertEquals("General", saved.getGeneralSession().getName());

        GpManagerEngine restored = engine(10);
        restored.restore(saved);
        assertEquals("Overall", restored.getGeneralSession().getName());
        assertEquals("Overall", restored.getActiveSession().getName());
    }

    @Test
    public void customTransitionPreservesEveryGeneralPauseOwner()
    {
        for (PauseReason reason : new PauseReason[] {PauseReason.IDLE, PauseReason.MANUAL, PauseReason.STOPPED, PauseReason.RECOVERY})
        {
            GpManagerEngine engine = engine(10);
            engine.ensureSession(1_000L);
            ProfitSession general = engine.getGeneralSession();
            if (reason == PauseReason.IDLE) engine.pauseForIdle(1_100L);
            if (reason == PauseReason.MANUAL) engine.togglePause(1_100L);
            if (reason == PauseReason.STOPPED) engine.stop(1_100L);
            if (reason == PauseReason.RECOVERY)
            {
                general.pause(1_100L, PauseReason.RECOVERY);
                general.markRecoveredFromCrash();
            }
            engine.startCustomSession("Custom", SessionMode.AUTO, 1_200L);
            engine.finishCustomSession(1_300L);
            assertEquals(reason, general.getPauseReason());
            if (reason == PauseReason.IDLE || reason == PauseReason.RECOVERY)
            {
                engine.resumeAfterActivity(1_400L);
                assertFalse(general.isPaused());
            }
            else
            {
                engine.resumeAfterActivity(1_400L);
                assertTrue(general.isPaused());
            }
        }
    }

    @Test
    public void startingSecondCustomDoesNotArchiveTheFirst()
    {
        GpManagerEngine engine = engine(10);
        engine.ensureSession(0L);
        engine.startCustomSession("First", SessionMode.AUTO, 1L);
        String first = engine.getActiveSession().getId();
        engine.startCustomSession("Second", SessionMode.AUTO, 2L);
        assertEquals(first, engine.getActiveSession().getId());
        assertEquals(0, engine.getHistory().size());
    }

    @Test
    public void idleGeneralPauseOwnerSurvivesCustomPersistenceAndFinish()
    {
        GpManagerEngine engine = engine(10);
        engine.ensureSession(1_000L);
        engine.pauseForIdle(1_100L);
        engine.startCustomSession("Raid", SessionMode.AUTO, 1_200L);
        SavedState saved = new Gson().fromJson(new Gson().toJson(engine.createSavedState()), SavedState.class);

        GpManagerEngine restored = engine(10);
        restored.restore(saved);
        restored.finishCustomSession(1_300L);

        assertEquals(PauseReason.IDLE, restored.getGeneralSession().getPauseReason());
        restored.resumeAfterActivity(1_400L);
        assertFalse(restored.getGeneralSession().isPaused());
    }

    @Test
    public void explicitOwnersSurviveRestartAndCustomRecoveryWithoutMerging()
    {
        GpManagerEngine engine = engine(10);
        engine.ensureSession(1_000L);
        String generalId = engine.getGeneralSession().getId();
        engine.startCustomSession("Raid", SessionMode.MIXED, 2_000L);
        String customId = engine.getActiveSession().getId();

        SavedState saved = new Gson().fromJson(new Gson().toJson(engine.createSavedState()), SavedState.class);
        GpManagerEngine restored = engine(10);
        restored.restore(saved);

        assertEquals(generalId, restored.getGeneralSession().getId());
        assertEquals(customId, restored.getActiveSession().getId());
        assertTrue(restored.isCustomSessionActive());
        assertTrue(restored.getActiveSession().isRecoveredFromCrash());
        assertTrue(restored.getGeneralSession().isPaused());
    }

    @Test
    public void enginePersistsGoalsAndTileLayoutThroughSavedStateRoundTrip()
    {
        GpManagerEngine engine = engine(10);
        GoalDefinition goal = new GoalDefinition(GoalDefinition.Kind.NET,
            GoalDefinition.Scope.TODAY, 250_000L, Collections.singletonList(50));
        engine.setGoalDefinitions(Collections.singletonList(goal));
        java.util.Map<String, TileLayout.PageLayout> pages = new java.util.LinkedHashMap<>();
        pages.put("live", new TileLayout.PageLayout(Arrays.asList("party", "rates"),
            Collections.singleton("notices")));
        engine.setTileLayout(new TileLayout(pages));

        Gson gson = new Gson();
        SavedState saved = gson.fromJson(gson.toJson(engine.createSavedState()), SavedState.class);
        GpManagerEngine restored = engine(10);
        restored.restore(saved);

        assertEquals(250_000L, restored.getGoalDefinitions().get(0).getTargetValue());
        assertTrue(restored.getTileLayout().isHidden("live", "notices"));
        restored.getGoalDefinitions().get(0).setTargetValue(1L);
        assertEquals("getGoalDefinitions returns detached copies",
            250_000L, restored.getGoalDefinitions().get(0).getTargetValue());
        SavedState savedAgain = gson.fromJson(gson.toJson(restored.createSavedState()), SavedState.class);
        assertEquals(250_000L, savedAgain.getGoalDefinitions().get(0).getTargetValue());
        assertTrue(savedAgain.getTileLayout().isHidden("live", "notices"));
    }

    @Test
    public void legacySingleOwnerMigratesWithoutChangingSessionOrHistory()
    {
        ProfitSession legacy = new ProfitSession("Existing session", 1_000L, SessionMode.AUTO);
        ProfitSession archived = new ProfitSession("Archived", 500L, SessionMode.GENERAL);
        archived.close(900L);
        GpManagerEngine restored = engine(10);
        restored.restore(new SavedState(legacy, Arrays.asList(archived)));

        assertEquals(legacy.getId(), restored.getGeneralSession().getId());
        assertEquals(legacy.getId(), restored.getActiveSession().getId());
        assertFalse(restored.isCustomSessionActive());
        assertEquals(1, restored.getHistory().size());
        assertEquals(archived.getId(), restored.getHistory().get(0).getId());
    }

    @Test
    public void pauseStopLogoutAndRetentionDoNotCreateASecondOwner()
    {
        GpManagerEngine engine = engine(2);
        engine.ensureSession(1_000L);
        String generalId = engine.getGeneralSession().getId();
        engine.togglePause(2_000L);
        engine.pauseForLifecycle(3_000L);
        engine.resumeAfterLifecycle(4_000L);
        assertTrue(engine.getActiveSession().isPaused());
        engine.stop(5_000L);
        engine.resumeAfterActivity(6_000L);
        assertTrue(engine.isStopped());

        engine.startCustomSession("First", SessionMode.AUTO, 7_000L);
        engine.finishCustomSession(8_000L);
        engine.startCustomSession("Second", SessionMode.AUTO, 9_000L);
        engine.finishCustomSession(10_000L);
        engine.startCustomSession("Third", SessionMode.AUTO, 11_000L);
        engine.finishCustomSession(12_000L);

        assertEquals(generalId, engine.getGeneralSession().getId());
        assertEquals(generalId, engine.getActiveSession().getId());
        assertEquals(2, engine.getHistory().size());
        assertTrue(engine.getGeneralSession().isStopped());
    }

    private static ContainerSnapshot snapshot(long coins)
    {
        return new ContainerSnapshot(Collections.singletonMap(995, coins));
    }
}
