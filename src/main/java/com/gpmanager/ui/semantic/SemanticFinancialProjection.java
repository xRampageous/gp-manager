package com.gpmanager;
import com.gpmanager.MarketSettlementProjection.*;
import com.gpmanager.Contribution.Category;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import lombok.*;
import static com.gpmanager.ModelText.*;
import static java.util.Locale.*;
import static java.util.Collections.*;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.Correction.*;
import static com.gpmanager.TransactionType.*;
import static com.gpmanager.GameData.msg;
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
class SemanticFinancialProjection {
/** Financial table destination for the future stacked Ledger and the Live consumer. */
enum Table {
GAINS, COSTS_SUPPLIES, COSTS_LOSS, MARKET, AUDIT
}

/** Value coverage: an incomplete group never presents itself as an exact headline. */
enum Coverage {
COMPLETE, INCOMPLETE
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
final String correctionLabel;
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
if (empty(needle)) return true;
String lower = needle.toLowerCase(ROOT);
return itemName.toLowerCase(ROOT).contains(lower) || verb.toLowerCase(ROOT).contains(lower)
|| actionDisplayName.toLowerCase(ROOT).contains(lower) || source.toLowerCase(ROOT).contains(lower)
|| why.toLowerCase(ROOT).contains(lower) || correctionLabel.toLowerCase(ROOT).contains(lower)
|| transactionId.toLowerCase(ROOT).contains(lower) || contributionId.toLowerCase(ROOT).contains(lower);
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
final String contextLine;
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
Group(String mergeKey, int itemId, Table table, String primaryName, String contextLine, String actionLabel,
String usedBy, boolean chargeUse, long at, long value, Coverage coverage, long quantity, boolean normalizedConsume,
boolean composite, boolean neutral, boolean market, boolean reviewRequired, boolean corrected,
String marketPresentationId, String transactionId, String representativeContributionId, int claim) {
if (GROUP_IDS.size() > 50_000) GROUP_IDS.clear();
this.semanticGroupId = GROUP_IDS.computeIfAbsent(mergeKey,
key -> "sg:" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)));
this.itemId = itemId;
this.table = table;
this.primaryName = primaryName;
this.contextLine = contextLine;
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
if (empty(id)) return false;
if (semanticGroupId.equals(id)) return true;
for (Receipt receipt : receipts) {
if (id.equals(receipt.contributionId)) return true;
}
return false;
}
/** True when this group represents the given canonical transaction identity. */
boolean containsTransaction(String id) {
if (empty(id)) return false;
if (id.equals(representativeTransactionId)) return true;
if (receiptIds.contains(id)) return true;
for (Receipt receipt : receipts) {
if (id.equals(receipt.transactionId)) return true;
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
if (query == null) return true;
String needle = query.trim().toLowerCase(ROOT);
if (needle.isEmpty()) return true;
for (String term : searchTerms) {
if (term != null && term.toLowerCase(ROOT).contains(needle)) return true;
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
static Result capture(List<Transaction> transactions, List<Row> marketRows, String sessionId,
Predicate<Flow> flowVisible) {
Set<String> sessions = sessionId == null || sessionId.isEmpty() ? emptySet() : singleton(sessionId);
return capture(transactions, marketRows, sessions, flowVisible);
}

/** Multi-session scope: Today and future period consumers merge compatible activity. */
static Result capture(List<Transaction> transactions, List<Row> marketRows, Set<String> sessionIds,
Predicate<Flow> flowVisible) {
Predicate<Flow> visibility = flowVisible == null ? flow -> true : flowVisible;
var units = new ArrayList<Group>();
var representedSettlements = new LinkedHashSet<String>();
var transactionById = new LinkedHashMap<String, Transaction>();
if (transactions != null) {
for (Transaction transaction : transactions) {
if (transaction != null) transactionById.put(transaction.getId(), transaction);
}
}
if (marketRows != null) {
for (Row market : marketRows) {
if (market == null || sessionIds == null || !sessionIds.contains(market.scopeSessionId)) continue;
if (!market.settlementId.isEmpty()) representedSettlements.add(market.settlementId);
units.add(marketUnit(market, market.settlementId.isEmpty() ? null : transactionById.get(market.settlementId)));
}
}
if (transactions != null) {
for (Transaction transaction : transactions) {
// An uncounted trade is custody's audit copy: its market row already shows the sale
// (owner 2026-09-28: a second fill read "Bones ×2 · 0").
if (transaction == null || representedSettlements.contains(transaction.getId())
|| transaction.getType() == TransactionType.TRADE && !transaction.isCounted()) {
continue;
}
units.addAll(transactionUnits(transaction, visibility));
}
}
units.sort(Comparator.comparingLong((Group unit) -> unit.latestActivityAt).reversed()
.thenComparing(unit -> unit.representativeContributionId));
var buckets = new LinkedHashMap<String, Group>();
for (Group unit : units) buckets.merge(unit.semanticGroupId, unit, Group::absorb);
var groups = new ArrayList<Group>(buckets.values());
groups.sort(Comparator.comparingLong((Group group) -> group.latestActivityAt).reversed()
.thenComparing(group -> group.semanticGroupId));
return new Result(groups);
}

/** Reconciled counts over any group set (the search-filtered Ledger tables reuse this). */
static Summary summarize(Collection<Group> groups) {
long gains = 0L;
long costs = 0L;
long marketTotal = 0L;
int entries = 0;
boolean complete = true;
var receipts = new LinkedHashSet<String>();
if (groups == null) return new Summary(0L, 0L, 0L, 0, 0, true);
for (Group group : groups) {
if (group.reviewRequired || group.neutral || group.table == Table.AUDIT) continue;
if (group.table == Table.MARKET) {
if (group.coverage != Coverage.COMPLETE) {
// Pending/unrealised Market is context, not a counted financial entry.
complete = false;
continue;
}
marketTotal = safeAdd(marketTotal, group.value);
} else if (group.table == Table.GAINS) {
gains = safeAdd(gains, group.value);
} else if (group.table == Table.COSTS_SUPPLIES || group.table == Table.COSTS_LOSS) {
costs = safeAdd(costs, group.value);
} else {
continue;
}
if (group.coverage == Coverage.INCOMPLETE) complete = false;
if (!group.corrected) {
entries++;
receipts.addAll(group.receiptIds);
}
}
return new Summary(gains, costs, marketTotal, entries, receipts.size(), complete);
}

// ── unit construction ──────────────────────────────────────────────────────────────────────
static List<Group> transactionUnits(Transaction transaction, Predicate<Flow> flowVisible) {
List<Flow> flows = transaction.getFlows();
boolean neutral = transaction.getType() == TransactionType.TRANSFER
|| transaction.getCorrection() == Correction.TRANSFER;
boolean review = ReviewEligibility.needsOwnerDecision(transaction);
boolean corrected = transaction.getCorrection() != AUTO;
boolean split = Contribution.isSplit(transaction);
boolean claimLinked = !transaction.getSourceClaimId().isEmpty()
|| transaction.getActionKind() == ActionKind.DEFERRED_CLAIM;
long at = transaction.timestampEpochMillis;
String source = Contribution.sourceOf(transaction);
if (flows.isEmpty()) return noteUnits(transaction, neutral, review, corrected, at, source);
var projected = Contribution.project(transaction);
// An opened claim's proceeds are the action's income: a loot minimum never hides them.
boolean claimSpend = false;
for (Contribution contribution : projected) {
claimSpend |= contribution.priceSource == PriceSource.DEFERRED_CLAIM && contribution.quantityDelta < 0L;
}
var visible = new ArrayList<Contribution>();
for (Contribution contribution : projected) {
boolean visibleFlow = false;
for (Flow raw : contribution.rawFlows) visibleFlow |= flowVisible.test(raw);
if (visibleFlow || neutral || review || claimSpend || contribution.isUnpriced()) visible.add(contribution);
}
if (visible.isEmpty()) return emptyList();
// An opened claim (a coin pouch, an unopened key) is one action row: the claim count and
// the net it yielded, never a claim leg plus separate proceeds (owner 2026-09-29).
if (claimSpend && !neutral && !review && !corrected && !split) {
long spent = 0L;
long net = 0L;
boolean unpriced = false;
boolean claimOnly = true;
String name = null;
int icon = -1;
for (Contribution contribution : visible) {
long delta = contribution.quantityDelta;
if (delta < 0L) {
if (contribution.priceSource != PriceSource.DEFERRED_CLAIM) {
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
+ coverageKey(unpriced) + ":" + verb + ":" + transaction.getAutomaticType().name();
var unit = new Group(key, icon, Table.GAINS, primary, "", verb, "", false, at, net, coverage(unpriced), spent, false,
false, false, false, false, false, "", transaction.getId(), visible.get(0).contributionId, -1);
for (Contribution contribution : visible) unit.receipts.add(receipt(transaction, contribution, null, null));
unit.searchTerms.add(primary);
unit.searchTerms.add(verb);
unit.searchTerms.add(source);
unit.searchTerms.add(transaction.getId());
for (Contribution contribution : visible) unit.searchTerms.add(contribution.itemName);
return singletonList(unit);
}
}
// An estimated charge use is one row named by the weapon.
String note = transaction.getNote();
String prefix = "Estimated charge use \u00b7 ";
String castName = note.startsWith(prefix) ? note.substring(prefix.length()).trim() : "";
if (!castName.isEmpty()) {
// A worn charge feeder (blood fury) is no cast: its estimate books the charged
// resource "Used by" it — the same shape its measured Check produces (owner 2026-09-29).
ChargeRead.Variant feeder = ChargeRead.variantNamed(castName);
if (feeder == ChargeRead.Variant.BLOOD_FURY || feeder == ChargeRead.Variant.EYE_OF_AYAK) {
var feasted = new ArrayList<Group>(visible.size());
for (Contribution contribution : visible) {
feasted.add(resourceUnit(transaction, contribution, castName, neutral, review, corrected, source, at));
}
return feasted;
}
return singletonList(chargeCastUnit(transaction, visible, castName, source, at, neutral, review, corrected));
}
// A measured charge spend books its resources as rows "Used by" the weapon, never a composite.
String weapon = transaction.getActionKind() == ActionKind.FIRE || transaction.getActionKind() == ActionKind.CAST
? measuredChargeWeaponName(transaction) : "";
if (weapon.isEmpty() && isActionComposite(transaction, visible, neutral, review, corrected, split, claimLinked)) {
return singletonList(actionUnit(transaction, visible, source, at));
}
var units = new ArrayList<Group>(visible.size());
for (Contribution contribution : visible) {
units.add(resourceUnit(transaction, contribution, weapon, neutral, review, corrected, source, at));
}
return units;
}

/**
* A safe action composite: pure counted costs of one financial category from one spell cast or
* one shot. Other actions (a sapling planted with its payment) stay per-item rows, as do mixed
* gain+cost, Market, Transfer, Review, Corrected, split and claim-linked rows.
*/
static boolean isActionComposite(Transaction transaction,
List<Contribution> contributions, boolean neutral, boolean review, boolean corrected,
boolean split, boolean claimLinked) {
if (neutral || review || corrected || split || claimLinked
|| (transaction.getActionKind() != ActionKind.CAST && transaction.getActionKind() != ActionKind.FIRE)) {
return false;
}
if (contributions.size() < 2) return false;
Category category = financialCategory(transaction, contributions.get(0));
if (category != Category.SUPPLY && category != Category.LOSS && category != Category.FEE) {
return false;
}
for (Contribution contribution : contributions) {
if (financialCategory(transaction, contribution) != category || !contribution.counted || contribution.auditOnly) {
return false;
}
}
return true;
}

static Group actionUnit(Transaction transaction, List<Contribution> contributions, String source, long at) {
Category category = financialCategory(transaction, contributions.get(0));
boolean unpriced = false;
long value = 0L;
for (Contribution contribution : contributions) {
unpriced |= contribution.isUnpriced();
value = safeAdd(value, contribution.effectiveValue);
}
String exactName = transaction.spellName();
String verb = verbOf(transaction);
String primaryName = exactName.isEmpty() ? verb : exactName;
String context = compositionText(transaction, contributions);
String key = exactName.isEmpty() ? "action:" + transaction.getActionKind().wireName() + ":generic="
+ compositionKey(contributions) + ":cov=" + coverageKey(unpriced)
: "action:" + transaction.getActionKind().wireName() + ":exact=" + exactName + ":cov=" + coverageKey(unpriced);
String representative = contributions.get(0).contributionId;
var unit = new Group(key, Spells.icon(exactName), tableFor(category), primaryName,
context, verb, "", false, at, value, coverage(unpriced), -1L, false, true,
false, false, false, false, "", transaction.getId(), representative, 0);
for (Contribution contribution : contributions) {
unit.receipts.add(receipt(transaction, contribution, null, null));
}
unit.searchTerms.add(primaryName);
unit.searchTerms.add(context);
unit.searchTerms.add(verb);
unit.searchTerms.add(source);
unit.searchTerms.add(transaction.getId());
for (Contribution contribution : contributions) unit.searchTerms.add(contribution.itemName);
return unit;
}

/** One resource row; with a weapon it is a measured charge spend "Used by" that weapon. */
static Group resourceUnit(Transaction transaction, Contribution contribution, String weapon,
boolean neutral, boolean review, boolean corrected, String source, long at) {
Category category = financialCategory(transaction, contribution);
boolean unpriced = contribution.isUnpriced();
// An unopened claim (a key or a coin pouch) is held at zero, never shown as a value.
int claimSign = contribution.priceSource == PriceSource.DEFERRED_CLAIM
? (int) Long.signum(contribution.quantityDelta) : 0;
long value = neutral || claimSign != 0 || contribution.auditOnly ? 0L : contribution.effectiveValue;
String verb = verbOf(transaction);
String label = contribution.actionDisplayName;
String actionLabel = weapon.isEmpty() ? label.isEmpty() ? verb : verb + " \u00b7 " + label : "";
String context = weapon.isEmpty() ? actionLabel : "Used by " + weapon;
String key = (weapon.isEmpty() ? "res:" : "charge:" + weapon + ":") + tableFor(category) + ":"
// Doses of one potion share a row whichever dose each came from (owner 2026-09-28).
+ (contribution.normalizedConsume ? contribution.itemName : contribution.itemId)
+ ":" + Long.signum(value) + ":" + coverageKey(unpriced) + ":"
+ contribution.priceSource.name() + ":" + label + ":" + verb + ":" + transaction.getAutomaticType().name()
+ ":" + (contribution.normalizedConsume ? "norm" : "raw")
+ ":" + (review ? "review" : "auto") + ":" + (corrected ? "corrected" : "auto")
+ ":" + (neutral || claimSign != 0 ? "neutral" : "counted");
var unit = new Group(key, contribution.itemId, tableFor(category),
contribution.itemName, context, actionLabel, weapon, !weapon.isEmpty(), at, value,
coverage(unpriced), semanticQuantity(contribution), contribution.normalizedConsume, false,
neutral || claimSign != 0, false, review, corrected, "", transaction.getId(), contribution.contributionId, claimSign);
unit.receipts.add(receipt(transaction, contribution, weapon, null));
for (String term : new String[] {contribution.itemName, context, verb, label, weapon, source, transaction.getId()}) {
unit.searchTerms.add(term);
}
return unit;
}

static List<Group> noteUnits(Transaction transaction, boolean neutral, boolean review,
boolean corrected, long at, String source) {
AccountingProjection.TransactionAmounts amounts = AccountingProjection.transaction(transaction);
if (!amounts.included && !review && !neutral) return emptyList();
long value = neutral ? 0L : amounts.getNet();
Category category = Contribution.noteCategory(transaction, amounts, neutral);
String name = transaction.getNote().isEmpty() ? transaction.getActivityName() : transaction.getNote();
String verb = verbOf(transaction);
String key = "note:" + transaction.getId();
var unit = new Group(key, -1, tableFor(category), name, verb, verb,
"", false, at, value, Coverage.COMPLETE, 0L, false, true, neutral, false, review, corrected,
"", transaction.getId(), transaction.getId() + ":composite", 0);
unit.receipts.add(new Receipt(transaction.getId() + ":composite", transaction.getId(), at, -1, name, 0L,
value, transaction.getAutomaticNet(), false, neutral, review, corrected,
Contribution.isSplit(transaction), category, verb, transaction.spellName(),
why(transaction, false, neutral), correctionLabel(transaction.getCorrection()), source, "", "", null));
unit.searchTerms.add(name);
unit.searchTerms.add(verb);
unit.searchTerms.add(source);
unit.searchTerms.add(transaction.getId());
return singletonList(unit);
}

static Group marketUnit(Row market, Transaction settlement) {
boolean realized = market.isRealizedIncluded() && market.realizedResultCorrectionAware;
// Unknown-basis liquidation has no available Result: never show 0 as if break-even were
// known. Its one exact contribution is a proven GE tax, which stays a market cost.
boolean knownResult = realized && (market.coverage != MarketSettlementProjection.Coverage.FULLY_UNKNOWN
|| market.manualFinancialResult);
boolean knownCost = realized && market.knownCostOnly;
boolean knownContribution = knownResult || knownCost;
boolean review = market.lifecycle == Lifecycle.AMBIGUOUS || market.lifecycle == Lifecycle.UNAVAILABLE
|| market.lifecycle == Lifecycle.CLOSED_UNOBSERVED
|| market.isRealizedIncluded() && !market.realizedResultCorrectionAware;
String side = market.side.name().equals("SELL") ? "Sell" : "Buy";
String status = knownResult ? side : knownCost ? side + " \u00b7 tax counted"
: market.isRealizedIncluded() ? side + " \u00b7 result unavailable" : lifecycleLabel(market);
long quantity = market.isRealizedIncluded() ? market.settledQty : market.filledQty;
String presentationId = market.presentationId;
String key = "market:" + presentationId;
var unit = new Group(key, market.itemId, Table.MARKET,
market.itemName, status, "", "", false, market.timestampEpochMillis, knownContribution ? market.realizedResultGp : 0L,
knownContribution ? Coverage.COMPLETE : Coverage.INCOMPLETE, quantity, false, true, false, true, review, false,
presentationId, market.settlementId, "market:" + presentationId, 0);
unit.searchTerms.add(market.itemName);
unit.searchTerms.add(status);
unit.searchTerms.add(side);
unit.searchTerms.add(market.lifecycle.name());
unit.searchTerms.add(market.side.name());
unit.searchTerms.add(presentationId);
if (!market.settlementId.isEmpty()) unit.searchTerms.add(market.settlementId);
// D2: a market group owns only its sale item's receipt. Its settlement's Coins leg is never
// listed as a separate receipt or grouped with an unclaimed row.
if (settlement != null) {
for (Contribution contribution : Contribution.project(settlement)) {
if (contribution.itemId == market.itemId) unit.receipts.add(receipt(settlement, contribution, null, market));
}
}
return unit;
}

// ── helpers ────────────────────────────────────────────────────────────────────────────────
static Receipt receipt(Transaction transaction, Contribution contribution, String usedBy, Row marketSettlement) {
boolean neutral = contribution.category == Category.AUDIT && (transaction.getType() == TransactionType.TRANSFER
|| transaction.getCorrection() == Correction.TRANSFER);
boolean review = transaction.getCorrection() == AUTO
&& (contribution.needsReview || ReviewEligibility.needsOwnerDecision(transaction));
boolean valueKnown = !neutral && !contribution.auditOnly;
Category category = contribution.category;
return new Receipt(contribution.contributionId, transaction.getId(),
contribution.timestampEpochMillis, contribution.itemId, contribution.itemName,
semanticQuantity(contribution), valueKnown ? contribution.effectiveValue : 0L,
contribution.valueDelta, contribution.isUnpriced(), neutral, review,
contribution.corrected, contribution.split, category, verbOf(transaction),
contribution.actionDisplayName, why(transaction, contribution.isUnpriced(), neutral),
correctionLabel(transaction.getCorrection()), Contribution.sourceOf(transaction),
usedBy == null ? "" : usedBy, chargeBasis(transaction, contribution), marketSettlement);
}

static long semanticQuantity(Contribution contribution) {
if (contribution.normalizedConsume) return contribution.normalizedQuantity;
return abs(contribution.quantityDelta);
}

/** Display verb for a transaction: action kind first, then split/type direction. */
static String verbOf(Transaction transaction) {
if ("Dropped".equals(transaction.getNote())) return "Dropped";
if (transaction.getActionKind() != null) return actionVerb(transaction.getActionKind());
if (Contribution.isSplit(transaction) && transaction.getCorrection() == AUTO) return "Split";
if (transaction.getType() == CONSUMPTION && transaction.getContext() == Context.PRODUCTION) {
return msg("zz", "Failed");
}
return msg("verb." + transaction.getType(), transaction.getNet() < 0L ? "Used" : "Received");
}

static String actionVerb(ActionKind kind) {
return msg("action." + kind, "Used");
}

/** Why a receipt counted, told from stored records only. */
static String why(Transaction transaction, boolean unpriced, boolean neutral) {
if (transaction.getCorrection() != AUTO) return msg("why-correction." + transaction.getCorrection());
if (neutral) return msg("ah");
if (unpriced) return msg("ho");
String note = transaction.getNote();
// Owner 2026-10-01 (F11): a Charges receipt names its retained confidence.
if (note.startsWith("Estimated charge use \u00b7 ")) {
return "Estimated from the local cast or hit graphic; a measured Check reconciles it.";
}
if (note.startsWith("Measured charge spend \u00b7 ")) {
return "Measured from the exact charge Check difference.";
}
if (ReviewEligibility.needsOwnerDecision(transaction)) return msg("fd");
if (transaction.getType() == CONSUMPTION && transaction.getContext() == Context.PRODUCTION) return msg("zy");
return msg("why-type." + transaction.getType(), transaction.isCounted() ? "Counted automatically." : "Not counted.");
}

/**
* Charges: the exact captured unit price and its source, or "unpriced". Retained flow facts
* only - never re-derived from the value (owner 2026-10-01, F11).
*/
static String chargeBasis(Transaction transaction, Contribution contribution) {
String note = transaction.getNote();
if (!note.startsWith("Estimated charge use \u00b7 ") && !note.startsWith("Measured charge spend \u00b7 ")) {
return "";
}
if (contribution.isUnpriced() || contribution.rawFlows.isEmpty() || contribution.rawFlows.get(0).unitPrice <= 0) {
return "unpriced";
}
Flow flow = contribution.rawFlows.get(0);
return Fmt.exact(flow.unitPrice) + " gp each \u00b7 " + flow.getPriceSource();
}

static String correctionLabel(Correction correction) {
return msg("counted-as." + correction, "Automatic");
}

/** Exact weapon identity already retained by the measured charge booking path. */
/** One estimated charge cast: the weapon names a single cast row, like a spell. */
static Group chargeCastUnit(Transaction transaction, List<Contribution> contributions,
String weapon, String source, long at, boolean neutral, boolean review, boolean corrected) {
boolean unpriced = false;
long value = 0L;
for (Contribution contribution : contributions) {
unpriced |= contribution.isUnpriced();
value = safeAdd(value, contribution.effectiveValue);
}
String verb = verbOf(transaction);
String context = compositionText(transaction, contributions);
String key = "chargecast:" + weapon + ":" + coverageKey(unpriced) + ":" + (neutral ? "neutral" : "counted")
+ ":" + (review ? "review" : "auto") + ":" + (corrected ? "corrected" : "auto");
int icon = transaction.chargeCastItemId;
if (icon <= 0) {
// The weapon hint is transient; a restart must still draw the weapon sprite,
// including estimate-only variants (the scythe has no Check mapping yet).
var variant = ChargeRead.variantNamed(weapon);
if (variant != null) icon = variant.itemIds[0];
}
var unit = new Group(key, icon,
tableFor(financialCategory(transaction, contributions.get(0))), weapon, context, verb, "", true, at,
value, coverage(unpriced), 1L, false, true, neutral, false, review, corrected, "",
transaction.getId(), contributions.get(0).contributionId, 0);
for (Contribution contribution : contributions) {
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

static String measuredChargeWeaponName(Transaction transaction) {
String note = transaction.getNote();
String prefix = "Measured charge spend \u00b7 ";
if (!note.startsWith(prefix)) return "";
String weapon = note.substring(prefix.length()).trim();
return weapon.length() > 80 ? weapon.substring(0, 80).trim() : weapon;
}

static String compositionText(Transaction transaction, List<Contribution> contributions) {
boolean allRunes = true;
var runeTypes = new LinkedHashSet<Integer>();
for (Contribution contribution : contributions) {
String name = contribution.itemName.toLowerCase(ROOT);
if (!name.endsWith(" rune")) allRunes = false;
else runeTypes.add(contribution.itemId);
}
if (transaction.getActionKind() == ActionKind.CAST && allRunes) return runeTypes.size() + " rune types";
return contributions.size() + (transaction.getActionKind() == ActionKind.CAST ? " components" : " items");
}

/**
* Presentation grouping signature for generic action composites: sorted resource ids with a
* GCD-normalised quantity ratio when every quantity is known, else raw quantities (split
* rather than over-merge), plus the price source so priced/incomplete shapes stay distinct.
*/
static String compositionKey(List<Contribution> contributions) {
long gcd = 0L;
boolean normalisable = true;
for (Contribution contribution : contributions) {
long quantity = abs(contribution.quantityDelta);
if (quantity <= 0L) {
normalisable = false;
break;
}
gcd = gcd(gcd, quantity);
}
var sorted = new ArrayList<Contribution>(contributions);
sorted.sort(Comparator.comparingInt((Contribution itemData) -> itemData.itemId)
.thenComparing((itemData -> itemData.contributionId)));
var parts = new ArrayList<String>(sorted.size());
for (Contribution contribution : sorted) {
long quantity = abs(contribution.quantityDelta);
long ratio = normalisable && gcd > 0L ? quantity / gcd : quantity;
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
static Category financialCategory(Transaction transaction, Contribution contribution) {
Category category = contribution.category;
if (category != Category.AUDIT) return category;
if (contribution.auditOnly || transaction.getType() == TransactionType.TRANSFER
|| transaction.getCorrection() == Correction.TRANSFER) {
return Category.AUDIT;
}
long quantity = contribution.quantityDelta;
if (quantity > 0L) return Category.GAIN;
if (quantity == 0L) return Category.AUDIT;
TransactionType type = transaction.getAutomaticType();
if (type == TransactionType.PK_FEE) return Category.FEE;
if (type == TransactionType.TRADE) return Category.MARKET;
if (type == TransactionType.PK_DEATH_LOSS || type == PK_SUPPLY_COST
|| type == TransactionType.PROCESSING || transaction.getActionKind() != null) {
return type == TransactionType.PK_DEATH_LOSS ? Category.LOSS : Category.SUPPLY;
}
return Category.LOSS;
}

static Table tableFor(Category category) {
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

static String coverageKey(boolean unpriced) {
return unpriced ? "incomplete" : "complete";
}

static Coverage coverage(boolean unpriced) {
return unpriced ? Coverage.INCOMPLETE : Coverage.COMPLETE;
}

static String lifecycleLabel(Row market) {
boolean sell = market.side == GeRecord.Side.SELL;
switch (market.lifecycle) {
case PENDING: return "×" + market.offeredQty;
case PARTIALLY_EXECUTED:
return (sell ? "Part sold " : "Part bought ") + market.filledQty + "/" + market.offeredQty;
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
