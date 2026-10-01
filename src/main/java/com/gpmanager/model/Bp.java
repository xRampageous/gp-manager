package com.gpmanager;
import com.gpmanager.Bn.Cg;
import java.util.List;
import lombok.*;
import static com.gpmanager.Ae.*;
/** Shared correction-aware projection for accounting read models and exports. */
class Bp {
static Ax transaction(Ac transaction) {
 if (transaction == null) return Ax.unavailable();
 return transaction.memo(8, () -> amounts(transaction));
}

static Ax amounts(Ac transaction) {
 if (transaction.getType() == Ai.TRANSFER || transaction.getCorrection() == Ah.TRANSFER) {
  return Ax.transfer();
 }
 if (!transaction.isCounted()) return Ax.excluded();
 List<Ab> flows = transaction.getFlows();
 if (Ag.empty(flows)) {
  return Ax.included(transaction.getRevenue(), transaction.getCosts(), 0L, 0L);
 }
 long autoRevenue = 0L;
 long autoCosts = 0L;
 long gross = 0L;
 boolean included = false;
 for (Ab flow : flows) {
  if (flow == null) continue;
  included = true;
  long value = flow.valueDelta;
  if (value > 0L) autoRevenue = safeAdd(autoRevenue, value);
  else if (value < 0L) autoCosts = safeAdd(autoCosts, abs(value));
  gross = safeAdd(gross, abs(value));
 }
 if (!included) return Ax.excluded();
 switch (transaction.getCorrection()) {
  case REVENUE:
  return Ax.included(gross, 0L, autoRevenue, autoCosts);
  case COST:
  return Ax.included(0L, gross, autoRevenue, autoCosts);
  case IGNORE:
  return Ax.excluded();
  case TRANSFER:
  return Ax.transfer();
  case AUTO:
  default:
  long[] steps = qg(transaction);
  long step = 0L;
  for (int i = 0; i < flows.size(); i++) {
   if (flows.get(i) != null && flows.get(i).valueDelta > 0L) step = safeAdd(step, steps[i]);
  }
  step = Math.min(step, Math.min(transaction.getRevenue(), transaction.getCosts()));
  return Ax.included(aha(transaction.getRevenue(), step),
  aha(transaction.getCosts(), step), autoRevenue, autoCosts);
 }
}

/**
* Owner 2026-09-28: a sip or bite steps an item down ((4) to (3), pie to half pie). The part left
* is no gain and the whole potion no cost; only the step is, as the Ledger pairs it. Per flow, the
* value both legs of each such pair give up (0 elsewhere, and always 0 under a correction).
*/
static long[] qg(Ac transaction) {
 return transaction.memo(6, () -> steps(transaction));
}

static long[] steps(Ac transaction) {
 List<Ab> flows = transaction.getFlows();
 long[] steps = new long[flows.size()];
 if (transaction.getCorrection() != Ah.AUTO) return steps;
 for (Cg pair : qb(transaction)) {
  long left = flows.get(pair.remainderIndex).valueDelta;
  if (left > 0L && safeAdd(flows.get(pair.lossIndex).valueDelta, left) <= 0L) {
   steps[pair.lossIndex] = left;
   steps[pair.remainderIndex] = left;
  }
 }
 return steps;
}

/** One consume gate for accounting and item presentation; transfers/decants keep raw quotes. */
static List<Cg> qb(Ac transaction) {
 List<Ab> flows = transaction.getFlows();
 Au action = transaction.getActionKind();
 // Cheapest facts first: this runs for every flow of every receipt on each refresh (release pass).
 if (!transaction.isCounted() || transaction.getCorrection() != Ah.AUTO
 || transaction.getType() != Ai.CONSUMPTION && transaction.getType() != Ai.PK_SUPPLY_COST
 || action != null && action != Au.DRINK && action != Au.EAT && action != Au.SUPPLIES
 || Bn.isDecant(flows)) return java.util.Collections.emptyList();
 var pairs = new java.util.ArrayList<Cg>();
 for (Cg pair : Bn.acr(flows)) {
  if (flows.get(pair.lossIndex).getPriceSource() == flows.get(pair.remainderIndex).getPriceSource())
  pairs.add(pair);
 }
 return pairs;
}

/** Returns the additive accounting contribution of a single item-flow row. */
static Ax flow(Ac transaction, Ab flow) {
 if (transaction == null || flow == null) return Ax.unavailable();
 if (transaction.getType() == Ai.TRANSFER || transaction.getCorrection() == Ah.TRANSFER) {
  return Ax.transfer();
 }
 if (!transaction.isCounted() || transaction.getCorrection() == Ah.IGNORE) {
  return Ax.excluded();
 }
 long value = flow.valueDelta;
 long autoRevenue = value > 0L ? value : 0L;
 long autoCosts = value < 0L ? abs(value) : 0L;
 switch (transaction.getCorrection()) {
  case REVENUE:
  return Ax.included(abs(value), 0L, autoRevenue, autoCosts);
  case COST:
  return Ax.included(0L, abs(value), autoRevenue, autoCosts);
  case IGNORE:
  return Ax.excluded();
  case TRANSFER:
  return Ax.transfer();
  case AUTO:
  default:
  List<Ab> flows = transaction.getFlows();
  long step = 0L;
  for (int i = 0; i < flows.size(); i++) {
   if (flows.get(i) == flow) step = qg(transaction)[i];
  }
  return Ax.included(aha(autoRevenue, value > 0L ? step : 0L),
  aha(autoCosts, value < 0L ? step : 0L), autoRevenue, autoCosts);
 }
}

/** Ah-aware supplies/other split shared by every retained money fold. */
static Du costSplit(Ac transaction) {
 return transaction.memo(9, () -> split(transaction));
}

static Du split(Ac transaction) {
 Ax total = transaction(transaction);
 if (!total.available) return new Du(0L, 0L, false);
 if (!total.included || total.costs <= 0L) return new Du(0L, 0L, true);
 if (transaction.getFlows().isEmpty()) return new Du(0L, 0L, false);
 long supplies = 0L;
 long other = 0L;
 for (Ab flow : transaction.getFlows()) {
  if (flow == null) continue;
  Ax amounts = flow(transaction, flow);
  if (!amounts.available) return new Du(0L, 0L, false);
  if (!amounts.included || amounts.costs <= 0L) continue;
  switch (CostKind.of(transaction, flow)) {
   case SUPPLIES: supplies = safeAdd(supplies, amounts.costs); break;
   case LOSS:
   case MARKET: other = safeAdd(other, amounts.costs); break;
   default: return new Du(0L, 0L, false);
  }
 }
 return new Du(supplies, other, safeAdd(supplies, other) == total.costs);
}

/**
* The correction-aware fold of the counted Market-context population. Market membership is the
* authoritative typed predicate {@code automaticType == TRADE}; corrections change effective
* amounts but never membership. Whole transactions are folded here so callers can remove their
* gross legs from ordinary categories without re-deriving economics.
*/
static Dy zl(List<Ac> transactions) {
 long revenue = 0L;
 long costs = 0L;
 long supplies = 0L;
 long other = 0L;
 boolean splitAvailable = true;
 int counted = 0;
 if (transactions == null) return new Dy(0L, 0L, 0L, 0L, true, 0);
 for (Ac transaction : transactions) {
  if (transaction == null || transaction.tm() != Ai.TRADE) continue;
  Ax amounts = transaction(transaction);
  if (!amounts.included) continue;
  counted++;
  revenue = safeAdd(revenue, amounts.revenue);
  costs = safeAdd(costs, amounts.costs);
  Du split = costSplit(transaction);
  if (!split.available) {
   splitAvailable = false;
   continue;
  }
  supplies = safeAdd(supplies, split.supplies);
  other = safeAdd(other, split.other);
 }
 return new Dy(revenue, costs, splitAvailable ? supplies : 0L,
 splitAvailable ? other : 0L, splitAvailable, counted);
}

@AllArgsConstructor
static class Dy {
 final long revenue;
 final long costs;
 final long supplies;
 final long other;
 final boolean splitAvailable;
 /** Counted Market-context transactions behind this fold. */
 final int transactions;
 long getNet() {
  return aha(revenue, costs);
 }
}

@AllArgsConstructor
static class Ax {
 final boolean available;
 final boolean included;
 final boolean transfer;
 final long revenue;
 final long costs;
 final long automaticRevenue;
 final long automaticCosts;
 static Ax included(long revenue, long costs, long automaticRevenue, long automaticCosts) {
  return new Ax(true, true, false, revenue, costs, automaticRevenue, automaticCosts);
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
 final long supplies;
 final long other;
 final boolean available;
}
}
