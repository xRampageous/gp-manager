package com.gpmanager;
import com.gpmanager.Bn.Cg;
import java.util.List;
import lombok.*;
import static com.gpmanager.Ae.*;
/** Shared correction-aware projection for accounting read models and exports. */
class Bp {
static Ax transaction(Ac /* transaction */a) {
 if (a == null) return Ax.unavailable();
 return a.memo(8, () -> amounts(a));
}

static Ax amounts(Ac /* transaction */a) {
 if (a.getType() == Ai.TRANSFER || a.getCorrection() == Ah.TRANSFER) {
  return Ax.transfer();
 }
 if (!a.isCounted()) return Ax.excluded();
 List<Ab> /* flows */b = a.getFlows();
 if (Ag.empty(b)) {
  return Ax.included(a.getRevenue(), a.getCosts(), 0L, 0L);
 }
 long /* autoRevenue */c = 0L;
 long /* autoCosts */d = 0L;
 long /* gross */e = 0L;
 boolean /* included */f = false;
 for (Ab /* flow */g : b) {
  if (g == null) continue;
  f = true;
  long /* value */h = g.valueDelta;
  if (h > 0L) c = safeAdd(c, h);
  else if (h < 0L) d = safeAdd(d, abs(h));
  e = safeAdd(e, abs(h));
 }
 if (!f) return Ax.excluded();
 switch (a.getCorrection()) {
  case REVENUE:
  return Ax.included(e, 0L, c, d);
  case COST:
  return Ax.included(0L, e, c, d);
  case IGNORE:
  return Ax.excluded();
  case TRANSFER:
  return Ax.transfer();
  case AUTO:
  default:
  long[] /* steps */j = qg(a);
  long /* step */k = 0L;
  for (int i = 0; i < b.size(); i++) {
   if (b.get(i) != null && b.get(i).valueDelta > 0L) k = safeAdd(k, j[i]);
  }
  k = Math.min(k, Math.min(a.getRevenue(), a.getCosts()));
  return Ax.included(aha(a.getRevenue(), k),
  aha(a.getCosts(), k), c, d);
 }
}

/**
* Owner 2026-09-28: a sip or bite steps an item down ((4) to (3), pie to half pie). The part left
* is no gain and the whole potion no cost; only the step is, as the Ledger pairs it. Per flow, the
* value both legs of each such pair give up (0 elsewhere, and always 0 under a correction).
*/
static long[] qg(Ac /* transaction */a) {
 return a.memo(6, () -> steps(a));
}

static long[] steps(Ac /* transaction */a) {
 List<Ab> /* flows */b = a.getFlows();
 long[] /* steps */c = new long[b.size()];
 for (Cg /* pair */d : qb(a)) {
  long /* left */e = b.get(d.remainderIndex).valueDelta;
  if (e > 0L && safeAdd(b.get(d.lossIndex).valueDelta, e) <= 0L) {
   c[d.lossIndex] = e;
   c[d.remainderIndex] = e;
  }
 }
 return c;
}

/** One consume gate for accounting and item presentation; transfers/decants keep raw quotes. */
static List<Cg> qb(Ac /* transaction */a) {
 List<Ab> /* flows */b = a.getFlows();
 Au /* action */c = a.getActionKind();
 // Cheapest facts first: this runs for every flow of every receipt on each refresh (release pass).
 if (!a.isCounted() || a.getCorrection() != Ah.AUTO
 || a.getType() != Ai.CONSUMPTION && a.getType() != Ai.PK_SUPPLY_COST
 || c != null && c != Au.DRINK && c != Au.EAT && c != Au.SUPPLIES
 || Bn.isDecant(b)) return java.util.Collections.emptyList();
 var /* pairs */d = new java.util.ArrayList<Cg>();
 for (Cg /* pair */e : Bn.acr(b)) {
  if (b.get(e.lossIndex).getPriceSource() == b.get(e.remainderIndex).getPriceSource())
  d.add(e);
 }
 return d;
}

/** Returns the additive accounting contribution of a single item-flow row. */
static Ax flow(Ac /* transaction */a, Ab /* flow */b) {
 if (a == null || b == null) return Ax.unavailable();
 if (a.getType() == Ai.TRANSFER || a.getCorrection() == Ah.TRANSFER) {
  return Ax.transfer();
 }
 if (!a.isCounted() || a.getCorrection() == Ah.IGNORE) {
  return Ax.excluded();
 }
 long /* value */c = b.valueDelta;
 long /* autoRevenue */d = c > 0L ? c : 0L;
 long /* autoCosts */e = c < 0L ? abs(c) : 0L;
 switch (a.getCorrection()) {
  case REVENUE:
  return Ax.included(abs(c), 0L, d, e);
  case COST:
  return Ax.included(0L, abs(c), d, e);
  case IGNORE:
  return Ax.excluded();
  case TRANSFER:
  return Ax.transfer();
  case AUTO:
  default:
  List<Ab> /* flows */f = a.getFlows();
  long /* step */g = 0L;
  for (int i = 0; i < f.size(); i++) {
   if (f.get(i) == b) g = qg(a)[i];
  }
  return Ax.included(aha(d, c > 0L ? g : 0L),
  aha(e, c < 0L ? g : 0L), d, e);
 }
}

/** Ah-aware supplies/other split shared by every retained money fold. */
static Du costSplit(Ac /* transaction */a) {
 return a.memo(9, () -> split(a));
}

static Du split(Ac /* transaction */a) {
 Ax /* total */b = transaction(a);
 if (!b.available) return new Du(0L, 0L, false);
 if (!b.included || b.costs <= 0L) return new Du(0L, 0L, true);
 if (a.getFlows().isEmpty()) return new Du(0L, 0L, false);
 long /* supplies */c = 0L;
 long /* other */d = 0L;
 for (Ab /* flow */e : a.getFlows()) {
  if (e == null) continue;
  Ax /* amounts */f = flow(a, e);
  if (!f.available) return new Du(0L, 0L, false);
  if (!f.included || f.costs <= 0L) continue;
  switch (CostKind.of(a, e)) {
   case SUPPLIES: c = safeAdd(c, f.costs); break;
   case LOSS:
   case MARKET: d = safeAdd(d, f.costs); break;
   default: return new Du(0L, 0L, false);
  }
 }
 return new Du(c, d, safeAdd(c, d) == b.costs);
}

/**
* The correction-aware fold of the counted Market-context population. Market membership is the
* authoritative typed predicate {@code automaticType == TRADE}; corrections change effective
* amounts but never membership. Whole transactions are folded here so callers can remove their
* gross legs from ordinary categories without re-deriving economics.
*/
static Dy zl(List<Ac> /* transactions */a) {
 long /* revenue */b = 0L;
 long /* costs */c = 0L;
 long /* supplies */d = 0L;
 long /* other */e = 0L;
 boolean /* splitAvailable */f = true;
 int /* counted */g = 0;
 if (a == null) return new Dy(0L, 0L, 0L, 0L, true, 0);
 for (Ac /* transaction */h : a) {
  if (h == null || h.tm() != Ai.TRADE) continue;
  Ax /* amounts */i = transaction(h);
  if (!i.included) continue;
  g++;
  b = safeAdd(b, i.revenue);
  c = safeAdd(c, i.costs);
  Du /* split */j = costSplit(h);
  if (!j.available) {
   f = false;
   continue;
  }
  d = safeAdd(d, j.supplies);
  e = safeAdd(e, j.other);
 }
 return new Dy(b, c, f ? d : 0L,
 f ? e : 0L, f, g);
}

@AllArgsConstructor
static class Dy {
 long revenue;
 long costs;
 long supplies;
 long other;
 boolean splitAvailable;
 /** Counted Market-context transactions behind this fold. */
 int transactions;
 long getNet() {
  return aha(revenue, costs);
 }
}

@AllArgsConstructor
static class Ax {
 boolean available;
 boolean included;
 boolean transfer;
 long revenue;
 long costs;
 long automaticRevenue;
 long automaticCosts;
 static Ax included(long /* revenue */b, long /* costs */c, long /* automaticRevenue */d, long /* automaticCosts */e) {
  return new Ax(true, true, false, b, c, d, e);
 }
 static Ax excluded() {
  return new Ax(true, false, false, 0L, 0L, 0L, 0L);
 }
 static Ax transfer() {
  return new Ax(true, false, true, 0L, 0L, 0L, 0L);
 }
 static Ax unavailable() {
  return new Ax(false, false, false, 0L, 0L, 0L, 0L);
 }
 long getNet() { return aha(revenue, costs); }
 long tl() { return aha(automaticRevenue, automaticCosts); }
}

@AllArgsConstructor
static class Du {
 long supplies;
 long other;
 boolean available;
}
}
