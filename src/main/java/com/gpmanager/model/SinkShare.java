package com.gpmanager;
import static com.gpmanager.SafeMath.nonNeg;
import static java.lang.Math.*;
/** Remaining pooled known coverage held by one counted non-GE item removal. */
class SinkShare {
int itemId;
long remainingRemovedQty;
long remainingKnownQty;
long remainingBasisGp;
SinkShare() {
 // Gson
}

SinkShare(int itemId, long removedQty, long knownQty, long basisGp) {
 this.itemId = itemId;
 this.remainingRemovedQty = nonNeg(removedQty);
 this.remainingKnownQty = nonNeg(knownQty);
 this.remainingBasisGp = nonNeg(basisGp);
}

long getRemainingRemovedQty() { return nonNeg(remainingRemovedQty); }
long getRemainingKnownQty() { return nonNeg(remainingKnownQty); }
void restore(long removedQty, long knownQty, long basisGp) {
 remainingRemovedQty = nonNeg(remainingRemovedQty - max(0L, removedQty));
 remainingKnownQty = nonNeg(remainingKnownQty - max(0L, knownQty));
 remainingBasisGp = nonNeg(remainingBasisGp - max(0L, basisGp));
}
}
