package com.gpmanager;

import static com.gpmanager.PriceSource.*;

import lombok.*;
import lombok.AllArgsConstructor;

@EqualsAndHashCode(exclude = "priceCapturedAtEpochMillis")
class Flow {
    int itemId;
    String itemName;
    long quantityDelta;
    int unitPrice;
    long valueDelta;
    PriceSource priceSource;

    /** Epoch-millis when the selected non-zero unit price was observed; zero when unavailable. */
    long priceCapturedAtEpochMillis;

    Flow() {
        // Gson
    }

    Flow(int itemId, String itemName, long quantityDelta, int unitPrice, long valueDelta) {
        this(itemId, itemName, quantityDelta, unitPrice, valueDelta, UNKNOWN);
    }

    Flow(
            int itemId,
            String itemName,
            long quantityDelta,
            int unitPrice,
            long valueDelta,
            PriceSource priceSource) {
        this(itemId, itemName, quantityDelta, unitPrice, valueDelta, priceSource, 0L);
    }

    Flow(
            int itemId,
            String itemName,
            long quantityDelta,
            int unitPrice,
            long valueDelta,
            PriceSource priceSource,
            long priceCapturedAtEpochMillis) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.quantityDelta = quantityDelta;
        this.unitPrice = unitPrice;
        this.valueDelta = valueDelta;
        this.priceSource = priceSource == null ? UNKNOWN : priceSource;
        this.priceCapturedAtEpochMillis = priceCapturedAtEpochMillis;
    }

    PriceSource getPriceSource() {
        return priceSource == null ? UNKNOWN : priceSource;
    }

    long getPriceCapturedAtEpochMillis() {
        return SafeMath.nonNeg(priceCapturedAtEpochMillis);
    }

    /** {@code magnitude} units of this flow, signed like it, valued at {@code unit} each. */
    Flow part(long magnitude, long unit, PriceSource source) {
        long sign = quantityDelta < 0L ? -1L : 1L;
        return new Flow(
                itemId,
                itemName,
                sign * magnitude,
                SafeMath.toInt(unit),
                sign * SafeMath.safeMultiply(magnitude, unit),
                source,
                getPriceCapturedAtEpochMillis());
    }

    Flow part(long magnitude) {
        return part(magnitude, unitPrice, getPriceSource());
    }

    /**
     * What remains once {@code claimed} units leave at the unit price; null when nothing remains.
     */
    Flow rest(long claimed) {
        long left = SafeMath.abs(quantityDelta) - claimed;
        return claimed <= 0L
                ? this
                : left <= 0L
                        ? null
                        : new Flow(
                                itemId,
                                itemName,
                                quantityDelta < 0L ? -left : left,
                                unitPrice,
                                valueDelta - part(claimed).valueDelta,
                                priceSource,
                                priceCapturedAtEpochMillis);
    }

    boolean isGain() {
        return quantityDelta > 0;
    }

    boolean isCost() {
        return quantityDelta < 0;
    }
}

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
