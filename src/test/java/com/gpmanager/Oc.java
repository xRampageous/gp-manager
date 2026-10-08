package com.gpmanager;
import com.gpmanager.Bp.Dy;
import com.gpmanager.Bi.Row;
import com.gpmanager.Br.*;
import com.gpmanager.Ao.Bs;
import java.util.*;
import lombok.*;
import static java.lang.Math.*;
import static com.gpmanager.Ae.*;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.Ak.msg;
import com.gpmanager.Ca.*;
class Oc {
static final int RECENT_ROWS = 15;
static List<Object> recentKey = Collections.emptyList();
static List<Recent> recentList = Collections.emptyList();
static Ca capture(Am /* engine */a, long /* now */b, Dz /* context */c) {
 Dz /* ctx */d = c == null ? Dz.NONE : c;
 Bu /* metrics */e = a.getMetrics(b);
 Ad /* active */f = a.getActiveSession();
 boolean /* custom */g = a.wb();
 boolean /* freePlay */h = f == null || !g;
 String /* owner */i = f == null ? "Free play" : f.getName();
 long /* elapsed */j = e.elapsedMillis;
 int /* review */k = 0;
 List<Ac> /* transactions */l = f == null ? Collections.emptyList() : f.getTransactions();
 for (Ac t : l) {
  if (t == null) continue;
  if (Eh.aal(t)) k++;
 }
 // One shared projection for the whole snapshot: Recent rows and the Market separation. The
 // Market rows are rebuilt on every read, so they are read once.
 List<Row> /* marketRows */m = a.ub();
 // Hero categories are canonical and unfiltered: the whole counted Market-context
 // population is removed from ordinary Gains/Supplies/Losses and represented once by its
 // correction-aware net. Recent Activity display filters never enter this math.
 Dy /* zl */n = Bp.zl(l);
 long /* marketResult */o = n.getNet();
 int /* marketPending */p = 0;
 var /* marketByPresentation */q = new HashMap<String, Row>();
 for (Row /* row */r : m) {
  if (r != null) {
   q.put(r.presentationId, r);
   p += Ao.wr(r.lifecycle) ? 1 : 0;
  }
 }
 Dt /* pk */s = f == null ? null : a.vl();
 boolean /* pvpSession */u = f != null
 && (f.getMode() == Cx.PK || (s != null && s.ue() > 0));
 return new Ca(f == null ? "" : f.getId(), f != null, i, j, e.paused, d.idle,
 e.net, akw(e, n), akx(e, n),
 aky(e, n), aku(e, n), e.costSplitAvailable,
 RateReadiness.wn(j), e.profitPerHour,
 goal(f, e, j), o, p, h, d.activity, d.target, d.pvp.pvpPossible,
 d.pvp.skulled, d.pvp.protectItem, d.pvp.risk, k,
 recent(a, f, l, m, q, d), u, s == null ? 0 : s.kills,
 s == null ? 0 : s.deaths, s == null ? 0L : s.totalKillNet, s == null ? 0L : s.totalDeathLoss,
 s == null ? 0L : s.bestKill, a.getPendingClaims().size(), a.tt().awaiting
 ? max(1L, a.tt().outstandingItemCount) : 0L, s == null ? 0 : max(0, s.currentStreak),
 f == null ? 0 : f.mb(),
 f == null ? d.offline : f.paused && f.getPauseReason() == Ed.LIFECYCLE,
 f == null || f.getActiveTimeTargetMillis() == null ? 0L : f.getActiveTimeTargetMillis(),
 a.autoResumeSeconds(b), a.calibrating(b), d.hopping);
}

static Goal goal(Ad /* active */b, Bu /* metrics */c, long /* elapsed */d) {
 if (b == null) return null;
 Long /* target */e = b.getProfitTargetGp();
 if (e != null && e > 0L) {
  long /* current */f = c.net;
  double /* fraction */g = max(0d, min(1d, f / (double) e));
  boolean /* reached */h = f >= e;
  String /* eta */i = null;
  if (!h && d >= 60_000L && c.profitPerHour > 0L) {
   long /* minutes */j = round((e - f) * 60d / c.profitPerHour);
   i = j < 1L ? "<1m" : "~" + As.rb(j);
  }
  return new Goal(e, f, h, compact(e),
  h ? "reached" : percent(g), i, g, false);
 }
 // Owner 2026-10-01 (F10): a time-only target is still a target; the card tracks Active Time.
 Long /* timeTarget */k = b.getActiveTimeTargetMillis();
 if (k == null || k <= 0L) return null;
 double /* timeFraction */l = max(0d, min(1d, d / (double) k));
 boolean /* timeReached */m = d >= k;
 // The shared Pace answer for a time target: where the booked Net lands at the target time.
 As.Pace /* pace */n = m ? null
 : As.pace(null, k, c.net, true, c.profitPerHour, d);
 return new Goal(k, d, m, ra(k),
 m ? "reached" : percent(l),
 n == null || !n.available ? null : n.line, l, true);
}

static long akw(Bu /* metrics */a, Dy /* market */b) {
 return aha(a.revenue, b.revenue);
}

static long aky(Bu /* metrics */a, Dy /* market */b) {
 return a.costSplitAvailable ? aha(a.suppliesCosts, b.supplies) : -1L;
}

static long akx(Bu /* metrics */a, Dy /* market */b) {
 return a.costSplitAvailable ? aha(a.otherCosts, b.other) : -1L;
}

static long aku(Bu /* metrics */a, Dy /* market */b) {
 return aha(a.costs, b.costs);
}

static synchronized List<Recent> recent(Am /* engine */a, Ad /* active */b,
List<Ac> /* transactions */c, List<Row> /* marketRows */d, Map<String, Row> /* marketByPresentation */e, Dz /* ctx */f) {
 List<Object> /* key */g = Arrays.asList(a, a.getRevision(), b, d,
 f.minimumDisplayedLootValue, f.filter, f.filter == null ? "" : f.filter.version());
 if (!g.equals(recentKey)) {
  recentList = afa(Or.capture(c, d,
  b == null ? "" : b.getId(), f::td), e, f.filter);
  recentKey = g;
 }
 return recentList;
}

static List<Recent> afa(Br.Result /* projection */a,
Map<String, Row> /* marketRows */b, RecentFilter /* filter */c) {
 var /* rows */d = new ArrayList<Recent>();
 for (Group /* group */e : a.groups) {
  // Charges live in the Ledger only.
  if (e.chargeUse) continue;
  Recent /* row */f = toRecent(e, e.marketPresentationId.isEmpty() ? null : b.get(e.marketPresentationId));
  // A canceled offer that returned everything stays in the Ledger, not Recent (owner 2026-09-28).
  if (f == null || "canceled".equals(f.tag)
  || !f.open && c != null && !c.showRecent(f.name, f.quantity)) {
   continue;
  }
  d.add(f);
  if (d.size() == RECENT_ROWS) break;
 }
 return Collections.unmodifiableList(d);
}

static Recent toRecent(Group /* group */b, Row /* marketRow */c) {
 if (b == null) return null;
 boolean /* market */d = b.market;
 boolean /* ati */e = d && b.coverage == Coverage.COMPLETE;
 long /* marketResult */f = b.value;
 boolean /* amg */g = d && c != null
 && c.isRealizedIncluded() && c.settledQty > 0L && c.observedSettlementGp != 0L;
 boolean /* ape */h = c != null && "SELL".equals(c.side.name());
 boolean /* aso */i = c != null
 && c.lifecycle == Bi.Lifecycle.CANCELLED_RETURNED;
 String /* marketSide */j = c == null ? ""
 : c.isRealizedIncluded() ? h ? "Sold" : "Bought" : Dc.avj(c);
 boolean /* unpriced */k = !d && b.coverage == Coverage.INCOMPLETE;
 String /* tag */l = b.reviewRequired ? "review" : i ? "canceled"
 : d ? "market" : k ? "unpriced" : null;
 String /* quantity */m = aeb(b);
 String /* qty */n;
 String /* actionLabel */o;
 if (d) {
  // Cash movement first: the primary value is the observed signed settlement; the
  // canonical Market result is the explicit secondary line, never the primary.
  String /* detail */q = e ? (j.isEmpty() ? "Result " + signed(f)
  : j + " \u00b7 Result " + signed(f)) : g
  ? j + " \u00b7 Result \u2014" : b.qe;
  // A finished trade shows its quantity like any item ("\u00d7500"); the line keeps its side
  // and Result (owner 2026-10-08).
  n = g ? aeb(b) : null;
  o = q;
 } else if (b.actionGroup()) {
  // An exact named spell is its own title, so the verb plus the receipt count is the
  // honest second line. A generic action keeps its composition, which is the only
  // context that distinguishes it.
  // Owner 2026-09-28: a count per cast like any item ("×23"), never "23 receipts", so the
  // narrow quantity column leaves every name readable.
  String /* casts */r = b.receiptCount > 1 ? times(b.receiptCount) : null;
  n = !b.primaryName.equalsIgnoreCase(b.actionLabel) ? r
  : r == null ? b.qe : b.qe + " · " + r;
  o = b.actionLabel;
 } else if (!b.failedOf().isEmpty()) {
  n = b.failedOf();
  o = b.failedTip();
 } else {
  n = m;
  // A run whose every unit failed so far reads as failed, whichever receipt came first.
  o = b.failed > 0L ? msg("zz") : b.actionLabel;
 }
 Bs /* ledgerCostView */s = b.table == Br.Table.COSTS_SUPPLIES
 ? b.chargeUse ? Bs.CHARGES : Bs.SUPPLIES : b.table == Br.Table.COSTS_LOSS
 ? Bs.LOSS : null;
 boolean /* neutral */t = b.neutral;
 long /* value */u = t ? 0L : b.value;
 boolean /* aql */v = false;
 if (g && !t) {
  u = h ? Math.abs(c.observedSettlementGp) : -Math.abs(c.observedSettlementGp);
  v = true;
 }
 return new Recent(b.itemId, b.primaryName, n, u, t, k, l,
 b.receiptCount, b.representativeTransactionId, b.representativeContributionId,
 o, Ae.nonNeg(b.quantity), b.composite, d,
 d ? b.coverage == Coverage.COMPLETE : !k, s, b.reviewRequired,
 f, v, j, c != null && Ao.wr(c.lifecycle));
}

static String aeb(Group /* group */b) {
 String /* name */c = b.primaryName.toLowerCase(Locale.ROOT);
 String /* unit */d = Ag.has(c, "pizza", "cake", "pie") ? "portion"
 : b.actionLabel.equals("Drank") ? "dose" : null;
 if (b.normalizedConsume && d != null) {
  if (b.quantity == 1L) return "1 " + d;
  return b.quantity > 1L ? times(b.quantity) + " " + d + "s" : null;
 }
 if (b.claim < 0) {
  // A claim spend reads as its own signed count ("-1 pouch"); a pickup stays "×N".
  return "\u2212" + Math.max(1L, b.quantity);
 }
 return b.quantity > 1L ? times(b.quantity) : null;
}
}
