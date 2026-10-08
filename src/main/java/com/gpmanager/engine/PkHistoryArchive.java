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
void restore(SavedState /* saved */a) {
 state = a == null ? new Cf() : a.getPkHistory();
}

void ayc(SavedState /* saved */a) {
 a.pkHistory = state == null ? new Cf() : state;
}

void clear() {
 state = new Cf();
}

void auc(PkHistoryArchive /* staged */a) {
 state = a.state;
}

/** Assigns the profile-wide completion position once, then enforces the bounded detail cap. */
void aeu(Bx /* encounter */a) {
 if (a == null) return;
 a.completionSequence = Ae.nonNeg(state.km());
 ri(state, engine.akf(), 0, 0L);
}

/** Enforces the profile-wide detail bound: age horizon (when configured) plus the newest cap. */
void ri(List<Ad> /* sessions */a, int /* days */b, long /* now */c) {
 ri(state, a, b, c);
}

static void ri(Cf /* state */a, List<Ad> /* sessions */b, int /* days */c, long /* now */d) {
 if (a == null) return;
 List<Ce> /* rows */e = retainedRows(b);
 if (e.isEmpty()) return;
 lg(a, e);
 long /* cutoff */f = c <= 0 ? Long.MIN_VALUE : d - Math.max(0, c) * 86_400_000L;
 long /* floor */g = a.detailFloorSequence;
 e.sort(Comparator.comparingLong(Ce::sequence).thenComparing(/* row */h -> h.encounter.getId()));
 int /* retained */i = e.size();
 for (Ce /* row */j : e) {
  boolean /* arn */k = j.sequence() <= g;
  boolean /* avv */l = c > 0 && j.encounter.timestampEpochMillis < f;
  boolean /* asz */m = i > MAX_DETAILED_ENCOUNTERS;
  if (!k && !l && !m) continue;
  if (j.session.afw(j.encounter)) {
   i--;
   a.adv(j.sequence());
  }
 }
}

/** Legacy or hand-built rows without a completion position receive one, once, deterministically. */
static void lg(Cf /* state */a, List<Ce> /* rows */b) {
 var /* missing */c = new ArrayList<Ce>();
 for (Ce /* row */d : b) {
  if (d.encounter.completionSequence < 0L) c.add(d);
 }
 if (c.isEmpty()) return;
 c.sort(Comparator.comparingLong((Ce /* row */e) -> e.encounter.timestampEpochMillis)
 .thenComparing(/* row */f -> f.session.getId()).thenComparing(/* row */g -> g.encounter.getId()));
 for (Ce /* row */h : c) {
  h.encounter.completionSequence = Ae.nonNeg(a.km());
 }
}

static List<Ce> retainedRows(List<Ad> /* sessions */a) {
 var /* rows */b = new ArrayList<Ce>();
 if (a == null) return b;
 for (Ad /* session */c : a) {
  if (c == null) continue;
  for (Bx /* encounter */d : c.getPkEncounters()) {
   if (d != null) b.add(new Ce(c, d));
  }
 }
 return b;
}

@AllArgsConstructor
static class Ce {
 Ad session;
 Bx encounter;
 long sequence() {
  return encounter.completionSequence;
 }
}
}
