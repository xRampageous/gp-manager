package com.gpmanager;


import static com.gpmanager.Context.*;
import static com.gpmanager.FlowFilters.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.GeOffer.Side.*;
import static com.gpmanager.GeOffer.Stage.*;
import static com.gpmanager.PriceSource.*;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.TransactionType.*;
import static java.lang.Math.*;
import static java.util.Collections.*;

import com.gpmanager.GeOffer.*;
import com.gpmanager.OfferSlots.Snapshot;
import java.util.*;
import java.util.function.*;
import javax.inject.Singleton;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.gameval.ItemID;
import static com.gpmanager.Phrases.*;

/**
 * Durable schema-104 GE custody ledger.
 *
 * <p>Physical placement principal, cancellation returns and refunds are ownership-neutral
 * transfers. A safely realized settlement books one canonical counted Market result exactly once,
 * guarded by the persisted cumulative-execution counters. Unattributable or unbased lifecycles fail
 * closed through the existing Review domain instead of guessing.
 *
 * <p>An observed collect coin movement (a live Collect interaction with its coin gain) is
 * attributed to exactly the sold offers it proves: the offers whose slot cleared within the SLOT
 * WINDOW, or the one subset of observed after-tax nets that sums to it exactly. A
 * unique-but-unconfirmed match is held for that short window and settles only without contradicting
 * slot evidence; a tie, a contradiction or exhausted evidence fails closed, quarantining only
 * slot-evidenced candidates and leaving the rest pending. Collect cash that arrives before the
 * matching sell execution evidence keeps its longer transient hold. Both holds are transient: a
 * restart leaves the durable records to their own unobserved-settlement handoff, never a double
 * booking. A coin gain with no Collect interaction keeps the previous booking path unchanged.
 *
 * <p>The ledger never mutates booked transactions, never re-prices history, and never scans the
 * whole session history: claims are quantity-bounded against the bounded open-record set.
 */
class GeCustodyLedger {
    /** How long a placement still explains the coins or items it took from the inventory. */
    static final long PLACEMENT_WINDOW_MILLIS = 90_000;

    /** How long a terminal lifecycle may await an unobserved settlement before Review handoff. */
    static final long UNOBSERVED_GRACE_MILLIS = 10L * 60L * 1000;

    /** A cleared slot whose cash or items never reached the inventory went to the bank by now. */
    static final long BANK_COLLECT_MILLIS = 30_000;

    /**
     * Slot-clear evidence window: at most 3 game ticks either side of the coin gain. A slot that
     * cleared in this window proves its offer's coins were the ones collected.
     */
    static final long SLOT_WINDOW_MILLIS = 1_800;

    /** One open record per GE slot; more candidates than this can never be enumerated. */
    static final int MAX_SETTLEMENT_CANDIDATES = 8;

    /** How long a fill or cancellation still explains a collected movement. */
    static final long COLLECTION_WINDOW_MILLIS = 2L * 60L * 60 * 1000;

    /** Bounded retention of reconciled lifecycles so recent Market context survives. */
    static final int MAX_CLOSED_RECORDS = 64;

    /**
     * Per-slot baseline of each observed offer; exact fill deltas come from its cumulative
     * counters.
     */
    Map<Integer, Snapshot> baselines = new HashMap<>();

    List<GeOffer> records = new ArrayList<>();

    /** Slots whose identity was replaced mid-life; the next placement is quarantined. */
    Set<Integer> replacedSlots = new HashSet<>();

    /**
     * Aggregate GE cash observed with a live Collect interaction but not yet exactly attributable
     * to every sell lifecycle it may settle. Retained internally (never a visible receipt) until
     * the missing offer evidence arrives and the batch reconciles exactly, or the bounded lifecycle
     * expires and the cash fails closed to Review. Transient by design: a restart without the
     * evidence leaves the records to their own unobserved-settlement handoff, never a double
     * booking.
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

    static final long LOGIN_REPLAY_MILLIS = 2L * 60L * 1000;

    /**
     * Transient: when each offer last explained a movement (its offer id, with a "$" suffix for the
     * coin side). A placement explains one loss; a fill or cancellation explains nothing more until
     * the offer is seen again.
     */
    Map<String, Long> explainedAt = new HashMap<>();

    /** Schema-106 pooled known-basis ledger; null keeps custody purely observational. */
    TrackedBasisLedger basisLedger;

    // The operation in progress: each synchronized entry point sets these before it works.
    /** The operation's time. */
    long now;

    /** The session the operation books into. */
    String sessionId = "";

    /** What the operation books: settlements and Review handoffs. */
    List<Transaction> bookings = new ArrayList<>();

    /** One settle's flows not yet claimed, the parts claimed so far, and the items it may claim. */
    List<Flow> residual = new ArrayList<>();

    List<Flow> claimed = new ArrayList<>();
    IntPredicate allowed = identifier -> true;

    /** Starts an operation at {@code now}; it books into a fresh list. */
    void begin(long now, String id) {
        this.now = now;
        sessionId = id;
        bookings = new ArrayList<>();
    }

    /** Wire the pooled known-basis ledger that owns SELL reservations and BUY coverage. */
    synchronized void setBasisLedger(TrackedBasisLedger ledger) {
        basisLedger = ledger;
    }

    /** Latest capture time of an open SELL reservation for the item; 0 when none is open. */
    synchronized long latestReservationTime(int id) {
        long latest = 0;
        for (var record : records) {
            if (record.side != SELL || record.itemId != id) continue;
            if (record.reservedQty() <= 0 && record.reservedBasis() <= 0) continue;
            long captured = record.basisCapturedAtEpochMillis > 0 ? record.basisCapturedAtEpochMillis
                            : record.placedAtEpochMillis;
            latest = max(latest, captured);
        }
        return latest;
    }

    /** Immutable result of one settle: residual flows still needing normal classification. */
    @AllArgsConstructor
    static class Partition {
        List<Flow> residualFlows;
        List<Transaction> countedBookings, transferBookings;

        /** Physical flows consumed by custody claims; used to spend matching offer evidence. */
        List<Flow> claimedFlows;

        Transaction lastBooking() {
            List<Transaction> rows = countedBookings.isEmpty() ? transferBookings : countedBookings;
            return rows.isEmpty() ? null : rows.get(rows.size() - 1);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle observation
    // ---------------------------------------------------------------------------------------
    /** Apply one offer-ledger transition; returns any Review handoff or settled rows it forced. */
    synchronized List<Transaction> observeTransition(
            OfferSlots.Transition transition, String name, long now, String id) {
        begin(now, id);
        observe(transition.previous, transition.current, name);
        // New execution/lifecycle evidence may complete a settlement whose cash was observed
        // first; the held batch settles as soon as it is exactly attributable.
        reconcilePendingSettlement();
        return bookings;
    }

    /**
     * Applies one slot snapshot against the slot's baseline: exact incremental fills from the
     * cumulative counters, Modify Offer, slot reuse and identity replacement. Backwards counters,
     * value moved without quantity, or a missed placement are a desync, never a fill.
     */
    void observe(Snapshot previous, Snapshot current, String name) {
        int slot = current.slot;
        Side side = OfferSlots.sideOf(current.state);
        var base = baselines.remove(slot);
        if (side == null) {
            var record = touch(slot, base != null ? base : previous);
            if (record != null) {
                record.cleared = true;
                // Transient slot evidence: proves which offer a nearby collect belongs to.
                slotClearedAtEpochMillis.put(record.offerId, now);
            }
            return;
        }
        var replay = base == null ? lateLoginReplay(slot, side, current) : null;
        if (replay != null) {
            loginClearedAtEpochMillis.remove(replay.offerId);
            slotClearedAtEpochMillis.remove(replay.offerId);
            replay.cleared = false;
            resume(replay, current);
            baselines.put(slot, current);
            return;
        }
        boolean active = OfferSlots.isActive(current.state);
        if (base == null || active && (OfferSlots.sideOf(base.state) != side || base.itemId != current.itemId)) {
            if (base != null) {
                // Item/side changed mid-life without Modify evidence: retire the old lifecycle and
                // quarantine the next; never inherit prior basis or progress.
                var old = touch(slot, base);
                if (old != null) {
                    old.confidence = CustodyConfidence.AMBIGUOUS;
                    handoffAndRemove(old, msg("offer-identity"));
                }
                replacedSlots.add(slot);
            }
            baselines.put(slot, current);
            if (base == null && previous != null && previous.state != GrandExchangeOfferState.EMPTY) {
                // A missed placement cannot be dated: quarantine the lifecycle, emit no fill.
                desync(slot, current, current, name);
                return;
            }
            place(slot, side, current, name);
            advance(slot, current, current, current.quantityTraded, current.spent);
            return;
        }
        if (active && (base.totalQuantity != current.totalQuantity || base.price != current.price)) {
            // A price-only Modify keeps the lifecycle and its basis; a quantity change has no exact
            // per-slice basis allocation, so realization fails closed to Review.
            boolean priceOnly = base.totalQuantity == current.totalQuantity;
            baselines.put(slot, new Snapshot(slot, base.state, base.itemId, current.totalQuantity, base.quantityTraded,
                            priceOnly ? current.price : base.price, base.spent));
            var record = touch(slot, base);
            if (record != null && priceOnly) {
                record.listedPrice = nonNeg(current.price);
            } else if (record != null) {
                record.offeredQty = nonNeg(current.totalQuantity);
                record.quantityModified = true;
            }
            return;
        }
        long quantity = current.quantityTraded - base.quantityTraded, spent = current.spent - base.spent;
        baselines.put(slot, new Snapshot(slot, base.state, base.itemId, base.totalQuantity, current.quantityTraded,
                        base.price, current.spent));
        if (quantity < 0 || spent < 0 || quantity == 0 && spent > 0) {
            desync(slot, base, current, name);
            return;
        }
        advance(slot, base, current, quantity, spent);
    }

    /** A new offer in the slot: a duplicate replay of the live lifecycle changes nothing. */
    void place(int slot, Side side, Snapshot offer, String name) {
        var previous = forSlot(slot);
        if (previous != null && isSameIdentity(previous, side, offer.itemId, offer.totalQuantity, offer.price)
                && !previous.cleared && !previous.isTerminal()) {
            previous.lastSeenAtEpochMillis = now;
            return;
        }
        if (previous != null && previous.stage == OPEN && previous.isReconciled()) {
            // Settled but not yet sealed (a settlement completed by an offer transition):
            // seal it exactly as the next settle would, so it stays as history.
            previous.stage = CLOSED;
            previous = null;
        }
        if (previous != null && previous.cleared && previous.isTerminal() && trusted(previous, previous.side)) {
            // Owner 1.2: a finished offer collected moments before its slot is reused still waits
            // for
            // its items or coins to settle; it gives up the slot instead of going to Review.
            previous.slot = -1;
            previous = null;
        }
        if (previous != null) {
            // Slot reuse: a new offer never inherits prior basis or progress.
            handoffAndRemove(previous, msg("slot-reused"));
        }
        // A settled (CLOSED) lifecycle in this slot stays as bounded market history for its
        // booked sale; only its unrealized reservation leaves with the slot.
        releaseRemaining(latestClosed(slot));
        var record = newRecord(offer, name, offer.state);
        if (replacedSlots.remove(slot)) record.confidence = CustodyConfidence.AMBIGUOUS;
        // Per-fill tax is exact only for a placement observed live from an empty slot.
        record.fillTaxExact = side == SELL && record.confidence == CustodyConfidence.CONFIRMED;
        records.add(record);
    }

    /** Exact fill progress, then the terminal state (a final fill lands before a cancellation). */
    void advance(int slot, Snapshot offer, Snapshot current, long quantity, long spent) {
        boolean fill = quantity > 0 && spent >= 0, terminal = OfferSlots.isTerminal(current.state);
        var record = fill || terminal ? touch(slot, offer) : null;
        if (record != null && fill) recordFill(record, quantity, spent);
        if (record != null && terminal) record.offerState = current.state.name();
    }

    /** Counters with no exact lineage: quarantine the lifecycle, or start an unbased one. */
    void desync(int slot, Snapshot offer, Snapshot current, String name) {
        var record = touch(slot, offer);
        if (record != null) {
            record.confidence = CustodyConfidence.AMBIGUOUS;
            record.fillTaxExact = false;
        } else if (offer.itemId > 0) {
            record = newRecord(offer, name, current.state);
            record.confidence = CustodyConfidence.LEGACY_UNBASED;
            records.add(record);
        }
    }

    /**
     * The live lifecycle the login seed cleared in this slot, when this snapshot is that same
     * offer.
     */
    GeOffer lateLoginReplay(int slot, Side side, Snapshot current) {
        var record = forSlot(slot);
        Long clear = record == null ? null : loginClearedAtEpochMillis.get(record.offerId);
        return clear != null && now - clear <= LOGIN_REPLAY_MILLIS && isSameIdentity(
                                record, side, current.itemId, current.totalQuantity, current.price) ? record : null;
    }

    /**
     * Resume a persisted lifecycle; progress made while away is adopted once, never as a fill. Its
     * confidence stays as it was: a relog never makes a quarantined or unbased offer trusted.
     */
    void resume(GeOffer existing, Snapshot snapshot) {
        existing.lastSeenAtEpochMillis = now;
        existing.offerState = snapshot.state.name();
        if (snapshot.quantityTraded != existing.filledQty) {
            // Fills while logged out were never observed at their own prices.
            existing.fillTaxExact = false;
        }
        existing.filledQty = snapshot.quantityTraded;
        existing.spentGp = nonNeg(snapshot.spent);
    }

    /** A new lifecycle for the offer in this snapshot's slot, last seen in {@code state}. */
    GeOffer newRecord(Snapshot offer, String name, GrandExchangeOfferState state) {
        var record = new GeOffer(uuid(), offer.slot, OfferSlots.sideOf(offer.state), offer.itemId,
                        name, offer.totalQuantity, offer.price, now, orEmpty(sessionId));
        record.lastSeenAtEpochMillis = now;
        record.offerState = state.name();
        return record;
    }

    /**
     * Record the login slot baseline: resume a persisted matching lifecycle, quarantine active
     * offers first seen now as legacy/unbased, and hand off records whose slot changed identity.
     */
    synchronized List<Transaction> seedSlots(Map<Integer, Snapshot> snapshots, long now, String id) {
        begin(now, id);
        baselines.clear();
        loginClearedAtEpochMillis.clear();
        for (var snapshot : snapshots.values()) {
            var existing = forSlot(snapshot.slot);
            if (snapshot.state == GrandExchangeOfferState.EMPTY) {
                if (existing != null) loginClearedAtEpochMillis.put(existing.offerId, now);
                else existing = latestClosed(snapshot.slot);
                if (existing != null) {
                    existing.cleared = true;
                    existing.lastSeenAtEpochMillis = now;
                    slotClearedAtEpochMillis.put(existing.offerId, now);
                }
                continue;
            }
            Side side = OfferSlots.sideOf(snapshot.state);
            if (side == null) continue;
            // Pre-existing progress is only a baseline, never replayed as a fill.
            baselines.put(snapshot.slot, snapshot);
            if (existing != null && isSameIdentity(existing, side, snapshot.itemId, snapshot.totalQuantity,
                            snapshot.price)) {
                resume(existing, snapshot);
                continue;
            }
            if (existing != null) {
                handoffAndRemove(existing, msg("offer-changed"));
            }
            var record = newRecord(snapshot, "", snapshot.state);
            record.confidence = CustodyConfidence.LEGACY_UNBASED;
            record.filledQty = snapshot.quantityTraded;
            record.spentGp = nonNeg(snapshot.spent);
            records.add(record);
        }
        return bookings;
    }

    /**
     * True when every moved flow is explained by a recent offer: an item or coins lost shortly
     * after a sell or buy was placed, an item or coins gained after a buy or sell filled, or the
     * other side's cancellation. Custody is authoritative for GE economics, so a movement it could
     * not claim but an offer explains is Grand Exchange activity for Review, never counted.
     */
    synchronized boolean explains(List<Flow> flows, long now) {
        this.now = now;
        boolean moved = false;
        for (Flow flow : flows) {
            if (flow.quantityDelta != 0) {
                moved = true;
                if (explaining(flow) == null) return false;
            }
        }
        return moved;
    }

    /** Spends the offer evidence behind these movements: one offer side explains one movement. */
    synchronized void spend(List<Flow> flows, long now) {
        this.now = now;
        for (Flow flow : flows) {
            String key = flow.quantityDelta == 0 ? null : explaining(flow);
            if (key != null) explainedAt.put(key, now);
        }
    }

    String explaining(Flow flow) {
        boolean coins = flow.itemId == ItemID.COINS;
        for (var record : records) {
            boolean buy = record.side == BUY;
            String key = record.offerId + (coins ? "$" : "");
            Long spent = explainedAt.get(key);
            long seen = record.lastSeenAtEpochMillis;
            // Only a live placement moves coins (buy) or items (sell) out of the inventory; a
            // finished
            // fill of the same side or a cancellation of the other brings them back. Passive
            // progress on an open offer explains nothing.
            boolean fits = flow.isCost() ? buy == coins && spent == null && !record.isUnbased() && !record.isCancelled()
                                    && now - record.placedAtEpochMillis <= PLACEMENT_WINDOW_MILLIS
                            : (spent == null || spent < seen) && now - seen <= COLLECTION_WINDOW_MILLIS && (buy != coins
                                            ? record.filledQty > 0 && record.isTerminal() : record.isCancelled());
            if (fits && (coins || record.itemId == flow.itemId)) return key;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Flow-level custody claims and canonical realization
    // ---------------------------------------------------------------------------------------
    /**
     * Claim custody flows from one stabilized settle. {@code itemAllowed} excludes items already
     * owned by a stronger per-item evidence source (consume/drop/loot) without weakening the
     * whole-transaction safety gates. {@code collectionEvidence} is a bounded player Collect
     * interaction; without it, collection claims need an observed terminal offer state.
     */
    synchronized Partition partition(List<Flow> incoming, long now, String id, IntPredicate itemAllowed,
            boolean collection) {
        begin(now, id);
        var transfers = new ArrayList<Transaction>();
        residual = new ArrayList<>(incoming);
        claimed = new ArrayList<>();
        allowed = itemAllowed;
        // A settlement held from an earlier collect may now be exactly attributable.
        reconcilePendingSettlement();
        claimSellPlacements();
        claimBuyReserves();
        claimBuyRefunds();
        claimSellReturns();
        claimBuyCollections(collection);
        claimSellSettlements(collection);
        if (!claimed.isEmpty()) {
            transfers.add(new Transaction(now, TransactionType.TRANSFER, Context.TRANSFER,
                            msg("grand-exchange-custody"), "Market", false, new ArrayList<>(claimed),
                            Confidence.CONFIRMED, msg("ownership-neutral-grand"), null));
        }
        // A hold created by this settle retries immediately: a batch whose evidence is complete
        // fails closed at once instead of waiting invisibly for a later event.
        reconcilePendingSettlement();
        for (var open : records) {
            if (open.stage == OPEN && open.isReconciled()) open.stage = CLOSED;
        }
        return new Partition(residual, bookings, transfers, claimed);
    }

    /** Prune reconciled lifecycles, expire terminal-unobserved ones and cap stale records. */
    synchronized List<Transaction> maintenance(long now, String id) {
        begin(now, id);
        prune();
        // A held settlement retries and, once its evidence is exhausted or its bounded
        // lifecycle expires, fails closed to Review with the observed cash preserved.
        reconcilePendingSettlement();
        return bookings;
    }

    void prune() {
        int closed = 0;
        for (var record : records) {
            if (record.stage == CLOSED) closed++;
        }
        // Settled history is bounded: the oldest closed lifecycles leave first.
        for (; closed > MAX_CLOSED_RECORDS; closed--) {
            var first = oldestClosed();
            releaseRemaining(first);
            records.remove(first);
        }
        for (int index = records.size() - 1; index >= 0; index--) {
            var record = records.get(index);
            if (record.stage == CLOSED) continue;
            if (record.isReconciled()) {
                record.stage = CLOSED;
                continue;
            }
            // Expire by grace only a slot that is CLEARED and whose cash was never seen (a
            // collect-to-bank and similar). A SELL whose slot still shows the terminal state is
            // still in GE custody: its coins may be collected much later, so it must not expire by
            // time. BUY keeps its existing pattern (reported, not changed).
            boolean clear = record.cleared;
            // Owner 2026-10-09: Collect to bank (or a collect while GP Manager was away) settles
            // from
            // the offer itself instead of waiting for Review; held collect cash may still be its
            // own.
            if (clear && now - record.lastSeenAtEpochMillis > BANK_COLLECT_MILLIS && pendingSettlementCashGp <= 0
                    && banked(record)) {
                continue;
            }
            boolean buyTerminal = record.side != SELL && record.isTerminal();
            if ((clear || buyTerminal) && now - record.lastSeenAtEpochMillis > UNOBSERVED_GRACE_MILLIS) {
                handoffAndRemove(record, msg("settlement-observed"));
            }
        }
        slotClearedAtEpochMillis.values().removeIf(at -> now - at > UNOBSERVED_GRACE_MILLIS);
        // Spent offer evidence goes with its offer.
        var live = new HashSet<String>();
        for (var record : records) live.add(record.offerId);
        explainedAt.keySet().removeIf(key -> !live.contains(key.replace("$", "")));
    }

    // -- Custody claims: each settle offers its flows to the lifecycles, oldest placement first --
    /** One lifecycle's claim on a flow: the magnitude it takes, or 0 to pass. */
    interface ClaimStep {
        long claim(GeOffer record, Flow flow, long remaining);
    }

    /** Offers one side's trusted lifecycles a gain or loss of coins or items, oldest first. */
    void claimFlows(Side side, boolean gain, boolean coins, ClaimStep step) {
        FlowFilters.claim(residual, flow -> (gain ? flow.isGain() : flow.isCost())
                                && (flow.itemId == ItemID.COINS) == coins && (coins || allowed.test(flow.itemId)),
                (flow, size) -> {
                    long remaining = size;
                    for (var record : byPlacement()) {
                        if (remaining > 0 && trusted(record, side) && (coins || record.itemId == flow.itemId))
                            remaining -= nonNeg(step.claim(record, flow, remaining));
                    }
                    return size - remaining;
                });
    }

    /** A lifecycle observed from its placement with no identity desync. */
    static boolean trusted(GeOffer record, Side side) {
        return record.side == side && !record.isUnbased() && record.confidence != CustodyConfidence.AMBIGUOUS;
    }

    /** SELL placement principal (item loss). */
    void claimSellPlacements() {
        claimFlows(SELL, false, false, (record, flow, remaining) -> {
                    long claim = min(remaining, record.offeredQty);
                    if (record.isCancelled() || record.cleared || record.capturedQty > 0
                            || now - record.placedAtEpochMillis > PLACEMENT_WINDOW_MILLIS || claim <= 0) {
                        return 0L;
                    }
                    captureBasis(record, flow);
                    if (basisLedger != null) {
                        var reservation = basisLedger.reserve(record.itemId, claim, now);
                        record.reservedTrackedQty = nonNeg(safeAdd(record.reservedTrackedQty, reservation.trackedQty));
                        record.reservedTrackedBasisGp =
                                nonNeg(safeAdd(record.reservedTrackedBasisGp, reservation.basisGp));
                    }
                    record.capturedQty = claim;
                    record.lastSeenAtEpochMillis = now;
                    claimed.add(flow.part(claim));
                    return claim;
                });
    }

    /** BUY observed reserve (coin loss). */
    void claimBuyReserves() {
        var gainItems = gainQuantities(residual, ANY).keySet();
        claimFlows(BUY, false, true, (record, flow, remaining) -> {
                    long reserve = nonNeg(record.offeredQty * record.listedPrice);
                    // Owner 1.2: an offer placed, bought and collected inside one settle shows only
                    // its exact spend, the reserve with its change already back; the purchase books
                    // it, never Review.
                    boolean instant = gainItems.contains(record.itemId) && record.isTerminal()
                                    && remaining == record.spentGp;
                    long claim = instant ? remaining : min(remaining, reserve);
                    // A same-item acquisition alongside the coin loss is a purchase, not a reserve:
                    // leave the mixed movement to the normal classification path. An offer that
                    // filled at once still took these coins; only a cancelled one kept none.
                    if (record.isCancelled() || record.cleared && !instant || record.reserveGp > 0
                            || now - record.placedAtEpochMillis > PLACEMENT_WINDOW_MILLIS
                            || !instant && gainItems.contains(record.itemId) || claim <= 0) {
                        return 0L;
                    }
                    record.reserveGp = instant ? reserve : nonNeg(claim);
                    if (instant) record.refundedGp = nonNeg(reserve - claim);
                    record.lastSeenAtEpochMillis = now;
                    claimed.add(flow.part(claim, 1, FACE_VALUE));
                    return claim;
                });
    }

    /** BUY refund (coin gain). */
    void claimBuyRefunds() {
        claimFlows(BUY, true, true, (record, flow, remaining) -> {
                    long refundRoom = nonNeg(record.offeredQty * record.listedPrice - record.spentGp)
                                    - record.refundedGp;
                    // An offer paid from the bank (the GE draws there once the pack runs out) never
                    // showed its reserve, yet its change still lands in the pack: as the slot
                    // clears, up to the exact change owed is the player's own coins coming back
                    // (owner live 2026-10-16: two bank-paid buys sent their change to Review).
                    Long clear = slotClearedAtEpochMillis.get(record.offerId);
                    boolean bankPaid = record.reserveGp == 0 && clear != null
                                    && Math.abs(now - clear) <= 2 * SLOT_WINDOW_MILLIS;
                    long claim = min(remaining, min(nonNeg(refundRoom),
                                            bankPaid ? refundRoom : record.reserveOutstanding()));
                    // Change is owed once the offer is finished (bought or cancelled) or its slot
                    // cleared.
                    if (!(record.isTerminal() || record.cleared) || claim <= 0) return 0L;
                    record.refundedGp = nonNeg(safeAdd(record.refundedGp, claim));
                    claimed.add(flow.part(claim, 1, FACE_VALUE));
                    return claim;
                });
    }

    /**
     * An old Review row for coins a bank-paid buy gave back (before 1.5.1 that change went to
     * Review): the latest such buy placed before it that still owes at least that change takes it
     * (owner live 2026-10-16).
     */
    synchronized boolean claimOldChange(long gp, long at) {
        GeOffer match = null;
        for (var record : records) {
            if (record.side == BUY && record.reserveGp == 0 && (record.isTerminal() || record.cleared)
                    && record.placedAtEpochMillis <= at && at - record.placedAtEpochMillis <= COLLECTION_WINDOW_MILLIS
                    && nonNeg(record.offeredQty * record.listedPrice - record.spentGp) - record.refundedGp
                            >= gp && (match == null || record.placedAtEpochMillis > match.placedAtEpochMillis))
                match = record;
        }
        if (match == null) return false;
        match.refundedGp = safeAdd(match.refundedGp, gp);
        return true;
    }

    /** SELL cancellation return (item gain). */
    void claimSellReturns() {
        claimFlows(SELL, true, false, (record, flow, remaining) -> {
                    long claim = min(remaining, record.heldQty());
                    if (!(record.isCancelled() || record.cleared) || claim <= 0) return 0L;
                    releaseShare(record, claim);
                    record.returnedQty = safeAdd(record.returnedQty, claim);
                    // The returned principal is neutral at its frozen custody basis, never the
                    // current live quote; with no frozen basis it stays the observed movement.
                    claimed.add(record.basisUnitPrice > 0 ? flow.part(
                                            claim, record.basisUnitPrice, record.getBasisSource()) : flow.part(claim));
                    return claim;
                });
    }

    /** BUY collection (item gain). */
    void claimBuyCollections(boolean collection) {
        claimFlows(BUY, true, false, (record, flow, remaining) -> {
                    long claim = min(remaining, record.pendingQty());
                    // Passive progress alone never turns a same-item delta into a collection.
                    if (record.quantityModified || claim <= 0
                            || !(collection || record.isTerminal() || record.cleared)) {
                        return 0L;
                    }
                    if (record.basisSource == null) captureBasis(record, flow);
                    record.collectedQty = nonNeg(safeAdd(record.collectedQty, claim));
                    record.collectedValueGp = safeAdd(record.collectedValueGp,
                                    safeMultiply(claim, flow.unitPrice));
                    record.lastSeenAtEpochMillis = now;
                    claimed.add(flow.part(claim));
                    if (record.collectedQty < record.filledQty) {
                        // Partial collection: execution chunks are not attributable; fail closed.
                        record.collectionAmbiguous = true;
                    } else {
                        bookBuySettlement(record);
                    }
                    return claim;
                });
    }

    void bookBuySettlement(GeOffer record) {
        long quantity = record.collectedQty - record.settledQty, execution = record.pendingExecution();
        if (quantity <= 0) return;
        boolean clean = isCleanRealization(record);
        var flows = new ArrayList<Flow>(2);
        // Exact NEW-model BUY: the asset value equals the actual settled spend, so acquisition is
        // Net-neutral and the acquired quantity becomes known coverage at that exact spend.
        flows.add(itemFlow(record, quantity, execution / quantity, execution));
        flows.add(coins(-execution));
        settled(record, buildMarket(flows, clean, clean, msg("grand-exchange-purchase")), quantity, execution,
                execution);
        record.collectedQty = nonNeg(record.settledQty);
        record.collectedValueGp = 0;
    }

    Flow coins(long amount) {
        return new Flow(ItemID.COINS, "Coins", amount, 1, amount, FACE_VALUE, now);
    }

    /** One flow of the offer's item, carrying its frozen basis source and capture time. */
    static Flow itemFlow(GeOffer record, long quantity, long price, long value) {
        return new Flow(record.itemId, record.itemName, quantity, toInt(price), value, record.getBasisSource(),
                record.basisCapturedAtEpochMillis);
    }

    /** An uncounted Market row left for Review: the observed movement, never a guess. */
    Transaction reviewRow(List<Flow> flows, String reason) {
        return new Transaction(now, UNCERTAIN, MARKET, "Grand Exchange", "Market", false, flows, Confidence.UNCERTAIN,
                reason, null);
    }

    /** Claims a whole coin gain as held or settled GE cash. */
    long claimWhole(Flow flow) {
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
     * later exact collect.
     */
    void claimSellSettlements(boolean collection) {
        if (!collection) {
            // A coin gain with no Collect interaction settles a waiting sale only when it is
            // that whole sale, less at most its tax.
            claimSellsWithoutCollect();
            return;
        }
        FlowFilters.claim(residual, flow -> flow.isGain() && flow.itemId == ItemID.COINS, (flow, size) -> {
                    long cash = flow.valueDelta;
                    var options = settlementCandidates(true);
                    // Coins may arrive before the offer's execution/terminal evidence. A live
                    // Collect interaction with a captured sell lifecycle still pending is that
                    // settlement's cash; with no candidate yet it is held below (an empty set
                    // attributes to NONE) instead of letting an unresolved Coins row reach Review.
                    // The bounded hold retries as offer evidence arrives.
                    if (options.isEmpty() && (cash <= 0 || !hasPendingSellEvidence()))
                        return 0L;
                    var owner = attribute(options, cash, now);
                    if (owner.kind == AttributionKind.SETTLE) {
                        settleAttributed(owner.records, cash);
                        return claimWhole(flow);
                    }
                    if (!owner.contradicted) {
                        // Exact-but-unconfirmed, tied, or unmatched evidence: retain the observed
                        // cash
                        // transiently. The SLOT WINDOW gathers the slot evidence a fail-closed
                        // decision
                        // needs; while a sell can still receive execution evidence, the existing
                        // long hold
                        // keeps waiting for it.
                        holdCash(cash);
                        pendingSettlementAwaitingEvidence |= owner.kind == AttributionKind.NONE
                                        && hasPendingSellEvidence();
                        return claimWhole(flow);
                    }
                    // No exact attribution: only candidates with slot evidence in this interaction
                    // may be
                    // quarantined. The unattributed movement follows the ordinary path to one
                    // uncounted
                    // Review Coins row; every other candidate stays pending for a later exact
                    // collect.
                    quarantineAmbiguous(owner.quarantinable);
                    return 0L;
                });
    }

    /**
     * Booking for a coin gain with no Collect interaction (the click fell outside its window): only
     * a gain between the whole waiting sale and that sale less its largest possible tax settles it.
     */
    void claimSellsWithoutCollect() {
        FlowFilters.claim(residual, flow -> flow.isGain() && flow.itemId == ItemID.COINS, (flow, size) -> {
                    var options = settlementCandidates(false);
                    long totalExecution = 0;
                    for (var record : options)
                        totalExecution = safeAdd(totalExecution, record.pendingExecution());
                    long original = flow.valueDelta;
                    // Without a Collect click only the whole waiting sale, less at most its GE
                    // tax (2%), can explain the coins: an alch or a shop sale never claims it.
                    if (original > totalExecution || original < totalExecution - totalExecution / 50) return 0L;
                    long claimCash = min(original, totalExecution);
                    if (claimCash <= 0) return 0L;
                    if (options.size() > 1) {
                        boolean clean = true, allKnownCoverage = true;
                        long expectedTax = 0;
                        for (var record : options) {
                            clean &= isCleanRealization(record);
                            allKnownCoverage &= record.reservedQty() >= record.pendingQty();
                            // Candidate fee only: the aggregate observed cash must confirm the
                            // exact combined gap.
                            expectedTax = safeAdd(expectedTax, nonNeg(pendingSellTax(record)));
                        }
                        boolean taxAttributed = expectedTax > 0 && totalExecution - claimCash == expectedTax;
                        if (!taxAttributed && (expectedTax > 0 || claimCash != totalExecution) && !allKnownCoverage) {
                            clean = false;
                            for (var record : options) record.collectionAmbiguous = true;
                        }
                        // An exact batch books each offer's after-tax share of the observed cash;
                        // any
                        // other batch books gross execution and an adjustment row for the
                        // difference.
                        long allocated = 0;
                        for (var record : options) {
                            long quantity = record.pendingQty(), execution = record.pendingExecution();
                            if (quantity > 0 && execution > 0) {
                                long cash = taxAttributed ? execution - nonNeg(pendingSellTax(record)) : execution;
                                bookSellSettlement(record, quantity, cash, execution, clean);
                                allocated = safeAdd(allocated, cash);
                            }
                        }
                        if (claimCash != allocated) {
                            // Legacy (non-collect) batch adjustment row; collect interactions never
                            // produce one.
                            bookings.add(new Transaction(now, clean ? TRADE : UNCERTAIN, MARKET,
                                            msg("grand-exchange-settlement"), "Market", clean,
                                            singletonList(coins(claimCash - allocated)),
                                            clean ? Confidence.CONFIRMED : Confidence.UNCERTAIN,
                                            msg("grand-exchange-batch"), null));
                        }
                    } else {
                        var record = options.get(0);
                        long execution = record.pendingExecution();
                        boolean over = original > execution;
                        record.collectionAmbiguous = record.collectionAmbiguous || over;
                        bookSellSettlement(record, record.pendingQty(), over ? execution : claimCash, execution,
                                !over && isCleanRealization(record));
                    }
                    claimed.add(flow.part(claimCash, 1, FACE_VALUE));
                    return claimCash;
                });
    }

    /**
     * Book one exactly attributed subset at each offer's own observed after-tax cash. A sale that
     * filled at several prices in one observed chunk has a tax range, not one tax: the observed
     * cash proves the subset's total, so each such offer pays its lowest possible tax plus, in
     * order, the rest up to its highest (owner 2026-10-08: a big mixed-price sale is no Review).
     */
    void settleAttributed(List<GeOffer> records, long cash) {
        boolean clean = true;
        for (var record : records) {
            clean &= isCleanRealization(record);
            long[] net = net(record);
            if (net != null) cash -= net[1];
        }
        // The tax beyond every offer's lowest possible tax.
        cash = -cash;
        for (var record : records) {
            long[] net = net(record);
            // An offer with no execution is never part of a match; the attribution excluded it.
            if (net == null) continue;
            long execution = record.pendingExecution(), extra = nonNeg(min(net[1] - net[0], cash));
            cash -= extra;
            long tax = execution - net[1] + extra;
            if (pendingSellTax(record) < 0) {
                // The cash proved it: later reads (the receipt, a restart) see this exact tax.
                record.fillTaxExact = true;
                record.fillTaxGp = safeAdd(record.settledTaxGp, tax);
            }
            bookSellSettlement(record, record.pendingQty(), execution - tax, execution, clean);
        }
    }

    /**
     * The pending execution's possible after-tax cash {lowest, highest}, or null with no execution.
     * Exact when its tax is proven; otherwise each item paid price/50 rounded down, so the stack
     * paid at most gross/50 and at least (gross - 49 per item)/50 (below the 5M per-item cap).
     */
    static long[] net(GeOffer record) {
        long execution = record.pendingExecution(), quantity = record.pendingQty(), tax = pendingSellTax(record);
        if (execution <= 0 || quantity <= 0) return null;
        if (tax >= 0) return new long[] {execution - tax, execution - tax};
        long least = execution >= 250_000_000 ? 0 : nonNeg((execution - 49 * quantity + 49) / 50);
        return new long[] {execution - execution / 50, execution - least};
    }

    /**
     * Fail closed on the slot-evidenced candidates only. The quarantine booking keeps today's shape
     * (uncounted, collectionAmbiguous, the booked allocation recorded) while the observed cash
     * itself is never assigned to a lifecycle it was not proven part of.
     */
    void quarantineAmbiguous(List<GeOffer> quarantinable) {
        for (var record : quarantinable) {
            long quantity = record.pendingQty(), execution = record.pendingExecution();
            if (quantity > 0 && execution > 0) {
                record.collectionAmbiguous = true;
                bookSellSettlement(record, quantity, execution, execution, false);
            }
        }
    }

    /** Candidate sell lifecycles for one coin gain; the retry needs durable terminal evidence. */
    List<GeOffer> settlementCandidates(boolean collection) {
        return matching(byPlacement(), record -> record.side == SELL && record.pendingQty() > 0
                && record.capturedQty > 0 && record.settleableQty() >= record.pendingQty() && !record.isUnbased()
                && (collection || record.isTerminal() || record.cleared));
    }

    /** Retain observed collect cash transiently; the SLOT WINDOW anchors on the first hold. */
    void holdCash(long cash) {
        if (cash <= 0) return;
        if (pendingSettlementCashGp <= 0) pendingSettlementHeldSinceEpochMillis = now;
        pendingSettlementCashGp = safeAdd(pendingSettlementCashGp, cash);
        pendingSettlementAtEpochMillis = max(pendingSettlementAtEpochMillis, now);
    }

    /** Exact attribution of one collect coin movement over the current candidate set. */
    Attribution attribute(List<GeOffer> options, long cash, long coinAt) {
        int size = options.size();
        var quarantinable = new ArrayList<GeOffer>();
        if (size <= 0 || size > MAX_SETTLEMENT_CANDIDATES) {
            return new Attribution(AttributionKind.NONE, quarantinable, quarantinable, false);
        }
        // Each candidate's possible after-tax cash: one value, or a range for a mixed-price fill.
        long[][] nets = new long[size][];
        var clear = new ArrayList<Integer>();
        for (int index = 0; index < size; index++) {
            var record = options.get(index);
            nets[index] = net(record);
            Long clearedAt = slotClearedAtEpochMillis.get(record.offerId);
            // The candidate's slot cleared within the SLOT WINDOW of the coin gain.
            if (clearedAt != null && Math.abs(clearedAt - coinAt) <= SLOT_WINDOW_MILLIS) {
                clear.add(index);
                quarantinable.add(record);
            }
        }
        // Apply slot evidence only after enumerating every subset within the eight-offer bound.
        var matches = matchingSubsets(nets, cash, 1 << size);
        List<Integer> match = null;
        var kind = AttributionKind.NONE;
        boolean contradicted = false;
        if (matches.contains(clear)) {
            // §2.1: the slot-clear set is the primary proof; a single candidate has no competing
            // explanation either, so neither needs slot confirmation (an amount it cannot have
            // received, or an execution with no known price, still fails closed below).
            kind = AttributionKind.SETTLE;
            match = clear;
        } else if (size == 1 && !matches.isEmpty()) {
            kind = AttributionKind.SETTLE;
            match = matches.get(0);
        } else if (matches.size() > 1) {
            // §2.3: slot evidence may still select exactly one matching subset.
            matches.removeIf(subset -> !subset.containsAll(clear));
            contradicted = !clear.isEmpty() && matches.isEmpty();
            kind = contradicted ? AttributionKind.NONE
                            : matches.size() == 1 ? AttributionKind.SETTLE : AttributionKind.TIE;
            match = matches.size() == 1 ? matches.get(0) : null;
        } else if (matches.size() == 1) {
            // A unique match settles when the slot-clear set is exactly it, needs confirmation
            // while
            // no slot outside it cleared, and §2.2: a slot cleared outside it contradicts it.
            match = matches.get(0);
            contradicted = !match.containsAll(clear);
            kind = match.equals(clear) ? AttributionKind.SETTLE
                            : contradicted ? AttributionKind.NONE : AttributionKind.CONFIRM;
        }
        var records = new ArrayList<GeOffer>();
        for (int index : match == null || contradicted ? Collections.<Integer>emptyList() : match) {
            records.add(options.get(index));
        }
        return new Attribution(kind, records, quarantinable, contradicted);
    }

    /**
     * Subset enumeration over candidates with a known execution: a subset matches when the cash
     * lies within its summed {lowest, highest} after-tax cash. Saturating sums, deterministic index
     * order, at most {@code limit} matches. Returns empty when the candidate bound is exceeded, so
     * an over-full interaction can never be enumerated.
     */
    static List<List<Integer>> matchingSubsets(long[][] nets, long cash, int limit) {
        int size = nets == null ? 0 : nets.length;
        var matches = new ArrayList<List<Integer>>();
        if (size <= 0 || size > MAX_SETTLEMENT_CANDIDATES) {
            return matches;
        }
        for (int mask = 1; mask < 1 << size && matches.size() < limit; mask++) {
            long least = 0, most = 0;
            var subset = new ArrayList<Integer>();
            for (int index = 0; index < size && least >= 0; index++) {
                if ((mask & (1 << index)) != 0) {
                    subset.add(index);
                    least = nets[index] == null ? -1L : safeAdd(least, nets[index][0]);
                    most = nets[index] == null ? most : safeAdd(most, nets[index][1]);
                }
            }
            if (least >= 0 && least <= cash && cash <= most) matches.add(subset);
        }
        return matches;
    }

    /** Attribution outcome kinds; names are conceptual, not persisted. */
    enum AttributionKind {
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
        AttributionKind kind;
        List<GeOffer> records, quarantinable;
        boolean contradicted;
    }

    /** True while an observed sell lifecycle can still receive execution/terminal evidence. */
    boolean hasPendingSellEvidence() {
        for (var record : records) {
            if (record.side == SELL && record.capturedQty > 0 && !record.isUnbased() && !record.isTerminal()
                    && !record.cleared) {
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
    void reconcilePendingSettlement() {
        if (pendingSettlementCashGp <= 0) return;
        // The held cash IS a collect interaction, so the retry sees the same candidate set that
        // interaction saw (still-active sells whose fills may still be collected included).
        Attribution owner = attribute(settlementCandidates(true), pendingSettlementCashGp,
                        pendingSettlementHeldSinceEpochMillis);
        boolean windowClosed = now - pendingSettlementHeldSinceEpochMillis > SLOT_WINDOW_MILLIS;
        // §2.2b: a unique match settles at the window end with no contradicting slot evidence; the
        // existing long hold also books as soon as its missing execution evidence completes.
        if (owner.kind == AttributionKind.SETTLE || owner.kind == AttributionKind.CONFIRM
                        && (windowClosed || pendingSettlementAwaitingEvidence)) {
            settleAttributed(owner.records, pendingSettlementCashGp);
            clearPendingSettlement();
            return;
        }
        // §2.2 contradiction, §2.3 an unresolved tie, and §2.4/§3 exhausted evidence fail closed:
        // only slot-evidenced candidates are quarantined and the held cash goes to Review.
        if (owner.contradicted || now - pendingSettlementAtEpochMillis > UNOBSERVED_GRACE_MILLIS
                || owner.kind == AttributionKind.TIE && windowClosed || owner.kind == AttributionKind.NONE
                        && (pendingSettlementAwaitingEvidence ? !hasPendingSellEvidence() : windowClosed)) {
            quarantineAmbiguous(owner.quarantinable);
            releasePendingSettlement();
        }
    }

    /** Releases a held collect to Review at the time it was held; returns that row. */
    synchronized List<Transaction> releaseHeldCollect() {
        begin(pendingSettlementAtEpochMillis, sessionId);
        releasePendingSettlement();
        return bookings;
    }

    /** Fail closed: the observed cash stays visible as an uncounted Review row, never guessed. */
    void releasePendingSettlement() {
        long held = pendingSettlementCashGp;
        clearPendingSettlement();
        if (held <= 0) return;
        bookings.add(reviewRow(singletonList(coins(held)), msg("grand-exchange-collection")));
    }

    void bookSellSettlement(GeOffer record, long quantity, long cashValue, long execution, boolean clean) {
        long remaining = record.reservedQty(), remainingBasis = record.reservedBasis();
        long known = min(quantity, remaining), knownBasis = BasisMath.shareOf(remaining, remainingBasis, known);
        long knownProceeds = BasisMath.knownProceedsOf(cashValue, quantity, known);
        boolean fullyUnknown = known <= 0;
        record.consumedTrackedQty = safeAdd(record.consumedTrackedQty, known);
        record.consumedTrackedBasisGp = safeAdd(record.consumedTrackedBasisGp, knownBasis);
        if (basisLedger != null) {
            // The realized quantity left ownership even when it carried no known coverage; the
            // per-item realization fence always advances.
            basisLedger.fenceRealized(record.itemId, now);
        }
        // The one shared accepted-tax predicate: this settlement's observed gross execution against
        // its actual Received cash. Only an exact per-item match counts; the tax is never inferred
        // from a reference, a guide price or a difference.
        long provenTax = pendingSellTax(record);
        long acceptedTax = fullyUnknown && clean && provenTax > 0 && execution - cashValue == provenTax ? provenTax
                        : 0;
        boolean knownCostOnly = acceptedTax > 0;
        var flows = new ArrayList<Flow>(2);
        if (knownCostOnly) {
            // Fully unknown prior value with a PROVEN tax: the departing item flow is valued at
            // the observed gross execution (not at the frozen reference) against the observed
            // Received cash, so the counted automatic Net is exactly -accepted tax — a market
            // trade cost. Result stays unknown: the accumulator below receives no result here, and
            // a later correction replaces this contribution instead of stacking on it.
            long unit = execution / quantity;
            flows.add(itemFlow(record, -quantity, unit, -execution));
            flows.add(coins(cashValue));
        } else if (fullyUnknown) {
            // Unknown coverage without a proven tax: the full observed movement stays as
            // liquidation evidence and is Net-neutral. It is never a fake cost and never
            // automatic profit.
            flows.add(itemFlow(record, -quantity, record.basisUnitPrice, -record.basisFor(quantity)));
            flows.add(coins(cashValue));
        } else {
            if (knownBasis > 0) {
                long unit = known > 0 ? knownBasis / known : 0;
                flows.add(itemFlow(record, -quantity, unit, -knownBasis));
            }
            flows.add(coins(knownProceeds));
        }
        String reason = knownCostOnly ? msg("grand-exchange-sale-settled-proven") : fullyUnknown
                                ? msg("grand-exchange-sale-settled") : known < quantity
                                        ? msg("grand-exchange-sale-settled-quantity") : msg("grand-exchange-sale");
        // Counted when the lifecycle is exact AND either the realized quantity carries known
        // coverage
        // or the actual GE tax is proven. An unknown sale without a proven tax stays an exact,
        // uncounted evidence row (liquidation cash retained, Net-neutral, never a Review nag).
        if (provenTax >= 0) record.settledTaxGp = nonNeg(safeAdd(record.settledTaxGp, provenTax));
        record.realizedResultGp = safeAdd(record.realizedResultGp, knownProceeds - knownBasis);
        settled(record, buildMarket(flows, clean, clean && (!fullyUnknown || knownCostOnly), reason), quantity,
                execution, cashValue);
    }

    /** Books one settlement row and advances the offer's settled counters by what it booked. */
    void settled(GeOffer record, Transaction row, long quantity, long execution, long cash) {
        bookings.add(row);
        record.settledQty = safeAdd(record.settledQty, quantity);
        record.settledExecutionGp = safeAdd(record.settledExecutionGp, execution);
        record.settledCashGp = safeAdd(record.settledCashGp, cash);
        record.setSettlementId(row.getId());
    }

    /** Return an unconsumed reservation share when custody returns or retires a lifecycle. */
    void releaseShare(GeOffer record, long returnedQty) {
        if (returnedQty <= 0) return;
        long remaining = record.reservedQty(), remainingBasis = record.reservedBasis();
        if (remaining <= 0 && remainingBasis <= 0) return;
        long released = min(returnedQty, remaining);
        long releaseBasis = BasisMath.shareOf(remaining, remainingBasis, released);
        if (basisLedger != null && (released > 0 || releaseBasis > 0)) {
            basisLedger.release(record.itemId, released, releaseBasis);
        }
        record.reservedTrackedQty = nonNeg(max(record.consumedTrackedQty, record.reservedTrackedQty - released));
        record.reservedTrackedBasisGp = nonNeg(max(record.consumedTrackedBasisGp,
                                record.reservedTrackedBasisGp - releaseBasis));
    }

    /** Release every still-unrealized reserved share when a lifecycle leaves custody state. */
    void releaseRemaining(GeOffer record) {
        if (record == null) return;
        releaseShare(record, max(record.reservedQty(), record.reservedBasis()));
    }

    // ---------------------------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------------------------
    /**
     * One Market row: exact lifecycles book as trades, anything else stays uncounted for review.
     */
    Transaction buildMarket(List<Flow> flows, boolean exact, boolean countsInNet, String reason) {
        return new Transaction(now, exact ? TRADE : UNCERTAIN, MARKET, "Grand Exchange", "Market", countsInNet, flows,
                exact ? Confidence.CONFIRMED : Confidence.UNCERTAIN, exact ? reason : reason
                                + msg("lifecycle-could-settled-exactly") + msg("stays-uncounted"), null);
    }

    boolean isCleanRealization(GeOffer record) {
        if (record.isUnbased() || record.confidence == CustodyConfidence.AMBIGUOUS || record.quantityModified
                || record.collectionAmbiguous || !record.hasFrozenBasis()) {
            return false;
        }
        if (record.originSessionId.isEmpty() && !empty(sessionId)) {
            record.originSessionId = orEmpty(sessionId);
        }
        // Owner policy (2026-09-24, replaces PRE-R5B 11.19): a settlement books in the session
        // where it is collected; the listing session is kept for the "Listed during" label only
        // and is never rewritten.
        return true;
    }

    /**
     * Accumulate one observed fill. A SELL chunk's tax is exact only when its own per-item price is
     * proven (spent divisible by quantity, or an exempt item); otherwise per-fill tax is lost for
     * this lifecycle and the uniform rule over the total remains the only proof.
     */
    static void recordFill(GeOffer record, long quantity, long spent) {
        record.filledQty = safeAdd(record.filledQty, quantity);
        record.spentGp = safeAdd(record.spentGp, spent);
        if (record.side != SELL || !record.fillTaxExact || quantity <= 0) return;
        long chunkTax = GeTaxRule.provableTax(record.itemId, spent, quantity);
        if (chunkTax < 0) {
            record.fillTaxExact = false;
            return;
        }
        record.fillTaxGp = nonNeg(safeAdd(record.fillTaxGp, chunkTax));
    }

    /**
     * The exact GE tax of a SELL's pending (filled, unsettled) execution, or {@link
     * GeTaxRule#UNPROVABLE_TAX}. Per-fill tax wins when every fill was observed at a provable
     * price, since fills at different prices pay different per-item tax; otherwise the uniform rule
     * over the pending total applies.
     */
    static long pendingSellTax(GeOffer record) {
        long execution = record.pendingExecution(), quantity = record.pendingQty();
        if (record.fillTaxExact && execution > 0 && quantity > 0) {
            long tax = record.fillTaxGp - record.settledTaxGp;
            return tax >= 0 && tax <= execution ? tax : GeTaxRule.UNPROVABLE_TAX;
        }
        return GeTaxRule.provableTax(record.itemId, execution, quantity);
    }

    // ---------------------------------------------------------------------------------------
    // Persistence / lifecycle
    // ---------------------------------------------------------------------------------------
    synchronized void writeTo(SavedState state) {
        if (state != null) state.setGeCustody(new ArrayList<>(records));
    }

    synchronized void restore(SavedState state, long now) {
        reset();
        if (state == null) return;
        // One live lifecycle per slot (its newest record); settled CLOSED history is kept as well.
        var newest = new HashMap<Integer, GeOffer>();
        var closedByOffer = new LinkedHashMap<String, GeOffer>();
        for (var record : state.getGeCustody()) {
            if (record == null || record.offerId.isEmpty()) continue;
            if (record.stage == CLOSED) closedByOffer.put(record.offerId, record);
            var existing = newest.get(record.slot);
            if (existing == null || record.placedAtEpochMillis >= existing.placedAtEpochMillis) {
                newest.put(record.slot, record);
            }
        }
        for (var record : newest.values()) closedByOffer.remove(record.offerId);
        records.addAll(newest.values());
        records.addAll(closedByOffer.values());
        records.sort((left, right) -> Long.compare(left.placedAtEpochMillis, right.placedAtEpochMillis));
    }

    /** Destructive reset: custody state is financial continuity state and clears with it. */
    synchronized void reset() {
        records.clear();
        baselines.clear();
        replacedSlots.clear();
        explainedAt.clear();
        loginClearedAtEpochMillis.clear();
        clearPendingSettlement();
    }

    /** Adopt another ledger's durable records during a staged profile swap. */
    synchronized void adoptFrom(GeCustodyLedger other) {
        reset();
        if (other != null) records.addAll(other.records);
    }

    synchronized List<GeOffer> records() {
        return unmodifiableList(new ArrayList<>(records));
    }

    /**
     * Drop any transient held collect and slot-clear evidence; durable lifecycles are untouched.
     */
    synchronized void clearPendingSettlement() {
        pendingSettlementCashGp = 0;
        pendingSettlementAtEpochMillis = 0;
        pendingSettlementHeldSinceEpochMillis = 0;
        pendingSettlementAwaitingEvidence = false;
        slotClearedAtEpochMillis.clear();
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------
    List<GeOffer> byPlacement() {
        var sorted = new ArrayList<>(records);
        sorted.sort((left, right) -> {
                    int byTime = Long.compare(left.placedAtEpochMillis, right.placedAtEpochMillis);
                    return byTime != 0 ? byTime : Integer.compare(left.slot, right.slot);
                });
        return sorted;
    }

    /** The slot's live lifecycle; settled (CLOSED) history never owns a slot. */
    GeOffer forSlot(int slot) {
        for (var record : records) {
            if (record.slot == slot && record.stage != CLOSED) return record;
        }
        return null;
    }

    GeOffer latestClosed(int slot) {
        GeOffer latest = null;
        for (var record : records) {
            if (record.slot == slot && record.stage == CLOSED && (latest == null
                            || record.placedAtEpochMillis >= latest.placedAtEpochMillis)) {
                latest = record;
            }
        }
        return latest;
    }

    GeOffer oldestClosed() {
        GeOffer first = null;
        for (var record : records) {
            if (record.stage == CLOSED && (first == null || record.placedAtEpochMillis < first.placedAtEpochMillis)) {
                first = record;
            }
        }
        return first;
    }

    /**
     * The slot's live lifecycle for an offer (any side or item when unknown), falling back to its
     * latest settled history; marks it seen.
     */
    GeOffer touch(int slot, Snapshot offer) {
        Side side = offer == null ? null : OfferSlots.sideOf(offer.state);
        int id = offer == null ? 0 : offer.itemId;
        GeOffer match = null;
        for (var record : records) {
            if (record.slot != slot || side != null && side != record.side || id > 0 && record.itemId != id) {
                continue;
            }
            if (record.stage != CLOSED) {
                match = record;
                break;
            }
            if (match == null || record.placedAtEpochMillis >= match.placedAtEpochMillis)
                match = record;
        }
        if (match != null) match.lastSeenAtEpochMillis = now;
        return match;
    }

    boolean isSameIdentity(GeOffer record, Side side, int id, long totalQty, long price) {
        return record.side == side && record.itemId == id && record.offeredQty == totalQty
                && record.listedPrice == price;
    }

    /**
     * The slot cleared but its cash or items never reached the inventory: they went to the bank.
     * The offer itself proves what filled and what it cost, so it settles as if collected. A buy is
     * exact; a sale pays its proven tax or, filled at several prices, the highest tax it could have
     * paid, so its result is never overstated. What never sold, or was never spent, went too. False
     * leaves an untrusted or unobserved lifecycle to the Review handoff.
     */
    boolean banked(GeOffer record) {
        boolean sell = record.side == SELL;
        if (!sell && record.basisSource == null && record.pendingQty() > 0) {
            // A bought item never seen has no market quote: its own execution is its basis.
            record.basisUnitPrice = record.pendingExecution() / record.pendingQty();
            record.basisSource = GRAND_EXCHANGE;
            record.basisCapturedAtEpochMillis = now;
        }
        if (sell && record.capturedQty <= 0 || !isCleanRealization(record)) return false;
        if (sell) {
            long[] net = net(record);
            if (net != null) {
                // The lowest after-tax cash it could have received, as an exact one-offer collect.
                boolean estimated = pendingSellTax(record) < 0;
                settleAttributed(singletonList(record), net[0]);
                if (estimated) bookings.get(bookings.size() - 1).explanation += msg("gbank");
            }
            long unsold = record.heldQty();
            releaseShare(record, unsold);
            record.returnedQty = safeAdd(record.returnedQty, unsold);
        } else {
            record.collectedQty = nonNeg(record.filledQty);
            bookBuySettlement(record);
            record.refundedGp = nonNeg(safeAdd(record.refundedGp, record.reserveOutstanding()));
        }
        if (record.isReconciled()) record.stage = CLOSED;
        return true;
    }

    void handoffAndRemove(GeOffer record, String reason) {
        releaseRemaining(record);
        var review = reviewHandoff(record, reason);
        if (review != null) bookings.add(review);
        records.remove(record);
    }

    Transaction reviewHandoff(GeOffer record, String reason) {
        if (record.isUnbased()) return null;
        boolean buy = record.side == BUY;
        if (!buy && record.capturedQty <= 0) {
            // The principal was never observed entering custody; its movements follow the
            // ordinary classification path and must not be surfaced twice.
            return null;
        }
        long pendingQty = buy ? record.pendingQty() : record.settleableQty(), execution = record.pendingExecution();
        if (pendingQty <= 0 && execution <= 0) return null;
        var flows = new ArrayList<Flow>(2);
        long unit = record.basisUnitPrice;
        if (pendingQty > 0) {
            long value = buy && record.collectedValueGp != 0 ? SafeMath.abs(record.collectedValueGp)
                            : record.basisFor(pendingQty);
            flows.add(itemFlow(record, buy ? pendingQty : -pendingQty, unit, buy ? value : -value));
        }
        if (execution > 0) flows.add(coins(buy ? -execution : execution));
        return reviewRow(flows, msg("grand-exchange-lifecycle") + reason + msg("known-facts"));
    }

    void captureBasis(GeOffer record, Flow flow) {
        var source = flow.getPriceSource();
        record.basisUnitPrice = nonNeg(flow.unitPrice);
        record.basisSource = source == null ? UNKNOWN : source;
        record.basisCapturedAtEpochMillis = now;
    }

    /** Presentation/audit source name; never used for matching. */
}

/**
 * Slot-keyed, observation-only record of the Grand Exchange offer state exposed by RuneLite. It
 * screens duplicate snapshots and hands each real change to custody as a previous/current pair; it
 * assigns no financial meaning to any field and never books anything.
 *
 * <p>Call {@link #beginSeed()} before processing RuneLite's login slot replay. Snapshots received
 * until {@link #finishSeed()} establish baselines and produce no transitions. On relog, starting
 * another seed clears old slot state so the replay cannot look like new activity.
 */
@Singleton
class OfferSlots {
    Map<Integer, Snapshot> bySlot = new LinkedHashMap<>();
    boolean loginSeed;

    /**
     * Begin a login/relogin baseline pass; all observations are suppressed until it is finished.
     */
    synchronized void beginSeed() {
        bySlot.clear();
        loginSeed = true;
    }

    /** Finish the baseline pass after the caller has received the initial offer-slot replay. */
    synchronized void finishSeed() {
        loginSeed = false;
    }

    /**
     * Observe one immutable slot snapshot. Repeated identical snapshots, a first empty slot and all
     * login-seed observations return an empty Optional.
     */
    synchronized Optional<Transition> observe(Snapshot current) {
        Objects.requireNonNull(current, "current");
        var previous = bySlot.put(current.slot, current);
        if (loginSeed || current.equals(previous) || current.state == GrandExchangeOfferState.EMPTY
                        && (previous == null || previous.state == GrandExchangeOfferState.EMPTY)) {
            return Optional.empty();
        }
        return Optional.of(new Transition(previous, current));
    }

    /**
     * Take an immutable point-in-time copy of the latest per-slot observations. The returned map
     * cannot be used to mutate this ledger.
     */
    synchronized Map<Integer, Snapshot> snapshots() {
        return unmodifiableMap(new LinkedHashMap<>(bySlot));
    }

    /** The offer side a state belongs to; null for an empty slot. */
    static GeOffer.Side sideOf(GrandExchangeOfferState state) {
        return state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
                        || state == GrandExchangeOfferState.CANCELLED_BUY ? GeOffer.Side.BUY
                : state == GrandExchangeOfferState.SELLING || state == GrandExchangeOfferState.SOLD
                                || state == GrandExchangeOfferState.CANCELLED_SELL ? GeOffer.Side.SELL : null;
    }

    static boolean isActive(GrandExchangeOfferState state) {
        return state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING;
    }

    static boolean isTerminal(GrandExchangeOfferState state) {
        return sideOf(state) != null && !isActive(state);
    }

    /** Immutable copy of the API fields used by the offer ledger. */
    @EqualsAndHashCode
    @AllArgsConstructor
    static class Snapshot {
        int slot;
        GrandExchangeOfferState state;
        int itemId, totalQuantity;

        /** RuneLite's quantity bought or sold so far, copied from getQuantitySold(). */
        int quantityTraded;

        long price;

        /** Raw getSpent() observation; the model does not assign sell-side or tax semantics. */
        long spent;

        static Snapshot fromOffer(int slot, GrandExchangeOffer offer) {
            return new Snapshot(slot, offer.getState(), offer.getItemId(), offer.getTotalQuantity(),
                    offer.getQuantitySold(), offer.getPrice(), offer.getSpent());
        }
    }

    /**
     * One observed slot change: the previous snapshot (null when first seen) and the current one.
     */
    @AllArgsConstructor
    static class Transition {
        Snapshot previous, current;
    }
}

/**
 * One bounded schema-104 Grand Exchange custody lifecycle: the durable minimum needed to keep exact
 * economics across restart for an offer whose placement was observed under schema 104.
 *
 * <p>Physical custody movements (placement principal, cancellation return/refund) are
 * ownership-neutral. A safely realized settlement books one counted canonical Market result,
 * exactly once, using the persisted cumulative-execution counters as the idempotency guard.
 *
 * <p>Fields are side-specific on purpose: SELL freezes item basis at placement; BUY captures item
 * basis only when the purchased quantity enters tracked ownership. No field is a second financial
 * journal.
 */
@EqualsAndHashCode(of = "offerId", callSuper = false)
@Getter
@Setter
class GeOffer extends SaveCopies {
    /** Durable offer side. */
    enum Side {
        BUY, SELL
    }

    /** Durable lifecycle stage. Behavioural projection states are derived from these facts. */
    enum Stage {
        /** Placement observed; principal may still be arriving; execution may advance. */
        OPEN,
        /** Terminal and reconciled; eligible for safe retirement. */
        CLOSED
    }

    /** Confidence in the placement/basis lineage. */
    enum CustodyConfidence {
        /** Placement and any basis were observed live under schema 104. */
        CONFIRMED,
        /**
         * Offer was already open when schema 104 first observed it; never receives invented basis.
         */
        LEGACY_UNBASED,
        /** Identity or quantity desync; no exact basis lineage. */
        AMBIGUOUS
    }

    String offerId = "";
    int slot;
    Side side = SELL;
    int itemId;
    String itemName = "";
    long offeredQty, listedPrice, placedAtEpochMillis, lastSeenAtEpochMillis;
    String originSessionId = "", offerState = "";
    Stage stage = OPEN;
    CustodyConfidence confidence = CustodyConfidence.CONFIRMED;

    /** Latest cumulative filled quantity observed from the offer counters. */
    long filledQty;

    /** Latest cumulative spent observed from the offer counters (exact execution evidence). */
    long spentGp;

    /** Filled quantity already booked into a canonical settlement. */
    long settledQty;

    /** Gross execution value already booked into a canonical settlement. */
    long settledExecutionGp;

    /** Observed settlement value already applied to this offer's settlements (0 when batch). */
    long settledCashGp;

    /**
     * Cumulative realized Market result booked for this lifecycle (derived, not a second truth).
     */
    long realizedResultGp;

    /** SELL: quantity whose basis is frozen. Never increased after the placement capture. */
    long capturedQty;

    /** SELL: quantity returned from custody on cancellation. */
    long returnedQty;

    /** BUY: observed inventory coin principal currently held by the exchange. */
    long reserveGp;

    /** BUY: observed coin principal already refunded to the inventory. */
    long refundedGp;

    /** SELL frozen basis unit price; 0 when unbased. */
    long basisUnitPrice;

    /** SELL frozen basis price source; null until a basis is captured. */
    PriceSource basisSource;

    /** SELL frozen basis capture time; 0 when unbased. */
    long basisCapturedAtEpochMillis;

    /** BUY: quantity collected into tracked ownership (basis captured at collection). */
    long collectedQty;

    /** BUY: exact value of the collected quantity (sum of observed collection valuations). */
    long collectedValueGp;

    /** Last canonical settlement transaction id linked to this offer lifecycle. */
    String settlementId = "";

    /**
     * The earlier settlement ids, space-separated: an offer collected in parts books one settlement
     * per collect, and Live and the Ledger fold all of them into its one market row (owner
     * 2026-10-09).
     */
    String earlierSettlements = "";

    /** Schema-106 pooled tracked-basis reservation captured with the SELL placement principal. */
    long reservedTrackedQty;

    long reservedTrackedBasisGp;

    /** Schema-106 realized share of the reservation consumed by collected settlements. */
    long consumedTrackedQty;

    long consumedTrackedBasisGp;

    /** True when quantity was modified without an exact per-slice basis allocation. */
    boolean quantityModified;

    /** True once the offer slot was observed EMPTY; the terminal/cancelled state is retained. */
    boolean cleared;

    /** True when a collection could not be attributed exactly to the filled quantity. */
    boolean collectionAmbiguous;

    /**
     * Schema-107 SELL: exact GE tax of every observed fill, each at its own proven unit price.
     * Meaningful only while {@link #fillTaxExact}; absent (false) on pre-107 records.
     */
    long fillTaxGp;

    /** Schema-107 SELL: true while every fill was observed at a provable per-item price. */
    boolean fillTaxExact;

    /** Schema-107 SELL: the part of {@link #fillTaxGp} already booked into settlements. */
    long settledTaxGp;

    GeOffer() {
        // Gson
    }

    GeOffer(String offerId, int slot, Side side, int id, String name, long offeredQty, long listedPrice,
            long placedAtEpochMillis, String origin) {
        this.offerId = offerId;
        this.slot = slot;
        this.side = side;
        itemId = id;
        itemName = orEmpty(name);
        this.offeredQty = nonNeg(offeredQty);
        this.listedPrice = nonNeg(listedPrice);
        this.placedAtEpochMillis = nonNeg(placedAtEpochMillis);
        originSessionId = orEmpty(origin);
    }

    /** The frozen basis price source; UNKNOWN until one is captured. */
    PriceSource getBasisSource() {
        return basisSource == null ? UNKNOWN : basisSource;
    }

    /** SELL: tracked reservation quantity still held by this lifecycle. */
    long reservedQty() {
        return nonNeg(reservedTrackedQty - consumedTrackedQty);
    }

    /** SELL: tracked reservation basis still held by this lifecycle. */
    long reservedBasis() {
        return nonNeg(reservedTrackedBasisGp - consumedTrackedBasisGp);
    }

    void setSettlementId(String value) {
        earlierSettlements = earlierSettlements + " " + settlementId;
        settlementId = orEmpty(value);
    }

    /**
     * Clamps a record read from a save once, so the accessors can read fields directly: blank text
     * and missing enums take their defaults, and no counter is negative.
     */
    void sanitize() {
        offerId = orEmpty(offerId);
        itemName = orEmpty(itemName);
        originSessionId = orEmpty(originSessionId);
        offerState = orEmpty(offerState);
        settlementId = orEmpty(settlementId);
        earlierSettlements = orEmpty(earlierSettlements);
        if (side == null) side = BUY;
        if (stage == null) stage = OPEN;
        if (confidence == null) confidence = CustodyConfidence.CONFIRMED;
        offeredQty = nonNeg(offeredQty);
        listedPrice = nonNeg(listedPrice);
        placedAtEpochMillis = nonNeg(placedAtEpochMillis);
        lastSeenAtEpochMillis = nonNeg(lastSeenAtEpochMillis);
        filledQty = nonNeg(filledQty);
        spentGp = nonNeg(spentGp);
        settledQty = nonNeg(settledQty);
        settledExecutionGp = nonNeg(settledExecutionGp);
        capturedQty = nonNeg(capturedQty);
        returnedQty = nonNeg(returnedQty);
        reserveGp = nonNeg(reserveGp);
        refundedGp = nonNeg(refundedGp);
        basisUnitPrice = nonNeg(basisUnitPrice);
        basisCapturedAtEpochMillis = nonNeg(basisCapturedAtEpochMillis);
        collectedQty = nonNeg(collectedQty);
        reservedTrackedQty = nonNeg(reservedTrackedQty);
        reservedTrackedBasisGp = nonNeg(reservedTrackedBasisGp);
        consumedTrackedQty = nonNeg(consumedTrackedQty);
        consumedTrackedBasisGp = nonNeg(consumedTrackedBasisGp);
        fillTaxGp = nonNeg(fillTaxGp);
        settledTaxGp = nonNeg(settledTaxGp);
    }

    /** A fresh basis can still be frozen at the first matched owned movement. */
    boolean hasFrozenBasis() {
        return basisSource != null && basisSource != UNPRICED && basisSource != UNKNOWN
                && basisUnitPrice > 0;
    }

    /** Filled quantity not yet booked into a canonical settlement. */
    long pendingQty() {
        return nonNeg(filledQty - settledQty);
    }

    /** Exact gross execution value of the quantity not yet booked. */
    long pendingExecution() {
        return nonNeg(spentGp - settledExecutionGp);
    }

    /** SELL: quantity still held by the exchange (captured, not returned, not filled). */
    long heldQty() {
        return nonNeg(capturedQty - returnedQty - filledQty);
    }

    /** SELL: basis value for a quantity at the frozen unit price; 0 when unbased. */
    long basisFor(long quantity) {
        long left = basisUnitPrice, right = nonNeg(quantity);
        try {
            return multiplyExact(left, right);
        } catch (ArithmeticException ex) {
            return left >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** SELL: quantity that can be settled with an exact frozen basis. */
    long settleableQty() {
        return min(pendingQty(), nonNeg(capturedQty - settledQty));
    }

    /** BUY: reserve not yet spent on fills or returned as change; zero once the offer is done. */
    long reserveOutstanding() {
        return nonNeg(reserveGp - spentGp - refundedGp);
    }

    /** True when the offer's last observed state is a terminal fill/cancellation. */
    boolean isTerminal() {
        return "SOLD".equals(offerState) || "BOUGHT".equals(offerState) || isCancelled();
    }

    /** True when the last observed state is an explicit cancellation. */
    boolean isCancelled() {
        return "CANCELLED_SELL".equals(offerState) || "CANCELLED_BUY".equals(offerState);
    }

    /** True when custody is fully reconciled and the record may be retired after a durable save. */
    boolean isReconciled() {
        if (side == SELL) {
            return pendingQty() == 0 && heldQty() == 0 && (isTerminal() || cleared);
        }
        return pendingQty() == 0 && nonNeg(filledQty - collectedQty) == 0 && reserveOutstanding() == 0
                && (isTerminal() || cleared);
    }

    /** True when no durable basis can ever be attached (legacy-open or desync). */
    boolean isUnbased() {
        return confidence == CustodyConfidence.LEGACY_UNBASED;
    }
}

/**
 * The one authoritative GE sell-tax rule shared by custody settlement and the read model.
 *
 * <p>Ordinary taxed items pay 2% of the sale price rounded DOWN per item (so sub-50 gp items pay
 * nothing), capped at 5,000,000 gp per item. The wiki's Grand Exchange tax exemption list (checked
 * 2026-09-13, ids resolved against the pinned RuneLite gameval {@code ItemID} constants) pays
 * nothing regardless of price; a real client Collect All of the owner's seven-sale batch proved the
 * exempt low-level food case (cooked chicken sold at 69 gp paid no tax). Tax is never approximated
 * as a stack-wide 2%: it rounds per ITEM. Everything is integer-only and long-safe; there is no
 * floating point here and no second exemption list anywhere else in the codebase.
 */
class GeTaxRule {
    /**
     * Items exempt from the GE convenience fee, per the wiki's Grand Exchange tax exemption list.
     * Items under 50 gp pay nothing through rounding anyway, so most tools here matter only when
     * their price spikes.
     */
    static final Set<Integer> EXEMPT_IDS = GameData.byId("tax_exempt").keySet();

    /** Sentinel: no proven per-item price exists, so no tax can be asserted for an execution. */
    static final long UNPROVABLE_TAX = -1;

    /**
     * The uniform per-item tax of one execution: {@link #UNPROVABLE_TAX} when the per-item price
     * cannot be proven (a non-exempt gross that is not divisible by the quantity), otherwise the
     * exact tax, which may legitimately be 0 (an exempt item, or a unit price below 50 gp). Each
     * item pays its price / 50 rounded down, at most 5,000,000 gp. The tax never exceeds gross /
     * 50, so it always fits a long.
     */
    static long provableTax(int id, long gross, long quantity) {
        if (gross <= 0 || quantity <= 0) return UNPROVABLE_TAX;
        if (EXEMPT_IDS.contains(id)) return 0;
        if (gross % quantity != 0) return UNPROVABLE_TAX;
        return min(5_000_000, gross / quantity / 50) * quantity;
    }

    /**
     * The proven sell tax of one settled execution: the exact positive gap between the observed
     * gross execution and the observed Received cash, accepted only when it equals the uniform
     * per-item rule for that execution. Zero means nothing is proven — a genuinely tax-free stack,
     * an exempt item, a non-uniform execution, or a gap that does not match the rule.
     *
     * <p>Booking and the read model share this one predicate, so a booked tax can never disagree
     * with the receipt that explains it. It is a tax proof, not a result: it never implies the
     * prior tracked value of the sold units.
     */
    static long acceptedTax(int id, long gross, long quantity, long receivedGp) {
        long tax = provableTax(id, gross, quantity);
        return tax > 0 && gross - receivedGp == tax ? tax : 0;
    }
}
