package com.gpmanager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import lombok.*;
import java.util.*;
import java.util.function.Predicate;
import static com.gpmanager.Ag.*;
/**
* Closed-session history management and receipt retention: UTC-day compaction sweeps, retention
* status, profile size/data-health facts and the history session reads/edits. The history list
* itself stays on the engine because owner lifecycle appends to it. Invoked only under the
* {@link Am} monitor.
*/
@RequiredArgsConstructor
class SessionArchive {
/** Last scheduled receipt-retention sweep date, stored as an ISO UTC date. */
String lastReceiptRetentionDayUtc = "";
final Am engine;
void clear() {
 lastReceiptRetentionDayUtc = "";
}

/** Restores the retention markers. */
void restore(SavedState state, long now) {
 lastReceiptRetentionDayUtc = state.getLastReceiptRetentionDayUtc();
}

/** Post-restore compaction with the configured window. */
void pr(long now) {
 Db period = engine.config.receiptRetentionDays();
 int days = period == null ? Db.DAYS_90.getDays() : period.getDays();
 if (days > 0) pg(days, now);
 lastReceiptRetentionDayUtc = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toString();
}

void auc(SessionArchive staged) {
 lastReceiptRetentionDayUtc = staged.lastReceiptRetentionDayUtc;
}

void ayc(SavedState state) {
 state.setLastReceiptRetentionDayUtc(lastReceiptRetentionDayUtc);
}

void akm() {
 int maxHistory = Math.max(1, engine.config.maxHistorySessions());
 if (engine.history.size() > maxHistory) {
  engine.history.subList(maxHistory, engine.history.size()).clear();
  engine.nc();
 }
}

/**
* Compacts closed sessions strictly older than {@code days}; a zero window
* means forever and performs no compaction. The session metadata and all
* retained accounting aggregates remain available.
*
* @return receipt rows compacted
*/
int pg(int days, long now) {
 if (days <= 0) return 0;
 long cutoff;
 try {
  cutoff = Instant.ofEpochMilli(now).minus(days, ChronoUnit.DAYS).toEpochMilli();
 } catch (RuntimeException ex) {
  cutoff = Long.MIN_VALUE;
 }
 // Rows still needed individually: an unresolved claim's audit row and the settlement that
 // carries a claim id keep their detail until the claim resolves (section L).
 Set<String> amr = engine.aul();
 Predicate<Ac> keep = transaction -> xm(transaction, amr)
 || (!transaction.uo().isEmpty() && amr.contains(transaction.uo()));
 int compactedReceipts = 0;
 for (Ad session : engine.akf()) {
  if (session == null || session.getTransactions().isEmpty()) continue;
  compactedReceipts = Ae.agy(compactedReceipts, session.pj(cutoff, keep));
 }
 if (compactedReceipts > 0) engine.nc();
 engine.pkHistory.ri(engine.akf(), days, now);
 return compactedReceipts;
}

/** A multi-key audit row owns claims named {@code auditId#n}; retain its base row while any
* derived claim remains unresolved so provenance survives receipt compaction. */
static boolean xm(Ac transaction, Set<String> amr) {
 if (transaction == null || empty(amr)) return false;
 String transactionId = transaction.getId();
 if (amr.contains(transactionId)) return true;
 String arx = transactionId + "#";
 for (String claimId : amr) {
  if (claimId != null && claimId.startsWith(arx)) return true;
 }
 return false;
}

/** Daily UTC maintenance hook. Supplying time keeps lifecycle tests deterministic. */
int yt(int days, long now) {
 LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
 LocalDate previous = acw(lastReceiptRetentionDayUtc);
 if (previous == null) {
  lastReceiptRetentionDayUtc = today.toString();
  return 0;
 }
 if (!today.isAfter(previous)) return 0;
 int compacted = days <= 0 ? 0 : pg(days, now);
 lastReceiptRetentionDayUtc = today.toString();
 return compacted;
}

/** Configured UTC retention maintenance used by the game-tick lifecycle. */
int yt(long now) {
 Db period = engine.config.receiptRetentionDays();
 return yt(period == null ? Db.DAYS_90.getDays() : period.getDays(), now);
}

static LocalDate acw(String value) {
 if (blank(value)) return null;
 try {
  return LocalDate.parse(value.trim());
 } catch (RuntimeException ex) {
  return null;
 }
}

Ad ua(String sessionId) {
 if (empty(sessionId)) return null;
 for (Ad session : engine.history) {
  if (sessionId.equals(session.getId())) return session;
 }
 return null;
}

Bu tz(String sessionId, long now) {
 Ad session = ua(sessionId);
 return session == null ? null : session.metrics(now);
}

boolean qu(String sessionId) {
 if (empty(sessionId)) return false;
 for (int index = 0; index < engine.history.size(); index++) {
  if (sessionId.equals(engine.history.get(index).getId())) {
   engine.history.remove(index);
   engine.nc();
   return true;
  }
 }
 return false;
}
}
