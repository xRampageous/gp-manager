package com.gpmanager;

import lombok.AllArgsConstructor;

/** Provenance of the unit price used for an item flow. */
@AllArgsConstructor
enum PriceSource {
    MANUAL_OVERRIDE("manual"),
    /** A catalogue proxy price: a market item's quote divided by its fixed exchange rate. */
    CURRENCY_PROXY("currency proxy"),
    GRAND_EXCHANGE("RuneLite market"),
    FACE_VALUE("face value"),
    /** High-alchemy value, for an ironman who chose to value items at their alch price. */
    HIGH_ALCHEMY("high alchemy"),
    DEFERRED_CLAIM("Claim on open"),
    UNPRICED("unpriced"),
    UNKNOWN("unknown");
    String label;

    public String toString() {
        return label;
    }
}
