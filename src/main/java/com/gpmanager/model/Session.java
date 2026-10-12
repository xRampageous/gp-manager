package com.gpmanager;

import static com.gpmanager.Accounting.*;
import static com.gpmanager.Correction.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.PauseReason.*;
import static com.gpmanager.Phrases.*;
import static com.gpmanager.SafeMath.*;

import static java.lang.Math.*;
import static java.util.Collections.*;

import com.gpmanager.Accounting.Amounts;
import com.gpmanager.CorrectionRecord.Change;

import lombok.*;

import java.util.*;
import java.util.function.Predicate;

class Session {
    /** The always-on General tracker is shown as "Overall". */
    static final String DURABLE_OWNER_NAME = "Overall";

    static final int MAX_UNDO_HISTORY = 32;
    String id;
    String name;
    String activityHint;
    SessionMode mode;

    /** True for a named Grind; false for the Overall (free play) session. */
    boolean grind;

    /** Runtime-only signal/revision for profile totals derived from session-day summaries. */
    transient Runnable changeListener;

    /** Runtime-only change count; a quick save reuses a closed session's JSON while it holds. */
    transient long saveStamp;

    /** Runtime-only pooled known-basis observer; the engine wires the tracked-basis ledger. */
    transient ValueObserver ownedValueObserver;

    /**
     * Canonical owned-value observer used by the schema-106 pooled known-basis ledger. Change
     * validation happens BEFORE any canonical mutation: a false answer must leave the transaction
     * exactly as it was. It never books GP itself.
     */
    interface ValueObserver {
        /** Validate a pending effective change; false blocks the mutation before it happens. */
        boolean canChange(
                Transaction transaction,
                Correction previousCorrection,
                List<Flow> previousFlows,
                Correction nextCorrection,
                List<Flow> nextFlows);

        /** Apply the exact effective delta after a permitted change. */
        void onChanged(
                Transaction transaction, Correction previousCorrection, List<Flow> previousFlows);

        /** Validate restoring a previously removed transaction; false blocks the restore. */
        boolean canAdd(Transaction transaction);

        /** Validate removing a retained transaction (undo); false blocks the removal. */
        boolean canRemove(Transaction transaction);

        /** Book a newly added canonical transaction's known coverage/sink depletion. */
        void onAdded(Transaction transaction, boolean custodyOwned);

        /** Reverse a removed transaction's known coverage. */
        void onRemoved(Transaction transaction);

        /** A row that was on the ground is used up: what is left on it leaves the record. */
        void onUsedUp(Transaction transaction);
    }

    /** A drop or shot nobody picked up is used up, off the ground and off the paid-price record. */
    void usedUp(Transaction row) {
        row.setOnGround(false);
        ValueObserver observer = ownedValueObserver;
        if (observer != null) observer.onUsedUp(row);
        notifyChanged();
    }

    void notifyOwnedValue(Transaction transaction, boolean custodyOwned) {
        ValueObserver observer = ownedValueObserver;
        if (observer != null) observer.onAdded(transaction, custodyOwned);
    }

    boolean canChangeOwnedValue(
            Transaction transaction,
            Correction previousCorrection,
            List<Flow> previousFlows,
            Correction nextCorrection,
            List<Flow> nextFlows) {
        ValueObserver observer = ownedValueObserver;
        return observer == null
                || observer.canChange(
                        transaction, previousCorrection, previousFlows, nextCorrection, nextFlows);
    }

    /** Announces a changed row: totals, PvP and the owned-value observer follow it. */
    void changed(Transaction transaction, Correction previousCorrection, List<Flow> previousFlows) {
        transactionChanged(transaction);
        ValueObserver observer = ownedValueObserver;
        if (observer != null) observer.onChanged(transaction, previousCorrection, previousFlows);
    }

    void log(long now, String reason, Change... changes) {
        correctionHistory.add(new CorrectionRecord(reason, Arrays.asList(changes)));
    }

    long startedAtEpochMillis;
    long endedAtEpochMillis;

    boolean paused;
    PauseReason pauseReason = NONE;
    long pausedAtEpochMillis;
    long totalPausedMillis;
    List<Transaction> transactions = new ArrayList<>();
    PkTally pk = new PkTally();

    List<CorrectionRecord> correctionHistory = new ArrayList<>();
    List<UndoRecord> undoHistory = new ArrayList<>();
    int transactionLimit;
    long retainedRevenue;
    long retainedCosts;
    long retainedSuppliesCosts;
    boolean retainedCostSplitComplete;
    long compactedTransactionCount;
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
        this.activityHint = "General";
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.transactionLimit = 2_000;
        this.retainedCostSplitComplete = true;
    }

    void addTransaction(Transaction transaction, int maxTransactions) {
        addTransaction(transaction, maxTransactions, false);
    }

    /**
     * @param custodyOwned true for transactions already realized by a custody lifecycle; their item
     *     legs consumed their reservation and must never deplete again
     */
    void addTransaction(Transaction transaction, int maxTransactions, boolean custodyOwned) {
        if (transaction == null) return;
        transactionLimit = max(1, maxTransactions);
        transactions.add(transaction);
        transactionChanged(transaction);
        notifyOwnedValue(transaction, custodyOwned);
        evict(transactions.size() - transactionLimit);
    }

    /** Drops the oldest rows over the limit, each summarized exactly into the retained totals. */
    void evict(int overflow) {
        if (overflow <= 0) return;
        var removed = new ArrayList<Transaction>(transactions.subList(0, overflow));
        transactions.subList(0, overflow).clear();
        for (Transaction transactionToRemove : removed) compactTransaction(transactionToRemove);
    }

    void recordAction(String hint, long now) {
        setActivityHint(normalizeActivity(hint), now);
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
        return activeTimeTargetMillis != null && activeTimeTargetMillis > 0L
                ? activeTimeTargetMillis
                : null;
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

    boolean isLinked() {
        return !getGrindId().isEmpty();
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
        saveStamp++;
        endedAtEpochMillis = max(now, startedAtEpochMillis);
    }

    boolean correctTransaction(
            String transactionId, Correction correction, long now, String reason) {
        Transaction transaction = findTransaction(transactionId);
        return transaction != null
                && correct(
                        singletonList(transaction),
                        correction == null ? AUTO : correction,
                        now,
                        reason);
    }

    /**
     * Automatic reconciliation of an automatic estimate against a measured Check: shrink it by
     * {@code quantity} of one item, voiding the row once it spends nothing. Automatic evidence, not
     * an owner correction, so nothing is logged; the change is announced like every other change.
     */
    void settleEstimate(Transaction estimate, int itemId, long quantity, String explanation) {
        if (estimate == null || itemId <= 0 || quantity <= 0L) return;
        Correction previous = estimate.getCorrection();
        List<Flow> previousFlows = estimate.getFlows();
        estimate.takeQuantity(itemId, false, quantity);
        estimate.confidence = Confidence.CONFIRMED;
        if (!estimate.spends()) estimate.applyCorrection(IGNORE, SafeMath.now(), explanation);
        estimate.setExplanation(explanation);
        changed(estimate, previous, previousFlows);
    }

    /**
     * Applies one decision to the still-unresolved transactions and stores one correction record,
     * so a single undo restores the complete operation.
     */
    int correctAll(List<String> transactionIds, Correction correction, long now, String reason) {
        Correction next = correction == null ? AUTO : correction;
        if (empty(transactionIds) || next == AUTO) return 0;
        var targets = new ArrayList<Transaction>();
        for (String transactionId : new LinkedHashSet<>(transactionIds)) {
            Transaction transaction = findTransaction(transactionId);
            if (ReviewRules.needsOwnerDecision(transaction)) targets.add(transaction);
        }
        return correct(targets, next, now, nonBlank(reason, msg("bulk-review")))
                ? targets.size()
                : 0;
    }

    /**
     * One owner decision over rows: every row is checked with the owned-value observer before any
     * changes (one blocked row rejects the whole decision), then one undoable record is logged.
     */
    boolean correct(List<Transaction> targets, Correction next, long now, String reason) {
        var changes = new ArrayList<Change>();
        for (Transaction transaction : targets) {
            if (!canChangeOwnedValue(
                    transaction,
                    transaction.getCorrection(),
                    transaction.getFlows(),
                    next,
                    transaction.getFlows())) {
                // The item's known coverage is reserved or already realized; leave the rows
                // untouched.
                return false;
            }
            changes.add(
                    new Change(transaction.getId(), transaction.getCorrection(), next, null, null));
        }
        for (Transaction transaction : targets) {
            Correction previous = transaction.getCorrection();
            transaction.applyCorrection(next, now, reason);
            changed(transaction, previous, transaction.getFlows());
        }
        if (!changes.isEmpty()) correctionHistory.add(new CorrectionRecord(reason, changes));
        return !changes.isEmpty();
    }

    /**
     * Personal item split: Net keeps only {@code keepQuantity} of {@code itemId}. Given-away qty is
     * removed from counted gain (not Used). Full give-away on a single-item row IGNORE-corrects.
     * Shares the correction undo stack.
     */
    boolean splitItem(
            String transactionId, int itemId, long keepQuantity, long now, String optionalNote) {
        Transaction transaction = findTransaction(transactionId);
        if (transaction == null || itemId <= 0) return false;
        long totalGain = transaction.quantity(itemId, true);
        if (totalGain <= 0L) return false;
        long keep = nonNeg(min(keepQuantity, totalGain));
        if (keep == totalGain) return false;
        String reason = ItemSplits.reason(keep, totalGain, optionalNote);
        Correction previous = transaction.getCorrection();
        var flowSnapshot = new ArrayList<Flow>(transaction.getFlows());
        String explanationSnapshot = transaction.getExplanation();
        boolean ignore = keep <= 0L && onlyGainItem(transaction, itemId);
        if (!canChangeOwnedValue(
                transaction, previous, flowSnapshot, ignore ? IGNORE : previous, null)) {
            // The known coverage this split would shrink is reserved or already realized.
            return false;
        }
        if (ignore) transaction.applyCorrection(IGNORE, now, reason);
        else transaction.takeQuantity(itemId, true, totalGain - keep);
        transaction.stampSplit(reason, now);
        changed(transaction, previous, flowSnapshot);
        log(
                now,
                reason,
                new Change(
                        transactionId,
                        previous,
                        transaction.getCorrection(),
                        flowSnapshot,
                        explanationSnapshot));
        return true;
    }

    /**
     * Reverts the most recent correction/split when a flow snapshot is present, or restores the
     * previous correction enum otherwise.
     */
    boolean undoCorrection(long now) {
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
            List<Flow> nextFlows =
                    change.hasFlowSnapshot() ? change.getFlowSnapshot() : transaction.getFlows();
            if (!canChangeOwnedValue(
                    transaction,
                    transaction.getCorrection(),
                    transaction.getFlows(),
                    change.getPreviousCorrection(),
                    nextFlows)) {
                return false;
            }
        }
        for (int index = changes.size() - 1; index >= 0; index--) {
            Change change = changes.get(index);
            Transaction transaction = targets.get(index);
            Correction previous = transaction.getCorrection();
            List<Flow> previousFlows = transaction.getFlows();
            if (change.hasFlowSnapshot()) transaction.replaceFlows(change.getFlowSnapshot());
            if (change.explanationSnapshot != null)
                transaction.setExplanation(change.explanationSnapshot);
            transaction.applyCorrection(change.getPreviousCorrection(), now);
            transaction.correctionReason = previousReason(transaction.getId(), last);
            changed(transaction, previous, previousFlows);
        }
        last.undone = true;
        return true;
    }

    /** Restore metadata from the retained decision timeline without adding save fields. */
    String previousReason(String transactionId, CorrectionRecord undone) {
        for (int index = correctionHistory.indexOf(undone) - 1; index >= 0; index--) {
            CorrectionRecord record = correctionHistory.get(index);
            if (record == null || record.isUndone()) continue;
            for (Change change : record.getChanges()) {
                if (transactionId.equals(change.getTransactionId())
                        && (!change.hasFlowSnapshot()
                                || change.getPreviousCorrection() != change.newCorrection
                                || ItemSplits.isSplitReason(record.getReason())))
                    return record.getReason();
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
     * A dropped item picked back up: the Dropped row loses that quantity, and is excluded once it
     * spends nothing, so the drop and its pickup never show as a loss and a gain. Returns how many
     * the row still held to give back.
     */
    long pickUp(String dropId, int itemId, long quantity, long now) {
        Transaction drop = findTransaction(dropId);
        if (drop == null || !drop.isCounted() || drop.getCorrection() != AUTO) return 0L;
        long taken = min(quantity, drop.quantity(itemId, false));
        if (taken <= 0L) return 0L;
        var flowSnapshot = new ArrayList<Flow>(drop.getFlows());
        drop.takeQuantity(itemId, false, taken);
        if (!drop.spends()) drop.applyCorrection(IGNORE, now, "Picked back up");
        changed(drop, AUTO, flowSnapshot);
        return taken;
    }

    Transaction undoTransaction() {
        if (transactions.isEmpty()) return null;
        Transaction removed = transactions.get(transactions.size() - 1);
        ValueObserver observer = ownedValueObserver;
        if (observer != null && !observer.canRemove(removed)) {
            // Known coverage was already reserved/realized downstream; leave it untouched.
            return null;
        }
        transactions.remove(transactions.size() - 1);
        notifyChanged();
        if (observer != null) observer.onRemoved(removed);
        undoHistory.add(new UndoRecord(removed));
        int overflow = undoHistory.size() - MAX_UNDO_HISTORY;
        if (overflow > 0) undoHistory.subList(0, overflow).clear();
        return removed;
    }

    Transaction restoreUndo() {
        for (int index = undoHistory.size() - 1; index >= 0; index--) {
            UndoRecord record = undoHistory.get(index);
            if (record == null || record.restored || record.transaction == null) continue;
            Transaction restored = record.transaction;
            ValueObserver observer = ownedValueObserver;
            if (observer != null && !observer.canAdd(restored)) {
                // Restoring would re-add known coverage after a later realization; refuse it.
                return null;
            }
            int effectiveLimit = transactionLimit <= 0 ? 2_000 : transactionLimit;
            // Restoring an undo is also a bounded-ledger insertion: the displaced row is summarized
            // exactly.
            evict(transactions.size() + 1 - effectiveLimit);
            transactions.add(restored);
            transactionChanged(restored);
            notifyOwnedValue(restored, false);
            record.restored = true;
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

    /** Counts one kill or death on this Grind and returns the id its receipts carry. */
    String addEncounter(boolean kill, long now) {
        String id = pk().record(kill);
        notifyChanged();
        recordAction("PKing", now);
        return id;
    }

    void attachToEncounter(String transactionId, String encounterId, boolean asPkSupplyCost) {
        Transaction transaction = findTransaction(transactionId);
        if (transaction == null || empty(encounterId) || !PkTally.isEncounter(encounterId)) return;
        saveStamp++;
        transaction.assignEncounter(encounterId, asPkSupplyCost);
        notifyChanged();
    }

    void attachRecentCosts(String encounterId, long now, long lookbackMillis) {
        long cutoff = nonNeg(now - max(0L, lookbackMillis));
        for (int index = transactions.size() - 1; index >= 0; index--) {
            Transaction transaction = transactions.get(index);
            if (transaction.timestampEpochMillis < cutoff) break;
            if (!transaction.getEncounterId().isEmpty()) continue;
            if (transaction.revenue == 0L
                    && transaction.costs > 0L
                    && transaction.autoType() == TransactionType.CONSUMPTION) {
                attachToEncounter(transaction.getId(), encounterId, true);
            }
        }
    }

    void compactTransaction(Transaction transaction) {
        if (transaction == null) return;
        compactedTransactionCount++;
        if (transaction.isOnGround()) usedUp(transaction);
        pk().compact(transaction, transactions);
        if (transaction.getType() == TransactionType.TRANSFER) return;
        if (transaction.isCounted()) {
            Amounts amounts = transaction(transaction);
            retainedRevenue = safeAdd(retainedRevenue, amounts.revenue);
            retainedCosts = safeAdd(retainedCosts, amounts.costs);
            Accounting.CostSplit split = costSplit(transaction);
            if (!split.available) retainedCostSplitComplete = false;
            else retainedSuppliesCosts = safeAdd(retainedSuppliesCosts, split.supplies);
        }
    }

    /** A counted row changed: advance the revision. */
    void transactionChanged(Transaction transaction) {
        if (transaction != null) notifyChanged();
    }

    PkTally pk() {
        if (pk == null) pk = new PkTally();
        return pk;
    }

    long bestKill() {
        return pk().bestKill(transactions);
    }

    @AllArgsConstructor
    static class MoneyFold {
        long revenue;
        long costs;
        long supplies;
        boolean costSplitAvailable = true;

        Amounts add(Transaction transaction) {
            Amounts amounts = transaction(transaction);
            if (!amounts.available || !amounts.included) return amounts;
            revenue = safeAdd(revenue, amounts.revenue);
            costs = safeAdd(costs, amounts.costs);
            Accounting.CostSplit split = costSplit(transaction);
            if (!split.available) costSplitAvailable = false;
            else supplies = safeAdd(supplies, split.supplies);
            return amounts;
        }
    }

    Metrics metrics(long now) {
        var money =
                new MoneyFold(
                        retainedRevenue,
                        retainedCosts,
                        retainedSuppliesCosts,
                        compactedTransactionCount == 0L || retainedCostSplitComplete);
        for (Transaction transaction : transactions) {
            if (transaction == null) continue;
            money.add(transaction);
        }
        if (money.supplies > money.costs) money.costSplitAvailable = false;
        long net = safeSubtract(money.revenue, money.costs);
        long elapsed = getElapsedMillis(now);
        return new Metrics(
                getActivityHint(),
                paused,
                elapsed,
                money.revenue,
                money.costs,
                net,
                hourly(net, elapsed),
                money.costSplitAvailable ? money.supplies : 0L,
                money.costSplitAvailable ? safeSubtract(money.costs, money.supplies) : 0L,
                money.costSplitAvailable);
    }

    /**
     * The correction-aware money folded out of individually inspectable rows by compaction.
     * Retained rows plus this contribution reconcile exactly to {@link #metrics}.
     */
    @AllArgsConstructor
    static class CompactedContribution {
        long rows;
        long revenue;
        long costs;
        long suppliesCosts;
        boolean costSplitAvailable;

        long getNet() {
            return safeSubtract(revenue, costs);
        }
    }

    CompactedContribution compactedContribution() {
        return new CompactedContribution(
                compactedTransactionCount,
                retainedRevenue,
                retainedCosts,
                retainedSuppliesCosts,
                compactedTransactionCount == 0L || retainedCostSplitComplete);
    }

    long compactedRevenue() {
        return retainedRevenue;
    }

    long compactedCosts() {
        return retainedCosts;
    }

    static long hourly(long value, long elapsedMillis) {
        if (elapsedMillis <= 0L) return 0L;
        // Math.round saturates at the long range by itself.
        return round(value * (3_600_000.0d / elapsedMillis));
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

    String getName() {
        return normalizeName(name);
    }

    void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    void notifyChanged() {
        saveStamp++;
        Runnable listener = changeListener;
        if (listener != null) listener.run();
    }

    String getActivityHint() {
        return normalizeActivity(activityHint);
    }

    SessionMode getMode() {
        return mode == null ? SessionMode.GENERAL : mode;
    }

    boolean isClosed() {
        return endedAtEpochMillis != 0L;
    }

    Long getProfitTargetGp() {
        return profitTargetGp != null && profitTargetGp > 0L ? profitTargetGp : null;
    }

    List<Transaction> getTransactions() {
        return unmodifiableList(transactions);
    }

    /** Timestamp of the oldest retained row, or {@code Long.MIN_VALUE} when none is retained. */

    /**
     * Receipt retention (section L): folds retained rows dated before {@code cutoffEpochMillis}
     * into this session's correction-aware archive totals, oldest first, skipping rows the caller
     * still needs individually (an unresolved claim's audit row or its settlement). Undo/correction
     * entries that reference a folded row are pruned at the same boundary. Compacted rows are no
     * longer individually correctable; totals stay exact.
     */
    int compactBefore(long cutoffEpochMillis, Predicate<Transaction> keep) {
        if (transactions.isEmpty()) return 0;
        var folded = new ArrayList<Transaction>();
        for (Transaction transaction : transactions) {
            if (transaction == null) continue;
            if (transaction.timestampEpochMillis >= cutoffEpochMillis) continue;
            if (keep != null && keep.test(transaction)) continue;
            folded.add(transaction);
        }
        if (folded.isEmpty()) return 0;
        saveStamp++;
        var foldedIds = new HashSet<String>();
        for (Transaction transaction : folded) {
            foldedIds.add(transaction.getId());
            transactions.remove(transaction);
            compactTransaction(transaction);
        }
        undoHistory.removeIf(
                record ->
                        record != null
                                && record.transaction != null
                                && foldedIds.contains(record.transaction.getId()));
        correctionHistory.removeIf(record -> record != null && record.touchesAny(foldedIds));
        return folded.size();
    }
}

/**
 * AUTO sessions follow the detected activity; every session the engine opens (Overall and each
 * Grind) is AUTO. GENERAL is the plain default of a bare {@code new Session}.
 */
enum SessionMode {
    AUTO,
    GENERAL
}

/** Persisted ownership of a paused session. NONE means the session is running. */
enum PauseReason {
    NONE,
    MANUAL,
    IDLE,
    LIFECYCLE,
    RECOVERY,
    CUSTOM_SESSION
}

/** A transaction removed through the undo action, kept so the latest one can be restored. */
class UndoRecord {
    Transaction transaction;
    boolean restored;

    UndoRecord() {
        // Gson
    }

    UndoRecord(Transaction transaction) {
        this.transaction = transaction;
    }
}
