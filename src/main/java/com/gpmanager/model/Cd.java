package com.gpmanager;
import static com.gpmanager.Ae.nonNeg;
import static java.lang.Math.*;
/** Remaining pooled known coverage held by one counted non-GE item removal. */
class Cd {
int itemId;
long remainingRemovedQty;
long remainingKnownQty;
long remainingBasisGp;
Cd() {
 // Gson
}

Cd(int /* itemId */a, long /* removedQty */b, long /* knownQty */c, long /* basisGp */d) {
 this.itemId = a;
 this.remainingRemovedQty = nonNeg(b);
 this.remainingKnownQty = nonNeg(c);
 this.remainingBasisGp = nonNeg(d);
}

long uh() { return nonNeg(remainingRemovedQty); }
long ug() { return nonNeg(remainingKnownQty); }
void restore(long /* removedQty */a, long /* knownQty */b, long /* basisGp */c) {
 remainingRemovedQty = nonNeg(remainingRemovedQty - max(0L, a));
 remainingKnownQty = nonNeg(remainingKnownQty - max(0L, b));
 remainingBasisGp = nonNeg(remainingBasisGp - max(0L, c));
}
}
