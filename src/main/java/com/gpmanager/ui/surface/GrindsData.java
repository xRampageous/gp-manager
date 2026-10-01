package com.gpmanager;
import com.gpmanager.GrindHistory.Run;
import java.time.ZoneId;
import java.util.*;
import lombok.*;
import static com.gpmanager.ModelText.*;
import static java.lang.Math.*;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.Fmt.*;
/**
* Grinds read model: the current canonical Grind, the reusable My Grinds definitions with their
* derived Last/Best subset, the Recent Grinds history and one Grind detail. Targets and Pace are
* derived from canonical facts; estimates never feed accounting, completion or PBs.
*/
@AllArgsConstructor
class GrindsData {
/** How one target field parsed; INVALID never mutates and never means "clear". */
enum TargetParse {
EMPTY, VALID, INVALID
}

/** One parsed target input; {@code value} is non-null only when {@code parse == VALID}. */
@AllArgsConstructor
static class TargetInput {
final TargetParse parse;
final Long value;
boolean empty() {
return parse == TargetParse.EMPTY;
}
boolean invalid() {
return parse == TargetParse.INVALID;
}
}

/** Friendly Net-target parsing: 5m, 5M, 5000k, 5,000,000. Blank means no target. */
static TargetInput parseNetInput(String raw) {
if (blank(raw)) return new TargetInput(TargetParse.EMPTY, null);
// Strict whole-string form: digits with optional decimals and one k/m/b suffix. Anything
// else is malformed and must never be treated as "clear".
String text = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "").replace(" ", "");
if (!text.matches("\\d+(?:\\.\\d+)?[kmb]?")) return new TargetInput(TargetParse.INVALID, null);
long value = parseGp(text);
return value > 0L ? new TargetInput(TargetParse.VALID, value) : new TargetInput(TargetParse.INVALID, null);
}

/** Friendly Active-Time parsing: 90m, 3h, "3h 30m", plain minutes. Blank means no target. */
static TargetInput parseTimeInput(String raw) {
if (blank(raw)) return new TargetInput(TargetParse.EMPTY, null);
String text = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "").trim();
if (text.matches("\\d+(?:\\.\\d+)?")) {
long minutes = round(Double.parseDouble(text));
return minutes > 0L ? new TargetInput(TargetParse.VALID, minutes * 60_000L)
: new TargetInput(TargetParse.INVALID, null);
}
// Strict whole-string form: optional "Nh" then optional "Mm" and nothing else.
java.util.regex.Matcher matcher = java.util.regex.Pattern
.compile("^(?:(\\d+(?:\\.\\d+)?)\\s*h)?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*m)?$").matcher(text);
if (!matcher.matches() || (matcher.group(1) == null && matcher.group(2) == null)) {
return new TargetInput(TargetParse.INVALID, null);
}
long total = 0L;
if (matcher.group(1) != null) total += round(Double.parseDouble(matcher.group(1)) * 3_600_000d);
if (matcher.group(2) != null) total += round(Double.parseDouble(matcher.group(2)) * 60_000d);
return total > 0L ? new TargetInput(TargetParse.VALID, total) : new TargetInput(TargetParse.INVALID, null);
}

/** Applies a Net input to an existing target: INVALID keeps the existing value untouched. */
static Long applyNetInput(String raw, Long existing) {
TargetInput input = parseNetInput(raw);
if (input.invalid()) return existing;
return input.value;
}

/** Applies an Active-Time input to an existing target: INVALID keeps the existing value. */
static Long applyTimeInput(String raw, Long existing) {
TargetInput input = parseTimeInput(raw);
if (input.invalid()) return existing;
return input.value;
}

/** True when both fields are safe to apply (valid or deliberately blank). */
static boolean inputsApplyable(String netRaw, String timeRaw) {
return !parseNetInput(netRaw).invalid() && !parseTimeInput(timeRaw).invalid();
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
if (netTargetGp == null && timeTargetMillis == null) return Pace.none();
if (!rateEstablished) return new Pace(true, false, "Calculating\u2026", null);
if (netTargetGp != null && timeTargetMillis == null) {
if (net >= netTargetGp) return new Pace(true, true, "Target reached", null);
if (gpPerHour <= 0L) {
// The rate is established and genuinely non-positive: an ETA would be fabricated.
return new Pace(true, true, "Not on pace", null);
}
long minutes = nonNeg(round((netTargetGp - net) * 60d / gpPerHour));
return new Pace(true, true, "~" + durationMinutes(minutes) + " remaining", null);
}
long remaining = nonNeg(timeTargetMillis - activeMillis);
long projected = net + round(gpPerHour * (remaining / 3_600_000d));
if (netTargetGp == null) {
return remaining <= 0L ? new Pace(true, true, "Time target reached", null) : new Pace(true, true,
"~" + signed(projected) + " projected Net", null);
}
if (remaining <= 0L) {
return new Pace(true, true, "Time target reached", "Required average was "
+ compact(requiredAverage(netTargetGp, net, activeMillis)) + "/h");
}
long required = requiredAverage(netTargetGp, net, timeTargetMillis);
return new Pace(true, true, "~" + signed(projected) + " projected at " + durationMinutes(timeTargetMillis / 60_000L),
"Required average " + compact(required) + "/h");
}

static long requiredAverage(long target, long net, long byMillis) {
if (byMillis <= 0L) return 0L;
return round(nonNeg(target - net) * 3_600_000d / byMillis);
}

static String durationMinutes(long minutes) {
return minutes < 90L ? minutes + "m" : (minutes / 60L) + "h " + (minutes % 60L) + "m";
}

/** One reusable definition plus its derived Last/Best subset from linked canonical history. */
@AllArgsConstructor
static class MyGrind {
final SavedState.SavedGrind definition;
final int linkedSessions;
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
final GrindHistory history;
/** Factual target outcome wording; never "Failed". */
String netOutcome() {
return GrindHistory.netOutcome(netTargetGp, net, closed);
}
String timeOutcome() {
return GrindHistory.timeOutcome(activeTimeTargetMillis, activeMillis);
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

static AllTime allTime(Engine engine, long now) {
long net = 0L, active = 0L, supplies = 0L, losses = 0L, bestNet = 0L, bestRate = 0L;
var grinds = new HashSet<String>();
boolean split = true;
String bestNetName = null;
String bestRateName = null;
var seen = new HashSet<String>();
var sessions = new ArrayList<Session>(engine.getHistory());
sessions.add(engine.getActiveSession());
sessions.add(engine.getGeneralSession());
for (Session session : sessions) {
if (session == null || !seen.add(session.getId())) continue;
var run = new Run(engine, session, now);
SessionMetrics metrics = run.retained ? run.metrics : session.metrics(now);
net = safeAdd(net, metrics.net);
active = safeAdd(active, metrics.elapsedMillis);
split &= metrics.costSplitAvailable;
supplies = safeAdd(supplies, metrics.suppliesCosts);
losses = safeAdd(losses, metrics.otherCosts);
if (session.getOwnerKind() == SessionOwnerKind.NAMED_SESSION) {
// Runs of one saved Grind count once; an unsaved Grind is its own name.
grinds.add(session.isLinkedToGrind() ? session.getGrindId() : "name:" + session.getName());
}
if (!session.isClosed() || session.excludedFromAverages) continue;
if (bestNetName == null || run.net() > bestNet) {
bestNetName = session.getName();
bestNet = run.net();
}
if (run.gpPerHour != null && (bestRateName == null || run.gpPerHour > bestRate)) {
bestRateName = session.getName();
bestRate = run.gpPerHour;
}
}
return new AllTime(net, active, RateReadiness.isFullRateEstablished(active)
? GrindHistory.hourly(net, active) : null, grinds.size(), split, supplies, losses,
bestNetName, bestNet, bestRateName, bestRate);
}

static GrindsData capture(Engine engine, long now, boolean includeArchived, String detailSessionId) {
Session active = engine.getActiveSession();
boolean custom = engine.isCustomSessionActive();
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
SessionMetrics metrics = engine.getMetrics(now);
net = metrics.net;
rate = metrics.profitPerHour;
rateEstablished = RateReadiness.isFullRateEstablished(metrics.elapsedMillis);
activeMillis = metrics.elapsedMillis;
netTarget = active.getProfitTargetGp();
timeTarget = active.getActiveTimeTargetMillis();
activeName = active.getName();
activeSessionId = active.getId();
activeGrindId = active.getGrindId();
}
Pace pace = pace(netTarget, timeTarget, net, rateEstablished, rate, activeMillis);
var myGrinds = new ArrayList<MyGrind>();
for (SavedState.SavedGrind definition : engine.getSavedGrinds(includeArchived)) {
List<Session> linkedSessions = engine.completedLinkedSessions(definition.getGrindId());
Session last = null;
long best = Long.MIN_VALUE;
long lastNet = 0L;
for (Session session : linkedSessions) {
long sessionNet = new Run(engine, session, now).net();
if (last == null || session.startedAtEpochMillis > last.startedAtEpochMillis) {
last = session;
lastNet = sessionNet;
}
best = max(best, sessionNet);
}
boolean hasAny = last != null;
myGrinds.add(new MyGrind(definition, linkedSessions.size(), hasAny, lastNet,
hasAny ? last.startedAtEpochMillis : 0L, hasAny, best == Long.MIN_VALUE ? 0L : best));
}
myGrinds.sort(Comparator.comparing((MyGrind grind) -> !grind.definition.favorite)
.thenComparing(grind -> -grind.lastStartedAt)
.thenComparing(grind -> grind.definition.getName(), String.CASE_INSENSITIVE_ORDER)
.thenComparing(grind -> grind.definition.getGrindId()));
var recent = new ArrayList<Recent>();
for (Session session : engine.getHistory()) {
if (session == null) continue;
SessionMetrics metrics = new Run(engine, session, now).metrics;
recent.add(new Recent(session.getId(), session.getName(), session.startedAtEpochMillis,
metrics.net, metrics.elapsedMillis, session.getGrindId()));
}
recent.sort(Comparator.comparingLong((Recent item) -> item.startedAt).reversed()
.thenComparing(item -> item.sessionId));
Detail detail = null;
if (detailSessionId != null && !detailSessionId.isEmpty()) {
Session selected = active != null && detailSessionId.equals(active.getId())
? active : engine.getHistorySession(detailSessionId);
if (selected != null) {
boolean liveDetail = active != null && detailSessionId.equals(active.getId());
SessionMetrics metrics = liveDetail ? engine.getMetrics(now) : new Run(engine, selected, now).metrics;
String linkedId = selected.getGrindId();
detail = new Detail(selected.getId(), selected.getName(), selected.startedAtEpochMillis, metrics.net, metrics.revenue,
metrics.costSplitAvailable ? metrics.suppliesCosts : -1L, metrics.costSplitAvailable ? metrics.otherCosts : -1L,
metrics.costSplitAvailable, metrics.elapsedMillis, metrics.elapsedMillis,
RateReadiness.isFullRateEstablished(metrics.elapsedMillis), GrindHistory.hourly(metrics.net, metrics.elapsedMillis),
selected.getProfitTargetGp(), selected.getActiveTimeTargetMillis(),
selected.isClosed(), selected.getCompactedRevenue() != 0L || selected.getCompactedCosts() != 0L, linkedId,
selected.isClosed() ? GrindHistory.of(engine, selected, now) : null);
}
}
ZoneId profileZone = engine.getProfileTimeZone();
return new GrindsData(active != null && custom, activeName, activeSessionId, net, rate,
rateEstablished, activeMillis, netTarget, timeTarget, activeGrindId, pace, myGrinds,
recent, profileZone, detail, allTime(engine, now));
}
}
