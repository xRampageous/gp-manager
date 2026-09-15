package com.gpmanager.engine;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GeOfferProvenanceMatcherTest
{
    @Test
    public void matchingBuyAndSellTransitionsRequireTheSameFlowDirection()
    {
        GeOfferProvenanceMatcher.Observation bought = observation(
            GrandExchangeOfferState.BUYING, GrandExchangeOfferState.BOUGHT, 4151, 2, 1);
        GeOfferProvenanceMatcher.Observation sold = observation(
            GrandExchangeOfferState.SELLING, GrandExchangeOfferState.SOLD, 4151, 2, 1);

        assertSame(bought, match(transaction(4151, 1L, "Buy"), bought, 1_000L, 600L).get());
        assertSame(sold, match(transaction(4151, -1L, "Sell"), sold, 1_000L, 600L).get());
        assertFalse(match(transaction(4151, -1L, "Buy"), bought, 1_000L, 600L).isPresent());
        assertFalse(match(transaction(4151, 1L, "Sell"), sold, 1_000L, 600L).isPresent());
    }

    @Test
    public void wrongItemPlayerTradeAndNonMarketReceiptsDoNotMatch()
    {
        GeOfferProvenanceMatcher.Observation bought = observation(
            GrandExchangeOfferState.BUYING, GrandExchangeOfferState.BOUGHT, 4151, 2, 1);

        assertFalse(match(transaction(995, 1L, "Buy"), bought, 1_000L, 600L).isPresent());
        assertFalse(match(transaction(4151, 1L, "Player trade"), bought, 1_000L, 600L).isPresent());

        ProfitTransaction generic = transaction(4151, 1L, "Buy", TrackingContext.GENERIC);
        assertFalse(match(generic, bought, 1_000L, 600L).isPresent());
    }

    @Test
    public void correlationWindowIncludesItsBoundaryButRejectsExpiredAndFutureReads()
    {
        GeOfferProvenanceMatcher.Observation bought = observation(
            GrandExchangeOfferState.BUYING, GrandExchangeOfferState.BOUGHT, 4151, 2, 1);
        assertTrue(match(transaction(4151, 1L, "Buy"), bought, 1_600L, 600L).isPresent());
        assertFalse(match(transaction(4151, 1L, "Buy"), bought, 1_601L, 600L).isPresent());

        GeOfferProvenanceMatcher.Observation future = new GeOfferProvenanceMatcher.Observation(
            bought.getTransition(), 1_001L);
        assertFalse(match(transaction(4151, 1L, "Buy"), future, 1_000L, 600L).isPresent());
    }

    @Test
    public void ambiguousCandidatesAndZeroProgressStayUnmatched()
    {
        GeOfferProvenanceMatcher.Observation first = observation(
            GrandExchangeOfferState.BUYING, GrandExchangeOfferState.BOUGHT, 4151, 2, 1);
        GeOfferProvenanceMatcher.Observation second = observation(
            GrandExchangeOfferState.BUYING, GrandExchangeOfferState.BOUGHT, 4151, 3, 1);
        ProfitTransaction receipt = transaction(4151, 1L, "Buy");

        assertFalse(GeOfferProvenanceMatcher.uniqueMatch(
            receipt, Arrays.asList(first, second), 1_000L, 600L).isPresent());

        GeOfferLedger ledger = new GeOfferLedger();
        ledger.observe(snapshot(GrandExchangeOfferState.BUYING, 4151, 10, 0, 0));
        GeOfferLedger.Transition unchanged = ledger.observe(
            snapshot(GrandExchangeOfferState.BUYING, 4151, 10, 0, 1)).orElseThrow(AssertionError::new);
        assertFalse(match(receipt, new GeOfferProvenanceMatcher.Observation(unchanged, 1_000L),
            1_000L, 600L).isPresent());
    }

    @Test
    public void activeCompletedAndCancelledStatesRetainTheirOfferDirection()
    {
        for (GrandExchangeOfferState buyState : Arrays.asList(
            GrandExchangeOfferState.BUYING,
            GrandExchangeOfferState.BOUGHT,
            GrandExchangeOfferState.CANCELLED_BUY))
        {
            GeOfferProvenanceMatcher.Observation observation = observation(
                GrandExchangeOfferState.BUYING, buyState, 4151, 2, 1);
            assertTrue(match(transaction(4151, 1L, "Buy"), observation, 1_000L, 600L).isPresent());
        }
        for (GrandExchangeOfferState sellState : Arrays.asList(
            GrandExchangeOfferState.SELLING,
            GrandExchangeOfferState.SOLD,
            GrandExchangeOfferState.CANCELLED_SELL))
        {
            GeOfferProvenanceMatcher.Observation observation = observation(
                GrandExchangeOfferState.SELLING, sellState, 4151, 2, 1);
            assertTrue(match(transaction(4151, -1L, "Sell"), observation, 1_000L, 600L).isPresent());
        }
    }

    private static Optional<GeOfferProvenanceMatcher.Observation> match(
        ProfitTransaction transaction,
        GeOfferProvenanceMatcher.Observation observation,
        long now,
        long window)
    {
        return GeOfferProvenanceMatcher.uniqueMatch(
            transaction, Collections.singletonList(observation), now, window);
    }

    private static ProfitTransaction transaction(int itemId, long quantityDelta, String note)
    {
        return transaction(itemId, quantityDelta, note, TrackingContext.MARKET);
    }

    private static ProfitTransaction transaction(
        int itemId,
        long quantityDelta,
        String note,
        TrackingContext context)
    {
        return new ProfitTransaction(
            1_000L,
            TransactionType.TRADE,
            context,
            note,
            true,
            Collections.singletonList(new ItemFlow(itemId, "Item", quantityDelta,
                quantityDelta == 0 ? 0 : 100, quantityDelta * 100L)));
    }

    private static GeOfferProvenanceMatcher.Observation observation(
        GrandExchangeOfferState previousState,
        GrandExchangeOfferState currentState,
        int itemId,
        int quantityTraded,
        int spent)
    {
        GeOfferLedger ledger = new GeOfferLedger();
        ledger.observe(snapshot(previousState, itemId, 10, 0));
        GeOfferLedger.Transition transition = ledger.observe(
            snapshot(currentState, itemId, 10, quantityTraded, spent)).orElseThrow(AssertionError::new);
        return new GeOfferProvenanceMatcher.Observation(transition, 1_000L);
    }

    private static GeOfferLedger.Snapshot snapshot(
        GrandExchangeOfferState state,
        int itemId,
        int total,
        int quantityTraded,
        int spent)
    {
        return new GeOfferLedger.Snapshot(0, state, itemId, total, quantityTraded, 100, spent);
    }

    private static GeOfferLedger.Snapshot snapshot(
        GrandExchangeOfferState state,
        int itemId,
        int total,
        int spent)
    {
        return snapshot(state, itemId, total, 0, spent);
    }
}
