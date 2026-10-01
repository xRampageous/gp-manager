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
final Map<Integer, Snapshot> baselines = new HashMap<>();
final List<Aa> records = new ArrayList<>();
/** Slots whose identity was replaced mid-life; the next placement is quarantined. */
final Set<Integer> replacedSlots = new HashSet<>();
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
final Map<String, Long> slotClearedAtEpochMillis = new HashMap<>();
/**
* Transient: live lifecycles the login seed saw as an EMPTY slot. RuneLite reports every slot
* EMPTY at login before the server restores the offers, so the same offer arriving shortly
* after is a late replay of it, never a new offer reusing the slot.
*/
final Map<String, Long> loginClearedAtEpochMillis = new HashMap<>();
static final long LOGIN_REPLAY_MILLIS = 2L * 60L * 1000L;
/**
* Transient: when each offer last explained a movement (its offer id, with a "$" suffix for the
* coin side). A placement explains one loss; a fill or cancellation explains nothing more until
* the offer is seen again.
*/
final Map<String, Long> explainedAt = new HashMap<>();
/** Schema-106 pooled known-basis ledger; null keeps custody purely observational. */
TrackedBasisLedger basisLedger;
/** Wire the pooled known-basis ledger that owns SELL reservations and BUY coverage. */
synchronized void setBasisLedger(TrackedBasisLedger ledger) {
 basisLedger = ledger;
}

/** Latest capture time of an open SELL reservation for the item; 0 when none is open. */
synchronized long yh(int itemId) {
 int canonicalId = itemId;
 long latest = 0L;
 for (Aa record : records) {
  if (record == null || record.getSide() != SELL || record.itemId != canonicalId) {
   continue;
  }
  if (record.afs() <= 0L && record.afr() <= 0L) continue;
  long capturedAt = record.getBasisCapturedAtEpochMillis() > 0L
  ? record.getBasisCapturedAtEpochMillis() : record.getPlacedAtEpochMillis();
  latest = max(latest, capturedAt);
 }
 return latest;
}

/** Immutable result of one settle: residual flows still needing normal classification. */
@AllArgsConstructor
static class Partition {
 final List<Ab> residualFlows;
 final List<Ac> countedBookings;
 final List<Ac> transferBookings;
 /** Physical flows consumed by custody claims; used to spend matching offer evidence. */
 final List<Ab> claimedFlows;
 Ac lastBooking() {
  List<Ac> bookings = countedBookings.isEmpty() ? transferBookings : countedBookings;
  return bookings.isEmpty() ? null : bookings.get(bookings.size() - 1);
 }
}

// ---------------------------------------------------------------------------------------
// Lifecycle observation
// ---------------------------------------------------------------------------------------
/** Apply one offer-ledger transition; returns any Review handoff or settled rows it forced. */
synchronized List<Ac> ack(Bj.Transition transition,
String itemName, long now, String sessionId) {
 if (transition == null || transition.current == null) return Collections.emptyList();
 var handoffs = new ArrayList<Ac>();
 observe(transition.previous, transition.current, itemName, now, sessionId, handoffs);
 // New execution/lifecycle evidence may complete a settlement whose cash was observed
 // first; the held batch settles as soon as it is exactly attributable.
 aee(now, sessionId, handoffs);
 return handoffs;
}

/**
* Applies one slot snapshot against the slot's baseline: exact incremental fills from the
* cumulative counters, Modify Offer, slot reuse and identity replacement. Backwards counters,
* value moved without quantity, or a missed placement are a desync, never a fill.
*/
void observe(Snapshot previous, Snapshot current, String itemName,
long now, String sessionId, List<Ac> handoffs) {
 int slot = current.slot;
 Side side = Bj.sideOf(current.state);
 Snapshot base = baselines.remove(slot);
 if (side == null) {
  Aa record = touch(slot, base != null ? base : previous, now);
  if (record != null) {
   record.setCleared(true);
   // Transient slot evidence: proves which offer a nearby collect belongs to.
   slotClearedAtEpochMillis.put(record.getOfferId(), now);
  }
  return;
 }
 Aa replay = base == null ? ym(slot, side, current, now) : null;
 if (replay != null) {
  loginClearedAtEpochMillis.remove(replay.getOfferId());
  slotClearedAtEpochMillis.remove(replay.getOfferId());
  replay.setCleared(false);
  resume(replay, current, now);
  baselines.put(slot, current);
  return;
 }
 boolean active = Bj.isActive(current.state);
 if (base == null || active && (Bj.sideOf(base.state) != side || base.itemId != current.itemId)) {
  if (base != null) {
   // Item/side changed mid-life without Modify evidence: retire the old lifecycle and
   // quarantine the next; never inherit prior basis or progress.
   Aa old = touch(slot, base, now);
   if (old != null) {
    old.setConfidence(Confidence.AMBIGUOUS);
    ut(old, now, sessionId, msg("ab"), handoffs);
   }
   replacedSlots.add(slot);
  }
  baselines.put(slot, current);
  if (base == null && previous != null && previous.state != GrandExchangeOfferState.EMPTY) {
   // A missed placement cannot be dated: quarantine the lifecycle, emit no fill.
   desync(slot, current, current, itemName, now, sessionId, handoffs);
   return;
  }
  place(slot, side, current, itemName, now, sessionId, handoffs);
  advance(slot, current, current, current.quantityTraded, current.spent, now);
  return;
 }
 if (active && (base.totalQuantity != current.totalQuantity || base.price != current.price)) {
  // A price-only Modify keeps the lifecycle and its basis; a quantity change has no exact
  // per-slice basis allocation, so realization fails closed to Review.
  boolean priceOnly = base.totalQuantity == current.totalQuantity;
  baselines.put(slot, new Snapshot(slot, base.state, base.itemId,
  current.totalQuantity, base.quantityTraded, priceOnly ? current.price : base.price, base.spent));
  Aa record = touch(slot, base, now);
  if (record != null && priceOnly) {
   record.listedPrice = nonNeg(current.price);
  } else if (record != null) {
   record.offeredQty = nonNeg(current.totalQuantity);
   record.setQuantityModified(true);
  }
  return;
 }
 long quantity = current.quantityTraded - base.quantityTraded;
 long spent = current.spent - base.spent;
 baselines.put(slot, new Snapshot(slot, base.state, base.itemId, base.totalQuantity,
 current.quantityTraded, base.price, current.spent));
 if (quantity < 0L || spent < 0L || quantity == 0L && spent > 0L) {
  desync(slot, base, current, itemName, now, sessionId, handoffs);
  return;
 }
 advance(slot, base, current, quantity, spent, now);
}

/** A new offer in the slot: a duplicate replay of the live lifecycle changes nothing. */
void place(int slot, Side side, Snapshot offer, String itemName, long now,
String sessionId, List<Ac> handoffs) {
 Aa previous = aem(slot);
 if (previous != null && xo(previous, side, offer.itemId, offer.totalQuantity,
 offer.price) && !previous.cleared && !previous.xs()) {
  previous.setLastSeenAtEpochMillis(now);
  return;
 }
 if (previous != null && previous.getStage() == OPEN && previous.wo()) {
  // Settled but not yet sealed (a settlement completed by an offer transition):
  // seal it exactly as the next settle would, so it stays as history.
  previous.setStage(CLOSED);
  previous = null;
 }
 if (previous != null) {
  // Slot reuse: a new offer never inherits prior basis or progress.
  ut(previous, now, sessionId, msg("cc"), handoffs);
 }
 // A settled (CLOSED) lifecycle in this slot stays as bounded market history for its
 // booked sale; only its unrealized reservation leaves with the slot.
 aey(yg(slot));
 Aa record = auu(slot, side, offer.itemId, itemName, offer.totalQuantity,
 offer.price, offer.state.name(), now, sessionId);
 if (replacedSlots.remove(slot)) record.setConfidence(Confidence.AMBIGUOUS);
 // Per-fill tax is exact only for a placement observed live from an empty slot.
 record.setFillTaxExact(side == SELL && record.getConfidence() == Confidence.CONFIRMED);
 records.add(record);
 rj(now, sessionId, handoffs);
}

/** Exact fill progress, then the terminal state (a final fill lands before a cancellation). */
void advance(int slot, Snapshot offer, Snapshot current, long quantity, long spent, long now) {
 boolean fill = quantity > 0L && spent >= 0L;
 boolean terminal = Bj.isTerminal(current.state);
 Aa record = fill || terminal ? touch(slot, offer, now) : null;
 if (record != null && fill) afi(record, quantity, spent);
 if (record != null && terminal) record.setOfferState(current.state.name());
}

/** Counters with no exact lineage: quarantine the lifecycle, or start an unbased one. */
void desync(int slot, Snapshot offer, Snapshot current, String itemName,
long now, String sessionId, List<Ac> handoffs) {
 Aa record = touch(slot, offer, now);
 if (record != null) {
  record.setConfidence(Confidence.AMBIGUOUS);
  record.setFillTaxExact(false);
 } else if (offer.itemId > 0) {
  record = auu(slot, Bj.sideOf(offer.state), offer.itemId, itemName,
  offer.totalQuantity, offer.price, current.state.name(), now, sessionId);
  record.setConfidence(Confidence.LEGACY_UNBASED);
  records.add(record);
 }
 rj(now, sessionId, handoffs);
}

/** The live lifecycle the login seed cleared in this slot, when this snapshot is that same offer. */
Aa ym(int slot, Side side, Snapshot current, long now) {
 Aa record = aem(slot);
 Long clearedAt = record == null ? null : loginClearedAtEpochMillis.get(record.getOfferId());
 return clearedAt != null && now - clearedAt <= LOGIN_REPLAY_MILLIS && xo(record, side,
 current.itemId, current.totalQuantity, current.price) ? record : null;
}

/** Resume a persisted lifecycle; progress made while away is adopted once, never as a fill. */
void resume(Aa existing, Snapshot snapshot, long now) {
 existing.setConfidence(Confidence.RESUMED);
 existing.setLastSeenAtEpochMillis(now);
 existing.setOfferState(snapshot.state.name());
 if (snapshot.quantityTraded != existing.getFilledQty()) {
  // Fills while logged out were never observed at their own prices.
  existing.setFillTaxExact(false);
 }
 existing.setFilledQty(snapshot.quantityTraded);
 existing.setSpentGp(nonNeg(snapshot.spent));
}

Aa auu(int slot, Side side, int itemId, String itemName, long totalQty,
long price, String state, long now, String sessionId) {
 var record = new Aa(UUID.randomUUID().toString(), slot, side, itemId,
 itemName, totalQty, price, now, Ag.axw(sessionId));
 record.setLastSeenAtEpochMillis(now);
 record.setOfferState(state);
 return record;
}

/**
* Record the login slot baseline: resume a persisted matching lifecycle, quarantine active
* offers first seen now as legacy/unbased, and hand off records whose slot changed identity.
*/
synchronized List<Ac> avh(Map<Integer, Snapshot> snapshots, long now, String sessionId) {
 var handoffs = new ArrayList<Ac>();
 baselines.clear();
 loginClearedAtEpochMillis.clear();
 if (snapshots != null) {
  for (Snapshot snapshot : snapshots.values()) {
   if (snapshot == null) continue;
   Aa existing = aem(snapshot.slot);
   if (snapshot.state == GrandExchangeOfferState.EMPTY) {
    if (existing != null) loginClearedAtEpochMillis.put(existing.getOfferId(), now);
    else existing = yg(snapshot.slot);
    if (existing != null) {
     existing.setCleared(true);
     existing.setLastSeenAtEpochMillis(now);
     slotClearedAtEpochMillis.put(existing.getOfferId(), now);
    }
    continue;
   }
   Side side = Bj.sideOf(snapshot.state);
   if (side == null) continue;
   // Pre-existing progress is only a baseline, never replayed as a fill.
   baselines.put(snapshot.slot, snapshot);
   if (existing != null && xo(existing, side, snapshot.itemId, snapshot.totalQuantity, snapshot.price)) {
    resume(existing, snapshot, now);
    continue;
   }
   if (existing != null) {
    ut(existing, now, sessionId, msg("bi"), handoffs);
   }
   Aa record = auu(snapshot.slot, side, snapshot.itemId, "",
   snapshot.totalQuantity, snapshot.price, snapshot.state.name(), now, sessionId);
   record.setConfidence(Confidence.LEGACY_UNBASED);
   record.setFilledQty(snapshot.quantityTraded);
   record.setSpentGp(nonNeg(snapshot.spent));
   records.add(record);
  }
 }
 rj(now, sessionId, handoffs);
 return handoffs;
}

/**
* True when every moved flow is explained by a recent offer: an item or coins lost shortly after
* a sell or buy was placed, an item or coins gained after a buy or sell filled, or the other
* side's cancellation. Custody is authoritative for GE economics, so a movement it could not
* claim but an offer explains is Grand Exchange activity for Review, never counted.
*/
synchronized boolean explains(List<Ab> flows, long now) {
 boolean moved = false;
 for (Ab flow : flows) {
  if (flow != null && flow.quantityDelta != 0L) {
   moved = true;
   if (explaining(flow, now) == null) return false;
  }
 }
 return moved;
}

/** Spends the offer evidence behind these movements: one offer side explains one movement. */
synchronized void spend(List<Ab> flows, long now) {
 for (Ab flow : flows) {
  String key = flow == null || flow.quantityDelta == 0L ? null : explaining(flow, now);
  if (key != null) explainedAt.put(key, now);
 }
}

String explaining(Ab flow, long now) {
 boolean coins = flow.itemId == ItemID.COINS;
 for (Aa record : records) {
  boolean buy = record.getSide() == BUY;
  String key = record.getOfferId() + (coins ? "$" : "");
  Long spent = explainedAt.get(key);
  long seen = record.getLastSeenAtEpochMillis();
  // Only a live placement moves coins (buy) or items (sell) out of the inventory; a finished
  // fill of the same side or a cancellation of the other brings them back. Passive
  // progress on an open offer explains nothing.
  boolean fits = flow.isCost() ? buy == coins && spent == null && !record.wx() && !record.vq()
  && now - record.getPlacedAtEpochMillis() <= PLACEMENT_WINDOW_MILLIS
  : (spent == null || spent < seen) && now - seen <= COLLECTION_WINDOW_MILLIS
  && (buy != coins ? record.getFilledQty() > 0L && record.xs() : record.vq());
  if (fits && (coins || record.itemId == flow.itemId)) return key;
 }
 return null;
}

// ---------------------------------------------------------------------------------------
// Ab-level custody claims and canonical realization
// ---------------------------------------------------------------------------------------
/** Claim custody flows from one stabilized settle and book exact economics. */
synchronized Partition partition(List<Ab> incoming, long now, String sessionId) {
 return partition(incoming, now, sessionId, itemId -> true, false);
}

/**
* Claim custody flows from one stabilized settle. {@code itemAllowed} excludes items already
* owned by a stronger per-item evidence source (consume/drop/loot) without weakening the
* whole-transaction safety gates. {@code collectionEvidence} is a bounded player Collect
* interaction; without it, collection claims need an observed terminal offer state.
*/
synchronized Partition partition(List<Ab> incoming, long now, String sessionId,
IntPredicate itemAllowed, boolean collectionEvidence) {
 IntPredicate allowed = itemAllowed == null ? itemId -> true : itemAllowed;
 var counted = new ArrayList<Ac>();
 var transfers = new ArrayList<Ac>();
 var residual = new ArrayList<Ab>(incoming == null ? Collections.emptyList() : incoming);
 var claimed = new ArrayList<Ab>();
 // A settlement held from an earlier collect may now be exactly attributable.
 aee(now, sessionId, counted);
 oc(residual, claimed, now, allowed);
 ob(residual, claimed, now);
 oa(residual, claimed);
 oe(residual, claimed, allowed);
 ny(residual, claimed, now, sessionId, counted, allowed, collectionEvidence);
 og(residual, claimed, now, sessionId, counted, collectionEvidence);
 if (!claimed.isEmpty()) {
  transfers.add(new Ac(now, null, Ai.TRANSFER, Aj.TRANSFER,
  msg("db"), "Market", false, new ArrayList<>(claimed), Bd.CONFIRMED, msg("gd"), null));
 }
 // A hold created by this settle retries immediately: a batch whose evidence is complete
 // fails closed at once instead of waiting invisibly for a later event.
 aee(now, sessionId, counted);
 for (Aa open : records) {
  if (open != null && open.getStage() == OPEN && open.wo()) open.setStage(CLOSED);
 }
 return new Partition(residual, counted, transfers, claimed);
}

/** Prune reconciled lifecycles, expire terminal-unobserved ones and cap stale records. */
synchronized Partition maintenance(long now, String sessionId) {
 var counted = new ArrayList<Ac>();
 prune(now, sessionId, counted);
 // A held settlement retries and, once its evidence is exhausted or its bounded
 // lifecycle expires, fails closed to Review with the observed cash preserved.
 aee(now, sessionId, counted);
 return new Partition(Collections.emptyList(), counted, Collections.emptyList(), Collections.emptyList());
}

void prune(long now, String sessionId, List<Ac> reviews) {
 int closed = 0;
 for (Aa record : records) {
  if (record != null && record.getStage() == CLOSED) closed++;
 }
 // Settled history is bounded: the oldest closed lifecycles leave first.
 for (; closed > MAX_CLOSED_RECORDS; closed--) {
  Aa oldest = acl();
  aey(oldest);
  records.remove(oldest);
 }
 for (int index = records.size() - 1; index >= 0; index--) {
  Aa record = records.get(index);
  if (record == null || record.getOfferId().isEmpty() || record.getStage() == CLOSED_UNOBSERVED) {
   aey(record);
   records.remove(index);
   continue;
  }
  if (record.getStage() == CLOSED) continue;
  if (record.getStage() == AMBIGUOUS) {
   ut(record, now, sessionId, msg("bv"), reviews);
   continue;
  }
  if (record.wo()) {
   record.setStage(CLOSED);
   continue;
  }
  // Expire by grace only a slot that is CLEARED and whose cash was never seen (a
  // collect-to-bank and similar). A SELL whose slot still shows the terminal state is
  // still in GE custody: its coins may be collected much later, so it must not expire by
  // time. BUY keeps its existing pattern (reported, not changed).
  boolean cleared = record.cleared;
  boolean buyTerminal = record.getSide() != SELL && record.xs();
  if ((cleared || buyTerminal) && now - record.getLastSeenAtEpochMillis() > UNOBSERVED_GRACE_MILLIS) {
   ut(record, now, sessionId, msg("dc"), reviews);
  }
 }
 slotClearedAtEpochMillis.values().removeIf(at -> now - at > UNOBSERVED_GRACE_MILLIS);
}

void rj(long now, String sessionId, List<Ac> handoffs) {
 List<Aa> open = aeo();
 open.removeIf(record -> record.getStage() == CLOSED);
 for (int index = 0; index < open.size() - MAX_RECORDS; index++) {
  ut(open.get(index), now, sessionId, msg("bo"), handoffs);
 }
}

// -- Custody claims: each settle offers its flows to the lifecycles, oldest placement first --
/** One lifecycle's claim on a flow: the magnitude it takes, or 0 to pass. */
interface ClaimStep {
 long claim(Aa record, Ab flow, long remaining);
}

void nz(List<Ab> residual, Predicate<Ab> which, ClaimStep step) {
 Dv.claim(residual, which, (flow, magnitude) -> {
  long remaining = magnitude;
  for (Aa record : aeo()) {
   if (remaining > 0L) remaining -= nonNeg(step.claim(record, flow, remaining));
  }
  return magnitude - remaining;
 });
}

/** A lifecycle observed from its placement with no identity desync. */
static boolean trusted(Aa record, Side side) {
 return record.getSide() == side && !record.wx() && record.getConfidence() != Confidence.AMBIGUOUS;
}

/** SELL placement principal (item loss). */
void oc(List<Ab> residual, List<Ab> claimed, long now, IntPredicate itemAllowed) {
 nz(residual, flow -> flow.isCost() && flow.itemId != ItemID.COINS && itemAllowed.test(flow.itemId),
 (record, flow, remaining) -> {
  long claim = min(remaining, record.getOfferedQty());
  if (!trusted(record, SELL) || record.itemId != flow.itemId
  || record.vq() || record.cleared || record.getCapturedQty() > 0L
  || now - record.getPlacedAtEpochMillis() > PLACEMENT_WINDOW_MILLIS || claim <= 0L) {
   return 0L;
  }
  ts(record, flow, now);
  if (basisLedger != null) {
   TrackedBasisLedger.Reservation reservation = basisLedger.reserve(record.itemId, claim, now);
   if (!reservation.isEmpty()) {
    record.reservedTrackedQty = nonNeg(safeAdd(record.getReservedTrackedQty(), reservation.trackedQty));
    record.reservedTrackedBasisGp = nonNeg(safeAdd(record.getReservedTrackedBasisGp(), reservation.basisGp));
   }
  }
  record.setCapturedQty(claim);
  record.setLastSeenAtEpochMillis(now);
  claimed.add(flow.part(claim));
  return claim;
 });
}

/** BUY observed reserve (coin loss). */
void ob(List<Ab> residual, List<Ab> claimed, long now) {
 Set<Integer> gainItems = gainQuantities(residual, ANY).keySet();
 nz(residual, flow -> flow.isCost() && flow.itemId == ItemID.COINS, (record, flow, remaining) -> {
  long claim = min(remaining, nonNeg(record.getOfferedQty() * record.getListedPrice()));
  // A same-item acquisition alongside the coin loss is a purchase, not a reserve:
  // leave the mixed movement to the normal classification path.
  // An offer that filled at once still took these coins; only a cancelled one kept none.
  if (!trusted(record, BUY) || record.vq() || record.cleared
  || record.getReserveGp() > 0L || now - record.getPlacedAtEpochMillis() > PLACEMENT_WINDOW_MILLIS
  || gainItems.contains(record.itemId) || claim <= 0L) {
   return 0L;
  }
  record.reserveGp = nonNeg(claim);
  record.setLastSeenAtEpochMillis(now);
  claimed.add(flow.part(claim, 1, FACE_VALUE));
  return claim;
 });
}

/** BUY refund (coin gain). */
void oa(List<Ab> residual, List<Ab> claimed) {
 nz(residual, flow -> flow.isGain() && flow.itemId == ItemID.COINS, (record, flow, remaining) -> {
  long refundRoom = nonNeg(record.getOfferedQty() * record.getListedPrice() - record.getSpentGp())
  - record.getRefundedGp();
  long claim = min(remaining, min(nonNeg(refundRoom), record.nd()));
  // Change is owed once the offer is finished (bought or cancelled) or its slot cleared.
  if (!trusted(record, BUY) || !(record.xs() || record.cleared) || claim <= 0L) {
   return 0L;
  }
  record.refundedGp = nonNeg(safeAdd(record.getRefundedGp(), claim));
  claimed.add(flow.part(claim, 1, FACE_VALUE));
  return claim;
 });
}

/** SELL cancellation return (item gain). */
void oe(List<Ab> residual, List<Ab> claimed, IntPredicate itemAllowed) {
 nz(residual, flow -> flow.isGain() && flow.itemId != ItemID.COINS && itemAllowed.test(flow.itemId),
 (record, flow, remaining) -> {
  long claim = min(remaining, record.aib());
  if (!trusted(record, SELL) || record.itemId != flow.itemId
  || !(record.vq() || record.cleared) || claim <= 0L) {
   return 0L;
  }
  // The returned principal is neutral at its frozen custody basis, never the
  // current live quote; with no frozen basis it stays the observed movement.
  aez(record, claim);
  record.setReturnedQty(safeAdd(record.getReturnedQty(), claim));
  claimed.add(record.getBasisUnitPrice() > 0L
  ? flow.part(claim, record.getBasisUnitPrice(), adi(record.getBasisSource())) : flow.part(claim));
  return claim;
 });
}

/** BUY collection (item gain). */
void ny(List<Ab> residual, List<Ab> claimed, long now,
String sessionId, List<Ac> counted, IntPredicate itemAllowed, boolean collectionEvidence) {
 nz(residual, flow -> flow.isGain() && flow.itemId != ItemID.COINS && itemAllowed.test(flow.itemId),
 (record, flow, remaining) -> {
  long claim = min(remaining, record.ada());
  // Passive progress alone never turns a same-item delta into a collection.
  if (!trusted(record, BUY) || record.itemId != flow.itemId || record.quantityModified || claim <= 0L
  || !(collectionEvidence || record.xs() || record.cleared)) {
   return 0L;
  }
  if (record.getBasisSource().isEmpty()) ts(record, flow, now);
  record.collectedQty = nonNeg(safeAdd(record.getCollectedQty(), claim));
  record.setCollectedValueGp(safeAdd(record.collectedValueGp, Ae.agz(claim, flow.unitPrice)));
  record.setLastSeenAtEpochMillis(now);
  claimed.add(flow.part(claim));
  if (record.getCollectedQty() < record.getFilledQty()) {
   // Partial collection: execution chunks are not attributable; fail closed.
   record.setCollectionAmbiguous(true);
  } else {
   mi(record, now, sessionId, counted);
  }
  return claim;
 });
}

void mi(Aa record, long now, String sessionId, List<Ac> counted) {
 long settleQty = record.getCollectedQty() - record.getSettledQty();
 long execution = record.acx();
 if (settleQty <= 0L) return;
 boolean clean = vu(record, sessionId);
 var flows = new ArrayList<Ab>(2);
 // Exact NEW-model BUY: the asset value equals the actual settled spend, so acquisition is
 // Net-neutral and the acquired quantity becomes known coverage at that exact spend.
 flows.add(new Ab(record.itemId, record.getItemName(), settleQty, toInt(execution / settleQty),
 execution, adi(record.getBasisSource()), record.getBasisCapturedAtEpochMillis()));
 flows.add(coins(-execution, now));
 Ac transaction = mz(flows, now, clean, clean, msg("gb"));
 counted.add(transaction);
 record.setSettledQty(safeAdd(record.getSettledQty(), settleQty));
 record.setSettledExecutionGp(safeAdd(record.getSettledExecutionGp(), execution));
 record.setSettledCashGp(safeAdd(record.settledCashGp, execution));
 record.setSettlementId(transaction.getId());
 record.collectedQty = nonNeg(record.getSettledQty());
 record.setCollectedValueGp(0L);
}

static Ab coins(long amount, long now) {
 return new Ab(ItemID.COINS, "Coins", amount, 1, amount, FACE_VALUE, now);
}

/** Claims a whole coin gain as held or settled GE cash. */
static long oj(Ab flow, List<Ab> claimed) {
 claimed.add(flow.part(flow.valueDelta, 1, FACE_VALUE));
 return flow.valueDelta;
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
void og(List<Ab> residual, List<Ab> claimed, long now,
String sessionId, List<Ac> counted, boolean collectionEvidence) {
 if (!collectionEvidence) {
  // A coin gain with no Collect interaction is not a collect interaction: keep the
  // pre-6A.1A2 booking path byte-for-byte (reported as a remaining exposure).
  od(residual, claimed, now, sessionId, counted);
  return;
 }
 Dv.claim(residual, flow -> flow.isGain() && flow.itemId == ItemID.COINS, (flow, magnitude) -> {
  long cashGp = flow.valueDelta;
  List<Aa> candidates = aii(true);
  if (candidates.isEmpty()) {
   // Coins may arrive before the offer's execution/terminal evidence. A live Collect
   // interaction with a captured sell lifecycle still pending is that settlement's
   // cash; retain it internally instead of letting an unresolved Coins row reach
   // Review. The bounded hold retries as offer evidence arrives.
   if (cashGp <= 0L || !uz()) return 0L;
   agq(cashGp, now);
   pendingSettlementAwaitingEvidence = true;
   return oj(flow, claimed);
  }
  Attribution attribution = attribute(candidates, cashGp, now);
  if (attribution.kind == Bh.SETTLE) {
   ahz(attribution.records, now, sessionId, counted);
   return oj(flow, claimed);
  }
  if (!attribution.contradicted) {
   // Exact-but-unconfirmed, tied, or unmatched evidence: retain the observed cash
   // transiently. The SLOT WINDOW gathers the slot evidence a fail-closed decision
   // needs; while a sell can still receive execution evidence, the existing long hold
   // keeps waiting for it.
   agq(cashGp, now);
   pendingSettlementAwaitingEvidence |= attribution.kind == Bh.NONE && uz();
   return oj(flow, claimed);
  }
  // No exact attribution: only candidates with slot evidence in this interaction may be
  // quarantined. The unattributed movement follows the ordinary path to one uncounted
  // Review Coins row; every other candidate stays pending for a later exact collect.
  adt(attribution.quarantinable, now, sessionId, counted);
  return 0L;
 });
}

/**
* Pre-6A.1A2 booking for a coin gain with no Collect interaction. Kept byte-for-byte so
* ordinary non-collect coin movements keep their existing classification; it is never used
* for a collect interaction.
*/
void od(List<Ab> residual,
List<Ab> claimed, long now, String sessionId, List<Ac> counted) {
 Dv.claim(residual, flow -> flow.isGain() && flow.itemId == ItemID.COINS, (flow, magnitude) -> {
  List<Aa> candidates = aii(false);
  long totalExecution = 0L;
  for (Aa record : candidates) totalExecution = safeAdd(totalExecution, record.acx());
  long original = flow.valueDelta;
  long claimCash = min(original, totalExecution);
  if (claimCash <= 0L) return 0L;
  if (candidates.size() > 1) {
   boolean allClean = true;
   boolean allKnownCoverage = true;
   long expectedTax = 0L;
   for (Aa record : candidates) {
    allClean &= vu(record, sessionId);
    allKnownCoverage &= record.afs() >= record.ada();
    // Candidate fee only: the aggregate observed cash must confirm the exact combined gap.
    expectedTax = safeAdd(expectedTax, nonNeg(acz(record)));
   }
   boolean taxAttributed = expectedTax > 0L && totalExecution - claimCash == expectedTax;
   if (!taxAttributed && (expectedTax > 0L || claimCash != totalExecution) && !allKnownCoverage) {
    allClean = false;
    for (Aa record : candidates) record.setCollectionAmbiguous(true);
   }
   // An exact batch books each offer's after-tax share of the observed cash; any
   // other batch books gross execution and an adjustment row for the difference.
   long allocated = 0L;
   for (Aa record : candidates) {
    long settleQty = record.ada();
    long execution = record.acx();
    if (settleQty > 0L && execution > 0L) {
     long cash = taxAttributed ? execution - nonNeg(acz(record)) : execution;
     mq(record, settleQty, cash, execution, now, sessionId, allClean, counted);
     allocated = safeAdd(allocated, cash);
    }
   }
   if (claimCash != allocated) {
    // Legacy (non-collect) batch adjustment row; collect interactions never produce one.
    counted.add(new Ac(now, null, allClean ? TRADE : UNCERTAIN, MARKET, msg("ak"), "Market", allClean,
    singletonList(coins(claimCash - allocated, now)),
    allClean ? Bd.CONFIRMED : Bd.UNCERTAIN, msg("ga"), null));
   }
  } else {
   Aa record = candidates.get(0);
   long execution = record.acx();
   boolean over = original > execution;
   record.setCollectionAmbiguous(record.collectionAmbiguous || over);
   mq(record, record.ada(), over ? execution : claimCash, execution,
   now, sessionId, !over && vu(record, sessionId), counted);
  }
  claimed.add(flow.part(claimCash, 1, FACE_VALUE));
  return claimCash;
 });
}

/** Book one exactly attributed subset at each offer's own observed after-tax cash. */
void ahz(List<Aa> records, long now, String sessionId, List<Ac> counted) {
 boolean allClean = true;
 for (Aa record : records) allClean &= vu(record, sessionId);
 for (Aa record : records) {
  long settleQty = record.ada();
  long execution = record.acx();
  long tax = acz(record);
  // A negative (unprovable) tax is never part of an exact match; the attribution excluded it.
  if (settleQty > 0L && execution > 0L && tax >= 0L) {
   mq(record, settleQty, execution - tax, execution, now, sessionId, allClean, counted);
  }
 }
}

/**
* Fail closed on the slot-evidenced candidates only. The quarantine booking keeps today's
* shape (uncounted, collectionAmbiguous, the booked allocation recorded) while the observed
* cash itself is never assigned to a lifecycle it was not proven part of.
*/
void adt(List<Aa> quarantinable, long now, String sessionId, List<Ac> counted) {
 for (Aa record : quarantinable) {
  long settleQty = record.ada();
  long execution = record.acx();
  if (settleQty > 0L && execution > 0L) {
   record.setCollectionAmbiguous(true);
   mq(record, settleQty, execution, execution, now, sessionId, false, counted);
  }
 }
}

/** Candidate sell lifecycles for one coin gain; the retry needs durable terminal evidence. */
List<Aa> aii(boolean collectionEvidence) {
 var candidates = new ArrayList<Aa>();
 for (Aa record : aeo()) {
  if (record.getSide() == SELL && record.ada() > 0L && record.getCapturedQty() > 0L
  && record.ahm() >= record.ada() && !record.wx()
  && (collectionEvidence || record.xs() || record.cleared)) {
   candidates.add(record);
  }
 }
 return candidates;
}

/** Retain observed collect cash transiently; the SLOT WINDOW anchors on the first hold. */
void agq(long cashGp, long now) {
 if (cashGp <= 0L) return;
 if (pendingSettlementCashGp <= 0L) pendingSettlementHeldSinceEpochMillis = now;
 pendingSettlementCashGp = safeAdd(pendingSettlementCashGp, cashGp);
 pendingSettlementAtEpochMillis = max(pendingSettlementAtEpochMillis, now);
}

/** Exact attribution of one collect coin movement over the current candidate set. */
Attribution attribute(List<Aa> candidates, long cashGp, long coinAt) {
 int size = candidates.size();
 var quarantinable = new ArrayList<Aa>();
 if (size <= 0 || size > MAX_SETTLEMENT_CANDIDATES) {
  return new Attribution(Bh.NONE, quarantinable, quarantinable, false);
 }
 long[] netGp = new long[size];
 boolean[] provable = new boolean[size];
 var cleared = new ArrayList<Integer>();
 boolean anq = false;
 long alk = 0L;
 for (int index = 0; index < size; index++) {
  Aa record = candidates.get(index);
  long tax = acz(record);
  provable[index] = tax >= 0L;
  netGp[index] = provable[index] ? record.acx() - tax : 0L;
  Long clearedAt = slotClearedAtEpochMillis.get(record.getOfferId());
  // The candidate's slot cleared within the SLOT WINDOW of the coin gain.
  if (clearedAt != null && Math.abs(clearedAt - coinAt) <= SLOT_WINDOW_MILLIS) {
   cleared.add(index);
   quarantinable.add(record);
   alk = safeAdd(alk, netGp[index]);
   anq |= !provable[index];
  }
 }
 // Apply slot evidence only after enumerating every subset within the eight-offer bound.
 List<List<Integer>> matches = aan(netGp, provable, cashGp, 1 << size);
 List<Integer> match = null;
 Bh kind = Bh.NONE;
 boolean contradicted = false;
 if (anq) {
  // §2.4: a slot-evidenced candidate whose tax cannot be proven fails the interaction.
  kind = Bh.NONE;
 } else if (size == 1 && provable[0] && netGp[0] == cashGp) {
  // A single candidate has no competing explanation: its exact observed net proves the
  // movement belongs to it, so no slot confirmation is needed (an inexact amount still
  // fails closed below).
  kind = Bh.SETTLE;
  match = singletonList(0);
 } else if (!cleared.isEmpty() && alk == cashGp) {
  // §2.1: the slot-clear set is the primary proof.
  kind = Bh.SETTLE;
  match = cleared;
 } else if (matches.size() > 1) {
  // §2.3: slot evidence may still select exactly one matching subset.
  matches.removeIf(subset -> !subset.containsAll(cleared));
  contradicted = !cleared.isEmpty() && matches.isEmpty();
  kind = contradicted ? Bh.NONE : matches.size() == 1 ? Bh.SETTLE : Bh.TIE;
  match = matches.size() == 1 ? matches.get(0) : null;
 } else if (matches.size() == 1) {
  // A unique match settles when the slot-clear set is exactly it, needs confirmation while
  // no slot outside it cleared, and §2.2: a slot cleared outside it contradicts it.
  match = matches.get(0);
  contradicted = !match.containsAll(cleared);
  kind = match.equals(cleared) ? Bh.SETTLE : contradicted ? Bh.NONE : Bh.CONFIRM;
 }
 var records = new ArrayList<Aa>();
 for (int index : match == null || contradicted ? Collections.<Integer>emptyList() : match) {
  records.add(candidates.get(index));
 }
 return new Attribution(kind, records, quarantinable, contradicted);
}

/**
* Exact subset enumeration over provable candidates: saturating sums, deterministic index
* order, at most {@code limit} matches. Returns empty when the candidate bound is exceeded,
* so an over-full interaction can never be enumerated.
*/
static List<List<Integer>> aan(long[] netGp, boolean[] provable, long cashGp, int limit) {
 int size = netGp == null ? 0 : netGp.length;
 var matches = new ArrayList<List<Integer>>();
 if (size <= 0 || size > MAX_SETTLEMENT_CANDIDATES || provable == null || provable.length != size) {
  return matches;
 }
 for (int mask = 1; mask < 1 << size && matches.size() < limit; mask++) {
  long sum = 0L;
  var subset = new ArrayList<Integer>();
  for (int index = 0; index < size; index++) {
   if ((mask & (1 << index)) != 0) {
    subset.add(index);
    sum = provable[index] ? safeAdd(sum, netGp[index]) : Long.MIN_VALUE;
    if (!provable[index]) break;
   }
  }
  if (sum == cashGp && sum != Long.MIN_VALUE) matches.add(subset);
 }
 return matches;
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
 final Bh kind;
 final List<Aa> records;
 final List<Aa> quarantinable;
 final boolean contradicted;
}

/** True while an observed sell lifecycle can still receive execution/terminal evidence. */
boolean uz() {
 for (Aa record : records) {
  if (record != null && record.getSide() == SELL && record.getCapturedQty() > 0L && !record.wx()
  && !record.xs() && !record.cleared) {
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
void aee(long now, String sessionId, List<Ac> counted) {
 if (pendingSettlementCashGp <= 0L) return;
 // The held cash IS a collect interaction, so the retry sees the same candidate set that
 // interaction saw (still-active sells whose fills may still be collected included).
 Attribution attribution = attribute(aii(true), pendingSettlementCashGp,
 pendingSettlementHeldSinceEpochMillis);
 boolean ane = now - pendingSettlementHeldSinceEpochMillis > SLOT_WINDOW_MILLIS;
 // §2.2b: a unique match settles at the window end with no contradicting slot evidence; the
 // existing long hold also books as soon as its missing execution evidence completes.
 if (attribution.kind == Bh.SETTLE || attribution.kind == Bh.CONFIRM
 && (ane || pendingSettlementAwaitingEvidence)) {
  ahz(attribution.records, now, sessionId, counted);
  ox();
  return;
 }
 // §2.2 contradiction, §2.3 an unresolved tie, and §2.4/§3 exhausted evidence fail closed:
 // only slot-evidenced candidates are quarantined and the held cash goes to Review.
 if (attribution.contradicted || now - pendingSettlementAtEpochMillis > UNOBSERVED_GRACE_MILLIS
 || attribution.kind == Bh.TIE && ane || attribution.kind == Bh.NONE
 && (pendingSettlementAwaitingEvidence ? !uz() : ane)) {
  adt(attribution.quarantinable, now, sessionId, counted);
  aex(now, counted);
 }
}

/** Fail closed: the observed cash stays visible as an uncounted Review row, never guessed. */
void aex(long now, List<Ac> counted) {
 long held = pendingSettlementCashGp;
 ox();
 if (held <= 0L) return;
 counted.add(new Ac(now, null, UNCERTAIN, MARKET,
 "Grand Exchange", "Market", false, singletonList(coins(held, now)), Bd.UNCERTAIN,
 msg("gc"), null));
}

void mq(Aa record, long settleQty, long cashValue,
long execution, long now, String sessionId, boolean clean, List<Ac> counted) {
 long remainingQty = record.afs();
 long remainingBasis = record.afr();
 long knownQty = min(settleQty, remainingQty);
 long knownBasis = Df.ayb(remainingQty, remainingBasis, knownQty);
 long aou = Df.yl(cashValue, settleQty, knownQty);
 boolean aly = knownQty <= 0L;
 record.setConsumedTrackedQty(safeAdd(record.getConsumedTrackedQty(), knownQty));
 record.setConsumedTrackedBasisGp(safeAdd(record.getConsumedTrackedBasisGp(), knownBasis));
 if (basisLedger != null) {
  // The realized quantity left ownership even when it carried no known coverage; the
  // per-item realization fence always advances.
  basisLedger.qd(record.itemId, knownQty, knownBasis, now);
 }
 // The one shared accepted-tax predicate: this settlement's observed gross execution against
 // its actual Received cash. Only an exact per-item match counts; the tax is never inferred
 // from a reference, a guide price or a difference.
 long provenTax = acz(record);
 long arc = aly && clean && provenTax > 0L && execution - cashValue == provenTax ? provenTax : 0L;
 boolean knownCostOnly = arc > 0L;
 var flows = new ArrayList<Ab>(2);
 if (knownCostOnly) {
  // Fully unknown prior value with a PROVEN tax: the departing item flow is valued at
  // the observed gross execution (not at the frozen reference) against the observed
  // Received cash, so the counted automatic Net is exactly -accepted tax — a market
  // trade cost. Result stays unknown: the accumulator below receives no result here, and
  // a later correction replaces this contribution instead of stacking on it.
  long unit = execution / settleQty;
  flows.add(new Ab(record.itemId, record.getItemName(), -settleQty,
  toInt(unit), -execution, adi(record.getBasisSource()), record.getBasisCapturedAtEpochMillis()));
  flows.add(coins(cashValue, now));
 } else if (aly) {
  // Unknown coverage without a proven tax: the full observed movement stays as
  // liquidation evidence and is Net-neutral. It is never a fake cost and never
  // automatic profit.
  flows.add(new Ab(record.itemId, record.getItemName(), -settleQty,
  toInt(record.getBasisUnitPrice()), -record.lo(settleQty),
  adi(record.getBasisSource()), record.getBasisCapturedAtEpochMillis()));
  flows.add(coins(cashValue, now));
 } else {
  if (knownBasis > 0L) {
   long unit = knownQty > 0L ? knownBasis / knownQty : 0L;
   flows.add(new Ab(record.itemId, record.getItemName(), -settleQty,
   toInt(unit), -knownBasis, adi(record.getBasisSource()), record.getBasisCapturedAtEpochMillis()));
  }
  flows.add(coins(aou, now));
 }
 String explanation = knownCostOnly ? msg("gj") : aly ? msg("gh") : knownQty < settleQty ? msg("gi")
 : msg("gg");
 // Counted when the lifecycle is exact AND either the realized quantity carries known coverage
 // or the actual GE tax is proven. An unknown sale without a proven tax stays an exact,
 // uncounted evidence row (liquidation cash retained, Net-neutral, never a Review nag).
 Ac transaction = mz(flows, now, clean, clean && (!aly || knownCostOnly), explanation);
 counted.add(transaction);
 if (provenTax >= 0L) record.settledTaxGp = nonNeg(safeAdd(record.getSettledTaxGp(), provenTax));
 record.setSettledQty(safeAdd(record.getSettledQty(), settleQty));
 record.setSettledExecutionGp(safeAdd(record.getSettledExecutionGp(), execution));
 record.setSettledCashGp(safeAdd(record.settledCashGp, cashValue));
 record.setRealizedResultGp(safeAdd(record.realizedResultGp, aou - knownBasis));
 record.setSettlementId(transaction.getId());
}

/** Return an unconsumed reservation share when custody returns or retires a lifecycle. */
void aez(Aa record, long returnedQty) {
 if (record == null || returnedQty <= 0L) return;
 long remainingQty = record.afs();
 long remainingBasis = record.afr();
 if (remainingQty <= 0L && remainingBasis <= 0L) return;
 long releaseQty = min(returnedQty, remainingQty);
 long releaseBasis = Df.ayb(remainingQty, remainingBasis, releaseQty);
 if (basisLedger != null && (releaseQty > 0L || releaseBasis > 0L)) {
  basisLedger.release(record.itemId, releaseQty, releaseBasis);
 }
 record.reservedTrackedQty = nonNeg(max(record.getConsumedTrackedQty(), record.getReservedTrackedQty() - releaseQty));
 record.reservedTrackedBasisGp = nonNeg(max(record.getConsumedTrackedBasisGp(),
 record.getReservedTrackedBasisGp() - releaseBasis));
}

/** Release every still-unrealized reserved share when a lifecycle leaves custody state. */
void aey(Aa record) {
 if (record == null) return;
 aez(record, max(record.afs(), record.afr()));
}

// ---------------------------------------------------------------------------------------
// Builders
// ---------------------------------------------------------------------------------------
/** One Market row: exact lifecycles book as trades, anything else stays uncounted for review. */
Ac mz(List<Ab> flows, long now, boolean exact, boolean counted, String explanation) {
 return new Ac(now, null, exact ? TRADE : UNCERTAIN, MARKET, "Grand Exchange", "Market", counted, flows,
 exact ? Bd.CONFIRMED : Bd.UNCERTAIN,
 exact ? explanation : explanation + msg("js") + msg("bw"), null);
}

boolean vu(Aa record, String sessionId) {
 if (record.wx() || record.getConfidence() == Confidence.AMBIGUOUS || record.quantityModified
 || record.collectionAmbiguous || !record.vf()) {
  return false;
 }
 if (record.getOriginSessionId().isEmpty() && sessionId != null && !sessionId.isEmpty()) {
  record.originSessionId = Ag.axw(sessionId);
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
static void afi(Aa record, long quantity, long spent) {
 record.setFilledQty(safeAdd(record.getFilledQty(), quantity));
 record.setSpentGp(safeAdd(record.getSpentGp(), spent));
 if (record.getSide() != SELL || !record.fillTaxExact || quantity <= 0L) return;
 long chunkTax = GeTaxRule.adn(record.itemId, spent, quantity);
 if (chunkTax < 0L) {
  record.setFillTaxExact(false);
  return;
 }
 record.fillTaxGp = nonNeg(safeAdd(record.getFillTaxGp(), chunkTax));
}

/**
* The exact GE tax of a SELL's pending (filled, unsettled) execution, or
* {@link GeTaxRule#UNPROVABLE_TAX}. Per-fill tax wins when every fill was observed at a
* provable price, since fills at different prices pay different per-item tax; otherwise the
* uniform rule over the pending total applies.
*/
static long acz(Aa record) {
 long execution = record.acx();
 long quantity = record.ada();
 if (record.fillTaxExact && execution > 0L && quantity > 0L) {
  long tax = record.getFillTaxGp() - record.getSettledTaxGp();
  return tax >= 0L && tax <= execution ? tax : GeTaxRule.UNPROVABLE_TAX;
 }
 return GeTaxRule.adn(record.itemId, execution, quantity);
}

// ---------------------------------------------------------------------------------------
// Persistence / lifecycle
// ---------------------------------------------------------------------------------------
synchronized void ayc(SavedState state) {
 if (state != null) state.setGeCustody(new ArrayList<>(records));
}

synchronized void restore(SavedState state, long now) {
 reset();
 if (state == null) return;
 // One live lifecycle per slot (its newest record); settled CLOSED history is kept as well.
 var newestBySlot = new HashMap<Integer, Aa>();
 var closedByOffer = new LinkedHashMap<String, Aa>();
 for (Aa record : state.getGeCustody()) {
  if (record == null || record.getOfferId().isEmpty() || record.getStage() == CLOSED_UNOBSERVED) {
   continue;
  }
  if (record.getStage() == CLOSED) closedByOffer.put(record.getOfferId(), record);
  Aa existing = newestBySlot.get(record.slot);
  if (existing == null || record.getPlacedAtEpochMillis() >= existing.getPlacedAtEpochMillis()) {
   newestBySlot.put(record.slot, record);
  }
 }
 for (Aa record : newestBySlot.values()) closedByOffer.remove(record.getOfferId());
 records.addAll(newestBySlot.values());
 records.addAll(closedByOffer.values());
 records.sort((left, right) -> Long.compare(left.getPlacedAtEpochMillis(), right.getPlacedAtEpochMillis()));
 for (Aa record : records) {
  if (record.getConfidence() == Confidence.CONFIRMED) record.setConfidence(Confidence.RESUMED);
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
synchronized void auc(GeCustodyLedger other) {
 reset();
 if (other != null) records.addAll(other.records);
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
 var sorted = new ArrayList<Aa>(records);
 sorted.sort((left, right) -> {
  int avp = Long.compare(left.getPlacedAtEpochMillis(), right.getPlacedAtEpochMillis());
  return avp != 0 ? avp : Integer.compare(left.slot, right.slot);
 });
 return sorted;
}

/** The slot's live lifecycle; settled (CLOSED) history never owns a slot. */
Aa aem(int slot) {
 for (Aa record : records) {
  if (record.slot == slot && record.getStage() != CLOSED) return record;
 }
 return null;
}

Aa yg(int slot) {
 Aa latest = null;
 for (Aa record : records) {
  if (record.slot == slot && record.getStage() == CLOSED
  && (latest == null || record.getPlacedAtEpochMillis() >= latest.getPlacedAtEpochMillis())) {
   latest = record;
  }
 }
 return latest;
}

Aa acl() {
 Aa oldest = null;
 for (Aa record : records) {
  if (record != null && record.getStage() == CLOSED
  && (oldest == null || record.getPlacedAtEpochMillis() < oldest.getPlacedAtEpochMillis())) {
   oldest = record;
  }
 }
 return oldest;
}

/**
* The slot's live lifecycle for an offer (any side or item when unknown), falling back to its
* latest settled history; marks it seen.
*/
Aa touch(int slot, Snapshot offer, long now) {
 Side side = offer == null ? null : Bj.sideOf(offer.state);
 int itemId = offer == null ? 0 : offer.itemId;
 Aa match = null;
 for (Aa record : records) {
  if (record.slot != slot || side != null && side != record.getSide() || itemId > 0 && record.itemId != itemId) {
   continue;
  }
  if (record.getStage() != CLOSED) {
   match = record;
   break;
  }
  if (match == null || record.getPlacedAtEpochMillis() >= match.getPlacedAtEpochMillis()) match = record;
 }
 if (match != null) match.setLastSeenAtEpochMillis(now);
 return match;
}

boolean xo(Aa record, Side side, int itemId, long totalQty, long price) {
 return record.getSide() == side && record.itemId == itemId
 && record.getOfferedQty() == totalQty && record.getListedPrice() == price;
}

void ut(Aa record, long now, String sessionId, String reason, List<Ac> handoffs) {
 aey(record);
 Ac review = nf(record, now, reason);
 if (review != null) handoffs.add(review);
 records.remove(record);
}

Ac nf(Aa record, long now, String reason) {
 if (record.wx()) return null;
 boolean buy = record.getSide() == BUY;
 if (!buy && record.getCapturedQty() <= 0L) {
  // The principal was never observed entering custody; its movements follow the
  // ordinary classification path and must not be surfaced twice.
  return null;
 }
 long unsettledQty = buy ? record.ada() : record.ahm();
 long execution = record.acx();
 if (unsettledQty <= 0L && execution <= 0L) return null;
 var flows = new ArrayList<Ab>(2);
 long unit = record.getBasisUnitPrice();
 Av source = adi(record.getBasisSource());
 if (unsettledQty > 0L) {
  long value = buy && record.collectedValueGp != 0L ? Ae.abs(record.collectedValueGp) : record.lo(unsettledQty);
  flows.add(new Ab(record.itemId, record.getItemName(),
  buy ? unsettledQty : -unsettledQty, toInt(unit), buy ? value : -value, source, record.getBasisCapturedAtEpochMillis()));
 }
 if (execution > 0L) flows.add(coins(buy ? -execution : execution, now));
 return new Ac(now, null, UNCERTAIN, MARKET,
 "Grand Exchange", "Market", false, flows, Bd.UNCERTAIN, msg("jt") + reason + msg("ju"), null);
}

void ts(Aa record, Ab flow, long now) {
 Av source = flow.getPriceSource();
 record.setBasisUnitPrice(nonNeg(flow.unitPrice));
 record.setBasisSource(source == null ? UNKNOWN.name() : source.name());
 record.setBasisCapturedAtEpochMillis(now);
}

/** Presentation/audit source name; never used for matching. */
static Av adi(String sourceName) {
 for (Av source : Av.values()) {
  if (source.name().equals(sourceName)) return source;
 }
 return UNKNOWN;
}
}
