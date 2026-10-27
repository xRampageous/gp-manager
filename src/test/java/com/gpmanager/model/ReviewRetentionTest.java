package com.gpmanager;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReviewRetentionTest {
    private static Transaction row(long at, boolean review) {
        var row = Tx.of(at, review ? TransactionType.UNCERTAIN : TransactionType.LOOT,
                review ? Context.GENERIC : Context.LOOT, "Retention", !review,
                Collections.singletonList(new Flow(536, "Dragon bones", 1, 100, 100)));
        row.confidence = review ? Confidence.UNCERTAIN : Confidence.CONFIRMED;
        return row;
    }

    @Test public void capKeepsUndecidedReviewAndCompactsOnlyCountedMoney() {
        var session = new Session("Review", 0, SessionMode.GENERAL);
        var review = row(1, true);
        session.addTransaction(review, 3, false);
        for (int index = 2; index <= 5; index++) session.addTransaction(row(index, false), 3, false);
        assertTrue(session.transactions().contains(review));
        assertEquals(3, session.transactions().size());
        assertEquals(2, session.compactedTransactionCount);
        assertEquals(400, session.metrics(10).net);
        assertTrue(ReviewRules.needsReview(review));
    }

    @Test public void onlyUndecidedReviewsMayExceedTheCap() {
        var session = new Session("Review overflow", 0, SessionMode.GENERAL);
        for (int index = 1; index <= 4; index++) session.addTransaction(row(index, true), 2, false);
        session.addTransaction(row(5, false), 2, false);
        assertEquals(4, session.transactions().size());
        assertTrue(session.transactions().stream().allMatch(ReviewRules::needsReview));
        assertEquals(100, session.metrics(10).net);
        assertTrue(session.correctTransaction(session.transactions().get(0).id(), Correction.REVENUE, "Count"));
        session.addTransaction(row(6, false), 2, false);
        assertTrue(session.transactions().stream().allMatch(ReviewRules::needsReview));
        assertEquals(300, session.metrics(10).net);
    }

    @Test public void retentionKeepsUndecidedReviewAndPreservesTotalsAcrossReload() {
        var gson = new com.google.gson.Gson();
        JsonCodec.bind(gson);
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        engine.ensureSession(1);
        var session = engine.session();
        var review = row(2, true);
        session.addTransaction(review, 2000, false);
        session.addTransaction(row(3, false), 2000, false);
        assertEquals(1, engine.compactOlderThan(30, 100L * 86_400_000));
        assertEquals(Collections.singletonList(review), session.transactions());
        var restored = gson.fromJson(gson.toJson(session), Session.class);
        assertTrue(ReviewRules.needsReview(restored.transactions().get(0)));
        assertEquals(100, restored.metrics(100L * 86_400_000).net);
        assertTrue(restored.correctTransaction(review.id(), Correction.REVENUE, "Owner decision"));
        assertEquals(200, restored.metrics(100L * 86_400_000).net);
    }

    @Test public void evictedCorrectionCannotBlockUndoOfARetainedRow() {
        var session = new Session("Undo", 0, SessionMode.GENERAL);
        var oldest = row(1, false);
        var retained = row(2, false);
        session.addTransaction(oldest, 2, false);
        session.addTransaction(retained, 2, false);
        assertTrue(session.correctTransaction(retained.id(), Correction.COST, "Earlier decision"));
        assertTrue(session.correctTransaction(oldest.id(), Correction.IGNORE, "Latest decision"));
        session.undoHistory.add(new UndoRecord(oldest));
        session.addTransaction(row(3, false), 2, false);
        assertFalse(session.transactions().contains(oldest));
        assertTrue(session.undoHistory.isEmpty());
        assertEquals(1, session.correctionHistory.size());
        assertEquals(0, session.metrics(10).net);
        assertTrue(session.undoCorrection());
        assertEquals(Correction.AUTO, retained.correction());
        assertEquals(200, session.metrics(10).net);
        assertFalse(session.undoCorrection());
    }
}
