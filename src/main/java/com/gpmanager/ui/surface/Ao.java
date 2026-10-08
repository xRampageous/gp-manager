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
/**
* The stacked-table Ledger read model: three independently paginated financial tables (Gains,
* Costs with a local Loss/Supplies selector, Market) built from the shared semantic projection,
* plus compact Review/Corrected audit lists and a reconciled Total block.
*
* <p>Search runs against COMPLETE semantic groups: a group that owns a matching underlying
* resource or receipt is kept whole, so a header total never becomes a partial child total.
* Ah targets are always exact canonical contribution receipts, never a group.</p>
*/
@AllArgsConstructor
class Ao {
enum Scope {
 CURRENT_GRIND("Current Grind"), TODAY("Runs today"), HISTORY("Recent Grind");
 String label;
 Scope(String /* label */b) { this.label = b; }
 public String toString() { return label; }
}

/** The Losses table's chips (owner 2026-09-28): LOSS is shown as Items; Charges are measured charge use. */
enum Bs {
 ALL("All"), SUPPLIES("Supplies"), LOSS("Items"), CHARGES("Charges");
 String label;
 Bs(String /* label */b) { this.label = b; }
 public String toString() { return label; }
}

/** A not-yet-settled count in Pending: an unopened loot key or chest, or a death reclaim. */
@AllArgsConstructor
static class PendingItem {
 int itemId;
 String name;
 long quantity;
 String tip;
}

/** One death in scope: what dying cost, opened as its receipt. */
@AllArgsConstructor
static class Death {
 String transactionId;
 String place;
 long at;
 long value;
 /** Items the death cost, from the death-loss receipt. */
 List<Ab> lost;
 /** Items held at the grave or retrieval service: ownership-neutral, not counted. */
 List<Ab> kept;
 /** Reclaim and retrieval fees booked for this death. */
 long fees;
}

/** Compact audit subsections; never peer financial tabs. */
enum Audit {
 REVIEW("Review"), CORRECTED("Corrected");
 String label;
 Audit(String /* label */b) { this.label = b; }
 public String toString() { return label; }
}

/** Raw Market settlement row for a semantic group's presentation id, or null. */
Row zu(String /* presentationId */a) {
 return a == null || a.isEmpty() ? null : marketRows.get(a);
}

/** Presentation-only Ledger query state; it never enters SavedState. */
static class Entry {
 Scope scope;
 public String historySessionId;
 public String historyName;
 Bs costView;
 String search;
 public String highlightTransactionId;
 public String highlightContributionId;
 public String highlightGroupId;
 /** Deep-link request to expand one audit subsection. */
 public Audit focusAudit;
 Entry(Scope /* scope */b, String /* historySessionId */c, String /* historyName */d) {
  this(b, c, d, Bs.ALL, "", null, null, null, null);
 }
 Entry(Scope /* scope */b, String /* historySessionId */c, String /* historyName */d,
 Bs /* costView */f, String /* search */g, String /* highlightTransactionId */h,
 String /* highlightContributionId */i, String /* highlightGroupId */j, Audit /* focusAudit */k) {
  this.scope = b == null ? Scope.CURRENT_GRIND : b;
  this.historySessionId = c;
  this.historyName = d;
  this.costView = f == null ? Bs.ALL : f;
  this.search = Ag.axw(g);
  this.highlightTransactionId = h;
  this.highlightContributionId = i;
  this.highlightGroupId = j;
  this.focusAudit = k;
 }
 static Entry current() {
  return new Entry(Scope.CURRENT_GRIND, null, null);
 }
 static Entry current(Audit /* audit */b, String /* transactionId */c) {
  return new Entry(Scope.CURRENT_GRIND, null, null, Bs.ALL, "", c, null, null, b);
 }
 Entry withCostView(Bs /* next */b) {
  return new Entry(scope, historySessionId, historyName, b, search, null, null, null, null);
 }
 Entry withSearch(String /* next */b) {
  return new Entry(scope, historySessionId, historyName, costView, b, null, null, null, null);
 }
 Entry withScope(Scope /* next */b, String /* sessionId */c, String /* name */d) {
  return new Entry(b, c, d, costView, search, null, null, null, null);
 }
 /** Selection stores only stable identity. */
 Entry withSelection(String /* transactionId */b, String /* contributionId */c, String /* groupId */d) {
  return new Entry(scope, historySessionId, historyName, costView, search, b,
  c, d, focusAudit);
 }
}

/** One table's full filtered set and its header facts; the page table pages it. */
@AllArgsConstructor
static class Eb {
 List<Group> groups;
 long total;
 int entries;
 int receipts;
 boolean incomplete;
}

/** Review audit: exact review rows with the full eligible bulk count. */
@AllArgsConstructor
static class ReviewData {
 List<Cu> rows;
 int scopeCount;
}

/** The open detail: one semantic group, its exact receipts, and any selected exact receipt. */
@AllArgsConstructor
static class Detail {
 Group group;
 List<Receipt> receipts;
 public Receipt exact;
}

/** One resource inside a receipt: "Chaos rune ×4" on its line. */
@AllArgsConstructor
static class ResourceSummary {
 int itemId;
 String itemName;
 String usedBy;
 long quantity;
 long value;
 /** Sum of only the known children; shown with explicit wording when incomplete. */
 long knownSubtotal;
 boolean incomplete;
}

/** One receipt row: exactly one canonical transaction inside the selected group. */
@AllArgsConstructor
static class Card {
 String transactionId;
 long at;
 long value;
 long knownSubtotal;
 boolean incomplete;
 /** Representative contribution id for selecting the receipt. */
 String contributionId;
 List<ResourceSummary> resources;
 /** "Chaos rune ×4 · Death rune ×2" for the row's tooltip. */
 String summaryText() {
  var /* text */b = new StringBuilder();
  for (ResourceSummary /* resource */c : resources) {
   if (b.length() > 0) b.append(" \u00b7 ");
   b.append(c.itemName);
   if (c.quantity > 1L) b.append(' ').append(Fmt.times(c.quantity));
  }
  return b.toString();
 }
}

/** Additive facts shared by a receipt card and each resource inside it. */
@RequiredArgsConstructor
static class ReceiptTotal {
 final Receipt head;
 long quantity;
 long value;
 long known;
 long at;
 boolean incomplete;
 String representative = "";
 void add(Receipt /* receipt */b) {
  quantity += b.quantity;
  value += b.value;
  at = Math.max(at, b.at);
  incomplete |= b.unpriced;
  if (!b.unpriced) known += b.value;
  if (representative.isEmpty()) representative = b.contributionId;
 }
}

static class ReceiptCard extends ReceiptTotal {
 Map<String, ReceiptTotal> resources = new LinkedHashMap<>();
 ReceiptCard(Receipt /* head */b) {
  super(b);
 }
 void add(Receipt /* receipt */b) {
  super.add(b);
  resources.computeIfAbsent(b.itemId + "|" + b.usedBy, /* ignored */c -> new ReceiptTotal(b)).add(b);
 }
 Card card() {
  var /* summaries */b = new ArrayList<ResourceSummary>();
  for (ReceiptTotal /* resource */c : resources.values()) {
   Receipt r = c.head;
   b.add(new ResourceSummary(r.itemId, r.itemName, r.usedBy, c.quantity,
   c.value, c.known, c.incomplete));
  }
  b.sort(Comparator.comparing((ResourceSummary /* summary */d) -> d.itemName, String.CASE_INSENSITIVE_ORDER)
  .thenComparingInt(/* summary */f -> f.itemId));
  return new Card(head.transactionId, at, value, known, incomplete, representative, b);
 }
}

/** One pass builds each transaction card and its resources; newest cards first. */
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

/** The query this data answers: scope, Costs view, search and the selected receipt. */
Entry entry;
String scopeName;
boolean readOnly;
boolean compacted;
boolean detailedHistoryAvailable;
long net;
long revision;
/** Capture time for honest ages without leaking the engine. */
long capturedAt;
Eb gains;
Eb costs;
Eb market;
ReviewData review;
List<Receipt> corrected;
Summary total;
boolean reconciled;
public Detail detail;
/** Raw Market settlement rows by presentation id; presentation-only lookup for value semantics. */
Map<String, Row> marketRows;
/** Group counts for the Losses chips by {@link Bs} ordinal, whatever view is selected. */
int[] costCounts;
/** Keys and reclaim waiting to settle; current scope only, counts and items, never GP. */
List<PendingItem> pendingItems;
/** Deaths in scope, newest first; their losses also stay in Costs · Loss. */
List<Death> deaths;
static Ao capture(Am /* engine */a, long /* now */b, Entry /* entry */c) {
 Entry e = c == null ? Entry.current() : c;
 List<Ad> /* sessions */d = aig(a, e, b);
 String /* scopeName */f = scopeName(a, e);
 Sm v0 = a.summary();
 Sm.S w0 = v0.scope(d);
 Set<String> g = w0.ids;
 List<Ac> h = w0.t;
 boolean i = w0.cut;
 long j = w0.net;
 List<Row> o = v0.m;
 Map<String, Row> p = w0.markets;
 Result r = w0.groups;
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
 Summary /* total */ai = Br.summarize(ah);
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

/**
* PvP deaths are their death-loss receipts. A PvM death is its gravestone wipe (a neutral
* transfer) plus the reclaim fees that follow it; a fee with no kept wipe row stands alone.
*/
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

/** Ordinary tables sum every group; Market's header includes only complete results. */
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

/** True when a Market lifecycle is genuinely pending rather than already realized. */
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

// ── correction support (unchanged semantics: exact canonical ids only) ─────────────────────
static class Ef {
 long currentNet;
 long afterNet;
 long change;
 long revision;
 Ef(long /* currentNet */b, long /* afterNet */c, long /* revision */d) {
  this.currentNet = b;
  this.afterNet = c;
  this.change = c - b;
  this.revision = d;
 }
 String note() { return change == 0L ? msg("fk") : "Net changes by " + Fmt.signed(change) + " gp."; }
}

/**
* Net if a split keeps {@code keep} of {@code itemId}: the split itself runs on a detached copy of
* the receipt, so the preview and the applied split cannot differ.
*/
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
