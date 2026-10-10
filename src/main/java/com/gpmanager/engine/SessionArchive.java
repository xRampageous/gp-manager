package com.gpmanager;

import static com.gpmanager.Phrases.*;

import lombok.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;

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

    void adoptFrom(SessionArchive staged) {
        lastSweepDay = staged.lastSweepDay;
    }

    /** The configured receipt retention in days; 0 keeps receipts forever. */
    int retentionDays() {
        Retention period = engine.config.receiptRetentionDays();
        return period == null ? Retention.DAYS_90.days : period.days;
    }

    static LocalDate utcDay(long now) {
        return Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
    }

    void trimHistory() {
        int maxHistory = Math.max(1, engine.config.maxHistorySessions());
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
        Set<String> pendingClaimIds = engine.pendingClaimIds();
        Predicate<Transaction> keep =
                transaction ->
                        isPendingClaimRow(transaction, pendingClaimIds)
                                || (!transaction.sourceClaimId().isEmpty()
                                        && pendingClaimIds.contains(transaction.sourceClaimId()));
        int compactedReceipts = 0;
        for (Session session : engine.uniqueSessions()) {
            if (session == null || session.getTransactions().isEmpty()) continue;
            compactedReceipts =
                    SafeMath.safeAddCount(compactedReceipts, session.compactBefore(cutoff, keep));
        }
        if (compactedReceipts > 0) engine.bumpRevision();
        return compactedReceipts;
    }

    /**
     * A multi-key audit row owns claims named {@code auditId#n}; retain its base row while any
     * derived claim remains unresolved so provenance survives receipt compaction.
     */
    static boolean isPendingClaimRow(Transaction transaction, Set<String> pendingClaimIds) {
        if (transaction == null || empty(pendingClaimIds)) return false;
        String transactionId = transaction.getId();
        if (pendingClaimIds.contains(transactionId)) return true;
        String derivedPrefix = transactionId + "#";
        for (String claimId : pendingClaimIds) {
            if (claimId != null && claimId.startsWith(derivedPrefix)) return true;
        }
        return false;
    }

    /**
     * The game tick's daily sweep: the first tick of each later UTC day compacts once. The first
     * tick of a run only marks the day, since a restore already swept.
     */
    int maintainRetention(long now) {
        LocalDate today = utcDay(now);
        if (lastSweepDay != null && !today.isAfter(lastSweepDay)) return 0;
        boolean first = lastSweepDay == null;
        lastSweepDay = today;
        return first ? 0 : compactOlderThan(retentionDays(), now);
    }

    Session historySession(String sessionId) {
        if (empty(sessionId)) return null;
        for (Session session : engine.history) {
            if (sessionId.equals(session.getId())) return session;
        }
        return null;
    }

    Metrics historyMetrics(String sessionId, long now) {
        Session session = historySession(sessionId);
        return session == null ? null : session.metrics(now);
    }

    boolean deleteHistory(String sessionId) {
        Session session = historySession(sessionId);
        if (session == null) return false;
        engine.history.remove(session);
        engine.bumpRevision();
        return true;
    }
}
