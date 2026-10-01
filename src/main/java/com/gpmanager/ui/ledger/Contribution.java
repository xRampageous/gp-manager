package com.gpmanager;
import com.gpmanager.AccountingProjection.TransactionAmounts;
import java.util.*;
import static com.gpmanager.ModelText.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static com.gpmanager.Correction.*;
import static com.gpmanager.ActionKind.*;
import static com.gpmanager.PriceSource.*;
/**
* One correction-aware, read-only financial contribution derived from a retained Flow.
* The parent transaction remains the only correction identity and accounting authority.
*/
class Contribution {
enum Category {
GAIN, SUPPLY, LOSS, FEE, MARKET, AUDIT
}

final String contributionId;
final long timestampEpochMillis;
final int flowOrdinal;
final int itemId;
final String itemName;
final long quantityDelta;
/** Raw automatic Flow value, retained for audit and correction comparison. */
final long valueDelta;
/** Effective, correction-aware additive value used by financial presentation. */
final long effectiveValue;
final PriceSource priceSource;
final boolean counted;
final Category category;
/** Transient exact action label captured from trusted client evidence; display/search only. */
final String actionDisplayName;
final boolean needsReview;
final boolean corrected;
final boolean split;
final boolean auditOnly;
final boolean normalizedConsume;
final long normalizedQuantity;
final List<Flow> rawFlows;
Contribution(Transaction transaction, int flowOrdinal, int itemId, String itemName, long quantityDelta, long valueDelta,
long effectiveValue, PriceSource priceSource, Category category, boolean needsReview,
boolean auditOnly, boolean normalizedConsume, long normalizedQuantity, List<Flow> rawFlows) {
this.contributionId = transaction.getId() + (normalizedConsume ? ":consume:" + flowOrdinal : ":flow:" + flowOrdinal);
this.timestampEpochMillis = transaction.timestampEpochMillis;
this.flowOrdinal = flowOrdinal;
this.itemId = itemId;
this.itemName = nonBlank(itemName, "Unknown item");
this.quantityDelta = quantityDelta;
this.valueDelta = valueDelta;
this.effectiveValue = effectiveValue;
this.priceSource = priceSource == null ? UNKNOWN : priceSource;
this.counted = transaction.isCounted();
this.category = category == null ? Category.AUDIT : category;
this.actionDisplayName = transaction.spellName();
this.needsReview = needsReview;
this.corrected = transaction.getCorrection() != null && transaction.getCorrection() != AUTO;
this.split = isSplit(transaction);
this.auditOnly = auditOnly;
this.normalizedConsume = normalizedConsume;
this.normalizedQuantity = normalizedQuantity;
this.rawFlows = unmodifiableList(new ArrayList<>(rawFlows));
}

static Contribution from(Transaction transaction, Flow flow) {
return from(transaction, flow, 0);
}

static Contribution from(Transaction transaction, Flow flow, int ordinal) {
if (transaction == null || flow == null) return null;
TransactionAmounts amounts = AccountingProjection.flow(transaction, flow);
long effective = amounts.included ? amounts.getNet() : 0L;
Category category = category(transaction, flow, amounts);
return new Contribution(transaction, ordinal,
flow.itemId, flow.itemName, flow.quantityDelta, flow.valueDelta, effective,
flow.getPriceSource(), category, reviewState(transaction, flow), !amounts.included, false,
SafeMath.abs(flow.quantityDelta), singletonList(flow));
}

/**
* Project one receipt into exact per-flow rows, coalescing only proven full→partial consume pairs.
* Raw flows are retained on normalized rows for detail and audit.
*/
static List<Contribution> project(Transaction transaction) {
return transaction == null ? emptyList() : transaction.memo(10, () -> build(transaction));
}

static List<Contribution> build(Transaction transaction) {
List<Flow> flows = transaction.getFlows();
if (empty(flows)) return emptyList();
var result = new ArrayList<Contribution>();
var consumed = new HashSet<Integer>();
for (ActionEvidence.PartialConsumePair pair : AccountingProjection.consumePairs(transaction)) {
Flow loss = flows.get(pair.lossIndex);
Flow remainder = flows.get(pair.remainderIndex);
TransactionAmounts lossAmounts = AccountingProjection.flow(transaction, loss);
TransactionAmounts remainderAmounts = AccountingProjection.flow(transaction, remainder);
long effective = SafeMath.safeAdd(lossAmounts.getNet(), remainderAmounts.getNet());
if (!lossAmounts.included || !remainderAmounts.included || effective > 0L) continue;
int ordinal = min(pair.lossIndex, pair.remainderIndex);
result.add(new Contribution(transaction, ordinal,
loss.itemId, normalizedName(loss.itemName), -min(SafeMath.abs(loss.quantityDelta),
SafeMath.abs(remainder.quantityDelta)), SafeMath.safeAdd(loss.valueDelta, remainder.valueDelta),
effective, loss.getPriceSource(), Category.SUPPLY, reviewState(transaction, loss), false,
true, min(SafeMath.abs(loss.quantityDelta), SafeMath.abs(remainder.quantityDelta)), List.of(loss, remainder)));
consumed.add(pair.lossIndex);
consumed.add(pair.remainderIndex);
}
for (int i = 0; i < flows.size(); i++) {
if (consumed.contains(i)) continue;
Flow flow = flows.get(i);
TransactionAmounts amounts = AccountingProjection.flow(transaction, flow);
// The last dose drunk, its vial smashed, is one more dose of the same potion.
if (transaction.getActionKind() == DRINK && transaction.getCorrection() == AUTO && amounts.included
&& (transaction.getType() == TransactionType.CONSUMPTION || transaction.getType() == TransactionType.PK_SUPPLY_COST)
&& flow.quantityDelta < 0L && ActionEvidence.trailingDose(flow.itemName) == 1) {
long doses = SafeMath.abs(flow.quantityDelta);
result.add(new Contribution(transaction, i, flow.itemId,
normalizedName(flow.itemName), -doses, flow.valueDelta, amounts.getNet(),
flow.getPriceSource(), Category.SUPPLY, reviewState(transaction, flow), false, true, doses, List.of(flow)));
continue;
}
Contribution contribution = from(transaction, flow, i);
if (contribution != null) result.add(contribution);
}
result.sort(Comparator.comparingInt((Contribution itemData) -> itemData.flowOrdinal)
.thenComparing((itemData -> itemData.contributionId)));
return unmodifiableList(result);
}

static String normalizedName(String name) {
if (name == null) return "Unknown item";
String doseBase = ActionEvidence.doseBaseName(name);
if (!doseBase.equals(name.trim())) return doseBase;
String lower = name.trim().toLowerCase(Locale.ROOT);
if (has(lower, "pizza", "cake", "pie")) {
for (String prefix : new String[] {"whole ", "full ", "half ", "1/2 "})
if (lower.startsWith(prefix)) return name.trim().substring(prefix.length()).trim();
}
return name.trim();
}

static Category category(Transaction transaction, Flow flow, TransactionAmounts amounts) {
if (!amounts.available || amounts.transfer || !amounts.included) return Category.AUDIT;
if (amounts.revenue > 0L) return Category.GAIN;
if (amounts.costs <= 0L) return Category.AUDIT;
if (transaction.getAutomaticType() == TransactionType.PK_FEE) return Category.FEE;
switch (CostKind.of(transaction, flow)) {
case SUPPLIES: return Category.SUPPLY;
case MARKET: return Category.MARKET;
case LOSS: return Category.LOSS;
default: return Category.LOSS;
}
}

/** A shared-loot split share, told by the split marker on the reason or explanation. */
static boolean isSplit(Transaction transaction) {
return ItemSplitAccounting.isSplitReason(transaction.getCorrectionReason())
|| ItemSplitAccounting.isSplitReason(transaction.getExplanation());
}

/** The receipt's activity as a display source; the default "General" reads as no source. */
static String sourceOf(Transaction transaction) {
String activity = transaction.getActivityName();
return empty(activity) || "General".equalsIgnoreCase(activity) ? "" : activity;
}

/** Category of a flow-less note receipt: its own included Net decides the direction. */
static Category noteCategory(Transaction transaction, TransactionAmounts amounts, boolean neutral) {
if (neutral || !amounts.included) return Category.AUDIT;
if (amounts.getNet() > 0L) return Category.GAIN;
return transaction.getAutomaticType() == TransactionType.TRADE ? Category.MARKET : Category.LOSS;
}

static boolean reviewState(Transaction transaction, Flow flow) {
boolean unpriced = flow.unitPrice <= 0 && (flow.getPriceSource() == UNKNOWN || flow.getPriceSource() == UNPRICED);
boolean uncertain = transaction.getCorrection() == AUTO
&& transaction.getConfidence() == ClassificationConfidence.UNCERTAIN;
String blob = (orEmpty(transaction.getExplanation()) + " "
+ orEmpty(transaction.getCorrectionReason())).toLowerCase(Locale.ROOT);
return uncertain || unpriced || blob.contains("calibrat")
|| blob.contains("uncertain") && (has(blob, "plank", "sack", "pouch", "bag", "container"))
|| blob.contains("charge") && blob.contains("warn");
}

boolean isUnpriced() {
return itemUnitPriceUnavailable() && (priceSource == UNKNOWN || priceSource == UNPRICED);
}

boolean itemUnitPriceUnavailable() {
for (Flow flow : rawFlows) {
if (flow != null && flow.unitPrice > 0) return false;
}
return true;
}
}
