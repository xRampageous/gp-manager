package com.gpmanager;
import com.gpmanager.Bj.Snapshot;
import com.gpmanager.Aa.*;
import java.util.*;
import java.util.function.*;
import lombok.*;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import static com.gpmanager.Ae.*;
import static com.gpmanager.Aa.Side.*;
import static com.gpmanager.Aa.Stage.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static com.gpmanager.Dv.*;
import static com.gpmanager.Av.*;
import static com.gpmanager.Aj.*;
import static com.gpmanager.Ai.*;
import static com.gpmanager.Ak.msg;
/**
* Durable schema-104 GE custody ledger.
*
* <p>Physical placement principal, cancellation returns and refunds are ownership-neutral
* transfers. A safely realized settlement books one canonical counted Market result exactly
* once, guarded by the persisted cumulative-execution counters. Unattributable or unbased
* lifecycles fail closed through the existing Review domain instead of guessing.</p>
*
* <p>An observed collect coin movement (a live Collect interaction with its coin gain) is
* attributed to exactly the sold offers it proves: the offers whose slot cleared within the SLOT
* WINDOW, or the one subset of observed after-tax nets that sums to it exactly. A
* unique-but-unconfirmed match is held for that short window and settles only without contradicting
* slot evidence; a tie, a contradiction or exhausted evidence fails closed, quarantining only
* slot-evidenced candidates and leaving the rest pending. Collect cash that arrives before the
* matching sell execution evidence keeps its longer transient hold. Both holds are transient: a
* restart leaves the durable records to their own unobserved-settlement handoff, never a double
* booking. A coin gain with no Collect interaction keeps the previous booking path unchanged.</p>
*
* <p>The ledger never mutates booked transactions, never re-prices history, and never scans the
* whole session history: claims are quantity-bounded against the bounded open-record set.</p>
*/
class GeCustodyLedger {
/** How long a placement still explains the coins or items it took from the inventory. */
static final long PLACEMENT_WINDOW_MILLIS = 90_000L;
/** How long a terminal lifecycle may await an unobserved settlement before Review handoff. */
static final long UNOBSERVED_GRACE_MILLIS = 10L * 60L * 1000L;
/** A cleared slot whose cash or items never reached the inventory went to the bank by now. */
static final long BANK_COLLECT_MILLIS = 30_000L;
/**
* Slot-clear evidence window: at most 3 game ticks either side of the coin gain. A slot that
* cleared in this window proves its offer's coins were the ones collected.
*/
static final long SLOT_WINDOW_MILLIS = 1_800L;
/** One open record per GE slot; more candidates than this can never be enumerated. */
static final int MAX_SETTLEMENT_CANDIDATES = 8;
/** How long a fill or cancellation still explains a collected movement. */
static final long COLLECTION_WINDOW_MILLIS = 2L * 60L * 60L * 1000L;
/** Hard bound on durable open custody records. */
static final int MAX_RECORDS = 32;
/** Bounded retention of reconciled lifecycles so recent Market context survives. */
static final int MAX_CLOSED_RECORDS = 64;
/** Per-slot baseline of each observed offer; exact fill deltas come from its cumulative counters. */
Map<Integer, Snapshot> baselines = new HashMap<>();
List<Aa> records = new ArrayList<>();
/** Slots whose identity was replaced mid-life; the next placement is quarantined. */
Set<Integer> replacedSlots = new HashSet<>();
/**
* Aggregate GE cash observed with a live Collect interaction but not yet exactly attributable
* to every sell lifecycle it may settle. Retained internally (never a visible receipt) until
* the missing offer evidence arrives and the batch reconciles exactly, or the bounded
* lifecycle expires and the cash fails closed to Review. Transient by design: a restart
* without the evidence leaves the records to their own unobserved-settlement handoff, never a
* double booking.
*/
long pendingSettlementCashGp;
long pendingSettlementAtEpochMillis;
/** Earliest observation of the still-unattributed cash; the SLOT WINDOW anchor. */
long pendingSettlementHeldSinceEpochMillis;
/**
* True while the held cash is waiting for sell execution/terminal evidence (the existing long
* hold). Its evidence completion books the exact attribution immediately, as before; a short
* SLOT WINDOW hold instead waits for slot confirmation.
*/
boolean pendingSettlementAwaitingEvidence;
/**
* Transient slot-clear evidence (offer id to CLEARED observation time). It only proves which
* sold offers a nearby collect movement belongs to; like held cash it is never persisted and
* clears on restore/reset/adopt.
*/
Map<String, Long> slotClearedAtEpochMillis = new HashMap<>();
/**
* Transient: live lifecycles the login seed saw as an EMPTY slot. RuneLite reports every slot
* EMPTY at login before the server restores the offers, so the same offer arriving shortly
* after is a late replay of it, never a new offer reusing the slot.
*/
Map<String, Long> loginClearedAtEpochMillis = new HashMap<>();
static final long LOGIN_REPLAY_MILLIS = 2L * 60L * 1000L;
/**
* Transient: when each offer last explained a movement (its offer id, with a "$" suffix for the
* coin side). A placement explains one loss; a fill or cancellation explains nothing more until
* the offer is seen again.
*/
Map<String, Long> explainedAt = new HashMap<>();
/** Schema-106 pooled known-basis ledger; null keeps custody purely observational. */
TrackedBasisLedger basisLedger;
/** Wire the pooled known-basis ledger that owns SELL reservations and BUY coverage. */
synchronized void setBasisLedger(TrackedBasisLedger /* ledger */a) {
 basisLedger = a;
}

/** Latest capture time of an open SELL reservation for the item; 0 when none is open. */
synchronized long yh(int /* itemId */a) {
 int /* canonicalId */b = a;
 long /* latest */c = 0L;
 for (Aa /* record */d : records) {
  if (d == null || d.getSide() != SELL || d.itemId != b) {
   continue;
  }
  if (d.afs() <= 0L && d.afr() <= 0L) continue;
  long /* capturedAt */e = d.getBasisCapturedAtEpochMillis() > 0L
  ? d.getBasisCapturedAtEpochMillis() : d.getPlacedAtEpochMillis();
  c = max(c, e);
 }
 return c;
}

/** Immutable result of one settle: residual flows still needing normal classification. */
@AllArgsConstructor
static class Partition {
 List<Ab> residualFlows;
 List<Ac> countedBookings;
 List<Ac> transferBookings;
 /** Physical flows consumed by custody claims; used to spend matching offer evidence. */
 List<Ab> claimedFlows;
 Ac lastBooking() {
  List<Ac> /* bookings */b = countedBookings.isEmpty() ? transferBookings : countedBookings;
  return b.isEmpty() ? null : b.get(b.size() - 1);
 }
}

// ---------------------------------------------------------------------------------------
// Lifecycle observation
// ---------------------------------------------------------------------------------------
/** Apply one offer-ledger transition; returns any Review handoff or settled rows it forced. */
synchronized List<Ac> ack(Bj.Transition /* transition */b,
String /* itemName */c, long /* now */d, String /* sessionId */e) {
 if (b == null || b.current == null) return Collections.emptyList();
 var /* handoffs */f = new ArrayList<Ac>();
 observe(b.previous, b.current, c, d, e, f);
 // New execution/lifecycle evidence may complete a settlement whose cash was observed
 // first; the held batch settles as soon as it is exactly attributable.
 aee(d, e, f);
 return f;
}

/**
* Applies one slot snapshot against the slot's baseline: exact incremental fills from the
* cumulative counters, Modify Offer, slot reuse and identity replacement. Backwards counters,
* value moved without quantity, or a missed placement are a desync, never a fill.
*/
void observe(Snapshot /* previous */b, Snapshot /* current */c, String /* itemName */d,
long /* now */e, String /* sessionId */f, List<Ac> /* handoffs */g) {
 int /* slot */h = c.slot;
 Side /* side */i = Bj.sideOf(c.state);
 Snapshot /* base */j = baselines.remove(h);
 if (i == null) {
  Aa /* record */k = touch(h, j != null ? j : b, e);
  if (k != null) {
   k.setCleared(true);
   // Transient slot evidence: proves which offer a nearby collect belongs to.
   slotClearedAtEpochMillis.put(k.getOfferId(), e);
  }
  return;
 }
 Aa /* replay */l = j == null ? ym(h, i, c, e) : null;
 if (l != null) {
  loginClearedAtEpochMillis.remove(l.getOfferId());
  slotClearedAtEpochMillis.remove(l.getOfferId());
  l.setCleared(false);
  resume(l, c, e);
  baselines.put(h, c);
  return;
 }
 boolean /* active */m = Bj.isActive(c.state);
 if (j == null || m && (Bj.sideOf(j.state) != i || j.itemId != c.itemId)) {
  if (j != null) {
   // Item/side changed mid-life without Modify evidence: retire the old lifecycle and
   // quarantine the next; never inherit prior basis or progress.
   Aa /* old */n = touch(h, j, e);
   if (n != null) {
    n.setConfidence(Confidence.AMBIGUOUS);
    ut(n, e, f, msg("ab"), g);
   }
   replacedSlots.add(h);
  }
  baselines.put(h, c);
  if (j == null && b != null && b.state != GrandExchangeOfferState.EMPTY) {
   // A missed placement cannot be dated: quarantine the lifecycle, emit no fill.
   desync(h, c, c, d, e, f, g);
   return;
  }
  place(h, i, c, d, e, f, g);
  advance(h, c, c, c.quantityTraded, c.spent, e);
  return;
 }
 if (m && (j.totalQuantity != c.totalQuantity || j.price != c.price)) {
  // A price-only Modify keeps the lifecycle and its basis; a quantity change has no exact
  // per-slice basis allocation, so realization fails closed to Review.
  boolean /* priceOnly */o = j.totalQuantity == c.totalQuantity;
  baselines.put(h, new Snapshot(h, j.state, j.itemId,
  c.totalQuantity, j.quantityTraded, o ? c.price : j.price, j.spent));
  Aa /* record */p = touch(h, j, e);
  if (p != null && o) {
   p.listedPrice = nonNeg(c.price);
  } else if (p != null) {
   p.offeredQty = nonNeg(c.totalQuantity);
   p.setQuantityModified(true);
  }
  return;
 }
 long /* quantity */q = c.quantityTraded - j.quantityTraded;
 long /* spent */r = c.spent - j.spent;
 baselines.put(h, new Snapshot(h, j.state, j.itemId, j.totalQuantity,
 c.quantityTraded, j.price, c.spent));
 if (q < 0L || r < 0L || q == 0L && r > 0L) {
  desync(h, j, c, d, e, f, g);
  return;
 }
 advance(h, j, c, q, r, e);
}

/** A new offer in the slot: a duplicate replay of the live lifecycle changes nothing. */
void place(int /* slot */b, Side /* side */c, Snapshot /* offer */d, String /* itemName */e, long /* now */f,
String /* sessionId */g, List<Ac> /* handoffs */h) {
 Aa /* previous */i = aem(b);
 if (i != null && xo(i, c, d.itemId, d.totalQuantity,
 d.price) && !i.cleared && !i.xs()) {
  i.setLastSeenAtEpochMillis(f);
  return;
 }
 if (i != null && i.getStage() == OPEN && i.wo()) {
  // Settled but not yet sealed (a settlement completed by an offer transition):
  // seal it exactly as the next settle would, so it stays as history.
  i.setStage(CLOSED);
  i = null;
 }
 if (i != null && i.cleared && i.xs() && trusted(i, i.getSide())) {
  // Owner 1.2: a finished offer collected moments before its slot is reused still waits for
  // its items or coins to settle; it gives up the slot instead of going to Review.
  i.slot = -1;
  i = null;
 }
 if (i != null) {
  // Slot reuse: a new offer never inherits prior basis or progress.
  ut(i, f, g, msg("cc"), h);
 }
 // A settled (CLOSED) lifecycle in this slot stays as bounded market history for its
 // booked sale; only its unrealized reservation leaves with the slot.
 aey(yg(b));
 Aa /* record */j = auu(b, c, d.itemId, e, d.totalQuantity,
 d.price, d.state.name(), f, g);
 if (replacedSlots.remove(b)) j.setConfidence(Confidence.AMBIGUOUS);
 // Per-fill tax is exact only for a placement observed live from an empty slot.
 j.setFillTaxExact(c == SELL && j.getConfidence() == Confidence.CONFIRMED);
 records.add(j);
 rj(f, g, h);
}

/** Exact fill progress, then the terminal state (a final fill lands before a cancellation). */
void advance(int /* slot */a, Snapshot /* offer */b, Snapshot /* current */c, long /* quantity */d, long /* spent */e, long /* now */f) {
 boolean /* fill */g = d > 0L && e >= 0L;
 boolean /* terminal */h = Bj.isTerminal(c.state);
 Aa /* record */i = g || h ? touch(a, b, f) : null;
 if (i != null && g) afi(i, d, e);
 if (i != null && h) i.setOfferState(c.state.name());
}

/** Counters with no exact lineage: quarantine the lifecycle, or start an unbased one. */
void desync(int /* slot */a, Snapshot /* offer */b, Snapshot /* current */c, String /* itemName */d,
long /* now */e, String /* sessionId */f, List<Ac> /* handoffs */g) {
 Aa /* record */h = touch(a, b, e);
 if (h != null) {
  h.setConfidence(Confidence.AMBIGUOUS);
  h.setFillTaxExact(false);
 } else if (b.itemId > 0) {
  h = auu(a, Bj.sideOf(b.state), b.itemId, d,
  b.totalQuantity, b.price, c.state.name(), e, f);
  h.setConfidence(Confidence.LEGACY_UNBASED);
  records.add(h);
 }
 rj(e, f, g);
}

/** The live lifecycle the login seed cleared in this slot, when this snapshot is that same offer. */
Aa ym(int /* slot */a, Side /* side */b, Snapshot /* current */c, long /* now */d) {
 Aa /* record */e = aem(a);
 Long /* clearedAt */f = e == null ? null : loginClearedAtEpochMillis.get(e.getOfferId());
 return f != null && d - f <= LOGIN_REPLAY_MILLIS && xo(e, b,
 c.itemId, c.totalQuantity, c.price) ? e : null;
}

/** Resume a persisted lifecycle; progress made while away is adopted once, never as a fill. */
void resume(Aa /* existing */a, Snapshot /* snapshot */b, long /* now */c) {
 a.setConfidence(Confidence.RESUMED);
 a.setLastSeenAtEpochMillis(c);
 a.setOfferState(b.state.name());
 if (b.quantityTraded != a.getFilledQty()) {
  // Fills while logged out were never observed at their own prices.
  a.setFillTaxExact(false);
 }
 a.setFilledQty(b.quantityTraded);
 a.setSpentGp(nonNeg(b.spent));
}

Aa auu(int /* slot */a, Side /* side */b, int /* itemId */c, String /* itemName */d, long /* totalQty */e,
long /* price */f, String /* state */g, long /* now */h, String /* sessionId */i) {
 var /* record */j = new Aa(UUID.randomUUID().toString(), a, b, c,
 d, e, f, h, Ag.axw(i));
 j.setLastSeenAtEpochMillis(h);
 j.setOfferState(g);
 return j;
}

/**
* Record the login slot baseline: resume a persisted matching lifecycle, quarantine active
* offers first seen now as legacy/unbased, and hand off records whose slot changed identity.
*/
synchronized List<Ac> avh(Map<Integer, Snapshot> /* snapshots */b, long /* now */c, String /* sessionId */d) {
 var /* handoffs */e = new ArrayList<Ac>();
 baselines.clear();
 loginClearedAtEpochMillis.clear();
 if (b != null) {
  for (Snapshot /* snapshot */f : b.values()) {
   if (f == null) continue;
   Aa /* existing */g = aem(f.slot);
   if (f.state == GrandExchangeOfferState.EMPTY) {
    if (g != null) loginClearedAtEpochMillis.put(g.getOfferId(), c);
    else g = yg(f.slot);
    if (g != null) {
     g.setCleared(true);
     g.setLastSeenAtEpochMillis(c);
     slotClearedAtEpochMillis.put(g.getOfferId(), c);
    }
    continue;
   }
   Side /* side */h = Bj.sideOf(f.state);
   if (h == null) continue;
   // Pre-existing progress is only a baseline, never replayed as a fill.
   baselines.put(f.slot, f);
   if (g != null && xo(g, h, f.itemId, f.totalQuantity, f.price)) {
    resume(g, f, c);
    continue;
   }
   if (g != null) {
    ut(g, c, d, msg("bi"), e);
   }
   Aa /* record */i = auu(f.slot, h, f.itemId, "",
   f.totalQuantity, f.price, f.state.name(), c, d);
   i.setConfidence(Confidence.LEGACY_UNBASED);
   i.setFilledQty(f.quantityTraded);
   i.setSpentGp(nonNeg(f.spent));
   records.add(i);
  }
 }
 rj(c, d, e);
 return e;
}

/**
* True when every moved flow is explained by a recent offer: an item or coins lost shortly after
* a sell or buy was placed, an item or coins gained after a buy or sell filled, or the other
* side's cancellation. Custody is authoritative for GE economics, so a movement it could not
* claim but an offer explains is Grand Exchange activity for Review, never counted.
*/
synchronized boolean explains(List<Ab> /* flows */a, long /* now */b) {
 boolean /* moved */c = false;
 for (Ab /* flow */d : a) {
  if (d != null && d.quantityDelta != 0L) {
   c = true;
   if (explaining(d, b) == null) return false;
  }
 }
 return c;
}

/** Spends the offer evidence behind these movements: one offer side explains one movement. */
synchronized void spend(List<Ab> /* flows */a, long /* now */b) {
 for (Ab /* flow */c : a) {
  String /* key */d = c == null || c.quantityDelta == 0L ? null : explaining(c, b);
  if (d != null) explainedAt.put(d, b);
 }
}

String explaining(Ab /* flow */b, long /* now */c) {
 boolean /* coins */d = b.itemId == ItemID.COINS;
 for (Aa /* record */e : records) {
  boolean /* buy */f = e.getSide() == BUY;
  String /* key */g = e.getOfferId() + (d ? "$" : "");
  Long /* spent */h = explainedAt.get(g);
  long /* seen */i = e.getLastSeenAtEpochMillis();
  // Only a live placement moves coins (buy) or items (sell) out of the inventory; a finished
  // fill of the same side or a cancellation of the other brings them back. Passive
  // progress on an open offer explains nothing.
  boolean /* fits */j = b.isCost() ? f == d && h == null && !e.wx() && !e.vq()
  && c - e.getPlacedAtEpochMillis() <= PLACEMENT_WINDOW_MILLIS
  : (h == null || h < i) && c - i <= COLLECTION_WINDOW_MILLIS
  && (f != d ? e.getFilledQty() > 0L && e.xs() : e.vq());
  if (j && (d || e.itemId == b.itemId)) return g;
 }
 return null;
}

// ---------------------------------------------------------------------------------------
// Ab-level custody claims and canonical realization
// ---------------------------------------------------------------------------------------
/** Claim custody flows from one stabilized settle and book exact economics. */
synchronized Partition partition(List<Ab> /* incoming */a, long /* now */b, String /* sessionId */c) {
 return partition(a, b, c, /* itemId */d -> true, false);
}

/**
* Claim custody flows from one stabilized settle. {@code itemAllowed} excludes items already
* owned by a stronger per-item evidence source (consume/drop/loot) without weakening the
* whole-transaction safety gates. {@code collectionEvidence} is a bounded player Collect
* interaction; without it, collection claims need an observed terminal offer state.
*/
synchronized Partition partition(List<Ab> /* incoming */b, long /* now */c, String /* sessionId */d,
IntPredicate /* itemAllowed */e, boolean /* collectionEvidence */f) {
 IntPredicate /* allowed */g = e == null ? /* itemId */h -> true : e;
 var /* counted */i = new ArrayList<Ac>();
 var /* transfers */j = new ArrayList<Ac>();
 var /* residual */k = new ArrayList<Ab>(b == null ? Collections.emptyList() : b);
 var /* claimed */l = new ArrayList<Ab>();
 // A settlement held from an earlier collect may now be exactly attributable.
 aee(c, d, i);
 oc(k, l, c, g);
 ob(k, l, c);
 oa(k, l);
 oe(k, l, g);
 ny(k, l, c, d, i, g, f);
 og(k, l, c, d, i, f);
 if (!l.isEmpty()) {
  j.add(new Ac(c, null, Ai.TRANSFER, Aj.TRANSFER,
  msg("db"), "Market", false, new ArrayList<>(l), Bd.CONFIRMED, msg("gd"), null));
 }
 // A hold created by this settle retries immediately: a batch whose evidence is complete
 // fails closed at once instead of waiting invisibly for a later event.
 aee(c, d, i);
 for (Aa /* open */m : records) {
  if (m != null && m.getStage() == OPEN && m.wo()) m.setStage(CLOSED);
 }
 return new Partition(k, i, j, l);
}

/** Prune reconciled lifecycles, expire terminal-unobserved ones and cap stale records. */
synchronized Partition maintenance(long /* now */a, String /* sessionId */b) {
 var /* counted */c = new ArrayList<Ac>();
 prune(a, b, c);
 // A held settlement retries and, once its evidence is exhausted or its bounded
 // lifecycle expires, fails closed to Review with the observed cash preserved.
 aee(a, b, c);
 return new Partition(Collections.emptyList(), c, Collections.emptyList(), Collections.emptyList());
}

void prune(long /* now */b, String /* sessionId */c, List<Ac> /* reviews */d) {
 int /* closed */e = 0;
 for (Aa /* record */f : records) {
  if (f != null && f.getStage() == CLOSED) e++;
 }
 // Settled history is bounded: the oldest closed lifecycles leave first.
 for (; e > MAX_CLOSED_RECORDS; e--) {
  Aa /* oldest */g = acl();
  aey(g);
  records.remove(g);
 }
 for (int /* index */h = records.size() - 1; h >= 0; h--) {
  Aa /* record */i = records.get(h);
  if (i == null || i.getOfferId().isEmpty() || i.getStage() == CLOSED_UNOBSERVED) {
   aey(i);
   records.remove(h);
   continue;
  }
  if (i.getStage() == CLOSED) continue;
  if (i.getStage() == AMBIGUOUS) {
   ut(i, b, c, msg("bv"), d);
   continue;
  }
  if (i.wo()) {
   i.setStage(CLOSED);
   continue;
  }
  // Expire by grace only a slot that is CLEARED and whose cash was never seen (a
  // collect-to-bank and similar). A SELL whose slot still shows the terminal state is
  // still in GE custody: its coins may be collected much later, so it must not expire by
  // time. BUY keeps its existing pattern (reported, not changed).
  boolean /* cleared */j = i.cleared;
  // Owner 2026-10-09: Collect to bank (or a collect while GP Manager was away) settles from
  // the offer itself instead of waiting for Review; held collect cash may still be its own.
  if (j && b - i.getLastSeenAtEpochMillis() > BANK_COLLECT_MILLIS && pendingSettlementCashGp <= 0L
  && banked(i, b, c, d)) {
   continue;
  }
  boolean /* buyTerminal */k = i.getSide() != SELL && i.xs();
  if ((j || k) && b - i.getLastSeenAtEpochMillis() > UNOBSERVED_GRACE_MILLIS) {
   ut(i, b, c, msg("dc"), d);
  }
 }
 slotClearedAtEpochMillis.values().removeIf(/* at */l -> b - l > UNOBSERVED_GRACE_MILLIS);
}

void rj(long /* now */a, String /* sessionId */b, List<Ac> /* handoffs */c) {
 List<Aa> /* open */d = aeo();
 d.removeIf(/* record */e -> e.getStage() == CLOSED);
 for (int /* index */f = 0; f < d.size() - MAX_RECORDS; f++) {
  ut(d.get(f), a, b, msg("bo"), c);
 }
}

// -- Custody claims: each settle offers its flows to the lifecycles, oldest placement first --
/** One lifecycle's claim on a flow: the magnitude it takes, or 0 to pass. */
interface ClaimStep {
 long claim(Aa /* record */b, Ab /* flow */c, long /* remaining */d);
}

void nz(List<Ab> /* residual */a, Predicate<Ab> /* which */b, ClaimStep /* step */c) {
 Dv.claim(a, b, (/* flow */d, /* magnitude */e) -> {
  long /* remaining */f = e;
  for (Aa /* record */g : aeo()) {
   if (f > 0L) f -= nonNeg(c.claim(g, d, f));
  }
  return e - f;
 });
}

/** A lifecycle observed from its placement with no identity desync. */
static boolean trusted(Aa /* record */a, Side /* side */b) {
 return a.getSide() == b && !a.wx() && a.getConfidence() != Confidence.AMBIGUOUS;
}

/** SELL placement principal (item loss). */
void oc(List<Ab> /* residual */a, List<Ab> /* claimed */b, long /* now */c, IntPredicate /* itemAllowed */d) {
 nz(a, /* flow */e -> e.isCost() && e.itemId != ItemID.COINS && d.test(e.itemId),
 (/* record */f, /* flow */g, /* remaining */h) -> {
  long /* claim */i = min(h, f.getOfferedQty());
  if (!trusted(f, SELL) || f.itemId != g.itemId
  || f.vq() || f.cleared || f.getCapturedQty() > 0L
  || c - f.getPlacedAtEpochMillis() > PLACEMENT_WINDOW_MILLIS || i <= 0L) {
   return 0L;
  }
  ts(f, g, c);
  if (basisLedger != null) {
   TrackedBasisLedger.Reservation /* reservation */j = basisLedger.reserve(f.itemId, i, c);
   if (!j.isEmpty()) {
    f.reservedTrackedQty = nonNeg(safeAdd(f.getReservedTrackedQty(), j.trackedQty));
    f.reservedTrackedBasisGp = nonNeg(safeAdd(f.getReservedTrackedBasisGp(), j.basisGp));
   }
  }
  f.setCapturedQty(i);
  f.setLastSeenAtEpochMillis(c);
  b.add(g.part(i));
  return i;
 });
}

/** BUY observed reserve (coin loss). */
void ob(List<Ab> /* residual */b, List<Ab> /* claimed */c, long /* now */d) {
 Set<Integer> /* gainItems */e = gainQuantities(b, ANY).keySet();
 nz(b, /* flow */f -> f.isCost() && f.itemId == ItemID.COINS, (/* record */g, /* flow */h, /* remaining */i) -> {
  long /* reserve */j = nonNeg(g.getOfferedQty() * g.getListedPrice());
  // Owner 1.2: an offer placed, bought and collected inside one settle shows only its exact
  // spend, the reserve with its change already back; the purchase books it, never Review.
  boolean /* instant */k = e.contains(g.itemId) && g.xs() && i == g.getSpentGp();
  long /* claim */l = k ? i : min(i, j);
  // A same-item acquisition alongside the coin loss is a purchase, not a reserve:
  // leave the mixed movement to the normal classification path.
  // An offer that filled at once still took these coins; only a cancelled one kept none.
  if (!trusted(g, BUY) || g.vq() || g.cleared && !k
  || g.getReserveGp() > 0L || d - g.getPlacedAtEpochMillis() > PLACEMENT_WINDOW_MILLIS
  || !k && e.contains(g.itemId) || l <= 0L) {
   return 0L;
  }
  g.reserveGp = k ? j : nonNeg(l);
  if (k) g.refundedGp = nonNeg(j - l);
  g.setLastSeenAtEpochMillis(d);
  c.add(h.part(l, 1, FACE_VALUE));
  return l;
 });
}

/** BUY refund (coin gain). */
void oa(List<Ab> /* residual */a, List<Ab> /* claimed */b) {
 nz(a, /* flow */c -> c.isGain() && c.itemId == ItemID.COINS, (/* record */d, /* flow */e, /* remaining */f) -> {
  long /* refundRoom */g = nonNeg(d.getOfferedQty() * d.getListedPrice() - d.getSpentGp())
  - d.getRefundedGp();
  long /* claim */h = min(f, min(nonNeg(g), d.nd()));
  // Change is owed once the offer is finished (bought or cancelled) or its slot cleared.
  if (!trusted(d, BUY) || !(d.xs() || d.cleared) || h <= 0L) {
   return 0L;
  }
  d.refundedGp = nonNeg(safeAdd(d.getRefundedGp(), h));
  b.add(e.part(h, 1, FACE_VALUE));
  return h;
 });
}

/** SELL cancellation return (item gain). */
void oe(List<Ab> /* residual */a, List<Ab> /* claimed */b, IntPredicate /* itemAllowed */c) {
 nz(a, /* flow */d -> d.isGain() && d.itemId != ItemID.COINS && c.test(d.itemId),
 (/* record */e, /* flow */f, /* remaining */g) -> {
  long /* claim */h = min(g, e.aib());
  if (!trusted(e, SELL) || e.itemId != f.itemId
  || !(e.vq() || e.cleared) || h <= 0L) {
   return 0L;
  }
  // The returned principal is neutral at its frozen custody basis, never the
  // current live quote; with no frozen basis it stays the observed movement.
  aez(e, h);
  e.setReturnedQty(safeAdd(e.getReturnedQty(), h));
  b.add(e.getBasisUnitPrice() > 0L
  ? f.part(h, e.getBasisUnitPrice(), adi(e.getBasisSource())) : f.part(h));
  return h;
 });
}

/** BUY collection (item gain). */
void ny(List<Ab> /* residual */b, List<Ab> /* claimed */c, long /* now */d,
String /* sessionId */e, List<Ac> /* counted */f, IntPredicate /* itemAllowed */g, boolean /* collectionEvidence */h) {
 nz(b, /* flow */i -> i.isGain() && i.itemId != ItemID.COINS && g.test(i.itemId),
 (/* record */j, /* flow */k, /* remaining */l) -> {
  long /* claim */m = min(l, j.ada());
  // Passive progress alone never turns a same-item delta into a collection.
  if (!trusted(j, BUY) || j.itemId != k.itemId || j.quantityModified || m <= 0L
  || !(h || j.xs() || j.cleared)) {
   return 0L;
  }
  if (j.getBasisSource().isEmpty()) ts(j, k, d);
  j.collectedQty = nonNeg(safeAdd(j.getCollectedQty(), m));
  j.setCollectedValueGp(safeAdd(j.collectedValueGp, Ae.agz(m, k.unitPrice)));
  j.setLastSeenAtEpochMillis(d);
  c.add(k.part(m));
  if (j.getCollectedQty() < j.getFilledQty()) {
   // Partial collection: execution chunks are not attributable; fail closed.
   j.setCollectionAmbiguous(true);
  } else {
   mi(j, d, e, f);
  }
  return m;
 });
}

void mi(Aa /* record */a, long /* now */b, String /* sessionId */c, List<Ac> /* counted */d) {
 long /* settleQty */e = a.getCollectedQty() - a.getSettledQty();
 long /* execution */f = a.acx();
 if (e <= 0L) return;
 boolean /* clean */g = vu(a, c);
 var /* flows */h = new ArrayList<Ab>(2);
 // Exact NEW-model BUY: the asset value equals the actual settled spend, so acquisition is
 // Net-neutral and the acquired quantity becomes known coverage at that exact spend.
 h.add(new Ab(a.itemId, a.getItemName(), e, toInt(f / e),
 f, adi(a.getBasisSource()), a.getBasisCapturedAtEpochMillis()));
 h.add(coins(-f, b));
 Ac /* transaction */i = mz(h, b, g, g, msg("gb"));
 d.add(i);
 a.setSettledQty(safeAdd(a.getSettledQty(), e));
 a.setSettledExecutionGp(safeAdd(a.getSettledExecutionGp(), f));
 a.setSettledCashGp(safeAdd(a.settledCashGp, f));
 a.setSettlementId(i.getId());
 a.collectedQty = nonNeg(a.getSettledQty());
 a.setCollectedValueGp(0L);
}

static Ab coins(long /* amount */a, long /* now */b) {
 return new Ab(ItemID.COINS, "Coins", a, 1, a, FACE_VALUE, b);
}

/** Claims a whole coin gain as held or settled GE cash. */
static long oj(Ab /* flow */a, List<Ab> /* claimed */b) {
 b.add(a.part(a.valueDelta, 1, FACE_VALUE));
 return a.valueDelta;
}

// -- SELL collection (coin gain) ----------------------------------------------------------
/**
* Attribute one observed collect coin movement to exactly the sold offers it proves.
*
* <p>Exact integer equality only: a movement settles a subset when the subset's observed
* after-tax nets sum to it exactly. The slot-clear set is the primary proof; otherwise the
* unique matching subset is confirmed by slot evidence within the SLOT WINDOW or, with no
* contradicting evidence, at the window end. Ties and unprovable evidence fail closed: only
* slot-evidenced candidates may be quarantined, and every other candidate stays pending for a
* later exact collect.</p>
*/
void og(List<Ab> /* residual */b, List<Ab> /* claimed */c, long /* now */d,
String /* sessionId */e, List<Ac> /* counted */f, boolean /* collectionEvidence */g) {
 if (!g) {
  // A coin gain with no Collect interaction is not a collect interaction: keep the
  // pre-6A.1A2 booking path byte-for-byte (reported as a remaining exposure).
  od(b, c, d, e, f);
  return;
 }
 Dv.claim(b, /* flow */h -> h.isGain() && h.itemId == ItemID.COINS, (/* flow */i, /* magnitude */j) -> {
  long /* cashGp */k = i.valueDelta;
  List<Aa> /* candidates */l = aii(true);
  if (l.isEmpty()) {
   // Coins may arrive before the offer's execution/terminal evidence. A live Collect
   // interaction with a captured sell lifecycle still pending is that settlement's
   // cash; retain it internally instead of letting an unresolved Coins row reach
   // Review. The bounded hold retries as offer evidence arrives.
   if (k <= 0L || !uz()) return 0L;
   agq(k, d);
   pendingSettlementAwaitingEvidence = true;
   return oj(i, c);
  }
  Attribution /* attribution */m = attribute(l, k, d);
  if (m.kind == Bh.SETTLE) {
   ahz(m.records, k, d, e, f);
   return oj(i, c);
  }
  if (!m.contradicted) {
   // Exact-but-unconfirmed, tied, or unmatched evidence: retain the observed cash
   // transiently. The SLOT WINDOW gathers the slot evidence a fail-closed decision
   // needs; while a sell can still receive execution evidence, the existing long hold
   // keeps waiting for it.
   agq(k, d);
   pendingSettlementAwaitingEvidence |= m.kind == Bh.NONE && uz();
   return oj(i, c);
  }
  // No exact attribution: only candidates with slot evidence in this interaction may be
  // quarantined. The unattributed movement follows the ordinary path to one uncounted
  // Review Coins row; every other candidate stays pending for a later exact collect.
  adt(m.quarantinable, d, e, f);
  return 0L;
 });
}

/**
* Pre-6A.1A2 booking for a coin gain with no Collect interaction. Kept byte-for-byte so
* ordinary non-collect coin movements keep their existing classification; it is never used
* for a collect interaction.
*/
void od(List<Ab> /* residual */a,
List<Ab> /* claimed */b, long /* now */c, String /* sessionId */d, List<Ac> /* counted */e) {
 Dv.claim(a, /* flow */f -> f.isGain() && f.itemId == ItemID.COINS, (/* flow */g, /* magnitude */h) -> {
  List<Aa> /* candidates */i = aii(false);
  long /* totalExecution */j = 0L;
  for (Aa /* record */k : i) j = safeAdd(j, k.acx());
  long /* original */l = g.valueDelta;
  long /* claimCash */m = min(l, j);
  if (m <= 0L) return 0L;
  if (i.size() > 1) {
   boolean /* allClean */n = true;
   boolean /* allKnownCoverage */o = true;
   long /* expectedTax */p = 0L;
   for (Aa /* record */q : i) {
    n &= vu(q, d);
    o &= q.afs() >= q.ada();
    // Candidate fee only: the aggregate observed cash must confirm the exact combined gap.
    p = safeAdd(p, nonNeg(acz(q)));
   }
   boolean /* taxAttributed */r = p > 0L && j - m == p;
   if (!r && (p > 0L || m != j) && !o) {
    n = false;
    for (Aa /* record */t : i) t.setCollectionAmbiguous(true);
   }
   // An exact batch books each offer's after-tax share of the observed cash; any
   // other batch books gross execution and an adjustment row for the difference.
   long /* allocated */u = 0L;
   for (Aa /* record */v : i) {
    long /* settleQty */w = v.ada();
    long /* execution */x = v.acx();
    if (w > 0L && x > 0L) {
     long /* cash */y = r ? x - nonNeg(acz(v)) : x;
     mq(v, w, y, x, c, d, n, e);
     u = safeAdd(u, y);
    }
   }
   if (m != u) {
    // Legacy (non-collect) batch adjustment row; collect interactions never produce one.
    e.add(new Ac(c, null, n ? TRADE : UNCERTAIN, MARKET, msg("ak"), "Market", n,
    singletonList(coins(m - u, c)),
    n ? Bd.CONFIRMED : Bd.UNCERTAIN, msg("ga"), null));
   }
  } else {
   Aa /* record */z = i.get(0);
   long /* execution */aa = z.acx();
   boolean /* over */ab = l > aa;
   z.setCollectionAmbiguous(z.collectionAmbiguous || ab);
   mq(z, z.ada(), ab ? aa : m, aa,
   c, d, !ab && vu(z, d), e);
  }
  b.add(g.part(m, 1, FACE_VALUE));
  return m;
 });
}

/**
* Book one exactly attributed subset at each offer's own observed after-tax cash. A sale that
* filled at several prices in one observed chunk has a tax range, not one tax: the observed
* cash proves the subset's total, so each such offer pays its lowest possible tax plus, in
* order, the rest up to its highest (owner 2026-10-08: a big mixed-price sale is no Review).
*/
void ahz(List<Aa> /* records */a, long /* cash */k, long /* now */b, String /* sessionId */c, List<Ac> /* counted */d) {
 boolean /* allClean */e = true;
 for (Aa /* record */f : a) {
  e &= vu(f, c);
  long[] /* net */n = net(f);
  if (n != null) k -= n[1];
 }
 // The tax beyond every offer's lowest possible tax.
 k = -k;
 for (Aa /* record */g : a) {
  long[] /* net */n = net(g);
  // An offer with no execution is never part of a match; the attribution excluded it.
  if (n == null) continue;
  long /* execution */i = g.acx();
  long /* extra */l = max(0L, min(n[1] - n[0], k));
  k -= l;
  long /* tax */j = i - n[1] + l;
  if (acz(g) < 0L) {
   // The cash proved it: later reads (the receipt, a restart) see this exact tax.
   g.fillTaxExact = true;
   g.fillTaxGp = safeAdd(g.getSettledTaxGp(), j);
  }
  mq(g, g.ada(), i - j, i, b, c, e, d);
 }
}

/**
* The pending execution's possible after-tax cash {lowest, highest}, or null with no execution.
* Exact when its tax is proven; otherwise each item paid price/50 rounded down, so the stack
* paid at most gross/50 and at least (gross - 49 per item)/50 (below the 5M per-item cap).
*/
static long[] net(Aa /* record */a) {
 long /* execution */b = a.acx();
 long /* quantity */c = a.ada();
 long /* tax */d = acz(a);
 if (b <= 0L || c <= 0L) return null;
 if (d >= 0L) return new long[] {b - d, b - d};
 long /* least */e = b >= 250_000_000L ? 0L : max(0L, (b - 49L * c + 49L) / 50L);
 return new long[] {b - b / 50L, b - e};
}

/**
* Fail closed on the slot-evidenced candidates only. The quarantine booking keeps today's
* shape (uncounted, collectionAmbiguous, the booked allocation recorded) while the observed
* cash itself is never assigned to a lifecycle it was not proven part of.
*/
void adt(List<Aa> /* quarantinable */a, long /* now */b, String /* sessionId */c, List<Ac> /* counted */d) {
 for (Aa /* record */e : a) {
  long /* settleQty */f = e.ada();
  long /* execution */g = e.acx();
  if (f > 0L && g > 0L) {
   e.setCollectionAmbiguous(true);
   mq(e, f, g, g, b, c, false, d);
  }
 }
}

/** Candidate sell lifecycles for one coin gain; the retry needs durable terminal evidence. */
List<Aa> aii(boolean /* collectionEvidence */a) {
 var /* candidates */b = new ArrayList<Aa>();
 for (Aa /* record */c : aeo()) {
  if (c.getSide() == SELL && c.ada() > 0L && c.getCapturedQty() > 0L
  && c.ahm() >= c.ada() && !c.wx()
  && (a || c.xs() || c.cleared)) {
   b.add(c);
  }
 }
 return b;
}

/** Retain observed collect cash transiently; the SLOT WINDOW anchors on the first hold. */
void agq(long /* cashGp */a, long /* now */b) {
 if (a <= 0L) return;
 if (pendingSettlementCashGp <= 0L) pendingSettlementHeldSinceEpochMillis = b;
 pendingSettlementCashGp = safeAdd(pendingSettlementCashGp, a);
 pendingSettlementAtEpochMillis = max(pendingSettlementAtEpochMillis, b);
}

/** Exact attribution of one collect coin movement over the current candidate set. */
Attribution attribute(List<Aa> /* candidates */b, long /* cashGp */c, long /* coinAt */d) {
 int /* size */e = b.size();
 var /* quarantinable */f = new ArrayList<Aa>();
 if (e <= 0 || e > MAX_SETTLEMENT_CANDIDATES) {
  return new Attribution(Bh.NONE, f, f, false);
 }
 // Each candidate's possible after-tax cash: one value, or a range for a mixed-price fill.
 long[][] /* nets */g = new long[e][];
 var /* cleared */i = new ArrayList<Integer>();
 for (int /* index */l = 0; l < e; l++) {
  Aa /* record */m = b.get(l);
  g[l] = net(m);
  Long /* clearedAt */o = slotClearedAtEpochMillis.get(m.getOfferId());
  // The candidate's slot cleared within the SLOT WINDOW of the coin gain.
  if (o != null && Math.abs(o - d) <= SLOT_WINDOW_MILLIS) {
   i.add(l);
   f.add(m);
  }
 }
 // Apply slot evidence only after enumerating every subset within the eight-offer bound.
 List<List<Integer>> /* matches */p = aan(g, c, 1 << e);
 List<Integer> /* match */q = null;
 Bh /* kind */r = Bh.NONE;
 boolean /* contradicted */t = false;
 if (p.contains(i)) {
  // §2.1: the slot-clear set is the primary proof; a single candidate has no competing
  // explanation either, so neither needs slot confirmation (an amount it cannot have
  // received, or an execution with no known price, still fails closed below).
  r = Bh.SETTLE;
  q = i;
 } else if (e == 1 && !p.isEmpty()) {
  r = Bh.SETTLE;
  q = p.get(0);
 } else if (p.size() > 1) {
  // §2.3: slot evidence may still select exactly one matching subset.
  p.removeIf(/* subset */u -> !u.containsAll(i));
  t = !i.isEmpty() && p.isEmpty();
  r = t ? Bh.NONE : p.size() == 1 ? Bh.SETTLE : Bh.TIE;
  q = p.size() == 1 ? p.get(0) : null;
 } else if (p.size() == 1) {
  // A unique match settles when the slot-clear set is exactly it, needs confirmation while
  // no slot outside it cleared, and §2.2: a slot cleared outside it contradicts it.
  q = p.get(0);
  t = !q.containsAll(i);
  r = q.equals(i) ? Bh.SETTLE : t ? Bh.NONE : Bh.CONFIRM;
 }
 var /* records */v = new ArrayList<Aa>();
 for (int /* index */w : q == null || t ? Collections.<Integer>emptyList() : q) {
  v.add(b.get(w));
 }
 return new Attribution(r, v, f, t);
}

/**
* Subset enumeration over candidates with a known execution: a subset matches when the cash
* lies within its summed {lowest, highest} after-tax cash. Saturating sums, deterministic index
* order, at most {@code limit} matches. Returns empty when the candidate bound is exceeded,
* so an over-full interaction can never be enumerated.
*/
static List<List<Integer>> aan(long[][] /* nets */a, long /* cashGp */c, int /* limit */d) {
 int /* size */e = a == null ? 0 : a.length;
 var /* matches */f = new ArrayList<List<Integer>>();
 if (e <= 0 || e > MAX_SETTLEMENT_CANDIDATES) {
  return f;
 }
 for (int /* mask */g = 1; g < 1 << e && f.size() < d; g++) {
  long /* least */h = 0L;
  long /* most */k = 0L;
  var /* subset */i = new ArrayList<Integer>();
  for (int /* index */j = 0; j < e && h >= 0L; j++) {
   if ((g & (1 << j)) != 0) {
    i.add(j);
    h = a[j] == null ? -1L : safeAdd(h, a[j][0]);
    k = a[j] == null ? k : safeAdd(k, a[j][1]);
   }
  }
  if (h >= 0L && h <= c && c <= k) f.add(i);
 }
 return f;
}

/** Attribution outcome kinds; names are conceptual, not persisted. */
enum Bh {
 /** An exact subset is proven and may be settled now. */
 SETTLE,
 /** The unique exact subset needs slot confirmation (or the window end). */
 CONFIRM,
 /** More than one exact subset matches; slot evidence must select one. */
 TIE,
 /** No exact attribution exists. */
 NONE
}

@AllArgsConstructor
static class Attribution {
 Bh kind;
 List<Aa> records;
 List<Aa> quarantinable;
 boolean contradicted;
}

/** True while an observed sell lifecycle can still receive execution/terminal evidence. */
boolean uz() {
 for (Aa /* record */a : records) {
  if (a != null && a.getSide() == SELL && a.getCapturedQty() > 0L && !a.wx()
  && !a.xs() && !a.cleared) {
   return true;
  }
 }
 return false;
}

/**
* Retry a held collect under the one attribution law. An exact subset settles as soon as the
* evidence (or the SLOT WINDOW) confirms it; a tie, a contradiction, or exhausted evidence
* fails closed to Review, never distributed by guesswork.
*/
void aee(long /* now */b, String /* sessionId */c, List<Ac> /* counted */d) {
 if (pendingSettlementCashGp <= 0L) return;
 // The held cash IS a collect interaction, so the retry sees the same candidate set that
 // interaction saw (still-active sells whose fills may still be collected included).
 Attribution /* attribution */e = attribute(aii(true), pendingSettlementCashGp,
 pendingSettlementHeldSinceEpochMillis);
 boolean /* ane */f = b - pendingSettlementHeldSinceEpochMillis > SLOT_WINDOW_MILLIS;
 // §2.2b: a unique match settles at the window end with no contradicting slot evidence; the
 // existing long hold also books as soon as its missing execution evidence completes.
 if (e.kind == Bh.SETTLE || e.kind == Bh.CONFIRM
 && (f || pendingSettlementAwaitingEvidence)) {
  ahz(e.records, pendingSettlementCashGp, b, c, d);
  ox();
  return;
 }
 // §2.2 contradiction, §2.3 an unresolved tie, and §2.4/§3 exhausted evidence fail closed:
 // only slot-evidenced candidates are quarantined and the held cash goes to Review.
 if (e.contradicted || b - pendingSettlementAtEpochMillis > UNOBSERVED_GRACE_MILLIS
 || e.kind == Bh.TIE && f || e.kind == Bh.NONE
 && (pendingSettlementAwaitingEvidence ? !uz() : f)) {
  adt(e.quarantinable, b, c, d);
  aex(b, d);
 }
}

/** Fail closed: the observed cash stays visible as an uncounted Review row, never guessed. */
void aex(long /* now */a, List<Ac> /* counted */b) {
 long /* held */c = pendingSettlementCashGp;
 ox();
 if (c <= 0L) return;
 b.add(new Ac(a, null, UNCERTAIN, MARKET,
 "Grand Exchange", "Market", false, singletonList(coins(c, a)), Bd.UNCERTAIN,
 msg("gc"), null));
}

void mq(Aa /* record */b, long /* settleQty */c, long /* cashValue */d,
long /* execution */e, long /* now */f, String /* sessionId */g, boolean /* clean */h, List<Ac> /* counted */i) {
 long /* remainingQty */j = b.afs();
 long /* remainingBasis */k = b.afr();
 long /* knownQty */l = min(c, j);
 long /* knownBasis */m = Df.ayb(j, k, l);
 long /* aou */n = Df.yl(d, c, l);
 boolean /* aly */o = l <= 0L;
 b.setConsumedTrackedQty(safeAdd(b.getConsumedTrackedQty(), l));
 b.setConsumedTrackedBasisGp(safeAdd(b.getConsumedTrackedBasisGp(), m));
 if (basisLedger != null) {
  // The realized quantity left ownership even when it carried no known coverage; the
  // per-item realization fence always advances.
  basisLedger.qd(b.itemId, l, m, f);
 }
 // The one shared accepted-tax predicate: this settlement's observed gross execution against
 // its actual Received cash. Only an exact per-item match counts; the tax is never inferred
 // from a reference, a guide price or a difference.
 long /* provenTax */p = acz(b);
 long /* arc */q = o && h && p > 0L && e - d == p ? p : 0L;
 boolean /* knownCostOnly */r = q > 0L;
 var /* flows */t = new ArrayList<Ab>(2);
 if (r) {
  // Fully unknown prior value with a PROVEN tax: the departing item flow is valued at
  // the observed gross execution (not at the frozen reference) against the observed
  // Received cash, so the counted automatic Net is exactly -accepted tax — a market
  // trade cost. Result stays unknown: the accumulator below receives no result here, and
  // a later correction replaces this contribution instead of stacking on it.
  long /* unit */u = e / c;
  t.add(new Ab(b.itemId, b.getItemName(), -c,
  toInt(u), -e, adi(b.getBasisSource()), b.getBasisCapturedAtEpochMillis()));
  t.add(coins(d, f));
 } else if (o) {
  // Unknown coverage without a proven tax: the full observed movement stays as
  // liquidation evidence and is Net-neutral. It is never a fake cost and never
  // automatic profit.
  t.add(new Ab(b.itemId, b.getItemName(), -c,
  toInt(b.getBasisUnitPrice()), -b.lo(c),
  adi(b.getBasisSource()), b.getBasisCapturedAtEpochMillis()));
  t.add(coins(d, f));
 } else {
  if (m > 0L) {
   long /* unit */v = l > 0L ? m / l : 0L;
   t.add(new Ab(b.itemId, b.getItemName(), -c,
   toInt(v), -m, adi(b.getBasisSource()), b.getBasisCapturedAtEpochMillis()));
  }
  t.add(coins(n, f));
 }
 String /* explanation */w = r ? msg("gj") : o ? msg("gh") : l < c ? msg("gi")
 : msg("gg");
 // Counted when the lifecycle is exact AND either the realized quantity carries known coverage
 // or the actual GE tax is proven. An unknown sale without a proven tax stays an exact,
 // uncounted evidence row (liquidation cash retained, Net-neutral, never a Review nag).
 Ac /* transaction */x = mz(t, f, h, h && (!o || r), w);
 i.add(x);
 if (p >= 0L) b.settledTaxGp = nonNeg(safeAdd(b.getSettledTaxGp(), p));
 b.setSettledQty(safeAdd(b.getSettledQty(), c));
 b.setSettledExecutionGp(safeAdd(b.getSettledExecutionGp(), e));
 b.setSettledCashGp(safeAdd(b.settledCashGp, d));
 b.setRealizedResultGp(safeAdd(b.realizedResultGp, n - m));
 b.setSettlementId(x.getId());
}

/** Return an unconsumed reservation share when custody returns or retires a lifecycle. */
void aez(Aa /* record */a, long /* returnedQty */b) {
 if (a == null || b <= 0L) return;
 long /* remainingQty */c = a.afs();
 long /* remainingBasis */d = a.afr();
 if (c <= 0L && d <= 0L) return;
 long /* releaseQty */e = min(b, c);
 long /* releaseBasis */f = Df.ayb(c, d, e);
 if (basisLedger != null && (e > 0L || f > 0L)) {
  basisLedger.release(a.itemId, e, f);
 }
 a.reservedTrackedQty = nonNeg(max(a.getConsumedTrackedQty(), a.getReservedTrackedQty() - e));
 a.reservedTrackedBasisGp = nonNeg(max(a.getConsumedTrackedBasisGp(),
 a.getReservedTrackedBasisGp() - f));
}

/** Release every still-unrealized reserved share when a lifecycle leaves custody state. */
void aey(Aa /* record */a) {
 if (a == null) return;
 aez(a, max(a.afs(), a.afr()));
}

// ---------------------------------------------------------------------------------------
// Builders
// ---------------------------------------------------------------------------------------
/** One Market row: exact lifecycles book as trades, anything else stays uncounted for review. */
Ac mz(List<Ab> /* flows */a, long /* now */b, boolean /* exact */c, boolean /* counted */d, String /* explanation */e) {
 return new Ac(b, null, c ? TRADE : UNCERTAIN, MARKET, "Grand Exchange", "Market", d, a,
 c ? Bd.CONFIRMED : Bd.UNCERTAIN,
 c ? e : e + msg("js") + msg("bw"), null);
}

boolean vu(Aa /* record */b, String /* sessionId */c) {
 if (b.wx() || b.getConfidence() == Confidence.AMBIGUOUS || b.quantityModified
 || b.collectionAmbiguous || !b.vf()) {
  return false;
 }
 if (b.getOriginSessionId().isEmpty() && c != null && !c.isEmpty()) {
  b.originSessionId = Ag.axw(c);
 }
 // Owner policy (2026-09-24, replaces PRE-R5B 11.19): a settlement books in the session
 // where it is collected; the listing session is kept for the "Listed during" label only
 // and is never rewritten.
 return true;
}

/**
* Accumulate one observed fill. A SELL chunk's tax is exact only when its own per-item price
* is proven (spent divisible by quantity, or an exempt item); otherwise per-fill tax is lost
* for this lifecycle and the uniform rule over the total remains the only proof.
*/
static void afi(Aa /* record */a, long /* quantity */b, long /* spent */c) {
 a.setFilledQty(safeAdd(a.getFilledQty(), b));
 a.setSpentGp(safeAdd(a.getSpentGp(), c));
 if (a.getSide() != SELL || !a.fillTaxExact || b <= 0L) return;
 long /* chunkTax */d = GeTaxRule.adn(a.itemId, c, b);
 if (d < 0L) {
  a.setFillTaxExact(false);
  return;
 }
 a.fillTaxGp = nonNeg(safeAdd(a.getFillTaxGp(), d));
}

/**
* The exact GE tax of a SELL's pending (filled, unsettled) execution, or
* {@link GeTaxRule#UNPROVABLE_TAX}. Per-fill tax wins when every fill was observed at a
* provable price, since fills at different prices pay different per-item tax; otherwise the
* uniform rule over the pending total applies.
*/
static long acz(Aa /* record */a) {
 long /* execution */b = a.acx();
 long /* quantity */c = a.ada();
 if (a.fillTaxExact && b > 0L && c > 0L) {
  long /* tax */d = a.getFillTaxGp() - a.getSettledTaxGp();
  return d >= 0L && d <= b ? d : GeTaxRule.UNPROVABLE_TAX;
 }
 return GeTaxRule.adn(a.itemId, b, c);
}

// ---------------------------------------------------------------------------------------
// Persistence / lifecycle
// ---------------------------------------------------------------------------------------
synchronized void ayc(SavedState /* state */a) {
 if (a != null) a.setGeCustody(new ArrayList<>(records));
}

synchronized void restore(SavedState /* state */a, long /* now */b) {
 reset();
 if (a == null) return;
 // One live lifecycle per slot (its newest record); settled CLOSED history is kept as well.
 var /* newestBySlot */c = new HashMap<Integer, Aa>();
 var /* closedByOffer */d = new LinkedHashMap<String, Aa>();
 for (Aa /* record */e : a.getGeCustody()) {
  if (e == null || e.getOfferId().isEmpty() || e.getStage() == CLOSED_UNOBSERVED) {
   continue;
  }
  if (e.getStage() == CLOSED) d.put(e.getOfferId(), e);
  Aa /* existing */f = c.get(e.slot);
  if (f == null || e.getPlacedAtEpochMillis() >= f.getPlacedAtEpochMillis()) {
   c.put(e.slot, e);
  }
 }
 for (Aa /* record */g : c.values()) d.remove(g.getOfferId());
 records.addAll(c.values());
 records.addAll(d.values());
 records.sort((/* left */h, /* right */i) -> Long.compare(h.getPlacedAtEpochMillis(), i.getPlacedAtEpochMillis()));
 for (Aa /* record */j : records) {
  if (j.getConfidence() == Confidence.CONFIRMED) j.setConfidence(Confidence.RESUMED);
 }
}

/** Destructive reset: custody state is financial continuity state and clears with it. */
synchronized void reset() {
 records.clear();
 baselines.clear();
 replacedSlots.clear();
 explainedAt.clear();
 loginClearedAtEpochMillis.clear();
 ox();
}

/** Adopt another ledger's durable records during a staged profile swap. */
synchronized void auc(GeCustodyLedger /* other */a) {
 reset();
 if (a != null) records.addAll(a.records);
}

synchronized List<Aa> aji() {
 return unmodifiableList(new ArrayList<>(records));
}

/** Drop any transient held collect and slot-clear evidence; durable lifecycles are untouched. */
synchronized void ox() {
 pendingSettlementCashGp = 0L;
 pendingSettlementAtEpochMillis = 0L;
 pendingSettlementHeldSinceEpochMillis = 0L;
 pendingSettlementAwaitingEvidence = false;
 slotClearedAtEpochMillis.clear();
}

// ---------------------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------------------
List<Aa> aeo() {
 var /* sorted */a = new ArrayList<Aa>(records);
 a.sort((/* left */b, /* right */c) -> {
  int /* avp */d = Long.compare(b.getPlacedAtEpochMillis(), c.getPlacedAtEpochMillis());
  return d != 0 ? d : Integer.compare(b.slot, c.slot);
 });
 return a;
}

/** The slot's live lifecycle; settled (CLOSED) history never owns a slot. */
Aa aem(int /* slot */a) {
 for (Aa /* record */b : records) {
  if (b.slot == a && b.getStage() != CLOSED) return b;
 }
 return null;
}

Aa yg(int /* slot */a) {
 Aa /* latest */b = null;
 for (Aa /* record */c : records) {
  if (c.slot == a && c.getStage() == CLOSED
  && (b == null || c.getPlacedAtEpochMillis() >= b.getPlacedAtEpochMillis())) {
   b = c;
  }
 }
 return b;
}

Aa acl() {
 Aa /* oldest */a = null;
 for (Aa /* record */b : records) {
  if (b != null && b.getStage() == CLOSED
  && (a == null || b.getPlacedAtEpochMillis() < a.getPlacedAtEpochMillis())) {
   a = b;
  }
 }
 return a;
}

/**
* The slot's live lifecycle for an offer (any side or item when unknown), falling back to its
* latest settled history; marks it seen.
*/
Aa touch(int /* slot */a, Snapshot /* offer */b, long /* now */c) {
 Side /* side */d = b == null ? null : Bj.sideOf(b.state);
 int /* itemId */e = b == null ? 0 : b.itemId;
 Aa /* match */f = null;
 for (Aa /* record */g : records) {
  if (g.slot != a || d != null && d != g.getSide() || e > 0 && g.itemId != e) {
   continue;
  }
  if (g.getStage() != CLOSED) {
   f = g;
   break;
  }
  if (f == null || g.getPlacedAtEpochMillis() >= f.getPlacedAtEpochMillis()) f = g;
 }
 if (f != null) f.setLastSeenAtEpochMillis(c);
 return f;
}

boolean xo(Aa /* record */a, Side /* side */b, int /* itemId */c, long /* totalQty */d, long /* price */e) {
 return a.getSide() == b && a.itemId == c
 && a.getOfferedQty() == d && a.getListedPrice() == e;
}

/**
* The slot cleared but its cash or items never reached the inventory: they went to the bank.
* The offer itself proves what filled and what it cost, so it settles as if collected. A buy is
* exact; a sale pays its proven tax or, filled at several prices, the highest tax it could have
* paid, so its result is never overstated. What never sold, or was never spent, went too.
* False leaves an untrusted or unobserved lifecycle to the Review handoff.
*/
boolean banked(Aa /* record */a, long /* now */b, String /* sessionId */c, List<Ac> /* counted */d) {
 boolean /* sell */e = a.getSide() == SELL;
 if (!e && a.getBasisSource().isEmpty() && a.ada() > 0L) {
  // A bought item never seen has no market quote: its own execution is its basis.
  a.setBasisUnitPrice(a.acx() / a.ada());
  a.setBasisSource(Av.GRAND_EXCHANGE.name());
  a.setBasisCapturedAtEpochMillis(b);
 }
 if (e && a.getCapturedQty() <= 0L || !vu(a, c)) return false;
 if (e) {
  long[] /* net */f = net(a);
  if (f != null) {
   boolean /* estimated */g = acz(a) < 0L;
   if (g) {
    a.fillTaxExact = true;
    a.fillTaxGp = safeAdd(a.getSettledTaxGp(), a.acx() - f[0]);
   }
   mq(a, a.ada(), f[0], a.acx(), b, c, true, d);
   if (g) d.get(d.size() - 1).explanation += msg("gbank");
  }
  long /* unsold */h = a.aib();
  aez(a, h);
  a.setReturnedQty(safeAdd(a.getReturnedQty(), h));
 } else {
  a.collectedQty = nonNeg(a.getFilledQty());
  mi(a, b, c, d);
  a.refundedGp = nonNeg(safeAdd(a.getRefundedGp(), a.nd()));
 }
 if (a.wo()) a.setStage(CLOSED);
 return true;
}

void ut(Aa /* record */a, long /* now */b, String /* sessionId */c, String /* reason */d, List<Ac> /* handoffs */e) {
 aey(a);
 Ac /* review */f = nf(a, b, d);
 if (f != null) e.add(f);
 records.remove(a);
}

Ac nf(Aa /* record */a, long /* now */b, String /* reason */c) {
 if (a.wx()) return null;
 boolean /* buy */d = a.getSide() == BUY;
 if (!d && a.getCapturedQty() <= 0L) {
  // The principal was never observed entering custody; its movements follow the
  // ordinary classification path and must not be surfaced twice.
  return null;
 }
 long /* unsettledQty */e = d ? a.ada() : a.ahm();
 long /* execution */f = a.acx();
 if (e <= 0L && f <= 0L) return null;
 var /* flows */g = new ArrayList<Ab>(2);
 long /* unit */h = a.getBasisUnitPrice();
 Av /* source */i = adi(a.getBasisSource());
 if (e > 0L) {
  long /* value */j = d && a.collectedValueGp != 0L ? Ae.abs(a.collectedValueGp) : a.lo(e);
  g.add(new Ab(a.itemId, a.getItemName(),
  d ? e : -e, toInt(h), d ? j : -j, i, a.getBasisCapturedAtEpochMillis()));
 }
 if (f > 0L) g.add(coins(d ? -f : f, b));
 return new Ac(b, null, UNCERTAIN, MARKET,
 "Grand Exchange", "Market", false, g, Bd.UNCERTAIN, msg("jt") + c + msg("ju"), null);
}

void ts(Aa /* record */a, Ab /* flow */b, long /* now */c) {
 Av /* source */d = b.getPriceSource();
 a.setBasisUnitPrice(nonNeg(b.unitPrice));
 a.setBasisSource(d == null ? UNKNOWN.name() : d.name());
 a.setBasisCapturedAtEpochMillis(c);
}

/** Presentation/audit source name; never used for matching. */
static Av adi(String /* sourceName */a) {
 for (Av /* source */b : Av.values()) {
  if (b.name().equals(a)) return b;
 }
 return UNKNOWN;
}
}
