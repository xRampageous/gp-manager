package com.gpmanager.model;

/** One measured wealth mover. Earned is profile Net as a single aggregate, never per-item inference. */
public final class WealthTopMover
{
    public enum Reason
    {
        EARNED,
        MARKET
    }

    private final Reason reason;
    private final int itemId;
    private final String name;
    private final long valueChangeGp;

    public WealthTopMover(Reason reason, int itemId, String name, long valueChangeGp)
    {
        this.reason = reason == null ? Reason.EARNED : reason;
        this.itemId = Math.max(0, itemId);
        this.name = name == null || name.trim().isEmpty()
            ? (this.reason == Reason.EARNED ? "Counted net" : "Unknown item") : name.trim();
        this.valueChangeGp = valueChangeGp;
    }

    public Reason getReason() { return reason; }
    public int getItemId() { return itemId; }
    public String getName() { return name; }
    public long getValueChangeGp() { return valueChangeGp; }
}
