package com.gpmanager;
import lombok.*;
import java.util.*;
/**
* Owns the schema-102 profile-level PvP retention state and enforces the bounded detailed
* encounter window (receipt-retention age horizon plus the newest profile-wide completion
* positions). Ad projections and correction-eligible anchors live on their sessions; this
* class owns the sequence watermarks. Invoked only under the {@link Am} monitor.
*/
@RequiredArgsConstructor
class PkHistoryArchive {
/** Dedicated internal detailed PvP encounter bound; deliberately not a user-facing setting. */
static final int MAX_DETAILED_ENCOUNTERS = 2_000;
final Am engine;
Cf state = new Cf();
void restore(SavedState saved) {
 state = saved == null ? new Cf() : saved.getPkHistory();
}

void ayc(SavedState saved) {
 saved.pkHistory = state == null ? new Cf() : state;
}

void clear() {
 state = new Cf();
}

void auc(PkHistoryArchive staged) {
 state = staged.state;
}

/** Assigns the profile-wide completion position once, then enforces the bounded detail cap. */
void aeu(Bx encounter) {
 if (encounter == null) return;
 encounter.completionSequence = Ae.nonNeg(state.km());
 ri(state, engine.akf(), 0, 0L);
}

/** Enforces the profile-wide detail bound: age horizon (when configured) plus the newest cap. */
void ri(List<Ad> sessions, int days, long now) {
 ri(state, sessions, days, now);
}

static void ri(Cf state, List<Ad> sessions, int days, long now) {
 if (state == null) return;
 List<Ce> rows = retainedRows(sessions);
 if (rows.isEmpty()) return;
 lg(state, rows);
 long cutoff = days <= 0 ? Long.MIN_VALUE : now - Math.max(0, days) * 86_400_000L;
 long floor = state.detailFloorSequence;
 rows.sort(Comparator.comparingLong(Ce::sequence).thenComparing(row -> row.encounter.getId()));
 int retained = rows.size();
 for (Ce row : rows) {
  boolean arn = row.sequence() <= floor;
  boolean avv = days > 0 && row.encounter.timestampEpochMillis < cutoff;
  boolean asz = retained > MAX_DETAILED_ENCOUNTERS;
  if (!arn && !avv && !asz) continue;
  if (row.session.afw(row.encounter)) {
   retained--;
   state.adv(row.sequence());
  }
 }
}

/** Legacy or hand-built rows without a completion position receive one, once, deterministically. */
static void lg(Cf state, List<Ce> rows) {
 var missing = new ArrayList<Ce>();
 for (Ce row : rows) {
  if (row.encounter.completionSequence < 0L) missing.add(row);
 }
 if (missing.isEmpty()) return;
 missing.sort(Comparator.comparingLong((Ce row) -> row.encounter.timestampEpochMillis)
 .thenComparing(row -> row.session.getId()).thenComparing(row -> row.encounter.getId()));
 for (Ce row : missing) {
  row.encounter.completionSequence = Ae.nonNeg(state.km());
 }
}

static List<Ce> retainedRows(List<Ad> sessions) {
 var rows = new ArrayList<Ce>();
 if (sessions == null) return rows;
 for (Ad session : sessions) {
  if (session == null) continue;
  for (Bx encounter : session.getPkEncounters()) {
   if (encounter != null) rows.add(new Ce(session, encounter));
  }
 }
 return rows;
}

@AllArgsConstructor
static class Ce {
 final Ad session;
 final Bx encounter;
 long sequence() {
  return encounter.completionSequence;
 }
}
}
