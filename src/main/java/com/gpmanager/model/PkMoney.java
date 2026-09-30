package com.gpmanager;
import static com.gpmanager.Ae.*;
/**
* One PvP money amount: net, counted costs and their supplies share while every part's split is
* known. A receipt's contribution to its encounter and anchor, and the totals folded from them.
*/
class PkMoney {
long netGp;
long costsGp;
long suppliesCostsGp;
boolean costSplitAvailable = true;
PkMoney() {
// Gson
}
PkMoney(long netGp, long costsGp, long suppliesCostsGp, boolean costSplitAvailable) {
this.netGp = netGp;
this.costsGp = nonNeg(costsGp);
this.suppliesCostsGp = nonNeg(suppliesCostsGp);
this.costSplitAvailable = costSplitAvailable;
}
/** A receipt's correction-aware PvP money; an excluded or unavailable receipt is zero. */
static PkMoney of(Ac transaction) {
Bp.Ax amounts = Bp.transaction(transaction);
if (!amounts.available || !amounts.included) {
return new PkMoney(0L, 0L, 0L, true);
}
if (amounts.costs <= 0L) {
return new PkMoney(amounts.getNet(), 0L, 0L, true);
}
Bp.Du split = Bp.costSplit(transaction);
return new PkMoney(amounts.getNet(), amounts.costs, split.supplies, split.available);
}
/** Adds another amount; its supplies count only when its own split is known. */
PkMoney plus(PkMoney other) {
netGp = safeAdd(netGp, other.netGp);
costsGp = safeAdd(costsGp, other.tr());
if (other.costSplitAvailable) {
suppliesCostsGp = safeAdd(suppliesCostsGp, other.uq());
} else {
costSplitAvailable = false;
}
return this;
}
long tr() { return nonNeg(costsGp); }
long uq() { return nonNeg(suppliesCostsGp); }
long auk() { return netGp == Long.MIN_VALUE ? Long.MAX_VALUE : nonNeg(-netGp); }
}
