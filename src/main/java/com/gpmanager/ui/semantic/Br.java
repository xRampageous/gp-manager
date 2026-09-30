package com.gpmanager;
import com.gpmanager.Bi.*;
import com.gpmanager.Af.Category;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import lombok.*;
import static com.gpmanager.Ag.*;
import static java.util.Locale.*;
import static java.util.Collections.*;
import static com.gpmanager.Ae.*;
import static com.gpmanager.Ah.*;
import static com.gpmanager.Ai.*;
import static com.gpmanager.Ak.msg;
/**
* The one derived, read-only semantic financial projection every presentation surface shares.
*
* <p>It turns canonical transactions and the derived Market settlement context into living
* semantic groups: repeated compatible activity updates one group, totals stay additive, and no
* group is ever a correction identity. It never books, never reprices and never persists.</p>
*
* <p>Grouping laws:</p>
* <ul>
*   <li>Exact named {@code Cast} groups key on the proven spell name; different names never
*       merge and exact never merges with generic.</li>
*   <li>Generic action groups key on a canonical input-composition signature (action kind,
*       financial category, sorted resource item ids, a scale-normalised quantity ratio where it
*       is safely derivable, and price/coverage compatibility). The signature is presentation
*       grouping only and is never rendered as a spell name; ambiguous composition splits
*       instead of over-merging.</li>
*   <li>Measured charge receipts become their actual booked resource rows with a
*       {@code Used by <equipment>} context. The equipment is never a financial row and no
*       component ever carries the whole parent Net.</li>
*   <li>Action composites require pure counted cost contributions of one financial category:
*       no Gain, Market, Transfer, Review, Corrected, split or claim-linked row may collapse
*       into one.</li>
*   <li>Unpriced/incomplete groups stay in their financial table with an incomplete marker;
*       only transactions that independently need an owner decision are review-required.</li>
*   <li>Sort truth is {@code latestActivityAt DESC} with a stable semantic-group-id tie-break.</li>
* </ul>
*/
class Br {
/** Financial table destination for the future stacked Ledger and the Live consumer. */
enum Table {
GAINS,
COSTS_SUPPLIES,
COSTS_LOSS,
MARKET,
AUDIT
}
/** Value coverage: an incomplete group never presents itself as an exact headline. */
enum Coverage {
COMPLETE,
INCOMPLETE
}
/**
* One exact canonical contribution receipt behind a group; the only correction target layer.
* Built once by the projection and shared by the Ledger tables, the receipt list and the
* Corrected audit.
*/
@AllArgsConstructor
static class Receipt {
final String contributionId;
final String transactionId;
final long at;
final int itemId;
final String itemName;
/** Semantic magnitude where known; for measured charge spends this is the normalized quantity. */
final long quantity;
/** Effective, correction-aware additive value; 0 when the receipt is neutral or unpriced. */
final long value;
/** Raw automatic value, retained for audit and correction comparison. */
final long automaticValue;
final boolean unpriced;
final boolean neutral;
final boolean review;
final boolean corrected;
final boolean split;
final Category category;
final String verb;
final String actionDisplayName;
final String why;
final String qj;
final String source;
/** Measured-charge equipment context ("Toxic blowpipe"), or empty for ordinary resources. */
final String usedBy;
/** Charges: the exact captured unit price and its source; empty for other receipts (F11). */
final String priceBasis;
public final Row marketSettlement;
boolean gain() {
return category == Category.GAIN;
}
/** The Corrected audit search; financial table search uses the projection group instead. */
boolean matchesSearch(String needle) {
if (empty(needle)) {
return true;
}
String lower = needle.toLowerCase(ROOT);
return itemName.toLowerCase(ROOT).contains(lower)
|| verb.toLowerCase(ROOT).contains(lower)
|| actionDisplayName.toLowerCase(ROOT).contains(lower)
|| source.toLowerCase(ROOT).contains(lower)
|| why.toLowerCase(ROOT).contains(lower)
|| qj.toLowerCase(ROOT).contains(lower)
|| transactionId.toLowerCase(ROOT).contains(lower)
|| contributionId.toLowerCase(ROOT).contains(lower);
}
}
/**
* One semantic presentation group; never a correction identity and never persisted. Each unit
* of activity starts as a group; units sharing a merge key agree on everything the key encodes
* (table, flags, consume shape), so the newest keeps those and later ones only add value, time,
* quantity, receipts and search terms.
*/
static class Group {
final String semanticGroupId;
/** Display item id for the row sprite; -1 for action composites. */
final int itemId;
final Table table;
final String primaryName;
/** The honest secondary line: "Used by Toxic blowpipe", "3 rune types", "Sell". */
final String qe;
final String actionLabel;
/** Measured-charge equipment context ("Toxic blowpipe"), or empty for ordinary rows. */
final String usedBy;
/** Measured or estimated charge use: the Losses Charges chip, never a Supplies row. */
final boolean chargeUse;
long latestActivityAt;
long value;
Coverage coverage;
/** Semantic quantity where honestly known; -1 when it cannot be stated. */
long quantity;
/** +1 claim pickup, -1 claim spend, 0 for an ordinary resource row. */
final int claim;
final boolean normalizedConsume;
int receiptCount = 1;
final boolean composite;
final boolean neutral;
final boolean market;
final boolean reviewRequired;
final boolean corrected;
final String marketPresentationId;
final String representativeTransactionId;
final String representativeContributionId;
public final Set<String> receiptIds = new LinkedHashSet<>();
public final List<Receipt> receipts = new ArrayList<>();
public final Set<String> searchTerms = new LinkedHashSet<>();
int units = 1;
Group(String mergeKey, int itemId, Table table, String primaryName, String qe, String actionLabel,
String usedBy, boolean chargeUse, long at, long value, Coverage coverage, long quantity, boolean normalizedConsume,
boolean composite, boolean neutral, boolean market, boolean reviewRequired, boolean corrected,
String marketPresentationId, String transactionId, String representativeContributionId,
int claim) {
if (GROUP_IDS.size() > 50_000) {
GROUP_IDS.clear();
}
this.semanticGroupId = GROUP_IDS.computeIfAbsent(mergeKey,
key -> "sg:" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)));
this.itemId = itemId;
this.table = table;
this.primaryName = primaryName;
this.qe = qe;
this.actionLabel = actionLabel;
this.usedBy = usedBy;
this.chargeUse = chargeUse;
this.latestActivityAt = at;
this.value = value;
this.coverage = coverage;
this.quantity = quantity;
this.normalizedConsume = normalizedConsume;
this.composite = composite;
this.neutral = neutral;
this.market = market;
this.reviewRequired = reviewRequired;
this.corrected = corrected;
this.marketPresentationId = marketPresentationId;
this.representativeTransactionId = transactionId;
this.representativeContributionId = representativeContributionId;
this.claim = claim;
receiptIds.add(transactionId.isEmpty() ? representativeContributionId : transactionId);
receiptIds.remove("");
}
/** Adds a later unit with the same merge key. */
Group absorb(Group unit) {
value = safeAdd(value, unit.value);
latestActivityAt = Math.max(latestActivityAt, unit.latestActivityAt);
coverage = unit.coverage == Coverage.INCOMPLETE ? Coverage.INCOMPLETE : coverage;
quantity = quantity < 0L || unit.quantity < 0L ? -1L : safeAdd(quantity, unit.quantity);
units++;
receiptIds.addAll(unit.receiptIds);
receiptCount = receiptIds.isEmpty() ? units : receiptIds.size();
receipts.addAll(unit.receipts);
searchTerms.addAll(unit.searchTerms);
return this;
}
boolean incomplete() {
return coverage == Coverage.INCOMPLETE;
}
/** True when this group owns the given semantic group or contribution identity. */
boolean containsContribution(String id) {
if (empty(id)) {
return false;
}
if (semanticGroupId.equals(id)) {
return true;
}
for (Receipt receipt : receipts) {
if (id.equals(receipt.contributionId)) {
return true;
}
}
return false;
}
/** True when this group represents the given canonical transaction identity. */
boolean containsTransaction(String id) {
if (empty(id)) {
return false;
}
if (id.equals(representativeTransactionId)) {
return true;
}
if (receiptIds.contains(id)) {
return true;
}
for (Receipt receipt : receipts) {
if (id.equals(receipt.transactionId)) {
return true;
}
}
return false;
}
/** True when this group is a composite row (action or note), not a resource row. */
boolean actionGroup() {
return composite && !market;
}
/**
* Prepared for PRE-R5C.2B: text search decides whether a COMPLETE group matches by
* looking at group metadata and underlying resources; a match keeps the full total.
*/
boolean matchesText(String query) {
if (query == null) {
return true;
}
String needle = query.trim().toLowerCase(ROOT);
if (needle.isEmpty()) {
return true;
}
for (String term : searchTerms) {
if (term != null && term.toLowerCase(ROOT).contains(needle)) {
return true;
}
}
return false;
}
}
/** The full derived read: the groups, newest first. */
static class Result {
final List<Group> groups;
Result(List<Group> groups) {
this.groups = unmodifiableList(groups);
}
}
/**
* Reconciled financial counts over one group set. `entries` counts financial groups across
* Gains + Costs + Market (review-required and Audit rows excluded); `receipts` counts the
* unique counted canonical receipt identities those entries represent (neutral transfers,
* pending/unrealised Market and Review-only receipts are excluded).
*/
@AllArgsConstructor
static class Summary {
/** Sum of financial Gain groups (review-required rows excluded). */
final long gains;
/** Sum of financial cost groups, negative (review-required rows excluded). */
final long costs;
/** Sum of realised Market results (review-required and unrealised rows excluded). */
final long market;
final int entries;
final int receipts;
/** True when every counted financial group had complete value coverage. */
final boolean coverageComplete;
/** Canonical Net this summary accounts for when coverage is complete. */
long net() {
return safeAdd(safeAdd(gains, costs), market);
}
}
/** Stable group ids by merge key; hashing is paid once per key, not on every tick. */
static final Map<String, String> GROUP_IDS = new java.util.concurrent.ConcurrentHashMap<>();
/** Pure capture over explicit data; used by Live, tests, previews and the Ledger. */
static Result capture(List<Ac> transactions,
List<Row> marketRows, String sessionId,
Predicate<Ab> td) {
Set<String> sessions = sessionId == null || sessionId.isEmpty()
? emptySet() : singleton(sessionId);
return capture(transactions, marketRows, sessions, td);
}
/** Multi-session scope: Today and future period consumers merge compatible activity. */
static Result capture(List<Ac> transactions,
List<Row> marketRows, Set<String> sessionIds,
Predicate<Ab> td) {
Predicate<Ab> visibility = td == null ? flow -> true : td;
var units = new ArrayList<Group>();
var apz = new LinkedHashSet<String>();
var ajs = new LinkedHashMap<String, Ac>();
if (transactions != null) {
for (Ac transaction : transactions) {
if (transaction != null) {
ajs.put(transaction.getId(), transaction);
}
}
}
if (marketRows != null) {
for (Row market : marketRows) {
if (market == null || sessionIds == null || !sessionIds.contains(market.scopeSessionId)) {
continue;
}
if (!market.settlementId.isEmpty()) {
apz.add(market.settlementId);
}
units.add(aap(market, market.settlementId.isEmpty()
? null : ajs.get(market.settlementId)));
}
}
if (transactions != null) {
for (Ac transaction : transactions) {
// An uncounted trade is custody's audit copy: its market row already shows the sale
// (owner 2026-09-28: a second fill read "Bones ×2 · 0").
if (transaction == null || apz.contains(transaction.getId())
|| transaction.getType() == Ai.TRADE && !transaction.isCounted()) {
continue;
}
units.addAll(akl(transaction, visibility));
}
}
units.sort(Comparator.comparingLong((Group unit) -> unit.latestActivityAt).reversed()
.thenComparing(unit -> unit.representativeContributionId));
var aro = new LinkedHashMap<String, Group>();
for (Group unit : units) {
aro.merge(unit.semanticGroupId, unit, Group::absorb);
}
var groups = new ArrayList<Group>(aro.values());
groups.sort(Comparator.comparingLong((Group group) -> group.latestActivityAt).reversed()
.thenComparing(group -> group.semanticGroupId));
return new Result(groups);
}
/** Reconciled counts over any group set (the search-filtered Ledger tables reuse this). */
static Summary summarize(Collection<Group> groups) {
long gains = 0L;
long costs = 0L;
long amh = 0L;
int entries = 0;
boolean complete = true;
var receipts = new LinkedHashSet<String>();
if (groups == null) {
return new Summary(0L, 0L, 0L, 0, 0, true);
}
for (Group group : groups) {
if (group.reviewRequired || group.neutral || group.table == Table.AUDIT) {
continue;
}
if (group.table == Table.MARKET) {
if (group.coverage != Coverage.COMPLETE) {
// Pending/unrealised Market is context, not a counted financial entry.
complete = false;
continue;
}
amh = safeAdd(amh, group.value);
} else if (group.table == Table.GAINS) {
gains = safeAdd(gains, group.value);
} else if (group.table == Table.COSTS_SUPPLIES || group.table == Table.COSTS_LOSS) {
costs = safeAdd(costs, group.value);
} else {
continue;
}
if (group.coverage == Coverage.INCOMPLETE) {
complete = false;
}
if (!group.corrected) {
entries++;
receipts.addAll(group.receiptIds);
}
}
return new Summary(gains, costs, amh, entries, receipts.size(), complete);
}
// ── unit construction ──────────────────────────────────────────────────────────────────────
static List<Group> akl(Ac transaction, Predicate<Ab> td) {
List<Ab> flows = transaction.getFlows();
boolean neutral = transaction.getType() == Ai.TRANSFER
|| transaction.getCorrection() == Ah.TRANSFER;
boolean review = Eh.aal(transaction);
boolean corrected = transaction.getCorrection() != AUTO;
boolean split = Af.isSplit(transaction);
boolean alj = !transaction.uo().isEmpty()
|| transaction.getActionKind() == Au.DEFERRED_CLAIM;
long at = transaction.timestampEpochMillis;
String source = Af.axb(transaction);
if (flows.isEmpty()) {
return auv(transaction, neutral, review, corrected, at, source);
}
var projected = Af.project(transaction);
// An opened claim's proceeds are the action's income: a loot minimum never hides them.
boolean claimSpend = false;
for (Af contribution : projected) {
claimSpend |= contribution.priceSource == Av.DEFERRED_CLAIM
&& contribution.quantityDelta < 0L;
}
var visible = new ArrayList<Af>();
for (Af contribution : projected) {
boolean ara = false;
for (Ab raw : contribution.rawFlows) {
ara |= td.test(raw);
}
if (ara || neutral || review || claimSpend || contribution.isUnpriced()) {
visible.add(contribution);
}
}
if (visible.isEmpty()) {
return emptyList();
}
// An opened claim (a coin pouch, an unopened key) is one action row: the claim count and
// the net it yielded, never a claim leg plus separate proceeds (owner 2026-09-29).
if (claimSpend && !neutral && !review && !corrected && !split) {
long spent = 0L;
long net = 0L;
boolean unpriced = false;
boolean claimOnly = true;
String name = null;
int icon = -1;
for (Af contribution : visible) {
long delta = contribution.quantityDelta;
if (delta < 0L) {
if (contribution.priceSource != Av.DEFERRED_CLAIM) {
claimOnly = false;
break;
}
spent -= delta;
if (name == null) {
name = contribution.itemName;
icon = contribution.itemId;
} else if (!name.equals(contribution.itemName)) {
name = "";
icon = -1;
}
} else if (delta > 0L) {
net = safeAdd(net, contribution.effectiveValue);
unpriced |= contribution.isUnpriced();
}
}
if (claimOnly && spent > 0L && net > 0L) {
String verb = verbOf(transaction);
String primary = name == null || name.isEmpty() ? verb : name;
String key = "claim:" + primary + ":" + Long.signum(net) + ":"
+ ql(unpriced) + ":" + verb + ":" + transaction.tm().name();
var unit = new Group(key, icon, Table.GAINS, primary,
"", verb, "", false, at, net, coverage(unpriced), spent, false,
false, false, false, false, false, "", transaction.getId(),
visible.get(0).contributionId, -1);
for (Af contribution : visible) {
unit.receipts.add(receipt(transaction, contribution, null, null));
}
unit.searchTerms.add(primary);
unit.searchTerms.add(verb);
unit.searchTerms.add(source);
unit.searchTerms.add(transaction.getId());
for (Af contribution : visible) {
unit.searchTerms.add(contribution.itemName);
}
return singletonList(unit);
}
}
// An estimated charge use is one row named by the weapon.
String note = transaction.getNote();
String prefix = "Estimated charge use \u00b7 ";
String ano = note.startsWith(prefix) ? note.substring(prefix.length()).trim() : "";
if (!ano.isEmpty()) {
// A worn charge feeder (blood fury) is no cast: its estimate books the charged
// resource "Used by" it — the same shape its measured Check produces (owner 2026-09-29).
Ar.V feeder = Ar.ajt(ano);
if (feeder == Ar.V.BLOOD_FURY || feeder == Ar.V.EYE_OF_AYAK) {
var feasted = new ArrayList<Group>(visible.size());
for (Af contribution : visible) {
feasted.add(agx(transaction, contribution, ano, neutral, review, corrected, source, at));
}
return feasted;
}
return singletonList(nw(transaction, visible, ano, source, at, neutral, review, corrected));
}
// A measured charge spend books its resources as rows "Used by" the weapon, never a composite.
String weapon = transaction.getActionKind() == Au.FIRE || transaction.getActionKind() == Au.CAST
? aai(transaction) : "";
if (weapon.isEmpty() && vz(transaction, visible, neutral, review, corrected, split, alj)) {
return singletonList(jx(transaction, visible, source, at));
}
var units = new ArrayList<Group>(visible.size());
for (Af contribution : visible) {
units.add(agx(transaction, contribution, weapon, neutral, review, corrected, source, at));
}
return units;
}
/**
* A safe action composite: pure counted costs of one financial category from one spell cast or
* one shot. Other actions (a sapling planted with its payment) stay per-item rows, as do mixed
* gain+cost, Market, Transfer, Review, Corrected, split and claim-linked rows.
*/
static boolean vz(Ac transaction,
List<Af> contributions, boolean neutral, boolean review, boolean corrected,
boolean split, boolean alj) {
if (neutral || review || corrected || split || alj
|| (transaction.getActionKind() != Au.CAST && transaction.getActionKind() != Au.FIRE)) {
return false;
}
if (contributions.size() < 2) {
return false;
}
Category category = sr(transaction, contributions.get(0));
if (category != Category.SUPPLY
&& category != Category.LOSS
&& category != Category.FEE) {
return false;
}
for (Af contribution : contributions) {
if (sr(transaction, contribution) != category
|| !contribution.counted || contribution.auditOnly) {
return false;
}
}
return true;
}
static Group jx(Ac transaction, List<Af> contributions,
String source, long at) {
Category category = sr(transaction, contributions.get(0));
boolean unpriced = false;
long value = 0L;
for (Af contribution : contributions) {
unpriced |= contribution.isUnpriced();
value = safeAdd(value, contribution.effectiveValue);
}
String alu = transaction.spellName();
String verb = verbOf(transaction);
String primaryName = alu.isEmpty() ? verb : alu;
String context = pt(transaction, contributions);
String key = alu.isEmpty()
? "action:" + transaction.getActionKind().wireName() + ":generic="
+ ps(contributions) + ":cov=" + ql(unpriced)
: "action:" + transaction.getActionKind().wireName() + ":exact=" + alu
+ ":cov=" + ql(unpriced);
String representative = contributions.get(0).contributionId;
var unit = new Group(key, Spells.icon(alu), axd(category), primaryName,
context, verb, "", false, at, value, coverage(unpriced), -1L, false, true,
false, false, false, false, "", transaction.getId(), representative, 0);
for (Af contribution : contributions) {
unit.receipts.add(receipt(transaction, contribution, null, null));
}
unit.searchTerms.add(primaryName);
unit.searchTerms.add(context);
unit.searchTerms.add(verb);
unit.searchTerms.add(source);
unit.searchTerms.add(transaction.getId());
for (Af contribution : contributions) {
unit.searchTerms.add(contribution.itemName);
}
return unit;
}
/** One resource row; with a weapon it is a measured charge spend "Used by" that weapon. */
static Group agx(Ac transaction, Af contribution, String weapon,
boolean neutral, boolean review, boolean corrected, String source, long at) {
Category category = sr(transaction, contribution);
boolean unpriced = contribution.isUnpriced();
// An unopened claim (a key or a coin pouch) is held at zero, never shown as a value.
int claimSign = contribution.priceSource == Av.DEFERRED_CLAIM
? (int) Long.signum(contribution.quantityDelta) : 0;
long value = neutral || claimSign != 0 || contribution.auditOnly ? 0L
: contribution.effectiveValue;
String verb = verbOf(transaction);
String label = contribution.actionDisplayName;
String actionLabel = weapon.isEmpty() ? label.isEmpty() ? verb : verb + " \u00b7 " + label : "";
String context = weapon.isEmpty() ? actionLabel : "Used by " + weapon;
String key = (weapon.isEmpty() ? "res:" : "charge:" + weapon + ":") + axd(category) + ":"
// Doses of one potion share a row whichever dose each came from (owner 2026-09-28).
+ (contribution.normalizedConsume ? contribution.itemName : contribution.itemId)
+ ":" + Long.signum(value) + ":" + ql(unpriced) + ":"
+ contribution.priceSource.name() + ":" + label + ":" + verb + ":" + transaction.tm().name()
+ ":" + (contribution.normalizedConsume ? "norm" : "raw")
+ ":" + (review ? "review" : "auto") + ":" + (corrected ? "corrected" : "auto")
+ ":" + (neutral || claimSign != 0 ? "neutral" : "counted");
var unit = new Group(key, contribution.itemId, axd(category),
contribution.itemName, context, actionLabel, weapon, !weapon.isEmpty(), at, value,
coverage(unpriced), ahn(contribution), contribution.normalizedConsume, false,
neutral || claimSign != 0, false, review, corrected, "", transaction.getId(),
contribution.contributionId, claimSign);
unit.receipts.add(receipt(transaction, contribution, weapon, null));
for (String term : new String[] {contribution.itemName, context, verb, label, weapon, source,
transaction.getId()}) {
unit.searchTerms.add(term);
}
return unit;
}
static List<Group> auv(Ac transaction, boolean neutral, boolean review,
boolean corrected, long at, String source) {
Bp.Ax amounts = Bp.transaction(transaction);
if (!amounts.included && !review && !neutral) {
return emptyList();
}
long value = neutral ? 0L : amounts.getNet();
Category category = Af.acf(transaction, amounts, neutral);
String name = transaction.getNote().isEmpty() ? transaction.getActivityName() : transaction.getNote();
String verb = verbOf(transaction);
String key = "note:" + transaction.getId();
var unit = new Group(key, -1, axd(category), name, verb, verb,
"", false, at, value, Coverage.COMPLETE, 0L, false, true, neutral, false, review, corrected,
"", transaction.getId(), transaction.getId() + ":composite", 0);
unit.receipts.add(new Receipt(transaction.getId() + ":composite", transaction.getId(), at, -1, name, 0L,
value, transaction.tl(), false, neutral, review, corrected,
Af.isSplit(transaction), category, verb,
transaction.spellName(),
why(transaction, false, neutral), qj(transaction.getCorrection()), source, "", "", null));
unit.searchTerms.add(name);
unit.searchTerms.add(verb);
unit.searchTerms.add(source);
unit.searchTerms.add(transaction.getId());
return singletonList(unit);
}
static Group aap(Row market, Ac settlement) {
boolean realized = market.isRealizedIncluded() && market.realizedResultCorrectionAware;
// Unknown-basis liquidation has no available Result: never show 0 as if break-even were
// known. Its one exact contribution is a proven GE tax, which stays a market cost.
boolean knownResult = realized
&& (market.coverage != Bi.Coverage.FULLY_UNKNOWN
|| market.manualFinancialResult);
boolean aot = realized && market.knownCostOnly;
boolean aos = knownResult || aot;
boolean review = market.lifecycle == Lifecycle.AMBIGUOUS
|| market.lifecycle == Lifecycle.UNAVAILABLE
|| market.lifecycle == Lifecycle.CLOSED_UNOBSERVED
|| market.isRealizedIncluded() && !market.realizedResultCorrectionAware;
String side = market.side.name().equals("SELL") ? "Sell" : "Buy";
String status = knownResult ? side
: aot ? side + " \u00b7 tax counted"
: market.isRealizedIncluded() ? side + " \u00b7 result unavailable"
: yo(market);
long quantity = market.isRealizedIncluded() ? market.settledQty : market.filledQty;
String presentationId = market.presentationId;
String key = "market:" + presentationId;
var unit = new Group(key, market.itemId, Table.MARKET,
market.itemName, status, "", "", false, market.timestampEpochMillis,
aos ? market.realizedResultGp : 0L,
aos ? Coverage.COMPLETE : Coverage.INCOMPLETE, quantity, false,
true, false, true, review, false,
presentationId, market.settlementId, "market:" + presentationId, 0);
unit.searchTerms.add(market.itemName);
unit.searchTerms.add(status);
unit.searchTerms.add(side);
unit.searchTerms.add(market.lifecycle.name());
unit.searchTerms.add(market.side.name());
unit.searchTerms.add(presentationId);
if (!market.settlementId.isEmpty()) {
unit.searchTerms.add(market.settlementId);
}
// D2: a market group owns only its sale item's receipt. Its settlement's Coins leg is never
// listed as a separate receipt or grouped with an unclaimed row.
if (settlement != null) {
for (Af contribution : Af.project(settlement)) {
if (contribution.itemId == market.itemId) {
unit.receipts.add(receipt(settlement, contribution, null, market));
}
}
}
return unit;
}
// ── helpers ────────────────────────────────────────────────────────────────────────────────
static Receipt receipt(Ac transaction, Af contribution,
String usedBy, Row marketSettlement) {
boolean neutral = contribution.category == Category.AUDIT
&& (transaction.getType() == Ai.TRANSFER
|| transaction.getCorrection() == Ah.TRANSFER);
boolean review = transaction.getCorrection() == AUTO
&& (contribution.needsReview || Eh.aal(transaction));
boolean atx = !neutral && !contribution.auditOnly;
Category category = contribution.category;
return new Receipt(contribution.contributionId, transaction.getId(),
contribution.timestampEpochMillis, contribution.itemId, contribution.itemName,
ahn(contribution), atx ? contribution.effectiveValue : 0L,
contribution.valueDelta, contribution.isUnpriced(), neutral, review,
contribution.corrected, contribution.split, category, verbOf(transaction),
contribution.actionDisplayName, why(transaction, contribution.isUnpriced(), neutral),
qj(transaction.getCorrection()), Af.axb(transaction),
usedBy == null ? "" : usedBy, chargeBasis(transaction, contribution), marketSettlement);
}
static long ahn(Af contribution) {
if (contribution.normalizedConsume) {
return contribution.normalizedQuantity;
}
return abs(contribution.quantityDelta);
}
/** Display verb for a transaction: action kind first, then split/type direction. */
static String verbOf(Ac transaction) {
if ("Dropped".equals(transaction.getNote())) return "Dropped";
if (transaction.getActionKind() != null) {
return jy(transaction.getActionKind());
}
if (Af.isSplit(transaction) && transaction.getCorrection() == AUTO) {
return "Split";
}
if (transaction.getType() == CONSUMPTION && transaction.getContext() == Aj.PRODUCTION) {
return msg("zz", "Failed");
}
return msg("verb." + transaction.getType(), transaction.getNet() < 0L ? "Used" : "Received");
}
static String jy(Au kind) {
return msg("action." + kind, "Used");
}
/** Why a receipt counted, told from stored records only. */
static String why(Ac transaction, boolean unpriced, boolean neutral) {
if (transaction.getCorrection() != AUTO) {
return msg("why-correction." + transaction.getCorrection());
}
if (neutral) {
return msg("ah");
}
if (unpriced) {
return msg("ho");
}
String note = transaction.getNote();
// Owner 2026-10-01 (F11): a Charges receipt names its retained confidence.
if (note.startsWith("Estimated charge use \u00b7 ")) {
return "Estimated from the local cast or hit graphic; a measured Check reconciles it.";
}
if (note.startsWith("Measured charge spend \u00b7 ")) {
return "Measured from the exact charge Check difference.";
}
if (Eh.aal(transaction)) {
return msg("fd");
}
if (transaction.getType() == CONSUMPTION && transaction.getContext() == Aj.PRODUCTION) {
return msg("zy");
}
return msg("why-type." + transaction.getType(), transaction.isCounted() ? "Counted automatically." : "Not counted.");
}
/**
* Charges: the exact captured unit price and its source, or "unpriced". Retained flow facts
* only - never re-derived from the value (owner 2026-10-01, F11).
*/
static String chargeBasis(Ac transaction, Af contribution) {
String note = transaction.getNote();
if (!note.startsWith("Estimated charge use \u00b7 ")
&& !note.startsWith("Measured charge spend \u00b7 ")) {
return "";
}
if (contribution.isUnpriced() || contribution.rawFlows.isEmpty()
|| contribution.rawFlows.get(0).unitPrice <= 0) {
return "unpriced";
}
Ab flow = contribution.rawFlows.get(0);
return Fmt.exact(flow.unitPrice) + " gp each \u00b7 " + flow.getPriceSource();
}
static String qj(Ah correction) {
return msg("counted-as." + correction, "Automatic");
}
/** Exact weapon identity already retained by the measured charge booking path. */
/** One estimated charge cast: the weapon names a single cast row, like a spell. */
static Group nw(Ac transaction, List<Af> contributions,
String weapon, String source, long at, boolean neutral, boolean review, boolean corrected) {
boolean unpriced = false;
long value = 0L;
for (Af contribution : contributions) {
unpriced |= contribution.isUnpriced();
value = safeAdd(value, contribution.effectiveValue);
}
String verb = verbOf(transaction);
String context = pt(transaction, contributions);
String key = "chargecast:" + weapon + ":" + ql(unpriced)
+ ":" + (neutral ? "neutral" : "counted")
+ ":" + (review ? "review" : "auto")
+ ":" + (corrected ? "corrected" : "auto");
int icon = transaction.aym;
if (icon <= 0) {
// The weapon hint is transient; a restart must still draw the weapon sprite,
// including estimate-only variants (the scythe has no Check mapping yet).
var variant = Ar.ajt(weapon);
if (variant != null) {
icon = variant.itemIds[0];
}
}
var unit = new Group(key, icon,
axd(sr(transaction, contributions.get(0))), weapon, context, verb, "", true, at,
value, coverage(unpriced), 1L, false, true, neutral, false, review, corrected, "",
transaction.getId(), contributions.get(0).contributionId, 0);
for (Af contribution : contributions) {
unit.receipts.add(receipt(transaction, contribution, null, null));
unit.searchTerms.add(contribution.itemName);
}
unit.searchTerms.add(weapon);
unit.searchTerms.add(context);
unit.searchTerms.add(verb);
unit.searchTerms.add(source);
unit.searchTerms.add(transaction.getId());
return unit;
}
static String aai(Ac transaction) {
String note = transaction.getNote();
String prefix = "Measured charge spend \u00b7 ";
if (!note.startsWith(prefix)) {
return "";
}
String weapon = note.substring(prefix.length()).trim();
return weapon.length() > 80 ? weapon.substring(0, 80).trim() : weapon;
}
static String pt(Ac transaction, List<Af> contributions) {
boolean anh = true;
var aqc = new LinkedHashSet<Integer>();
for (Af contribution : contributions) {
String name = contribution.itemName.toLowerCase(ROOT);
if (!name.endsWith(" rune")) {
anh = false;
} else {
aqc.add(contribution.itemId);
}
}
if (transaction.getActionKind() == Au.CAST && anh) {
return aqc.size() + " rune types";
}
return contributions.size() + (transaction.getActionKind() == Au.CAST ? " components" : " items");
}
/**
* Presentation grouping signature for generic action composites: sorted resource ids with a
* GCD-normalised quantity ratio when every quantity is known, else raw quantities (split
* rather than over-merge), plus the price source so priced/incomplete shapes stay distinct.
*/
static String ps(List<Af> contributions) {
long gcd = 0L;
boolean aph = true;
for (Af contribution : contributions) {
long quantity = abs(contribution.quantityDelta);
if (quantity <= 0L) {
aph = false;
break;
}
gcd = gcd(gcd, quantity);
}
var sorted = new ArrayList<Af>(contributions);
sorted.sort(Comparator.comparingInt((Af itemData) -> itemData.itemId)
.thenComparing((itemData -> itemData.contributionId)));
var parts = new ArrayList<String>(sorted.size());
for (Af contribution : sorted) {
long quantity = abs(contribution.quantityDelta);
long ratio = aph && gcd > 0L ? quantity / gcd : quantity;
parts.add(contribution.itemId + ":" + ratio + ":"
+ (contribution.isUnpriced() ? "unpriced" : contribution.priceSource.name()));
}
return String.join(",", parts);
}
static long gcd(long left, long right) {
long a = Math.abs(left);
long b = Math.abs(right);
while (b != 0L) {
long next = a % b;
a = b;
b = next;
}
return a;
}
/**
* The financial destination of one contribution. A zero-value financial movement (an
* unpriced cost or gain) is routed by its direction and positive evidence — never to Audit —
* so an unpriced Supply stays in Costs/Supplies with an incomplete marker.
*/
static Category sr(Ac transaction,
Af contribution) {
Category category = contribution.category;
if (category != Category.AUDIT) {
return category;
}
if (contribution.auditOnly
|| transaction.getType() == Ai.TRANSFER
|| transaction.getCorrection() == Ah.TRANSFER) {
return Category.AUDIT;
}
long quantity = contribution.quantityDelta;
if (quantity > 0L) {
return Category.GAIN;
}
if (quantity == 0L) {
return Category.AUDIT;
}
Ai type = transaction.tm();
if (type == Ai.PK_FEE) {
return Category.FEE;
}
if (type == Ai.TRADE) {
return Category.MARKET;
}
if (type == Ai.PK_DEATH_LOSS || type == PK_SUPPLY_COST
|| type == Ai.PROCESSING || transaction.getActionKind() != null) {
return type == Ai.PK_DEATH_LOSS
? Category.LOSS : Category.SUPPLY;
}
return Category.LOSS;
}
static Table axd(Category category) {
switch (category) {
case GAIN: return Table.GAINS;
case SUPPLY: return Table.COSTS_SUPPLIES;
case LOSS:
case FEE: return Table.COSTS_LOSS;
case MARKET: return Table.MARKET;
case AUDIT:
default: return Table.AUDIT;
}
}
static String ql(boolean unpriced) {
return unpriced ? "incomplete" : "complete";
}
static Coverage coverage(boolean unpriced) {
return unpriced ? Coverage.INCOMPLETE : Coverage.COMPLETE;
}
static String yo(Row market) {
boolean sell = market.side == Aa.Side.SELL;
switch (market.lifecycle) {
case PENDING: return "×" + market.offeredQty;
case PARTIALLY_EXECUTED:
return (sell ? "Part sold " : "Part bought ") + market.filledQty
+ "/" + market.offeredQty;
case EXECUTED_UNSETTLED:
return sell ? "Sold \u00b7 collect" : "Bought \u00b7 collect";
case PARTIALLY_REALIZED: return "Partially realized";
case CANCELLED_RETURNED: return "Returned";
case RESUMED: return "Resumed";
case AMBIGUOUS: return "Review";
case CLOSED_UNOBSERVED: return "Settlement unobserved";
case UNAVAILABLE:
default: return "Detail unavailable";
}
}
}
