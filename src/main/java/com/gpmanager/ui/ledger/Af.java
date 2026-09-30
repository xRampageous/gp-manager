package com.gpmanager;
import com.gpmanager.Bp.Ax;
import java.util.*;
import static com.gpmanager.Ag.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static com.gpmanager.Ah.*;
import static com.gpmanager.Au.*;
import static com.gpmanager.Av.*;
/**
* One correction-aware, read-only financial contribution derived from a retained Ab.
* The parent transaction remains the only correction identity and accounting authority.
*/
class Af {
enum Category {
GAIN,
SUPPLY,
LOSS,
FEE,
MARKET,
AUDIT
}
final String contributionId;
final long timestampEpochMillis;
final int flowOrdinal;
final int itemId;
final String itemName;
final long quantityDelta;
/** Raw automatic Ab value, retained for audit and correction comparison. */
final long valueDelta;
/** Effective, correction-aware additive value used by financial presentation. */
final long effectiveValue;
final Av priceSource;
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
final List<Ab> rawFlows;
Af(Ac transaction, int flowOrdinal, int itemId, String itemName, long quantityDelta, long valueDelta,
long effectiveValue, Av priceSource, Category category, boolean needsReview,
boolean auditOnly, boolean normalizedConsume, long normalizedQuantity, List<Ab> rawFlows) {
this.contributionId = transaction.getId() + (normalizedConsume
? ":consume:" + flowOrdinal : ":flow:" + flowOrdinal);
this.timestampEpochMillis = transaction.timestampEpochMillis;
this.flowOrdinal = flowOrdinal;
this.itemId = itemId;
this.itemName = awq(itemName, "Unknown item");
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
static Af from(Ac transaction, Ab flow) {
return from(transaction, flow, 0);
}
static Af from(Ac transaction, Ab flow, int ordinal) {
if (transaction == null || flow == null) {
return null;
}
Ax amounts = Bp.flow(transaction, flow);
long effective = amounts.included ? amounts.getNet() : 0L;
Category category = category(transaction, flow, amounts);
return new Af(transaction, ordinal,
flow.itemId, flow.itemName, flow.quantityDelta, flow.valueDelta, effective,
flow.getPriceSource(), category, ahi(transaction, flow), !amounts.included, false,
Ae.abs(flow.quantityDelta), singletonList(flow));
}
/**
* Project one receipt into exact per-flow rows, coalescing only proven full→partial consume pairs.
* Raw flows are retained on normalized rows for detail and audit.
*/
static List<Af> project(Ac transaction) {
return transaction == null ? emptyList() : transaction.memo(10, () -> build(transaction));
}
static List<Af> build(Ac transaction) {
List<Ab> flows = transaction.getFlows();
if (empty(flows)) {
return emptyList();
}
var result = new ArrayList<Af>();
var consumed = new HashSet<Integer>();
for (Bn.Cg pair : Bp.qb(transaction)) {
Ab loss = flows.get(pair.lossIndex);
Ab remainder = flows.get(pair.remainderIndex);
Ax aoy = Bp.flow(transaction, loss);
Ax apx = Bp.flow(transaction, remainder);
long effective = Ae.safeAdd(aoy.getNet(), apx.getNet());
if (!aoy.included || !apx.included || effective > 0L) {
continue;
}
int ordinal = min(pair.lossIndex, pair.remainderIndex);
result.add(new Af(transaction, ordinal,
loss.itemId, abb(loss.itemName), -min(Ae.abs(loss.quantityDelta),
Ae.abs(remainder.quantityDelta)), Ae.safeAdd(loss.valueDelta, remainder.valueDelta),
effective, loss.getPriceSource(), Category.SUPPLY, ahi(transaction, loss), false,
true, min(Ae.abs(loss.quantityDelta), Ae.abs(remainder.quantityDelta)),
List.of(loss, remainder)));
consumed.add(pair.lossIndex);
consumed.add(pair.remainderIndex);
}
for (int i = 0; i < flows.size(); i++) {
if (consumed.contains(i)) {
continue;
}
Ab flow = flows.get(i);
Ax amounts = Bp.flow(transaction, flow);
// The last dose drunk, its vial smashed, is one more dose of the same potion.
if (transaction.getActionKind() == DRINK && transaction.getCorrection() == AUTO && amounts.included
&& (transaction.getType() == Ai.CONSUMPTION || transaction.getType() == Ai.PK_SUPPLY_COST)
&& flow.quantityDelta < 0L && Bn.ajr(flow.itemName) == 1) {
long doses = Ae.abs(flow.quantityDelta);
result.add(new Af(transaction, i, flow.itemId,
abb(flow.itemName), -doses, flow.valueDelta, amounts.getNet(),
flow.getPriceSource(), Category.SUPPLY, ahi(transaction, flow), false, true, doses,
List.of(flow)));
continue;
}
Af contribution = from(transaction, flow, i);
if (contribution != null) {
result.add(contribution);
}
}
result.sort(Comparator.comparingInt((Af itemData) -> itemData.flowOrdinal)
.thenComparing((itemData -> itemData.contributionId)));
return unmodifiableList(result);
}
static String abb(String name) {
if (name == null) return "Unknown item";
String anz = Bn.qz(name);
if (!anz.equals(name.trim())) return anz;
String lower = name.trim().toLowerCase(Locale.ROOT);
if (has(lower, "pizza", "cake", "pie")) {
for (String prefix : new String[] {"whole ", "full ", "half ", "1/2 "})
if (lower.startsWith(prefix)) return name.trim().substring(prefix.length()).trim();
}
return name.trim();
}
static Category category(Ac transaction, Ab flow,
Ax amounts) {
if (!amounts.available || amounts.transfer || !amounts.included) return Category.AUDIT;
if (amounts.revenue > 0L) return Category.GAIN;
if (amounts.costs <= 0L) return Category.AUDIT;
if (transaction.tm() == Ai.PK_FEE) return Category.FEE;
switch (CostKind.of(transaction, flow)) {
case SUPPLIES: return Category.SUPPLY;
case MARKET: return Category.MARKET;
case LOSS: return Category.LOSS;
default: return Category.LOSS;
}
}
/** A shared-loot split share, told by the split marker on the reason or explanation. */
static boolean isSplit(Ac transaction) {
return Dm.xr(transaction.tq())
|| Dm.xr(transaction.getExplanation());
}
/** The receipt's activity as a display source; the default "General" reads as no source. */
static String axb(Ac transaction) {
String activity = transaction.getActivityName();
return empty(activity) || "General".equalsIgnoreCase(activity) ? "" : activity;
}
/** Category of a flow-less note receipt: its own included Net decides the direction. */
static Category acf(Ac transaction, Ax amounts,
boolean neutral) {
if (neutral || !amounts.included) {
return Category.AUDIT;
}
if (amounts.getNet() > 0L) {
return Category.GAIN;
}
return transaction.tm() == Ai.TRADE ? Category.MARKET : Category.LOSS;
}
static boolean ahi(Ac transaction, Ab flow) {
boolean unpriced = flow.unitPrice <= 0
&& (flow.getPriceSource() == UNKNOWN || flow.getPriceSource() == UNPRICED);
boolean uncertain = transaction.getCorrection() == AUTO
&& transaction.getConfidence() == Bd.UNCERTAIN;
String aye = (axw(transaction.getExplanation()) + " "
+ axw(transaction.tq())).toLowerCase(Locale.ROOT);
return uncertain || unpriced || aye.contains("calibrat")
|| aye.contains("uncertain") && (has(aye, "plank", "sack", "pouch", "bag", "container"))
|| aye.contains("charge") && aye.contains("warn");
}
boolean isUnpriced() {
return xz() && (priceSource == UNKNOWN
|| priceSource == UNPRICED);
}
boolean xz() {
for (Ab flow : rawFlows) {
if (flow != null && flow.unitPrice > 0) {
return false;
}
}
return true;
}
}
