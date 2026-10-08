package com.gpmanager;
import static com.gpmanager.Ak.msg;
import java.util.*;
import lombok.*;
import static java.lang.Math.*;
import static com.gpmanager.Fmt.*;
import com.gpmanager.Ba.*;
class Ob {

static Ba of(Ad /* selected */a) {
 boolean /* amn */b = a.compactedTransactionCount > 0L;
 return new Ba(new Recap(b ? null : biggest(a, true), b ? null : lz(a),
 b, b ? Collections.emptyList() : sources(a)));
}

static String aay(Long /* netTargetGp */a, long /* net */b, boolean /* closed */c) {
 if (a == null) return "No Net target";
 if (b >= a) return "Reached " + compact(a) + " Net";
 return c ? msg("kr") + signed(a - b) + " remaining"
 : msg("ks") + signed(a - b) + " remaining";
}

static String akd(Long /* activeTimeTargetMillis */a, long /* activeMillis */b) {
 if (a == null) return msg("fj");
 if (b >= a) return msg("f3");
 return duration(a - b) + msg("f2");
}

static List<Highlight> sources(Ad /* session */c) {
 var /* gains */d = new HashMap<String, Long>();
 for (Ac /* transaction */e : c.getTransactions()) {
  Ai /* type */f = e.getType();
  if (e.isCounted() && (f == Ai.LOOT || f == Ai.PK_LOOT)) {
   d.merge(e.getActivityName(), e.getRevenue(), Long::sum);
  }
 }
 var /* sources */g = new ArrayList<Highlight>();
 d.forEach((/* name */h, /* value */i) -> g.add(new Highlight(h, i)));
 g.sort((a, b) -> Long.compare(b.net, a.net));
 return g.subList(0, min(5, g.size()));
}

static Highlight lz(Ad /* session */a) {
 return biggest(a, false);
}

static Highlight biggest(Ad /* session */a, boolean /* gain */b) {
 Highlight /* best */c = null;
 for (Ac /* transaction */d : a.getTransactions()) {
  if (d == null || !d.isCounted() || d.getType() == Ai.TRANSFER) {
   continue;
  }
  long /* net */e = d.getNet();
  if (b ? e <= 0L : e >= 0L) continue;
  if (c == null || (b ? e > c.net : e < c.net)) {
   c = new Highlight(awm(d), e);
  }
 }
 return c;
}

static String awm(Ac /* transaction */a) {
 // A proven exact all-cost action reads as the action, not as its largest rune.
 if (!a.spellName().isEmpty() && kl(a)) return a.spellName();
 String /* name */b = "";
 long /* lead */c = -1L;
 for (Ab /* flow */d : a.getFlows()) {
  if (d == null) continue;
  long /* abs */e = abs(d.valueDelta);
  if (e > c) {
   c = e;
   // A sip reads as its potion, as the Ledger pairs it: "Prayer potion", not "(4)".
   b = Bp.qb(a).isEmpty() ? d.itemName
   : Af.abb(d.itemName);
  }
 }
 if (Ag.empty(b)) {
  b = a.getNote().isEmpty() ? a.getActivityName() : a.getNote();
 }
 return b;
}

static boolean kl(Ac /* transaction */a) {
 boolean /* any */b = false;
 for (Ab /* flow */c : a.getFlows()) {
  if (c == null) continue;
  b = true;
  if (c.valueDelta > 0L) return false;
 }
 return b;
}

static long hourly(long /* net */a, long /* millis */b) {
 return b <= 0L ? 0L : round(a * 3_600_000d / b);
}
static class Run {
 Ad session;
 Bu metrics;
 boolean retained;
 Long gpPerHour;
 Run(Am /* engine */c, Ad /* session */d, long /* now */e) {
  Bu /* amx */f = c.tz(d.getId(), e);
  this.session = d;
  this.retained = f != null;
  if (f != null) {
   this.metrics = f;
  } else {
   Ad.CompactedContribution /* money */g = d.pl();
   this.metrics = new Bu(d.getActivityHint(), d.paused, d.getElapsedMillis(e),
   g.revenue, g.costs, g.getNet(), 0L);
  }
  long /* active */h = metrics.elapsedMillis;
  this.gpPerHour = RateReadiness.wn(h) ? hourly(metrics.net, h) : null;
 }
 long net() {
  return metrics.net;
 }
}
}
