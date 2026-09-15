package com.gpmanager;

/**
 * When the HUD+ session ledger satellite folio appears.
 * Never uses Alt — RuneLite reserves Alt for moving overlays.
 */
public enum HudPlusDetailTrigger
{
    HOVER("Hover"),
    SHIFT("Shift"),
    HOVER_OR_SHIFT("Hover or Shift");

    private final String label;

    HudPlusDetailTrigger(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
