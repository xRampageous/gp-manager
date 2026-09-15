package com.gpmanager.ui.bento;

/**
 * Client-side PvP facts the plugin observes on its tick (Wilderness / PvP world, skull,
 * Protect Item, carried risk). Presentation only; nothing here touches accounting.
 */
public final class PvpState
{
    public static final PvpState NONE = new PvpState(false, false, false, false, false, 0L);

    public final boolean pvpPossible;
    public final boolean skulled;
    public final boolean highRisk;
    public final boolean protectItem;
    public final boolean riskKnown;
    public final long riskValue;

    public PvpState(boolean pvpPossible, boolean skulled, boolean highRisk, boolean protectItem, boolean riskKnown, long riskValue)
    {
        this.pvpPossible = pvpPossible;
        this.skulled = skulled;
        this.highRisk = highRisk;
        this.protectItem = protectItem;
        this.riskKnown = riskKnown;
        this.riskValue = riskValue;
    }
}
