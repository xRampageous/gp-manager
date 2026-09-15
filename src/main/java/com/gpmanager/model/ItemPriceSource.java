package com.gpmanager.model;

/** Provenance of the unit price used for an item flow. */
public enum ItemPriceSource
{
    MANUAL_OVERRIDE("manual"),
    CURRENCY_PROXY("currency proxy"),
    GRAND_EXCHANGE("RuneLite market"),
    OFFER_PRICE("GE offer price"),
    FACE_VALUE("face value"),
    HIGH_ALCHEMY("high alchemy"),
    DEFERRED_CLAIM("Claim on open"),
    UNPRICED("unpriced"),
    UNKNOWN("unknown");

    private final String label;

    ItemPriceSource(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
