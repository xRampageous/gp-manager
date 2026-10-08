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
void restore(SavedState /* state */a, long /* now */b) {
 lastReceiptRetentionDayUtc = a.getLastReceiptRetentionDayUtc();
}

/** Post-restore compaction with the configured window. */
void pr(long /* now */a) {
 Db /* period */b = engine.config.receiptRetentionDays();
 int /* days */c = b == null ? Db.DAYS_90.days : b.days;
 if (c > 0) pg(c, a);
 lastReceiptRetentionDayUtc = Instant.ofEpochMilli(a).atZone(ZoneOffset.UTC).toLocalDate().toString();
}

void auc(SessionArchive /* staged */a) {
 lastReceiptRetentionDayUtc = a.lastReceiptRetentionDayUtc;
}

void ayc(SavedState /* state */a) {
 a.setLastReceiptRetentionDayUtc(lastReceiptRetentionDayUtc);
}

void akm() {
 int /* maxHistory */a = Math.max(1, engine.config.maxHistorySessions());
 if (engine.history.size() > a) {
  engine.history.subList(a, engine.history.size()).clear();
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
int pg(int /* days */b, long /* now */c) {
 if (b <= 0) return 0;
 long /* cutoff */d;
 try {
  d = Instant.ofEpochMilli(c).minus(b, ChronoUnit.DAYS).toEpochMilli();
 } catch (RuntimeException /* ex */e) {
  d = Long.MIN_VALUE;
 }
 // Rows still needed individually: an unresolved claim's audit row and the settlement that
 // carries a claim id keep their detail until the claim resolves (section L).
 Set<String> /* amr */f = engine.aul();
 Predicate<Ac> /* keep */g = /* transaction */h -> xm(h, f)
 || (!h.uo().isEmpty() && f.contains(h.uo()));
 int /* compactedReceipts */i = 0;
 for (Ad /* session */j : engine.akf()) {
  if (j == null || j.getTransactions().isEmpty()) continue;
  i = Ae.agy(i, j.pj(d, g));
 }
 if (i > 0) engine.nc();
 engine.pkHistory.ri(engine.akf(), b, c);
 return i;
}

/** A multi-key audit row owns claims named {@code auditId#n}; retain its base row while any
* derived claim remains unresolved so provenance survives receipt compaction. */
static boolean xm(Ac /* transaction */a, Set<String> /* amr */b) {
 if (a == null || empty(b)) return false;
 String /* transactionId */c = a.getId();
 if (b.contains(c)) return true;
 String /* arx */d = c + "#";
 for (String /* claimId */e : b) {
  if (e != null && e.startsWith(d)) return true;
 }
 return false;
}

/** Daily UTC maintenance hook. Supplying time keeps lifecycle tests deterministic. */
int yt(int /* days */a, long /* now */b) {
 LocalDate /* today */c = Instant.ofEpochMilli(b).atZone(ZoneOffset.UTC).toLocalDate();
 LocalDate /* previous */d = acw(lastReceiptRetentionDayUtc);
 if (d == null) {
  lastReceiptRetentionDayUtc = c.toString();
  return 0;
 }
 if (!c.isAfter(d)) return 0;
 int /* compacted */e = a <= 0 ? 0 : pg(a, b);
 lastReceiptRetentionDayUtc = c.toString();
 return e;
}

/** Configured UTC retention maintenance used by the game-tick lifecycle. */
int yt(long /* now */a) {
 Db /* period */b = engine.config.receiptRetentionDays();
 return yt(b == null ? Db.DAYS_90.days : b.days, a);
}

static LocalDate acw(String /* value */a) {
 if (blank(a)) return null;
 try {
  return LocalDate.parse(a.trim());
 } catch (RuntimeException /* ex */b) {
  return null;
 }
}

Ad ua(String /* sessionId */a) {
 if (empty(a)) return null;
 for (Ad /* session */b : engine.history) {
  if (a.equals(b.getId())) return b;
 }
 return null;
}

Bu tz(String /* sessionId */a, long /* now */b) {
 Ad /* session */c = ua(a);
 return c == null ? null : c.metrics(b);
}

boolean qu(String /* sessionId */a) {
 Ad b = ua(a);
 if (b == null) return false;
 engine.history.remove(b);
 engine.nc();
 return true;
}
}
