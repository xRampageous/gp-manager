package com.gpmanager;
import com.gpmanager.ActionEvidence.PartialConsumePair;
import java.util.List;
import lombok.*;
import static com.gpmanager.SafeMath.*;
/** Shared correction-aware projection for accounting read models and exports. */
class AccountingProjection {
static TransactionAmounts transaction(Transaction transaction) {
if (transaction == null) return TransactionAmounts.unavailable();
return transaction.memo(8, () -> amounts(transaction));
}

static TransactionAmounts amounts(Transaction transaction) {
if (transaction.getType() == TransactionType.TRANSFER || transaction.getCorrection() == Correction.TRANSFER) {
return TransactionAmounts.transfer();
}
if (!transaction.isCounted()) return TransactionAmounts.excluded();
List<Flow> flows = transaction.getFlows();
if (ModelText.empty(flows)) {
return TransactionAmounts.included(transaction.getRevenue(), transaction.getCosts(), 0L, 0L);
}
long autoRevenue = 0L;
long autoCosts = 0L;
long gross = 0L;
boolean included = false;
for (Flow flow : flows) {
if (flow == null) continue;
included = true;
long value = flow.valueDelta;
if (value > 0L) autoRevenue = safeAdd(autoRevenue, value);
else if (value < 0L) autoCosts = safeAdd(autoCosts, abs(value));
gross = safeAdd(gross, abs(value));
}
if (!included) return TransactionAmounts.excluded();
switch (transaction.getCorrection()) {
case REVENUE:
return TransactionAmounts.included(gross, 0L, autoRevenue, autoCosts);
case COST:
return TransactionAmounts.included(0L, gross, autoRevenue, autoCosts);
case IGNORE:
return TransactionAmounts.excluded();
case TRANSFER:
return TransactionAmounts.transfer();
case AUTO:
default:
long[] steps = consumeSteps(transaction);
long step = 0L;
for (int i = 0; i < flows.size(); i++) {
if (flows.get(i) != null && flows.get(i).valueDelta > 0L) step = safeAdd(step, steps[i]);
}
step = Math.min(step, Math.min(transaction.getRevenue(), transaction.getCosts()));
return TransactionAmounts.included(safeSubtract(transaction.getRevenue(), step),
safeSubtract(transaction.getCosts(), step), autoRevenue, autoCosts);
}
}

/**
* Owner 2026-09-28: a sip or bite steps an item down ((4) to (3), pie to half pie). The part left
* is no gain and the whole potion no cost; only the step is, as the Ledger pairs it. Per flow, the
* value both legs of each such pair give up (0 elsewhere, and always 0 under a correction).
*/
static long[] consumeSteps(Transaction transaction) {
return transaction.memo(6, () -> steps(transaction));
}

static long[] steps(Transaction transaction) {
List<Flow> flows = transaction.getFlows();
long[] steps = new long[flows.size()];
if (transaction.getCorrection() != Correction.AUTO) return steps;
for (PartialConsumePair pair : consumePairs(transaction)) {
long left = flows.get(pair.remainderIndex).valueDelta;
if (left > 0L && safeAdd(flows.get(pair.lossIndex).valueDelta, left) <= 0L) {
steps[pair.lossIndex] = left;
steps[pair.remainderIndex] = left;
}
}
return steps;
}

/** One consume gate for accounting and item presentation; transfers/decants keep raw quotes. */
static List<PartialConsumePair> consumePairs(Transaction transaction) {
List<Flow> flows = transaction.getFlows();
ActionKind action = transaction.getActionKind();
// Cheapest facts first: this runs for every flow of every receipt on each refresh (release pass).
if (!transaction.isCounted() || transaction.getCorrection() != Correction.AUTO
|| transaction.getType() != TransactionType.CONSUMPTION && transaction.getType() != TransactionType.PK_SUPPLY_COST
|| action != null && action != ActionKind.DRINK && action != ActionKind.EAT && action != ActionKind.SUPPLIES
|| ActionEvidence.isDecant(flows)) return java.util.Collections.emptyList();
var pairs = new java.util.ArrayList<PartialConsumePair>();
for (PartialConsumePair pair : ActionEvidence.partialConsumePairs(flows)) {
if (flows.get(pair.lossIndex).getPriceSource() == flows.get(pair.remainderIndex).getPriceSource())
pairs.add(pair);
}
return pairs;
}

/** Returns the additive accounting contribution of a single item-flow row. */
static TransactionAmounts flow(Transaction transaction, Flow flow) {
if (transaction == null || flow == null) return TransactionAmounts.unavailable();
if (transaction.getType() == TransactionType.TRANSFER || transaction.getCorrection() == Correction.TRANSFER) {
return TransactionAmounts.transfer();
}
if (!transaction.isCounted() || transaction.getCorrection() == Correction.IGNORE) {
return TransactionAmounts.excluded();
}
long value = flow.valueDelta;
long autoRevenue = value > 0L ? value : 0L;
long autoCosts = value < 0L ? abs(value) : 0L;
switch (transaction.getCorrection()) {
case REVENUE:
return TransactionAmounts.included(abs(value), 0L, autoRevenue, autoCosts);
case COST:
return TransactionAmounts.included(0L, abs(value), autoRevenue, autoCosts);
case IGNORE:
return TransactionAmounts.excluded();
case TRANSFER:
return TransactionAmounts.transfer();
case AUTO:
default:
List<Flow> flows = transaction.getFlows();
long step = 0L;
for (int i = 0; i < flows.size(); i++) {
if (flows.get(i) == flow) step = consumeSteps(transaction)[i];
}
return TransactionAmounts.included(safeSubtract(autoRevenue, value > 0L ? step : 0L),
safeSubtract(autoCosts, value < 0L ? step : 0L), autoRevenue, autoCosts);
}
}

/** Correction-aware supplies/other split shared by every retained money fold. */
static CostSplit costSplit(Transaction transaction) {
return transaction.memo(9, () -> split(transaction));
}

static CostSplit split(Transaction transaction) {
TransactionAmounts total = transaction(transaction);
if (!total.available) return new CostSplit(0L, 0L, false);
if (!total.included || total.costs <= 0L) return new CostSplit(0L, 0L, true);
if (transaction.getFlows().isEmpty()) return new CostSplit(0L, 0L, false);
long supplies = 0L;
long other = 0L;
for (Flow flow : transaction.getFlows()) {
if (flow == null) continue;
TransactionAmounts amounts = flow(transaction, flow);
if (!amounts.available) return new CostSplit(0L, 0L, false);
if (!amounts.included || amounts.costs <= 0L) continue;
switch (CostKind.of(transaction, flow)) {
case SUPPLIES: supplies = safeAdd(supplies, amounts.costs); break;
case LOSS:
case MARKET: other = safeAdd(other, amounts.costs); break;
default: return new CostSplit(0L, 0L, false);
}
}
return new CostSplit(supplies, other, safeAdd(supplies, other) == total.costs);
}

/**
* The correction-aware fold of the counted Market-context population. Market membership is the
* authoritative typed predicate {@code automaticType == TRADE}; corrections change effective
* amounts but never membership. Whole transactions are folded here so callers can remove their
* gross legs from ordinary categories without re-deriving economics.
*/
static MarketFold marketFold(List<Transaction> transactions) {
long revenue = 0L;
long costs = 0L;
long supplies = 0L;
long other = 0L;
boolean splitAvailable = true;
int counted = 0;
if (transactions == null) return new MarketFold(0L, 0L, 0L, 0L, true, 0);
for (Transaction transaction : transactions) {
if (transaction == null || transaction.getAutomaticType() != TransactionType.TRADE) continue;
TransactionAmounts amounts = transaction(transaction);
if (!amounts.included) continue;
counted++;
revenue = safeAdd(revenue, amounts.revenue);
costs = safeAdd(costs, amounts.costs);
CostSplit split = costSplit(transaction);
if (!split.available) {
splitAvailable = false;
continue;
}
supplies = safeAdd(supplies, split.supplies);
other = safeAdd(other, split.other);
}
return new MarketFold(revenue, costs, splitAvailable ? supplies : 0L,
splitAvailable ? other : 0L, splitAvailable, counted);
}

@AllArgsConstructor
static class MarketFold {
final long revenue;
final long costs;
final long supplies;
final long other;
final boolean splitAvailable;
/** Counted Market-context transactions behind this fold. */
final int transactions;
long getNet() {
return safeSubtract(revenue, costs);
}
}

@AllArgsConstructor
static class TransactionAmounts {
final boolean available;
final boolean included;
final boolean transfer;
final long revenue;
final long costs;
final long automaticRevenue;
final long automaticCosts;
static TransactionAmounts included(long revenue, long costs, long automaticRevenue, long automaticCosts) {
return new TransactionAmounts(true, true, false, revenue, costs, automaticRevenue, automaticCosts);
}
static TransactionAmounts excluded() {
return new TransactionAmounts(true, false, false, 0L, 0L, 0L, 0L);
}
static TransactionAmounts transfer() {
return new TransactionAmounts(true, false, true, 0L, 0L, 0L, 0L);
}
static TransactionAmounts unavailable() {
return new TransactionAmounts(false, false, false, 0L, 0L, 0L, 0L);
}
long getNet() { return safeSubtract(revenue, costs); }
long getAutomaticNet() { return safeSubtract(automaticRevenue, automaticCosts); }
}

@AllArgsConstructor
static class CostSplit {
final long supplies;
final long other;
final boolean available;
}
}
