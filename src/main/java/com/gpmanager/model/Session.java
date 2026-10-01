package com.gpmanager;
import static com.gpmanager.GameData.msg;
import com.gpmanager.AccountingProjection.TransactionAmounts;
import com.gpmanager.CorrectionRecord.Change;
import lombok.*;
import java.util.*;
import java.util.function.Predicate;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.ModelText.*;
import static com.gpmanager.PauseReason.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static com.gpmanager.Correction.*;
import static com.gpmanager.AccountingProjection.*;
class Session {
/** The always-on General tracker is shown as "Overall". */
static final String DURABLE_OWNER_NAME = "Overall";
static final int MAX_UNDO_HISTORY = 32;
String id;
String name;
String activityHint;
SessionMode mode;
/** Explicit owner provenance; legacy saves intentionally deserialize as UNKNOWN. */
SessionOwnerKind ownerKind = SessionOwnerKind.UNKNOWN;
/** Runtime-only signal/revision for profile totals derived from session-day summaries. */
transient Runnable changeListener;
/** Runtime-only narrow live-clock patch; avoids rebuilding historical rollups on each tick. */
/** Runtime-only pooled known-basis observer; the engine wires the tracked-basis ledger. */
transient OwnedValueObserver ownedValueObserver;
/**
* Canonical owned-value observer used by the schema-106 pooled known-basis ledger. Change
* validation happens BEFORE any canonical mutation: a false answer must leave the transaction
* exactly as it was. It never books GP itself.
*/
interface OwnedValueObserver {
/** Validate a pending effective change; false blocks the mutation before it happens. */
boolean canChange(Transaction transaction, Correction previousCorrection,
List<Flow> previousFlows, Correction nextCorrection, List<Flow> nextFlows);
/** Apply the exact effective delta after a permitted change. */
void onChanged(Transaction transaction, Correction previousCorrection, List<Flow> previousFlows);
/** Validate restoring a previously removed transaction; false blocks the restore. */
boolean canAdd(Transaction transaction);
/** Validate removing a retained transaction (undo); false blocks the removal. */
boolean canRemove(Transaction transaction);
/** Book a newly added canonical transaction's known coverage/sink depletion. */
void onAdded(Transaction transaction, boolean custodyOwned);
/** Reverse a removed transaction's known coverage. */
void onRemoved(Transaction transaction);
}

void notifyOwnedValueAdded(Transaction transaction, boolean custodyOwned) {
OwnedValueObserver observer = ownedValueObserver;
if (observer != null) observer.onAdded(transaction, custodyOwned);
}

boolean canChangeOwnedValue(Transaction transaction, Correction previousCorrection, List<Flow> previousFlows,
Correction nextCorrection, List<Flow> nextFlows) {
OwnedValueObserver observer = ownedValueObserver;
return observer == null || observer.canChange(transaction, previousCorrection, previousFlows, nextCorrection,
nextFlows);
}

/** Announces a changed row: totals, PvP and the owned-value observer follow it. */
void changed(Transaction transaction, Correction previousCorrection, List<Flow> previousFlows) {
transactionChanged(transaction);
OwnedValueObserver observer = ownedValueObserver;
if (observer != null) observer.onChanged(transaction, previousCorrection, previousFlows);
}

void log(long now, String reason, Change... changes) {
correctionHistory.add(new CorrectionRecord(now, reason, Arrays.asList(changes)));
}

long startedAtEpochMillis;
long endedAtEpochMillis;
/** Null while open and for legacy sessions whose close reason was never recorded. */
@Setter
SessionEndReason endReason;
boolean paused;
PauseReason pauseReason = NONE;
long pausedAtEpochMillis;
long totalPausedMillis;
int actionCount;
List<Transaction> transactions = new ArrayList<>();
List<PkEncounter> pkEncounters = new ArrayList<>();
/** Exact retained PvP projection; null until the first encounter. */
PkHistoryProjection pkProjection;
/** Correction-eligible mutable attribution anchors; bounded by canonical receipt retention. */
List<PkMutableAttribution> pkAttributions = new ArrayList<>();
List<CorrectionRecord> correctionHistory = new ArrayList<>();
List<UndoRecord> undoHistory = new ArrayList<>();
int transactionLimit;
long retainedRevenue;
long retainedCosts;
long retainedSuppliesCosts;
boolean retainedCostSplitComplete;
int retainedCountedTransactions;
int retainedTransfers;
long compactedTransactionCount;
List<String> tags = new ArrayList<>();
@Setter
boolean excludedFromAverages;
boolean recoveredFromCrash;
String notes;
@Setter
boolean favorite;
// Nullable by design: a target is owner-local presentation metadata, never
// an accounting input or generated transaction.
Long profitTargetGp;
// Signature targets: the actual Active-Time aim this Grind instance ran with, and the stable
// My Grind lineage link it was started from. Both are metadata; neither books anything.
Long activeTimeTargetMillis;
String grindId;
Session() {
// Gson
}

Session(String name, long startedAtEpochMillis) {
this(name, startedAtEpochMillis, SessionMode.GENERAL);
}

Session(String name, long startedAtEpochMillis, SessionMode mode) {
this.id = UUID.randomUUID().toString();
this.name = normalizeName(name);
this.mode = mode == null ? SessionMode.GENERAL : mode;
this.activityHint = this.mode == SessionMode.PK ? "PKing" : "General";
this.startedAtEpochMillis = startedAtEpochMillis;
this.transactionLimit = 2_000;
this.retainedCostSplitComplete = true;
this.notes = "";
}

void addTransaction(Transaction transaction, int maxTransactions) {
addTransaction(transaction, maxTransactions, false);
}

/**
* @param custodyOwned true for transactions already realized by a custody lifecycle; their
*                     item legs consumed their reservation and must never deplete again
*/
void addTransaction(Transaction transaction, int maxTransactions, boolean custodyOwned) {
if (transaction == null) return;
transactionLimit = max(1, maxTransactions);
transactions.add(transaction);
transactionChanged(transaction);
notifyOwnedValueAdded(transaction, custodyOwned);
int overflow = transactions.size() - transactionLimit;
if (overflow > 0) {
var removed = new ArrayList<Transaction>(transactions.subList(0, overflow));
transactions.subList(0, overflow).clear();
for (Transaction transactionToRemove : removed) compactTransaction(transactionToRemove);
}
}

void recordAction(String hint, long now) {
String activity = normalizeActivity(hint);
actionCount++;
setActivityHint(activity, now);
}

void setActivityHint(String hint, long now) {
if (blank(hint)) return;
String next = normalizeActivity(hint);
String current = getActivityHint();
if (next.equalsIgnoreCase(current)) return;
activityHint = next;
}

void rename(String value) {
name = normalizeName(value);
}

boolean setProfitTargetGp(Long value) {
if (value != null && value <= 0L) return false;
profitTargetGp = value;
return true;
}

/** The actual Active-Time target this instance ran with; null when none was set. */
Long getActiveTimeTargetMillis() {
return activeTimeTargetMillis != null && activeTimeTargetMillis > 0L ? activeTimeTargetMillis : null;
}

boolean setActiveTimeTargetMillis(Long value) {
if (value != null && value <= 0L) return false;
activeTimeTargetMillis = value;
return true;
}

/** Stable My Grind lineage link; empty when this instance was never saved as a Grind. */
String getGrindId() {
return orEmpty(grindId);
}

void setGrindId(String value) {
grindId = nonBlank(value, null);
}

boolean isLinkedToGrind() {
return !getGrindId().isEmpty();
}

void markRecoveredFromCrash() {
if (!isClosed()) recoveredFromCrash = true;
}

void pause(long now, PauseReason reason) {
if (!paused && endedAtEpochMillis == 0L) {
paused = true;
pausedAtEpochMillis = now;
pauseReason = reason == null ? MANUAL : reason;
}
}

void resume(long now) {
if (paused && endedAtEpochMillis == 0L) {
totalPausedMillis = safeAdd(totalPausedMillis, nonNeg(now - pausedAtEpochMillis));
pausedAtEpochMillis = 0L;
paused = false;
}
pauseReason = NONE;
}

PauseReason getPauseReason() {
if (!paused) return NONE;
return pauseReason == null || pauseReason == NONE ? MANUAL : pauseReason;
}

void close(long now) {
if (endedAtEpochMillis != 0L) return;
endedAtEpochMillis = max(now, startedAtEpochMillis);
}

boolean correctTransaction(String transactionId, Correction correction, long now, String reason) {
Transaction transaction = findTransaction(transactionId);
return transaction != null && correct(singletonList(transaction), correction == null ? AUTO : correction, now, reason);
}

/**
* A measured Check confirmed charge-load quantities on an automatic Review row: it becomes a
* transfer (all confirmed) or keeps only the unconfirmed flows. Automatic evidence, not an owner
* correction, so nothing is logged; the change is announced like every other change.
*/
void confirmChargeLoad(Transaction review, List<Flow> confirmed, List<Flow> remaining) {
Correction previous = review.getCorrection();
List<Flow> previousFlows = review.getFlows();
if (remaining.isEmpty()) {
if (!review.resolveChargeLoadReviewAsTransfer(confirmed)) return;
} else {
review.replaceFlows(remaining);
}
changed(review, previous, previousFlows);
}

/**
* Automatic reconciliation of an automatic estimate against a measured Check: shrink it by
* {@code quantity} of one item, voiding the row when nothing is left. Automatic evidence, not an
* owner correction, so nothing is logged; the change is announced like every other change.
*/
void settleEstimate(Transaction estimate, int itemId, long quantity, String explanation) {
if (estimate == null || itemId <= 0 || quantity <= 0L) return;
Correction previous = estimate.getCorrection();
List<Flow> previousFlows = estimate.getFlows();
long remaining = estimate.removeQuantity(itemId, false, quantity);
estimate.confidence = ClassificationConfidence.CONFIRMED;
if (remaining <= 0L) estimate.applyCorrection(IGNORE, System.currentTimeMillis(), explanation);
estimate.rewriteExplanation(explanation);
changed(estimate, previous, previousFlows);
}

/**
* Applies one decision to the still-unresolved transactions and stores one
* correction record, so a single undo restores the complete operation.
*/
int correctPendingTransactions(List<String> transactionIds, Correction correction, long now, String reason) {
Correction next = correction == null ? AUTO : correction;
if (empty(transactionIds) || next == AUTO) return 0;
var targets = new ArrayList<Transaction>();
for (String transactionId : new LinkedHashSet<>(transactionIds)) {
Transaction transaction = findTransaction(transactionId);
if (ReviewEligibility.needsOwnerDecision(transaction)) targets.add(transaction);
}
return correct(targets, next, now, nonBlank(reason, msg("eq"))) ? targets.size() : 0;
}

/**
* One owner decision over rows: every row is checked with the owned-value observer before
* any changes (one blocked row rejects the whole decision), then one undoable record is logged.
*/
boolean correct(List<Transaction> targets, Correction next, long now, String reason) {
var changes = new ArrayList<Change>();
for (Transaction transaction : targets) {
if (!canChangeOwnedValue(transaction, transaction.getCorrection(), transaction.getFlows(), next,
transaction.getFlows())) {
// The item's known coverage is reserved or already realized; leave the rows untouched.
return false;
}
changes.add(new Change(transaction.getId(), transaction.getCorrection(), next, null, null));
}
for (Transaction transaction : targets) {
Correction previous = transaction.getCorrection();
transaction.applyCorrection(next, now, reason);
changed(transaction, previous, transaction.getFlows());
}
if (!changes.isEmpty()) correctionHistory.add(new CorrectionRecord(now, reason, changes));
return !changes.isEmpty();
}

/**
* Personal item split: Net keeps only {@code keepQuantity} of {@code itemId}.
* Given-away qty is removed from counted gain (not Used). Full give-away on a
* single-item row IGNORE-corrects. Shares the correction undo stack.
*/
boolean applyItemSplit(String transactionId, int itemId, long keepQuantity, long now, String optionalNote) {
Transaction transaction = findTransaction(transactionId);
if (transaction == null || itemId <= 0) return false;
long totalGain = transaction.quantity(itemId, true);
if (totalGain <= 0L) return false;
long keep = nonNeg(min(keepQuantity, totalGain));
if (keep == totalGain) return false;
String reason = ItemSplitAccounting.reason(keep, totalGain, optionalNote);
Correction previous = transaction.getCorrection();
var flowSnapshot = new ArrayList<Flow>(transaction.getFlows());
String explanationSnapshot = transaction.getExplanation();
boolean ignore = keep <= 0L && onlyGainItem(transaction, itemId);
if (!canChangeOwnedValue(transaction, previous, flowSnapshot, ignore ? IGNORE : previous, null)) {
// The known coverage this split would shrink is reserved or already realized.
return false;
}
if (ignore) transaction.applyCorrection(IGNORE, now, reason);
else transaction.removeQuantity(itemId, true, totalGain - keep);
transaction.stampSplitProvenance(reason, now);
changed(transaction, previous, flowSnapshot);
log(now, reason, new Change(transactionId, previous, transaction.getCorrection(), flowSnapshot, explanationSnapshot));
return true;
}

/**
* Reverts the most recent correction/split when a flow snapshot is present,
* or restores the previous correction enum otherwise.
*/
boolean undoLastCorrection(long now) {
if (correctionHistory.isEmpty()) return false;
CorrectionRecord last = null;
for (int index = correctionHistory.size() - 1; index >= 0; index--) {
CorrectionRecord candidate = correctionHistory.get(index);
if (candidate != null && !candidate.isUndone()) {
last = candidate;
break;
}
}
if (last == null) return false;
List<Change> changes = last.getChanges();
if (changes.isEmpty()) return false;
var targets = new ArrayList<Transaction>(changes.size());
for (Change change : changes) {
Transaction transaction = findTransaction(change.getTransactionId());
if (transaction == null) {
// Fail closed: never partially undo a batch after retention or deletion.
return false;
}
targets.add(transaction);
}
// Validate the whole restore before any canonical mutation.
for (int index = 0; index < changes.size(); index++) {
Change change = changes.get(index);
Transaction transaction = targets.get(index);
List<Flow> nextFlows = change.hasFlowSnapshot() ? change.getFlowSnapshot() : transaction.getFlows();
if (!canChangeOwnedValue(transaction, transaction.getCorrection(),
transaction.getFlows(), change.getPreviousCorrection(), nextFlows)) {
return false;
}
}
for (int index = changes.size() - 1; index >= 0; index--) {
Change change = changes.get(index);
Transaction transaction = targets.get(index);
Correction previous = transaction.getCorrection();
List<Flow> previousFlows = transaction.getFlows();
if (change.hasFlowSnapshot()) transaction.replaceFlows(change.getFlowSnapshot());
if (change.explanationSnapshot != null) transaction.rewriteExplanation(change.explanationSnapshot);
transaction.applyCorrection(change.getPreviousCorrection(), now);
transaction.correctionReason = previousCorrectionReason(transaction.getId(), last);
changed(transaction, previous, previousFlows);
}
last.markUndone(now);
return true;
}

/** Restore metadata from the retained decision timeline without adding save fields. */
String previousCorrectionReason(String transactionId, CorrectionRecord undone) {
for (int index = correctionHistory.indexOf(undone) - 1; index >= 0; index--) {
CorrectionRecord record = correctionHistory.get(index);
if (record == null || record.isUndone()) continue;
for (Change change : record.getChanges()) {
if (transactionId.equals(change.getTransactionId())
&& (!change.hasFlowSnapshot() || change.getPreviousCorrection() != change.newCorrection
|| ItemSplitAccounting.isSplitReason(record.getReason()))) return record.getReason();
}
}
return "";
}

static boolean onlyGainItem(Transaction transaction, int itemId) {
if (transaction == null) return false;
boolean sawTarget = false;
for (Flow flow : transaction.getFlows()) {
if (flow == null || flow.quantityDelta == 0L) continue;
if (flow.itemId != itemId) return false;
if (flow.quantityDelta > 0L) sawTarget = true;
}
return sawTarget;
}

/**
* Reverses unrecovered own-drop cost at original valuation. Full recovery
* IGNORE-corrects the drop row; partial recovery shrinks its cost flows.
*/
boolean recoverOwnDropCosts(String transactionId, int itemId, long quantity, long now, String reason) {
Transaction transaction = findTransaction(transactionId);
if (transaction == null || quantity <= 0L || itemId <= 0 || transaction.quantity(itemId, false) <= 0L) {
return false;
}
var flowSnapshot = new ArrayList<Flow>(transaction.getFlows());
String explanationSnapshot = transaction.getExplanation();
Correction previous = transaction.getCorrection();
long remaining = transaction.removeQuantity(itemId, false, quantity);
Correction next = remaining <= 0L ? IGNORE : previous;
if (!canChangeOwnedValue(transaction, previous, flowSnapshot, next, remaining <= 0L ? null : transaction.getFlows())) {
// Cost-side recovery only shrinks costs; a blocked result must leave the row intact.
transaction.replaceFlows(flowSnapshot);
return false;
}
if (remaining <= 0L) transaction.applyCorrection(next, now, reason);
log(now, reason, new Change(transactionId, previous, next, flowSnapshot, explanationSnapshot));
changed(transaction, previous, flowSnapshot);
return true;
}

Transaction undoLastTransaction(long now) {
if (transactions.isEmpty()) return null;
Transaction removed = transactions.get(transactions.size() - 1);
OwnedValueObserver observer = ownedValueObserver;
if (observer != null && !observer.canRemove(removed)) {
// Known coverage was already reserved/realized downstream; leave it untouched.
return null;
}
transactions.remove(transactions.size() - 1);
notifyChanged();
for (PkEncounter encounter : pkEncounters) {
encounter.removeTransactionId(removed.getId());
encounter.removeFinancialContribution(removed.getId());
}
detachPkContribution(removed.getId());
if (observer != null) observer.onRemoved(removed);
undoHistory.add(new UndoRecord(removed, now));
int overflow = undoHistory.size() - MAX_UNDO_HISTORY;
if (overflow > 0) undoHistory.subList(0, overflow).clear();
return removed;
}

Transaction restoreLastUndo(long now) {
for (int index = undoHistory.size() - 1; index >= 0; index--) {
UndoRecord record = undoHistory.get(index);
if (record == null || record.restored || record.transaction == null) continue;
Transaction restored = record.transaction;
OwnedValueObserver observer = ownedValueObserver;
if (observer != null && !observer.canAdd(restored)) {
// Restoring would re-add known coverage after a later realization; refuse it.
return null;
}
int effectiveLimit = transactionLimit <= 0 ? 2_000 : transactionLimit;
int overflow = transactions.size() + 1 - effectiveLimit;
if (overflow > 0) {
var evicted = new ArrayList<Transaction>(transactions.subList(0, overflow));
transactions.subList(0, overflow).clear();
for (Transaction transactionToRemove : evicted) {
// Restoring an undo is also a bounded-ledger insertion.
// The row displaced to make room must be summarized exactly
// as it would be for a newly observed transaction.
compactTransaction(transactionToRemove);
}
}
transactions.add(restored);
transactionChanged(restored);
notifyOwnedValueAdded(restored, false);
record.markRestored(now);
return restored;
}
return null;
}

Transaction findTransaction(String transactionId) {
if (empty(transactionId)) return null;
for (Transaction transaction : transactions) {
if (transactionId.equals(transaction.getId())) return transaction;
}
return null;
}

PkEncounter addPkEncounter(EncounterType type, long now, String label, ClassificationConfidence confidence,
String explanation) {
PkHistoryProjection projection = ensurePkProjection();
var encounter = new PkEncounter(type, now, label, confidence, explanation);
pkEncounters.add(encounter);
projection.recordEncounter(encounter.getType());
notifyChanged();
recordAction("PKing", now);
return encounter;
}

void attachTransactionToEncounter(String transactionId, String encounterId, boolean asPkSupplyCost) {
Transaction transaction = findTransaction(transactionId);
PkEncounter encounter = findPkEncounter(encounterId);
if (transaction == null || encounter == null) return;
transaction.assignEncounter(encounterId, asPkSupplyCost);
encounter.addTransactionId(transactionId);
refreshFinancialEncounterContribution(transaction);
}

void attachRecentCostsToEncounter(String encounterId, long now, long lookbackMillis) {
long cutoff = nonNeg(now - max(0L, lookbackMillis));
for (int index = transactions.size() - 1; index >= 0; index--) {
Transaction transaction = transactions.get(index);
if (transaction.timestampEpochMillis < cutoff) break;
if (!transaction.getEncounterId().isEmpty()) continue;
if (transaction.revenue == 0L && transaction.costs > 0L
&& transaction.getAutomaticType() == TransactionType.CONSUMPTION) {
attachTransactionToEncounter(transaction.getId(), encounterId, true);
}
}
}

PkEncounter findPkEncounter(String encounterId) {
if (empty(encounterId)) return null;
for (PkEncounter encounter : pkEncounters) {
if (encounterId.equals(encounter.getId())) return encounter;
}
return null;
}

void compactTransactionFromEncounters(String transactionId) {
for (PkEncounter encounter : pkEncounters) {
if (encounter.getTransactionIds().contains(transactionId)) {
encounter.compactFinancialContribution(transactionId);
encounter.removeTransactionId(transactionId);
}
}
}

void compactTransaction(Transaction transaction) {
if (transaction == null) return;
compactedTransactionCount++;
if (transaction.getType() == TransactionType.TRANSFER) {
retainedTransfers++;
finalizePkContribution(transaction.getId());
compactTransactionFromEncounters(transaction.getId());
return;
}
if (transaction.isCounted()) {
retainedCountedTransactions++;
TransactionAmounts amounts = transaction(transaction);
retainedRevenue = safeAdd(retainedRevenue, amounts.revenue);
retainedCosts = safeAdd(retainedCosts, amounts.costs);
AccountingProjection.CostSplit split = costSplit(transaction);
if (!split.available) retainedCostSplitComplete = false;
else retainedSuppliesCosts = safeAdd(retainedSuppliesCosts, split.supplies);
}
finalizePkContribution(transaction.getId());
compactTransactionFromEncounters(transaction.getId());
}

/** A counted row changed: advance the revision and refresh its PvP encounter money. */
void transactionChanged(Transaction transaction) {
if (transaction == null) return;
notifyChanged();
refreshFinancialEncounterContribution(transaction);
}

/** A receipt's current money on its encounter row and its correction-eligible anchor. */
void refreshFinancialEncounterContribution(Transaction transaction) {
if (transaction == null || transaction.getEncounterId().isEmpty()) return;
PkEncounter encounter = findPkEncounter(transaction.getEncounterId());
if (encounter != null) encounter.setFinancialContribution(transaction);
PkMutableAttribution attribution = findPkAttribution(transaction.getEncounterId());
if (attribution == null && encounter != null) {
// Undo removes the transaction from the encounter and may finalize an empty or
// partially finalized anchor. Redo restores a live canonical receipt, so recreate
// only that bounded child anchor; the encounter count is already in the projection.
attribution = ensurePkAttribution(encounter);
}
if (attribution != null) attribution.putChild(transaction.getId(), PkMoney.of(transaction));
}

PkMetrics pkMetrics() {
return pkProjection == null ? PkMetrics.none() : projectedPkMetrics();
}

/**
* Exact retained projection plus bounded correction-eligible mutable anchors. Counts and streak
* are all-history; finalized money/extrema never decrease; medians are exact over the retained
* detail window only.
*/
PkMetrics projectedPkMetrics() {
var facts = new PkProfileBase();
facts.merge(pkProjection, getPkAttributions());
boolean split = facts.isCostSplitComplete() && facts.getSuppliesCosts() <= facts.getCosts();
var killNets = new ArrayList<Long>();
var deathLosses = new ArrayList<Long>();
for (PkEncounter encounter : chronologicalEncounters()) {
if (encounter.getType() == EncounterType.KILL) killNets.add(encounter.getFinancialNetGp());
else deathLosses.add(encounter.getFinancialLossGp());
}
return new PkMetrics(facts.getKills(), facts.getDeaths(), pkProjection.getStreak(), facts.getRevenue(),
facts.getCosts(), facts.getNet(), facts.getBestKill(), facts.getLargestDeathLoss(), facts.getKillNet(),
facts.getDeathLoss(), facts.getSuppliesCosts(), split ? facts.getOtherCosts() : 0L, split,
PkMetrics.median(killNets), PkMetrics.median(deathLosses), pkProjection.getDetailScope(),
(int) min(Integer.MAX_VALUE, pkProjection.getRetainedDetailCount()));
}

/** Longest run of kills without a death over the encounters held, never below the current run. */
int bestPkStreak() {
int best = 0;
int run = 0;
for (PkEncounter encounter : chronologicalEncounters()) {
run = encounter.getType() == EncounterType.KILL ? run + 1 : 0;
best = max(best, run);
}
return max(best, pkMetrics().currentStreak);
}

List<PkEncounter> chronologicalEncounters() {
var chronological = new ArrayList<PkEncounter>();
for (PkEncounter encounter : getPkEncounters()) {
if (encounter != null) chronological.add(encounter);
}
chronological.sort(Comparator.comparingLong((itemData -> itemData.timestampEpochMillis)));
return chronological;
}

@AllArgsConstructor
static class MoneyFold {
long revenue;
long costs;
long supplies;
boolean costSplitAvailable = true;
TransactionAmounts add(Transaction transaction) {
TransactionAmounts amounts = transaction(transaction);
if (!amounts.available || !amounts.included) return amounts;
revenue = safeAdd(revenue, amounts.revenue);
costs = safeAdd(costs, amounts.costs);
AccountingProjection.CostSplit split = costSplit(transaction);
if (!split.available) costSplitAvailable = false;
else supplies = safeAdd(supplies, split.supplies);
return amounts;
}
}

PkHistoryProjection ensurePkProjection() {
if (pkProjection == null) pkProjection = new PkHistoryProjection();
return pkProjection;
}

List<PkMutableAttribution> getPkAttributions() {
return unmodifiableList(new ArrayList<>(pkAttributions));
}

PkMutableAttribution findPkAttribution(String encounterId) {
if (empty(encounterId)) return null;
for (PkMutableAttribution attribution : pkAttributions) {
if (attribution != null && encounterId.equals(attribution.getEncounterId())) return attribution;
}
return null;
}

PkMutableAttribution findPkAttributionContaining(String transactionId) {
if (empty(transactionId)) return null;
for (PkMutableAttribution attribution : pkAttributions) {
if (attribution != null && attribution.holds(transactionId)) return attribution;
}
return null;
}

PkMutableAttribution ensurePkAttribution(PkEncounter encounter) {
ensurePkProjection();
PkMutableAttribution existing = findPkAttribution(encounter.getId());
if (existing != null) return existing;
var created = new PkMutableAttribution(encounter.getId(), encounter.getType());
pkAttributions.add(created);
return created;
}

void detachPkContribution(String transactionId) {
PkMutableAttribution attribution = findPkAttributionContaining(transactionId);
if (attribution == null) return;
attribution.removeChild(transactionId);
if (!attribution.hasChildren()) finalizePkAttribution(attribution);
}

void finalizePkContribution(String transactionId) {
PkMutableAttribution attribution = findPkAttributionContaining(transactionId);
if (attribution == null) return;
attribution.finalizeChild(transactionId);
if (!attribution.hasChildren()) finalizePkAttribution(attribution);
}

void finalizePkAttribution(PkMutableAttribution attribution) {
if (attribution == null) return;
ensurePkProjection().fold(attribution.getType(), attribution.current());
pkAttributions.remove(attribution);
notifyChanged();
}

/** Removes one compacted detailed encounter row; exact facts are already retained. */
boolean removePkEncounter(PkEncounter encounter) {
if (encounter == null || !pkEncounters.remove(encounter)) return false;
if (pkProjection != null) pkProjection.markDetailCompacted();
return true;
}

SessionMetrics metrics(long now) {
var money = new MoneyFold(retainedRevenue, retainedCosts, retainedSuppliesCosts,
compactedTransactionCount == 0L || retainedCostSplitComplete);
for (Transaction transaction : transactions) {
if (transaction == null) continue;
money.add(transaction);
}
if (money.supplies > money.costs) money.costSplitAvailable = false;
long net = safeSubtract(money.revenue, money.costs);
long elapsed = getElapsedMillis(now);
return new SessionMetrics(getActivityHint(), paused, elapsed, money.revenue, money.costs, net,
hourly(net, elapsed), money.costSplitAvailable ? money.supplies : 0L,
money.costSplitAvailable ? safeSubtract(money.costs, money.supplies) : 0L, money.costSplitAvailable);
}

/**
* The correction-aware money folded out of individually inspectable rows by compaction.
* Retained rows plus this contribution reconcile exactly to {@link #metrics}; CSV writes it
* as the explicit {@code COMPACTED} row (charter T).
*/
@AllArgsConstructor
static class CompactedContribution {
final long rows;
final long revenue;
final long costs;
final long suppliesCosts;
final boolean costSplitAvailable;
long getNet() { return safeSubtract(revenue, costs); }
}

CompactedContribution compactedContribution() {
return new CompactedContribution(compactedTransactionCount, retainedRevenue, retainedCosts,
retainedSuppliesCosts, compactedTransactionCount == 0L || retainedCostSplitComplete);
}

long getCompactedRevenue() { return retainedRevenue; }
long getCompactedCosts() { return retainedCosts; }


static long hourly(long value, long elapsedMillis) {
if (elapsedMillis <= 0L) return 0L;
double rate = value * (3_600_000.0d / elapsedMillis);
if (rate >= Long.MAX_VALUE) return Long.MAX_VALUE;
if (rate <= Long.MIN_VALUE) return Long.MIN_VALUE;
return round(rate);
}

long getElapsedMillis(long now) {
long endpoint = endedAtEpochMillis == 0L ? now : endedAtEpochMillis;
long activePaused = paused ? nonNeg(endpoint - pausedAtEpochMillis) : 0L;
return nonNeg(endpoint - startedAtEpochMillis - totalPausedMillis - activePaused);
}

static String normalizeName(String value) {
if (blank(value)) return DURABLE_OWNER_NAME;
return value.trim();
}

String getId() {
if (empty(id)) id = UUID.randomUUID().toString();
return id;
}

String getName() { return normalizeName(name); }
SessionOwnerKind getOwnerKind() {
return ownerKind == null ? SessionOwnerKind.UNKNOWN : ownerKind;
}

void setOwnerKind(SessionOwnerKind value) {
SessionOwnerKind next = value == null ? SessionOwnerKind.UNKNOWN : value;
if (getOwnerKind() != next) {
ownerKind = next;
notifyChanged();
}
}

void setChangeListener(Runnable listener) { changeListener = listener; }
void notifyChanged() {
Runnable listener = changeListener;
if (listener != null) listener.run();
}

String getActivityHint() {
String normalized = normalizeActivity(activityHint);
if (getMode() == SessionMode.PK && "General".equalsIgnoreCase(normalized)) return "PKing";
return normalized;
}

SessionMode getMode() { return mode == null ? SessionMode.GENERAL : mode; }
boolean isClosed() { return endedAtEpochMillis != 0L; }
Long getProfitTargetGp() { return profitTargetGp != null && profitTargetGp > 0L ? profitTargetGp : null; }
List<Transaction> getTransactions() {
return unmodifiableList(transactions);
}

/** Timestamp of the oldest retained row, or {@code Long.MIN_VALUE} when none is retained. */

/**
* Receipt retention (section L): folds retained rows dated before {@code cutoffEpochMillis}
* into this session's correction-aware archive totals, oldest first, skipping rows the
* caller still needs individually (an unresolved claim's audit row or its settlement).
* Undo/correction entries that reference a folded row are pruned at the same boundary.
* Compacted rows are no longer individually correctable; totals stay exact.
*/
int compactTransactionsBefore(long cutoffEpochMillis, Predicate<Transaction> keep) {
if (transactions.isEmpty()) return 0;
var folded = new ArrayList<Transaction>();
for (Transaction transaction : transactions) {
if (transaction == null) continue;
if (transaction.timestampEpochMillis >= cutoffEpochMillis) continue;
if (keep != null && keep.test(transaction)) continue;
folded.add(transaction);
}
if (folded.isEmpty()) return 0;
var foldedIds = new HashSet<String>();
for (Transaction transaction : folded) {
foldedIds.add(transaction.getId());
transactions.remove(transaction);
compactTransaction(transaction);
}
undoHistory.removeIf(record -> record != null && foldedIds.contains(record.getTransactionId()));
correctionHistory.removeIf(record -> record != null && record.touchesAny(foldedIds));
return folded.size();
}

List<PkEncounter> getPkEncounters() {
return unmodifiableList(pkEncounters);
}
}
