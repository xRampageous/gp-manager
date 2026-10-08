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

PkMoney(long /* netGp */a, long /* costsGp */b, long /* suppliesCostsGp */c, boolean /* costSplitAvailable */d) {
 this.netGp = a;
 this.costsGp = nonNeg(b);
 this.suppliesCostsGp = nonNeg(c);
 this.costSplitAvailable = d;
}

/** A receipt's correction-aware PvP money; an excluded or unavailable receipt is zero. */
static PkMoney of(Ac /* transaction */a) {
 Bp.Ax /* amounts */b = Bp.transaction(a);
 if (!b.available || !b.included) return new PkMoney(0L, 0L, 0L, true);
 if (b.costs <= 0L) return new PkMoney(b.getNet(), 0L, 0L, true);
 Bp.Du /* split */c = Bp.costSplit(a);
 return new PkMoney(b.getNet(), b.costs, c.supplies, c.available);
}

/** Adds another amount; its supplies count only when its own split is known. */
PkMoney plus(PkMoney /* other */a) {
 netGp = safeAdd(netGp, a.netGp);
 costsGp = safeAdd(costsGp, a.tr());
 if (a.costSplitAvailable) suppliesCostsGp = safeAdd(suppliesCostsGp, a.uq());
 else costSplitAvailable = false;
 return this;
}

long tr() { return nonNeg(costsGp); }
long uq() { return nonNeg(suppliesCostsGp); }
long auk() { return netGp == Long.MIN_VALUE ? Long.MAX_VALUE : nonNeg(-netGp); }
}
