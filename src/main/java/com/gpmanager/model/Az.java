package com.gpmanager;
import lombok.*;
import static com.gpmanager.Ae.nonNeg;
/**
* One canonical item's pooled tracked-basis state (schema 106).
*
* <p>It is supporting ownership state, never a financial authority: canonical counted transactions
* stay the only financial truth. {@code available*} is the free pooled quantity/value that a new
* SELL reservation or permanent sink may consume; {@code reserved*} is held out by open SELL
* custody reservations; {@code realizationFenceEpochMillis} records the latest time pooled basis
* was realized or permanently depleted, which fences retroactive acquisition corrections for this
* item. No FIFO lots, no portfolio history, no mark-to-market.</p>
*/
class Az {
@Setter
int itemId;
long availableQty;
long availableBasisGp;
long reservedQty;
long reservedBasisGp;
long realizationFenceEpochMillis;
/** Latest proven known-coverage acquisition; fences retroactive counted removals. */
long latestAcquisitionEpochMillis;
Az() {
}

Az(int /* itemId */a) {
 this.itemId = a;
}

long getAvailableQty() { return nonNeg(availableQty); }
void setAvailableQty(long /* value */a) { availableQty = nonNeg(a); }
long getAvailableBasisGp() { return nonNeg(availableBasisGp); }
void setAvailableBasisGp(long /* value */a) { availableBasisGp = nonNeg(a); }
long getReservedQty() { return nonNeg(reservedQty); }
void setReservedQty(long /* value */a) { reservedQty = nonNeg(a); }
long getReservedBasisGp() { return nonNeg(reservedBasisGp); }
void setReservedBasisGp(long /* value */a) { reservedBasisGp = nonNeg(a); }
long getRealizationFenceEpochMillis() { return nonNeg(realizationFenceEpochMillis); }
void setRealizationFenceEpochMillis(long /* value */a) {
 realizationFenceEpochMillis = nonNeg(a);
}

long getLatestAcquisitionEpochMillis() { return nonNeg(latestAcquisitionEpochMillis); }
void setLatestAcquisitionEpochMillis(long /* value */a) {
 latestAcquisitionEpochMillis = nonNeg(a);
}
}
