package com.gpmanager;
import lombok.AllArgsConstructor;
/** Provenance of the unit price used for an item flow. */
@AllArgsConstructor
enum PriceSource {
MANUAL_OVERRIDE("manual"),
/** A catalogue proxy price: a market item's quote divided by its fixed exchange rate. */
CURRENCY_PROXY("currency proxy"), GRAND_EXCHANGE("RuneLite market"), OFFER_PRICE("GE offer price"),
FACE_VALUE("face value"),
/** Retired high-alchemy fallback valuation; retained so saved history still loads. */
HIGH_ALCHEMY("high alchemy"), DEFERRED_CLAIM("Claim on open"), UNPRICED("unpriced"), UNKNOWN("unknown");
final String label;
public String toString() {
return label;
}
}
