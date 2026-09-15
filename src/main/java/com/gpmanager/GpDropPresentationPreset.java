package com.gpmanager;

/**
 * High-level live-drop presets. Custom keeps the existing advanced controls,
 * while the other presets resolve to a coherent content/icon/text combination.
 */
public enum GpDropPresentationPreset
{
    ICON_VALUE_ONLY(
        "Icon + GP value only",
        GpDropContentMode.EACH_ITEM,
        GpDropItemTextMode.VALUE_ONLY,
        GpDropIconMode.SMART,
        false),
    ICON_QUANTITY_VALUE(
        "Icon + quantity + GP",
        GpDropContentMode.EACH_ITEM,
        GpDropItemTextMode.QUANTITY,
        GpDropIconMode.SMART,
        false),
    SMART_DETAILED(
        "Smart detailed",
        GpDropContentMode.SMART,
        GpDropItemTextMode.QUANTITY,
        GpDropIconMode.SMART,
        false),
    COIN_TOTAL(
        "Profit Tracker classic",
        GpDropContentMode.TRANSACTION_TOTAL,
        GpDropItemTextMode.VALUE_ONLY,
        GpDropIconMode.COINS,
        false),
    VALUE_ONLY(
        "GP value only",
        GpDropContentMode.TRANSACTION_TOTAL,
        GpDropItemTextMode.VALUE_ONLY,
        GpDropIconMode.NONE,
        false),
    CUSTOM(
        "Custom",
        null,
        null,
        null,
        false);

    private final String displayName;
    private final GpDropContentMode contentMode;
    private final GpDropItemTextMode itemTextMode;
    private final GpDropIconMode iconMode;
    private final boolean quantityOnIcon;

    GpDropPresentationPreset(
        String displayName,
        GpDropContentMode contentMode,
        GpDropItemTextMode itemTextMode,
        GpDropIconMode iconMode,
        boolean quantityOnIcon)
    {
        this.displayName = displayName;
        this.contentMode = contentMode;
        this.itemTextMode = itemTextMode;
        this.iconMode = iconMode;
        this.quantityOnIcon = quantityOnIcon;
    }

    public boolean isCustom()
    {
        return this == CUSTOM;
    }

    public GpDropContentMode getContentMode()
    {
        return contentMode;
    }

    public GpDropItemTextMode getItemTextMode()
    {
        return itemTextMode;
    }

    public GpDropIconMode getIconMode()
    {
        return iconMode;
    }

    public boolean isQuantityOnIcon()
    {
        return quantityOnIcon;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
