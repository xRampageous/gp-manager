package com.gpmanager;

import static com.gpmanager.Correction.*;
import static com.gpmanager.PriceSource.*;
import static com.gpmanager.SafeMath.*;

import static java.lang.Math.*;

import lombok.*;

import net.runelite.api.gameval.ItemID;

import java.util.*;
import java.util.Map.Entry;
import static com.gpmanager.SafeMath.nonNeg;

/**
 * Schema-106 pooled known-basis coverage memory.
 *
 * <p>Tracked basis is a double-count-prevention memory, never a second financial ledger: canonical
 * counted transactions stay the only financial authority and this ledger never books, reprices or
 * exports GP. It records only <em>known coverage</em>: quantity whose prior GP Manager accounting
 * is proven, with the exact GP value GP Manager counted (which may legitimately be zero). Absence
 * of coverage means UNKNOWN basis, never zero basis; unknown quantity is derived per realization
 * and never persisted as a global unknown-owned ledger.
 *
 * <p>Allocation is tracked-first pooled weighted average (never FIFO/LIFO). Acquisitions add known
 * coverage. Proven SELL placements reserve their share; cancellations release the unused
 * reservation exactly; settlements consume the realized share; permanent ownership sinks deplete
 * with the same rule. A per-item realization fence blocks retroactive acquisition corrections once
 * known coverage has been realized or depleted, so canonical Net and tracked basis can never
 * disagree about what was previously counted.
 */
class TrackedBasisLedger implements Session.ValueObserver {
    /** Exact known-coverage share a proven SELL placement captured from the available pool. */
    @AllArgsConstructor
    static class Reservation {
        long trackedQty;
        long basisGp;

        boolean isEmpty() {
            return trackedQty <= 0L && basisGp <= 0L;
        }
    }

    Map<Integer, BasisPool> pools = new LinkedHashMap<>();
    long basisEpochMillis;
    java.util.function.IntFunction<Long> openReservationLookup = id -> 0L;

    // ---------------------------------------------------------------------------------------
    // Acquisition / removal / change
    // ---------------------------------------------------------------------------------------
    /**
     * Book a newly added canonical transaction: add its exact known coverage, and deplete known
     * coverage for proven permanent non-custody ownership sinks. Custody-owned settlements already
     * consumed their reservation, so their item legs never deplete again.
     */
    public synchronized void onAdded(Transaction transaction, boolean custodyOwned) {
        if (transaction == null || transaction.timestampEpochMillis < basisEpochMillis) {
            // Pre-epoch stock is UNKNOWN coverage by migration law; nothing is inferred here.
            return;
        }
        if (basisEpochMillis <= 0L && transaction.timestampEpochMillis > 0L)
            basisEpochMillis = transaction.timestampEpochMillis;
        applyContribution(contribution(transaction), transaction.timestampEpochMillis);
        if (!custodyOwned) depleteSinks(transaction);
    }

    /** Validate restoring a previously removed transaction; false blocks the restore. */
    public synchronized boolean canAdd(Transaction transaction) {
        if (transaction == null) return false;
        if (transaction.timestampEpochMillis >= basisEpochMillis
                && !sinkQuantities(transaction).isEmpty()) {
            return false;
        }
        return canApplyAll(contribution(transaction), 1L, transaction.timestampEpochMillis);
    }

    /** Validate removing a retained transaction (undo); false blocks the canonical removal. */
    public synchronized boolean canRemove(Transaction transaction) {
        if (transaction == null || transaction.timestampEpochMillis < basisEpochMillis) return true;
        if (!sinkQuantities(transaction).isEmpty()) {
            return false;
        }
        return canApplyAll(contribution(transaction), -1L, transaction.timestampEpochMillis);
    }

    boolean canApplyAll(Map<Integer, long[]> deltas, long sign, long timestamp) {
        for (Entry<Integer, long[]> entry : deltas.entrySet()) {
            if (!canApply(
                    entry.getKey(),
                    sign * entry.getValue()[0],
                    sign * entry.getValue()[1],
                    timestamp)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized void onUsedUp(Transaction transaction) {
        depleteSinks(transaction);
    }

    /** Reverse a previously removed transaction's exact known coverage. */
    public synchronized void onRemoved(Transaction transaction) {
        if (transaction == null || transaction.timestampEpochMillis < basisEpochMillis) return;
        contribution(transaction)
                .forEach((itemId, delta) -> applyDelta(itemId, -delta[0], -delta[1]));
    }

    /**
     * Validate a pending correction/split/undo before any canonical mutation. False means the
     * mutation must be rejected as-is: the item's known coverage is reserved or already realized.
     */
    public synchronized boolean canChange(
            Transaction transaction,
            Correction previousCorrection,
            List<Flow> previousFlows,
            Correction nextCorrection,
            List<Flow> nextFlows) {
        if (transaction == null) return false;
        Map<Integer, long[]> before = contribution(transaction, previousCorrection, previousFlows);
        // A null next-flow set means the caller can only promise the positive contribution
        // shrinks; validating against the empty worst case is the strictest safe check.
        Map<Integer, long[]> after = contribution(transaction, nextCorrection, nextFlows);
        Map<Integer, Long> beforeSinks =
                sinkQuantities(transaction, previousCorrection, previousFlows);
        Map<Integer, Long> afterSinks = sinkQuantities(transaction, nextCorrection, nextFlows);
        if (transaction.timestampEpochMillis >= basisEpochMillis
                && !beforeSinks.equals(afterSinks)
                && !canIntroduceSink(beforeSinks, afterSinks, transaction.timestampEpochMillis)) {
            return false;
        }
        return canApplyAll(deltas(before, after), 1L, transaction.timestampEpochMillis);
    }

    /** Apply the exact effective delta after a permitted correction/split/undo. */
    public synchronized void onChanged(
            Transaction transaction, Correction previousCorrection, List<Flow> previousFlows) {
        if (transaction == null) return;
        Map<Integer, Long> beforeSinks =
                sinkQuantities(transaction, previousCorrection, previousFlows);
        Map<Integer, Long> afterSinks = sinkQuantities(transaction);
        applyContribution(
                deltas(
                        contribution(transaction, previousCorrection, previousFlows),
                        contribution(transaction)),
                transaction.timestampEpochMillis);
        if (!beforeSinks.equals(afterSinks) && beforeSinks.isEmpty()) depleteSinks(transaction);
    }

    // ---------------------------------------------------------------------------------------
    // Reservation / realization / depletion
    // ---------------------------------------------------------------------------------------
    /**
     * Reserve the tracked-first known-coverage share for a quantity entering proven SELL custody.
     */
    synchronized Reservation reserve(int itemId, long quantity, long now) {
        int id = itemId;
        long requested = nonNeg(quantity);
        BasisPool pool = pools.get(id);
        long availableQty = pool == null ? 0L : pool.getAvailableQty();
        long availableBasis = pool == null ? 0L : pool.getAvailableBasisGp();
        long consumeQty = min(requested, availableQty);
        long basis = BasisMath.shareOf(availableQty, availableBasis, consumeQty);
        if (consumeQty <= 0L && basis <= 0L) return new Reservation(0L, 0L);
        pool = pool(id);
        pool.setAvailableQty(availableQty - consumeQty);
        pool.setAvailableBasisGp(availableBasis - basis);
        return new Reservation(consumeQty, basis);
    }

    /** A sale realized units of this item: fence retroactive corrections before {@code now}. */
    synchronized void fenceRealized(int itemId, long now) {
        BasisPool pool = pool(itemId);
        pool.setRealizationFenceEpochMillis(max(pool.getRealizationFenceEpochMillis(), now));
    }

    /** Return an unconsumed reservation share to the available pool (cancel/return). */
    synchronized void release(int itemId, long quantity, long basisGp) {
        int id = itemId;
        long releaseQty = nonNeg(quantity);
        long releaseBasis = nonNeg(basisGp);
        if (releaseQty <= 0L && releaseBasis <= 0L) return;
        BasisPool pool = pool(id);
        pool.setAvailableQty(pool.getAvailableQty() + releaseQty);
        pool.setAvailableBasisGp(pool.getAvailableBasisGp() + releaseBasis);
    }

    /** Deplete known coverage for a proven permanent ownership sink; returns basis consumed. */
    synchronized long deplete(int itemId, long quantity, long now) {
        int id = itemId;
        long requested = nonNeg(quantity);
        BasisPool pool = pools.get(id);
        if (requested <= 0L) return 0L;
        if (pool == null) {
            // Even a sink with no known coverage leaves a realization fence: units left ownership,
            // so later retroactive coverage for them can never be re-added.
            pool(id).setRealizationFenceEpochMillis(now);
            return 0L;
        }
        long consumeQty = min(requested, pool.getAvailableQty());
        long basis =
                BasisMath.shareOf(pool.getAvailableQty(), pool.getAvailableBasisGp(), consumeQty);
        pool.setAvailableQty(pool.getAvailableQty() - consumeQty);
        pool.setAvailableBasisGp(pool.getAvailableBasisGp() - basis);
        pool.setRealizationFenceEpochMillis(max(pool.getRealizationFenceEpochMillis(), now));
        return basis;
    }

    /** Currency is money, never tracked owned-item value. */
    static boolean isCurrency(int itemId) {
        return itemId == ItemID.COINS || itemId == ItemID.PLATINUM;
    }

    // ---------------------------------------------------------------------------------------
    // Persistence / lifecycle
    // ---------------------------------------------------------------------------------------
    synchronized void writeTo(SavedState state) {
        if (state == null) return;
        var out = new TrackedBasisState();
        // Epoch 0 only happens for an engine that never restored or tracked anything; a positive
        // sentinel keeps a saved pool loadable as "tracked since the beginning".
        out.basisEpochMillis = nonNeg(basisEpochMillis > 0L ? basisEpochMillis : 1L);
        var list = new ArrayList<BasisPool>();
        for (BasisPool pool : pools.values()) {
            if (pool == null) continue;
            if (pool.getAvailableQty() <= 0L
                    && pool.getAvailableBasisGp() <= 0L
                    && pool.getRealizationFenceEpochMillis() <= 0L
                    && pool.getLatestAcquisitionEpochMillis() <= 0L) {
                continue;
            }
            var copy = new BasisPool(pool.itemId);
            copy.setAvailableQty(pool.getAvailableQty());
            copy.setAvailableBasisGp(pool.getAvailableBasisGp());
            copy.setRealizationFenceEpochMillis(pool.getRealizationFenceEpochMillis());
            copy.setLatestAcquisitionEpochMillis(pool.getLatestAcquisitionEpochMillis());
            list.add(copy);
        }
        list.removeIf(value -> value == null || value.itemId <= 0);
        out.pools = new ArrayList<>(list);
        state.setTrackedBasis(out);
    }

    /**
     * Restore persisted continuity. A pre-106 (or absent) state starts with no known coverage at
     * the migration boundary: existing holdings are UNKNOWN coverage, no bank value seeds basis and
     * no history is reconstructed. Reserved shares live on the durable custody records.
     */
    synchronized void restore(SavedState state, long now) {
        pools.clear();
        TrackedBasisState saved = state == null ? null : state.getTrackedBasis();
        if (saved != null && saved.getBasisEpochMillis() > 0L) {
            basisEpochMillis = saved.getBasisEpochMillis();
            for (BasisPool pool : saved.pools()) {
                if (pool == null || pool.itemId <= 0) continue;
                pools.put(pool.itemId, pool);
            }
        } else {
            basisEpochMillis = now;
        }
    }

    /** Destructive reset: tracked basis is continuity state and clears with the rest. */
    synchronized void reset() {
        pools.clear();
        basisEpochMillis = 0L;
    }

    /** Adopt another ledger's durable state during a staged profile swap. */
    synchronized void adoptFrom(TrackedBasisLedger other) {
        reset();
        if (other != null) {
            basisEpochMillis = other.basisEpochMillis;
            other.pools.forEach(
                    (itemId, pool) -> {
                        if (pool != null) pools.put(itemId, pool);
                    });
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------
    BasisPool pool(int itemId) {
        return pools.computeIfAbsent(itemId, BasisPool::new);
    }

    void applyContribution(Map<Integer, long[]> contribution, long timestamp) {
        for (Entry<Integer, long[]> entry : contribution.entrySet()) {
            applyDelta(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
            if (entry.getValue()[0] > 0L) {
                BasisPool pool = pool(entry.getKey());
                pool.setLatestAcquisitionEpochMillis(
                        max(pool.getLatestAcquisitionEpochMillis(), timestamp));
            }
        }
    }

    /** Deplete known coverage for each proven permanent non-custody ownership sink in the row. */
    void depleteSinks(Transaction transaction) {
        Map<Integer, Long> quantities = sinkQuantities(transaction);
        for (Entry<Integer, Long> entry : quantities.entrySet())
            deplete(entry.getKey(), entry.getValue(), transaction.timestampEpochMillis);
    }

    /** An owner may first count a Review removal while no later use has frozen its pool. */
    boolean canIntroduceSink(
            Map<Integer, Long> before, Map<Integer, Long> after, long transactionTimestamp) {
        if (!before.isEmpty() || after.isEmpty()) return false;
        for (Integer itemId : after.keySet()) {
            if (!canApply(itemId, 0L, 0L, transactionTimestamp)) return false;
            BasisPool pool = pools.get(itemId);
            if (pool != null
                    && pool.getAvailableQty() > 0L
                    && (pool.getLatestAcquisitionEpochMillis() <= 0L
                            || pool.getLatestAcquisitionEpochMillis() > transactionTimestamp)) {
                return false;
            }
        }
        return true;
    }

    /** Physical counted removals that would require exact reversal of pooled sink depletion. */
    Map<Integer, Long> sinkQuantities(
            TransactionType type, boolean counted, Correction correction, List<Flow> flows) {
        var quantities = new LinkedHashMap<Integer, Long>();
        if (!counted
                || type == TransactionType.TRANSFER
                || type == TransactionType.TRADE
                || correction == TRANSFER
                || flows == null) {
            // Custody-owned trade settlements consume their reservation separately. Their financial
            // Result corrections must remain available without touching physical basis.
            return quantities;
        }
        for (Flow flow : flows) {
            if (flow == null || flow.quantityDelta >= 0L || isCurrency(flow.itemId)) continue;
            long quantity =
                    flow.quantityDelta == Long.MIN_VALUE ? Long.MAX_VALUE : -flow.quantityDelta;
            int itemId = flow.itemId;
            quantities.put(itemId, safeAdd(quantities.getOrDefault(itemId, 0L), quantity));
        }
        return quantities;
    }

    void applyDelta(int itemId, long deltaQty, long deltaBasisGp) {
        if (deltaQty == 0L && deltaBasisGp == 0L) return;
        BasisPool pool = pool(itemId);
        pool.setAvailableQty(pool.getAvailableQty() + deltaQty);
        pool.setAvailableBasisGp(pool.getAvailableBasisGp() + deltaBasisGp);
    }

    boolean canApply(int itemId, long deltaQty, long deltaBasisGp, long transactionTimestamp) {
        if (transactionTimestamp < basisEpochMillis) return false;
        int id = itemId;
        Long reservationTime = openReservationLookup.apply(id);
        if (reservationTime != null
                && reservationTime > 0L
                && reservationTime >= transactionTimestamp) return false;
        BasisPool pool = pools.get(id);
        long fence = pool == null ? 0L : pool.getRealizationFenceEpochMillis();
        if (fence > 0L && fence >= transactionTimestamp) return false;
        long availableQty = pool == null ? 0L : pool.getAvailableQty();
        long availableBasis = pool == null ? 0L : pool.getAvailableBasisGp();
        if (availableQty + deltaQty < 0L || availableBasis + deltaBasisGp < 0L) return false;
        return true;
    }

    Map<Integer, long[]> deltas(Map<Integer, long[]> before, Map<Integer, long[]> after) {
        var out = new LinkedHashMap<Integer, long[]>();
        for (Entry<Integer, long[]> entry : before.entrySet()) {
            long[] target = after.get(entry.getKey());
            long deltaQty = (target == null ? 0L : target[0]) - entry.getValue()[0];
            long deltaBasis = (target == null ? 0L : target[1]) - entry.getValue()[1];
            if (deltaQty != 0L || deltaBasis != 0L) {
                out.put(entry.getKey(), new long[] {deltaQty, deltaBasis});
            }
        }
        for (Entry<Integer, long[]> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey())) {
                out.put(entry.getKey(), new long[] {entry.getValue()[0], entry.getValue()[1]});
            }
        }
        return out;
    }

    Map<Integer, long[]> contribution(Transaction transaction) {
        if (transaction == null) return new LinkedHashMap<>();
        return contribution(
                transaction.autoType(),
                transaction.isCounted(),
                transaction.getCorrection(),
                transaction.getFlows());
    }

    /** The contribution the transaction would have under {@code correction} with {@code flows}. */
    Map<Integer, long[]> contribution(
            Transaction transaction, Correction correction, List<Flow> flows) {
        return flows == null
                ? new LinkedHashMap<>()
                : contribution(
                        transaction.autoType(),
                        effectiveCounted(transaction.autoCounted(), correction),
                        correction,
                        flows);
    }

    /**
     * A drop or a shot still on the ground is not a use yet: picking it up shrinks the row. It
     * depletes the pool when its pickup window closes ({@link Engine#settleGround}).
     */
    static boolean pickable(Transaction transaction) {
        return transaction.isOnGround();
    }

    /** The row's sinks as it is booked now. */
    Map<Integer, Long> sinkQuantities(Transaction transaction) {
        if (pickable(transaction)) return new LinkedHashMap<>();
        return sinkQuantities(
                transaction.autoType(),
                transaction.isCounted(),
                transaction.getCorrection(),
                transaction.getFlows());
    }

    Map<Integer, Long> sinkQuantities(
            Transaction transaction, Correction correction, List<Flow> flows) {
        if (pickable(transaction)) return new LinkedHashMap<>();
        return sinkQuantities(
                transaction.autoType(),
                effectiveCounted(transaction.autoCounted(), correction),
                correction,
                flows);
    }

    /** Effective counted state for an explicit correction (REVENUE/COST always count). */
    static boolean effectiveCounted(boolean automaticallyCounted, Correction correction) {
        Correction effective = correction == null ? AUTO : correction;
        return effective == REVENUE
                || effective == COST
                || (effective == AUTO && automaticallyCounted);
    }

    /**
     * Exact known coverage a counted transaction introduces, mirroring {@code Accounting.flow} for
     * positive quantities: AUTO counts the observed positive value, REVENUE counts the gross
     * magnitude, COST/IGNORE/TRANSFER count nothing. Quantity is tracked even when the booked value
     * is legitimately zero; unpriced/unknown/deferred sources create no coverage at all.
     */
    static Map<Integer, long[]> contribution(
            TransactionType type, boolean counted, Correction correction, List<Flow> flows) {
        var out = new LinkedHashMap<Integer, long[]>();
        Correction effective = correction == null ? AUTO : correction;
        if (type == TransactionType.TRANSFER
                || effective == TRANSFER
                || !counted
                || effective == IGNORE
                || flows == null) {
            return out;
        }
        for (Flow flow : flows) {
            if (flow == null || flow.quantityDelta <= 0L || isCurrency(flow.itemId)) continue;
            PriceSource source = flow.getPriceSource();
            if (source == null
                    || source == UNPRICED
                    || source == UNKNOWN
                    || source == DEFERRED_CLAIM) continue;
            long revenue =
                    effective == REVENUE
                            ? SafeMath.abs(flow.valueDelta)
                            : effective == COST ? 0L : nonNeg(flow.valueDelta);
            long[] total = out.computeIfAbsent(flow.itemId, itemId -> new long[2]);
            total[0] = safeAdd(total[0], flow.quantityDelta);
            total[1] = safeAdd(total[1], revenue);
        }
        return out;
    }
}

/**
 * Persisted schema-106 tracked-basis continuity: the minimum state a pooled weighted-average
 * double-count-prevention memory needs across restart.
 *
 * <p>{@code basisEpochMillis} is the migration boundary: acquisitions booked before it are
 * untracked by law (no backfill, no bank-price inference), and basis-affecting corrections of those
 * rows are fenced. Open SELL reservation shares live on their durable custody records; the ledger
 * rebinds the pooled reserved totals from them on restore.
 */
class TrackedBasisState {
    long basisEpochMillis;
    List<BasisPool> pools = new ArrayList<>();

    long getBasisEpochMillis() {
        return nonNeg(basisEpochMillis);
    }

    /** Never null: a pre-106 file upgrades with an empty pool. */
    List<BasisPool> pools() {
        if (pools == null) pools = new ArrayList<>();
        return pools;
    }
}
