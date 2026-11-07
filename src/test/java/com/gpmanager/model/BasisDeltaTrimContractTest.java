package com.gpmanager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class BasisDeltaTrimContractTest {
    @Test public void signedDeltasKeepOrderAbsentZeroPairsAndDetachedArrays() {
        var basis = new TrackedBasisLedger();
        var before = new LinkedHashMap<Integer, long[]>();
        before.put(1, new long[]{2, 200});
        before.put(2, new long[]{1, 0});
        before.put(3, new long[]{0, 0});
        var after = new LinkedHashMap<Integer, long[]>();
        after.put(1, new long[]{1, 100});
        after.put(2, new long[]{1, 0});
        after.put(4, new long[]{0, 0});
        after.put(5, new long[]{2, 0});
        var delta = basis.deltas(before, after);
        assertEquals(List.of(1, 4, 5), new ArrayList<>(delta.keySet()));
        assertArrayEquals(new long[]{-1, -100}, delta.get(1));
        assertArrayEquals(new long[]{0, 0}, delta.get(4));
        assertArrayEquals(new long[]{2, 0}, delta.get(5));
        delta.get(1)[0] = 99;
        delta.get(5)[0] = 99;
        assertEquals(2, before.get(1)[0]);
        assertEquals(1, after.get(1)[0]);
        assertEquals(2, after.get(5)[0]);
    }

    @Test public void deltaComponentsRetainSignedCapacityBoundaries() {
        var before = new LinkedHashMap<Integer, long[]>();
        var after = new LinkedHashMap<Integer, long[]>();
        before.put(1, new long[]{Long.MAX_VALUE, 0});
        before.put(2, new long[]{0, Long.MAX_VALUE});
        before.put(3, new long[]{Long.MAX_VALUE, Long.MAX_VALUE});
        after.put(1, new long[]{0, Long.MAX_VALUE});
        after.put(2, new long[]{Long.MAX_VALUE, 0});
        after.put(3, new long[]{Long.MAX_VALUE, Long.MAX_VALUE});
        var delta = new TrackedBasisLedger().deltas(before, after);
        assertEquals(List.of(1, 2), new ArrayList<>(delta.keySet()));
        assertArrayEquals(new long[]{-Long.MAX_VALUE, Long.MAX_VALUE}, delta.get(1));
        assertArrayEquals(new long[]{Long.MAX_VALUE, -Long.MAX_VALUE}, delta.get(2));
    }

    @Test public void nullTargetsKeepTheirOriginalExistingAndAbsentKeyBehavior() {
        var before = new LinkedHashMap<Integer, long[]>();
        var after = new LinkedHashMap<Integer, long[]>();
        before.put(1, new long[]{1, 100});
        after.put(1, null);
        assertArrayEquals(new long[]{-1, -100}, new TrackedBasisLedger().deltas(before, after).get(1));
        after.put(2, null);
        try {
            new TrackedBasisLedger().deltas(before, after);
            fail("an absent key with a null delta vector remains invalid");
        } catch (NullPointerException expected) {
            assertEquals(1, before.get(1)[0]);
        }
    }

    @Test public void aMissingNormalizedUndoTargetRefusesWithoutPartialChanges() {
        var engine = PreEpochReceiptRestoreTest.engine();
        long at = 1_700_000_000_001L;
        for (int id : new int[]{1203, 882})
            engine.session().addTransaction(Tx.of(at++, TransactionType.GAIN, Context.GENERIC,
                    "", true, List.of(new Flow(id, "Item", 1, 100, 100, PriceSource.MANUAL_OVERRIDE))), 2000, false);
        var rows = new ArrayList<>(engine.session().transactions());
        assertTrue(engine.correctEntries(List.of(rows.get(0).id(), rows.get(1).id()), Correction.COST));
        engine.session().transactions.remove(rows.get(0));
        var gson = new com.google.gson.Gson();
        String basis = gson.toJson(engine.basis.stock);
        String receipts = gson.toJson(engine.session().transactions());
        String history = gson.toJson(engine.session().correctionHistory);
        assertFalse(engine.undoCorrection());
        assertEquals(basis, gson.toJson(engine.basis.stock));
        assertEquals(receipts, gson.toJson(engine.session().transactions()));
        assertEquals(history, gson.toJson(engine.session().correctionHistory));
    }
}
