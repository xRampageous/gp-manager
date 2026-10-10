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
    private static GeOffer sell(long offered, long captured, long filled, long spent,
        long settled, long settledExecution, long settledCash, long realized)
    {
        GeOffer record = new GeOffer("offer-" + offered + "-" + filled, 0,
            GeOffer.Side.SELL, 561, "Nature rune", offered, 7L, 1_000L, "session-a");
        record.setCapturedQty(captured);
        record.setFilledQty(filled);
        record.setSpentGp(spent);
        record.setSettledQty(settled);
        record.setSettledExecutionGp(settledExecution);
        record.setSettledCashGp(settledCash);
        record.setRealizedResultGp(realized);
        record.setBasisUnitPrice(6L);
        record.setBasisSource(PriceSource.GRAND_EXCHANGE);
        record.setBasisCapturedAtEpochMillis(1_000L);
        return record;
    }

    @Test
    public void pendingExecutionEdgeIsNeverRealized()
    {
        GeOffer record = sell(10, 10, 10, 70, 0, 0, 0, 0);
        record.setOfferState("SOLD");
        MarketRows.Row row = MarketFacts.row(record);

        assertEquals(MarketRows.Lifecycle.EXECUTED_UNSETTLED, row.lifecycle);
        assertEquals(0L, row.realizedResultGp);
        assertFalse(row.isRealizedIncluded());
    }

    @Test
    public void settlementAdjustmentAndRealizedResultAreExact()
    {
        GeOffer record = sell(10, 10, 10, 70, 10, 70, 69, 9);
        record.setOfferState("SOLD");
        MarketRows.Row row = MarketFacts.row(record);

        assertEquals(MarketRows.Lifecycle.REALIZED, row.lifecycle);
        assertEquals(60L, MarketFacts.basisValueGp(record));
        assertEquals(69L, row.observedSettlementGp);
        assertEquals(-1L, row.settlementAdjustmentGp);
        assertEquals(9L, row.realizedResultGp);
        assertTrue(row.isRealizedIncluded());
    }

    @Test
    public void partiallyRealizedCountsOnlySettledQuantity()
    {
        GeOffer record = sell(100, 100, 100, 700, 40, 280, 280, 40);
        record.setOfferState("SELLING");
        MarketRows.Row row = MarketFacts.row(record);

        assertEquals(MarketRows.Lifecycle.PARTIALLY_REALIZED, row.lifecycle);
        assertEquals(40L, row.settledQty);
        assertEquals(40L, row.realizedResultGp);
    }

    @Test
    public void cancellingBeforeAnyFillIsCancelledReturned()
    {
        GeOffer record = sell(100, 100, 0, 0, 0, 0, 0, 0);
        record.setOfferState("CANCELLED_SELL");
        record.setReturnedQty(100L);
        MarketRows.Row row = MarketFacts.row(record);

        assertEquals(MarketRows.Lifecycle.CANCELLED_RETURNED, row.lifecycle);
    }

    @Test
    public void ambiguousAndLegacyFailClosed()
    {
        GeOffer ambiguous = sell(10, 10, 10, 70, 0, 0, 0, 0);
        ambiguous.setQuantityModified(true);
        assertEquals(MarketRows.Lifecycle.AMBIGUOUS,
            MarketRows.lifecycleOf(ambiguous));

        GeOffer legacy = sell(10, 0, 4, 28, 0, 0, 0, 0);
        legacy.setConfidence(GeOffer.CustodyConfidence.LEGACY_UNBASED);
        assertEquals(MarketRows.Lifecycle.UNAVAILABLE,
            MarketRows.lifecycleOf(legacy));
        assertEquals("unknown basis is never presented as a value", -1L,
            MarketFacts.basisValueGp(legacy));
    }

    @Test
    public void anOpenOfferAfterARelogIsStillPending()
    {
        GeOffer record = sell(10, 10, 0, 0, 0, 0, 0, 0);
        record.setOfferState("SELLING");
        assertEquals(MarketRows.Lifecycle.PENDING,
            MarketRows.lifecycleOf(record));
    }

    @Test
    public void rowsAreReadOnlyAndEmptyInputsAreSafe()
    {
        assertTrue(MarketRows.rows(Collections.emptyList(), null, null).isEmpty());
        assertTrue(MarketRows.rows(null, null, null).isEmpty());
    }

    @Test
    public void aSaleWhoseReceiptWasCompactedIsHistoryNotReview()
    {
        GeOffer record = sell(10, 10, 10, 70, 10, 70, 69, 9);
        record.setOfferState("SOLD");
        record.setSettlementId("compacted-receipt");
        MarketRows.Row row = MarketRows.rows(Collections.singletonList(record), id -> null, null).get(0);
        assertTrue(row.isRealizedIncluded());
        assertFalse("its money is already in the retained totals; nothing to decide",
            Semantics.marketUnit(row, Collections.emptyList()).reviewRequired);
    }
}
