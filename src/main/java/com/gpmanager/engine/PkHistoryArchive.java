package com.gpmanager;
import lombok.*;
import java.util.*;
/**
* Owns the schema-102 profile-level PvP retention state and enforces the bounded detailed
* encounter window (receipt-retention age horizon plus the newest profile-wide completion
* positions). Session projections and correction-eligible anchors live on their sessions; this
* class owns the sequence watermarks. Invoked only under the {@link Engine} monitor.
*/
@RequiredArgsConstructor
class PkHistoryArchive {
/** Dedicated internal detailed PvP encounter bound; deliberately not a user-facing setting. */
static final int MAX_DETAILED_ENCOUNTERS = 2_000;
final Engine engine;
PkHistoryState state = new PkHistoryState();
void restore(SavedState saved) {
state = saved == null ? new PkHistoryState() : saved.getPkHistory();
}

void writeTo(SavedState saved) {
saved.pkHistory = state == null ? new PkHistoryState() : state;
}

void clear() {
state = new PkHistoryState();
}

void adoptFrom(PkHistoryArchive staged) {
state = staged.state;
}

/** Assigns the profile-wide completion position once, then enforces the bounded detail cap. */
void registerEncounter(PkEncounter encounter) {
if (encounter == null) return;
encounter.completionSequence = SafeMath.nonNeg(state.allocateCompletionSequence());
enforceDetailRetention(state, engine.uniqueProfileSessions(), 0, 0L);
}

/** Enforces the profile-wide detail bound: age horizon (when configured) plus the newest cap. */
void enforceDetailRetention(List<Session> sessions, int days, long now) {
enforceDetailRetention(state, sessions, days, now);
}

static void enforceDetailRetention(PkHistoryState state, List<Session> sessions, int days, long now) {
if (state == null) return;
List<RetainedRow> rows = retainedRows(sessions);
if (rows.isEmpty()) return;
assignMissingSequences(state, rows);
long cutoff = days <= 0 ? Long.MIN_VALUE : now - Math.max(0, days) * 86_400_000L;
long floor = state.detailFloorSequence;
rows.sort(Comparator.comparingLong(RetainedRow::sequence).thenComparing(row -> row.encounter.getId()));
int retained = rows.size();
for (RetainedRow row : rows) {
boolean belowFloor = row.sequence() <= floor;
boolean tooOld = days > 0 && row.encounter.timestampEpochMillis < cutoff;
boolean overCap = retained > MAX_DETAILED_ENCOUNTERS;
if (!belowFloor && !tooOld && !overCap) continue;
if (row.session.removePkEncounter(row.encounter)) {
retained--;
state.raiseDetailFloorSequence(row.sequence());
}
}
}

/** Legacy or hand-built rows without a completion position receive one, once, deterministically. */
static void assignMissingSequences(PkHistoryState state, List<RetainedRow> rows) {
var missing = new ArrayList<RetainedRow>();
for (RetainedRow row : rows) {
if (row.encounter.completionSequence < 0L) missing.add(row);
}
if (missing.isEmpty()) return;
missing.sort(Comparator.comparingLong((RetainedRow row) -> row.encounter.timestampEpochMillis)
.thenComparing(row -> row.session.getId()).thenComparing(row -> row.encounter.getId()));
for (RetainedRow row : missing) {
row.encounter.completionSequence = SafeMath.nonNeg(state.allocateCompletionSequence());
}
}

static List<RetainedRow> retainedRows(List<Session> sessions) {
var rows = new ArrayList<RetainedRow>();
if (sessions == null) return rows;
for (Session session : sessions) {
if (session == null) continue;
for (PkEncounter encounter : session.getPkEncounters()) {
if (encounter != null) rows.add(new RetainedRow(session, encounter));
}
}
return rows;
}

@AllArgsConstructor
static class RetainedRow {
final Session session;
final PkEncounter encounter;
long sequence() {
return encounter.completionSequence;
}
}
}
