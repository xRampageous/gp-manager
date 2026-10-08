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
 Dr parse;
 Long value;
 boolean empty() {
  return parse == Dr.EMPTY;
 }
 boolean invalid() {
  return parse == Dr.INVALID;
 }
}

/** Friendly Net-target parsing: 5m, 5M, 5000k, 5,000,000. Blank means no target. */
static Dn acv(String /* raw */a) {
 if (blank(a)) return new Dn(Dr.EMPTY, null);
 // Strict whole-string form: digits with optional decimals and one k/m/b suffix. Anything
 // else is malformed and must never be treated as "clear".
 String /* text */c = Ag.lower(a).replace(",", "").replace("_", "").replace(" ", "");
 if (!c.matches("\\d+(?:\\.\\d+)?[kmb]?")) return new Dn(Dr.INVALID, null);
 long /* value */e = axx(c);
 return e > 0L ? new Dn(Dr.VALID, e) : new Dn(Dr.INVALID, null);
}

/** Friendly Active-Time parsing: 90m, 3h, "3h 30m", plain minutes. Blank means no target. */
static Dn ade(String /* raw */a) {
 if (blank(a)) return new Dn(Dr.EMPTY, null);
 String /* text */b = Ag.lower(a).replace(",", "").replace("_", "").trim();
 if (b.matches("\\d+(?:\\.\\d+)?")) {
  long /* minutes */c = round(Double.parseDouble(b));
  return c > 0L ? new Dn(Dr.VALID, c * 60_000L)
  : new Dn(Dr.INVALID, null);
 }
 // Strict whole-string form: optional "Nh" then optional "Mm" and nothing else.
 java.util.regex.Matcher /* matcher */e = java.util.regex.Pattern
 .compile("^(?:(\\d+(?:\\.\\d+)?)\\s*h)?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*m)?$").matcher(b);
 if (!e.matches() || (e.group(1) == null && e.group(2) == null)) {
  return new Dn(Dr.INVALID, null);
 }
 long /* total */f = 0L;
 if (e.group(1) != null) f += round(Double.parseDouble(e.group(1)) * 3_600_000d);
 if (e.group(2) != null) f += round(Double.parseDouble(e.group(2)) * 60_000d);
 return f > 0L ? new Dn(Dr.VALID, f) : new Dn(Dr.INVALID, null);
}

/** Applies a Net input to an existing target: INVALID keeps the existing value untouched. */
static Long ks(String /* raw */a, Long /* existing */b) {
 Dn /* input */c = acv(a);
 if (c.invalid()) return b;
 return c.value;
}

/** Applies an Active-Time input to an existing target: INVALID keeps the existing value. */
static Long lb(String /* raw */a, Long /* existing */b) {
 Dn /* input */c = ade(a);
 if (c.invalid()) return b;
 return c.value;
}

/** True when both fields are safe to apply (valid or deliberately blank). */
static boolean vv(String /* netRaw */a, String /* timeRaw */b) {
 return !acv(a).invalid() && !ade(b).invalid();
}

/** Derived pace over canonical facts; nothing here is booked or persisted. */
@AllArgsConstructor
static class Pace {
 /** True when a Pace question exists at all (at least one target). */
 boolean present;
 boolean available;
 String line;
 String second;
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
static Pace pace(Long /* netTargetGp */a, Long /* timeTargetMillis */b, long /* net */c,
boolean /* rateEstablished */d, long /* gpPerHour */e, long /* activeMillis */f) {
 if (a == null && b == null) return Pace.none();
 if (!d) return new Pace(true, false, "Calculating\u2026", null);
 if (a != null && b == null) {
  if (c >= a) return new Pace(true, true, "Target reached", null);
  if (e <= 0L) {
   // The rate is established and genuinely non-positive: an ETA would be fabricated.
   return new Pace(true, true, "Not on pace", null);
  }
  long /* minutes */g = nonNeg(round((a - c) * 60d / e));
  return new Pace(true, true, "~" + rb(g) + " remaining", null);
 }
 long /* remaining */i = nonNeg(b - f);
 long /* projected */j = c + round(e * (i / 3_600_000d));
 if (a == null) {
  return i <= 0L ? new Pace(true, true, "Time target reached", null) : new Pace(true, true,
  "~" + signed(j) + " projected Net", null);
 }
 if (i <= 0L) {
  return new Pace(true, true, "Time target reached", "Required average was "
  + compact(agd(a, c, f)) + "/h");
 }
 long /* required */k = agd(a, c, b);
 return new Pace(true, true, "~" + signed(j) + " projected at " + rb(b / 60_000L),
 "Required average " + compact(k) + "/h");
}

static long agd(long /* target */a, long /* net */b, long /* byMillis */c) {
 if (c <= 0L) return 0L;
 return round(nonNeg(a - b) * 3_600_000d / c);
}

static String rb(long /* minutes */a) {
 return a < 90L ? a + "m" : (a / 60L) + "h " + (a % 60L) + "m";
}

/** One reusable definition plus its derived Last/Best subset from linked canonical history. */
@AllArgsConstructor
static class MyGrind {
 SavedState.Ap definition;
 int yk;
 boolean hasLast;
 long lastNet;
 long lastStartedAt;
 boolean hasBest;
 long bestNet;
}

/** One recent canonical instance, rendered with Grind language. */
@AllArgsConstructor
static class Recent {
 String sessionId;
 String name;
 long startedAt;
 long net;
 long activeMillis;
 String linkedGrindId;
}

/** One instance's detail: financial summary, targets and target outcome. */
@AllArgsConstructor
static class Detail {
 String sessionId;
 String name;
 long startedAt;
 long net;
 long revenue;
 long supplies;
 long losses;
 boolean splitAvailable;
 long activeMillis;
 long elapsedMillis;
 boolean rateEstablished;
 long gpPerHour;
 Long netTargetGp;
 Long activeTimeTargetMillis;
 boolean closed;
 boolean compacted;
 String linkedGrindId;
 /** Derived recap/comparison/PB facts for a completed Grind; null while it is running. */
 Ba history;
 /** Factual target outcome wording; never "Failed". */
 String aay() {
  return Ba.aay(netTargetGp, net, closed);
 }
 String akd() {
  return Ba.akd(activeTimeTargetMillis, activeMillis);
 }
}

boolean hasActive;
String activeName;
String activeSessionId;
long activeNet;
long activeGpPerHour;
boolean activeRateEstablished;
long activeMillis;
Long activeNetTarget;
Long activeTimeTarget;
String activeGrindId;
Pace activePace;
List<MyGrind> myGrinds;
List<Recent> recent;
ZoneId profileZone;
Detail detail;
AllTime allTime;
/** The All time card: every retained session (Free play included) from canonical metrics. */
@AllArgsConstructor
static class AllTime {
 long net;
 long activeMillis;
 Long gpPerHour;
 int grinds;
 boolean splitAvailable;
 long supplies;
 long losses;
 String bestNetName;
 long bestNet;
 String bestRateName;
 long bestRate;
 /** Bank (when last seen) plus what is carried now; -1 age until the bank has been opened. */
 long wealth;
 long bankAge;
 long bank;
}

static AllTime allTime(Am /* engine */a, long /* now */b) {
 Sm z0 = a.summary();
 long /* net */c = 0L, /* active */d = 0L, /* supplies */e = 0L, /* losses */f = 0L, /* bestNet */g = 0L, /* bestRate */h = 0L;
 var /* grinds */i = new HashSet<String>();
 boolean /* split */j = true;
 String /* bestNetName */k = null;
 String /* bestRateName */l = null;
 var /* seen */m = new HashSet<String>();
 var /* sessions */n = new ArrayList<Ad>(a.getHistory());
 n.add(a.getActiveSession());
 n.add(a.getGeneralSession());
 for (Ad /* session */o : n) {
  if (o == null || !m.add(o.getId())) continue;
  var /* run */p = z0.run(a, o, b);
  Bu /* metrics */q = p.retained ? p.metrics : z0.metrics(o, b);
  c = safeAdd(c, q.net);
  d = safeAdd(d, q.elapsedMillis);
  j &= q.costSplitAvailable;
  e = safeAdd(e, q.suppliesCosts);
  f = safeAdd(f, q.otherCosts);
  if (o.getOwnerKind() == Bt.NAMED_SESSION) {
   // Runs of one saved Grind count once; an unsaved Grind is its own name.
   i.add(o.xf() ? o.getGrindId() : "name:" + o.getName());
  }
  if (!o.isClosed() || o.excludedFromAverages) continue;
  if (k == null || p.net() > g) {
   k = o.getName();
   g = p.net();
  }
  if (p.gpPerHour != null && (l == null || p.gpPerHour > h)) {
   l = o.getName();
   h = p.gpPerHour;
  }
 }
 long /* wealth */r;
 long /* bankAge */s;
 long /* bank */t;
 synchronized (a) {
  t = a.bankValue;
  r = safeAdd(a.bankValue, a.carriedValue);
  s = a.bankAt == 0L ? -1L : b - a.bankAt;
 }
 return new AllTime(c, d, RateReadiness.wn(d)
 ? Ba.hourly(c, d) : null, i.size(), j, e, f,
 k, g, l, h, r, s, t);
}

static As capture(Am /* engine */a, long /* now */b, boolean /* includeArchived */c, String /* detailSessionId */d) {
 Sm z0 = a.summary();
 Ad /* active */e = a.getActiveSession();
 boolean /* custom */f = a.wb();
 long /* net */g = 0L;
 long /* rate */h = 0L;
 boolean /* rateEstablished */i = false;
 long /* activeMillis */j = 0L;
 Long /* netTarget */k = null;
 Long /* timeTarget */l = null;
 String /* activeName */m = "";
 String /* activeSessionId */n = "";
 String /* activeGrindId */o = "";
 if (e != null) {
  Bu /* metrics */p = z0.metrics(a.getActiveSession(), b);
  g = p.net;
  h = p.profitPerHour;
  i = RateReadiness.wn(p.elapsedMillis);
  j = p.elapsedMillis;
  k = e.getProfitTargetGp();
  l = e.getActiveTimeTargetMillis();
  m = e.getName();
  n = e.getId();
  o = e.getGrindId();
 }
 Pace /* pace */q = pace(k, l, g, i, h, j);
 var /* myGrinds */r = new ArrayList<MyGrind>();
 for (SavedState.Ap /* definition */s : a.getSavedGrinds(c)) {
  Sm.G t = z0.g.get(s.getGrindId());
  r.add(new MyGrind(s, t.count, t.has, t.last, t.at, t.has, t.best));
 }
 r.sort(Comparator.comparing((MyGrind /* grind */aa) -> !aa.definition.favorite)
 .thenComparing(/* grind */ab -> -ab.lastStartedAt)
 .thenComparing(/* grind */ac -> ac.definition.getName(), String.CASE_INSENSITIVE_ORDER)
 .thenComparing(/* grind */ad -> ad.definition.getGrindId()));
 var /* recent */ae = new ArrayList<Recent>();
 for (Ad /* session */af : a.getHistory()) {
  if (af == null) continue;
  Bu /* metrics */ag = z0.run(a, af, b).metrics;
  ae.add(new Recent(af.getId(), af.getName(), af.startedAtEpochMillis,
  ag.net, ag.elapsedMillis, af.getGrindId()));
 }
 ae.sort(Comparator.comparingLong((Recent /* item */ah) -> ah.startedAt).reversed()
 .thenComparing(/* item */ai -> ai.sessionId));
 Detail /* detail */aj = null;
 if (d != null && !d.isEmpty()) {
  Ad /* selected */ak = e != null && d.equals(e.getId())
  ? e : a.ua(d);
  if (ak != null) {
   boolean /* liveDetail */al = e != null && d.equals(e.getId());
   Bu /* metrics */am = al ? z0.metrics(a.getActiveSession(), b) : z0.run(a, ak, b).metrics;
   String /* ask */an = ak.getGrindId();
   aj = new Detail(ak.getId(), ak.getName(), ak.startedAtEpochMillis, am.net, am.revenue,
   am.costSplitAvailable ? am.suppliesCosts : -1L, am.costSplitAvailable ? am.otherCosts : -1L,
   am.costSplitAvailable, am.elapsedMillis, am.elapsedMillis,
   RateReadiness.wn(am.elapsedMillis), Ba.hourly(am.net, am.elapsedMillis),
   ak.getProfitTargetGp(), ak.getActiveTimeTargetMillis(),
   ak.isClosed(), ak.tp() != 0L || ak.tn() != 0L, an,
   ak.isClosed() ? z0.fact(ak).recap : null);
  }
 }
 ZoneId /* profileZone */ao = a.uf();
 return new As(e != null && f, m, n, g, h,
 i, j, k, l, o, q, r,
 ae, ao, aj, allTime(a, b));
}
}
