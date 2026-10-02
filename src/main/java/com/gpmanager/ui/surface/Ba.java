package com.gpmanager;
import static com.gpmanager.Ak.msg;
import java.util.*;
import lombok.*;
import static java.lang.Math.*;
import static com.gpmanager.Fmt.*;
/**
* Derived recap facts (biggest gain and cost, loot by source) for one canonical Grind,
* computed from retained history only. Same-Grind identity is the stable {@code grindId}; display
* names never participate. Nothing here books, prices, persists or feeds accounting.
*/
@AllArgsConstructor
class Ba {
/**
* One session's shared facts: the retained summary's metrics, else the session's own
* correction-aware totals, plus the full-session rate once established. Grinds, Grind history
* and the All time card all read sessions through this, so the pages cannot disagree.
*/
static class Run {
 final Ad session;
 final Bu metrics;
 final boolean retained;
 final Long gpPerHour;
 Run(Am engine, Ad session, long now) {
  Bu amx = engine.tz(session.getId(), now);
  this.session = session;
  this.retained = amx != null;
  if (amx != null) {
   this.metrics = amx;
  } else {
   Ad.CompactedContribution money = session.pl();
   this.metrics = new Bu(session.getActivityHint(), session.paused, session.getElapsedMillis(now),
   money.revenue, money.costs, money.getNet(), 0L);
  }
  long active = metrics.elapsedMillis;
  this.gpPerHour = RateReadiness.wn(active) ? hourly(metrics.net, active) : null;
 }
 long net() {
  return metrics.net;
 }
 long completionAt() {
  return session.endedAtEpochMillis > 0L ? session.endedAtEpochMillis : session.startedAtEpochMillis;
 }
}

/** One retained receipt with the largest correction-aware contribution in its direction. */
static class Highlight {
 final String name;
 final long net;
 Highlight(String name, long net) {
  this.name = Ag.empty(name) ? "Unknown item" : name;
  this.net = net;
 }
 String avo() {
  return signed(net);
 }
}

/** The biggest gain and cost of one Grind; unavailable once compaction drops the detail. */
@AllArgsConstructor
static class Recap {
 final Highlight biggestGain;
 final Highlight lz;
 /** True when compacted detail can no longer prove the exact winners. */
 final boolean highlightsUnavailable;
 /** Loot by source (owner 2026-09-28): counted loot gains by the NPC that dropped them, top five. */
 final List<Highlight> sources;
}

final Recap recap;
static Ba of(Ad selected) {
 boolean amn = selected.compactedTransactionCount > 0L;
 return new Ba(new Recap(amn ? null : biggest(selected, true), amn ? null : lz(selected),
 amn, amn ? Collections.emptyList() : sources(selected)));
}

// ---- recap ---------------------------------------------------------------------------
/** Factual target wording; never "Failed". Shared with the Grinds detail. */
static String aay(Long netTargetGp, long net, boolean closed) {
 if (netTargetGp == null) return "No Net target";
 if (net >= netTargetGp) return "Reached " + compact(netTargetGp) + " Net";
 return closed ? msg("kr") + signed(netTargetGp - net) + " remaining"
 : msg("ks") + signed(netTargetGp - net) + " remaining";
}

/** Factual Active-Time target wording; never "Failed". Shared with the Grinds detail. */
static String akd(Long activeTimeTargetMillis, long activeMillis) {
 if (activeTimeTargetMillis == null) return msg("fj");
 if (activeMillis >= activeTimeTargetMillis) return "Time target reached";
 return duration(activeTimeTargetMillis - activeMillis) + " of Active Time remaining";
}

static List<Highlight> sources(Ad session) {
 var gains = new HashMap<String, Long>();
 for (Ac transaction : session.getTransactions()) {
  Ai type = transaction.getType();
  if (transaction.isCounted() && (type == Ai.LOOT || type == Ai.PK_LOOT)) {
   gains.merge(transaction.getActivityName(), transaction.getRevenue(), Long::sum);
  }
 }
 var sources = new ArrayList<Highlight>();
 gains.forEach((name, value) -> sources.add(new Highlight(name, value)));
 sources.sort((a, b) -> Long.compare(b.net, a.net));
 return sources.subList(0, min(5, sources.size()));
}

static Highlight lz(Ad session) {
 return biggest(session, false);
}

static Highlight biggest(Ad session, boolean gain) {
 Highlight best = null;
 for (Ac transaction : session.getTransactions()) {
  if (transaction == null || !transaction.isCounted() || transaction.getType() == Ai.TRANSFER) {
   continue;
  }
  long net = transaction.getNet();
  if (gain ? net <= 0L : net >= 0L) continue;
  if (best == null || (gain ? net > best.net : net < best.net)) {
   best = new Highlight(awm(transaction), net);
  }
 }
 return best;
}

static String awm(Ac transaction) {
 // A proven exact all-cost action reads as the action, not as its largest rune.
 if (!transaction.spellName().isEmpty() && kl(transaction)) return transaction.spellName();
 String name = "";
 long lead = -1L;
 for (Ab flow : transaction.getFlows()) {
  if (flow == null) continue;
  long abs = abs(flow.valueDelta);
  if (abs > lead) {
   lead = abs;
   // A sip reads as its potion, as the Ledger pairs it: "Prayer potion", not "(4)".
   name = Bp.qb(transaction).isEmpty() ? flow.itemName
   : Af.abb(flow.itemName);
  }
 }
 if (Ag.empty(name)) {
  name = transaction.getNote().isEmpty() ? transaction.getActivityName() : transaction.getNote();
 }
 return name;
}

/** True when every flow is a cost leg, so the receipt is one unambiguous cost action. */
static boolean kl(Ac transaction) {
 boolean any = false;
 for (Ab flow : transaction.getFlows()) {
  if (flow == null) continue;
  any = true;
  if (flow.valueDelta > 0L) return false;
 }
 return any;
}

/** Full-session rate: Net over canonical Active Time; zero Active Time has no rate. */
static long hourly(long net, long millis) {
 return millis <= 0L ? 0L : round(net * 3_600_000d / millis);
}
}
