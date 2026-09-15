package com.gpmanager.model;

/**
 * One raw Grand Exchange offer transition as the client reported it (pass 10 step 45 live check):
 * the slot, the state it moved to, the traded and coin deltas exactly as the API said, and the
 * listed price. Kept transiently so Tools › Diagnostics can show what the client reports against
 * what the player actually received — the comparison that decides the sell-side coin reading.
 */
public final class GeOfferObservation
{
    private final long atEpochMillis;
    private final int slot;
    private final String state;
    private final int itemId;
    private final String itemName;
    private final int totalQuantity;
    private final int quantityTraded;
    private final int quantityTradedDelta;
    private final int listedPrice;
    private final int spentDelta;
    private final int spentTotal;
    private final boolean comparable;

    public GeOfferObservation(long atEpochMillis, int slot, String state, int itemId, String itemName, int totalQuantity,
        int quantityTraded, int quantityTradedDelta, int listedPrice, int spentDelta, int spentTotal, boolean comparable)
    {
        this.atEpochMillis = atEpochMillis;
        this.slot = slot;
        this.state = state == null ? "" : state;
        this.itemId = itemId;
        this.itemName = itemName == null ? "" : itemName;
        this.totalQuantity = totalQuantity;
        this.quantityTraded = quantityTraded;
        this.quantityTradedDelta = quantityTradedDelta;
        this.listedPrice = listedPrice;
        this.spentDelta = spentDelta;
        this.spentTotal = spentTotal;
        this.comparable = comparable;
    }

    public long getAtEpochMillis() { return atEpochMillis; }
    public int getSlot() { return slot; }
    public String getState() { return state; }
    public int getItemId() { return itemId; }
    public String getItemName() { return itemName; }
    public int getTotalQuantity() { return totalQuantity; }
    public int getQuantityTraded() { return quantityTraded; }
    public int getQuantityTradedDelta() { return quantityTradedDelta; }
    public int getListedPrice() { return listedPrice; }
    /** Raw {@code getSpent()} difference; no proceeds or tax meaning is assigned here. */
    public int getSpentDelta() { return spentDelta; }
    public int getSpentTotal() { return spentTotal; }
    public boolean isComparable() { return comparable; }
    public boolean isSell() { return state.contains("SELL") || state.equals("SOLD"); }

    /** What the listing would have paid or cost for the traded delta, before any tax. */
    public long getListedValueForDelta()
    {
        return (long) quantityTradedDelta * listedPrice;
    }
}
