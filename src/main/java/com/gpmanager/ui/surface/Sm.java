package com.gpmanager;
import java.util.*;
import java.util.function.Predicate;
import com.gpmanager.Bi.Row;
import static com.gpmanager.Ae.*;
import static java.util.Collections.*;

/**
* One owned, unfiltered financial snapshot. Session save stamps cover close/history changes
* that do not advance Am.revision. Clock, pause and activity are overlays, never cached money.
* A changed Market presentation reuses the same financial facts rather than refolding receipts.
*/
class Sm {
 final List<Object> k;
 final List<Row> m;
 final Map<Ad, F> f;
 final Map<String, G> g;
 /** Derived scopes memoize only from the captured facts; they cannot read mutable receipts. */
 final Map<List<Ad>, S> q = new HashMap<>();
 static Sm of(Am a) {
  List<Ad> b = a.akf();
  var c = new ArrayList<Object>();
  c.add(a.getRevision()); c.add(a.getActiveSession()); c.add(a.getHistory());
  for (Ad d : b) { c.add(d); c.add(d == null ? 0L : d.saveStamp); c.add(d == null ? "" : d.getGrindId()); }
  for (SavedState.Ap d : a.getSavedGrinds(true)) c.add(d.getGrindId());
  List<Row> e = a.ub();
  Sm h = a.sm;
  if (h != null && h.k.equals(c) && h.m.equals(e)) return h;
  return a.sm = new Sm(a, b, c, e, h);
 }
 Sm(Am a, List<Ad> b, List<Object> c, List<Row> d, Sm e) {
  k = unmodifiableList(c); m = unmodifiableList(new ArrayList<>(d));
  if (e != null && e.k.equals(k)) { f = e.f; g = e.g; return; }
  var h = new IdentityHashMap<Ad, F>();
  for (Ad i : b) if (i != null && !h.containsKey(i)) {
   F l = e == null ? null : e.f.get(i);
   h.put(i, i.isClosed() && l != null && l.s == i.saveStamp && l.recap != null ? l : new F(i));
  }
  f = unmodifiableMap(h);
  var j = new HashMap<String, G>();
  for (SavedState.Ap l : a.getSavedGrinds(true)) j.put(l.getGrindId(), new G(a.pp(l.getGrindId())));
  g = unmodifiableMap(j);
 }
 /** Canonical metrics keep the exact live clock and Ad's saturating rate formula. */
 Bu metrics(Ad a, long b) { return a == null ? Bu.none() : fact(a).metrics(a, b, true); }
 F fact(Ad a) { return Objects.requireNonNull(f.get(a), "Session outside captured scope"); }
 /** Run's compact-only fallback is preserved for a session outside retained history. */
 Ba.Run run(Am a, Ad b, long c) {
  Ad d = a.ua(b.getId());
  boolean e = d != null;
  Ad h = e ? d : b;
  return new Ba.Run(b, fact(h).metrics(h, c, e), e);
 }
 S scope(List<Ad> a) {
  List<Ad> b = unmodifiableList(new ArrayList<>(a));
  return q.computeIfAbsent(b, S::new);
 }
 /** Visibility derives another presentation from frozen receipts; hero folds remain unfiltered. */
 Br.Result view(S a, Predicate<Ab> b) {
  return b == null ? a.groups : Br.capture(a.t, m, a.ids, b);
 }
 /** Canonical per-session money and independently ordered Ledger deltas. */
 static class F {
  /** Closed facts reuse the existing save stamp; a correction invalidates them. */
  final long s;
  final Bu money, compact;
  final List<Ac> t;
  final List<Long> v;
  final boolean cut;
  final int review;
  final Bp.Dy market;
  final Ba recap;
  F(Ad a) {
   s = a.saveStamp;
   money = a.metrics(0L);
   Ad.CompactedContribution b = a.pl();
   compact = new Bu(a.getActivityHint(), a.paused, 0L, b.revenue, b.costs, b.getNet(), 0L);
   cut = b.rows > 0L || b.revenue != 0L || b.costs != 0L;
   var c = new ArrayList<Ac>(); var d = new ArrayList<Long>(); d.add(b.getNet());
   int e = 0;
   for (Ac h : a.getTransactions()) {
    if (h == null) continue;
    Ac i = copy(h); c.add(i);
    Bp.Ax j = Bp.transaction(i);
    if (j.included) d.add(j.getNet());
    if (Eh.aal(i)) e++;
   }
   t = unmodifiableList(c); v = unmodifiableList(d); review = e;
   market = Bp.zl(t);
   recap = a.isClosed() ? Ba.of(a) : null;
  }
  Bu metrics(Ad a, long b, boolean c) {
   Bu d = c ? money : compact;
   long e = a.getElapsedMillis(b);
   return new Bu(a.getActivityHint(), a.paused, e, d.revenue, d.costs, d.net,
   c ? Ad.hourly(d.net, e) : 0L, d.suppliesCosts, d.otherCosts, d.costSplitAvailable);
  }
 }
 /** The last/best rollup is over completed lineage members, in the original order. */
 class G {
  final int count;
  final boolean has;
  final long last, at, best;
  G(List<Ad> a) {
   Ad b = null; long c = Long.MIN_VALUE, d = 0L;
   for (Ad e : a) {
    long h = runMoney(e);
    if (b == null || e.startedAtEpochMillis > b.startedAtEpochMillis) { b = e; d = h; }
    c = Math.max(c, h);
   }
   count = a.size(); has = b != null; last = d;
   at = has ? b.startedAtEpochMillis : 0L; best = c == Long.MIN_VALUE ? 0L : c;
  }
  long runMoney(Ad a) { return fact(a).money.net; }
 }
 /** Scope construction preserves saturation order, compaction, Market ownership and groups. */
 class S {
  final Set<String> ids;
  final List<Ac> t;
  final Map<String, Row> markets;
  final long net;
  final boolean cut;
  final Br.Result groups;
  S(List<Ad> a) {
   var b = new LinkedHashSet<String>(); var c = new ArrayList<Ac>();
   long d = 0L; boolean e = false;
   for (Ad h : a) {
    if (h == null) continue;
    F i = fact(h); b.add(h.getId()); c.addAll(i.t); e |= i.cut;
    for (long j : i.v) d = safeAdd(d, j);
   }
   ids = unmodifiableSet(b); t = unmodifiableList(c); net = d; cut = e;
   var l = new HashMap<String, Row>();
   for (Row n : m) if (n != null && ids.contains(n.scopeSessionId)) l.put(n.presentationId, n);
   markets = unmodifiableMap(l);
   groups = Br.capture(t, m, ids, null);
  }
 }
 /** Own the receipt and its flows: later corrections cannot mutate this financial revision. */
 static Ac copy(Ac a) {
  Ac b = new Ac();
  b.id = a.id; b.timestampEpochMillis = a.timestampEpochMillis; b.activeElapsedMillis = a.activeElapsedMillis;
  b.type = a.type; b.context = a.context; b.note = a.note; b.activityName = a.activityName; b.counted = a.counted;
  b.revenue = a.revenue; b.costs = a.costs; b.confidence = a.confidence; b.explanation = a.explanation;
  b.correction = a.correction; b.correctionReason = a.correctionReason; b.encounterId = a.encounterId;
  b.sourceClaimId = a.sourceClaimId; b.actionKind = a.actionKind; b.observedActionLabel = a.observedActionLabel;
  b.trackedSinkObserved = a.trackedSinkObserved; b.ownDropRecoveryEligible = a.ownDropRecoveryEligible; b.aym = a.aym;
  var c = new ArrayList<Ab>();
  for (Ab d : a.getFlows()) {
   if (d == null) { c.add(null); continue; }
   Ab e = new Ab();
   e.itemId = d.itemId; e.itemName = d.itemName; e.quantityDelta = d.quantityDelta;
   e.unitPrice = d.unitPrice; e.valueDelta = d.valueDelta; e.priceSource = d.priceSource;
   e.priceCapturedAtEpochMillis = d.priceCapturedAtEpochMillis; c.add(e);
  }
  b.flows = unmodifiableList(c);
  if (a.trackedSinkShares != null) {
   var h = new ArrayList<Cd>();
   for (Cd i : a.trackedSinkShares) {
    if (i == null) { h.add(null); continue; }
    Cd j = new Cd(); j.itemId = i.itemId; j.remainingRemovedQty = i.remainingRemovedQty;
    j.remainingKnownQty = i.remainingKnownQty; j.remainingBasisGp = i.remainingBasisGp; h.add(j);
   }
   b.trackedSinkShares = unmodifiableList(h);
  }
  return b;
 }
}
