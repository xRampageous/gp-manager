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
/** Runtime-only change count; a quick save reuses a closed session's JSON while it holds. */
transient long saveStamp;
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
 boolean canChange(Ac /* transaction */b, Ah /* previousCorrection */c,
 List<Ab> /* previousFlows */d, Ah /* nextCorrection */e, List<Ab> /* nextFlows */f);
 /** Apply the exact effective delta after a permitted change. */
 void onChanged(Ac /* transaction */b, Ah /* previousCorrection */c, List<Ab> /* previousFlows */d);
 /** Validate restoring a previously removed transaction; false blocks the restore. */
 boolean canAdd(Ac /* transaction */b);
 /** Validate removing a retained transaction (undo); false blocks the removal. */
 boolean canRemove(Ac /* transaction */b);
 /** Book a newly added canonical transaction's known coverage/sink depletion. */
 void onAdded(Ac /* transaction */b, boolean /* custodyOwned */c);
 /** Reverse a removed transaction's known coverage. */
 void onRemoved(Ac /* transaction */b);
}

void abl(Ac /* transaction */a, boolean /* custodyOwned */b) {
 Dj /* observer */c = ownedValueObserver;
 if (c != null) c.onAdded(a, b);
}

boolean ng(Ac /* transaction */a, Ah /* previousCorrection */b, List<Ab> /* previousFlows */c,
Ah /* nextCorrection */d, List<Ab> /* nextFlows */e) {
 Dj /* observer */f = ownedValueObserver;
 return f == null || f.canChange(a, b, c, d,
 e);
}

/** Announces a changed row: totals, PvP and the owned-value observer follow it. */
void changed(Ac /* transaction */a, Ah /* previousCorrection */b, List<Ab> /* previousFlows */c) {
 ajt(a);
 Dj /* observer */d = ownedValueObserver;
 if (d != null) d.onChanged(a, b, c);
}

void log(long /* now */a, String /* reason */b, Change... /* changes */c) {
 correctionHistory.add(new Dx(a, b, Arrays.asList(c)));
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
/** Ah-eligible mutable attribution anchors; bounded by canonical receipt retention. */
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

Ad(String /* name */a, long /* startedAtEpochMillis */b) {
 this(a, b, Cx.GENERAL);
}

Ad(String /* name */a, long /* startedAtEpochMillis */b, Cx /* mode */c) {
 this.id = UUID.randomUUID().toString();
 this.name = aaw(a);
 this.mode = c == null ? Cx.GENERAL : c;
 this.activityHint = this.mode == Cx.PK ? "PKing" : "General";
 this.startedAtEpochMillis = b;
 this.transactionLimit = 2_000;
 this.retainedCostSplitComplete = true;
 this.notes = "";
}

void kf(Ac /* transaction */a, int /* maxTransactions */b) {
 kf(a, b, false);
}

/**
* @param custodyOwned true for transactions already realized by a custody lifecycle; their
*                     item legs consumed their reservation and must never deplete again
*/
void kf(Ac /* transaction */a, int /* maxTransactions */b, boolean /* custodyOwned */c) {
 if (a == null) return;
 transactionLimit = max(1, b);
 transactions.add(a);
 ajt(a);
 abl(a, c);
 int /* overflow */d = transactions.size() - transactionLimit;
 if (d > 0) {
  var /* removed */e = new ArrayList<Ac>(transactions.subList(0, d));
  transactions.subList(0, d).clear();
  for (Ac /* transactionToRemove */f : e) ph(f);
 }
}

void aeh(String /* hint */a, long /* now */b) {
 String /* activity */c = aas(a);
 actionCount++;
 setActivityHint(c, b);
}

void setActivityHint(String /* hint */a, long /* now */b) {
 if (blank(a)) return;
 String /* next */c = aas(a);
 String /* current */d = getActivityHint();
 if (c.equalsIgnoreCase(d)) return;
 activityHint = c;
}

void rename(String /* value */a) {
 name = aaw(a);
}

boolean setProfitTargetGp(Long /* value */a) {
 if (a != null && a <= 0L) return false;
 profitTargetGp = a;
 return true;
}

/** The actual Active-Time target this instance ran with; null when none was set. */
Long getActiveTimeTargetMillis() {
 return activeTimeTargetMillis != null && activeTimeTargetMillis > 0L ? activeTimeTargetMillis : null;
}

boolean setActiveTimeTargetMillis(Long /* value */a) {
 if (a != null && a <= 0L) return false;
 activeTimeTargetMillis = a;
 return true;
}

/** Stable My Grind lineage link; empty when this instance was never saved as a Grind. */
String getGrindId() {
 return axw(grindId);
}

void setGrindId(String /* value */a) {
 grindId = awq(a, null);
}

boolean xf() {
 return !getGrindId().isEmpty();
}

void zg() {
 if (!isClosed()) recoveredFromCrash = true;
}

void pause(long /* now */a, Ed /* reason */b) {
 if (!paused && endedAtEpochMillis == 0L) {
  paused = true;
  pausedAtEpochMillis = a;
  pauseReason = b == null ? MANUAL : b;
 }
}

void resume(long /* now */a) {
 if (paused && endedAtEpochMillis == 0L) {
  totalPausedMillis = safeAdd(totalPausedMillis, nonNeg(a - pausedAtEpochMillis));
  pausedAtEpochMillis = 0L;
  paused = false;
 }
 pauseReason = NONE;
}

Ed getPauseReason() {
 if (!paused) return NONE;
 return pauseReason == null || pauseReason == NONE ? MANUAL : pauseReason;
}

void close(long /* now */a) {
 if (endedAtEpochMillis != 0L) return;
 saveStamp++;
 endedAtEpochMillis = max(a, startedAtEpochMillis);
}

boolean qi(String /* transactionId */a, Ah /* correction */b, long /* now */c, String /* reason */d) {
 Ac /* transaction */e = sw(a);
 return e != null && correct(singletonList(e), b == null ? AUTO : b, c, d);
}

/**
* A measured Check confirmed charge-load quantities on an automatic Review row: it becomes a
* transfer (all confirmed) or keeps only the unconfirmed flows. Automatic evidence, not an owner
* correction, so nothing is logged; the change is announced like every other change.
*/
void pz(Ac /* review */a, List<Ab> /* confirmed */b, List<Ab> /* remaining */c) {
 Ah /* previous */d = a.getCorrection();
 List<Ab> /* previousFlows */e = a.getFlows();
 if (c.isEmpty()) {
  if (!a.agg(b)) return;
 } else {
  a.afj(c);
 }
 changed(a, d, e);
}

/**
* Automatic reconciliation of an automatic estimate against a measured Check: shrink it by
* {@code quantity} of one item, voiding the row when nothing is left. Automatic evidence, not an
* owner correction, so nothing is logged; the change is announced like every other change.
*/
void pzz(Ac /* estimate */a, int /* itemId */b, long /* quantity */c, String /* explanation */d) {
 if (a == null || b <= 0 || c <= 0L) return;
 Ah /* previous */e = a.getCorrection();
 List<Ab> /* previousFlows */f = a.getFlows();
 long /* remaining */g = a.afg(b, false, c);
 a.confidence = Bd.CONFIRMED;
 if (g <= 0L) a.ko(IGNORE, Ae.now(), d);
 a.agt(d);
 changed(a, e, f);
}

/**
* Applies one decision to the still-unresolved transactions and stores one
* correction record, so a single undo restores the complete operation.
*/
int qh(List<String> /* transactionIds */a, Ah /* correction */b, long /* now */c, String /* reason */d) {
 Ah /* next */e = b == null ? AUTO : b;
 if (empty(a) || e == AUTO) return 0;
 var /* targets */f = new ArrayList<Ac>();
 for (String /* transactionId */g : new LinkedHashSet<>(a)) {
  Ac /* transaction */h = sw(g);
  if (Eh.aal(h)) f.add(h);
 }
 return correct(f, e, c, awq(d, msg("eq"))) ? f.size() : 0;
}

/**
* One owner decision over rows: every row is checked with the owned-value observer before
* any changes (one blocked row rejects the whole decision), then one undoable record is logged.
*/
boolean correct(List<Ac> /* targets */a, Ah /* next */b, long /* now */c, String /* reason */d) {
 var /* changes */e = new ArrayList<Change>();
 for (Ac /* transaction */f : a) {
  if (!ng(f, f.getCorrection(), f.getFlows(), b,
  f.getFlows())) {
   // The item's known coverage is reserved or already realized; leave the rows untouched.
   return false;
  }
  e.add(new Change(f.getId(), f.getCorrection(), b, null, null));
 }
 for (Ac /* transaction */g : a) {
  Ah /* previous */h = g.getCorrection();
  g.ko(b, c, d);
  changed(g, h, g.getFlows());
 }
 if (!e.isEmpty()) correctionHistory.add(new Dx(c, d, e));
 return !e.isEmpty();
}

/**
* Personal item split: Net keeps only {@code keepQuantity} of {@code itemId}.
* Given-away qty is removed from counted gain (not Used). Full give-away on a
* single-item row IGNORE-corrects. Shares the correction undo stack.
*/
boolean kr(String /* transactionId */a, int /* itemId */b, long /* keepQuantity */c, long /* now */d, String /* optionalNote */e) {
 Ac /* transaction */f = sw(a);
 if (f == null || b <= 0) return false;
 long /* anb */g = f.quantity(b, true);
 if (g <= 0L) return false;
 long /* keep */h = nonNeg(min(c, g));
 if (h == g) return false;
 String /* reason */i = Dm.reason(h, g, e);
 Ah /* previous */j = f.getCorrection();
 var /* flowSnapshot */k = new ArrayList<Ab>(f.getFlows());
 String /* explanationSnapshot */l = f.getExplanation();
 boolean /* ignore */m = h <= 0L && aco(f, b);
 if (!ng(f, j, k, m ? IGNORE : j, null)) {
  // The known coverage this split would shrink is reserved or already realized.
  return false;
 }
 if (m) f.ko(IGNORE, d, i);
 else f.afg(b, true, g - h);
 f.ajk(i, d);
 changed(f, j, k);
 log(d, i, new Change(a, j, f.getCorrection(), k, l));
 return true;
}

/**
* Reverts the most recent correction/split when a flow snapshot is present,
* or restores the previous correction enum otherwise.
*/
boolean akb(long /* now */b) {
 if (correctionHistory.isEmpty()) return false;
 Dx /* last */c = null;
 for (int /* index */d = correctionHistory.size() - 1; d >= 0; d--) {
  Dx /* candidate */e = correctionHistory.get(d);
  if (e != null && !e.isUndone()) {
   c = e;
   break;
  }
 }
 if (c == null) return false;
 List<Change> /* changes */f = c.getChanges();
 if (f.isEmpty()) return false;
 var /* targets */g = new ArrayList<Ac>(f.size());
 for (Change /* change */h : f) {
  Ac /* transaction */i = sw(h.getTransactionId());
  if (i == null) {
   // Fail closed: never partially undo a batch after retention or deletion.
   return false;
  }
  g.add(i);
 }
 // Validate the whole restore before any canonical mutation.
 for (int /* index */j = 0; j < f.size(); j++) {
  Change /* change */k = f.get(j);
  Ac /* transaction */l = g.get(j);
  List<Ab> /* nextFlows */m = k.hasFlowSnapshot() ? k.getFlowSnapshot() : l.getFlows();
  if (!ng(l, l.getCorrection(),
  l.getFlows(), k.getPreviousCorrection(), m)) {
   return false;
  }
 }
 for (int /* index */n = f.size() - 1; n >= 0; n--) {
  Change /* change */o = f.get(n);
  Ac /* transaction */p = g.get(n);
  Ah /* previous */q = p.getCorrection();
  List<Ab> /* previousFlows */r = p.getFlows();
  if (o.hasFlowSnapshot()) p.afj(o.getFlowSnapshot());
  if (o.explanationSnapshot != null) p.agt(o.explanationSnapshot);
  p.ko(o.getPreviousCorrection(), b);
  p.correctionReason = adl(p.getId(), c);
  changed(p, q, r);
 }
 c.zr(b);
 return true;
}

/** Restore metadata from the retained decision timeline without adding save fields. */
String adl(String /* transactionId */a, Dx /* undone */b) {
 for (int /* index */c = correctionHistory.indexOf(b) - 1; c >= 0; c--) {
  Dx /* record */d = correctionHistory.get(c);
  if (d == null || d.isUndone()) continue;
  for (Change /* change */e : d.getChanges()) {
   if (a.equals(e.getTransactionId())
   && (!e.hasFlowSnapshot() || e.getPreviousCorrection() != e.newCorrection
   || Dm.xr(d.getReason()))) return d.getReason();
  }
 }
 return "";
}

static boolean aco(Ac /* transaction */a, int /* itemId */b) {
 if (a == null) return false;
 boolean /* sawTarget */c = false;
 for (Ab /* flow */d : a.getFlows()) {
  if (d == null || d.quantityDelta == 0L) continue;
  if (d.itemId != b) return false;
  if (d.quantityDelta > 0L) c = true;
 }
 return c;
}

/**
* Reverses unrecovered own-drop cost at original valuation. Full recovery
* IGNORE-corrects the drop row; partial recovery shrinks its cost flows.
*/
boolean afc(String /* transactionId */b, int /* itemId */c, long /* quantity */d, long /* now */e, String /* reason */f) {
 Ac /* transaction */g = sw(b);
 if (g == null || d <= 0L || c <= 0 || g.quantity(c, false) <= 0L) {
  return false;
 }
 var /* flowSnapshot */h = new ArrayList<Ab>(g.getFlows());
 String /* explanationSnapshot */i = g.getExplanation();
 Ah /* previous */j = g.getCorrection();
 long /* remaining */k = g.afg(c, false, d);
 Ah /* next */l = k <= 0L ? IGNORE : j;
 if (!ng(g, j, h, l, k <= 0L ? null : g.getFlows())) {
  // Cost-side recovery only shrinks costs; a blocked result must leave the row intact.
  g.afj(h);
  return false;
 }
 if (k <= 0L) g.ko(l, e, f);
 log(e, f, new Change(b, j, l, h, i));
 changed(g, j, h);
 return true;
}

Ac akc(long /* now */a) {
 if (transactions.isEmpty()) return null;
 Ac /* removed */b = transactions.get(transactions.size() - 1);
 Dj /* observer */c = ownedValueObserver;
 if (c != null && !c.canRemove(b)) {
  // Known coverage was already reserved/realized downstream; leave it untouched.
  return null;
 }
 transactions.remove(transactions.size() - 1);
 abk();
 for (Bx /* encounter */d : pkEncounters) {
  d.afh(b.getId());
  d.afd(b.getId());
 }
 qy(b.getId());
 if (c != null) c.onRemoved(b);
 undoHistory.add(new UndoRecord(b, a));
 int /* overflow */e = undoHistory.size() - MAX_UNDO_HISTORY;
 if (e > 0) undoHistory.subList(0, e).clear();
 return b;
}

Ac agn(long /* now */b) {
 for (int /* index */c = undoHistory.size() - 1; c >= 0; c--) {
  UndoRecord /* record */d = undoHistory.get(c);
  if (d == null || d.restored || d.transaction == null) continue;
  Ac /* restored */e = d.transaction;
  Dj /* observer */f = ownedValueObserver;
  if (f != null && !f.canAdd(e)) {
   // Restoring would re-add known coverage after a later realization; refuse it.
   return null;
  }
  int /* ary */g = transactionLimit <= 0 ? 2_000 : transactionLimit;
  int /* overflow */h = transactions.size() + 1 - g;
  if (h > 0) {
   var /* evicted */i = new ArrayList<Ac>(transactions.subList(0, h));
   transactions.subList(0, h).clear();
   for (Ac /* transactionToRemove */j : i) {
    // Restoring an undo is also a bounded-ledger insertion.
    // The row displaced to make room must be summarized exactly
    // as it would be for a newly observed transaction.
    ph(j);
   }
  }
  transactions.add(e);
  ajt(e);
  abl(e, false);
  d.zp(b);
  return e;
 }
 return null;
}

Ac sw(String /* transactionId */a) {
 if (empty(a)) return null;
 for (Ac /* transaction */b : transactions) {
  if (a.equals(b.getId())) return b;
 }
 return null;
}

Bx ke(Be /* type */a, long /* now */b, String /* label */c, Bd /* confidence */d,
String /* explanation */e) {
 PkHistoryProjection /* projection */f = rl();
 var /* encounter */g = new Bx(a, b, c, d, e);
 pkEncounters.add(g);
 f.aev(g.getType());
 abk();
 aeh("PKing", b);
 return g;
}

void ll(String /* transactionId */a, String /* encounterId */b, boolean /* asPkSupplyCost */c) {
 Ac /* transaction */d = sw(a);
 Bx /* encounter */e = tf(b);
 if (d == null || e == null) return;
 saveStamp++;
 d.lf(b, c);
 e.kg(a);
 aes(d);
}

void lj(String /* encounterId */a, long /* now */b, long /* lookbackMillis */c) {
 long /* cutoff */d = nonNeg(b - max(0L, c));
 for (int /* index */e = transactions.size() - 1; e >= 0; e--) {
  Ac /* transaction */f = transactions.get(e);
  if (f.timestampEpochMillis < d) break;
  if (!f.getEncounterId().isEmpty()) continue;
  if (f.revenue == 0L && f.costs > 0L
  && f.tm() == Ai.CONSUMPTION) {
   ll(f.getId(), a, true);
  }
 }
}

Bx tf(String /* encounterId */a) {
 if (empty(a)) return null;
 for (Bx /* encounter */b : pkEncounters) {
  if (a.equals(b.getId())) return b;
 }
 return null;
}

void pi(String /* transactionId */a) {
 for (Bx /* encounter */b : pkEncounters) {
  if (b.getTransactionIds().contains(a)) {
   b.pf(a);
   b.afh(a);
  }
 }
}

void ph(Ac /* transaction */a) {
 if (a == null) return;
 compactedTransactionCount++;
 if (a.getType() == Ai.TRANSFER) {
  retainedTransfers++;
  sp(a.getId());
  pi(a.getId());
  return;
 }
 if (a.isCounted()) {
  retainedCountedTransactions++;
  Ax /* amounts */b = transaction(a);
  retainedRevenue = safeAdd(retainedRevenue, b.revenue);
  retainedCosts = safeAdd(retainedCosts, b.costs);
  Bp.Du /* split */c = costSplit(a);
  if (!c.available) retainedCostSplitComplete = false;
  else retainedSuppliesCosts = safeAdd(retainedSuppliesCosts, c.supplies);
 }
 sp(a.getId());
 pi(a.getId());
}

/** A counted row changed: advance the revision and refresh its PvP encounter money. */
void ajt(Ac /* transaction */a) {
 if (a == null) return;
 abk();
 aes(a);
}

/** A receipt's current money on its encounter row and its correction-eligible anchor. */
void aes(Ac /* transaction */b) {
 if (b == null || b.getEncounterId().isEmpty()) return;
 Bx /* encounter */c = tf(b.getEncounterId());
 if (c != null) c.ahr(b);
 Ay /* attribution */d = st(b.getEncounterId());
 if (d == null && c != null) {
  // Undo removes the transaction from the encounter and may finalize an empty or
  // partially finalized anchor. Redo restores a live canonical receipt, so recreate
  // only that bounded child anchor; the encounter count is already in the projection.
  d = ro(c);
 }
 if (d != null) d.awv(b.getId(), PkMoney.of(b));
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
 var /* facts */a = new PkProfileBase();
 a.merge(pkProjection, getPkAttributions());
 boolean /* split */b = a.costSplitComplete && a.suppliesCosts <= a.costs;
 var /* aor */c = new ArrayList<Long>();
 var /* anw */d = new ArrayList<Long>();
 for (Bx /* encounter */e : nv()) {
  if (e.getType() == Be.KILL) c.add(e.ur());
  else d.add(e.uk());
 }
 return new Dt(a.getKills(), a.getDeaths(), pkProjection.streak, a.getRevenue(),
 a.costs, a.net, a.getBestKill(), a.getLargestDeathLoss(), a.killNet,
 a.deathLoss, a.suppliesCosts, b ? a.otherCosts : 0L, b,
 Dt.median(c), Dt.median(d), pkProjection.ud(),
 (int) min(Integer.MAX_VALUE, pkProjection.uj()));
}

/** Longest run of kills without a death over the encounters held, never below the current run. */
int mb() {
 int /* best */a = 0;
 int /* run */b = 0;
 for (Bx /* encounter */c : nv()) {
  b = c.getType() == Be.KILL ? b + 1 : 0;
  a = max(a, b);
 }
 return max(a, ava().currentStreak);
}

List<Bx> nv() {
 var /* chronological */a = new ArrayList<Bx>();
 for (Bx /* encounter */b : getPkEncounters()) {
  if (b != null) a.add(b);
 }
 a.sort(Comparator.comparingLong((/* itemData */c -> c.timestampEpochMillis)));
 return a;
}

@AllArgsConstructor
static class MoneyFold {
 long revenue;
 long costs;
 long supplies;
 boolean costSplitAvailable = true;
 Ax add(Ac /* transaction */b) {
  Ax /* amounts */c = transaction(b);
  if (!c.available || !c.included) return c;
  revenue = safeAdd(revenue, c.revenue);
  costs = safeAdd(costs, c.costs);
  Bp.Du /* split */d = costSplit(b);
  if (!d.available) costSplitAvailable = false;
  else supplies = safeAdd(supplies, d.supplies);
  return c;
 }
}

PkHistoryProjection rl() {
 if (pkProjection == null) pkProjection = new PkHistoryProjection();
 return pkProjection;
}

List<Ay> getPkAttributions() {
 return unmodifiableList(new ArrayList<>(pkAttributions));
}

Ay st(String /* encounterId */a) {
 if (empty(a)) return null;
 for (Ay /* attribution */b : pkAttributions) {
  if (b != null && a.equals(b.getEncounterId())) return b;
 }
 return null;
}

Ay su(String /* transactionId */a) {
 if (empty(a)) return null;
 for (Ay /* attribution */b : pkAttributions) {
  if (b != null && b.holds(a)) return b;
 }
 return null;
}

Ay ro(Bx /* encounter */a) {
 rl();
 Ay /* existing */b = st(a.getId());
 if (b != null) return b;
 var /* created */c = new Ay(a.getId(), a.getType());
 pkAttributions.add(c);
 return c;
}

void qy(String /* transactionId */a) {
 Ay /* attribution */b = su(a);
 if (b == null) return;
 b.aga(a);
 if (!b.vd()) sn(b);
}

void sp(String /* transactionId */a) {
 Ay /* attribution */b = su(a);
 if (b == null) return;
 b.te(a);
 if (!b.vd()) sn(b);
}

void sn(Ay /* attribution */a) {
 if (a == null) return;
 rl().fold(a.getType(), a.current());
 pkAttributions.remove(a);
 abk();
}

/** Removes one compacted detailed encounter row; exact facts are already retained. */
boolean afw(Bx /* encounter */a) {
 if (a == null || !pkEncounters.remove(a)) return false;
 saveStamp++;
 if (pkProjection != null) pkProjection.zf();
 return true;
}

Bu metrics(long /* now */a) {
 var /* money */b = new MoneyFold(retainedRevenue, retainedCosts, retainedSuppliesCosts,
 compactedTransactionCount == 0L || retainedCostSplitComplete);
 for (Ac /* transaction */c : transactions) {
  if (c == null) continue;
  b.add(c);
 }
 if (b.supplies > b.costs) b.costSplitAvailable = false;
 long /* net */d = aha(b.revenue, b.costs);
 long /* elapsed */e = getElapsedMillis(a);
 return new Bu(getActivityHint(), paused, e, b.revenue, b.costs, d,
 hourly(d, e), b.costSplitAvailable ? b.supplies : 0L,
 b.costSplitAvailable ? aha(b.costs, b.supplies) : 0L, b.costSplitAvailable);
}

/**
* The correction-aware money folded out of individually inspectable rows by compaction.
* Retained rows plus this contribution reconcile exactly to {@link #metrics}; CSV writes it
* as the explicit {@code COMPACTED} row (charter T).
*/
@AllArgsConstructor
static class CompactedContribution {
 long rows;
 long revenue;
 long costs;
 long suppliesCosts;
 boolean costSplitAvailable;
 long getNet() { return aha(revenue, costs); }
}

CompactedContribution pl() {
 return new CompactedContribution(compactedTransactionCount, retainedRevenue, retainedCosts,
 retainedSuppliesCosts, compactedTransactionCount == 0L || retainedCostSplitComplete);
}

long tp() { return retainedRevenue; }
long tn() { return retainedCosts; }


static long hourly(long /* value */a, long /* elapsedMillis */b) {
 if (b <= 0L) return 0L;
 // Math.round saturates at the long range by itself.
 return round(a * (3_600_000.0d / b));
}

long getElapsedMillis(long /* now */a) {
 long /* aoc */b = endedAtEpochMillis == 0L ? a : endedAtEpochMillis;
 long /* arj */c = paused ? nonNeg(b - pausedAtEpochMillis) : 0L;
 return nonNeg(b - startedAtEpochMillis - totalPausedMillis - c);
}

static String aaw(String /* value */a) {
 if (blank(a)) return DURABLE_OWNER_NAME;
 return a.trim();
}

String getId() {
 if (empty(id)) id = UUID.randomUUID().toString();
 return id;
}

String getName() { return aaw(name); }
Bt getOwnerKind() {
 return ownerKind == null ? Bt.UNKNOWN : ownerKind;
}

void setOwnerKind(Bt /* value */a) {
 Bt /* next */b = a == null ? Bt.UNKNOWN : a;
 if (getOwnerKind() != b) {
  ownerKind = b;
  abk();
 }
}

void setChangeListener(Runnable /* listener */a) { ayl = a; }
void abk() {
 saveStamp++;
 Runnable /* listener */a = ayl;
 if (a != null) a.run();
}

String getActivityHint() {
 String /* normalized */a = aas(activityHint);
 if (getMode() == Cx.PK && "General".equalsIgnoreCase(a)) return "PKing";
 return a;
}

Cx getMode() { return mode == null ? Cx.GENERAL : mode; }
boolean isClosed() { return endedAtEpochMillis != 0L; }
Long getProfitTargetGp() { return profitTargetGp != null && profitTargetGp > 0L ? profitTargetGp : null; }
List<Ac> getTransactions() {
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
int pj(long /* cutoffEpochMillis */a, Predicate<Ac> /* keep */b) {
 if (transactions.isEmpty()) return 0;
 var /* folded */c = new ArrayList<Ac>();
 for (Ac /* transaction */d : transactions) {
  if (d == null) continue;
  if (d.timestampEpochMillis >= a) continue;
  if (b != null && b.test(d)) continue;
  c.add(d);
 }
 if (c.isEmpty()) return 0;
 saveStamp++;
 var /* foldedIds */e = new HashSet<String>();
 for (Ac /* transaction */f : c) {
  e.add(f.getId());
  transactions.remove(f);
  ph(f);
 }
 undoHistory.removeIf(/* record */g -> g != null && e.contains(g.getTransactionId()));
 correctionHistory.removeIf(/* record */h -> h != null && h.ale(e));
 return c.size();
}

List<Bx> getPkEncounters() {
 return unmodifiableList(pkEncounters);
}
}
