package com.gpmanager;

import static com.gpmanager.Correction.*;
import static com.gpmanager.PriceSource.*;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.SafeMath.nonNeg;
import static java.lang.Math.*;

import java.math.BigInteger;
import java.util.*;
import java.util.Map.Entry;
import lombok.*;
import net.runelite.api.gameval.ItemID;

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
class TrackedBasisLedger {
    /** Exact known-coverage share a proven SELL placement captured from the available pool. */
    @AllArgsConstructor
    static class Reservation {
        long trackedQty;
        long basisGp;
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
    synchronized void onAdded(Transaction transaction, boolean custodyOwned) {
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
    synchronized boolean canAdd(Transaction transaction) {
        if (transaction == null) return false;
        if (transaction.timestampEpochMillis >= basisEpochMillis
                && !sinkQuantities(transaction).isEmpty()) {
            return false;
        }
        return canApplyAll(contribution(transaction), 1L, transaction.timestampEpochMillis);
    }

    /** Validate removing a retained transaction (undo); false blocks the canonical removal. */
    synchronized boolean canRemove(Transaction transaction) {
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

    synchronized void onUsedUp(Transaction transaction) {
        depleteSinks(transaction);
    }

    /** Reverse a previously removed transaction's exact known coverage. */
    synchronized void onRemoved(Transaction transaction) {
        if (transaction == null || transaction.timestampEpochMillis < basisEpochMillis) return;
        contribution(transaction)
                .forEach((itemId, delta) -> applyDelta(itemId, -delta[0], -delta[1]));
    }

    /**
     * Validate a pending correction/split/undo before any canonical mutation. False means the
     * mutation must be rejected as-is: the item's known coverage is reserved or already realized.
     */
    synchronized boolean canChange(
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
    synchronized void onChanged(
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
        long requested = nonNeg(quantity);
        BasisPool pool = pools.get(itemId);
        long availableQty = pool == null ? 0L : pool.availableQty;
        long availableBasis = pool == null ? 0L : pool.availableBasisGp;
        long consumeQty = min(requested, availableQty);
        long basis = BasisMath.shareOf(availableQty, availableBasis, consumeQty);
        if (consumeQty <= 0L && basis <= 0L) return new Reservation(0L, 0L);
        pool = pool(itemId);
        pool.availableQty = nonNeg(availableQty - consumeQty);
        pool.availableBasisGp = nonNeg(availableBasis - basis);
        return new Reservation(consumeQty, basis);
    }

    /** A sale realized units of this item: fence retroactive corrections before {@code now}. */
    synchronized void fenceRealized(int itemId, long now) {
        BasisPool pool = pool(itemId);
        pool.realizationFenceEpochMillis = max(pool.realizationFenceEpochMillis, now);
    }

    /** Return an unconsumed reservation share to the available pool (cancel/return). */
    synchronized void release(int itemId, long quantity, long basisGp) {
        long releaseQty = nonNeg(quantity);
        long releaseBasis = nonNeg(basisGp);
        if (releaseQty <= 0L && releaseBasis <= 0L) return;
        BasisPool pool = pool(itemId);
        pool.availableQty = nonNeg(pool.availableQty + releaseQty);
        pool.availableBasisGp = nonNeg(pool.availableBasisGp + releaseBasis);
    }

    /** Deplete known coverage for a proven permanent ownership sink; returns basis consumed. */
    synchronized long deplete(int itemId, long quantity, long now) {
        long requested = nonNeg(quantity);
        BasisPool pool = pools.get(itemId);
        if (requested <= 0L) return 0L;
        if (pool == null) {
            // Even a sink with no known coverage leaves a realization fence: units left ownership,
            // so later retroactive coverage for them can never be re-added.
            pool(itemId).realizationFenceEpochMillis = nonNeg(now);
            return 0L;
        }
        long consumeQty = min(requested, pool.availableQty);
        long basis = BasisMath.shareOf(pool.availableQty, pool.availableBasisGp, consumeQty);
        pool.availableQty = nonNeg(pool.availableQty - consumeQty);
        pool.availableBasisGp = nonNeg(pool.availableBasisGp - basis);
        pool.realizationFenceEpochMillis = max(pool.realizationFenceEpochMillis, now);
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
            if (pool.availableQty <= 0L
                    && pool.availableBasisGp <= 0L
                    && pool.realizationFenceEpochMillis <= 0L
                    && pool.latestAcquisitionEpochMillis <= 0L) {
                continue;
            }
            var copy = new BasisPool(pool.itemId);
            copy.availableQty = pool.availableQty;
            copy.availableBasisGp = pool.availableBasisGp;
            copy.realizationFenceEpochMillis = pool.realizationFenceEpochMillis;
            copy.latestAcquisitionEpochMillis = pool.latestAcquisitionEpochMillis;
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
                // A save is clamped once here, so the pool's fields are read directly.
                pool.availableQty = nonNeg(pool.availableQty);
                pool.availableBasisGp = nonNeg(pool.availableBasisGp);
                pool.realizationFenceEpochMillis = nonNeg(pool.realizationFenceEpochMillis);
                pool.latestAcquisitionEpochMillis = nonNeg(pool.latestAcquisitionEpochMillis);
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
                pool.latestAcquisitionEpochMillis =
                        max(pool.latestAcquisitionEpochMillis, timestamp);
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
                    && pool.availableQty > 0L
                    && (pool.latestAcquisitionEpochMillis <= 0L
                            || pool.latestAcquisitionEpochMillis > transactionTimestamp)) {
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
            if (flow.quantityDelta >= 0L || isCurrency(flow.itemId)) continue;
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
        pool.availableQty = nonNeg(pool.availableQty + deltaQty);
        pool.availableBasisGp = nonNeg(pool.availableBasisGp + deltaBasisGp);
    }

    boolean canApply(int itemId, long deltaQty, long deltaBasisGp, long transactionTimestamp) {
        if (transactionTimestamp < basisEpochMillis) return false;
        Long reservationTime = openReservationLookup.apply(itemId);
        if (reservationTime != null
                && reservationTime > 0L
                && reservationTime >= transactionTimestamp) return false;
        BasisPool pool = pools.get(itemId);
        long fence = pool == null ? 0L : pool.realizationFenceEpochMillis;
        if (fence > 0L && fence >= transactionTimestamp) return false;
        long availableQty = pool == null ? 0L : pool.availableQty;
        long availableBasis = pool == null ? 0L : pool.availableBasisGp;
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
        return contribution(transaction, transaction.getCorrection(), transaction.getFlows());
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

    /** The row's sinks as it is booked now. */
    Map<Integer, Long> sinkQuantities(Transaction transaction) {
        return sinkQuantities(transaction, transaction.getCorrection(), transaction.getFlows());
    }

    Map<Integer, Long> sinkQuantities(
            Transaction transaction, Correction correction, List<Flow> flows) {
        // A drop or a shot still on the ground is not a use yet: picking it up shrinks the row. It
        // depletes the pool when its pickup window closes (Engine#settleGround).
        if (transaction.isOnGround()) return new LinkedHashMap<>();
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
            if (flow.quantityDelta <= 0L || isCurrency(flow.itemId)) continue;
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

/**
 * One canonical item's pooled tracked-basis state (schema 106).
 *
 * <p>It is supporting ownership state, never a financial authority: canonical counted transactions
 * stay the only financial truth. {@code available*} is the free pooled quantity/value that a new
 * SELL reservation or permanent sink may consume (a reserved share is held by its open SELL custody
 * record, not here); {@code realizationFenceEpochMillis} records the latest time pooled basis was
 * realized or permanently depleted, which fences retroactive acquisition corrections for this item.
 * No FIFO lots, no portfolio history, no mark-to-market.
 */
class BasisPool {
    int itemId;
    long availableQty;
    long availableBasisGp;
    long realizationFenceEpochMillis;

    /** Latest proven known-coverage acquisition; fences retroactive counted removals. */
    long latestAcquisitionEpochMillis;

    BasisPool() {}

    BasisPool(int itemId) {
        this.itemId = itemId;
    }
}

/**
 * Integer-only pooled basis math shared by the known-basis ledger and the read model.
 *
 * <p>Every allocation is floor-based and the remainder stays with the other side, so parts always
 * sum to the whole: no invented GP, no decimals, no drift. All multiplication is long-safe.
 */
class BasisMath {
    /**
     * Floor-share of a pooled quantity/basis pair; consuming everything takes the exact remainder.
     */
    static long shareOf(long totalQty, long totalBasisGp, long consumeQty) {
        long qty = nonNeg(totalQty);
        long basis = nonNeg(totalBasisGp);
        long consume = nonNeg(consumeQty);
        if (qty <= 0L || basis <= 0L || consume <= 0L) return 0L;
        if (consume >= qty) return basis;
        return multiplyDivide(basis, consume, qty);
    }

    /**
     * Known-coverage share of an observed settlement: the known portion takes the floor share and
     * the unknown remainder receives the integer difference, so both always sum to the settlement.
     */
    static long knownProceedsOf(long totalSettlementGp, long realizedQty, long knownQty) {
        long total = nonNeg(totalSettlementGp);
        long realized = nonNeg(realizedQty);
        long known = nonNeg(min(knownQty, realized));
        if (realized <= 0L || known <= 0L) return 0L;
        if (known >= realized) return total;
        return multiplyDivide(total, known, realized);
    }

    /** Long-safe floor(value × numerator / denominator) for non-negative inputs. */
    static long multiplyDivide(long value, long numerator, long denominator) {
        if (denominator <= 0L || numerator <= 0L || value <= 0L) return 0L;
        if (numerator >= denominator) return value;
        BigInteger product = BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator));
        BigInteger share = product.divide(BigInteger.valueOf(denominator));
        // b < c, so the share is below a and fits a long.
        return share.longValue();
    }
}
