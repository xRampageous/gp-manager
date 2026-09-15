package com.gpmanager.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Slot-keyed, observation-only model of the Grand Exchange offer state exposed by RuneLite.
 *
 * <p>{@link GrandExchangeOffer#getQuantitySold()} is the API's quantity bought or sold so far;
 * {@link GrandExchangeOffer#getSpent()} is retained as an un-interpreted raw observation. This
 * class deliberately assigns no financial meaning to either field, especially on sell offers,
 * and never books a cost or receipt.</p>
 *
 * <p>Call {@link #beginLoginSeed()} before processing RuneLite's login slot replay. Snapshots
 * received until {@link #finishLoginSeed()} establish baselines and produce no transitions. On
 * relog, starting another seed clears old slot state so the replay cannot look like new activity.</p>
 */
public final class GeOfferLedger
{
    private final Map<Integer, Snapshot> bySlot = new LinkedHashMap<>();
    private final Map<Integer, Snapshot> beforeSeed = new LinkedHashMap<>();
    private final List<Transition> resumedProgress = new ArrayList<>();
    private boolean loginSeed;

    /** Begin a login/relogin baseline pass; all observations are suppressed until it is finished. */
    public synchronized void beginLoginSeed()
    {
        beforeSeed.clear();
        beforeSeed.putAll(bySlot);
        bySlot.clear();
        loginSeed = true;
    }

    /**
     * Finish the baseline pass after the caller has received the initial offer-slot replay.
     * Progress an offer made while the client was away (same slot, same offer identity, more
     * traded) is kept as comparable transitions for {@link #takeResumedProgress()}; a slot whose
     * offer changed identity, or that this process never saw, starts as a plain baseline.
     */
    public synchronized void finishLoginSeed()
    {
        loginSeed = false;
        resumedProgress.clear();
        for (Map.Entry<Integer, Snapshot> entry : bySlot.entrySet())
        {
            Snapshot previous = beforeSeed.get(entry.getKey());
            Snapshot current = entry.getValue();
            if (previous == null || current == null || !isActive(previous.getState())
                || !hasSameOfferIdentity(previous, current))
            {
                continue;
            }
            int quantityDelta = current.getQuantityTraded() - previous.getQuantityTraded();
            int spentDelta = current.getSpent() - previous.getSpent();
            if (quantityDelta > 0 && spentDelta >= 0)
            {
                resumedProgress.add(new Transition(previous, current, classify(previous, current), true,
                    quantityDelta, spentDelta));
            }
        }
        beforeSeed.clear();
    }

    /** Comparable progress made while away, found by the last {@link #finishLoginSeed()}; cleared once taken. */
    public synchronized List<Transition> takeResumedProgress()
    {
        List<Transition> result = new ArrayList<>(resumedProgress);
        resumedProgress.clear();
        return Collections.unmodifiableList(result);
    }

    public synchronized boolean isLoginSeedInProgress()
    {
        return loginSeed;
    }

    /**
     * Observe one immutable slot snapshot. Repeated identical snapshots and all login-seed
     * observations return an empty Optional.
     */
    public synchronized Optional<Transition> observe(Snapshot current)
    {
        Objects.requireNonNull(current, "current");
        Snapshot previous = bySlot.put(current.getSlot(), current);
        if (loginSeed || current.equals(previous))
        {
            return Optional.empty();
        }
        if (current.getState() == GrandExchangeOfferState.EMPTY
            && (previous == null || previous.getState() == GrandExchangeOfferState.EMPTY))
        {
            return Optional.empty();
        }

        TransitionKind kind = classify(previous, current);
        boolean sameActiveIdentity = previous != null
            && isActive(previous.getState())
            && hasSameOfferIdentity(previous, current);
        int quantityDelta = sameActiveIdentity
            ? current.getQuantityTraded() - previous.getQuantityTraded()
            : 0;
        int spentDelta = sameActiveIdentity
            ? current.getSpent() - previous.getSpent()
            : 0;
        // A skipped/reordered terminal transition or an identical-slot replacement can make
        // progress move backwards. Preserve the raw deltas for diagnostics but never describe
        // them as comparable evidence for later accounting.
        boolean comparable = sameActiveIdentity && quantityDelta >= 0 && spentDelta >= 0;
        return Optional.of(new Transition(previous, current, kind, comparable, quantityDelta, spentDelta));
    }

    /**
     * Take an immutable point-in-time copy of the latest per-slot observations. The returned map
     * cannot be used to mutate this ledger.
     */
    public synchronized Map<Integer, Snapshot> snapshots()
    {
        return Collections.unmodifiableMap(new LinkedHashMap<>(bySlot));
    }

    private static TransitionKind classify(Snapshot previous, Snapshot current)
    {
        GrandExchangeOfferState next = current.getState();
        if (previous == null || previous.getState() == GrandExchangeOfferState.EMPTY)
        {
            return next == GrandExchangeOfferState.EMPTY ? TransitionKind.CLEARED : TransitionKind.PLACED;
        }
        if (next == GrandExchangeOfferState.EMPTY)
        {
            return TransitionKind.CLEARED;
        }
        if (isCancelled(next))
        {
            return TransitionKind.CANCELLED;
        }
        if (isTerminal(previous.getState()) && isActive(next))
        {
            return TransitionKind.PLACED;
        }
        if (!hasSameOfferIdentity(previous, current))
        {
            return TransitionKind.REPLACED;
        }
        if (isTerminal(next))
        {
            return TransitionKind.COMPLETED;
        }
        return TransitionKind.UPDATED;
    }

    private static boolean hasSameOfferIdentity(Snapshot left, Snapshot right)
    {
        return left.getItemId() == right.getItemId()
            && left.getTotalQuantity() == right.getTotalQuantity()
            && left.getPrice() == right.getPrice()
            && offerSide(left.getState()) == offerSide(right.getState());
    }

    private static OfferSide offerSide(GrandExchangeOfferState state)
    {
        switch (state)
        {
            case BUYING:
            case BOUGHT:
            case CANCELLED_BUY:
                return OfferSide.BUY;
            case SELLING:
            case SOLD:
            case CANCELLED_SELL:
                return OfferSide.SELL;
            case EMPTY:
            default:
                return OfferSide.NONE;
        }
    }

    private static boolean isActive(GrandExchangeOfferState state)
    {
        return state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING;
    }

    private static boolean isTerminal(GrandExchangeOfferState state)
    {
        return state == GrandExchangeOfferState.BOUGHT
            || state == GrandExchangeOfferState.SOLD
            || state == GrandExchangeOfferState.CANCELLED_BUY
            || state == GrandExchangeOfferState.CANCELLED_SELL;
    }

    private static boolean isCancelled(GrandExchangeOfferState state)
    {
        return state == GrandExchangeOfferState.CANCELLED_BUY
            || state == GrandExchangeOfferState.CANCELLED_SELL;
    }

    private enum OfferSide
    {
        NONE,
        BUY,
        SELL
    }

    public enum TransitionKind
    {
        PLACED,
        UPDATED,
        COMPLETED,
        CANCELLED,
        CLEARED,
        REPLACED
    }

    /** Immutable copy of the API fields used by the offer ledger. */
    public static final class Snapshot
    {
        private final int slot;
        private final GrandExchangeOfferState state;
        private final int itemId;
        private final int totalQuantity;
        private final int quantityTraded;
        private final int price;
        private final int spent;

        public Snapshot(
            int slot,
            GrandExchangeOfferState state,
            int itemId,
            int totalQuantity,
            int quantityTraded,
            int price,
            int spent)
        {
            if (slot < 0)
            {
                throw new IllegalArgumentException("slot must be non-negative");
            }
            this.slot = slot;
            this.state = Objects.requireNonNull(state, "state");
            this.itemId = itemId;
            this.totalQuantity = totalQuantity;
            this.quantityTraded = quantityTraded;
            this.price = price;
            this.spent = spent;
        }

        public static Snapshot fromOffer(int slot, GrandExchangeOffer offer)
        {
            Objects.requireNonNull(offer, "offer");
            return new Snapshot(
                slot,
                offer.getState(),
                offer.getItemId(),
                offer.getTotalQuantity(),
                offer.getQuantitySold(),
                offer.getPrice(),
                offer.getSpent());
        }

        public int getSlot()
        {
            return slot;
        }

        public GrandExchangeOfferState getState()
        {
            return state;
        }

        public int getItemId()
        {
            return itemId;
        }

        public int getTotalQuantity()
        {
            return totalQuantity;
        }

        /** RuneLite's quantity bought or sold so far, copied from getQuantitySold(). */
        public int getQuantityTraded()
        {
            return quantityTraded;
        }

        public int getPrice()
        {
            return price;
        }

        /** Raw getSpent() observation; the model does not assign sell-side or tax semantics. */
        public int getSpent()
        {
            return spent;
        }

        @Override
        public boolean equals(Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof Snapshot))
            {
                return false;
            }
            Snapshot that = (Snapshot) other;
            return slot == that.slot
                && itemId == that.itemId
                && totalQuantity == that.totalQuantity
                && quantityTraded == that.quantityTraded
                && price == that.price
                && spent == that.spent
                && state == that.state;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(slot, state, itemId, totalQuantity, quantityTraded, price, spent);
        }
    }

    /** Immutable description of an observed state transition; contains no accounting decision. */
    public static final class Transition
    {
        private final Snapshot previous;
        private final Snapshot current;
        private final TransitionKind kind;
        private final boolean comparableProgress;
        private final int quantityTradedDelta;
        private final int spentDelta;

        private Transition(
            Snapshot previous,
            Snapshot current,
            TransitionKind kind,
            boolean comparableProgress,
            int quantityTradedDelta,
            int spentDelta)
        {
            this.previous = previous;
            this.current = current;
            this.kind = kind;
            this.comparableProgress = comparableProgress;
            this.quantityTradedDelta = quantityTradedDelta;
            this.spentDelta = spentDelta;
        }

        public Snapshot getPrevious()
        {
            return previous;
        }

        public Snapshot getCurrent()
        {
            return current;
        }

        public TransitionKind getKind()
        {
            return kind;
        }

        /**
         * True when the previous observation was active, both snapshots have the same offer
         * identity, and quantity/spent moved monotonically. The current observation may be a
         * terminal fill/cancellation state. A false result is never accounting evidence.
         */
        public boolean hasComparableProgress()
        {
            return comparableProgress;
        }

        /** Signed raw difference; meaningful only when {@link #hasComparableProgress()} is true. */
        public int getQuantityTradedDelta()
        {
            return quantityTradedDelta;
        }

        /** Signed raw getSpent() difference; not a proceeds or tax interpretation. */
        public int getSpentDelta()
        {
            return spentDelta;
        }
    }
}
