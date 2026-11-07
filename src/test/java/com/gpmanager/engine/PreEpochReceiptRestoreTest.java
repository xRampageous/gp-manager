package com.gpmanager;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreEpochReceiptRestoreTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final int ITEM = 1203;

    static Engine engine() {
        JsonCodec.bind(new com.google.gson.Gson());
        var result = new Engine(changes -> {
            var flows = new ArrayList<Flow>();
            changes.forEach((id, qty) -> flows.add(new Flow(id, "Iron dagger", qty, 100, qty * 100,
                    PriceSource.GRAND_EXCHANGE)));
            return flows;
        }, new TransactionClassifier(), ReviewFixesTest.CONFIG);
        result.startCustom("Epoch restore review", NOW - 2000);
        return result;
    }

    private static Transaction add(Engine engine, long timestamp, long quantity, long value, PriceSource source) {
        var row = Tx.of(timestamp, quantity > 0 ? TransactionType.GAIN : TransactionType.CONSUMPTION,
                Context.GENERIC, "", true,
                List.of(new Flow(ITEM, "Iron dagger", quantity, Math.abs(value), value, source)));
        engine.session().addTransaction(row, 2000, false);
        return row;
    }

    private static Engine legacy(long quantity, long value, PriceSource source) {
        var original = engine();
        add(original, NOW - 1000, quantity, value, source);
        var state = SaveCopies.copy(original.savedState());
        state.setTrackedBasis(null);
        var restored = engine();
        restored.restore(state, NOW);
        assertEquals(NOW, restored.basis.epoch);
        assertEquals(0, restored.basis.known(ITEM).availableQty);
        return restored;
    }

    private static void restoreOldGain(boolean reload) {
        var engine = legacy(1, 100, PriceSource.GRAND_EXCHANGE);
        String id = engine.session().transactions().get(0).id();
        assertEquals(100, engine.metrics(NOW + 1).net);
        assertNotNull(engine.undoTransaction());
        assertEquals(0, engine.metrics(NOW + 2).net);
        if (reload) {
            var saved = SaveCopies.copy(engine.savedState());
            engine = engine();
            engine.restore(saved, NOW + 100);
        }
        var restored = engine.restoreUndo();
        assertNotNull("restore rebooks a captured receipt, without backfilling old coverage", restored);
        assertEquals(id, restored.id());
        assertEquals(100, engine.metrics(NOW + 200).net);
        assertEquals(0, engine.basis.known(ITEM).availableQty);
        assertEquals(0, engine.basis.known(ITEM).availableBasisGp);
    }

    @Test public void anUndonePreEpochGainMustRemainRestorable() {
        restoreOldGain(false);
    }

    @Test public void aPreEpochGainRestoreMustWorkAfterReload() {
        restoreOldGain(true);
    }

    @Test public void oldKnownZeroReceiptHistoryMustRemainRestorableWithoutCoverage() {
        var engine = legacy(1, 0, PriceSource.NO_VALUE);
        assertNotNull(engine.undoTransaction());
        assertNotNull(engine.restoreUndo());
        assertEquals(1, engine.session().transactions().size());
        assertEquals(0, engine.metrics(NOW + 2).net);
        assertEquals(0, engine.basis.known(ITEM).availableQty);
    }

    @Test public void aPreEpochExpenseControlRestoresWithoutCoverage() {
        var engine = legacy(-1, -100, PriceSource.GRAND_EXCHANGE);
        assertNotNull(engine.undoTransaction());
        assertNotNull(engine.restoreUndo());
        assertEquals(-100, engine.metrics(NOW + 2).net);
        assertEquals(0, engine.basis.known(ITEM).availableQty);
    }

    @Test public void aCurrentGainControlRestoresItsOwnCoverage() {
        var engine = engine();
        add(engine, NOW, 1, 100, PriceSource.GRAND_EXCHANGE);
        assertNotNull(engine.undoTransaction());
        assertNotNull(engine.restoreUndo());
        assertEquals(100, engine.metrics(NOW + 2).net);
        assertEquals(1, engine.basis.known(ITEM).availableQty);
        assertEquals(100, engine.basis.known(ITEM).availableBasisGp);
    }

    @Test public void restoringOldReceiptLeavesNewReservedAndFencedStockUntouched() {
        var engine = legacy(1, 100, PriceSource.GRAND_EXCHANGE);
        assertNotNull(engine.undoTransaction());
        add(engine, NOW + 1, 5, 500, PriceSource.GRAND_EXCHANGE);
        engine.basis.reserve(ITEM, 4);
        engine.basis.fenceRealized(ITEM, NOW + 2);
        engine.basis.openReservationLookup = id -> NOW + 2;
        var gson = new com.google.gson.Gson();
        String before = gson.toJson(engine.basis.stock);
        var restored = engine.restoreUndo();
        assertNotNull(restored);
        assertEquals(100, restored.flows().get(0).unitPrice);
        assertEquals(PriceSource.GRAND_EXCHANGE, restored.flows().get(0).source());
        assertEquals(600, engine.metrics(NOW + 3).net);
        assertEquals(before, gson.toJson(engine.basis.stock));
    }

    @Test public void currentReceiptRestoreStillRefusesALaterRealizationFence() {
        var engine = engine();
        add(engine, NOW, 1, 100, PriceSource.GRAND_EXCHANGE);
        assertNotNull(engine.undoTransaction());
        engine.basis.fenceRealized(ITEM, NOW + 1);
        assertNull(engine.restoreUndo());
        assertEquals(0, engine.metrics(NOW + 2).net);
        assertEquals(0, engine.basis.known(ITEM).availableQty);
        assertFalse(engine.session().undoHistory.get(0).restored);
    }

    @Test public void currentReceiptRestoreStillRefusesAnOpenReservation() {
        var engine = engine();
        add(engine, NOW, 1, 100, PriceSource.GRAND_EXCHANGE);
        assertNotNull(engine.undoTransaction());
        engine.basis.openReservationLookup = id -> NOW + 1;
        assertNull(engine.restoreUndo());
        assertEquals(0, engine.metrics(NOW + 2).net);
        assertEquals(0, engine.basis.known(ITEM).availableQty);
    }

    @Test public void currentPhysicalSinkAndNullRestoresRemainRefused() {
        var engine = engine();
        var sink = add(engine, NOW, -1, -100, PriceSource.GRAND_EXCHANGE);
        assertFalse(engine.basis.canAdd(sink));
        assertFalse(engine.basis.canAdd(null));
    }
}
