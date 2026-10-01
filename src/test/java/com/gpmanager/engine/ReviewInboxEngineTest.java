package com.gpmanager;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReviewInboxEngineTest
{
    @Test
    public void inboxContainsOnlyAutomaticUncertainRowsAndGroupsReasons()
    {
        Engine engine = engine();
        long now = 100_000L;
        engine.ensureSession(now - 10_000L);
        Transaction first = uncertain("Mixed inventory change", now - 5_000L, 100L);
        Transaction second = uncertain("Mixed inventory change", now - 2_000L, 200L);
        Transaction otherReason = uncertain("Unknown source", now - 1_000L, 300L);
        engine.getActiveSession().addTransaction(first, 20);
        engine.getActiveSession().addTransaction(second, 20);
        engine.getActiveSession().addTransaction(otherReason, 20);
        assertTrue(engine.correctTransaction(second.getId(), Correction.COST,
            now - 500L, "Owner already decided"));

        List<ReviewRow> rows = engine.getReviewRows(now);
        assertEquals(2, rows.size());
        assertEquals(5_000L, Math.max(rows.get(0).ageMillis, rows.get(1).ageMillis));
        assertEquals("Mixed inventory change", rows.get(1).why);
        ReviewRow row = rows.get(0);
        assertEquals(otherReason.getId(), row.transactionId);
        assertEquals(engine.getActiveSession().getId(), row.sessionId);
        assertEquals(300L, row.value);
        assertEquals("Unknown source", row.why);
        assertEquals(1, row.items.size());
        assertTrue(row.validDecisions.contains(ReviewDecision.IGNORE));
        assertFalse(rows.stream().anyMatch(entry -> second.getId().equals(entry.transactionId)));
    }

    @Test
    public void decideAllCreatesOnePersistedUndoUnitAndSkipsHandCorrectedRows()
    {
        Engine engine = engine();
        long now = 200_000L;
        engine.ensureSession(now - 10_000L);
        Transaction first = uncertain("Review", now - 3_000L, 100L);
        Transaction second = uncertain("Review", now - 2_000L, 200L);
        Transaction handCorrected = uncertain("Review", now - 1_000L, 50L);
        engine.getActiveSession().addTransaction(first, 20);
        engine.getActiveSession().addTransaction(second, 20);
        engine.getActiveSession().addTransaction(handCorrected, 20);
        assertTrue(engine.correctTransaction(handCorrected.getId(), Correction.COST,
            now - 500L, "Single decision"));

        assertEquals(2, engine.applyDecideAll(engine.previewDecideAll(ReviewDecision.GAIN, row -> true, now), now));
        assertEquals(2, latestDecision(engine).getChanges().size());
        assertEquals(250L, engine.getMetrics(now).net);
        assertEquals(Correction.COST, handCorrected.getCorrection());
        assertEquals(0, engine.getReviewRows(now).size());

        Gson gson = new Gson();
        Session restored = gson.fromJson(gson.toJson(engine.getActiveSession()), Session.class);
        assertEquals(250L, restored.metrics(now).net);
        assertTrue(restored.undoLastCorrection(now + 1L));
        assertEquals(Correction.AUTO, find(restored, first.getId()).getCorrection());
        assertEquals(Correction.AUTO, find(restored, second.getId()).getCorrection());
        assertEquals(Correction.COST, find(restored, handCorrected.getId()).getCorrection());
        assertEquals(-50L, restored.metrics(now + 1L).net);
        assertTrue(restored.correctionHistory.get(restored.correctionHistory.size() - 1).isUndone());
        assertTrue(engine.undoLastCorrection(now + 1L));
        assertTrue(latestDecision(engine).isUndone());
    }

    @Test
    public void decideAllPreviewIsExactAndRevisionBound()
    {
        Engine engine = engine();
        long now = 300_000L;
        engine.ensureSession(now - 10_000L);
        Transaction first = uncertain("Review", now - 3_000L, 100L);
        Transaction second = uncertain("Review", now - 2_000L, 200L);
        Transaction handCorrected = uncertain("Review", now - 1_000L, 50L);
        engine.getActiveSession().addTransaction(first, 20);
        engine.getActiveSession().addTransaction(second, 20);
        engine.getActiveSession().addTransaction(handCorrected, 20);
        assertTrue(engine.correctTransaction(handCorrected.getId(), Correction.COST, now - 500L, "Single"));

        Engine.DecideAllPreview gain = engine.previewDecideAll(ReviewDecision.GAIN, row -> true, now);
        assertEquals("exact affected rows from canonical ids", 2, gain.rowCount());
        assertEquals(Arrays.asList(first.getId(), second.getId()), gain.transactionIds);
        assertEquals("exact prospective personal-Net delta", 300L, gain.netDelta);
        assertEquals(-300L, engine.previewDecideAll(ReviewDecision.COST, row -> true, now).netDelta);
        assertEquals(0L, engine.previewDecideAll(ReviewDecision.IGNORE, row -> true, now).netDelta);
        assertEquals(0, engine.previewDecideAll(ReviewDecision.GAIN, row -> false, now).rowCount());

        // A canonical mutation after the preview moves the revision: the stale preview never applies.
        long before = engine.getRevision();
        engine.getActiveSession().addTransaction(uncertain("Review", now - 100L, 5L), 20);
        assertTrue(engine.getRevision() != before);
        assertEquals(-1, engine.applyDecideAll(gain, now));
        assertEquals(Correction.AUTO, first.getCorrection());
        assertEquals(-50L, engine.getMetrics(now).net);

        Engine.DecideAllPreview fresh = engine.previewDecideAll(ReviewDecision.GAIN, row -> true, now);
        assertEquals(3, fresh.rowCount());
        assertEquals(305L, fresh.netDelta);
        assertEquals(3, engine.applyDecideAll(fresh, now));
        assertEquals("applied delta equals the previewed delta", -50L + 305L, engine.getMetrics(now).net);
        assertEquals("one undo unit for the whole batch", 3, latestDecision(engine).getChanges().size());
        assertTrue(engine.undoLastCorrection(now + 1L));
        assertEquals(-50L, engine.getMetrics(now + 1L).net);
    }

    private static Transaction find(Session session, String id)
    {
        for (Transaction transaction : session.getTransactions())
        {
            if (id.equals(transaction.getId())) return transaction;
        }
        throw new AssertionError("Missing transaction " + id);
    }

    private static Transaction uncertain(String why, long at, long value)
    {
        return new Transaction(at, null, TransactionType.UNCERTAIN, Context.GENERIC,
            "Uncertain item change", "Testing", false,
            Arrays.asList(new Flow((int) value, "Item " + value, 1L, (int) value, value)),
            ClassificationConfidence.UNCERTAIN, why, null);
    }

    private static Engine engine()
    {
        return new Engine(deltas -> java.util.Collections.<Flow>emptyList(),
            new TransactionClassifier(), new GpManagerConfig() { });
    }

    private static CorrectionRecord latestDecision(Engine engine)
    {
        java.util.List<CorrectionRecord> log = engine.getActiveSession().correctionHistory;
        return log.get(log.size() - 1);
    }
}
