package com.gpmanager;
import com.gpmanager.Ba.Run;
import java.time.ZoneId;
import java.util.*;
import lombok.*;
import static com.gpmanager.Ag.*;
import static java.lang.Math.*;
import static com.gpmanager.Ae.*;
import static com.gpmanager.Fmt.*;
/**
* Grinds read model: the current canonical Grind, the reusable My Grinds definitions with their
* derived Last/Best subset, the Recent Grinds history and one Grind detail. Targets and Pace are
* derived from canonical facts; estimates never feed accounting, completion or PBs.
*/
@AllArgsConstructor
class As {
/** How one target field parsed; INVALID never mutates and never means "clear". */
enum Dr {
EMPTY, VALID, INVALID
}
/** One parsed target input; {@code value} is non-null only when {@code parse == VALID}. */
@AllArgsConstructor
static class Dn {
final Dr parse;
final Long value;
boolean empty() {
return parse == Dr.EMPTY;
}
boolean invalid() {
return parse == Dr.INVALID;
}
}
/** Friendly Net-target parsing: 5m, 5M, 5000k, 5,000,000. Blank means no target. */
static Dn acv(String raw) {
if (blank(raw)) {
return new Dn(Dr.EMPTY, null);
}
// Strict whole-string form: digits with optional decimals and one k/m/b suffix. Anything
// else is malformed and must never be treated as "clear".
String text = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "").replace(" ", "");
if (!text.matches("\\d+(?:\\.\\d+)?[kmb]?")) {
return new Dn(Dr.INVALID, null);
}
long value = axx(text);
return value > 0L ? new Dn(Dr.VALID, value) : new Dn(Dr.INVALID, null);
}
/** Friendly Active-Time parsing: 90m, 3h, "3h 30m", plain minutes. Blank means no target. */
static Dn ade(String raw) {
if (blank(raw)) {
return new Dn(Dr.EMPTY, null);
}
String text = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "").trim();
if (text.matches("\\d+(?:\\.\\d+)?")) {
long minutes = round(Double.parseDouble(text));
return minutes > 0L ? new Dn(Dr.VALID, minutes * 60_000L)
: new Dn(Dr.INVALID, null);
}
// Strict whole-string form: optional "Nh" then optional "Mm" and nothing else.
java.util.regex.Matcher matcher = java.util.regex.Pattern
.compile("^(?:(\\d+(?:\\.\\d+)?)\\s*h)?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*m)?$")
.matcher(text);
if (!matcher.matches() || (matcher.group(1) == null && matcher.group(2) == null)) {
return new Dn(Dr.INVALID, null);
}
long total = 0L;
if (matcher.group(1) != null) {
total += round(Double.parseDouble(matcher.group(1)) * 3_600_000d);
}
if (matcher.group(2) != null) {
total += round(Double.parseDouble(matcher.group(2)) * 60_000d);
}
return total > 0L ? new Dn(Dr.VALID, total) : new Dn(Dr.INVALID, null);
}
/** Applies a Net input to an existing target: INVALID keeps the existing value untouched. */
static Long ks(String raw, Long existing) {
Dn input = acv(raw);
if (input.invalid()) {
return existing;
}
return input.value;
}
/** Applies an Active-Time input to an existing target: INVALID keeps the existing value. */
static Long lb(String raw, Long existing) {
Dn input = ade(raw);
if (input.invalid()) {
return existing;
}
return input.value;
}
/** True when both fields are safe to apply (valid or deliberately blank). */
static boolean vv(String netRaw, String timeRaw) {
return !acv(netRaw).invalid() && !ade(timeRaw).invalid();
}
/** Derived pace over canonical facts; nothing here is booked or persisted. */
@AllArgsConstructor
static class Pace {
/** True when a Pace question exists at all (at least one target). */
final boolean present;
final boolean available;
final String line;
final String second;
/** No target at all: there is no Pace question to answer, so no row renders. */
static Pace none() {
return new Pace(false, false, "", null);
}
}
/**
* Mode A/B/C pace. A: time until the Net target. B: projected Net at the Active-Time target.
* C: both plus the required average. Unavailable evidence reads "Calculating…" instead of a
* fabricated precision; estimates stay visually muted.
*/
static Pace pace(Long netTargetGp, Long timeTargetMillis, long net,
boolean rateEstablished, long gpPerHour, long activeMillis) {
if (netTargetGp == null && timeTargetMillis == null) {
return Pace.none();
}
if (!rateEstablished) {
return new Pace(true, false, "Calculating\u2026", null);
}
if (netTargetGp != null && timeTargetMillis == null) {
if (net >= netTargetGp) {
return new Pace(true, true, "Target reached", null);
}
if (gpPerHour <= 0L) {
// The rate is established and genuinely non-positive: an ETA would be fabricated.
return new Pace(true, true, "Not on pace", null);
}
long minutes = nonNeg(round((netTargetGp - net) * 60d / gpPerHour));
return new Pace(true, true, "~" + rb(minutes) + " remaining", null);
}
long remaining = nonNeg(timeTargetMillis - activeMillis);
long projected = net + round(gpPerHour * (remaining / 3_600_000d));
if (netTargetGp == null) {
return remaining <= 0L
? new Pace(true, true, "Time target reached", null)
: new Pace(true, true,
"~" + signed(projected) + " projected Net", null);
}
if (remaining <= 0L) {
return new Pace(true, true, "Time target reached", "Required average was "
+ compact(agd(netTargetGp, net, activeMillis)) + "/h");
}
long required = agd(netTargetGp, net, timeTargetMillis);
return new Pace(true, true, "~" + signed(projected) + " projected at "
+ rb(timeTargetMillis / 60_000L),
"Required average " + compact(required) + "/h");
}
static long agd(long target, long net, long byMillis) {
if (byMillis <= 0L) {
return 0L;
}
return round(nonNeg(target - net) * 3_600_000d / byMillis);
}
static String rb(long minutes) {
return minutes < 90L ? minutes + "m" : (minutes / 60L) + "h " + (minutes % 60L) + "m";
}
/** One reusable definition plus its derived Last/Best subset from linked canonical history. */
@AllArgsConstructor
static class MyGrind {
final SavedState.Ap definition;
final int yk;
final boolean hasLast;
final long lastNet;
final long lastStartedAt;
final boolean hasBest;
final long bestNet;
}
/** One recent canonical instance, rendered with Grind language. */
@AllArgsConstructor
static class Recent {
final String sessionId;
final String name;
final long startedAt;
final long net;
final long activeMillis;
final String linkedGrindId;
}
/** One instance's detail: financial summary, targets and target outcome. */
@AllArgsConstructor
static class Detail {
final String sessionId;
final String name;
final long startedAt;
final long net;
final long revenue;
final long supplies;
final long losses;
final boolean splitAvailable;
final long activeMillis;
final long elapsedMillis;
final boolean rateEstablished;
final long gpPerHour;
final Long netTargetGp;
final Long activeTimeTargetMillis;
final boolean closed;
final boolean compacted;
final String linkedGrindId;
/** Derived recap/comparison/PB facts for a completed Grind; null while it is running. */
final Ba history;
/** Factual target outcome wording; never "Failed". */
String aay() {
return Ba.aay(netTargetGp, net, closed);
}
String akd() {
return Ba.akd(activeTimeTargetMillis, activeMillis);
}
}
final boolean hasActive;
final String activeName;
final String activeSessionId;
final long activeNet;
final long activeGpPerHour;
final boolean activeRateEstablished;
final long activeMillis;
final Long activeNetTarget;
final Long activeTimeTarget;
final String activeGrindId;
final Pace activePace;
final List<MyGrind> myGrinds;
final List<Recent> recent;
final ZoneId profileZone;
final Detail detail;
final AllTime allTime;
/** The All time card: every retained session (Free play included) from canonical metrics. */
@AllArgsConstructor
static class AllTime {
final long net;
final long activeMillis;
final Long gpPerHour;
final int grinds;
final boolean splitAvailable;
final long supplies;
final long losses;
final String bestNetName;
final long bestNet;
final String bestRateName;
final long bestRate;
}
static AllTime allTime(Am engine, long now) {
long net = 0L, active = 0L, supplies = 0L, losses = 0L, bestNet = 0L, bestRate = 0L;
var grinds = new HashSet<String>();
boolean split = true;
String bestNetName = null;
String bestRateName = null;
var seen = new HashSet<String>();
var sessions = new ArrayList<Ad>(engine.getHistory());
sessions.add(engine.getActiveSession());
sessions.add(engine.getGeneralSession());
for (Ad session : sessions) {
if (session == null || !seen.add(session.getId())) {
continue;
}
var run = new Run(engine, session, now);
Bu metrics = run.retained ? run.metrics : session.metrics(now, 900_000L);
net = safeAdd(net, metrics.net);
active = safeAdd(active, metrics.elapsedMillis);
split &= metrics.costSplitAvailable;
supplies = safeAdd(supplies, metrics.suppliesCosts);
losses = safeAdd(losses, metrics.otherCosts);
if (session.getOwnerKind() == Bt.NAMED_SESSION) {
// Runs of one saved Grind count once; an unsaved Grind is its own name.
grinds.add(session.xf() ? session.getGrindId() : "name:" + session.getName());
}
if (!session.isClosed() || session.excludedFromAverages) {
continue;
}
if (bestNetName == null || run.net() > bestNet) {
bestNetName = session.getName();
bestNet = run.net();
}
if (run.gpPerHour != null && (bestRateName == null || run.gpPerHour > bestRate)) {
bestRateName = session.getName();
bestRate = run.gpPerHour;
}
}
return new AllTime(net, active, RateReadiness.wn(active)
? Ba.hourly(net, active) : null, grinds.size(), split, supplies, losses,
bestNetName, bestNet, bestRateName, bestRate);
}
static As capture(Am engine, long now, boolean includeArchived,
String detailSessionId) {
Ad active = engine.getActiveSession();
boolean custom = engine.wb();
long net = 0L;
long rate = 0L;
boolean rateEstablished = false;
long activeMillis = 0L;
Long netTarget = null;
Long timeTarget = null;
String activeName = "";
String activeSessionId = "";
String activeGrindId = "";
if (active != null) {
Bu metrics = engine.getMetrics(now);
net = metrics.net;
rate = metrics.rollingProfitPerHour;
rateEstablished = RateReadiness.wm(metrics.rollingRateAvailable,
metrics.elapsedMillis);
activeMillis = metrics.elapsedMillis;
netTarget = active.getProfitTargetGp();
timeTarget = active.getActiveTimeTargetMillis();
activeName = active.getName();
activeSessionId = active.getId();
activeGrindId = active.getGrindId();
}
Pace pace = pace(netTarget, timeTarget, net, rateEstablished, rate, activeMillis);
var myGrinds = new ArrayList<MyGrind>();
for (SavedState.Ap definition : engine.getSavedGrinds(includeArchived)) {
List<Ad> yk = engine.pp(definition.getGrindId());
Ad last = null;
long best = Long.MIN_VALUE;
long lastNet = 0L;
for (Ad session : yk) {
long aqj = new Run(engine, session, now).net();
if (last == null || session.startedAtEpochMillis > last.startedAtEpochMillis) {
last = session;
lastNet = aqj;
}
best = max(best, aqj);
}
boolean avs = last != null;
myGrinds.add(new MyGrind(definition, yk.size(), avs, lastNet,
avs ? last.startedAtEpochMillis : 0L, avs, best == Long.MIN_VALUE ? 0L : best));
}
myGrinds.sort(Comparator
.comparing((MyGrind grind) -> !grind.definition.favorite)
.thenComparing(grind -> -grind.lastStartedAt)
.thenComparing(grind -> grind.definition.getName(), String.CASE_INSENSITIVE_ORDER)
.thenComparing(grind -> grind.definition.getGrindId()));
var recent = new ArrayList<Recent>();
for (Ad session : engine.getHistory()) {
if (session == null) {
continue;
}
Bu metrics = new Run(engine, session, now).metrics;
recent.add(new Recent(session.getId(), session.getName(), session.startedAtEpochMillis,
metrics.net, metrics.elapsedMillis, session.getGrindId()));
}
recent.sort(Comparator.comparingLong((Recent item) -> item.startedAt).reversed()
.thenComparing(item -> item.sessionId));
Detail detail = null;
if (detailSessionId != null && !detailSessionId.isEmpty()) {
Ad selected = active != null && detailSessionId.equals(active.getId())
? active : engine.ua(detailSessionId);
if (selected != null) {
boolean liveDetail = active != null && detailSessionId.equals(active.getId());
Bu metrics = liveDetail ? engine.getMetrics(now)
: new Run(engine, selected, now).metrics;
String ask = selected.getGrindId();
detail = new Detail(selected.getId(), selected.getName(), selected.startedAtEpochMillis,
metrics.net, metrics.revenue,
metrics.costSplitAvailable ? metrics.suppliesCosts : -1L,
metrics.costSplitAvailable ? metrics.otherCosts : -1L,
metrics.costSplitAvailable, metrics.elapsedMillis, metrics.elapsedMillis,
// Owner 2026-10-01 (F08): a closed detail rate is the full-session rate; only the live
// rolling rate depends on rolling-window coverage.
liveDetail
? RateReadiness.wm(metrics.rollingRateAvailable, metrics.elapsedMillis)
: RateReadiness.wn(metrics.elapsedMillis),
liveDetail ? metrics.rollingProfitPerHour : Ba.hourly(metrics.net, metrics.elapsedMillis),
selected.getProfitTargetGp(), selected.getActiveTimeTargetMillis(),
selected.isClosed(), selected.tp() != 0L || selected.tn() != 0L,
ask,
selected.isClosed() ? Ba.of(engine, selected, now) : null);
}
}
ZoneId profileZone = engine.uf();
return new As(active != null && custom, activeName, activeSessionId, net, rate,
rateEstablished, activeMillis, netTarget, timeTarget, activeGrindId,
pace, myGrinds,
recent, profileZone, detail, allTime(engine, now));
}
}
