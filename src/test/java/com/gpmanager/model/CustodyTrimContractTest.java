package com.gpmanager;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class CustodyTrimContractTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final int ITEM = 1203;

    @Test public void aNullSinkOwnerStillCannotBorrowAnotherReviewsFence() {
        var basis = new TrackedBasisLedger();
        basis.epoch = NOW;
        basis.fenceRealized(ITEM, NOW + 1, "its-review");
        var gson = new com.google.gson.Gson();
        String before = gson.toJson(basis.stock);
        assertFalse(basis.canIntroduceSink(Map.of(), Map.of(ITEM, 1L), NOW + 1, null));
        assertTrue(basis.canIntroduceSink(Map.of(), Map.of(ITEM, 1L), NOW + 1, "its-review"));
        assertFalse(basis.canIntroduceSink(Map.of(ITEM, 1L), Map.of(ITEM, 2L), NOW + 1, "its-review"));
        assertFalse(basis.canIntroduceSink(Map.of(), Map.of(), NOW + 1, null));
        assertEquals(before, gson.toJson(basis.stock));
    }

    private static GeOffer record(GeOffer.Side side) {
        var row = new GeOffer("audit-offer", 0, side, ITEM, "Iron dagger", 2, 150, NOW, "origin");
        row.offerState = side == GeOffer.Side.SELL ? "SOLD" : "BOUGHT";
        row.filledQty = row.settledQty = row.collectedQty = row.capturedQty = 2;
        row.reserveGp = row.spentGp = 300;
        return row;
    }

    @Test public void sellRetirementRequiresNoPendingFillOrHeldItemsAndATerminalState() {
        var row = record(GeOffer.Side.SELL);
        assertTrue(row.reconciled());
        row.settledQty = 1;
        assertFalse(row.reconciled());
        row.settledQty = 2;
        row.capturedQty = 3;
        assertFalse(row.reconciled());
        row.capturedQty = 2;
        row.offerState = "SELLING";
        assertFalse(row.reconciled());
        row.cleared = true;
        assertTrue(row.reconciled());
        row.capturedQty = 3;
        assertFalse(row.reconciled());
    }

    @Test public void buyRetirementRequiresCollectedItemsAndReturnedChange() {
        var row = record(GeOffer.Side.BUY);
        assertTrue(row.reconciled());
        row.collectedQty = 1;
        assertFalse(row.reconciled());
        row.collectedQty = 2;
        row.reserveGp = 320;
        assertFalse(row.reconciled());
        row.refundedGp = 20;
        assertTrue(row.reconciled());
        row.offerState = null;
        assertFalse(row.reconciled());
        row.cleared = true;
        assertTrue(row.reconciled());
        row.side = null;
        assertTrue(row.reconciled());
        row.reserveGp = row.spentGp = Long.MAX_VALUE;
        row.refundedGp = 0;
        assertTrue(row.reconciled());
    }

    @Test public void numericMapFactoriesPreserveConcreteTypesOrderAndIndependentOwnership() {
        var engine = PreEpochReceiptRestoreTest.engine();
        assertEquals(HashMap.class, engine.stored.getClass());
        assertEquals(HashMap.class, engine.reward.getClass());
        assertEquals(HashMap.class, engine.bag.getClass());
        assertEquals(HashMap.class, engine.contents.getClass());
        assertEquals(HashMap.class, engine.death.items.getClass());
        assertEquals(HashMap.class, engine.raids.state.supplies.getClass());
        engine.stored.put(ITEM, 1L);
        assertTrue(engine.reward.isEmpty());
        assertTrue(engine.bag.isEmpty());
        assertTrue(engine.contents.isEmpty());
        var ordered = Phrases.orderedNumberMap();
        assertEquals(LinkedHashMap.class, ordered.getClass());
        ordered.put(2, 1L);
        ordered.put(1, 1L);
        assertEquals(List.of(2, 1), List.copyOf(ordered.keySet()));
        assertTrue(Phrases.orderedNumberMap().isEmpty());
    }
}
