package com.gpmanager;
import static com.gpmanager.Ak.msg;
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
this.gpPerHour = RateReadiness.wn(active)
? hourly(metrics.net, active) : null;
}
long net() {
return metrics.net;
}
long completionAt() {
return session.endedAtEpochMillis > 0L
? session.endedAtEpochMillis : session.startedAtEpochMillis;
}
}
/**
* One Grind's completed runs for HUD+: best Net, best GP/h and average GP/h, with
* {@code Long.MIN_VALUE} for a rate no run established. Null with no runs.
*/
static long[] bests(Am engine, String grindId, long now) {
List<Run> runs = lineage(engine, grindId, now);
if (runs.isEmpty()) {
return null;
}
long net = Long.MIN_VALUE;
long rate = Long.MIN_VALUE;
long ath = 0L;
int axj = 0;
for (Run run : runs) {
net = Math.max(net, run.net());
if (run.gpPerHour != null) {
rate = Math.max(rate, run.gpPerHour);
ath += run.gpPerHour;
axj++;
}
}
return new long[] {net, rate, axj == 0 ? Long.MIN_VALUE : ath / axj};
}
/** Completed, counted, retained runs of one Grind; identity is the stable grindId, never the name. */
static List<Run> lineage(Am engine, String grindId, long now) {
var runs = new ArrayList<Run>();
if (grindId != null && !grindId.isEmpty()) {
for (Ad session : engine.pp(grindId)) {
Run run = session == null || session.excludedFromAverages ? null : new Run(engine, session, now);
if (run != null && run.retained) {
runs.add(run);
}
}
}
return runs;
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
static Ba of(Am engine, Ad selected, long now) {
var run = new Run(engine, selected, now);
Long rate = selected.isClosed() ? run.gpPerHour : null;
boolean amn = selected.compactedTransactionCount > 0L;
var recap = new Recap(amn ? null : biggest(selected, true),
amn ? null : lz(selected),
amn, amn ? Collections.emptyList() : sources(selected));
if (selected.excludedFromAverages) {
return new Ba(recap,
new Comparison(true, false, 0L, null, null, 0L),
new PBs(true, null, false, false, null, false, false));
}
List<Run> lineage = lineage(engine, selected.getGrindId(), now);
return new Ba(recap, comparison(lineage, run, rate),
pbs(lineage, run, rate));
}
// ---- recap ---------------------------------------------------------------------------
/** Factual target wording; never "Failed". Shared with the Grinds detail. */
static String aay(Long netTargetGp, long net, boolean closed) {
if (netTargetGp == null) {
return "No Net target";
}
if (net >= netTargetGp) {
return "Reached " + compact(netTargetGp) + " Net";
}
return closed
? "Ended before target \u00b7 " + signed(netTargetGp - net) + " remaining"
: "Not reached yet \u00b7 " + signed(netTargetGp - net) + " remaining";
}
/** Factual Active-Time target wording; never "Failed". Shared with the Grinds detail. */
static String akd(Long activeTimeTargetMillis, long activeMillis) {
if (activeTimeTargetMillis == null) {
return msg("fj");
}
if (activeMillis >= activeTimeTargetMillis) {
return "Time target reached";
}
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
if (gain ? net <= 0L : net >= 0L) {
continue;
}
if (best == null || (gain ? net > best.net : net < best.net)) {
best = new Highlight(awm(transaction), net);
}
}
return best;
}
static String awm(Ac transaction) {
// A proven exact all-cost action reads as the action, not as its largest rune.
if (!transaction.spellName().isEmpty() && kl(transaction)) {
return transaction.spellName();
}
String name = "";
long lead = -1L;
for (Ab flow : transaction.getFlows()) {
if (flow == null) {
continue;
}
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
if (flow == null) {
continue;
}
any = true;
if (flow.valueDelta > 0L) {
return false;
}
}
return any;
}
// ---- previous comparison ---------------------------------------------------------------
static Comparison comparison(List<Run> lineage, Run selected, Long rate) {
long ato = selected.completionAt();
Run previous = null;
for (Run candidate : lineage) {
long at = candidate.completionAt();
if (!candidate.session.getId().equals(selected.session.getId())
&& at < ato && (previous == null || at > previous.completionAt())) {
previous = candidate;
}
}
if (previous == null) {
return new Comparison(false, false, 0L, null, null, 0L);
}
Bu metrics = selected.metrics;
Bu before = previous.metrics;
return new Comparison(false, true, metrics.net - before.net,
rate != null && previous.gpPerHour != null ? rate - previous.gpPerHour : null,
metrics.costSplitAvailable && before.costSplitAvailable
? metrics.suppliesCosts - before.suppliesCosts : null,
metrics.elapsedMillis - before.elapsedMillis);
}
// ---- same-Grind PBs --------------------------------------------------------------------
static PBs pbs(List<Run> lineage, Run selected, Long rate) {
long net = selected.net();
Long apl = null;
Long amq = null;
for (Run other : lineage) {
if (!other.session.getId().equals(selected.session.getId())) {
apl = max(apl, other.net());
amq = max(amq, other.gpPerHour);
}
}
boolean rates = rate != null && amq != null;
return new PBs(false, max(net, apl),
apl != null && net > apl, apl != null && net == apl,
max(rate, amq), rates && rate > amq, rates && rate.longValue() == amq);
}
static Long max(Long a, Long b) {
return a == null ? b : b == null ? a : Long.valueOf(Math.max(a, b));
}
/** Full-session rate: Net over canonical Active Time; zero Active Time has no rate. */
static long hourly(long net, long millis) {
return millis <= 0L ? 0L : round(net * 3_600_000d / millis);
}
}
