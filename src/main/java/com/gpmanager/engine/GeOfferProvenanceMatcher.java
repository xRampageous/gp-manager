package com.gpmanager.engine;

import com.gpmanager.model.GeOfferProvenance;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import net.runelite.api.GrandExchangeOfferState;

/** Matches presentation provenance to an already-booked MARKET receipt. */
public final class GeOfferProvenanceMatcher
{
    private GeOfferProvenanceMatcher()
    {
    }

    /**
     * Returns a match only when exactly one recent, comparable transition has the same item id
     * and direction as a MARKET trade flow. This helper never changes the receipt or books value.
     */
    public static Optional<Observation> uniqueMatch(
        ProfitTransaction transaction,
        Collection<Observation> observations,
        long transactionAtEpochMillis,
        long windowMillis)
    {
        if (transaction == null
            || transaction.getContext() != TrackingContext.MARKET
            || transaction.getType() != TransactionType.TRADE
            || transaction.getNote().toLowerCase(Locale.ROOT).contains("player trade")
            || observations == null
            || windowMillis < 0L)
        {
            return Optional.empty();
        }

        Observation match = null;
        for (Observation observation : observations)
        {
            if (!matches(transaction, observation, transactionAtEpochMillis, windowMillis))
            {
                continue;
            }
            if (match != null)
            {
                return Optional.empty();
            }
            match = observation;
        }
        return Optional.ofNullable(match);
    }

    private static boolean matches(
        ProfitTransaction transaction,
        Observation observation,
        long transactionAtEpochMillis,
        long windowMillis)
    {
        if (observation == null || observation.transition == null)
        {
            return false;
        }
        long ageMillis = transactionAtEpochMillis - observation.observedAtEpochMillis;
        GeOfferLedger.Transition transition = observation.transition;
        if (ageMillis < 0L
            || ageMillis > windowMillis
            || !transition.hasComparableProgress()
            || transition.getQuantityTradedDelta() <= 0)
        {
            return false;
        }

        GrandExchangeOfferState state = transition.getCurrent().getState();
        boolean buying = state == GrandExchangeOfferState.BUYING
            || state == GrandExchangeOfferState.BOUGHT
            || state == GrandExchangeOfferState.CANCELLED_BUY;
        boolean selling = state == GrandExchangeOfferState.SELLING
            || state == GrandExchangeOfferState.SOLD
            || state == GrandExchangeOfferState.CANCELLED_SELL;
        if (!buying && !selling)
        {
            return false;
        }

        int itemId = transition.getCurrent().getItemId();
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null || flow.getItemId() != itemId)
            {
                continue;
            }
            if ((buying && flow.getQuantityDelta() > 0L)
                || (selling && flow.getQuantityDelta() < 0L))
            {
                return true;
            }
        }
        return false;
    }

    /** One raw offer observation and its local observation time. */
    public static final class Observation
    {
        private final GeOfferLedger.Transition transition;
        private final long observedAtEpochMillis;

        public Observation(GeOfferLedger.Transition transition, long observedAtEpochMillis)
        {
            this.transition = transition;
            this.observedAtEpochMillis = observedAtEpochMillis;
        }

        public long getObservedAtEpochMillis()
        {
            return observedAtEpochMillis;
        }

        public GeOfferLedger.Transition getTransition()
        {
            return transition;
        }

        public GeOfferProvenance toProvenance()
        {
            if (transition == null)
            {
                return null;
            }
            GeOfferLedger.Snapshot current = transition.getCurrent();
            return new GeOfferProvenance(
                current.getSlot(),
                transition.getQuantityTradedDelta(),
                transition.getSpentDelta(),
                current.getState().name());
        }
    }
}
