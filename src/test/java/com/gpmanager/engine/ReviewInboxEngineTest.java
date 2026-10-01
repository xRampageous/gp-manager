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
        Am engine = engine();
        long now = 100_000L;
        engine.rm(now - 10_000L);
        Ac first = uncertain("Mixed inventory change", now - 5_000L, 100L);
        Ac second = uncertain("Mixed inventory change", now - 2_000L, 200L);
        Ac otherReason = uncertain("Unknown source", now - 1_000L, 300L);
        engine.getActiveSession().kf(first, 20);
        engine.getActiveSession().kf(second, 20);
        engine.getActiveSession().kf(otherReason, 20);
        assertTrue(engine.qi(second.getId(), Ah.COST,
            now - 500L, "Owner already decided"));

        List<Cu> rows = engine.getReviewRows(now);
        assertEquals(2, rows.size());
        assertEquals(5_000L, Math.max(rows.get(0).ageMillis, rows.get(1).ageMillis));
        assertEquals("Mixed inventory change", rows.get(1).why);
        Cu row = rows.get(0);
        assertEquals(otherReason.getId(), row.transactionId);
        assertEquals(engine.getActiveSession().getId(), row.sessionId);
        assertEquals(300L, row.value);
        assertEquals("Unknown source", row.why);
        assertEquals(1, row.items.size());
        assertTrue(row.validDecisions.contains(Cl.IGNORE));
        assertFalse(rows.stream().anyMatch(entry -> second.getId().equals(entry.transactionId)));
    }

    @Test
    public void decideAllCreatesOnePersistedUndoUnitAndSkipsHandCorrectedRows()
    {
        Am engine = engine();
        long now = 200_000L;
        engine.rm(now - 10_000L);
        Ac first = uncertain("Review", now - 3_000L, 100L);
        Ac second = uncertain("Review", now - 2_000L, 200L);
        Ac handCorrected = uncertain("Review", now - 1_000L, 50L);
        engine.getActiveSession().kf(first, 20);
        engine.getActiveSession().kf(second, 20);
        engine.getActiveSession().kf(handCorrected, 20);
        assertTrue(engine.qi(handCorrected.getId(), Ah.COST,
            now - 500L, "Single decision"));

        assertEquals(2, engine.kp(engine.adu(Cl.GAIN, row -> true, now), now));
        assertEquals(2, latestDecision(engine).getChanges().size());
        assertEquals(250L, engine.getMetrics(now).net);
        assertEquals(Ah.COST, handCorrected.getCorrection());
        assertEquals(0, engine.getReviewRows(now).size());

        Gson gson = new Gson();
        Ad restored = gson.fromJson(gson.toJson(engine.getActiveSession()), Ad.class);
        assertEquals(250L, restored.metrics(now).net);
        assertTrue(restored.akb(now + 1L));
        assertEquals(Ah.AUTO, find(restored, first.getId()).getCorrection());
        assertEquals(Ah.AUTO, find(restored, second.getId()).getCorrection());
        assertEquals(Ah.COST, find(restored, handCorrected.getId()).getCorrection());
        assertEquals(-50L, restored.metrics(now + 1L).net);
        assertTrue(restored.correctionHistory.get(restored.correctionHistory.size() - 1).isUndone());
        assertTrue(engine.akb(now + 1L));
        assertTrue(latestDecision(engine).isUndone());
    }

    @Test
    public void decideAllPreviewIsExactAndRevisionBound()
    {
        Am engine = engine();
        long now = 300_000L;
        engine.rm(now - 10_000L);
        Ac first = uncertain("Review", now - 3_000L, 100L);
        Ac second = uncertain("Review", now - 2_000L, 200L);
        Ac handCorrected = uncertain("Review", now - 1_000L, 50L);
        engine.getActiveSession().kf(first, 20);
        engine.getActiveSession().kf(second, 20);
        engine.getActiveSession().kf(handCorrected, 20);
        assertTrue(engine.qi(handCorrected.getId(), Ah.COST, now - 500L, "Single"));

        Am.DecideAllPreview gain = engine.adu(Cl.GAIN, row -> true, now);
        assertEquals("exact affected rows from canonical ids", 2, gain.rowCount());
        assertEquals(Arrays.asList(first.getId(), second.getId()), gain.transactionIds);
        assertEquals("exact prospective personal-Net delta", 300L, gain.netDelta);
        assertEquals(-300L, engine.adu(Cl.COST, row -> true, now).netDelta);
        assertEquals(0L, engine.adu(Cl.IGNORE, row -> true, now).netDelta);
        assertEquals(0, engine.adu(Cl.GAIN, row -> false, now).rowCount());

        // A canonical mutation after the preview moves the revision: the stale preview never applies.
        long before = engine.getRevision();
        engine.getActiveSession().kf(uncertain("Review", now - 100L, 5L), 20);
        assertTrue(engine.getRevision() != before);
        assertEquals(-1, engine.kp(gain, now));
        assertEquals(Ah.AUTO, first.getCorrection());
        assertEquals(-50L, engine.getMetrics(now).net);

        Am.DecideAllPreview fresh = engine.adu(Cl.GAIN, row -> true, now);
        assertEquals(3, fresh.rowCount());
        assertEquals(305L, fresh.netDelta);
        assertEquals(3, engine.kp(fresh, now));
        assertEquals("applied delta equals the previewed delta", -50L + 305L, engine.getMetrics(now).net);
        assertEquals("one undo unit for the whole batch", 3, latestDecision(engine).getChanges().size());
        assertTrue(engine.akb(now + 1L));
        assertEquals(-50L, engine.getMetrics(now + 1L).net);
    }

    private static Ac find(Ad session, String id)
    {
        for (Ac transaction : session.getTransactions())
        {
            if (id.equals(transaction.getId())) return transaction;
        }
        throw new AssertionError("Missing transaction " + id);
    }

    private static Ac uncertain(String why, long at, long value)
    {
        return new Ac(at, null, Ai.UNCERTAIN, Aj.GENERIC,
            "Uncertain item change", "Testing", false,
            Arrays.asList(new Ab((int) value, "Item " + value, 1L, (int) value, value)),
            Bd.UNCERTAIN, why, null);
    }

    private static Am engine()
    {
        return new Am(deltas -> java.util.Collections.<Ab>emptyList(),
            new TransactionClassifier(), new GpManagerConfig() { });
    }

    private static Dx latestDecision(Am engine)
    {
        java.util.List<Dx> log = engine.getActiveSession().correctionHistory;
        return log.get(log.size() - 1);
    }
}
