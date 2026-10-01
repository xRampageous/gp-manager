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
    private static GeRecord sell(long offered, long captured, long filled, long spent,
        long settled, long settledExecution, long settledCash, long realized)
    {
        GeRecord record = new GeRecord("offer-" + offered + "-" + filled, 0,
            GeRecord.Side.SELL, 561, "Nature rune", offered, 7L, 1_000L, "session-a");
        record.setCapturedQty(captured);
        record.setFilledQty(filled);
        record.setSpentGp(spent);
        record.setSettledQty(settled);
        record.setSettledExecutionGp(settledExecution);
        record.setSettledCashGp(settledCash);
        record.setRealizedResultGp(realized);
        record.setBasisUnitPrice(6L);
        record.setBasisSource(PriceSource.GRAND_EXCHANGE.name());
        record.setBasisCapturedAtEpochMillis(1_000L);
        return record;
    }

    @Test
    public void pendingExecutionEdgeIsNeverRealized()
    {
        GeRecord record = sell(10, 10, 10, 70, 0, 0, 0, 0);
        record.setOfferState("SOLD");
        MarketSettlementProjection.Row row = MarketFacts.row(record);

        assertEquals(MarketSettlementProjection.Lifecycle.EXECUTED_UNSETTLED, row.lifecycle);
        assertEquals(0L, row.realizedResultGp);
        assertFalse(row.isRealizedIncluded());
    }

    @Test
    public void settlementAdjustmentAndRealizedResultAreExact()
    {
        GeRecord record = sell(10, 10, 10, 70, 10, 70, 69, 9);
        record.setOfferState("SOLD");
        MarketSettlementProjection.Row row = MarketFacts.row(record);

        assertEquals(MarketSettlementProjection.Lifecycle.REALIZED, row.lifecycle);
        assertEquals(60L, MarketFacts.basisValueGp(record));
        assertEquals(69L, row.observedSettlementGp);
        assertEquals(-1L, row.settlementAdjustmentGp);
        assertEquals(9L, row.realizedResultGp);
        assertTrue(row.isRealizedIncluded());
    }

    @Test
    public void partiallyRealizedCountsOnlySettledQuantity()
    {
        GeRecord record = sell(100, 100, 100, 700, 40, 280, 280, 40);
        record.setOfferState("SELLING");
        MarketSettlementProjection.Row row = MarketFacts.row(record);

        assertEquals(MarketSettlementProjection.Lifecycle.PARTIALLY_REALIZED, row.lifecycle);
        assertEquals(40L, row.settledQty);
        assertEquals(40L, row.realizedResultGp);
    }

    @Test
    public void cancellingBeforeAnyFillIsCancelledReturned()
    {
        GeRecord record = sell(100, 100, 0, 0, 0, 0, 0, 0);
        record.setOfferState("CANCELLED_SELL");
        record.setReturnedQty(100L);
        MarketSettlementProjection.Row row = MarketFacts.row(record);

        assertEquals(MarketSettlementProjection.Lifecycle.CANCELLED_RETURNED, row.lifecycle);
    }

    @Test
    public void ambiguousAndLegacyFailClosed()
    {
        GeRecord ambiguous = sell(10, 10, 10, 70, 0, 0, 0, 0);
        ambiguous.setQuantityModified(true);
        assertEquals(MarketSettlementProjection.Lifecycle.AMBIGUOUS,
            MarketSettlementProjection.lifecycleOf(ambiguous));

        GeRecord legacy = sell(10, 0, 4, 28, 0, 0, 0, 0);
        legacy.setConfidence(GeRecord.Confidence.LEGACY_UNBASED);
        assertEquals(MarketSettlementProjection.Lifecycle.UNAVAILABLE,
            MarketSettlementProjection.lifecycleOf(legacy));
        assertEquals("unknown basis is never presented as a value", -1L,
            MarketFacts.basisValueGp(legacy));
    }

    @Test
    public void resumedLifecycleIsExplicit()
    {
        GeRecord record = sell(10, 10, 0, 0, 0, 0, 0, 0);
        record.setConfidence(GeRecord.Confidence.RESUMED);
        record.setOfferState("SELLING");
        assertEquals(MarketSettlementProjection.Lifecycle.RESUMED,
            MarketSettlementProjection.lifecycleOf(record));
    }

    @Test
    public void rowsAreReadOnlyAndEmptyInputsAreSafe()
    {
        assertTrue(MarketSettlementProjection.rows(Collections.emptyList(), null, null).isEmpty());
        assertTrue(MarketSettlementProjection.rows(null, null, null).isEmpty());
    }
}
