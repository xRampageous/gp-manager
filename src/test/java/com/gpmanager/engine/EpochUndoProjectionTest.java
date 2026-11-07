package com.gpmanager;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class EpochUndoProjectionTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final int ITEM = 1203;
    private static final long UNIT = 1_000_000_000_000_000_000L;

    private static Engine engine() {
        JsonCodec.bind(new com.google.gson.Gson());
        var engine = new Engine(changes -> {
            var flows = new ArrayList<Flow>();
            changes.forEach((id, qty) -> flows.add(new Flow(id, "Iron dagger", qty, 100, qty * 100,
                    PriceSource.GRAND_EXCHANGE)));
            return flows;
        }, new TransactionClassifier(), ReviewFixesTest.CONFIG);
        engine.startCustom("Batch capacity audit", NOW - 2000);
        return engine;
    }

    private static Transaction row(Engine engine, long at, long qty, long value, boolean counted) {
        var tx = new Transaction(at, counted ? TransactionType.GAIN : TransactionType.UNCERTAIN,
                Context.GENERIC, "", "Audit", counted,
                List.of(new Flow(ITEM, "Iron dagger", qty, Math.abs(value / qty), value, PriceSource.MANUAL_OVERRIDE)),
                counted ? Confidence.CONFIRMED : Confidence.UNCERTAIN, "Audit", null);
        engine.session().addTransaction(tx, 2000, false);
        return tx;
    }

    private static void mixedUndo(long scale, boolean afterReload) {
        var engine = engine();
        row(engine, NOW + 1, 1, scale, true);
        var cost = row(engine, NOW + 10, 1, 4 * scale, true);
        assertTrue(engine.correctTransaction(cost.id(), Correction.COST, "A prior owner choice"));
        var gain = row(engine, NOW + 20, 1, 4 * scale, false);
        assertTrue(engine.correctTransaction(gain.id(), Correction.REVENUE, "Another prior owner choice"));
        assertTrue(engine.correctEntries(List.of(gain.id(), cost.id()), Correction.AUTO));
        row(engine, NOW + 30, 1, scale, true);
        assertEquals(6 * scale, engine.basis.known(ITEM).availableBasisGp);
        if (afterReload) {
            var saved = SaveCopies.copy(engine.savedState());
            engine = engine();
            engine.restore(saved, NOW + 100);
        }
        long beforeNet = engine.metrics(NOW + 200).net;
        int history = engine.session().correctionHistory.size();
        var gson = new com.google.gson.Gson();
        String beforeBasis = gson.toJson(engine.basis.stock);
        String beforeRows = gson.toJson(engine.session().transactions());
        String beforeHistory = gson.toJson(engine.session().correctionHistory);
        long revision = engine.revision();
        boolean undone = engine.undoCorrection();
        System.out.println("mixed undo scale=" + scale + " reload=" + afterReload + " applied=" + undone
                + " basis=" + engine.basis.known(ITEM).availableBasisGp + " net=" + engine.metrics(NOW + 200).net);
        if (undone) {
            assertEquals(100, scale);
            assertEquals("the two restored contributions preserve their exact combined basis", 6 * scale,
                    engine.basis.known(ITEM).availableBasisGp);
        } else {
            assertEquals(UNIT, scale);
            assertEquals(beforeNet, engine.metrics(NOW + 200).net);
            assertEquals(Correction.AUTO, engine.session().findTransaction(cost.id()).correction());
            assertEquals(Correction.AUTO, engine.session().findTransaction(gain.id()).correction());
            assertFalse(engine.session().latestCorrection().undone);
            assertEquals(beforeBasis, gson.toJson(engine.basis.stock));
            assertEquals(beforeRows, gson.toJson(engine.session().transactions()));
            assertEquals(beforeHistory, gson.toJson(engine.session().correctionHistory));
            assertEquals(revision, engine.revision());
        }
        assertEquals(history, engine.session().correctionHistory.size());
    }

    @Test public void aGroupedUndoMustNotSubtractAfterAnEarlierRestoreSaturatesThePool() {
        mixedUndo(UNIT, false);
    }

    @Test public void groupedUndoMustRemainSafeAfterReload() {
        mixedUndo(UNIT, true);
    }

    @Test public void ordinaryMixedUndoControlRestoresBothPriorChoices() {
        mixedUndo(100, false);
    }

    private static Engine migrationReview() {
        var engine = engine();
        row(engine, NOW - 1000, -1, -100, false);
        var state = SaveCopies.copy(engine.savedState());
        state.setTrackedBasis(null);
        var restored = engine();
        restored.restore(state, NOW);
        assertEquals(NOW, restored.basis.epoch);
        row(restored, NOW + 1, 1, 100, true);
        return restored;
    }

    private static void oldReview(boolean batch) {
        for (var choice : List.of(Correction.COST, Correction.REVENUE)) {
            var engine = migrationReview();
            var review = engine.session().transactions().stream().filter(ReviewRules::needsReview).findFirst().get();
            assertTrue(engine.session().canCorrect(review, choice));
            boolean applied = batch ? engine.correctEntries(List.of(review.id()), choice)
                    : engine.correctTransaction(review.id(), choice, "Old owner decision");
            assertTrue(applied);
            assertEquals(choice == Correction.COST ? 0 : 200, engine.metrics(NOW + 20).net);
            assertEquals("pre-epoch ownership is unknown, not a sink for newly acquired stock", 1,
                    engine.basis.known(ITEM).availableQty);
            assertEquals(100, engine.basis.known(ITEM).availableBasisGp);
            assertTrue(engine.undoCorrection());
            assertEquals(Correction.AUTO, review.correction());
            assertEquals(100, engine.metrics(NOW + 20).net);
            assertEquals(1, engine.basis.known(ITEM).availableQty);
            assertEquals(100, engine.basis.known(ITEM).availableBasisGp);
        }
    }

    @Test public void aPreEpochReviewMustNotConsumePostEpochAcquisitions() {
        oldReview(false);
    }

    @Test public void aPreEpochBatchMustNotConsumePostEpochAcquisitions() {
        oldReview(true);
    }

    @Test public void merelyPreviewingAnOldDecisionLeavesNewCoverageUnchanged() {
        var engine = migrationReview();
        long revision = engine.revision();
        var preview = engine.previewDecision(Decision.COST, row -> true, NOW + 20);
        assertEquals(1, preview.rowCount());
        assertEquals(1, engine.basis.known(ITEM).availableQty);
        assertEquals(100, engine.basis.known(ITEM).availableBasisGp);
        assertEquals(revision, engine.revision());
    }

    @Test public void preEpochUsedUpCannotConsumeNewCoverage() {
        var engine = migrationReview();
        var old = new Transaction(NOW - 1, TransactionType.CONSUMPTION, Context.GENERIC, "", "Old drop", true,
                List.of(new Flow(ITEM, "Iron dagger", -1, 100, -100, PriceSource.GRAND_EXCHANGE)),
                Confidence.CONFIRMED, "", null);
        engine.basis.onUsedUp(old);
        assertEquals(1, engine.basis.known(ITEM).availableQty);
        assertEquals(100, engine.basis.known(ITEM).availableBasisGp);
    }

    @Test public void preEpochPositiveCorrectionsRetainTheirExistingRefusal() {
        var engine = engine();
        var old = row(engine, NOW - 1000, 1, 100, false);
        var saved = SaveCopies.copy(engine.savedState());
        saved.setTrackedBasis(null);
        var restored = engine();
        restored.restore(saved, NOW);
        row(restored, NOW + 1, 1, 100, true);
        assertFalse(restored.correctTransaction(old.id(), Correction.REVENUE, "Cannot backfill old ownership"));
        assertEquals(1, restored.basis.known(ITEM).availableQty);
        assertEquals(100, restored.basis.known(ITEM).availableBasisGp);
    }

    @Test public void postEpochReviewStillConsumesItsEligibleCoverage() {
        var engine = migrationReview();
        var current = row(engine, NOW + 2, -1, -100, false);
        assertTrue(engine.correctTransaction(current.id(), Correction.COST, "Current decision"));
        assertEquals(0, engine.basis.known(ITEM).availableQty);
        assertEquals(0, engine.basis.known(ITEM).availableBasisGp);
    }

    private static Engine groupedSplits() {
        var engine = engine();
        var first = row(engine, NOW + 1, 2, 200, true);
        var second = Tx.of(NOW + 2, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(882, "Bronze arrow", 2, 200, 400, PriceSource.MANUAL_OVERRIDE)));
        engine.session().addTransaction(second, 2000, false);
        assertTrue(engine.session().splitItem(first.id(), ITEM, 1, "First split"));
        assertTrue(engine.session().splitItem(second.id(), 882, 1, "Second split"));
        var changes = new ArrayList<CorrectionRecord.Change>();
        for (var record : engine.session().correctionHistory) changes.addAll(record.changes());
        engine.session().correctionHistory.clear();
        engine.session().correctionHistory.add(new CorrectionRecord("Grouped saved split", changes));
        return engine;
    }

    @Test public void groupedUndoRestoresBothFlowSnapshotsAtOriginalPrices() {
        var engine = groupedSplits();
        assertTrue(engine.undoCorrection());
        assertEquals(2, engine.basis.known(ITEM).availableQty);
        assertEquals(200, engine.basis.known(ITEM).availableBasisGp);
        assertEquals(2, engine.basis.known(882).availableQty);
        assertEquals(400, engine.basis.known(882).availableBasisGp);
        assertEquals(600, engine.metrics(NOW + 20).net);
        assertTrue(engine.session().correctionHistory.get(0).undone);
    }

    @Test public void aBlockedFlowSnapshotUndoDoesNotPartiallyRestoreTheOtherItem() {
        var engine = groupedSplits();
        engine.basis.fenceRealized(ITEM, NOW + 30);
        var gson = new com.google.gson.Gson();
        String before = gson.toJson(engine.basis.stock);
        String rows = gson.toJson(engine.session().transactions());
        String history = gson.toJson(engine.session().correctionHistory);
        assertFalse(engine.undoCorrection());
        assertEquals(before, gson.toJson(engine.basis.stock));
        assertEquals(rows, gson.toJson(engine.session().transactions()));
        assertEquals(history, gson.toJson(engine.session().correctionHistory));
    }

    @Test public void groupedFlowSnapshotsStillRestoreAfterReload() {
        var engine = groupedSplits();
        var restored = engine();
        restored.restore(SaveCopies.copy(engine.savedState()), NOW + 100);
        assertTrue(restored.undoCorrection());
        assertEquals(600, restored.metrics(NOW + 200).net);
        assertEquals(200, restored.basis.known(ITEM).availableBasisGp);
        assertEquals(400, restored.basis.known(882).availableBasisGp);
    }

    @Test public void ambiguousRepeatedUndoTargetsRefuseBeforeMutation() {
        var engine = groupedSplits();
        var record = engine.session().latestCorrection();
        record.changes.add(record.changes.get(0));
        var gson = new com.google.gson.Gson();
        String before = gson.toJson(engine.basis.stock);
        String rows = gson.toJson(engine.session().transactions());
        assertFalse(engine.undoCorrection());
        assertFalse(record.undone);
        assertEquals(before, gson.toJson(engine.basis.stock));
        assertEquals(rows, gson.toJson(engine.session().transactions()));
    }
}
