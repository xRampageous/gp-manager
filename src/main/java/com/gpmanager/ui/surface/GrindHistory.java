package com.gpmanager;
import static com.gpmanager.GameData.msg;
import java.util.*;
import lombok.*;
import static java.lang.Math.*;
import static com.gpmanager.Fmt.*;
/**
* Derived recap / previous-Grind comparison / personal-best facts for one canonical Grind,
* computed from retained history only. Same-Grind identity is the stable {@code grindId}; display
* names never participate. Nothing here books, prices, persists or feeds accounting.
*/
@AllArgsConstructor
class GrindHistory {
/**
* One session's shared facts: the retained summary's metrics, else the session's own
* correction-aware totals, plus the full-session rate once established. Grinds, Grind history
* and the All time card all read sessions through this, so the pages cannot disagree.
*/
static class Run {
 final Session session;
 final SessionMetrics metrics;
 final boolean retained;
 final Long gpPerHour;
 Run(Engine engine, Session session, long now) {
  SessionMetrics retainedMetrics = engine.getHistoryMetrics(session.getId(), now);
  this.session = session;
  this.retained = retainedMetrics != null;
  if (retainedMetrics != null) {
   this.metrics = retainedMetrics;
  } else {
   Session.CompactedContribution money = session.compactedContribution();
   this.metrics = new SessionMetrics(session.getActivityHint(), session.paused, session.getElapsedMillis(now),
   money.revenue, money.costs, money.getNet(), 0L);
  }
  long active = metrics.elapsedMillis;
  this.gpPerHour = RateReadiness.isFullRateEstablished(active) ? hourly(metrics.net, active) : null;
 }
 long net() {
  return metrics.net;
 }
 long completionAt() {
  return session.endedAtEpochMillis > 0L ? session.endedAtEpochMillis : session.startedAtEpochMillis;
 }
}

/**
* One Grind's completed runs for HUD+: best Net, best GP/h and average GP/h, with
* {@code Long.MIN_VALUE} for a rate no run established. Null with no runs.
*/
static long[] bests(Engine engine, String grindId, long now) {
 List<Run> runs = lineage(engine, grindId, now);
 if (runs.isEmpty()) return null;
 long net = Long.MIN_VALUE;
 long rate = Long.MIN_VALUE;
 long rateSum = 0L;
 int rated = 0;
 for (Run run : runs) {
  net = Math.max(net, run.net());
  if (run.gpPerHour != null) {
   rate = Math.max(rate, run.gpPerHour);
   rateSum += run.gpPerHour;
   rated++;
  }
 }
 return new long[] {net, rate, rated == 0 ? Long.MIN_VALUE : rateSum / rated};
}

/** Completed, counted, retained runs of one Grind; identity is the stable grindId, never the name. */
static List<Run> lineage(Engine engine, String grindId, long now) {
 var runs = new ArrayList<Run>();
 if (grindId != null && !grindId.isEmpty()) {
  for (Session session : engine.completedLinkedSessions(grindId)) {
   Run run = session == null || session.excludedFromAverages ? null : new Run(engine, session, now);
   if (run != null && run.retained) runs.add(run);
  }
 }
 return runs;
}

/** One retained receipt with the largest correction-aware contribution in its direction. */
static class Highlight {
 final String name;
 final long net;
 Highlight(String name, long net) {
  this.name = ModelText.empty(name) ? "Unknown item" : name;
  this.net = net;
 }
 String valueText() {
  return signed(net);
 }
}

/** The biggest gain and cost of one Grind; unavailable once compaction drops the detail. */
@AllArgsConstructor
static class Recap {
 final Highlight biggestGain;
 final Highlight biggestCost;
 /** True when compacted detail can no longer prove the exact winners. */
 final boolean highlightsUnavailable;
 /** Loot by source (owner 2026-09-28): counted loot gains by the NPC that dropped them, top five. */
 final List<Highlight> sources;
}

/** Factual current-minus-previous arithmetic; no causal explanation, no analysis. */
@AllArgsConstructor
static class Comparison {
 final boolean excluded;
 final boolean available;
 final long netDelta;
 final Long rateDelta;
 final Long suppliesDelta;
 final long activeDelta;
}

/** Strict personal-best facts over the same-Grind lineage; derivation only, never persisted. */
@AllArgsConstructor
static class PBs {
 final boolean excluded;
 final Long bestNet;
 final boolean newBestNet;
 final boolean matchesBestNet;
 final Long bestGpPerHour;
 final boolean newBestGpPerHour;
 final boolean matchesBestGpPerHour;
}

final Recap recap;
final Comparison comparison;
final PBs pbs;
static GrindHistory of(Engine engine, Session selected, long now) {
 var run = new Run(engine, selected, now);
 Long rate = selected.isClosed() ? run.gpPerHour : null;
 boolean noHighlights = selected.compactedTransactionCount > 0L;
 var recap = new Recap(noHighlights ? null : biggest(selected, true), noHighlights ? null : biggestCost(selected),
 noHighlights, noHighlights ? Collections.emptyList() : sources(selected));
 if (selected.excludedFromAverages) {
  return new GrindHistory(recap, new Comparison(true, false, 0L, null, null, 0L),
  new PBs(true, null, false, false, null, false, false));
 }
 List<Run> lineage = lineage(engine, selected.getGrindId(), now);
 return new GrindHistory(recap, comparison(lineage, run, rate), pbs(lineage, run, rate));
}

// ---- recap ---------------------------------------------------------------------------
/** Factual target wording; never "Failed". Shared with the Grinds detail. */
static String netOutcome(Long netTargetGp, long net, boolean closed) {
 if (netTargetGp == null) return "No Net target";
 if (net >= netTargetGp) return "Reached " + compact(netTargetGp) + " Net";
 return closed ? "Ended before target \u00b7 " + signed(netTargetGp - net) + " remaining"
 : "Not reached yet \u00b7 " + signed(netTargetGp - net) + " remaining";
}

/** Factual Active-Time target wording; never "Failed". Shared with the Grinds detail. */
static String timeOutcome(Long activeTimeTargetMillis, long activeMillis) {
 if (activeTimeTargetMillis == null) return msg("fj");
 if (activeMillis >= activeTimeTargetMillis) return "Time target reached";
 return duration(activeTimeTargetMillis - activeMillis) + " of Active Time remaining";
}

static List<Highlight> sources(Session session) {
 var gains = new HashMap<String, Long>();
 for (Transaction transaction : session.getTransactions()) {
  TransactionType type = transaction.getType();
  if (transaction.isCounted() && (type == TransactionType.LOOT || type == TransactionType.PK_LOOT)) {
   gains.merge(transaction.getActivityName(), transaction.getRevenue(), Long::sum);
  }
 }
 var sources = new ArrayList<Highlight>();
 gains.forEach((name, value) -> sources.add(new Highlight(name, value)));
 sources.sort((a, b) -> Long.compare(b.net, a.net));
 return sources.subList(0, min(5, sources.size()));
}

static Highlight biggestCost(Session session) {
 return biggest(session, false);
}

static Highlight biggest(Session session, boolean gain) {
 Highlight best = null;
 for (Transaction transaction : session.getTransactions()) {
  if (transaction == null || !transaction.isCounted() || transaction.getType() == TransactionType.TRANSFER) {
   continue;
  }
  long net = transaction.getNet();
  if (gain ? net <= 0L : net >= 0L) continue;
  if (best == null || (gain ? net > best.net : net < best.net)) {
   best = new Highlight(leadName(transaction), net);
  }
 }
 return best;
}

static String leadName(Transaction transaction) {
 // A proven exact all-cost action reads as the action, not as its largest rune.
 if (!transaction.spellName().isEmpty() && allFlowsAreCosts(transaction)) return transaction.spellName();
 String name = "";
 long lead = -1L;
 for (Flow flow : transaction.getFlows()) {
  if (flow == null) continue;
  long abs = abs(flow.valueDelta);
  if (abs > lead) {
   lead = abs;
   // A sip reads as its potion, as the Ledger pairs it: "Prayer potion", not "(4)".
   name = AccountingProjection.consumePairs(transaction).isEmpty() ? flow.itemName
   : Contribution.normalizedName(flow.itemName);
  }
 }
 if (ModelText.empty(name)) {
  name = transaction.getNote().isEmpty() ? transaction.getActivityName() : transaction.getNote();
 }
 return name;
}

/** True when every flow is a cost leg, so the receipt is one unambiguous cost action. */
static boolean allFlowsAreCosts(Transaction transaction) {
 boolean any = false;
 for (Flow flow : transaction.getFlows()) {
  if (flow == null) continue;
  any = true;
  if (flow.valueDelta > 0L) return false;
 }
 return any;
}

// ---- previous comparison ---------------------------------------------------------------
static Comparison comparison(List<Run> lineage, Run selected, Long rate) {
 long selectedAt = selected.completionAt();
 Run previous = null;
 for (Run candidate : lineage) {
  long at = candidate.completionAt();
  if (!candidate.session.getId().equals(selected.session.getId())
  && at < selectedAt && (previous == null || at > previous.completionAt())) {
   previous = candidate;
  }
 }
 if (previous == null) return new Comparison(false, false, 0L, null, null, 0L);
 SessionMetrics metrics = selected.metrics;
 SessionMetrics before = previous.metrics;
 return new Comparison(false, true, metrics.net - before.net,
 rate != null && previous.gpPerHour != null ? rate - previous.gpPerHour : null,
 metrics.costSplitAvailable && before.costSplitAvailable ? metrics.suppliesCosts - before.suppliesCosts : null,
 metrics.elapsedMillis - before.elapsedMillis);
}

// ---- same-Grind PBs --------------------------------------------------------------------
static PBs pbs(List<Run> lineage, Run selected, Long rate) {
 long net = selected.net();
 Long otherNet = null;
 Long otherRate = null;
 for (Run other : lineage) {
  if (!other.session.getId().equals(selected.session.getId())) {
   otherNet = max(otherNet, other.net());
   otherRate = max(otherRate, other.gpPerHour);
  }
 }
 boolean rates = rate != null && otherRate != null;
 return new PBs(false, max(net, otherNet), otherNet != null && net > otherNet, otherNet != null && net == otherNet,
 max(rate, otherRate), rates && rate > otherRate, rates && rate.longValue() == otherRate);
}

static Long max(Long a, Long b) {
 return a == null ? b : b == null ? a : Long.valueOf(Math.max(a, b));
}

/** Full-session rate: Net over canonical Active Time; zero Active Time has no rate. */
static long hourly(long net, long millis) {
 return millis <= 0L ? 0L : round(net * 3_600_000d / millis);
}
}
