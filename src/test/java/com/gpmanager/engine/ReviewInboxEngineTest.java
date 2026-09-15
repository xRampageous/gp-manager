package com.gpmanager.engine;

import com.google.gson.Gson;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.ReviewDecision;
import com.gpmanager.model.ReviewInbox;
import com.gpmanager.model.ReviewRow;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReviewInboxEngineTest
{
    @Test
    public void inboxContainsOnlyAutomaticUncertainRowsAndGroupsReasons()
    {
        GpManagerEngine engine = engine();
        long now = 100_000L;
        engine.ensureSession(now - 10_000L);
        ProfitTransaction first = uncertain("Mixed inventory change", now - 5_000L, 100L);
        ProfitTransaction second = uncertain("Mixed inventory change", now - 2_000L, 200L);
        ProfitTransaction otherReason = uncertain("Unknown source", now - 1_000L, 300L);
        engine.getActiveSession().addTransaction(first, 20);
        engine.getActiveSession().addTransaction(second, 20);
        engine.getActiveSession().addTransaction(otherReason, 20);
        assertTrue(engine.correctTransaction(second.getId(), TransactionCorrection.COST,
            now - 500L, "Owner already decided"));

        ReviewInbox inbox = engine.getReviewInbox(now);
        assertEquals(2, inbox.getPendingCount());
        assertEquals(5_000L, inbox.getOldestAgeMillis());
        assertEquals(Integer.valueOf(1), inbox.getCountsByReason().get("Mixed inventory change"));
        assertEquals(Integer.valueOf(1), inbox.getCountsByReason().get("Unknown source"));
        ReviewRow row = inbox.getRows().get(0);
        assertEquals(otherReason.getId(), row.getTransactionId());
        assertEquals(engine.getActiveSession().getId(), row.getSessionId());
        assertEquals(300L, row.getValue());
        assertEquals("Unknown source", row.getWhy());
        assertEquals(1, row.getItems().size());
        assertTrue(row.getValidDecisions().contains(ReviewDecision.IGNORE));
        assertFalse(inbox.getRows().stream().anyMatch(entry -> second.getId().equals(entry.getTransactionId())));
    }

    @Test
    public void decideAllCreatesOnePersistedUndoUnitAndSkipsHandCorrectedRows()
    {
        GpManagerEngine engine = engine();
        long now = 200_000L;
        engine.ensureSession(now - 10_000L);
        ProfitTransaction first = uncertain("Review", now - 3_000L, 100L);
        ProfitTransaction second = uncertain("Review", now - 2_000L, 200L);
        ProfitTransaction handCorrected = uncertain("Review", now - 1_000L, 50L);
        engine.getActiveSession().addTransaction(first, 20);
        engine.getActiveSession().addTransaction(second, 20);
        engine.getActiveSession().addTransaction(handCorrected, 20);
        assertTrue(engine.correctTransaction(handCorrected.getId(), TransactionCorrection.COST,
            now - 500L, "Single decision"));

        assertEquals(2, engine.decideAll(ReviewDecision.GAIN, row -> true, now));
        assertEquals(2, engine.getAppliedDecisions(10).get(0).getBatchSize());
        assertEquals(250L, engine.getMetrics(now).getNet());
        assertEquals(TransactionCorrection.COST, handCorrected.getCorrection());
        assertEquals(0, engine.getReviewInbox(now).getPendingCount());

        Gson gson = new Gson();
        ProfitSession restored = gson.fromJson(gson.toJson(engine.getActiveSession()), ProfitSession.class);
        assertEquals(250L, restored.metrics(now, 60_000L).getNet());
        assertTrue(restored.undoLastCorrection(now + 1L));
        assertEquals(TransactionCorrection.AUTO, find(restored, first.getId()).getCorrection());
        assertEquals(TransactionCorrection.AUTO, find(restored, second.getId()).getCorrection());
        assertEquals(TransactionCorrection.COST, find(restored, handCorrected.getId()).getCorrection());
        assertEquals(-50L, restored.metrics(now + 1L, 60_000L).getNet());
        assertTrue(restored.getCorrectionLog().get(restored.getCorrectionLog().size() - 1).isUndone());
        assertTrue(engine.undoLastCorrection(now + 1L));
        assertTrue(engine.getAppliedDecisions(10).get(0).isUndone());
    }

    private static ProfitTransaction find(ProfitSession session, String id)
    {
        for (ProfitTransaction transaction : session.getTransactions())
        {
            if (id.equals(transaction.getId())) return transaction;
        }
        throw new AssertionError("Missing transaction " + id);
    }

    private static ProfitTransaction uncertain(String why, long at, long value)
    {
        return new ProfitTransaction(at, null, TransactionType.UNCERTAIN, TrackingContext.GENERIC,
            "Uncertain item change", "Testing", false,
            Arrays.asList(new ItemFlow((int) value, "Item " + value, 1L, (int) value, value)),
            ClassificationConfidence.UNCERTAIN, why, null);
    }

    private static GpManagerEngine engine()
    {
        return new GpManagerEngine(deltas -> java.util.Collections.<ItemFlow>emptyList(),
            new TransactionClassifier(), new GpManagerConfig() { });
    }
}
