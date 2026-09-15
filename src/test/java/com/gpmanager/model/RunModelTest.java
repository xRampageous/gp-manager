package com.gpmanager.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gpmanager.persistence.SavedState;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class RunModelTest
{
    @Test
    public void newSessionHasOneOpenRunAndSplitRollupsReconcileByActiveDuration()
    {
        ProfitSession session = new ProfitSession("Boss", 0L);
        String firstId = session.getCurrentRunId(0L);
        assertNotNull(firstId);
        session.addTransaction(receipt(600_000L, 600_000L, 1000, "Drop", 1_000L), 100);

        Run second = session.startRun("Second trip", 1_800_000L);
        assertNotNull(second);
        session.addTransaction(receipt(2_400_000L, 2_400_000L, 2000, "Drop", 2_000L), 100);
        session.addTransaction(receipt(2_400_001L, 2_400_001L, 2001, "Food", -500L), 100);

        RunHistorySnapshot history = session.runHistorySnapshot(3_600_000L);
        assertEquals(2, history.getStatements().size());
        RunStatementSnapshot first = history.getStatements().get(0);
        RunStatementSnapshot secondStatement = history.getStatements().get(1);
        assertEquals(firstId, first.getRunId());
        assertEquals("CLOSED", first.getStatus());
        assertEquals(1_000L, first.getLootGp());
        assertEquals(0L, first.getSuppliesGp());
        assertEquals(1_800_000L, first.getActiveDurationMillis());
        assertEquals(2_000L, first.getGpPerHour());
        assertEquals(2_000L, secondStatement.getLootGp());
        assertEquals(500L, secondStatement.getSuppliesGp());
        assertEquals(1_500L, secondStatement.getNetGp());
        assertEquals(1_800_000L, secondStatement.getActiveDurationMillis());
        assertEquals(3_000L, secondStatement.getGpPerHour());
        assertFalse(first.isLeftOnGroundAvailable());
        assertNull(first.getLeftOnGroundGp());

        SessionMetrics sessionMetrics = session.metrics(3_600_000L, 60_000L);
        assertEquals(sessionMetrics.getRevenue(), first.getLootGp() + secondStatement.getLootGp());
        assertEquals(sessionMetrics.getCosts(), first.getSuppliesGp() + secondStatement.getSuppliesGp());
        long durationWeightedRate = Math.round(
            ((first.getNetGp() + secondStatement.getNetGp()) * 3_600_000.0d)
                / (first.getActiveDurationMillis() + secondStatement.getActiveDurationMillis()));
        assertEquals(sessionMetrics.getProfitPerHour(), durationWeightedRate);

        RunComparisonSnapshot comparison = session.compareRuns(firstId, second.getId(), 3_600_000L);
        assertTrue(comparison.isAvailable());
        assertEquals(Long.valueOf(1_000L), comparison.getLootDeltaGp());
        assertEquals(Long.valueOf(500L), comparison.getSuppliesDeltaGp());
        assertEquals(Long.valueOf(500L), comparison.getNetDeltaGp());
        assertEquals(Long.valueOf(1_000L), comparison.getGpPerHourDelta());
    }

    @Test
    public void correctionsUndoAndRestoreKeepReceiptRunAssignment()
    {
        ProfitSession session = new ProfitSession("Owner", 0L);
        ProfitTransaction receipt = receipt(100L, 100L, 1, "Loot", 100L);
        session.addTransaction(receipt, 10);
        String sourceRun = receipt.getRunId();
        assertNotNull(session.startRun("Next", 200L));

        assertTrue(session.correctTransaction(receipt.getId(), TransactionCorrection.REVENUE, 300L));
        assertEquals(sourceRun, receipt.getRunId());
        ProfitTransaction removed = session.undoLastTransaction(400L);
        assertEquals(receipt.getId(), removed.getId());
        assertEquals(sourceRun, removed.getRunId());
        ProfitTransaction restored = session.restoreLastUndo(500L);
        assertEquals(sourceRun, restored.getRunId());
        assertEquals(sourceRun, session.runHistorySnapshot(600L).getStatements().get(0).getRunId());
    }

    @Test
    public void delayedClaimUsesEncounterRunOrRemainsExplicitlyUnassigned()
    {
        ProfitSession session = new ProfitSession("Wilderness", 0L, SessionMode.PK);
        ProfitTransaction source = new ProfitTransaction(100L, 100L,
            TransactionType.PK_LOOT, TrackingContext.PK_LOOT, "Key", "PKing", false,
            Collections.singletonList(new ItemFlow(1, "Loot key", 1L, 0, 0L)),
            ClassificationConfidence.CONFIRMED, "Source", "encounter-1");
        session.addTransaction(source, 20);
        String sourceRun = source.getRunId();
        session.startRun("Later", 200L);

        LootKeyProvenance provenance = claimedKey("encounter-1");
        ProfitTransaction delayed = new ProfitTransaction(300L, 300L,
            TransactionType.PK_LOOT, TrackingContext.PK_LOOT, "Claim", "PKing", true,
            Collections.singletonList(new ItemFlow(2, "Manifest item", 1L, 50, 50L)),
            ClassificationConfidence.CONFIRMED, "Claim", "encounter-1");
        delayed.addLootKeyProvenance(provenance);
        session.addTransaction(delayed, 20);
        assertEquals(sourceRun, delayed.getRunId());

        ProfitTransaction unresolved = new ProfitTransaction(400L, 400L,
            TransactionType.PK_LOOT, TrackingContext.PK_LOOT, "Unknown source", "PKing", true,
            Collections.singletonList(new ItemFlow(3, "Claim", 1L, 20, 20L)),
            ClassificationConfidence.CONFIRMED, "Unresolved claim", "unknown-encounter");
        unresolved.addLootKeyProvenance(claimedKey("unknown-encounter"));
        session.addTransaction(unresolved, 20);

        RunHistorySnapshot history = session.runHistorySnapshot(500L);
        assertEquals(1, history.getUnassignedReceiptCount());
        assertEquals(20L, history.getUnassignedReceiptValueGp());
        assertEquals("AVAILABLE", history.getUnassignedReceiptStatus());
        assertNull(unresolved.getRunId());
        assertTrue(unresolved.isRunAssignmentPending());
    }

    @Test
    public void delayedClaimInsertedBeforeItsEncounterIsResolvedByLaterProvenance()
    {
        ProfitSession session = new ProfitSession("Wilderness", 0L, SessionMode.PK);
        LootKeyProvenance provenance = claimedKey("late-encounter");
        ProfitTransaction delayed = new ProfitTransaction(100L, 100L,
            TransactionType.PK_LOOT, TrackingContext.PK_LOOT, "Claim", "PKing", true,
            Collections.singletonList(new ItemFlow(2, "Manifest item", 1L, 50, 50L)),
            ClassificationConfidence.CONFIRMED, "Claim", "late-encounter");
        delayed.addLootKeyProvenance(provenance);
        session.addTransaction(delayed, 20);
        assertTrue(delayed.isRunAssignmentPending());

        ProfitTransaction source = new ProfitTransaction(200L, 200L,
            TransactionType.PK_LOOT, TrackingContext.PK_LOOT, "Source", "PKing", false,
            Collections.singletonList(new ItemFlow(1, "Loot key", 1L, 0, 0L)),
            ClassificationConfidence.CONFIRMED, "Source", "late-encounter");
        session.addTransaction(source, 20);

        assertEquals(source.getRunId(), delayed.getRunId());
        assertFalse(delayed.isRunAssignmentPending());
        assertEquals(0, session.runHistorySnapshot(300L).getUnassignedReceiptCount());
    }

    @Test
    public void legacyCompactionMigratesToOneWholeSessionRunAndOldFilteredDetailIsUnavailable()
    {
        ProfitSession original = new ProfitSession("Legacy", 0L);
        original.addTransaction(receipt(100L, 100L, 1, "First", 100L), 1);
        original.addTransaction(receipt(200L, 200L, 2, "Second", 200L), 1);
        Gson gson = new Gson();
        JsonObject oldSave = new JsonParser().parse(gson.toJson(original)).getAsJsonObject();
        oldSave.remove("runs");
        oldSave.remove("currentRunId");
        oldSave.remove("runRetainedAggregates");
        oldSave.remove("retainedItemContributions");
        oldSave.remove("retainedItemContributionsVersion");
        oldSave.remove("retainedItemContributionsComplete");
        JsonArray transactions = oldSave.getAsJsonArray("transactions");
        for (int index = 0; index < transactions.size(); index++)
        {
            JsonObject transaction = transactions.get(index).getAsJsonObject();
            transaction.remove("runId");
            transaction.remove("runAssignmentPending");
        }
        ProfitSession legacy = gson.fromJson(oldSave, ProfitSession.class);

        RunHistorySnapshot history = legacy.runHistorySnapshot(1_000L);
        assertEquals(1, history.getStatements().size());
        RunStatementSnapshot statement = history.getStatements().get(0);
        assertTrue(statement.isLegacy());
        assertEquals("LEGACY_UNSPLIT", statement.getStatus());
        assertEquals(300L, statement.getLootGp());
        assertEquals(2, statement.getReceiptCount());
        assertEquals(300L, legacy.metrics(1_000L, 60_000L).getNet());
        RunHistorySnapshot filtered = legacy.runHistorySnapshot(1_000L, (tx, flow) -> true);
        assertFalse(filtered.getStatements().get(0).isAccountingAvailable());
        assertEquals("UNAVAILABLE_COMPACTED_RUN_FLOWS",
            filtered.getStatements().get(0).getAccountingStatus());
    }

    @Test
    public void legacyUndoRestoreKeepsReceiptInWholeSessionRun()
    {
        ProfitSession original = new ProfitSession("Legacy undo", 0L);
        original.addTransaction(receipt(100L, 100L, 1, "First", 100L), 10);
        original.addTransaction(receipt(200L, 200L, 2, "Removed", 200L), 10);
        original.undoLastTransaction(300L);

        Gson gson = new Gson();
        JsonObject oldSave = new JsonParser().parse(gson.toJson(original)).getAsJsonObject();
        oldSave.remove("runs");
        oldSave.remove("currentRunId");
        oldSave.remove("runRetainedAggregates");
        for (String key : new String[] {"transactions", "undoHistory"})
        {
            JsonArray records = oldSave.getAsJsonArray(key);
            for (int index = 0; index < records.size(); index++)
            {
                JsonObject record = records.get(index).getAsJsonObject();
                JsonObject transaction = key.equals("transactions")
                    ? record : record.getAsJsonObject("transaction");
                if (transaction != null)
                {
                    transaction.remove("runId");
                    transaction.remove("runAssignmentPending");
                }
            }
        }
        ProfitSession legacy = gson.fromJson(oldSave, ProfitSession.class);
        ProfitTransaction restored = legacy.restoreLastUndo(400L);
        assertNotNull(restored);

        RunHistorySnapshot history = legacy.runHistorySnapshot(500L);
        assertEquals(1, history.getStatements().size());
        RunStatementSnapshot statement = history.getStatements().get(0);
        assertTrue(statement.isLegacy());
        assertEquals(300L, statement.getNetGp());
        assertEquals(2, statement.getReceiptCount());
        assertEquals(statement.getRunId(), restored.getRunId());
    }

    @Test
    public void receiptsAfterRunStopAndSessionStopResumeBelongToFreshRuns()
    {
        ProfitSession session = new ProfitSession("Boundaries", 0L);
        ProfitTransaction first = receipt(100L, 100L, 1, "First", 10L);
        session.addTransaction(first, 10);
        assertTrue(session.stopRun(200L));

        ProfitTransaction afterRunStop = receipt(300L, 300L, 2, "Second", 20L);
        session.addTransaction(afterRunStop, 10);
        assertNotNull(afterRunStop.getRunId());
        assertFalse(first.getRunId().equals(afterRunStop.getRunId()));

        session.stop(400L);
        session.resume(800L);
        ProfitTransaction afterResume = receipt(900L, 500L, 3, "Third", 30L);
        session.addTransaction(afterResume, 10);
        assertNotNull(afterResume.getRunId());
        assertFalse(afterRunStop.getRunId().equals(afterResume.getRunId()));
        assertFalse(session.isPaused());

        RunHistorySnapshot history = session.runHistorySnapshot(1_000L);
        assertEquals(3, history.getStatements().size());
        assertEquals(10L, history.getStatements().get(0).getNetGp());
        assertEquals(20L, history.getStatements().get(1).getNetGp());
        assertEquals(30L, history.getStatements().get(2).getNetGp());
        assertEquals(0, history.getUnassignedReceiptCount());
    }

    @Test
    public void emptyStoppedRunIsReopenedOnResumeWithoutChangingItsIdentity()
    {
        ProfitSession session = new ProfitSession("Empty restart", 1_000L);
        Run original = session.getRuns(1_000L).get(0);
        session.stop(1_000L);
        assertTrue(original.isClosed());

        session.resume(5_000L);

        assertEquals(1, session.getRuns(5_000L).size());
        Run resumed = session.getRuns(5_000L).get(0);
        assertEquals(original.getId(), resumed.getId());
        assertEquals("Run 1", resumed.getName());
        assertFalse(resumed.isClosed());
        assertEquals("RESUME_EMPTY", resumed.getStartReason());
    }

    @Test
    public void zeroDurationRunWithAssignedReceiptIsNotReused()
    {
        ProfitSession session = new ProfitSession("Receipt at boundary", 1_000L);
        Run original = session.getRuns(1_000L).get(0);
        session.addTransaction(receipt(1_000L, 0L, 1, "Boundary", 10L), 10);
        session.stop(1_000L);

        session.resume(2_000L);

        assertEquals(2, session.getRuns(2_000L).size());
        assertTrue(session.getRuns(2_000L).get(0).isClosed());
        assertFalse(original.getId().equals(session.getRuns(2_000L).get(1).getId()));
    }

    @Test
    public void unavailableUnassignedCompactionDoesNotExposePartialFilteredValue()
    {
        ProfitSession session = new ProfitSession("Pending", 0L, SessionMode.PK);
        ProfitTransaction claim = new ProfitTransaction(100L, 100L,
            TransactionType.PK_LOOT, TrackingContext.PK_LOOT, "Claim", "PKing", true,
            Collections.singletonList(new ItemFlow(2, "Manifest item", 1L, 50, 50L)),
            ClassificationConfidence.CONFIRMED, "Claim", "missing-encounter");
        claim.addLootKeyProvenance(claimedKey("missing-encounter"));
        session.addTransaction(claim, 1);
        session.addTransaction(receipt(200L, 200L, 3, "Later", 10L), 1);

        Gson gson = new Gson();
        JsonObject persisted = new JsonParser().parse(gson.toJson(session)).getAsJsonObject();
        JsonObject unassigned = persisted.getAsJsonObject("runRetainedAggregates")
            .getAsJsonObject("UNASSIGNED");
        unassigned.addProperty("version", 0);
        ProfitSession loaded = gson.fromJson(persisted, ProfitSession.class);
        RunHistorySnapshot history = loaded.runHistorySnapshot(300L, (transaction, flow) -> true);

        assertEquals(1, history.getUnassignedReceiptCount());
        assertEquals(0L, history.getUnassignedReceiptValueGp());
        assertEquals("UNAVAILABLE_UNASSIGNED_COMPACTION", history.getUnassignedReceiptStatus());
    }

    @Test
    public void runRetentionSurvivesCompactionAndRecoveryPausePreservesBoundary()
    {
        ProfitSession session = new ProfitSession("Retained", 0L);
        Run first = session.getRuns(0L).get(0);
        session.addTransaction(receipt(100L, 100L, 1, "A", 70L), 1);
        session.startRun("Second", 200L);
        session.addTransaction(receipt(300L, 300L, 2, "B", 30L), 1);
        Gson gson = new Gson();
        ProfitSession restored = gson.fromJson(gson.toJson(session), ProfitSession.class);
        restored.pause(400L, PauseReason.RECOVERY);
        RunHistorySnapshot duringPause = restored.runHistorySnapshot(1_000L);
        assertEquals(2, duringPause.getStatements().size());
        assertEquals(70L, duringPause.getStatements().get(0).getNetGp());
        assertEquals(30L, duringPause.getStatements().get(1).getNetGp());
        assertEquals(first.getId(), duringPause.getStatements().get(0).getRunId());
        assertEquals(200L, duringPause.getStatements().get(1).getActiveDurationMillis());
    }

    @Test
    public void runOwnershipRoundTripsInsideThePerProfileSavedState()
    {
        ProfitSession session = new ProfitSession("Persisted", 0L);
        ProfitTransaction receipt = receipt(100L, 100L, 1, "Loot", 42L);
        session.addTransaction(receipt, 20);
        String runId = receipt.getRunId();
        session.startRun("Next", 200L);
        Gson gson = new Gson();
        SavedState state = new SavedState(session, null, false, Collections.emptyList());

        SavedState restored = gson.fromJson(gson.toJson(state), SavedState.class);
        ProfitSession restoredSession = restored.getGeneralSession();
        RunHistorySnapshot history = restoredSession.runHistorySnapshot(300L);

        assertEquals(SavedState.CURRENT_SCHEMA_VERSION, restored.getSchemaVersion());
        assertEquals(2, history.getStatements().size());
        assertEquals(runId, history.getStatements().get(0).getRunId());
        assertEquals(42L, history.getStatements().get(0).getNetGp());
        assertEquals(receipt.getRunId(), restoredSession.getTransactions().get(0).getRunId());
    }

    private static ProfitTransaction receipt(long timestamp, long activeElapsed, int itemId,
        String name, long value)
    {
        long quantity = value < 0L ? -1L : 1L;
        return new ProfitTransaction(timestamp, activeElapsed,
            value < 0L ? TransactionType.CONSUMPTION : TransactionType.LOOT,
            value < 0L ? TrackingContext.GENERIC : TrackingContext.LOOT,
            name, name, true,
            Collections.singletonList(new ItemFlow(itemId, name, quantity, (int) Math.abs(value), value)),
            ClassificationConfidence.CONFIRMED, "Run receipt", null);
    }

    private static LootKeyProvenance claimedKey(String encounterId)
    {
        LootKeyProvenance pending = new LootKeyProvenance(1, 1L, encounterId, "Victim", 10L, 20L);
        pending.captureManifest(Collections.singletonMap(2, 1L), Collections.singletonMap(2, 10L),
            10L, true, 30L);
        LootKeyProvenance claimed = pending.claim(Collections.singletonMap(2, 1L), 40L, 1L);
        assertNotNull(claimed);
        return claimed;
    }
}
