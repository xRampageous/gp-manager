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

Ab(int /* itemId */a, String /* itemName */b, long /* quantityDelta */c, int /* unitPrice */d, long /* valueDelta */e) {
 this(a, b, c, d, e, UNKNOWN);
}

Ab(int /* itemId */a, String /* itemName */b, long /* quantityDelta */c, int /* unitPrice */d, long /* valueDelta */e, Av /* priceSource */f) {
 this(a, b, c, d, e, f, 0L);
}

Ab(int /* itemId */a, String /* itemName */b, long /* quantityDelta */c, int /* unitPrice */d, long /* valueDelta */e, Av /* priceSource */f,
long /* priceCapturedAtEpochMillis */g) {
 this.itemId = a;
 this.itemName = b;
 this.quantityDelta = c;
 this.unitPrice = d;
 this.valueDelta = e;
 this.priceSource = f == null ? UNKNOWN : f;
 this.priceCapturedAtEpochMillis = g;
}

Av getPriceSource() {
 return priceSource == null ? UNKNOWN : priceSource;
}

long getPriceCapturedAtEpochMillis() {
 return Ae.nonNeg(priceCapturedAtEpochMillis);
}

/** {@code magnitude} units of this flow, signed like it, valued at {@code unit} each. */
Ab part(long /* magnitude */a, long /* unit */b, Av /* source */c) {
 long /* sign */d = quantityDelta < 0L ? -1L : 1L;
 return new Ab(itemId, itemName, d * a, Ae.toInt(b),
 d * Ae.agz(a, b), c, getPriceCapturedAtEpochMillis());
}

Ab part(long /* magnitude */a) {
 return part(a, unitPrice, getPriceSource());
}

/** What remains once {@code claimed} units leave at the unit price; null when nothing remains. */
Ab rest(long /* claimed */a) {
 long /* left */b = Ae.abs(quantityDelta) - a;
 return a <= 0L ? this : b <= 0L ? null : new Ab(itemId, itemName,
 quantityDelta < 0L ? -b : b, unitPrice, valueDelta - part(a).valueDelta, priceSource,
 priceCapturedAtEpochMillis);
}

boolean isGain() {
 return quantityDelta > 0;
}

boolean isCost() {
 return quantityDelta < 0;
}
}
