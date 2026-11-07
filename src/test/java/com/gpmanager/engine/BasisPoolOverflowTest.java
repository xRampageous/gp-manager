package com.gpmanager;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class BasisPoolOverflowTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final long UNIT = 1_000_000_000_000_000_000L;
    private static final int ITEM = 1203;

    private static void gain(TrackedBasisLedger basis, long at, long quantity, long value, PriceSource source) {
        basis.onAdded(Tx.of(at, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(ITEM, "Iron dagger", quantity, value / quantity, value, source))), false);
    }

    @Test public void acquisitionsSaturateTheirPooledValueWithoutTurningItIntoZero() {
        var basis = new TrackedBasisLedger();
        for (int index = 0; index < 3; index++)
            gain(basis, NOW + index, 1, 4 * UNIT, PriceSource.MANUAL_OVERRIDE);
        assertEquals(3, basis.known(ITEM).availableQty);
        assertEquals(Long.MAX_VALUE, basis.known(ITEM).availableBasisGp);
    }

    @Test public void cancellationReturnsSaturateBothPositiveFields() {
        var basis = new TrackedBasisLedger();
        gain(basis, NOW, 1, 4 * UNIT, PriceSource.MANUAL_OVERRIDE);
        gain(basis, NOW + 1, 1, 4 * UNIT, PriceSource.MANUAL_OVERRIDE);
        var reserved = basis.reserve(ITEM, 1);
        gain(basis, NOW + 2, 1, 4 * UNIT, PriceSource.MANUAL_OVERRIDE);
        basis.release(ITEM, reserved.trackedQty, reserved.basisGp);
        assertEquals(3, basis.known(ITEM).availableQty);
        assertEquals(Long.MAX_VALUE, basis.known(ITEM).availableBasisGp);
        basis.release(ITEM, Long.MAX_VALUE, 0);
        assertEquals(Long.MAX_VALUE, basis.known(ITEM).availableQty);
    }

    @Test public void positiveOverflowIsNotMisreadAsAnInsufficientCoverageDelta() {
        var basis = new TrackedBasisLedger();
        gain(basis, NOW, 2, 200, PriceSource.GRAND_EXCHANGE);
        assertTrue(basis.canApply(ITEM, Long.MAX_VALUE, Long.MAX_VALUE, NOW + 1));
        assertFalse(basis.canApply(ITEM, -3, 0, NOW + 1));
        assertFalse(basis.canApply(ITEM, 0, -201, NOW + 1));
        assertFalse(basis.canApply(ITEM, Long.MIN_VALUE, 0, NOW + 1));
        assertEquals(2, basis.known(ITEM).availableQty);
        assertEquals(200, basis.known(ITEM).availableBasisGp);
    }

    @Test public void aLargeRecoveredPricePublishesItsBoundedCoverage() {
        var engine = new Engine(changes -> {
            var flows = new ArrayList<Flow>();
            changes.forEach((id, quantity) -> flows.add(new Flow(id, "Iron dagger", quantity,
                    4 * UNIT, quantity * 4 * UNIT, PriceSource.MANUAL_OVERRIDE)));
            return flows;
        }, new TransactionClassifier(), ReviewFixesTest.CONFIG);
        engine.startCustom("Large price recovery", NOW);
        for (int index = 0; index < 3; index++)
            engine.session().addTransaction(Tx.of(NOW + index + 1, TransactionType.GAIN, Context.GENERIC, "", true,
                    List.of(new Flow(ITEM, "Iron dagger", 1, index < 2 ? 4 * UNIT : 0,
                            index < 2 ? 4 * UNIT : 0, index < 2 ? PriceSource.MANUAL_OVERRIDE : PriceSource.UNPRICED))),
                    2000, false);
        engine.recheck();
        assertEquals(PriceSource.MANUAL_OVERRIDE, engine.session().transactions().get(2).flows().get(0).source());
        assertEquals(3, engine.basis.known(ITEM).availableQty);
        assertEquals(Long.MAX_VALUE, engine.basis.known(ITEM).availableBasisGp);
    }

    @Test public void quantityGrowthUsesTheSameBoundedArithmetic() {
        var basis = new TrackedBasisLedger();
        gain(basis, NOW, Long.MAX_VALUE - 1, 2, PriceSource.MANUAL_OVERRIDE);
        gain(basis, NOW + 1, 3, 3, PriceSource.MANUAL_OVERRIDE);
        assertEquals(Long.MAX_VALUE, basis.known(ITEM).availableQty);
        assertEquals(5, basis.known(ITEM).availableBasisGp);
    }

    @Test public void wholeAndPartialReservationsKeepTheirBoundedRemainderAcrossReload() {
        var basis = new TrackedBasisLedger();
        for (int index = 0; index < 3; index++)
            gain(basis, NOW + index, 1, 4 * UNIT, PriceSource.MANUAL_OVERRIDE);
        var state = new SavedState();
        basis.writeTo(state);
        var restored = new TrackedBasisLedger();
        restored.restore(SaveCopies.copy(state), NOW + 10);
        var partial = restored.reserve(ITEM, 1);
        var remaining = restored.reserve(ITEM, 2);
        assertEquals(Long.MAX_VALUE, SafeMath.sum(partial.basisGp, remaining.basisGp));
        assertEquals(0, restored.known(ITEM).availableQty);
        assertEquals(0, restored.known(ITEM).availableBasisGp);
    }

    @Test public void knownZeroAndOrdinaryReturnsKeepTheirCoverageAndNegativeChecks() {
        var basis = new TrackedBasisLedger();
        gain(basis, NOW, 1, 0, PriceSource.NO_VALUE);
        var zero = basis.reserve(ITEM, 1);
        assertEquals(1, zero.trackedQty);
        assertEquals(0, zero.basisGp);
        basis.release(ITEM, zero.trackedQty, zero.basisGp);
        assertEquals(1, basis.known(ITEM).availableQty);
        assertFalse(basis.canApply(ITEM, 0, -1, NOW + 1));
        basis.fenceRealized(ITEM, NOW + 2);
        assertFalse(basis.canApply(ITEM, 1, 100, NOW + 1));
    }
}
