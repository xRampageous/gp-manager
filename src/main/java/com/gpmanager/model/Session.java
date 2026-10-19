package com.gpmanager;


import static com.gpmanager.FlowFilters.matching;

import com.gpmanager.GpManagerConfig.Retention;

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
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;
import lombok.*;

class Session extends SaveCopies {
    /** The always-on General tracker is shown as "Overall". */
    static final String DURABLE_OWNER_NAME = "Overall";

    static final int MAX_UNDO_HISTORY = 32;
    String id, name, activityHint;
    SessionMode mode;

    /** True for a named Grind; false for the Overall (free play) session. */
    boolean grind;

    /** Runtime-only signal/revision for profile totals derived from session-day summaries. */
    transient Runnable changeListener;

    /** Runtime-only change count for closed-session financial view invalidation. */
    transient long saveStamp;

    /** Runtime-only pooled known-basis observer; the engine wires the tracked-basis ledger. */
    transient TrackedBasisLedger ownedValueObserver;

    /** A drop or shot nobody picked up is used up, off the ground and off the paid-price record. */
    void usedUp(Transaction row) {
        row.setOnGround(false);
        if (ownedValueObserver != null) ownedValueObserver.onUsedUp(row);
        notifyChanged();
    }

    void notifyOwnedValue(Transaction transaction, boolean owned) {
        if (ownedValueObserver != null) ownedValueObserver.onAdded(transaction, owned);
    }

    boolean canChangeOwnedValue(Transaction transaction, Correction previous, List<Flow> previousFlows,
            Correction nextCorrection, List<Flow> next) {
        return ownedValueObserver == null || ownedValueObserver.canChange(
                        transaction, previous, previousFlows, nextCorrection, next);
    }

    /** Announces a changed row: totals, PvP and the owned-value observer follow it. */
    void changed(Transaction transaction, Correction previous, List<Flow> previousFlows) {
        transactionChanged(transaction);
        if (ownedValueObserver != null)
            ownedValueObserver.onChanged(transaction, previous, previousFlows);
    }

    void log(long now, String reason, Change... changes) {
        correctionHistory.add(new CorrectionRecord(reason, Arrays.asList(changes)));
    }

    long startedAtEpochMillis, endedAtEpochMillis;

    boolean paused;
    PauseReason pauseReason = NONE;
    long pausedAtEpochMillis, totalPausedMillis;
    List<Transaction> transactions = new ArrayList<>();
    PkTally pk = new PkTally();

    List<CorrectionRecord> correctionHistory = new ArrayList<>();
    List<UndoRecord> undoHistory = new ArrayList<>();
    int transactionLimit;
    long retainedRevenue, retainedCosts, retainedSuppliesCosts;
    boolean retainedCostSplitComplete;
    long compactedTransactionCount;
    // Nullable by design: a target is owner-local presentation metadata, never
    // an accounting input or generated transaction.
    Long profitTargetGp;
    // The session's Net when the target above was last set: the aim counts what has been earned
    // since (owner live 2026-10-14). Null on older saves counts from the run's start.
    Long profitTargetSetNet;

    /** The run's Active Time when the Net aim was set; its pace counts from there (owner 2026-10-16). */
    Long profitTargetSetActiveMillis;

    /**
     * When Live's current session began (New session, or a target set), and the run's Active Time
     * then: Live counts from here while the run keeps everything (owner 1.5.2). Null shows the
     * whole run (a fresh Grind, older saves).
     */
    Long sessionStartAt;

    long sessionStartMillis;

    /** Optional rate-only restart; older saves use the normal Live session window. */
    Long rateStartAt;
    long rateSetMillis;
    /** Optional frozen clock backup and the time resumed by Restore time. */
    Long savedRateMillis, restoredRateMillis;
    /** Frozen displayed duration of a saved copy; absent on canonical runs. */
    Long savedActiveMillis;

    long liveElapsed(long now) {
        long elapsed = getElapsedMillis(now), from = sessionStartAt == null ? 0 : sessionStartMillis;
        if (rateStartAt != null && rateSetMillis >= 0 && rateSetMillis <= elapsed && rateStartAt <= now
                && ((rateSetMillis >= from && rateStartAt >= (sessionStartAt == null ? startedAtEpochMillis : sessionStartAt))
                    || (rateSetMillis == 0 && rateStartAt == startedAtEpochMillis))
                && (restoredRateMillis == null || restoredRateMillis >= 0 && restoredRateMillis <= rateSetMillis))
            return elapsed - rateSetMillis + (restoredRateMillis == null ? 0 : restoredRateMillis);
        return nonNeg(elapsed - from);
    }

    /** Free play's Net milestone: it counts Free play's whole Net (owner 1.5.2); null with none. */
    Long milestoneGp;
    // Signature targets: the actual Active-Time aim this Grind instance ran with, and the stable
    // My Grind lineage link it was started from. Both are metadata; neither books anything.
    Long activeTimeTargetMillis;
    // The session's own Active Time when the target above was last set; null on older saves and
    // fresh sessions, which keep counting from the run's start (owner live 2026-10-13).
    Long activeTimeTargetSetMillis;
    // The timer's own pause: the Active Time it was frozen at; null while it runs (owner live
    // 2026-10-14). Distinct from a session pause — the run keeps tracking.
    Long activeTimeTargetPauseMillis;
    String grindId;

    Session() {
        // Gson
    }

    Session(String name, long startedAtEpochMillis, SessionMode mode) {
        id = uuid();
        this.name = normalizeName(name);
        this.mode = mode == null ? SessionMode.GENERAL : mode;
        activityHint = "General";
        this.startedAtEpochMillis = startedAtEpochMillis;
        transactionLimit = 2_000;
        retainedCostSplitComplete = true;
    }

    /**
     * @param custodyOwned true for transactions already realized by a custody lifecycle; their item
     *     legs consumed their reservation and must never deplete again
     */
    void addTransaction(Transaction transaction, int maxTransactions, boolean owned) {
        if (transaction == null) return;
        transactionLimit = max(1, maxTransactions);
        transactions.add(transaction);
        transactionChanged(transaction);
        notifyOwnedValue(transaction, owned);
        evict(transactions.size() - transactionLimit);
    }

    /** Drops the oldest rows over the limit, each summarized exactly into the retained totals. */
    void evict(int overflow) {
        if (overflow <= 0) return;
        var removed = new ArrayList<>(transactions.subList(0, overflow));
        transactions.subList(0, overflow).clear();
        for (var transactionToRemove : removed) compactTransaction(transactionToRemove);
    }

    void recordAction(String hint, long now) {
        setActivityHint(normalizeActivity(hint), now);
    }

    void setActivityHint(String hint, long now) {
        if (blank(hint)) return;
        String next = normalizeActivity(hint), current = getActivityHint();
        if (next.equalsIgnoreCase(current)) return;
        activityHint = next;
    }

    void rename(String value) {
        name = normalizeName(value);
    }

    boolean setProfitTargetGp(Long value) {
        return setProfitTargetGp(value, 0, 0);
    }

    /**
     * Sets the Net aim and the run's Net at the set moment (owner live 2026-10-14): a changed value
     * counts from now, as does the same value once the aim is already reached; an unchanged,
     * unreached value keeps its count.
     */
    boolean setProfitTargetGp(Long value, long net, long activeMillis) {
        Long next = value == null || value <= 0 ? null : value;
        if (Objects.equals(next, profitTargetGp)) {
            return next != null && profitTargetProgress(net) >= next
                    && rebaseProfitTarget(net, activeMillis);
        }
        profitTargetGp = next;
        rebaseProfitTarget(net, activeMillis);
        return true;
    }

    /** The run's Net when the aim was set; 0 with none and on older saves. */
    long getProfitTargetSetNet() {
        return profitTargetSetNet == null || profitTargetSetNet < 0 ? 0L : profitTargetSetNet;
    }

    /** What the Net aim has counted so far; sessionNet is the run's total. */
    long profitTargetProgress(long net) {
        return sessionStartAt != null ? metrics(rowsSince(sessionStartAt), 0).net
                : net - getProfitTargetSetNet();
    }

    /**
     * Re-bases the set Net aim's count on the given net, keeping its value (owner live 2026-10-14):
     * Reset counts the aim from now whether or not it was reached.
     */
    boolean rebaseProfitTarget(long net, long activeMillis) {
        profitTargetSetNet = profitTargetGp == null ? null : net;
        profitTargetSetActiveMillis = profitTargetGp == null ? null : activeMillis;
        return profitTargetGp != null;
    }

    /** Active Time the Net aim has run for; the whole run on older saves. */
    long profitTargetActiveMillis(long elapsed) {
        return nonNeg(elapsed - (profitTargetSetActiveMillis == null ? 0L : profitTargetSetActiveMillis));
    }

    /** The actual Active-Time target this instance ran with; null when none was set. */
    Long getActiveTimeTargetMillis() {
        return activeTimeTargetMillis != null && activeTimeTargetMillis > 0 ? activeTimeTargetMillis : null;
    }

    /**
     * Sets the actual Active-Time target and restarts its count when the value changes (owner live
     * 2026-10-13): a target counts from the moment it was set, not from the run's start. An
     * unchanged value keeps the running count; a cleared one forgets it.
     */
    boolean setActiveTimeTargetMillis(Long value, long now) {
        Long next = value == null || value <= 0 ? null : value;
        if (Objects.equals(next, activeTimeTargetMillis)) {
            // Re-saving the same aim keeps a running window; a completed one starts over
            // (owner live 2026-10-13: Edit > Save after completion must begin again).
            if (next != null && getElapsedMillis(now) - getActiveTimeTargetSetMillis() >= next) {
                activeTimeTargetSetMillis = getElapsedMillis(now);
                activeTimeTargetPauseMillis = null;
                return true;
            }
            return false;
        }
        activeTimeTargetMillis = next;
        activeTimeTargetSetMillis = next == null ? null : getElapsedMillis(now);
        activeTimeTargetPauseMillis = null;
        return true;
    }

    /** The Active Time a set target counts from; 0 with none and on older saves. */
    long getActiveTimeTargetSetMillis() {
        return activeTimeTargetSetMillis == null || activeTimeTargetSetMillis < 0 ? 0L : activeTimeTargetSetMillis;
    }

    /** The window a set target has counted so far; frozen while the timer is paused. */
    long getActiveTimeTargetWindowMillis(long elapsed) {
        long at = activeTimeTargetPauseMillis != null ? activeTimeTargetPauseMillis : nonNeg(elapsed);
        return nonNeg(at - getActiveTimeTargetSetMillis());
    }

    /** True while the timer's own pause holds; the run keeps tracking (owner live 2026-10-14). */
    boolean isTimeTargetPaused() {
        return activeTimeTargetPauseMillis != null;
    }

    /** Freezes the timer's window where it stands; an ended window has nothing to stop. */
    boolean pauseTimeTarget(long now) {
        if (activeTimeTargetMillis == null || activeTimeTargetPauseMillis != null) return false;
        if (getActiveTimeTargetWindowMillis(getElapsedMillis(now)) >= activeTimeTargetMillis) {
            return false;
        }
        activeTimeTargetPauseMillis = getElapsedMillis(now);
        return true;
    }

    /** Resumes a paused timer, keeping the window it had already counted. */
    boolean resumeTimeTarget(long now) {
        if (activeTimeTargetMillis == null || activeTimeTargetPauseMillis == null) return false;
        activeTimeTargetSetMillis = getActiveTimeTargetSetMillis()
                        + nonNeg(getElapsedMillis(now) - activeTimeTargetPauseMillis);
        activeTimeTargetPauseMillis = null;
        return true;
    }

    /**
     * Restarts the set target's count from now without changing its value (owner live 2026-10-13).
     */
    void rearmTimeTarget(long now) {
        if (activeTimeTargetMillis != null) {
            activeTimeTargetSetMillis = getElapsedMillis(now);
            activeTimeTargetPauseMillis = null;
        }
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
        if (!paused && endedAtEpochMillis == 0) {
            paused = true;
            pausedAtEpochMillis = now;
            pauseReason = reason == null ? MANUAL : reason;
        }
    }

    void resume(long now) {
        if (paused && endedAtEpochMillis == 0) {
            totalPausedMillis = safeAdd(totalPausedMillis, nonNeg(now - pausedAtEpochMillis));
            pausedAtEpochMillis = 0;
            paused = false;
        }
        pauseReason = NONE;
    }

    PauseReason getPauseReason() {
        if (!paused) return NONE;
        return pauseReason == null || pauseReason == NONE ? MANUAL : pauseReason;
    }

    /** A closed run carries on: the time it was closed counts as paused (owner 1.5.2, Restore). */
    void reopen(long now) {
        if (paused) totalPausedMillis = safeAdd(totalPausedMillis, nonNeg(endedAtEpochMillis - pausedAtEpochMillis));
        totalPausedMillis = safeAdd(totalPausedMillis, nonNeg(now - endedAtEpochMillis));
        paused = false;
        pausedAtEpochMillis = 0;
        pauseReason = NONE;
        endedAtEpochMillis = 0;
        saveStamp++;
    }

    void close(long now) {
        if (endedAtEpochMillis != 0) return;
        saveStamp++;
        endedAtEpochMillis = max(now, startedAtEpochMillis);
    }

    boolean correctTransaction(String id, Correction decision, long now, String reason) {
        var transaction = findTransaction(id);
        return transaction != null && correct(singletonList(transaction), decision == null ? AUTO : decision, now,
                        reason);
    }

    /**
     * Automatic reconciliation of an automatic estimate against a measured Check: shrink it by
     * {@code quantity} of one item, voiding the row once it spends nothing. Automatic evidence, not
     * an owner correction, so nothing is logged; the change is announced like every other change.
     */
    void settleEstimate(Transaction estimate, int id, long quantity, String reason) {
        if (estimate == null || id <= 0 || quantity <= 0) return;
        var previous = estimate.getCorrection();
        var previousFlows = estimate.getFlows();
        estimate.takeQuantity(id, false, quantity);
        estimate.confidence = Confidence.CONFIRMED;
        if (!estimate.spends()) estimate.applyCorrection(IGNORE, now(), reason);
        estimate.setExplanation(reason);
        changed(estimate, previous, previousFlows);
    }

    /**
     * Applies one decision to the still-unresolved transactions and stores one correction record,
     * so a single undo restores the complete operation.
     */
    int correctAll(List<String> ids, Correction decision, long now, String reason) {
        var next = decision == null ? AUTO : decision;
        if (empty(ids) || next == AUTO) return 0;
        var targets = new ArrayList<Transaction>();
        for (String id : new LinkedHashSet<>(ids)) {
            var transaction = findTransaction(id);
            if (ReviewRules.needsOwnerDecision(transaction)) targets.add(transaction);
        }
        return correct(targets, next, now, nonBlank(reason, msg("bulk-review"))) ? targets.size() : 0;
    }

    /**
     * One owner decision over rows: every row is checked with the owned-value observer before any
     * changes (one blocked row rejects the whole decision), then one undoable record is logged.
     */
    boolean correct(List<Transaction> targets, Correction next, long now, String reason) {
        var changes = new ArrayList<Change>();
        for (var transaction : targets) {
            if (!canChangeOwnedValue(transaction, transaction.getCorrection(), transaction.getFlows(), next,
                    transaction.getFlows())) {
                // The item's known coverage is reserved or already realized; leave the rows
                // untouched.
                return false;
            }
            changes.add(new Change(transaction.getId(), transaction.getCorrection(), next, null, null));
        }
        for (var transaction : targets) {
            var previous = transaction.getCorrection();
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
    boolean splitItem(String identifier, int id, long keepQuantity, long now, String note) {
        var transaction = findTransaction(identifier);
        if (transaction == null || id <= 0) return false;
        long totalGain = transaction.quantity(id, true);
        if (totalGain <= 0) return false;
        long keep = nonNeg(min(keepQuantity, totalGain));
        if (keep == totalGain) return false;
        String reason = ItemSplits.reason(keep, totalGain, note);
        var previous = transaction.getCorrection();
        var flowSnapshot = new ArrayList<>(transaction.getFlows());
        String explanationSnapshot = transaction.getExplanation();
        boolean ignore = keep <= 0 && onlyGainItem(transaction, id);
        if (!canChangeOwnedValue(transaction, previous, flowSnapshot, ignore ? IGNORE : previous, null)) {
            // The known coverage this split would shrink is reserved or already realized.
            return false;
        }
        if (ignore) transaction.applyCorrection(IGNORE, now, reason);
        else transaction.takeQuantity(id, true, totalGain - keep);
        transaction.stampSplit(reason, now);
        changed(transaction, previous, flowSnapshot);
        log(now, reason, new Change(identifier, previous, transaction.getCorrection(), flowSnapshot,
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
            var candidate = correctionHistory.get(index);
            if (candidate != null && !candidate.isUndone()) {
                last = candidate;
                break;
            }
        }
        if (last == null) return false;
        var changes = last.getChanges();
        if (changes.isEmpty()) return false;
        var targets = new ArrayList<Transaction>(changes.size());
        for (Change change : changes) {
            var transaction = findTransaction(change.getTransactionId());
            if (transaction == null) {
                // Fail closed: never partially undo a batch after retention or deletion.
                return false;
            }
            targets.add(transaction);
        }
        // Validate the whole restore before any canonical mutation.
        for (int index = 0; index < changes.size(); index++) {
            var change = changes.get(index);
            var transaction = targets.get(index);
            var next = change.hasFlowSnapshot() ? change.getFlowSnapshot() : transaction.getFlows();
            if (!canChangeOwnedValue(transaction, transaction.getCorrection(), transaction.getFlows(),
                    change.getPreviousCorrection(), next)) {
                return false;
            }
        }
        for (int index = changes.size() - 1; index >= 0; index--) {
            var change = changes.get(index);
            var transaction = targets.get(index);
            var previous = transaction.getCorrection();
            var previousFlows = transaction.getFlows();
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
    String previousReason(String id, CorrectionRecord undone) {
        for (int index = correctionHistory.indexOf(undone) - 1; index >= 0; index--) {
            var record = correctionHistory.get(index);
            if (record == null || record.isUndone()) continue;
            for (Change change : record.getChanges()) {
                if (id.equals(change.getTransactionId()) && (!change.hasFlowSnapshot()
                                || change.getPreviousCorrection() != change.newCorrection
                                || ItemSplits.isSplitReason(record.getReason())))
                    return record.getReason();
            }
        }
        return "";
    }

    static boolean onlyGainItem(Transaction transaction, int id) {
        if (transaction == null) return false;
        boolean sawTarget = false;
        for (Flow flow : transaction.getFlows()) {
            if (flow.quantityDelta == 0) continue;
            if (flow.itemId != id) return false;
            if (flow.quantityDelta > 0) sawTarget = true;
        }
        return sawTarget;
    }

    /**
     * A dropped item picked back up: the Dropped row loses that quantity, and is excluded once it
     * spends nothing, so the drop and its pickup never show as a loss and a gain. Returns how many
     * the row still held to give back.
     */
    long pickUp(String dropId, int id, long quantity, long now) {
        var drop = findTransaction(dropId);
        if (drop == null || !drop.isCounted() || drop.getCorrection() != AUTO) return 0;
        long taken = min(quantity, drop.quantity(id, false));
        if (taken <= 0) return 0;
        var flowSnapshot = new ArrayList<>(drop.getFlows());
        drop.takeQuantity(id, false, taken);
        if (!drop.spends()) drop.applyCorrection(IGNORE, now, "Picked back up");
        changed(drop, AUTO, flowSnapshot);
        return taken;
    }

    /** Drops a neutral row the engine no longer keeps (old bank-paid GE change, owner 1.5.1). */
    void drop(Transaction transaction) {
        if (!transactions.remove(transaction)) return;
        saveStamp++;
        notifyChanged();
        if (ownedValueObserver != null) ownedValueObserver.onRemoved(transaction);
    }

    Transaction undoTransaction() {
        if (transactions.isEmpty()) return null;
        var removed = transactions.get(transactions.size() - 1);
        if (ownedValueObserver != null && !ownedValueObserver.canRemove(removed)) {
            // Known coverage was already reserved/realized downstream; leave it untouched.
            return null;
        }
        transactions.remove(transactions.size() - 1);
        notifyChanged();
        if (ownedValueObserver != null) ownedValueObserver.onRemoved(removed);
        undoHistory.add(new UndoRecord(removed));
        int overflow = undoHistory.size() - MAX_UNDO_HISTORY;
        if (overflow > 0) undoHistory.subList(0, overflow).clear();
        return removed;
    }

    Transaction restoreUndo() {
        for (int index = undoHistory.size() - 1; index >= 0; index--) {
            var record = undoHistory.get(index);
            if (record == null || record.restored || record.transaction == null) continue;
            var restored = record.transaction;
            if (ownedValueObserver != null && !ownedValueObserver.canAdd(restored)) {
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

    Transaction findTransaction(String id) {
        if (empty(id)) return null;
        for (var transaction : transactions) {
            if (id.equals(transaction.getId())) return transaction;
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

    void attachToEncounter(String id, String encounter, boolean pkSupply) {
        var transaction = findTransaction(id);
        if (transaction == null || empty(encounter) || !PkTally.isEncounter(encounter)) return;
        saveStamp++;
        transaction.assignEncounter(encounter, pkSupply);
        notifyChanged();
    }

    void attachRecentCosts(String encounter, long now, long lookbackMillis) {
        long cutoff = nonNeg(now - nonNeg(lookbackMillis));
        for (int index = transactions.size() - 1; index >= 0; index--) {
            var transaction = transactions.get(index);
            if (transaction.timestampEpochMillis < cutoff) break;
            if (!transaction.getEncounterId().isEmpty()) continue;
            if (transaction.revenue == 0 && transaction.costs > 0
                    && transaction.autoType() == TransactionType.CONSUMPTION) {
                attachToEncounter(transaction.getId(), encounter, true);
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
            var money = transaction(transaction);
            retainedRevenue = safeAdd(retainedRevenue, money.revenue);
            retainedCosts = safeAdd(retainedCosts, money.costs);
            var split = costSplit(transaction);
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
        long revenue, costs, supplies;
        boolean costSplitAvailable = true;

        Amounts add(Transaction transaction) {
            var money = transaction(transaction);
            if (!money.available || !money.included) return money;
            revenue = safeAdd(revenue, money.revenue);
            costs = safeAdd(costs, money.costs);
            var split = costSplit(transaction);
            if (!split.available) costSplitAvailable = false;
            else supplies = safeAdd(supplies, split.supplies);
            return money;
        }
    }

    Metrics metrics(long now) {
        return metrics(new MoneyFold(retainedRevenue, retainedCosts, retainedSuppliesCosts,
                        compactedTransactionCount == 0 || retainedCostSplitComplete), transactions,
                getElapsedMillis(now));
    }

    /**
     * Rows booked at or after {@code at}: a target run's own (owner 1.5.2). A correction keeps its
     * row's time, so fixes to older rows stay out; rows compacted past the row limit drop out.
     */
    List<Transaction> rowsSince(long at) {
        return matching(transactions, row -> row != null && row.timestampEpochMillis >= at);
    }

    /** The money in these rows alone, over the given Active Time. */
    Metrics metrics(List<Transaction> rows, long elapsed) {
        return metrics(new MoneyFold(0L, 0L, 0L, true), rows, elapsed);
    }

    Metrics metrics(MoneyFold money, List<Transaction> rows, long elapsed) {
        for (var transaction : rows) {
            if (transaction == null) continue;
            money.add(transaction);
        }
        if (money.supplies > money.costs) money.costSplitAvailable = false;
        long net = safeSubtract(money.revenue, money.costs);
        return new Metrics(getActivityHint(), paused, elapsed, money.revenue, money.costs, net, hourly(net, elapsed),
                money.costSplitAvailable ? money.supplies : 0,
                money.costSplitAvailable ? safeSubtract(money.costs, money.supplies) : 0, money.costSplitAvailable);
    }

    /**
     * The correction-aware money folded out of individually inspectable rows by compaction.
     * Retained rows plus this contribution reconcile exactly to {@link #metrics}.
     */
    @AllArgsConstructor
    static class CompactedContribution {
        long rows, revenue, costs, suppliesCosts;
        boolean costSplitAvailable;

        long getNet() {
            return safeSubtract(revenue, costs);
        }
    }

    CompactedContribution compactedContribution() {
        return new CompactedContribution(compactedTransactionCount, retainedRevenue, retainedCosts,
                retainedSuppliesCosts, compactedTransactionCount == 0 || retainedCostSplitComplete);
    }

    long compactedRevenue() {
        return retainedRevenue;
    }

    long compactedCosts() {
        return retainedCosts;
    }

    static long hourly(long value, long elapsed) {
        if (elapsed <= 0) return 0;
        // Math.round saturates at the long range by itself.
        return round(value * (3_600_000.0d / elapsed));
    }

    long getElapsedMillis(long now) {
        if (endedAtEpochMillis != 0 && savedActiveMillis != null && savedActiveMillis >= 0)
            return savedActiveMillis;
        long endpoint = endedAtEpochMillis == 0 ? now : endedAtEpochMillis;
        long activePaused = paused ? nonNeg(endpoint - pausedAtEpochMillis) : 0;
        return nonNeg(endpoint - startedAtEpochMillis - totalPausedMillis - activePaused);
    }

    static String normalizeName(String value) {
        return nonBlank(value, DURABLE_OWNER_NAME);
    }

    String getId() {
        if (empty(id)) id = uuid();
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
        var listener = changeListener;
        if (listener != null) listener.run();
    }

    String getActivityHint() {
        return normalizeActivity(activityHint);
    }

    SessionMode getMode() {
        return mode == null ? SessionMode.GENERAL : mode;
    }

    boolean isClosed() {
        return endedAtEpochMillis != 0;
    }

    Long getProfitTargetGp() {
        return profitTargetGp != null && profitTargetGp > 0 ? profitTargetGp : null;
    }

    /** A loaded session is cleaned once: no null rows or flows reach any reader. */
    void sanitize() {
        if (transactions == null) transactions = new ArrayList<>();
        transactions.removeIf(Objects::isNull);
        for (var transaction : transactions) transaction.sanitize();
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
        for (var transaction : transactions) {
            if (transaction == null) continue;
            if (transaction.timestampEpochMillis >= cutoffEpochMillis) continue;
            if (keep != null && keep.test(transaction)) continue;
            folded.add(transaction);
        }
        if (folded.isEmpty()) return 0;
        saveStamp++;
        var foldedIds = new HashSet<String>();
        for (var transaction : folded) {
            foldedIds.add(transaction.getId());
            transactions.remove(transaction);
            compactTransaction(transaction);
        }
        undoHistory.removeIf(record -> record != null && record.transaction != null
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
    AUTO, GENERAL
}

/** Persisted ownership of a paused session. NONE means the session is running. */
enum PauseReason {
    NONE, MANUAL, IDLE, LIFECYCLE, RECOVERY, CUSTOM_SESSION, WORLD
}

/** A transaction removed through the undo action, kept so the latest one can be restored. */
class UndoRecord extends SaveCopies {
    Transaction transaction;
    boolean restored;

    UndoRecord() {
        // Gson
    }

    UndoRecord(Transaction transaction) {
        this.transaction = transaction;
    }
}

/** Only the canonical money, active clock and rate facts consumed by presentation and export. */
class Metrics {
    String activityHint;
    boolean paused;
    long elapsedMillis, revenue, costs, net, profitPerHour, suppliesCosts, otherCosts;
    boolean costSplitAvailable;

    static Metrics none() {
        return new Metrics("General", false, 0L, 0L, 0L, 0L, 0L);
    }

    Metrics(String activityHint, boolean paused, long elapsed, long income, long cost, long net,
            long rate) {
        this(activityHint, paused, elapsed, income, cost, net, rate, 0, 0, false);
    }

    Metrics(String activityHint, boolean paused, long elapsed, long income, long cost, long net,
            long rate, long suppliesCosts, long otherCosts, boolean costSplitAvailable) {
        this.activityHint = activityHint;
        this.paused = paused;
        elapsedMillis = elapsed;
        revenue = income;
        costs = cost;
        this.net = net;
        profitPerHour = rate;
        this.suppliesCosts = suppliesCosts;
        this.otherCosts = otherCosts;
        this.costSplitAvailable = costSplitAvailable && safeAdd(suppliesCosts, otherCosts) == cost;
    }
}

/**
 * Closed-session history management and receipt retention: UTC-day compaction sweeps, retention
 * status, profile size/data-health facts and the history session reads/edits. The history list
 * itself stays on the engine because owner lifecycle appends to it. Invoked only under the {@link
 * Engine} monitor.
 */
@RequiredArgsConstructor
class SessionArchive {
    /** UTC day of the last receipt-retention sweep; null before the first one this run. */
    LocalDate lastSweepDay;

    final Engine engine;

    void clear() {
        lastSweepDay = null;
    }

    /** Post-restore compaction with the configured window; it counts as today's sweep. */
    void compactAfterRestore(long now) {
        compactOlderThan(retentionDays(), now);
        lastSweepDay = utcDay(now);
    }

    void adoptFrom(SessionArchive draft) {
        lastSweepDay = draft.lastSweepDay;
    }

    /** The configured receipt retention in days; 0 keeps receipts forever. */
    int retentionDays() {
        var period = engine.config.receiptRetentionDays();
        return period == null ? Retention.DAYS_90.days : period.days;
    }

    static LocalDate utcDay(long now) {
        return Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
    }

    void trimHistory() {
        int maxHistory = max(1, engine.config.maxHistorySessions());
        if (engine.history.size() > maxHistory) {
            engine.history.subList(maxHistory, engine.history.size()).clear();
            engine.bumpRevision();
        }
    }

    /**
     * Compacts closed sessions strictly older than {@code days}; a zero window means forever and
     * performs no compaction. The session metadata and all retained accounting aggregates remain
     * available.
     *
     * @return receipt rows compacted
     */
    int compactOlderThan(int days, long now) {
        if (days <= 0) return 0;
        long cutoff;
        try {
            cutoff = Instant.ofEpochMilli(now).minus(days, ChronoUnit.DAYS).toEpochMilli();
        } catch (RuntimeException ex) {
            cutoff = Long.MIN_VALUE;
        }
        // Rows still needed individually: an unresolved claim's audit row and the settlement that
        // carries a claim id keep their detail until the claim resolves (section L).
        var claimIds = engine.pendingClaimIds();
        Predicate<Transaction> keep = transaction -> isPendingClaimRow(transaction, claimIds)
                                || (!transaction.sourceClaimId().isEmpty()
                                        && claimIds.contains(transaction.sourceClaimId()));
        int compacted = 0;
        for (var session : engine.uniqueSessions()) {
            if (session == null || session.getTransactions().isEmpty()) continue;
            compacted = safeAddCount(compacted, session.compactBefore(cutoff, keep));
        }
        if (compacted > 0) engine.bumpRevision();
        return compacted;
    }

    /**
     * A multi-key audit row owns claims named {@code auditId#n}; retain its base row while any
     * derived claim remains unresolved so provenance survives receipt compaction.
     */
    static boolean isPendingClaimRow(Transaction transaction, Set<String> claimIds) {
        if (transaction == null || empty(claimIds)) return false;
        String id = transaction.getId();
        if (claimIds.contains(id)) return true;
        String derivedPrefix = id + "#";
        for (String claimId : claimIds) {
            if (claimId != null && claimId.startsWith(derivedPrefix)) return true;
        }
        return false;
    }

    /**
     * The game tick's daily sweep: the first tick of each later UTC day compacts once. The first
     * tick of a run only marks the day, since a restore already swept.
     */
    int maintainRetention(long now) {
        var today = utcDay(now);
        if (lastSweepDay != null && !today.isAfter(lastSweepDay)) return 0;
        boolean first = lastSweepDay == null;
        lastSweepDay = today;
        return first ? 0 : compactOlderThan(retentionDays(), now);
    }

    Session historySession(String id) {
        if (empty(id)) return null;
        for (var session : engine.history) {
            if (id.equals(session.getId())) return session;
        }
        return null;
    }

    Metrics historyMetrics(String id, long now) {
        var session = historySession(id);
        return session == null ? null : session.metrics(now);
    }

    boolean deleteHistory(String id) {
        var session = historySession(id);
        if (session == null) return false;
        engine.history.remove(session);
        engine.bumpRevision();
        return true;
    }
}
