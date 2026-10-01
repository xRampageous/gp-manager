package com.gpmanager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import lombok.*;
import java.util.*;
import java.util.function.Predicate;
import static com.gpmanager.ModelText.*;
/**
* Closed-session history management and receipt retention: UTC-day compaction sweeps, retention
* status, profile size/data-health facts and the history session reads/edits. The history list
* itself stays on the engine because owner lifecycle appends to it. Invoked only under the
* {@link Engine} monitor.
*/
@RequiredArgsConstructor
class SessionArchive {
/** Last scheduled receipt-retention sweep date, stored as an ISO UTC date. */
String lastReceiptRetentionDayUtc = "";
final Engine engine;
void clear() {
 lastReceiptRetentionDayUtc = "";
}

/** Restores the retention markers. */
void restore(SavedState state, long now) {
 lastReceiptRetentionDayUtc = state.getLastReceiptRetentionDayUtc();
}

/** Post-restore compaction with the configured window. */
void compactAfterRestore(long now) {
 ReceiptRetentionPeriod period = engine.config.receiptRetentionDays();
 int days = period == null ? ReceiptRetentionPeriod.DAYS_90.getDays() : period.getDays();
 if (days > 0) compactOlderThan(days, now);
 lastReceiptRetentionDayUtc = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toString();
}

void adoptFrom(SessionArchive staged) {
 lastReceiptRetentionDayUtc = staged.lastReceiptRetentionDayUtc;
}

void writeTo(SavedState state) {
 state.setLastReceiptRetentionDayUtc(lastReceiptRetentionDayUtc);
}

void trimHistory() {
 int maxHistory = Math.max(1, engine.config.maxHistorySessions());
 if (engine.history.size() > maxHistory) {
  engine.history.subList(maxHistory, engine.history.size()).clear();
  engine.bumpRevision();
 }
}

/**
* Compacts closed sessions strictly older than {@code days}; a zero window
* means forever and performs no compaction. The session metadata and all
* retained accounting aggregates remain available.
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
 Set<String> pendingClaimIds = engine.getPendingClaimIds();
 Predicate<Transaction> keep = transaction -> isPendingClaimRow(transaction, pendingClaimIds)
 || (!transaction.getSourceClaimId().isEmpty() && pendingClaimIds.contains(transaction.getSourceClaimId()));
 int compactedReceipts = 0;
 for (Session session : engine.uniqueProfileSessions()) {
  if (session == null || session.getTransactions().isEmpty()) continue;
  compactedReceipts = SafeMath.safeAddCount(compactedReceipts, session.compactTransactionsBefore(cutoff, keep));
 }
 if (compactedReceipts > 0) engine.bumpRevision();
 engine.pkHistory.enforceDetailRetention(engine.uniqueProfileSessions(), days, now);
 return compactedReceipts;
}

/** A multi-key audit row owns claims named {@code auditId#n}; retain its base row while any
* derived claim remains unresolved so provenance survives receipt compaction. */
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

/** Daily UTC maintenance hook. Supplying time keeps lifecycle tests deterministic. */
int maintainReceiptRetention(int days, long now) {
 LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
 LocalDate previous = parseRetentionDay(lastReceiptRetentionDayUtc);
 if (previous == null) {
  lastReceiptRetentionDayUtc = today.toString();
  return 0;
 }
 if (!today.isAfter(previous)) return 0;
 int compacted = days <= 0 ? 0 : compactOlderThan(days, now);
 lastReceiptRetentionDayUtc = today.toString();
 return compacted;
}

/** Configured UTC retention maintenance used by the game-tick lifecycle. */
int maintainReceiptRetention(long now) {
 ReceiptRetentionPeriod period = engine.config.receiptRetentionDays();
 return maintainReceiptRetention(period == null ? ReceiptRetentionPeriod.DAYS_90.getDays() : period.getDays(), now);
}

static LocalDate parseRetentionDay(String value) {
 if (blank(value)) return null;
 try {
  return LocalDate.parse(value.trim());
 } catch (RuntimeException ex) {
  return null;
 }
}

Session getHistorySession(String sessionId) {
 if (empty(sessionId)) return null;
 for (Session session : engine.history) {
  if (sessionId.equals(session.getId())) return session;
 }
 return null;
}

SessionMetrics getHistoryMetrics(String sessionId, long now) {
 Session session = getHistorySession(sessionId);
 return session == null ? null : session.metrics(now);
}

boolean deleteHistorySession(String sessionId) {
 if (empty(sessionId)) return false;
 for (int index = 0; index < engine.history.size(); index++) {
  if (sessionId.equals(engine.history.get(index).getId())) {
   engine.history.remove(index);
   engine.bumpRevision();
   return true;
  }
 }
 return false;
}
}
