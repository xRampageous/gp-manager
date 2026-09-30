package com.gpmanager;
import static com.gpmanager.Ak.msg;
import com.gpmanager.Bp.Ax;
import com.gpmanager.Dx.Change;
import lombok.*;
import java.util.*;
import java.util.function.Predicate;
import static com.gpmanager.Ae.*;
import static com.gpmanager.Ag.*;
import static com.gpmanager.Ed.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static com.gpmanager.Ah.*;
import static com.gpmanager.Bp.*;
class Ad {
/** The always-on General tracker is shown as "Overall". */
static final String DURABLE_OWNER_NAME = "Overall";
static final int MAX_UNDO_HISTORY = 32;
String id;
String name;
String activityHint;
Cx mode;
/** Explicit owner provenance; legacy saves intentionally deserialize as UNKNOWN. */
Bt ownerKind = Bt.UNKNOWN;
/** Runtime-only signal/revision for profile totals derived from session-day summaries. */
transient Runnable ayl;
/** Runtime-only narrow live-clock patch; avoids rebuilding historical rollups on each tick. */
/** Runtime-only pooled known-basis observer; the engine wires the tracked-basis ledger. */
transient Dj ownedValueObserver;
/**
* Canonical owned-value observer used by the schema-106 pooled known-basis ledger. Change
* validation happens BEFORE any canonical mutation: a false answer must leave the transaction
* exactly as it was. It never books GP itself.
*/
interface Dj {
/** Validate a pending effective change; false blocks the mutation before it happens. */
boolean canChange(Ac transaction, Ah previousCorrection,
List<Ab> previousFlows, Ah nextCorrection, List<Ab> nextFlows);
/** Apply the exact effective delta after a permitted change. */
void onChanged(Ac transaction, Ah previousCorrection,
List<Ab> previousFlows);
/** Validate restoring a previously removed transaction; false blocks the restore. */
boolean canAdd(Ac transaction);
/** Validate removing a retained transaction (undo); false blocks the removal. */
boolean canRemove(Ac transaction);
/** Book a newly added canonical transaction's known coverage/sink depletion. */
void onAdded(Ac transaction, boolean custodyOwned);
/** Reverse a removed transaction's known coverage. */
void onRemoved(Ac transaction);
}
void abl(Ac transaction, boolean custodyOwned) {
Dj observer = ownedValueObserver;
if (observer != null) {
observer.onAdded(transaction, custodyOwned);
}
}
boolean ng(Ac transaction,
Ah previousCorrection, List<Ab> previousFlows,
Ah nextCorrection, List<Ab> nextFlows) {
Dj observer = ownedValueObserver;
return observer == null
|| observer.canChange(transaction, previousCorrection, previousFlows, nextCorrection,
nextFlows);
}
/** Announces a changed row: totals, PvP and the owned-value observer follow it. */
void changed(Ac transaction,
Ah previousCorrection, List<Ab> previousFlows) {
ajt(transaction);
Dj observer = ownedValueObserver;
if (observer != null) {
observer.onChanged(transaction, previousCorrection, previousFlows);
}
}
void log(long now, String reason, Change... changes) {
correctionHistory.add(new Dx(now, reason, Arrays.asList(changes)));
}
long startedAtEpochMillis;
long endedAtEpochMillis;
/** Null while open and for legacy sessions whose close reason was never recorded. */
@Setter
SessionEndReason endReason;
boolean paused;
Ed pauseReason = NONE;
long pausedAtEpochMillis;
long totalPausedMillis;
int actionCount;
List<Ac> transactions = new ArrayList<>();
List<Bx> pkEncounters = new ArrayList<>();
/** Exact retained PvP projection; null until the first encounter. */
PkHistoryProjection pkProjection;
/** Correction-eligible mutable attribution anchors; bounded by canonical receipt retention. */
List<Ay> pkAttributions = new ArrayList<>();
List<Dx> correctionHistory = new ArrayList<>();
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
Ad() {
// Gson
}
Ad(String name, long startedAtEpochMillis) {
this(name, startedAtEpochMillis, Cx.GENERAL);
}
Ad(String name, long startedAtEpochMillis, Cx mode) {
this.id = UUID.randomUUID().toString();
this.name = aaw(name);
this.mode = mode == null ? Cx.GENERAL : mode;
this.activityHint = this.mode == Cx.PK ? "PKing" : "General";
this.startedAtEpochMillis = startedAtEpochMillis;
this.transactionLimit = 2_000;
this.retainedCostSplitComplete = true;
this.notes = "";
}
void kf(Ac transaction, int maxTransactions) {
kf(transaction, maxTransactions, false);
}
/**
* @param custodyOwned true for transactions already realized by a custody lifecycle; their
*                     item legs consumed their reservation and must never deplete again
*/
void kf(Ac transaction, int maxTransactions, boolean custodyOwned) {
if (transaction == null) {
return;
}
transactionLimit = max(1, maxTransactions);
transactions.add(transaction);
ajt(transaction);
abl(transaction, custodyOwned);
int overflow = transactions.size() - transactionLimit;
if (overflow > 0) {
var removed = new ArrayList<Ac>(transactions.subList(0, overflow));
transactions.subList(0, overflow).clear();
for (Ac transactionToRemove : removed) {
ph(transactionToRemove);
}
}
}
void aeh(String hint, long now) {
String activity = aas(hint);
actionCount++;
setActivityHint(activity, now);
}
void setActivityHint(String hint, long now) {
if (blank(hint)) {
return;
}
String next = aas(hint);
String current = getActivityHint();
if (next.equalsIgnoreCase(current)) {
return;
}
activityHint = next;
}
void rename(String value) {
name = aaw(value);
}
boolean setProfitTargetGp(Long value) {
if (value != null && value <= 0L) {
return false;
}
profitTargetGp = value;
return true;
}
/** The actual Active-Time target this instance ran with; null when none was set. */
Long getActiveTimeTargetMillis() {
return activeTimeTargetMillis != null && activeTimeTargetMillis > 0L ? activeTimeTargetMillis : null;
}
boolean setActiveTimeTargetMillis(Long value) {
if (value != null && value <= 0L) {
return false;
}
activeTimeTargetMillis = value;
return true;
}
/** Stable My Grind lineage link; empty when this instance was never saved as a Grind. */
String getGrindId() {
return axw(grindId);
}
void setGrindId(String value) {
grindId = awq(value, null);
}
boolean xf() {
return !getGrindId().isEmpty();
}
void zg() {
if (!isClosed()) {
recoveredFromCrash = true;
}
}
void pause(long now, Ed reason) {
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
Ed getPauseReason() {
if (!paused) {
return NONE;
}
return pauseReason == null || pauseReason == NONE ? MANUAL : pauseReason;
}
void close(long now) {
if (endedAtEpochMillis != 0L) {
return;
}
endedAtEpochMillis = max(now, startedAtEpochMillis);
}
boolean qi(
String transactionId,
Ah correction,
long now,
String reason) {
Ac transaction = sw(transactionId);
return transaction != null && correct(singletonList(transaction),
correction == null ? AUTO : correction, now, reason);
}
/**
* A measured Check confirmed charge-load quantities on an automatic Review row: it becomes a
* transfer (all confirmed) or keeps only the unconfirmed flows. Automatic evidence, not an owner
* correction, so nothing is logged; the change is announced like every other change.
*/
void pz(Ac review, List<Ab> confirmed, List<Ab> remaining) {
Ah previous = review.getCorrection();
List<Ab> previousFlows = review.getFlows();
if (remaining.isEmpty()) {
if (!review.agg(confirmed)) {
return;
}
} else {
review.afj(remaining);
}
changed(review, previous, previousFlows);
}
/**
 * Automatic reconciliation of an automatic estimate against a measured Check: shrink it by
 * {@code quantity} of one item, voiding the row when nothing is left. Automatic evidence, not an
 * owner correction, so nothing is logged; the change is announced like every other change.
 */
void pzz(Ac estimate, int itemId, long quantity, String explanation) {
if (estimate == null || itemId <= 0 || quantity <= 0L) {
return;
}
Ah previous = estimate.getCorrection();
List<Ab> previousFlows = estimate.getFlows();
long remaining = estimate.afg(itemId, false, quantity);
estimate.confidence = Bd.CONFIRMED;
if (remaining <= 0L) {
estimate.ko(IGNORE, System.currentTimeMillis(), explanation);
}
estimate.agt(explanation);
changed(estimate, previous, previousFlows);
}
/**
* Applies one decision to the still-unresolved transactions and stores one
* correction record, so a single undo restores the complete operation.
*/
int qh(
List<String> transactionIds,
Ah correction,
long now,
String reason) {
Ah next = correction == null ? AUTO : correction;
if (empty(transactionIds) || next == AUTO) {
return 0;
}
var targets = new ArrayList<Ac>();
for (String transactionId : new LinkedHashSet<>(transactionIds)) {
Ac transaction = sw(transactionId);
if (Eh.aal(transaction)) {
targets.add(transaction);
}
}
return correct(targets, next, now, awq(reason, msg("eq"))) ? targets.size() : 0;
}
/**
* One owner decision over rows: every row is checked with the owned-value observer before
* any changes (one blocked row rejects the whole decision), then one undoable record is logged.
*/
boolean correct(List<Ac> targets, Ah next, long now, String reason) {
var changes = new ArrayList<Change>();
for (Ac transaction : targets) {
if (!ng(transaction, transaction.getCorrection(), transaction.getFlows(), next,
transaction.getFlows())) {
// The item's known coverage is reserved or already realized; leave the rows untouched.
return false;
}
changes.add(new Change(transaction.getId(), transaction.getCorrection(), next, null, null));
}
for (Ac transaction : targets) {
Ah previous = transaction.getCorrection();
transaction.ko(next, now, reason);
changed(transaction, previous, transaction.getFlows());
}
if (!changes.isEmpty()) {
correctionHistory.add(new Dx(now, reason, changes));
}
return !changes.isEmpty();
}
/**
* Personal item split: Net keeps only {@code keepQuantity} of {@code itemId}.
* Given-away qty is removed from counted gain (not Used). Full give-away on a
* single-item row IGNORE-corrects. Shares the correction undo stack.
*/
boolean kr(
String transactionId,
int itemId,
long keepQuantity,
long now,
String optionalNote) {
Ac transaction = sw(transactionId);
if (transaction == null || itemId <= 0) {
return false;
}
long anb = transaction.quantity(itemId, true);
if (anb <= 0L) {
return false;
}
long keep = nonNeg(min(keepQuantity, anb));
if (keep == anb) {
return false;
}
String reason = Dm.reason(keep, anb, optionalNote);
Ah previous = transaction.getCorrection();
var flowSnapshot = new ArrayList<Ab>(transaction.getFlows());
String explanationSnapshot = transaction.getExplanation();
boolean ignore = keep <= 0L && aco(transaction, itemId);
if (!ng(transaction, previous, flowSnapshot,
ignore ? IGNORE : previous, null)) {
// The known coverage this split would shrink is reserved or already realized.
return false;
}
if (ignore) {
transaction.ko(IGNORE, now, reason);
} else {
transaction.afg(itemId, true, anb - keep);
}
transaction.ajk(reason, now);
changed(transaction, previous, flowSnapshot);
log(now, reason, new Change(transactionId, previous, transaction.getCorrection(),
flowSnapshot, explanationSnapshot));
return true;
}
/**
* Reverts the most recent correction/split when a flow snapshot is present,
* or restores the previous correction enum otherwise.
*/
boolean akb(long now) {
if (correctionHistory.isEmpty()) {
return false;
}
Dx last = null;
for (int index = correctionHistory.size() - 1; index >= 0; index--) {
Dx candidate = correctionHistory.get(index);
if (candidate != null && !candidate.isUndone()) {
last = candidate;
break;
}
}
if (last == null) {
return false;
}
List<Change> changes = last.getChanges();
if (changes.isEmpty()) {
return false;
}
var targets = new ArrayList<Ac>(changes.size());
for (Change change : changes) {
Ac transaction = sw(change.getTransactionId());
if (transaction == null) {
// Fail closed: never partially undo a batch after retention or deletion.
return false;
}
targets.add(transaction);
}
// Validate the whole restore before any canonical mutation.
for (int index = 0; index < changes.size(); index++) {
Change change = changes.get(index);
Ac transaction = targets.get(index);
List<Ab> nextFlows = change.hasFlowSnapshot()
? change.getFlowSnapshot() : transaction.getFlows();
if (!ng(transaction, transaction.getCorrection(),
transaction.getFlows(), change.getPreviousCorrection(), nextFlows)) {
return false;
}
}
for (int index = changes.size() - 1; index >= 0; index--) {
Change change = changes.get(index);
Ac transaction = targets.get(index);
Ah previous = transaction.getCorrection();
List<Ab> previousFlows = transaction.getFlows();
if (change.hasFlowSnapshot()) {
transaction.afj(change.getFlowSnapshot());
}
if (change.explanationSnapshot != null) {
transaction.agt(change.explanationSnapshot);
}
transaction.ko(change.getPreviousCorrection(), now);
transaction.correctionReason = adl(transaction.getId(), last);
changed(transaction, previous, previousFlows);
}
last.zr(now);
return true;
}
/** Restore metadata from the retained decision timeline without adding save fields. */
String adl(String transactionId, Dx undone) {
for (int index = correctionHistory.indexOf(undone) - 1; index >= 0; index--) {
Dx record = correctionHistory.get(index);
if (record == null || record.isUndone()) continue;
for (Change change : record.getChanges()) {
if (transactionId.equals(change.getTransactionId())
&& (!change.hasFlowSnapshot() || change.getPreviousCorrection() != change.newCorrection
|| Dm.xr(record.getReason()))) return record.getReason();
}
}
return "";
}
static boolean aco(Ac transaction, int itemId) {
if (transaction == null) {
return false;
}
boolean sawTarget = false;
for (Ab flow : transaction.getFlows()) {
if (flow == null || flow.quantityDelta == 0L) {
continue;
}
if (flow.itemId != itemId) {
return false;
}
if (flow.quantityDelta > 0L) {
sawTarget = true;
}
}
return sawTarget;
}
/**
* Reverses unrecovered own-drop cost at original valuation. Full recovery
* IGNORE-corrects the drop row; partial recovery shrinks its cost flows.
*/
boolean afc(
String transactionId,
int itemId,
long quantity,
long now,
String reason) {
Ac transaction = sw(transactionId);
if (transaction == null || quantity <= 0L || itemId <= 0 || transaction.quantity(itemId, false) <= 0L) {
return false;
}
var flowSnapshot = new ArrayList<Ab>(transaction.getFlows());
String explanationSnapshot = transaction.getExplanation();
Ah previous = transaction.getCorrection();
long remaining = transaction.afg(itemId, false, quantity);
Ah next = remaining <= 0L ? IGNORE : previous;
if (!ng(transaction, previous, flowSnapshot, next,
remaining <= 0L ? null : transaction.getFlows())) {
// Cost-side recovery only shrinks costs; a blocked result must leave the row intact.
transaction.afj(flowSnapshot);
return false;
}
if (remaining <= 0L) {
transaction.ko(next, now, reason);
}
log(now, reason, new Change(transactionId, previous, next, flowSnapshot, explanationSnapshot));
changed(transaction, previous, flowSnapshot);
return true;
}
Ac akc(long now) {
if (transactions.isEmpty()) {
return null;
}
Ac removed = transactions.get(transactions.size() - 1);
Dj observer = ownedValueObserver;
if (observer != null && !observer.canRemove(removed)) {
// Known coverage was already reserved/realized downstream; leave it untouched.
return null;
}
transactions.remove(transactions.size() - 1);
abk();
for (Bx encounter : pkEncounters) {
encounter.afh(removed.getId());
encounter.afd(removed.getId());
}
qy(removed.getId());
if (observer != null) {
observer.onRemoved(removed);
}
undoHistory.add(new UndoRecord(removed, now));
int overflow = undoHistory.size() - MAX_UNDO_HISTORY;
if (overflow > 0) {
undoHistory.subList(0, overflow).clear();
}
return removed;
}
Ac agn(long now) {
for (int index = undoHistory.size() - 1; index >= 0; index--) {
UndoRecord record = undoHistory.get(index);
if (record == null || record.restored || record.transaction == null) {
continue;
}
Ac restored = record.transaction;
Dj observer = ownedValueObserver;
if (observer != null && !observer.canAdd(restored)) {
// Restoring would re-add known coverage after a later realization; refuse it.
return null;
}
int ary = transactionLimit <= 0 ? 2_000 : transactionLimit;
int overflow = transactions.size() + 1 - ary;
if (overflow > 0) {
var asc = new ArrayList<Ac>(transactions.subList(0, overflow));
transactions.subList(0, overflow).clear();
for (Ac transactionToRemove : asc) {
// Restoring an undo is also a bounded-ledger insertion.
// The row displaced to make room must be summarized exactly
// as it would be for a newly observed transaction.
ph(transactionToRemove);
}
}
transactions.add(restored);
ajt(restored);
abl(restored, false);
record.zp(now);
return restored;
}
return null;
}
Ac sw(String transactionId) {
if (empty(transactionId)) {
return null;
}
for (Ac transaction : transactions) {
if (transactionId.equals(transaction.getId())) {
return transaction;
}
}
return null;
}
Bx ke(
Be type,
long now,
String label,
Bd confidence,
String explanation) {
PkHistoryProjection projection = rl();
var encounter = new Bx(type, now, label, confidence, explanation);
pkEncounters.add(encounter);
projection.aev(encounter.getType());
abk();
aeh("PKing", now);
return encounter;
}
void ll(
String transactionId,
String encounterId,
boolean asPkSupplyCost) {
Ac transaction = sw(transactionId);
Bx encounter = tf(encounterId);
if (transaction == null || encounter == null) {
return;
}
transaction.lf(encounterId, asPkSupplyCost);
encounter.kg(transactionId);
aes(transaction);
}
void lj(
String encounterId,
long now,
long lookbackMillis) {
long cutoff = nonNeg(now - max(0L, lookbackMillis));
for (int index = transactions.size() - 1; index >= 0; index--) {
Ac transaction = transactions.get(index);
if (transaction.timestampEpochMillis < cutoff) {
break;
}
if (!transaction.getEncounterId().isEmpty()) {
continue;
}
if (transaction.revenue == 0L
&& transaction.costs > 0L
&& transaction.tm() == Ai.CONSUMPTION) {
ll(transaction.getId(), encounterId, true);
}
}
}
Bx tf(String encounterId) {
if (empty(encounterId)) {
return null;
}
for (Bx encounter : pkEncounters) {
if (encounterId.equals(encounter.getId())) {
return encounter;
}
}
return null;
}
void pi(String transactionId) {
for (Bx encounter : pkEncounters) {
if (encounter.getTransactionIds().contains(transactionId)) {
encounter.pf(transactionId);
encounter.afh(transactionId);
}
}
}
void ph(Ac transaction) {
if (transaction == null) {
return;
}
compactedTransactionCount++;
if (transaction.getType() == Ai.TRANSFER) {
retainedTransfers++;
sp(transaction.getId());
pi(transaction.getId());
return;
}
if (transaction.isCounted()) {
retainedCountedTransactions++;
Ax amounts = transaction(transaction);
retainedRevenue = safeAdd(retainedRevenue, amounts.revenue);
retainedCosts = safeAdd(retainedCosts, amounts.costs);
Bp.Du split = costSplit(transaction);
if (!split.available) {
retainedCostSplitComplete = false;
} else {
retainedSuppliesCosts = safeAdd(retainedSuppliesCosts, split.supplies);
}
}
sp(transaction.getId());
pi(transaction.getId());
}
/** A counted row changed: advance the revision and refresh its PvP encounter money. */
void ajt(Ac transaction) {
if (transaction == null) return;
abk();
aes(transaction);
}
/** A receipt's current money on its encounter row and its correction-eligible anchor. */
void aes(Ac transaction) {
if (transaction == null || transaction.getEncounterId().isEmpty()) return;
Bx encounter = tf(transaction.getEncounterId());
if (encounter != null) encounter.ahr(transaction);
Ay attribution = st(transaction.getEncounterId());
if (attribution == null && encounter != null) {
// Undo removes the transaction from the encounter and may finalize an empty or
// partially finalized anchor. Redo restores a live canonical receipt, so recreate
// only that bounded child anchor; the encounter count is already in the projection.
attribution = ro(encounter);
}
if (attribution != null) attribution.awv(transaction.getId(), PkMoney.of(transaction));
}
Dt ava() {
return pkProjection == null ? Dt.none() : adw();
}
/**
* Exact retained projection plus bounded correction-eligible mutable anchors. Counts and streak
* are all-history; finalized money/extrema never decrease; medians are exact over the retained
* detail window only.
*/
Dt adw() {
var facts = new PkProfileBase();
facts.merge(pkProjection, getPkAttributions());
boolean split = facts.isCostSplitComplete() && facts.getSuppliesCosts() <= facts.getCosts();
var aor = new ArrayList<Long>();
var anw = new ArrayList<Long>();
for (Bx encounter : nv()) {
if (encounter.getType() == Be.KILL) {
aor.add(encounter.ur());
} else {
anw.add(encounter.uk());
}
}
return new Dt(facts.getKills(), facts.getDeaths(), pkProjection.getStreak(), facts.getRevenue(),
facts.getCosts(), facts.getNet(), facts.getBestKill(), facts.getLargestDeathLoss(), facts.getKillNet(),
facts.getDeathLoss(), facts.getSuppliesCosts(), split ? facts.getOtherCosts() : 0L, split,
Dt.median(aor), Dt.median(anw), pkProjection.ud(),
(int) min(Integer.MAX_VALUE, pkProjection.uj()));
}
/** Longest run of kills without a death over the encounters held, never below the current run. */
int mb() {
int best = 0;
int run = 0;
for (Bx encounter : nv()) {
run = encounter.getType() == Be.KILL ? run + 1 : 0;
best = max(best, run);
}
return max(best, ava().currentStreak);
}
List<Bx> nv() {
var chronological = new ArrayList<Bx>();
for (Bx encounter : getPkEncounters()) {
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
Ax add(Ac transaction) {
Ax amounts = transaction(transaction);
if (!amounts.available || !amounts.included) {
return amounts;
}
revenue = safeAdd(revenue, amounts.revenue);
costs = safeAdd(costs, amounts.costs);
Bp.Du split = costSplit(transaction);
if (!split.available) costSplitAvailable = false;
else supplies = safeAdd(supplies, split.supplies);
return amounts;
}
}
PkHistoryProjection rl() {
if (pkProjection == null) {
pkProjection = new PkHistoryProjection();
}
return pkProjection;
}
List<Ay> getPkAttributions() {
return unmodifiableList(new ArrayList<>(pkAttributions));
}
Ay st(String encounterId) {
if (empty(encounterId)) return null;
for (Ay attribution : pkAttributions) {
if (attribution != null && encounterId.equals(attribution.getEncounterId())) {
return attribution;
}
}
return null;
}
Ay su(String transactionId) {
if (empty(transactionId)) return null;
for (Ay attribution : pkAttributions) {
if (attribution != null && attribution.holds(transactionId)) {
return attribution;
}
}
return null;
}
Ay ro(Bx encounter) {
rl();
Ay existing = st(encounter.getId());
if (existing != null) return existing;
var created = new Ay(encounter.getId(), encounter.getType());
pkAttributions.add(created);
return created;
}
void qy(String transactionId) {
Ay attribution = su(transactionId);
if (attribution == null) return;
attribution.aga(transactionId);
if (!attribution.vd()) {
sn(attribution);
}
}
void sp(String transactionId) {
Ay attribution = su(transactionId);
if (attribution == null) return;
attribution.te(transactionId);
if (!attribution.vd()) {
sn(attribution);
}
}
void sn(Ay attribution) {
if (attribution == null) return;
rl().fold(attribution.getType(), attribution.current());
pkAttributions.remove(attribution);
abk();
}
/** Removes one compacted detailed encounter row; exact facts are already retained. */
boolean afw(Bx encounter) {
if (encounter == null || !pkEncounters.remove(encounter)) return false;
if (pkProjection != null) pkProjection.zf();
return true;
}
Bu metrics(long now, long rollingWindowMillis) {
// The rolling calculation needs receipt timestamps, which compacted rows no longer
// retain. Retention only folds rows older than its cutoff, so the rate stays exact
// while the retained rows still reach back past the rolling window.
boolean rollingRateAvailable = compactedTransactionCount == 0L
|| acb() <= now - max(60_000L, rollingWindowMillis);
var money = new MoneyFold(retainedRevenue, retainedCosts, retainedSuppliesCosts,
compactedTransactionCount == 0L || retainedCostSplitComplete);
for (Ac transaction : transactions) {
if (transaction == null) {
continue;
}
money.add(transaction);
}
if (money.supplies > money.costs) {
money.costSplitAvailable = false;
}
long net = aha(money.revenue, money.costs);
long elapsed = getElapsedMillis(now);
return new Bu(getActivityHint(), paused, elapsed, money.revenue, money.costs, net,
hourly(net, elapsed), rollingRateAvailable ? ni(elapsed, rollingWindowMillis) : 0L,
rollingRateAvailable, money.costSplitAvailable ? money.supplies : 0L,
money.costSplitAvailable ? aha(money.costs, money.supplies) : 0L, money.costSplitAvailable);
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
long getNet() { return aha(revenue, costs); }
}
CompactedContribution pl() {
return new CompactedContribution(compactedTransactionCount, retainedRevenue, retainedCosts,
retainedSuppliesCosts, compactedTransactionCount == 0L || retainedCostSplitComplete);
}
long tp() { return retainedRevenue; }
long tn() { return retainedCosts; }
long ni(long effectiveElapsedMillis, long windowMillis) {
long arz = max(60_000L, windowMillis);
long cutoff = nonNeg(effectiveElapsedMillis - arz);
long apg = 0L;
long alw = effectiveElapsedMillis;
boolean found = false;
for (Ac transaction : transactions) {
if (transaction == null) {
continue;
}
Ax amounts = transaction(transaction);
if (!amounts.available || !amounts.included) {
continue;
}
long aqy = agi(transaction, effectiveElapsedMillis);
if (aqy < cutoff) {
continue;
}
found = true;
alw = min(alw, aqy);
apg = safeAdd(apg, aha(amounts.revenue, amounts.costs));
}
if (!found) {
return 0L;
}
long denominator = max(1_000L,
effectiveElapsedMillis - max(cutoff, alw));
return hourly(apg, denominator);
}
long agi(
Ac transaction,
long effectiveElapsedMillis) {
Long aqs = transaction.activeElapsedMillis;
if (aqs != null) {
return nonNeg(min(effectiveElapsedMillis, aqs));
}
long atz = nonNeg(transaction.timestampEpochMillis - startedAtEpochMillis);
return min(effectiveElapsedMillis, atz);
}
static long hourly(long value, long elapsedMillis) {
if (elapsedMillis <= 0L) {
return 0L;
}
double rate = value * (3_600_000.0d / elapsedMillis);
if (rate >= Long.MAX_VALUE) {
return Long.MAX_VALUE;
}
if (rate <= Long.MIN_VALUE) {
return Long.MIN_VALUE;
}
return round(rate);
}
long getElapsedMillis(long now) {
long aoc = endedAtEpochMillis == 0L ? now : endedAtEpochMillis;
long arj = paused ? nonNeg(aoc - pausedAtEpochMillis) : 0L;
return nonNeg(aoc - startedAtEpochMillis - totalPausedMillis - arj);
}
static String aaw(String value) {
if (blank(value)) {
return DURABLE_OWNER_NAME;
}
return value.trim();
}
String getId() {
if (empty(id)) {
id = UUID.randomUUID().toString();
}
return id;
}
String getName() { return aaw(name); }
Bt getOwnerKind() {
return ownerKind == null ? Bt.UNKNOWN : ownerKind;
}
void setOwnerKind(Bt value) {
Bt next = value == null ? Bt.UNKNOWN : value;
if (getOwnerKind() != next) {
ownerKind = next;
abk();
}
}
void setChangeListener(Runnable listener) { ayl = listener; }
void abk() {
Runnable listener = ayl;
if (listener != null) listener.run();
}
String getActivityHint() {
String normalized = aas(activityHint);
if (getMode() == Cx.PK && "General".equalsIgnoreCase(normalized)) {
return "PKing";
}
return normalized;
}
Cx getMode() { return mode == null ? Cx.GENERAL : mode; }
boolean isClosed() { return endedAtEpochMillis != 0L; }
Long getProfitTargetGp() { return profitTargetGp != null && profitTargetGp > 0L ? profitTargetGp : null; }
List<Ac> getTransactions() {
return unmodifiableList(transactions);
}
/** Timestamp of the oldest retained row, or {@code Long.MIN_VALUE} when none is retained. */
long acb() {
long oldest = Long.MAX_VALUE;
for (Ac transaction : transactions) {
if (transaction != null) oldest = min(oldest, transaction.timestampEpochMillis);
}
return oldest == Long.MAX_VALUE ? Long.MIN_VALUE : oldest;
}
/**
* Receipt retention (section L): folds retained rows dated before {@code cutoffEpochMillis}
* into this session's correction-aware archive totals, oldest first, skipping rows the
* caller still needs individually (an unresolved claim's audit row or its settlement).
* Undo/correction entries that reference a folded row are pruned at the same boundary.
* Compacted rows are no longer individually correctable; totals stay exact.
*/
int pj(long cutoffEpochMillis, Predicate<Ac> keep) {
if (transactions.isEmpty()) return 0;
var folded = new ArrayList<Ac>();
for (Ac transaction : transactions) {
if (transaction == null) continue;
if (transaction.timestampEpochMillis >= cutoffEpochMillis) continue;
if (keep != null && keep.test(transaction)) continue;
folded.add(transaction);
}
if (folded.isEmpty()) return 0;
var foldedIds = new HashSet<String>();
for (Ac transaction : folded) {
foldedIds.add(transaction.getId());
transactions.remove(transaction);
ph(transaction);
}
undoHistory.removeIf(record -> record != null && foldedIds.contains(record.getTransactionId()));
correctionHistory.removeIf(record -> record != null && record.ale(foldedIds));
return folded.size();
}
List<Bx> getPkEncounters() {
return unmodifiableList(pkEncounters);
}
}
