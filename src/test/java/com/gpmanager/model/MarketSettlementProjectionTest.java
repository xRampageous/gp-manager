package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Derived Market settlement projection contract: behavioural lifecycles, exact economics, pending
 * edge separated from realized result, and fail-closed unavailability. It books nothing.
 */
public class MarketSettlementProjectionTest
{
    private static Aa sell(long offered, long captured, long filled, long spent,
        long settled, long settledExecution, long settledCash, long realized)
    {
        Aa record = new Aa("offer-" + offered + "-" + filled, 0,
            Aa.Side.SELL, 561, "Nature rune", offered, 7L, 1_000L, "session-a");
        record.setCapturedQty(captured);
        record.setFilledQty(filled);
        record.setSpentGp(spent);
        record.setSettledQty(settled);
        record.setSettledExecutionGp(settledExecution);
        record.setSettledCashGp(settledCash);
        record.setRealizedResultGp(realized);
        record.setBasisUnitPrice(6L);
        record.setBasisSource(Av.GRAND_EXCHANGE.name());
        record.setBasisCapturedAtEpochMillis(1_000L);
        return record;
    }

    @Test
    public void pendingExecutionEdgeIsNeverRealized()
    {
        Aa record = sell(10, 10, 10, 70, 0, 0, 0, 0);
        record.setOfferState("SOLD");
        Bi.Row row = MarketFacts.row(record);

        assertEquals(Bi.Lifecycle.EXECUTED_UNSETTLED, row.lifecycle);
        assertEquals(0L, row.realizedResultGp);
        assertFalse(row.isRealizedIncluded());
    }

    @Test
    public void settlementAdjustmentAndRealizedResultAreExact()
    {
        Aa record = sell(10, 10, 10, 70, 10, 70, 69, 9);
        record.setOfferState("SOLD");
        Bi.Row row = MarketFacts.row(record);

        assertEquals(Bi.Lifecycle.REALIZED, row.lifecycle);
        assertEquals(60L, MarketFacts.basisValueGp(record));
        assertEquals(69L, row.observedSettlementGp);
        assertEquals(-1L, row.settlementAdjustmentGp);
        assertEquals(9L, row.realizedResultGp);
        assertTrue(row.isRealizedIncluded());
    }

    @Test
    public void partiallyRealizedCountsOnlySettledQuantity()
    {
        Aa record = sell(100, 100, 100, 700, 40, 280, 280, 40);
        record.setOfferState("SELLING");
        Bi.Row row = MarketFacts.row(record);

        assertEquals(Bi.Lifecycle.PARTIALLY_REALIZED, row.lifecycle);
        assertEquals(40L, row.settledQty);
        assertEquals(40L, row.realizedResultGp);
    }

    @Test
    public void cancellingBeforeAnyFillIsCancelledReturned()
    {
        Aa record = sell(100, 100, 0, 0, 0, 0, 0, 0);
        record.setOfferState("CANCELLED_SELL");
        record.setReturnedQty(100L);
        Bi.Row row = MarketFacts.row(record);

        assertEquals(Bi.Lifecycle.CANCELLED_RETURNED, row.lifecycle);
    }

    @Test
    public void ambiguousAndLegacyFailClosed()
    {
        Aa ambiguous = sell(10, 10, 10, 70, 0, 0, 0, 0);
        ambiguous.setQuantityModified(true);
        assertEquals(Bi.Lifecycle.AMBIGUOUS,
            Bi.yw(ambiguous));

        Aa legacy = sell(10, 0, 4, 28, 0, 0, 0, 0);
        legacy.setConfidence(Aa.Confidence.LEGACY_UNBASED);
        assertEquals(Bi.Lifecycle.UNAVAILABLE,
            Bi.yw(legacy));
        assertEquals("unknown basis is never presented as a value", -1L,
            MarketFacts.basisValueGp(legacy));
    }

    @Test
    public void resumedLifecycleIsExplicit()
    {
        Aa record = sell(10, 10, 0, 0, 0, 0, 0, 0);
        record.setConfidence(Aa.Confidence.RESUMED);
        record.setOfferState("SELLING");
        assertEquals(Bi.Lifecycle.RESUMED,
            Bi.yw(record));
    }

    @Test
    public void rowsAreReadOnlyAndEmptyInputsAreSafe()
    {
        assertTrue(Bi.rows(Collections.emptyList(), null, null).isEmpty());
        assertTrue(Bi.rows(null, null, null).isEmpty());
    }
}
