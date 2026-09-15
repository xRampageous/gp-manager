package com.gpmanager.model;

/**
 * Presentation-only copy of a Grand Exchange offer observation attached to a receipt.
 *
 * <p>The slot, comparable traded-quantity delta, raw {@code getSpent()} delta and transition
 * state explain which offer observation was associated with the receipt. They are not prices or
 * accounting evidence.</p>
 */
public final class GeOfferProvenance
{
    private int slot;
    private long quantityTradedDelta;
    private long rawGetSpentDelta;
    private String offerState;

    public GeOfferProvenance()
    {
        // Gson
    }

    public GeOfferProvenance(
        int slot,
        long quantityTradedDelta,
        long rawGetSpentDelta,
        String offerState)
    {
        this.slot = Math.max(0, slot);
        this.quantityTradedDelta = Math.max(0L, quantityTradedDelta);
        this.rawGetSpentDelta = rawGetSpentDelta;
        this.offerState = normalizeState(offerState);
    }

    public int getSlot()
    {
        return Math.max(0, slot);
    }

    public long getQuantityTradedDelta()
    {
        return Math.max(0L, quantityTradedDelta);
    }

    /** Raw RuneLite {@code getSpent()} delta; no sell-side or tax meaning is inferred. */
    public long getRawGetSpentDelta()
    {
        return rawGetSpentDelta;
    }

    /** RuneLite offer state at the observation, for example {@code SELLING} or {@code SOLD}. */
    public String getOfferState()
    {
        return normalizeState(offerState);
    }

    public GeOfferProvenance copy()
    {
        return new GeOfferProvenance(
            getSlot(), getQuantityTradedDelta(), getRawGetSpentDelta(), getOfferState());
    }

    private static String normalizeState(String value)
    {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
