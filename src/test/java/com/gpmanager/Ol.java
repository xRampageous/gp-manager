package com.gpmanager;
import com.gpmanager.Bp.Ax;
import com.gpmanager.Bi.*;
import com.gpmanager.Br.*;
import com.gpmanager.Br.Table;
import java.time.*;
import java.util.*;
import net.runelite.api.gameval.ItemID;
import lombok.*;
import static com.gpmanager.Bi.Lifecycle.*;
import static java.util.Locale.*;
import static java.util.Collections.*;
import static com.gpmanager.Ae.*;
import static com.gpmanager.Ak.msg;
import com.gpmanager.Ao.*;
import com.gpmanager.Ao.Eb;
class Ol {

static List<Card> cards(Group /* group */a) {
 if (a == null) return emptyList();
 var /* alf */b = new LinkedHashMap<String, ReceiptCard>();
 for (Receipt /* receipt */c : a.receipts) {
  b.computeIfAbsent(c.transactionId, /* ignored */d -> new ReceiptCard(c)).add(c);
 }
 var /* cards */e = new ArrayList<Card>(b.size());
 for (ReceiptCard /* card */f : b.values()) e.add(f.card());
 e.sort(Comparator.comparingLong((Card /* card */g) -> g.at).reversed().thenComparing(/* card */h -> h.transactionId));
 return e;
}

static Ao capture(Am /* engine */a, long /* now */b, Entry /* entry */c) {
 Entry e = c == null ? Entry.current() : c;
 List<Ad> /* sessions */d = aig(a, e, b);
 String /* scopeName */f = scopeName(a, e);
 var /* sessionIds */g = new LinkedHashSet<String>();
 var /* transactions */h = new ArrayList<Ac>();
 boolean /* compacted */i = false;
 long /* aqg */j = 0L;
 for (Ad /* session */k : d) {
  if (k == null) continue;
  g.add(k.getId());
  Ad.CompactedContribution /* compact */l = k.pl();
  j = safeAdd(j, l.getNet());
  for (Ac /* transaction */m : k.getTransactions()) {
   if (m != null) {
    h.add(m);
    Ax /* amounts */n = Bp.transaction(m);
    if (n.included) j = safeAdd(j, n.getNet());
   }
  }
  i |= l.rows > 0 || l.revenue != 0L || l.costs != 0L;
 }
 List<Row> /* marketRows */o = a.ub();
 var /* marketByPresentation */p = new HashMap<String, Row>();
 for (Row /* market */q : o) {
  if (q != null && g.contains(q.scopeSessionId)) {
   p.put(q.presentationId, q);
  }
 }
 Result /* projection */r = Or.capture(h, o, g, null);
 var /* tables */s = new EnumMap<Table, List<Group>>(Table.class);
 for (Table /* table */t : Table.values()) s.put(t, new ArrayList<>());
 for (Group /* group */u : r.groups) {
  if (!u.reviewRequired && u.matchesText(e.search)) s.get(u.table).add(u);
 }
 List<Group> /* aog */v = s.get(Table.GAINS);
 List<Group> /* aqt */w = s.get(Table.COSTS_SUPPLIES);
 List<Group> /* asn */x = s.get(Table.COSTS_LOSS);
 List<Group> /* apd */y = s.get(Table.MARKET);
 var /* supplies */z = new ArrayList<Group>();
 var /* charges */aa = new ArrayList<Group>();
 for (Group /* group */ab : w) (ab.chargeUse ? aa : z).add(ab);
 var /* ant */ac = new ArrayList<Group>(e.costView == Bs.LOSS ? x
 : e.costView == Bs.CHARGES ? aa : z);
 if (e.costView == Bs.ALL) {
  // All is every list together, largest cost first.
  ac.addAll(aa);
  ac.addAll(x);
  ac.sort(Comparator.comparingLong(/* group */ad -> ad.value));
 }
 Eb /* gains */ae = avm(v, false);
 Eb /* costs */af = avm(ac, false);
 Eb /* market */ag = avm(y, true);
 var /* financial */ah = new ArrayList<Group>();
 ah.addAll(v);
 ah.addAll(w);
 ah.addAll(x);
 ah.addAll(y);
 Summary /* total */ai = Or.summarize(ah);
 // ── audit ────────────────────────────────────────────────────────────────────────────
 var /* reviewRows */aj = new ArrayList<Cu>();
 int /* aqb */ak = 0;
 for (Cu /* ave */al : a.getReviewRows(b)) {
  if (al != null && g.contains(al.sessionId)) {
   ak++;
   if (!readOnly(e) && aaf(al, e.search)) aj.add(al);
  }
 }
 var /* review */am = new ReviewData(aj, ak);
 var /* alo */an = new ArrayList<Receipt>();
 for (Group /* group */ao : r.groups) {
  for (Receipt /* receipt */ap : ao.receipts) {
   if ((ap.corrected || ap.split) && ap.matchesSearch(e.search)) an.add(ap);
  }
 }
 an.sort(Comparator.comparingLong((Receipt /* receipt */aq) -> aq.at)
 .reversed().thenComparing(/* receipt */ar -> ar.contributionId));
 // ── selected detail ──────────────────────────────────────────────────────────────────
 Detail /* detail */as = null;
 String /* zm */au = e.highlightContributionId;
 String /* ady */av = e.highlightTransactionId;
 String /* anj */aw = e.highlightGroupId;
 if (aw != null || au != null || av != null) {
  Group /* group */ax = auj(r.groups, aw, au, av);
  if (ax != null) {
   List<Receipt> /* receipts */ay = ax.receipts;
   Receipt /* exact */az = null;
   if (au != null) {
    for (Receipt /* receipt */ba : ay) {
     if (ba.contributionId.equals(au)) {
      az = ba;
      break;
     }
    }
   }
   if (az == null && av != null) {
    az = tb(ay, av);
   }
   as = new Detail(ax, ay, az);
  }
 }
 long /* net */bb = j;
 boolean /* detailedHistoryAvailable */bc = !i || ai.entries > 0;
 boolean /* reconciled */bd = ai.coverageComplete && safeAdd(safeAdd(ai.gains, ai.costs), ai.market) == bb;
 return new Ao(e, f, readOnly(e), i, bc, bb, a.getRevision(), b,
 ae, af, ag, am, an, ai, bd, as,
 p, new int[] {0, z.size(), x.size(), aa.size()}, pendingItems(a, e),
 deaths(h, e.search));
}

static List<Death> deaths(List<Ac> /* transactions */a, String /* search */b) {
 var /* ordered */c = new ArrayList<Ac>(a);
 c.sort(Comparator.comparingLong((/* itemData */d -> d.timestampEpochMillis)));
 var /* deaths */e = new ArrayList<Death>();
 int /* pvm */f = -1;
 for (Ac /* transaction */g : c) {
  Ax /* amounts */h = Bp.transaction(g);
  long /* value */i = h.included ? h.getNet() : 0L;
  Ai /* type */j = g.tm();
  if (j == Ai.PK_DEATH_LOSS) {
   e.add(new Death(g.getId(), "Death · PvP", g.timestampEpochMillis, i,
   new ArrayList<>(g.getFlows()), emptyList(), 0L));
  } else if (j == Ai.TRANSFER && g.getNote().startsWith("Death:")) {
   f = e.size();
   e.add(new Death(g.getId(), "Death · PvM", g.timestampEpochMillis, 0L,
   emptyList(), new ArrayList<>(g.getFlows()), 0L));
  } else if (j == Ai.CONSUMPTION && "Death reclaim".equals(g.getActivityName())) {
   if (f < 0) {
    f = e.size();
    e.add(new Death(g.getId(), "Death · PvM", g.timestampEpochMillis, 0L,
    emptyList(), emptyList(), 0L));
   }
   Death /* death */k = e.get(f);
   e.set(f, new Death(k.transactionId, k.place, k.at,
   safeAdd(k.value, i), k.lost, k.kept, safeAdd(k.fees, -i)));
  }
 }
 var /* shown */l = new ArrayList<Death>();
 for (Death /* death */m : e) {
  if (b == null || b.isEmpty() || m.place.toLowerCase(ROOT).contains(b.toLowerCase(ROOT))) {
   l.add(m);
  }
 }
 l.sort(Comparator.comparingLong((Death /* death */n) -> n.at).reversed());
 return l;
}

static List<PendingItem> pendingItems(Am /* engine */a, Entry e) {
 var /* items */b = new ArrayList<PendingItem>();
 if (e.scope != Scope.CURRENT_GRIND || readOnly(e)) return b;
 for (SavedState.By /* claim */c : a.getPendingClaims()) {
  if (c == null || !c.isValid()) continue;
  KeyChestCatalogue.Entry /* chest */d = KeyChestCatalogue.rq(c.itemOrKeyId);
  String /* name */f = d != null ? d.getChestName() + " key" : c.itemOrKeyId >= ItemID.WILDY_LOOT_KEY0
  && c.itemOrKeyId <= ItemID.WILDY_LOOT_KEY4 ? "Loot key" : "Key or chest";
  b.add(new PendingItem(c.itemOrKeyId, f, c.getQuantity(),
  f + msg("kq")));
 }
 DeathReclaimStatus /* reclaim */g = a.tt();
 if (g.awaiting) {
  b.add(new PendingItem(0, "Death reclaim", Math.max(1L, g.outstandingItemCount), msg("ha")));
 }
 return b;
}

static Eb avm(List<Group> /* groups */a, boolean /* market */b) {
 long /* total */c = 0L;
 boolean /* incomplete */d = false;
 var /* receipts */e = new LinkedHashSet<String>();
 for (Group /* group */f : a) {
  d |= f.incomplete();
  if (!b || !f.incomplete()) c = safeAdd(c, f.value);
  e.addAll(f.receiptIds);
 }
 return new Eb(a, c, a.size(), e.size(), d);
}

static boolean wr(Lifecycle /* lifecycle */a) {
 return a == PENDING || a == PARTIALLY_EXECUTED || a == EXECUTED_UNSETTLED
 || a == PARTIALLY_REALIZED || a == RESUMED;
}

static Group auj(List<Group> /* groups */a, String /* groupId */b, String /* contributionId */c, String /* transactionId */d) {
 for (Group /* group */e : a) {
  if (e.reviewRequired) continue;
  if (e.containsContribution(b) || e.containsContribution(c)
  || e.containsTransaction(d)) {
   return e;
  }
 }
 // Review-required groups are still openable from the audit list detail.
 for (Group /* group */f : a) {
  if (f.containsContribution(c) || f.containsTransaction(d)) return f;
 }
 return null;
}

static Receipt tb(List<Receipt> /* receipts */a, String /* transactionId */b) {
 Receipt /* found */c = null;
 for (Receipt /* receipt */d : a) {
  if (d.transactionId.equals(b) && (c == null || d.at >= c.at)) {
   c = d;
  }
 }
 return c;
}

static List<Ad> aig(Am /* engine */a, Entry /* entry */b, long /* now */c) {
 var /* sessions */d = new ArrayList<Ad>();
 if (b.scope == Scope.HISTORY) {
  Ad /* historical */e = b.historySessionId == null ? null : a.ua(b.historySessionId);
  if (e != null) d.add(e);
  return d;
 }
 Ad /* active */f = a.getActiveSession();
 if (f != null) d.add(f);
 if (b.scope == Scope.TODAY) {
  ZoneId /* zone */g = a.uf();
  LocalDate /* today */h = Instant.ofEpochMilli(c).atZone(g).toLocalDate();
  for (Ad /* historical */i : a.getHistory()) {
   if (i == null || (f != null && i.getId().equals(f.getId()))) continue;
   LocalDate /* day */j = Instant.ofEpochMilli(i.startedAtEpochMillis).atZone(g).toLocalDate();
   if (j.equals(h)) d.add(i);
  }
 }
 return d;
}

static String scopeName(Am /* engine */a, Entry /* entry */b) {
 if (b.scope == Scope.HISTORY) {
  if (b.historyName != null && !b.historyName.isEmpty()) return b.historyName;
  Ad /* historical */c = b.historySessionId == null ? null : a.ua(b.historySessionId);
  return c == null ? "Recent Grind" : c.getName();
 }
 return b.scope == Scope.TODAY ? "Runs today" : "Current Grind";
}

static boolean readOnly(Entry /* entry */a) {
 return a.scope != Scope.CURRENT_GRIND;
}

static boolean aaf(Cu /* row */a, String /* query */b) {
 if (Ag.blank(b)) return true;
 String /* needle */c = Ag.lower(b);
 if (a.why != null && a.why.toLowerCase(ROOT).contains(c)) return true;
 for (Cu.Item /* item */d : a.items) {
  if (d.name != null && d.name.toLowerCase(ROOT).contains(c)) return true;
 }
 return false;
}

static Ef splitPreview(Am /* engine */a, String /* transactionId */b, int /* itemId */c, long /* keep */d, long /* now */e) {
 synchronized (a) {
  long /* currentNet */f = a.getMetrics(e).net;
  Ad /* session */g = a.getActiveSession();
  Ac /* transaction */h = g == null ? null : g.sw(b);
  if (h == null) return new Ef(f, f, a.getRevision());
  var /* gson */i = JsonCodec.gson();
  Ac /* copy */j = i.fromJson(i.toJson(h), Ac.class);
  j.afg(c, true, j.quantity(c, true) - d);
  long /* before */k = h.isCounted() ? h.getNet() : 0L;
  long /* after */l = j.isCounted() ? j.getNet() : 0L;
  return new Ef(f, f - k + l, a.getRevision());
 }
}

static Ef preview(Am /* engine */a, String /* transactionId */b, Ah /* correction */c, long /* now */d) {
 Ad /* session */e = a.getActiveSession();
 long /* currentNet */f = a.getMetrics(d).net;
 if (e == null || b == null || c == null) {
  return new Ef(f, f, a.getRevision());
 }
 Ac /* transaction */g = e.sw(b);
 if (g == null) return new Ef(f, f, a.getRevision());
 long /* before */h = g.isCounted() ? g.getNet() : 0L;
 long /* after */i = g.awp(c);
 return new Ef(f, f - h + i, a.getRevision());
}
}
