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

import lombok.*;

import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;

import java.util.*;
import java.util.function.*;

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
    synchronized void setBasisLedger(TrackedBasisLedger ledger) {
        basisLedger = ledger;
    }

    /** Latest capture time of an open SELL reservation for the item; 0 when none is open. */
    synchronized long latestReservationTime(int itemId) {
        int canonicalId = itemId;
        long latest = 0L;
        for (GeOffer record : records) {
            if (record == null || record.getSide() != SELL || record.itemId != canonicalId) {
                continue;
            }
            if (record.reservedQty() <= 0L && record.reservedBasis() <= 0L) continue;
            long capturedAt =
                    record.getBasisCapturedAtEpochMillis() > 0L
                            ? record.getBasisCapturedAtEpochMillis()
                            : record.getPlacedAtEpochMillis();
            latest = max(latest, capturedAt);
        }
        return latest;
    }

    /** Immutable result of one settle: residual flows still needing normal classification. */
    @AllArgsConstructor
    static class Partition {
        List<Flow> residualFlows;
        List<Transaction> countedBookings;
        List<Transaction> transferBookings;

        /** Physical flows consumed by custody claims; used to spend matching offer evidence. */
        List<Flow> claimedFlows;

        Transaction lastBooking() {
            List<Transaction> bookings =
                    countedBookings.isEmpty() ? transferBookings : countedBookings;
            return bookings.isEmpty() ? null : bookings.get(bookings.size() - 1);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle observation
    // ---------------------------------------------------------------------------------------
    /** Apply one offer-ledger transition; returns any Review handoff or settled rows it forced. */
    synchronized List<Transaction> observeTransition(
            OfferSlots.Transition transition, String itemName, long now, String sessionId) {
        if (transition == null || transition.current == null) return Collections.emptyList();
        var handoffs = new ArrayList<Transaction>();
        observe(transition.previous, transition.current, itemName, now, sessionId, handoffs);
        // New execution/lifecycle evidence may complete a settlement whose cash was observed
        // first; the held batch settles as soon as it is exactly attributable.
        reconcilePendingSettlement(now, sessionId, handoffs);
        return handoffs;
    }

    /**
     * Applies one slot snapshot against the slot's baseline: exact incremental fills from the
     * cumulative counters, Modify Offer, slot reuse and identity replacement. Backwards counters,
     * value moved without quantity, or a missed placement are a desync, never a fill.
     */
    void observe(
            Snapshot previous,
            Snapshot current,
            String itemName,
            long now,
            String sessionId,
            List<Transaction> handoffs) {
        int slot = current.slot;
        Side side = OfferSlots.sideOf(current.state);
        Snapshot base = baselines.remove(slot);
        if (side == null) {
            GeOffer record = touch(slot, base != null ? base : previous, now);
            if (record != null) {
                record.setCleared(true);
                // Transient slot evidence: proves which offer a nearby collect belongs to.
                slotClearedAtEpochMillis.put(record.getOfferId(), now);
            }
            return;
        }
        GeOffer replay = base == null ? lateLoginReplay(slot, side, current, now) : null;
        if (replay != null) {
            loginClearedAtEpochMillis.remove(replay.getOfferId());
            slotClearedAtEpochMillis.remove(replay.getOfferId());
            replay.setCleared(false);
            resume(replay, current, now);
            baselines.put(slot, current);
            return;
        }
        boolean active = OfferSlots.isActive(current.state);
        if (base == null
                || active
                        && (OfferSlots.sideOf(base.state) != side
                                || base.itemId != current.itemId)) {
            if (base != null) {
                // Item/side changed mid-life without Modify evidence: retire the old lifecycle and
                // quarantine the next; never inherit prior basis or progress.
                GeOffer old = touch(slot, base, now);
                if (old != null) {
                    old.setConfidence(CustodyConfidence.AMBIGUOUS);
                    handoffAndRemove(old, now, sessionId, msg("offer-identity"), handoffs);
                }
                replacedSlots.add(slot);
            }
            baselines.put(slot, current);
            if (base == null
                    && previous != null
                    && previous.state != GrandExchangeOfferState.EMPTY) {
                // A missed placement cannot be dated: quarantine the lifecycle, emit no fill.
                desync(slot, current, current, itemName, now, sessionId);
                return;
            }
            place(slot, side, current, itemName, now, sessionId, handoffs);
            advance(slot, current, current, current.quantityTraded, current.spent, now);
            return;
        }
        if (active
                && (base.totalQuantity != current.totalQuantity || base.price != current.price)) {
            // A price-only Modify keeps the lifecycle and its basis; a quantity change has no exact
            // per-slice basis allocation, so realization fails closed to Review.
            boolean priceOnly = base.totalQuantity == current.totalQuantity;
            baselines.put(
                    slot,
                    new Snapshot(
                            slot,
                            base.state,
                            base.itemId,
                            current.totalQuantity,
                            base.quantityTraded,
                            priceOnly ? current.price : base.price,
                            base.spent));
            GeOffer record = touch(slot, base, now);
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
        baselines.put(
                slot,
                new Snapshot(
                        slot,
                        base.state,
                        base.itemId,
                        base.totalQuantity,
                        current.quantityTraded,
                        base.price,
                        current.spent));
        if (quantity < 0L || spent < 0L || quantity == 0L && spent > 0L) {
            desync(slot, base, current, itemName, now, sessionId);
            return;
        }
        advance(slot, base, current, quantity, spent, now);
    }

    /** A new offer in the slot: a duplicate replay of the live lifecycle changes nothing. */
    void place(
            int slot,
            Side side,
            Snapshot offer,
            String itemName,
            long now,
            String sessionId,
            List<Transaction> handoffs) {
        GeOffer previous = forSlot(slot);
        if (previous != null
                && isSameIdentity(previous, side, offer.itemId, offer.totalQuantity, offer.price)
                && !previous.cleared
                && !previous.isTerminal()) {
            previous.setLastSeenAtEpochMillis(now);
            return;
        }
        if (previous != null && previous.getStage() == OPEN && previous.isReconciled()) {
            // Settled but not yet sealed (a settlement completed by an offer transition):
            // seal it exactly as the next settle would, so it stays as history.
            previous.setStage(CLOSED);
            previous = null;
        }
        if (previous != null
                && previous.cleared
                && previous.isTerminal()
                && trusted(previous, previous.getSide())) {
            // Owner 1.2: a finished offer collected moments before its slot is reused still waits
            // for
            // its items or coins to settle; it gives up the slot instead of going to Review.
            previous.slot = -1;
            previous = null;
        }
        if (previous != null) {
            // Slot reuse: a new offer never inherits prior basis or progress.
            handoffAndRemove(previous, now, sessionId, msg("slot-reused"), handoffs);
        }
        // A settled (CLOSED) lifecycle in this slot stays as bounded market history for its
        // booked sale; only its unrealized reservation leaves with the slot.
        releaseRemaining(latestClosed(slot));
        GeOffer record =
                newRecord(
                        slot,
                        side,
                        offer.itemId,
                        itemName,
                        offer.totalQuantity,
                        offer.price,
                        offer.state.name(),
                        now,
                        sessionId);
        if (replacedSlots.remove(slot)) record.setConfidence(CustodyConfidence.AMBIGUOUS);
        // Per-fill tax is exact only for a placement observed live from an empty slot.
        record.setFillTaxExact(
                side == SELL && record.getConfidence() == CustodyConfidence.CONFIRMED);
        records.add(record);
    }

    /** Exact fill progress, then the terminal state (a final fill lands before a cancellation). */
    void advance(int slot, Snapshot offer, Snapshot current, long quantity, long spent, long now) {
        boolean fill = quantity > 0L && spent >= 0L;
        boolean terminal = OfferSlots.isTerminal(current.state);
        GeOffer record = fill || terminal ? touch(slot, offer, now) : null;
        if (record != null && fill) recordFill(record, quantity, spent);
        if (record != null && terminal) record.setOfferState(current.state.name());
    }

    /** Counters with no exact lineage: quarantine the lifecycle, or start an unbased one. */
    void desync(
            int slot,
            Snapshot offer,
            Snapshot current,
            String itemName,
            long now,
            String sessionId) {
        GeOffer record = touch(slot, offer, now);
        if (record != null) {
            record.setConfidence(CustodyConfidence.AMBIGUOUS);
            record.setFillTaxExact(false);
        } else if (offer.itemId > 0) {
            record =
                    newRecord(
                            slot,
                            OfferSlots.sideOf(offer.state),
                            offer.itemId,
                            itemName,
                            offer.totalQuantity,
                            offer.price,
                            current.state.name(),
                            now,
                            sessionId);
            record.setConfidence(CustodyConfidence.LEGACY_UNBASED);
            records.add(record);
        }
    }

    /**
     * The live lifecycle the login seed cleared in this slot, when this snapshot is that same
     * offer.
     */
    GeOffer lateLoginReplay(int slot, Side side, Snapshot current, long now) {
        GeOffer record = forSlot(slot);
        Long clearedAt = record == null ? null : loginClearedAtEpochMillis.get(record.getOfferId());
        return clearedAt != null
                        && now - clearedAt <= LOGIN_REPLAY_MILLIS
                        && isSameIdentity(
                                record, side, current.itemId, current.totalQuantity, current.price)
                ? record
                : null;
    }

    /**
     * Resume a persisted lifecycle; progress made while away is adopted once, never as a fill. Its
     * confidence stays as it was: a relog never makes a quarantined or unbased offer trusted.
     */
    void resume(GeOffer existing, Snapshot snapshot, long now) {
        existing.setLastSeenAtEpochMillis(now);
        existing.setOfferState(snapshot.state.name());
        if (snapshot.quantityTraded != existing.getFilledQty()) {
            // Fills while logged out were never observed at their own prices.
            existing.setFillTaxExact(false);
        }
        existing.setFilledQty(snapshot.quantityTraded);
        existing.setSpentGp(nonNeg(snapshot.spent));
    }

    GeOffer newRecord(
            int slot,
            Side side,
            int itemId,
            String itemName,
            long totalQty,
            long price,
            String state,
            long now,
            String sessionId) {
        var record =
                new GeOffer(
                        UUID.randomUUID().toString(),
                        slot,
                        side,
                        itemId,
                        itemName,
                        totalQty,
                        price,
                        now,
                        Phrases.orEmpty(sessionId));
        record.setLastSeenAtEpochMillis(now);
        record.setOfferState(state);
        return record;
    }

    /**
     * Record the login slot baseline: resume a persisted matching lifecycle, quarantine active
     * offers first seen now as legacy/unbased, and hand off records whose slot changed identity.
     */
    synchronized List<Transaction> seedSlots(
            Map<Integer, Snapshot> snapshots, long now, String sessionId) {
        var handoffs = new ArrayList<Transaction>();
        baselines.clear();
        loginClearedAtEpochMillis.clear();
        if (snapshots != null) {
            for (Snapshot snapshot : snapshots.values()) {
                if (snapshot == null) continue;
                GeOffer existing = forSlot(snapshot.slot);
                if (snapshot.state == GrandExchangeOfferState.EMPTY) {
                    if (existing != null) loginClearedAtEpochMillis.put(existing.getOfferId(), now);
                    else existing = latestClosed(snapshot.slot);
                    if (existing != null) {
                        existing.setCleared(true);
                        existing.setLastSeenAtEpochMillis(now);
                        slotClearedAtEpochMillis.put(existing.getOfferId(), now);
                    }
                    continue;
                }
                Side side = OfferSlots.sideOf(snapshot.state);
                if (side == null) continue;
                // Pre-existing progress is only a baseline, never replayed as a fill.
                baselines.put(snapshot.slot, snapshot);
                if (existing != null
                        && isSameIdentity(
                                existing,
                                side,
                                snapshot.itemId,
                                snapshot.totalQuantity,
                                snapshot.price)) {
                    resume(existing, snapshot, now);
                    continue;
                }
                if (existing != null) {
                    handoffAndRemove(existing, now, sessionId, msg("offer-changed"), handoffs);
                }
                GeOffer record =
                        newRecord(
                                snapshot.slot,
                                side,
                                snapshot.itemId,
                                "",
                                snapshot.totalQuantity,
                                snapshot.price,
                                snapshot.state.name(),
                                now,
                                sessionId);
                record.setConfidence(CustodyConfidence.LEGACY_UNBASED);
                record.setFilledQty(snapshot.quantityTraded);
                record.setSpentGp(nonNeg(snapshot.spent));
                records.add(record);
            }
        }
        return handoffs;
    }

    /**
     * True when every moved flow is explained by a recent offer: an item or coins lost shortly
     * after a sell or buy was placed, an item or coins gained after a buy or sell filled, or the
     * other side's cancellation. Custody is authoritative for GE economics, so a movement it could
     * not claim but an offer explains is Grand Exchange activity for Review, never counted.
     */
    synchronized boolean explains(List<Flow> flows, long now) {
        boolean moved = false;
        for (Flow flow : flows) {
            if (flow != null && flow.quantityDelta != 0L) {
                moved = true;
                if (explaining(flow, now) == null) return false;
            }
        }
        return moved;
    }

    /** Spends the offer evidence behind these movements: one offer side explains one movement. */
    synchronized void spend(List<Flow> flows, long now) {
        for (Flow flow : flows) {
            String key = flow == null || flow.quantityDelta == 0L ? null : explaining(flow, now);
            if (key != null) explainedAt.put(key, now);
        }
    }

    String explaining(Flow flow, long now) {
        boolean coins = flow.itemId == ItemID.COINS;
        for (GeOffer record : records) {
            boolean buy = record.getSide() == BUY;
            String key = record.getOfferId() + (coins ? "$" : "");
            Long spent = explainedAt.get(key);
            long seen = record.getLastSeenAtEpochMillis();
            // Only a live placement moves coins (buy) or items (sell) out of the inventory; a
            // finished
            // fill of the same side or a cancellation of the other brings them back. Passive
            // progress on an open offer explains nothing.
            boolean fits =
                    flow.isCost()
                            ? buy == coins
                                    && spent == null
                                    && !record.isUnbased()
                                    && !record.isCancelled()
                                    && now - record.getPlacedAtEpochMillis()
                                            <= PLACEMENT_WINDOW_MILLIS
                            : (spent == null || spent < seen)
                                    && now - seen <= COLLECTION_WINDOW_MILLIS
                                    && (buy != coins
                                            ? record.getFilledQty() > 0L && record.isTerminal()
                                            : record.isCancelled());
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
    synchronized Partition partition(
            List<Flow> incoming,
            long now,
            String sessionId,
            IntPredicate itemAllowed,
            boolean collectionEvidence) {
        IntPredicate allowed = itemAllowed == null ? itemId -> true : itemAllowed;
        var counted = new ArrayList<Transaction>();
        var transfers = new ArrayList<Transaction>();
        var residual = new ArrayList<Flow>(incoming == null ? Collections.emptyList() : incoming);
        var claimed = new ArrayList<Flow>();
        // A settlement held from an earlier collect may now be exactly attributable.
        reconcilePendingSettlement(now, sessionId, counted);
        claimSellPlacements(residual, claimed, now, allowed);
        claimBuyReserves(residual, claimed, now);
        claimBuyRefunds(residual, claimed);
        claimSellReturns(residual, claimed, allowed);
        claimBuyCollections(
                residual, claimed, now, sessionId, counted, allowed, collectionEvidence);
        claimSellSettlements(residual, claimed, now, sessionId, counted, collectionEvidence);
        if (!claimed.isEmpty()) {
            transfers.add(
                    new Transaction(
                            now,
                            TransactionType.TRANSFER,
                            Context.TRANSFER,
                            msg("grand-exchange-custody"),
                            "Market",
                            false,
                            new ArrayList<>(claimed),
                            Confidence.CONFIRMED,
                            msg("ownership-neutral-grand"),
                            null));
        }
        // A hold created by this settle retries immediately: a batch whose evidence is complete
        // fails closed at once instead of waiting invisibly for a later event.
        reconcilePendingSettlement(now, sessionId, counted);
        for (GeOffer open : records) {
            if (open != null && open.getStage() == OPEN && open.isReconciled())
                open.setStage(CLOSED);
        }
        return new Partition(residual, counted, transfers, claimed);
    }

    /** Prune reconciled lifecycles, expire terminal-unobserved ones and cap stale records. */
    synchronized Partition maintenance(long now, String sessionId) {
        var counted = new ArrayList<Transaction>();
        prune(now, sessionId, counted);
        // A held settlement retries and, once its evidence is exhausted or its bounded
        // lifecycle expires, fails closed to Review with the observed cash preserved.
        reconcilePendingSettlement(now, sessionId, counted);
        return new Partition(
                Collections.emptyList(), counted, Collections.emptyList(), Collections.emptyList());
    }

    void prune(long now, String sessionId, List<Transaction> reviews) {
        int closed = 0;
        for (GeOffer record : records) {
            if (record != null && record.getStage() == CLOSED) closed++;
        }
        // Settled history is bounded: the oldest closed lifecycles leave first.
        for (; closed > MAX_CLOSED_RECORDS; closed--) {
            GeOffer oldest = oldestClosed();
            releaseRemaining(oldest);
            records.remove(oldest);
        }
        for (int index = records.size() - 1; index >= 0; index--) {
            GeOffer record = records.get(index);
            if (record == null || record.getOfferId().isEmpty()) {
                releaseRemaining(record);
                records.remove(index);
                continue;
            }
            if (record.getStage() == CLOSED) continue;
            if (record.isReconciled()) {
                record.setStage(CLOSED);
                continue;
            }
            // Expire by grace only a slot that is CLEARED and whose cash was never seen (a
            // collect-to-bank and similar). A SELL whose slot still shows the terminal state is
            // still in GE custody: its coins may be collected much later, so it must not expire by
            // time. BUY keeps its existing pattern (reported, not changed).
            boolean cleared = record.cleared;
            // Owner 2026-10-09: Collect to bank (or a collect while GP Manager was away) settles
            // from
            // the offer itself instead of waiting for Review; held collect cash may still be its
            // own.
            if (cleared
                    && now - record.getLastSeenAtEpochMillis() > BANK_COLLECT_MILLIS
                    && pendingSettlementCashGp <= 0L
                    && banked(record, now, sessionId, reviews)) {
                continue;
            }
            boolean buyTerminal = record.getSide() != SELL && record.isTerminal();
            if ((cleared || buyTerminal)
                    && now - record.getLastSeenAtEpochMillis() > UNOBSERVED_GRACE_MILLIS) {
                handoffAndRemove(record, now, sessionId, msg("settlement-observed"), reviews);
            }
        }
        slotClearedAtEpochMillis.values().removeIf(at -> now - at > UNOBSERVED_GRACE_MILLIS);
        // Spent offer evidence goes with its offer.
        var live = new HashSet<String>();
        for (GeOffer record : records) live.add(record.getOfferId());
        explainedAt.keySet().removeIf(key -> !live.contains(key.replace("$", "")));
    }

    // -- Custody claims: each settle offers its flows to the lifecycles, oldest placement first --
    /** One lifecycle's claim on a flow: the magnitude it takes, or 0 to pass. */
    interface ClaimStep {
        long claim(GeOffer record, Flow flow, long remaining);
    }

    void claimFlows(List<Flow> residual, Predicate<Flow> which, ClaimStep step) {
        FlowFilters.claim(
                residual,
                which,
                (flow, magnitude) -> {
                    long remaining = magnitude;
                    for (GeOffer record : byPlacement()) {
                        if (remaining > 0L)
                            remaining -= nonNeg(step.claim(record, flow, remaining));
                    }
                    return magnitude - remaining;
                });
    }

    /** A lifecycle observed from its placement with no identity desync. */
    static boolean trusted(GeOffer record, Side side) {
        return record.getSide() == side
                && !record.isUnbased()
                && record.getConfidence() != CustodyConfidence.AMBIGUOUS;
    }

    /** SELL placement principal (item loss). */
    void claimSellPlacements(
            List<Flow> residual, List<Flow> claimed, long now, IntPredicate itemAllowed) {
        claimFlows(
                residual,
                flow ->
                        flow.isCost()
                                && flow.itemId != ItemID.COINS
                                && itemAllowed.test(flow.itemId),
                (record, flow, remaining) -> {
                    long claim = min(remaining, record.getOfferedQty());
                    if (!trusted(record, SELL)
                            || record.itemId != flow.itemId
                            || record.isCancelled()
                            || record.cleared
                            || record.getCapturedQty() > 0L
                            || now - record.getPlacedAtEpochMillis() > PLACEMENT_WINDOW_MILLIS
                            || claim <= 0L) {
                        return 0L;
                    }
                    captureBasis(record, flow, now);
                    if (basisLedger != null) {
                        TrackedBasisLedger.Reservation reservation =
                                basisLedger.reserve(record.itemId, claim, now);
                        if (!reservation.isEmpty()) {
                            record.reservedTrackedQty =
                                    nonNeg(
                                            safeAdd(
                                                    record.getReservedTrackedQty(),
                                                    reservation.trackedQty));
                            record.reservedTrackedBasisGp =
                                    nonNeg(
                                            safeAdd(
                                                    record.getReservedTrackedBasisGp(),
                                                    reservation.basisGp));
                        }
                    }
                    record.setCapturedQty(claim);
                    record.setLastSeenAtEpochMillis(now);
                    claimed.add(flow.part(claim));
                    return claim;
                });
    }

    /** BUY observed reserve (coin loss). */
    void claimBuyReserves(List<Flow> residual, List<Flow> claimed, long now) {
        Set<Integer> gainItems = gainQuantities(residual, ANY).keySet();
        claimFlows(
                residual,
                flow -> flow.isCost() && flow.itemId == ItemID.COINS,
                (record, flow, remaining) -> {
                    long reserve = nonNeg(record.getOfferedQty() * record.getListedPrice());
                    // Owner 1.2: an offer placed, bought and collected inside one settle shows only
                    // its exact
                    // spend, the reserve with its change already back; the purchase books it, never
                    // Review.
                    boolean instant =
                            gainItems.contains(record.itemId)
                                    && record.isTerminal()
                                    && remaining == record.getSpentGp();
                    long claim = instant ? remaining : min(remaining, reserve);
                    // A same-item acquisition alongside the coin loss is a purchase, not a reserve:
                    // leave the mixed movement to the normal classification path.
                    // An offer that filled at once still took these coins; only a cancelled one
                    // kept none.
                    if (!trusted(record, BUY)
                            || record.isCancelled()
                            || record.cleared && !instant
                            || record.getReserveGp() > 0L
                            || now - record.getPlacedAtEpochMillis() > PLACEMENT_WINDOW_MILLIS
                            || !instant && gainItems.contains(record.itemId)
                            || claim <= 0L) {
                        return 0L;
                    }
                    record.reserveGp = instant ? reserve : nonNeg(claim);
                    if (instant) record.refundedGp = nonNeg(reserve - claim);
                    record.setLastSeenAtEpochMillis(now);
                    claimed.add(flow.part(claim, 1, FACE_VALUE));
                    return claim;
                });
    }

    /** BUY refund (coin gain). */
    void claimBuyRefunds(List<Flow> residual, List<Flow> claimed) {
        claimFlows(
                residual,
                flow -> flow.isGain() && flow.itemId == ItemID.COINS,
                (record, flow, remaining) -> {
                    long refundRoom =
                            nonNeg(
                                            record.getOfferedQty() * record.getListedPrice()
                                                    - record.getSpentGp())
                                    - record.getRefundedGp();
                    long claim =
                            min(remaining, min(nonNeg(refundRoom), record.reserveOutstanding()));
                    // Change is owed once the offer is finished (bought or cancelled) or its slot
                    // cleared.
                    if (!trusted(record, BUY)
                            || !(record.isTerminal() || record.cleared)
                            || claim <= 0L) {
                        return 0L;
                    }
                    record.refundedGp = nonNeg(safeAdd(record.getRefundedGp(), claim));
                    claimed.add(flow.part(claim, 1, FACE_VALUE));
                    return claim;
                });
    }

    /** SELL cancellation return (item gain). */
    void claimSellReturns(List<Flow> residual, List<Flow> claimed, IntPredicate itemAllowed) {
        claimFlows(
                residual,
                flow ->
                        flow.isGain()
                                && flow.itemId != ItemID.COINS
                                && itemAllowed.test(flow.itemId),
                (record, flow, remaining) -> {
                    long claim = min(remaining, record.heldQty());
                    if (!trusted(record, SELL)
                            || record.itemId != flow.itemId
                            || !(record.isCancelled() || record.cleared)
                            || claim <= 0L) {
                        return 0L;
                    }
                    // The returned principal is neutral at its frozen custody basis, never the
                    // current live quote; with no frozen basis it stays the observed movement.
                    releaseShare(record, claim);
                    record.setReturnedQty(safeAdd(record.getReturnedQty(), claim));
                    claimed.add(
                            record.getBasisUnitPrice() > 0L
                                    ? flow.part(
                                            claim,
                                            record.getBasisUnitPrice(),
                                            record.getBasisSource())
                                    : flow.part(claim));
                    return claim;
                });
    }

    /** BUY collection (item gain). */
    void claimBuyCollections(
            List<Flow> residual,
            List<Flow> claimed,
            long now,
            String sessionId,
            List<Transaction> counted,
            IntPredicate itemAllowed,
            boolean collectionEvidence) {
        claimFlows(
                residual,
                flow ->
                        flow.isGain()
                                && flow.itemId != ItemID.COINS
                                && itemAllowed.test(flow.itemId),
                (record, flow, remaining) -> {
                    long claim = min(remaining, record.pendingQty());
                    // Passive progress alone never turns a same-item delta into a collection.
                    if (!trusted(record, BUY)
                            || record.itemId != flow.itemId
                            || record.quantityModified
                            || claim <= 0L
                            || !(collectionEvidence || record.isTerminal() || record.cleared)) {
                        return 0L;
                    }
                    if (record.basisSource == null) captureBasis(record, flow, now);
                    record.collectedQty = nonNeg(safeAdd(record.getCollectedQty(), claim));
                    record.setCollectedValueGp(
                            safeAdd(
                                    record.collectedValueGp,
                                    SafeMath.safeMultiply(claim, flow.unitPrice)));
                    record.setLastSeenAtEpochMillis(now);
                    claimed.add(flow.part(claim));
                    if (record.getCollectedQty() < record.getFilledQty()) {
                        // Partial collection: execution chunks are not attributable; fail closed.
                        record.setCollectionAmbiguous(true);
                    } else {
                        bookBuySettlement(record, now, sessionId, counted);
                    }
                    return claim;
                });
    }

    void bookBuySettlement(GeOffer record, long now, String sessionId, List<Transaction> counted) {
        long settleQty = record.getCollectedQty() - record.getSettledQty();
        long execution = record.pendingExecution();
        if (settleQty <= 0L) return;
        boolean clean = isCleanRealization(record, sessionId);
        var flows = new ArrayList<Flow>(2);
        // Exact NEW-model BUY: the asset value equals the actual settled spend, so acquisition is
        // Net-neutral and the acquired quantity becomes known coverage at that exact spend.
        flows.add(itemFlow(record, settleQty, execution / settleQty, execution));
        flows.add(coins(-execution, now));
        Transaction transaction =
                buildMarket(flows, now, clean, clean, msg("grand-exchange-purchase"));
        counted.add(transaction);
        record.setSettledQty(safeAdd(record.getSettledQty(), settleQty));
        record.setSettledExecutionGp(safeAdd(record.getSettledExecutionGp(), execution));
        record.setSettledCashGp(safeAdd(record.settledCashGp, execution));
        record.setSettlementId(transaction.getId());
        record.collectedQty = nonNeg(record.getSettledQty());
        record.setCollectedValueGp(0L);
    }

    static Flow coins(long amount, long now) {
        return new Flow(ItemID.COINS, "Coins", amount, 1, amount, FACE_VALUE, now);
    }

    /** One flow of the offer's item, carrying its frozen basis source and capture time. */
    static Flow itemFlow(GeOffer record, long quantity, long unitPrice, long value) {
        return new Flow(
                record.itemId,
                record.getItemName(),
                quantity,
                toInt(unitPrice),
                value,
                record.getBasisSource(),
                record.getBasisCapturedAtEpochMillis());
    }

    /** An uncounted Market row left for Review: the observed movement, never a guess. */
    static Transaction reviewRow(long now, List<Flow> flows, String explanation) {
        return new Transaction(
                now,
                UNCERTAIN,
                MARKET,
                "Grand Exchange",
                "Market",
                false,
                flows,
                Confidence.UNCERTAIN,
                explanation,
                null);
    }

    /** Claims a whole coin gain as held or settled GE cash. */
    static long claimWhole(Flow flow, List<Flow> claimed) {
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
    void claimSellSettlements(
            List<Flow> residual,
            List<Flow> claimed,
            long now,
            String sessionId,
            List<Transaction> counted,
            boolean collectionEvidence) {
        if (!collectionEvidence) {
            // A coin gain with no Collect interaction settles a waiting sale only when it is
            // that whole sale, less at most its tax.
            claimSellsWithoutCollect(residual, claimed, now, sessionId, counted);
            return;
        }
        FlowFilters.claim(
                residual,
                flow -> flow.isGain() && flow.itemId == ItemID.COINS,
                (flow, magnitude) -> {
                    long cashGp = flow.valueDelta;
                    List<GeOffer> candidates = settlementCandidates(true);
                    if (candidates.isEmpty()) {
                        // Coins may arrive before the offer's execution/terminal evidence. A live
                        // Collect
                        // interaction with a captured sell lifecycle still pending is that
                        // settlement's
                        // cash; retain it internally instead of letting an unresolved Coins row
                        // reach
                        // Review. The bounded hold retries as offer evidence arrives.
                        if (cashGp <= 0L || !hasPendingSellEvidence()) return 0L;
                        holdCash(cashGp, now);
                        pendingSettlementAwaitingEvidence = true;
                        return claimWhole(flow, claimed);
                    }
                    Attribution attribution = attribute(candidates, cashGp, now);
                    if (attribution.kind == AttributionKind.SETTLE) {
                        settleAttributed(attribution.records, cashGp, now, sessionId, counted);
                        return claimWhole(flow, claimed);
                    }
                    if (!attribution.contradicted) {
                        // Exact-but-unconfirmed, tied, or unmatched evidence: retain the observed
                        // cash
                        // transiently. The SLOT WINDOW gathers the slot evidence a fail-closed
                        // decision
                        // needs; while a sell can still receive execution evidence, the existing
                        // long hold
                        // keeps waiting for it.
                        holdCash(cashGp, now);
                        pendingSettlementAwaitingEvidence |=
                                attribution.kind == AttributionKind.NONE
                                        && hasPendingSellEvidence();
                        return claimWhole(flow, claimed);
                    }
                    // No exact attribution: only candidates with slot evidence in this interaction
                    // may be
                    // quarantined. The unattributed movement follows the ordinary path to one
                    // uncounted
                    // Review Coins row; every other candidate stays pending for a later exact
                    // collect.
                    quarantineAmbiguous(attribution.quarantinable, now, sessionId, counted);
                    return 0L;
                });
    }

    /**
     * Booking for a coin gain with no Collect interaction (the click fell outside its window): only
     * a gain between the whole waiting sale and that sale less its largest possible tax settles it.
     */
    void claimSellsWithoutCollect(
            List<Flow> residual,
            List<Flow> claimed,
            long now,
            String sessionId,
            List<Transaction> counted) {
        FlowFilters.claim(
                residual,
                flow -> flow.isGain() && flow.itemId == ItemID.COINS,
                (flow, magnitude) -> {
                    List<GeOffer> candidates = settlementCandidates(false);
                    long totalExecution = 0L;
                    for (GeOffer record : candidates)
                        totalExecution = safeAdd(totalExecution, record.pendingExecution());
                    long original = flow.valueDelta;
                    // Without a Collect click only the whole waiting sale, less at most its GE
                    // tax (2%), can explain the coins: an alch or a shop sale never claims it.
                    if (original > totalExecution
                            || original < totalExecution - totalExecution / 50) return 0L;
                    long claimCash = min(original, totalExecution);
                    if (claimCash <= 0L) return 0L;
                    if (candidates.size() > 1) {
                        boolean allClean = true;
                        boolean allKnownCoverage = true;
                        long expectedTax = 0L;
                        for (GeOffer record : candidates) {
                            allClean &= isCleanRealization(record, sessionId);
                            allKnownCoverage &= record.reservedQty() >= record.pendingQty();
                            // Candidate fee only: the aggregate observed cash must confirm the
                            // exact combined gap.
                            expectedTax = safeAdd(expectedTax, nonNeg(pendingSellTax(record)));
                        }
                        boolean taxAttributed =
                                expectedTax > 0L && totalExecution - claimCash == expectedTax;
                        if (!taxAttributed
                                && (expectedTax > 0L || claimCash != totalExecution)
                                && !allKnownCoverage) {
                            allClean = false;
                            for (GeOffer record : candidates) record.setCollectionAmbiguous(true);
                        }
                        // An exact batch books each offer's after-tax share of the observed cash;
                        // any
                        // other batch books gross execution and an adjustment row for the
                        // difference.
                        long allocated = 0L;
                        for (GeOffer record : candidates) {
                            long settleQty = record.pendingQty();
                            long execution = record.pendingExecution();
                            if (settleQty > 0L && execution > 0L) {
                                long cash =
                                        taxAttributed
                                                ? execution - nonNeg(pendingSellTax(record))
                                                : execution;
                                bookSellSettlement(
                                        record, settleQty, cash, execution, now, sessionId,
                                        allClean, counted);
                                allocated = safeAdd(allocated, cash);
                            }
                        }
                        if (claimCash != allocated) {
                            // Legacy (non-collect) batch adjustment row; collect interactions never
                            // produce one.
                            counted.add(
                                    new Transaction(
                                            now,
                                            allClean ? TRADE : UNCERTAIN,
                                            MARKET,
                                            msg("grand-exchange-settlement"),
                                            "Market",
                                            allClean,
                                            singletonList(coins(claimCash - allocated, now)),
                                            allClean ? Confidence.CONFIRMED : Confidence.UNCERTAIN,
                                            msg("grand-exchange-batch"),
                                            null));
                        }
                    } else {
                        GeOffer record = candidates.get(0);
                        long execution = record.pendingExecution();
                        boolean over = original > execution;
                        record.setCollectionAmbiguous(record.collectionAmbiguous || over);
                        bookSellSettlement(
                                record,
                                record.pendingQty(),
                                over ? execution : claimCash,
                                execution,
                                now,
                                sessionId,
                                !over && isCleanRealization(record, sessionId),
                                counted);
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
    void settleAttributed(
            List<GeOffer> records,
            long cash,
            long now,
            String sessionId,
            List<Transaction> counted) {
        boolean allClean = true;
        for (GeOffer record : records) {
            allClean &= isCleanRealization(record, sessionId);
            long[] net = net(record);
            if (net != null) cash -= net[1];
        }
        // The tax beyond every offer's lowest possible tax.
        cash = -cash;
        for (GeOffer record : records) {
            long[] net = net(record);
            // An offer with no execution is never part of a match; the attribution excluded it.
            if (net == null) continue;
            long execution = record.pendingExecution();
            long extra = max(0L, min(net[1] - net[0], cash));
            cash -= extra;
            long tax = execution - net[1] + extra;
            if (pendingSellTax(record) < 0L) {
                // The cash proved it: later reads (the receipt, a restart) see this exact tax.
                record.fillTaxExact = true;
                record.fillTaxGp = safeAdd(record.getSettledTaxGp(), tax);
            }
            bookSellSettlement(
                    record,
                    record.pendingQty(),
                    execution - tax,
                    execution,
                    now,
                    sessionId,
                    allClean,
                    counted);
        }
    }

    /**
     * The pending execution's possible after-tax cash {lowest, highest}, or null with no execution.
     * Exact when its tax is proven; otherwise each item paid price/50 rounded down, so the stack
     * paid at most gross/50 and at least (gross - 49 per item)/50 (below the 5M per-item cap).
     */
    static long[] net(GeOffer record) {
        long execution = record.pendingExecution();
        long quantity = record.pendingQty();
        long tax = pendingSellTax(record);
        if (execution <= 0L || quantity <= 0L) return null;
        if (tax >= 0L) return new long[] {execution - tax, execution - tax};
        long least =
                execution >= 250_000_000L ? 0L : max(0L, (execution - 49L * quantity + 49L) / 50L);
        return new long[] {execution - execution / 50L, execution - least};
    }

    /**
     * Fail closed on the slot-evidenced candidates only. The quarantine booking keeps today's shape
     * (uncounted, collectionAmbiguous, the booked allocation recorded) while the observed cash
     * itself is never assigned to a lifecycle it was not proven part of.
     */
    void quarantineAmbiguous(
            List<GeOffer> quarantinable, long now, String sessionId, List<Transaction> counted) {
        for (GeOffer record : quarantinable) {
            long settleQty = record.pendingQty();
            long execution = record.pendingExecution();
            if (settleQty > 0L && execution > 0L) {
                record.setCollectionAmbiguous(true);
                bookSellSettlement(
                        record, settleQty, execution, execution, now, sessionId, false, counted);
            }
        }
    }

    /** Candidate sell lifecycles for one coin gain; the retry needs durable terminal evidence. */
    List<GeOffer> settlementCandidates(boolean collectionEvidence) {
        var candidates = new ArrayList<GeOffer>();
        for (GeOffer record : byPlacement()) {
            if (record.getSide() == SELL
                    && record.pendingQty() > 0L
                    && record.getCapturedQty() > 0L
                    && record.settleableQty() >= record.pendingQty()
                    && !record.isUnbased()
                    && (collectionEvidence || record.isTerminal() || record.cleared)) {
                candidates.add(record);
            }
        }
        return candidates;
    }

    /** Retain observed collect cash transiently; the SLOT WINDOW anchors on the first hold. */
    void holdCash(long cashGp, long now) {
        if (cashGp <= 0L) return;
        if (pendingSettlementCashGp <= 0L) pendingSettlementHeldSinceEpochMillis = now;
        pendingSettlementCashGp = safeAdd(pendingSettlementCashGp, cashGp);
        pendingSettlementAtEpochMillis = max(pendingSettlementAtEpochMillis, now);
    }

    /** Exact attribution of one collect coin movement over the current candidate set. */
    Attribution attribute(List<GeOffer> candidates, long cashGp, long coinAt) {
        int size = candidates.size();
        var quarantinable = new ArrayList<GeOffer>();
        if (size <= 0 || size > MAX_SETTLEMENT_CANDIDATES) {
            return new Attribution(AttributionKind.NONE, quarantinable, quarantinable, false);
        }
        // Each candidate's possible after-tax cash: one value, or a range for a mixed-price fill.
        long[][] nets = new long[size][];
        var cleared = new ArrayList<Integer>();
        for (int index = 0; index < size; index++) {
            GeOffer record = candidates.get(index);
            nets[index] = net(record);
            Long clearedAt = slotClearedAtEpochMillis.get(record.getOfferId());
            // The candidate's slot cleared within the SLOT WINDOW of the coin gain.
            if (clearedAt != null && Math.abs(clearedAt - coinAt) <= SLOT_WINDOW_MILLIS) {
                cleared.add(index);
                quarantinable.add(record);
            }
        }
        // Apply slot evidence only after enumerating every subset within the eight-offer bound.
        List<List<Integer>> matches = matchingSubsets(nets, cashGp, 1 << size);
        List<Integer> match = null;
        AttributionKind kind = AttributionKind.NONE;
        boolean contradicted = false;
        if (matches.contains(cleared)) {
            // §2.1: the slot-clear set is the primary proof; a single candidate has no competing
            // explanation either, so neither needs slot confirmation (an amount it cannot have
            // received, or an execution with no known price, still fails closed below).
            kind = AttributionKind.SETTLE;
            match = cleared;
        } else if (size == 1 && !matches.isEmpty()) {
            kind = AttributionKind.SETTLE;
            match = matches.get(0);
        } else if (matches.size() > 1) {
            // §2.3: slot evidence may still select exactly one matching subset.
            matches.removeIf(subset -> !subset.containsAll(cleared));
            contradicted = !cleared.isEmpty() && matches.isEmpty();
            kind =
                    contradicted
                            ? AttributionKind.NONE
                            : matches.size() == 1 ? AttributionKind.SETTLE : AttributionKind.TIE;
            match = matches.size() == 1 ? matches.get(0) : null;
        } else if (matches.size() == 1) {
            // A unique match settles when the slot-clear set is exactly it, needs confirmation
            // while
            // no slot outside it cleared, and §2.2: a slot cleared outside it contradicts it.
            match = matches.get(0);
            contradicted = !match.containsAll(cleared);
            kind =
                    match.equals(cleared)
                            ? AttributionKind.SETTLE
                            : contradicted ? AttributionKind.NONE : AttributionKind.CONFIRM;
        }
        var records = new ArrayList<GeOffer>();
        for (int index : match == null || contradicted ? Collections.<Integer>emptyList() : match) {
            records.add(candidates.get(index));
        }
        return new Attribution(kind, records, quarantinable, contradicted);
    }

    /**
     * Subset enumeration over candidates with a known execution: a subset matches when the cash
     * lies within its summed {lowest, highest} after-tax cash. Saturating sums, deterministic index
     * order, at most {@code limit} matches. Returns empty when the candidate bound is exceeded, so
     * an over-full interaction can never be enumerated.
     */
    static List<List<Integer>> matchingSubsets(long[][] nets, long cashGp, int limit) {
        int size = nets == null ? 0 : nets.length;
        var matches = new ArrayList<List<Integer>>();
        if (size <= 0 || size > MAX_SETTLEMENT_CANDIDATES) {
            return matches;
        }
        for (int mask = 1; mask < 1 << size && matches.size() < limit; mask++) {
            long least = 0L;
            long most = 0L;
            var subset = new ArrayList<Integer>();
            for (int index = 0; index < size && least >= 0L; index++) {
                if ((mask & (1 << index)) != 0) {
                    subset.add(index);
                    least = nets[index] == null ? -1L : safeAdd(least, nets[index][0]);
                    most = nets[index] == null ? most : safeAdd(most, nets[index][1]);
                }
            }
            if (least >= 0L && least <= cashGp && cashGp <= most) matches.add(subset);
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
        List<GeOffer> records;
        List<GeOffer> quarantinable;
        boolean contradicted;
    }

    /** True while an observed sell lifecycle can still receive execution/terminal evidence. */
    boolean hasPendingSellEvidence() {
        for (GeOffer record : records) {
            if (record != null
                    && record.getSide() == SELL
                    && record.getCapturedQty() > 0L
                    && !record.isUnbased()
                    && !record.isTerminal()
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
    void reconcilePendingSettlement(long now, String sessionId, List<Transaction> counted) {
        if (pendingSettlementCashGp <= 0L) return;
        // The held cash IS a collect interaction, so the retry sees the same candidate set that
        // interaction saw (still-active sells whose fills may still be collected included).
        Attribution attribution =
                attribute(
                        settlementCandidates(true),
                        pendingSettlementCashGp,
                        pendingSettlementHeldSinceEpochMillis);
        boolean windowClosed = now - pendingSettlementHeldSinceEpochMillis > SLOT_WINDOW_MILLIS;
        // §2.2b: a unique match settles at the window end with no contradicting slot evidence; the
        // existing long hold also books as soon as its missing execution evidence completes.
        if (attribution.kind == AttributionKind.SETTLE
                || attribution.kind == AttributionKind.CONFIRM
                        && (windowClosed || pendingSettlementAwaitingEvidence)) {
            settleAttributed(attribution.records, pendingSettlementCashGp, now, sessionId, counted);
            clearPendingSettlement();
            return;
        }
        // §2.2 contradiction, §2.3 an unresolved tie, and §2.4/§3 exhausted evidence fail closed:
        // only slot-evidenced candidates are quarantined and the held cash goes to Review.
        if (attribution.contradicted
                || now - pendingSettlementAtEpochMillis > UNOBSERVED_GRACE_MILLIS
                || attribution.kind == AttributionKind.TIE && windowClosed
                || attribution.kind == AttributionKind.NONE
                        && (pendingSettlementAwaitingEvidence
                                ? !hasPendingSellEvidence()
                                : windowClosed)) {
            quarantineAmbiguous(attribution.quarantinable, now, sessionId, counted);
            releasePendingSettlement(now, counted);
        }
    }

    /** Fail closed: the observed cash stays visible as an uncounted Review row, never guessed. */
    void releasePendingSettlement(long now, List<Transaction> counted) {
        long held = pendingSettlementCashGp;
        clearPendingSettlement();
        if (held <= 0L) return;
        counted.add(
                reviewRow(now, singletonList(coins(held, now)), msg("grand-exchange-collection")));
    }

    void bookSellSettlement(
            GeOffer record,
            long settleQty,
            long cashValue,
            long execution,
            long now,
            String sessionId,
            boolean clean,
            List<Transaction> counted) {
        long remainingQty = record.reservedQty();
        long remainingBasis = record.reservedBasis();
        long knownQty = min(settleQty, remainingQty);
        long knownBasis = BasisMath.shareOf(remainingQty, remainingBasis, knownQty);
        long knownProceeds = BasisMath.knownProceedsOf(cashValue, settleQty, knownQty);
        boolean fullyUnknown = knownQty <= 0L;
        record.setConsumedTrackedQty(safeAdd(record.getConsumedTrackedQty(), knownQty));
        record.setConsumedTrackedBasisGp(safeAdd(record.getConsumedTrackedBasisGp(), knownBasis));
        if (basisLedger != null) {
            // The realized quantity left ownership even when it carried no known coverage; the
            // per-item realization fence always advances.
            basisLedger.fenceRealized(record.itemId, now);
        }
        // The one shared accepted-tax predicate: this settlement's observed gross execution against
        // its actual Received cash. Only an exact per-item match counts; the tax is never inferred
        // from a reference, a guide price or a difference.
        long provenTax = pendingSellTax(record);
        long acceptedTax =
                fullyUnknown && clean && provenTax > 0L && execution - cashValue == provenTax
                        ? provenTax
                        : 0L;
        boolean knownCostOnly = acceptedTax > 0L;
        var flows = new ArrayList<Flow>(2);
        if (knownCostOnly) {
            // Fully unknown prior value with a PROVEN tax: the departing item flow is valued at
            // the observed gross execution (not at the frozen reference) against the observed
            // Received cash, so the counted automatic Net is exactly -accepted tax — a market
            // trade cost. Result stays unknown: the accumulator below receives no result here, and
            // a later correction replaces this contribution instead of stacking on it.
            long unit = execution / settleQty;
            flows.add(itemFlow(record, -settleQty, unit, -execution));
            flows.add(coins(cashValue, now));
        } else if (fullyUnknown) {
            // Unknown coverage without a proven tax: the full observed movement stays as
            // liquidation evidence and is Net-neutral. It is never a fake cost and never
            // automatic profit.
            flows.add(
                    itemFlow(
                            record,
                            -settleQty,
                            record.getBasisUnitPrice(),
                            -record.basisFor(settleQty)));
            flows.add(coins(cashValue, now));
        } else {
            if (knownBasis > 0L) {
                long unit = knownQty > 0L ? knownBasis / knownQty : 0L;
                flows.add(itemFlow(record, -settleQty, unit, -knownBasis));
            }
            flows.add(coins(knownProceeds, now));
        }
        String explanation =
                knownCostOnly
                        ? msg("grand-exchange-sale-settled-proven")
                        : fullyUnknown
                                ? msg("grand-exchange-sale-settled")
                                : knownQty < settleQty
                                        ? msg("grand-exchange-sale-settled-quantity")
                                        : msg("grand-exchange-sale");
        // Counted when the lifecycle is exact AND either the realized quantity carries known
        // coverage
        // or the actual GE tax is proven. An unknown sale without a proven tax stays an exact,
        // uncounted evidence row (liquidation cash retained, Net-neutral, never a Review nag).
        Transaction transaction =
                buildMarket(
                        flows, now, clean, clean && (!fullyUnknown || knownCostOnly), explanation);
        counted.add(transaction);
        if (provenTax >= 0L)
            record.settledTaxGp = nonNeg(safeAdd(record.getSettledTaxGp(), provenTax));
        record.setSettledQty(safeAdd(record.getSettledQty(), settleQty));
        record.setSettledExecutionGp(safeAdd(record.getSettledExecutionGp(), execution));
        record.setSettledCashGp(safeAdd(record.settledCashGp, cashValue));
        record.setRealizedResultGp(safeAdd(record.realizedResultGp, knownProceeds - knownBasis));
        record.setSettlementId(transaction.getId());
    }

    /** Return an unconsumed reservation share when custody returns or retires a lifecycle. */
    void releaseShare(GeOffer record, long returnedQty) {
        if (record == null || returnedQty <= 0L) return;
        long remainingQty = record.reservedQty();
        long remainingBasis = record.reservedBasis();
        if (remainingQty <= 0L && remainingBasis <= 0L) return;
        long releaseQty = min(returnedQty, remainingQty);
        long releaseBasis = BasisMath.shareOf(remainingQty, remainingBasis, releaseQty);
        if (basisLedger != null && (releaseQty > 0L || releaseBasis > 0L)) {
            basisLedger.release(record.itemId, releaseQty, releaseBasis);
        }
        record.reservedTrackedQty =
                nonNeg(
                        max(
                                record.getConsumedTrackedQty(),
                                record.getReservedTrackedQty() - releaseQty));
        record.reservedTrackedBasisGp =
                nonNeg(
                        max(
                                record.getConsumedTrackedBasisGp(),
                                record.getReservedTrackedBasisGp() - releaseBasis));
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
    Transaction buildMarket(
            List<Flow> flows, long now, boolean exact, boolean counted, String explanation) {
        return new Transaction(
                now,
                exact ? TRADE : UNCERTAIN,
                MARKET,
                "Grand Exchange",
                "Market",
                counted,
                flows,
                exact ? Confidence.CONFIRMED : Confidence.UNCERTAIN,
                exact
                        ? explanation
                        : explanation
                                + msg("lifecycle-could-settled-exactly")
                                + msg("stays-uncounted"),
                null);
    }

    boolean isCleanRealization(GeOffer record, String sessionId) {
        if (record.isUnbased()
                || record.getConfidence() == CustodyConfidence.AMBIGUOUS
                || record.quantityModified
                || record.collectionAmbiguous
                || !record.hasFrozenBasis()) {
            return false;
        }
        if (record.getOriginSessionId().isEmpty() && sessionId != null && !sessionId.isEmpty()) {
            record.originSessionId = Phrases.orEmpty(sessionId);
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
        record.setFilledQty(safeAdd(record.getFilledQty(), quantity));
        record.setSpentGp(safeAdd(record.getSpentGp(), spent));
        if (record.getSide() != SELL || !record.fillTaxExact || quantity <= 0L) return;
        long chunkTax = GeTaxRule.provableTax(record.itemId, spent, quantity);
        if (chunkTax < 0L) {
            record.setFillTaxExact(false);
            return;
        }
        record.fillTaxGp = nonNeg(safeAdd(record.getFillTaxGp(), chunkTax));
    }

    /**
     * The exact GE tax of a SELL's pending (filled, unsettled) execution, or {@link
     * GeTaxRule#UNPROVABLE_TAX}. Per-fill tax wins when every fill was observed at a provable
     * price, since fills at different prices pay different per-item tax; otherwise the uniform rule
     * over the pending total applies.
     */
    static long pendingSellTax(GeOffer record) {
        long execution = record.pendingExecution();
        long quantity = record.pendingQty();
        if (record.fillTaxExact && execution > 0L && quantity > 0L) {
            long tax = record.getFillTaxGp() - record.getSettledTaxGp();
            return tax >= 0L && tax <= execution ? tax : GeTaxRule.UNPROVABLE_TAX;
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
        var newestBySlot = new HashMap<Integer, GeOffer>();
        var closedByOffer = new LinkedHashMap<String, GeOffer>();
        for (GeOffer record : state.getGeCustody()) {
            if (record == null || record.getOfferId().isEmpty()) continue;
            if (record.getStage() == CLOSED) closedByOffer.put(record.getOfferId(), record);
            GeOffer existing = newestBySlot.get(record.slot);
            if (existing == null
                    || record.getPlacedAtEpochMillis() >= existing.getPlacedAtEpochMillis()) {
                newestBySlot.put(record.slot, record);
            }
        }
        for (GeOffer record : newestBySlot.values()) closedByOffer.remove(record.getOfferId());
        records.addAll(newestBySlot.values());
        records.addAll(closedByOffer.values());
        records.sort(
                (left, right) ->
                        Long.compare(
                                left.getPlacedAtEpochMillis(), right.getPlacedAtEpochMillis()));
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
        pendingSettlementCashGp = 0L;
        pendingSettlementAtEpochMillis = 0L;
        pendingSettlementHeldSinceEpochMillis = 0L;
        pendingSettlementAwaitingEvidence = false;
        slotClearedAtEpochMillis.clear();
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------
    List<GeOffer> byPlacement() {
        var sorted = new ArrayList<GeOffer>(records);
        sorted.sort(
                (left, right) -> {
                    int byTime =
                            Long.compare(
                                    left.getPlacedAtEpochMillis(), right.getPlacedAtEpochMillis());
                    return byTime != 0 ? byTime : Integer.compare(left.slot, right.slot);
                });
        return sorted;
    }

    /** The slot's live lifecycle; settled (CLOSED) history never owns a slot. */
    GeOffer forSlot(int slot) {
        for (GeOffer record : records) {
            if (record.slot == slot && record.getStage() != CLOSED) return record;
        }
        return null;
    }

    GeOffer latestClosed(int slot) {
        GeOffer latest = null;
        for (GeOffer record : records) {
            if (record.slot == slot
                    && record.getStage() == CLOSED
                    && (latest == null
                            || record.getPlacedAtEpochMillis()
                                    >= latest.getPlacedAtEpochMillis())) {
                latest = record;
            }
        }
        return latest;
    }

    GeOffer oldestClosed() {
        GeOffer oldest = null;
        for (GeOffer record : records) {
            if (record != null
                    && record.getStage() == CLOSED
                    && (oldest == null
                            || record.getPlacedAtEpochMillis() < oldest.getPlacedAtEpochMillis())) {
                oldest = record;
            }
        }
        return oldest;
    }

    /**
     * The slot's live lifecycle for an offer (any side or item when unknown), falling back to its
     * latest settled history; marks it seen.
     */
    GeOffer touch(int slot, Snapshot offer, long now) {
        Side side = offer == null ? null : OfferSlots.sideOf(offer.state);
        int itemId = offer == null ? 0 : offer.itemId;
        GeOffer match = null;
        for (GeOffer record : records) {
            if (record.slot != slot
                    || side != null && side != record.getSide()
                    || itemId > 0 && record.itemId != itemId) {
                continue;
            }
            if (record.getStage() != CLOSED) {
                match = record;
                break;
            }
            if (match == null || record.getPlacedAtEpochMillis() >= match.getPlacedAtEpochMillis())
                match = record;
        }
        if (match != null) match.setLastSeenAtEpochMillis(now);
        return match;
    }

    boolean isSameIdentity(GeOffer record, Side side, int itemId, long totalQty, long price) {
        return record.getSide() == side
                && record.itemId == itemId
                && record.getOfferedQty() == totalQty
                && record.getListedPrice() == price;
    }

    /**
     * The slot cleared but its cash or items never reached the inventory: they went to the bank.
     * The offer itself proves what filled and what it cost, so it settles as if collected. A buy is
     * exact; a sale pays its proven tax or, filled at several prices, the highest tax it could have
     * paid, so its result is never overstated. What never sold, or was never spent, went too. False
     * leaves an untrusted or unobserved lifecycle to the Review handoff.
     */
    boolean banked(GeOffer record, long now, String sessionId, List<Transaction> counted) {
        boolean sell = record.getSide() == SELL;
        if (!sell && record.basisSource == null && record.pendingQty() > 0L) {
            // A bought item never seen has no market quote: its own execution is its basis.
            record.setBasisUnitPrice(record.pendingExecution() / record.pendingQty());
            record.setBasisSource(PriceSource.GRAND_EXCHANGE);
            record.setBasisCapturedAtEpochMillis(now);
        }
        if (sell && record.getCapturedQty() <= 0L || !isCleanRealization(record, sessionId))
            return false;
        if (sell) {
            long[] net = net(record);
            if (net != null) {
                boolean estimated = pendingSellTax(record) < 0L;
                if (estimated) {
                    record.fillTaxExact = true;
                    record.fillTaxGp =
                            safeAdd(record.getSettledTaxGp(), record.pendingExecution() - net[0]);
                }
                bookSellSettlement(
                        record,
                        record.pendingQty(),
                        net[0],
                        record.pendingExecution(),
                        now,
                        sessionId,
                        true,
                        counted);
                if (estimated) counted.get(counted.size() - 1).explanation += msg("gbank");
            }
            long unsold = record.heldQty();
            releaseShare(record, unsold);
            record.setReturnedQty(safeAdd(record.getReturnedQty(), unsold));
        } else {
            record.collectedQty = nonNeg(record.getFilledQty());
            bookBuySettlement(record, now, sessionId, counted);
            record.refundedGp =
                    nonNeg(safeAdd(record.getRefundedGp(), record.reserveOutstanding()));
        }
        if (record.isReconciled()) record.setStage(CLOSED);
        return true;
    }

    void handoffAndRemove(
            GeOffer record, long now, String sessionId, String reason, List<Transaction> handoffs) {
        releaseRemaining(record);
        Transaction review = reviewHandoff(record, now, reason);
        if (review != null) handoffs.add(review);
        records.remove(record);
    }

    Transaction reviewHandoff(GeOffer record, long now, String reason) {
        if (record.isUnbased()) return null;
        boolean buy = record.getSide() == BUY;
        if (!buy && record.getCapturedQty() <= 0L) {
            // The principal was never observed entering custody; its movements follow the
            // ordinary classification path and must not be surfaced twice.
            return null;
        }
        long unsettledQty = buy ? record.pendingQty() : record.settleableQty();
        long execution = record.pendingExecution();
        if (unsettledQty <= 0L && execution <= 0L) return null;
        var flows = new ArrayList<Flow>(2);
        long unit = record.getBasisUnitPrice();
        if (unsettledQty > 0L) {
            long value =
                    buy && record.collectedValueGp != 0L
                            ? SafeMath.abs(record.collectedValueGp)
                            : record.basisFor(unsettledQty);
            flows.add(
                    itemFlow(
                            record,
                            buy ? unsettledQty : -unsettledQty,
                            unit,
                            buy ? value : -value));
        }
        if (execution > 0L) flows.add(coins(buy ? -execution : execution, now));
        return reviewRow(now, flows, msg("grand-exchange-lifecycle") + reason + msg("known-facts"));
    }

    void captureBasis(GeOffer record, Flow flow, long now) {
        PriceSource source = flow.getPriceSource();
        record.setBasisUnitPrice(nonNeg(flow.unitPrice));
        record.setBasisSource(source == null ? UNKNOWN : source);
        record.setBasisCapturedAtEpochMillis(now);
    }

    /** Presentation/audit source name; never used for matching. */
}
