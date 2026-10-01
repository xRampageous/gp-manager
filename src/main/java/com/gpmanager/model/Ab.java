package com.gpmanager;
import lombok.*;
import static com.gpmanager.Av.*;
@EqualsAndHashCode(exclude = "priceCapturedAtEpochMillis")
class Ab {
int itemId;
String itemName;
long quantityDelta;
int unitPrice;
long valueDelta;
Av priceSource;
/** Epoch-millis when the selected non-zero unit price was observed; zero when unavailable. */
long priceCapturedAtEpochMillis;
Ab() {
 // Gson
}

Ab(int itemId, String itemName, long quantityDelta, int unitPrice, long valueDelta) {
 this(itemId, itemName, quantityDelta, unitPrice, valueDelta, UNKNOWN);
}

Ab(int itemId, String itemName, long quantityDelta, int unitPrice, long valueDelta, Av priceSource) {
 this(itemId, itemName, quantityDelta, unitPrice, valueDelta, priceSource, 0L);
}

Ab(int itemId, String itemName, long quantityDelta, int unitPrice, long valueDelta, Av priceSource,
long priceCapturedAtEpochMillis) {
 this.itemId = itemId;
 this.itemName = itemName;
 this.quantityDelta = quantityDelta;
 this.unitPrice = unitPrice;
 this.valueDelta = valueDelta;
 this.priceSource = priceSource == null ? UNKNOWN : priceSource;
 this.priceCapturedAtEpochMillis = priceCapturedAtEpochMillis;
}

Av getPriceSource() {
 return priceSource == null ? UNKNOWN : priceSource;
}

long getPriceCapturedAtEpochMillis() {
 return Ae.nonNeg(priceCapturedAtEpochMillis);
}

/** {@code magnitude} units of this flow, signed like it, valued at {@code unit} each. */
Ab part(long magnitude, long unit, Av source) {
 long sign = quantityDelta < 0L ? -1L : 1L;
 return new Ab(itemId, itemName, sign * magnitude, Ae.toInt(unit),
 sign * Ae.agz(magnitude, unit), source, getPriceCapturedAtEpochMillis());
}

Ab part(long magnitude) {
 return part(magnitude, unitPrice, getPriceSource());
}

/** What remains once {@code claimed} units leave at the unit price; null when nothing remains. */
Ab rest(long claimed) {
 long left = Ae.abs(quantityDelta) - claimed;
 return claimed <= 0L ? this : left <= 0L ? null : new Ab(itemId, itemName,
 quantityDelta < 0L ? -left : left, unitPrice, valueDelta - part(claimed).valueDelta, priceSource,
 priceCapturedAtEpochMillis);
}

boolean isGain() {
 return quantityDelta > 0;
}

boolean isCost() {
 return quantityDelta < 0;
}
}
