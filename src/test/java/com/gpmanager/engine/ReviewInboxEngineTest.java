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
        engine.session().addTransaction(first, 20, false);
        engine.session().addTransaction(second, 20, false);
        engine.session().addTransaction(otherReason, 20, false);
        assertTrue(engine.correctTransaction(second.id(), Correction.COST, "Owner already decided"));

        List<ReviewRow> rows = engine.getReviewRows(now);
        assertEquals(2, rows.size());
        assertEquals(5_000L, Math.max(rows.get(0).ageMillis, rows.get(1).ageMillis));
        assertEquals("Mixed inventory change", rows.get(1).why);
        ReviewRow row = rows.get(0);
        assertEquals(otherReason.id(), row.transaction);
        assertEquals(engine.session().id(), row.session);
        assertEquals(300L, row.value);
        assertEquals("Unknown source", row.why);
        assertEquals(1, row.items.size());
        assertTrue(row.decisions.contains(Decision.IGNORE));
        assertFalse(rows.stream().anyMatch(entry -> second.id().equals(entry.transaction)));
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
        engine.session().addTransaction(first, 20, false);
        engine.session().addTransaction(second, 20, false);
        engine.session().addTransaction(handCorrected, 20, false);
        assertTrue(engine.correctTransaction(handCorrected.id(), Correction.COST, "Single decision"));

        assertEquals(2, engine.applyDecision(engine.previewDecision(Decision.GAIN, row -> true, now)));
        assertEquals(2, latestDecision(engine).changes().size());
        assertEquals(250L, engine.metrics(now).net);
        assertEquals(Correction.COST, handCorrected.correction());
        assertEquals(0, engine.getReviewRows(now).size());

        Gson gson = new Gson();
        Session restored = gson.fromJson(gson.toJson(engine.session()), Session.class);
        assertEquals(250L, restored.metrics(now).net);
        assertTrue(restored.undoCorrection());
        assertEquals(Correction.AUTO, find(restored, first.id()).correction());
        assertEquals(Correction.AUTO, find(restored, second.id()).correction());
        assertEquals(Correction.COST, find(restored, handCorrected.id()).correction());
        assertEquals(-50L, restored.metrics(now + 1L).net);
        assertTrue(restored.correctionHistory.get(restored.correctionHistory.size() - 1).undone);
        assertTrue(engine.undoCorrection());
        assertTrue(latestDecision(engine).undone);
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
        engine.session().addTransaction(first, 20, false);
        engine.session().addTransaction(second, 20, false);
        engine.session().addTransaction(handCorrected, 20, false);
        assertTrue(engine.correctTransaction(handCorrected.id(), Correction.COST, "Single"));

        Engine.DecisionPreview gain = engine.previewDecision(Decision.GAIN, row -> true, now);
        assertEquals("exact affected rows from canonical ids", 2, gain.rowCount());
        assertEquals(Arrays.asList(first.id(), second.id()), gain.transactionIds);
        assertEquals("exact prospective personal-Net delta", 300L, gain.netDelta);
        assertEquals(-300L, engine.previewDecision(Decision.COST, row -> true, now).netDelta);
        assertEquals(0L, engine.previewDecision(Decision.IGNORE, row -> true, now).netDelta);
        assertEquals(0, engine.previewDecision(Decision.GAIN, row -> false, now).rowCount());

        // A canonical mutation after the preview moves the revision: the stale preview never applies.
        long before = engine.revision();
        engine.session().addTransaction(uncertain("Review", now - 100L, 5L), 20, false);
        assertTrue(engine.revision() != before);
        assertEquals(-1, engine.applyDecision(gain));
        assertEquals(Correction.AUTO, first.correction());
        assertEquals(-50L, engine.metrics(now).net);

        Engine.DecisionPreview fresh = engine.previewDecision(Decision.GAIN, row -> true, now);
        assertEquals(3, fresh.rowCount());
        assertEquals(305L, fresh.netDelta);
        assertEquals(3, engine.applyDecision(fresh));
        assertEquals("applied delta equals the previewed delta", -50L + 305L, engine.metrics(now).net);
        assertEquals("one undo unit for the whole batch", 3, latestDecision(engine).changes().size());
        assertTrue(engine.undoCorrection());
        assertEquals(-50L, engine.metrics(now + 1L).net);
    }

    private static Transaction find(Session session, String id)
    {
        for (Transaction transaction : session.transactions())
        {
            if (id.equals(transaction.id())) return transaction;
        }
        throw new AssertionError("Missing transaction " + id);
    }

    private static Transaction uncertain(String why, long at, long value)
    {
        return new Transaction(at, TransactionType.UNCERTAIN, Context.GENERIC,
            "Uncertain item change", "Testing", false,
            Arrays.asList(new Flow((int) value, "Item " + value, 1L, (int) value, value)),
            Confidence.UNCERTAIN, why, null);
    }

    private static Engine engine()
    {
        return new Engine(deltas -> java.util.Collections.<Flow>emptyList(),
            new TransactionClassifier(), new GpManagerConfig() { });
    }

    private static CorrectionRecord latestDecision(Engine engine)
    {
        java.util.List<CorrectionRecord> log = engine.session().correctionHistory;
        return log.get(log.size() - 1);
    }
}
