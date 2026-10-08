package com.gpmanager;
import com.gpmanager.Bi.*;
import com.gpmanager.Af.Category;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import lombok.*;
import static com.gpmanager.Ag.*;
import static java.util.Locale.*;
import static java.util.Collections.*;
import static com.gpmanager.Ae.*;
import static com.gpmanager.Ah.*;
import static com.gpmanager.Ai.*;
import static com.gpmanager.Ak.msg;
/**
* The one derived, read-only semantic financial projection every presentation surface shares.
*
* <p>It turns canonical transactions and the derived Market settlement context into living
* semantic groups: repeated compatible activity updates one group, totals stay additive, and no
* group is ever a correction identity. It never books, never reprices and never persists.</p>
*
* <p>Grouping laws:</p>
* <ul>
*   <li>Exact named {@code Cast} groups key on the proven spell name; different names never
*       merge and exact never merges with generic.</li>
*   <li>Generic action groups key on a canonical input-composition signature (action kind,
*       financial category, sorted resource item ids, a scale-normalised quantity ratio where it
*       is safely derivable, and price/coverage compatibility). The signature is presentation
*       grouping only and is never rendered as a spell name; ambiguous composition splits
*       instead of over-merging.</li>
*   <li>Measured charge receipts become their actual booked resource rows with a
*       {@code Used by <equipment>} context. The equipment is never a financial row and no
*       component ever carries the whole parent Net.</li>
*   <li>Action composites require pure counted cost contributions of one financial category:
*       no Gain, Market, Transfer, Review, Corrected, split or claim-linked row may collapse
*       into one.</li>
*   <li>Unpriced/incomplete groups stay in their financial table with an incomplete marker;
*       only transactions that independently need an owner decision are review-required.</li>
*   <li>Sort truth is {@code latestActivityAt DESC} with a stable semantic-group-id tie-break.</li>
* </ul>
*/
class Br {
/** Financial table destination for the future stacked Ledger and the Live consumer. */
enum Table {
 GAINS, COSTS_SUPPLIES, COSTS_LOSS, MARKET, AUDIT
}

/** Value coverage: an incomplete group never presents itself as an exact headline. */
enum Coverage {
 COMPLETE, INCOMPLETE
}

/**
* One exact canonical contribution receipt behind a group; the only correction target layer.
* Built once by the projection and shared by the Ledger tables, the receipt list and the
* Corrected audit.
*/
@AllArgsConstructor
static class Receipt {
 String contributionId;
 String transactionId;
 long at;
 int itemId;
 String itemName;
 /** Semantic magnitude where known; for measured charge spends this is the normalized quantity. */
 long quantity;
 /** Effective, correction-aware additive value; 0 when the receipt is neutral or unpriced. */
 long value;
 /** Raw automatic value, retained for audit and correction comparison. */
 long automaticValue;
 boolean unpriced;
 boolean neutral;
 boolean review;
 boolean corrected;
 boolean split;
 Category category;
 String verb;
 String actionDisplayName;
 String why;
 String qj;
 String source;
 /** Measured-charge equipment context ("Toxic blowpipe"), or empty for ordinary resources. */
 String usedBy;
 /** Charges: the exact captured unit price and its source; empty for other receipts (F11). */
 String priceBasis;
 public Row marketSettlement;
 boolean gain() {
  return category == Category.GAIN;
 }
 /** The Corrected audit search; financial table search uses the projection group instead. */
 boolean matchesSearch(String /* needle */c) {
  if (empty(c)) return true;
  String /* lower */d = c.toLowerCase(ROOT);
  for (String /* term */e : new String[] {itemName, verb, actionDisplayName, source, why, qj, transactionId, contributionId}) {
   if (e.toLowerCase(ROOT).contains(d)) return true;
  }
  return false;
 }
}

/**
* One semantic presentation group; never a correction identity and never persisted. Each unit
* of activity starts as a group; units sharing a merge key agree on everything the key encodes
* (table, flags, consume shape), so the newest keeps those and later ones only add value, time,
* quantity, receipts and search terms.
*/
static class Group {
 String semanticGroupId;
 /** Display item id for the row sprite; -1 for action composites. */
 int itemId;
 Table table;
 String primaryName;
 /** The honest secondary line: "Used by Toxic blowpipe", "3 rune types", "Sell". */
 String qe;
 String actionLabel;
 /** Measured-charge equipment context ("Toxic blowpipe"), or empty for ordinary rows. */
 String usedBy;
 /** Measured or estimated charge use: the Losses Charges chip, never a Supplies row. */
 boolean chargeUse;
 long latestActivityAt;
 long value;
 Coverage coverage;
 /** Semantic quantity where honestly known; -1 when it cannot be stated. */
 long quantity;
 /** +1 claim pickup, -1 claim spend, 0 for an ordinary resource row. */
 int claim;
 boolean normalizedConsume;
 int receiptCount = 1;
 boolean composite;
 boolean neutral;
 boolean market;
 boolean reviewRequired;
 boolean corrected;
 String marketPresentationId;
 String representativeTransactionId;
 String representativeContributionId;
 public Set<String> receiptIds = new LinkedHashSet<>();
 public List<Receipt> receipts = new ArrayList<>();
 public Set<String> searchTerms = new LinkedHashSet<>();
 void search(String... /* terms */c) {
  Collections.addAll(searchTerms, c);
 }
 int units = 1;
 /** Units of a production run's input its failures lost (a failed iron smelt's ore). */
 long failed;
 Group(String /* mergeKey */c, int /* itemId */d, Table /* table */e, String /* primaryName */f, String /* qe */g, String /* actionLabel */h,
 String /* usedBy */i, boolean /* chargeUse */j, long /* at */k, long /* value */l, Coverage /* coverage */m, long /* quantity */n, boolean /* normalizedConsume */o,
 boolean /* composite */q, boolean /* neutral */r, boolean /* market */t, boolean /* reviewRequired */u, boolean /* corrected */v,
 String /* marketPresentationId */w, String /* transactionId */x, String /* representativeContributionId */y, int /* claim */z) {
  if (GROUP_IDS.size() > 50_000) GROUP_IDS.clear();
  this.semanticGroupId = GROUP_IDS.computeIfAbsent(c,
  /* key */aa -> "sg:" + UUID.nameUUIDFromBytes(aa.getBytes(StandardCharsets.UTF_8)));
  this.itemId = d;
  this.table = e;
  this.primaryName = f;
  this.qe = g;
  this.actionLabel = h;
  this.usedBy = i;
  this.chargeUse = j;
  this.latestActivityAt = k;
  this.value = l;
  this.coverage = m;
  this.quantity = n;
  this.normalizedConsume = o;
  this.composite = q;
  this.neutral = r;
  this.market = t;
  this.reviewRequired = u;
  this.corrected = v;
  this.marketPresentationId = w;
  this.representativeTransactionId = x;
  this.representativeContributionId = y;
  this.claim = z;
  receiptIds.add(x.isEmpty() ? y : x);
  receiptIds.remove("");
 }
 /** Adds a later unit with the same merge key. */
 Group absorb(Group /* unit */c) {
  value = safeAdd(value, c.value);
  latestActivityAt = Math.max(latestActivityAt, c.latestActivityAt);
  coverage = c.coverage == Coverage.INCOMPLETE ? Coverage.INCOMPLETE : coverage;
  quantity = quantity < 0L || c.quantity < 0L ? -1L : safeAdd(quantity, c.quantity);
  units++;
  receiptIds.addAll(c.receiptIds);
  receiptCount = receiptIds.isEmpty() ? units : receiptIds.size();
  receipts.addAll(c.receipts);
  searchTerms.addAll(c.searchTerms);
  failed += c.failed;
  return this;
 }
 /** Owner 1.1: "16/28" when 16 of a run's 28 units failed; "" when none or all did. */
 String failedOf() {
  return failed > 0L && failed < quantity ? failed + "/" + quantity : "";
 }
 String failedTip() {
  return failed + " of " + quantity + " failed";
 }
 boolean incomplete() {
  return coverage == Coverage.INCOMPLETE;
 }
 /** True when this group owns the given semantic group or contribution identity. */
 boolean containsContribution(String /* id */c) {
  if (empty(c)) return false;
  if (semanticGroupId.equals(c)) return true;
  for (Receipt /* receipt */d : receipts) {
   if (c.equals(d.contributionId)) return true;
  }
  return false;
 }
 /** True when this group represents the given canonical transaction identity. */
 boolean containsTransaction(String /* id */c) {
  if (empty(c)) return false;
  if (c.equals(representativeTransactionId)) return true;
  if (receiptIds.contains(c)) return true;
  for (Receipt /* receipt */d : receipts) {
   if (c.equals(d.transactionId)) return true;
  }
  return false;
 }
 /** True when this group is a composite row (action or note), not a resource row. */
 boolean actionGroup() {
  return composite && !market;
 }
 /**
 * Prepared for PRE-R5C.2B: text search decides whether a COMPLETE group matches by
 * looking at group metadata and underlying resources; a match keeps the full total.
 */
 boolean matchesText(String /* query */c) {
  if (c == null) return true;
  String /* needle */d = Ag.lower(c);
  if (d.isEmpty()) return true;
  for (String /* term */e : searchTerms) {
   if (e != null && e.toLowerCase(ROOT).contains(d)) return true;
  }
  return false;
 }
}

/** The full derived read: the groups, newest first. */
static class Result {
 List<Group> groups;
 Result(List<Group> /* groups */c) {
  this.groups = unmodifiableList(c);
 }
}

/**
* Reconciled financial counts over one group set. `entries` counts financial groups across
* Gains + Costs + Market (review-required and Audit rows excluded); `receipts` counts the
* unique counted canonical receipt identities those entries represent (neutral transfers,
* pending/unrealised Market and Review-only receipts are excluded).
*/
@AllArgsConstructor
static class Summary {
 /** Sum of financial Gain groups (review-required rows excluded). */
 long gains;
 /** Sum of financial cost groups, negative (review-required rows excluded). */
 long costs;
 /** Sum of realised Market results (review-required and unrealised rows excluded). */
 long market;
 int entries;
 int receipts;
 /** True when every counted financial group had complete value coverage. */
 boolean coverageComplete;
 /** Canonical Net this summary accounts for when coverage is complete. */
 long net() {
  return safeAdd(safeAdd(gains, costs), market);
 }
}

/** Stable group ids by merge key; hashing is paid once per key, not on every tick. */
static final Map<String, String> GROUP_IDS = new java.util.concurrent.ConcurrentHashMap<>();
/** Pure capture over explicit data; used by Live, tests, previews and the Ledger. */
static Result capture(List<Ac> /* transactions */a, List<Row> /* marketRows */b, String /* sessionId */c,
Predicate<Ab> /* td */d) {
 Set<String> /* sessions */e = c == null || c.isEmpty() ? emptySet() : singleton(c);
 return capture(a, b, e, d);
}

/** Multi-session scope: Today and future period consumers merge compatible activity. */
static Result capture(List<Ac> /* transactions */b, List<Row> /* marketRows */c, Set<String> /* sessionIds */d,
Predicate<Ab> /* td */e) {
 Predicate<Ab> /* visibility */f = e == null ? /* flow */g -> true : e;
 var /* units */h = new ArrayList<Group>();
 var /* apz */i = new LinkedHashSet<String>();
 var /* ajs */j = new LinkedHashMap<String, Ac>();
 if (b != null) {
  for (Ac /* transaction */k : b) {
   if (k != null) j.put(k.getId(), k);
  }
 }
 if (c != null) {
  for (Row /* market */l : c) {
   if (l == null || d == null || !d.contains(l.scopeSessionId)) continue;
   if (!l.settlementId.isEmpty()) i.add(l.settlementId);
   h.add(aap(l, l.settlementId.isEmpty() ? null : j.get(l.settlementId)));
  }
 }
 if (b != null) {
  for (Ac /* transaction */m : b) {
   // An uncounted trade is custody's audit copy: its market row already shows the sale
   // (owner 2026-09-28: a second fill read "Bones ×2 · 0").
   if (m == null || i.contains(m.getId())
   || m.getType() == Ai.TRADE && !m.isCounted()) {
    continue;
   }
   h.addAll(akl(m, f));
  }
 }
 h.sort(Comparator.comparingLong((Group /* unit */n) -> n.latestActivityAt).reversed()
 .thenComparing(/* unit */o -> o.representativeContributionId));
 var /* aro */p = new LinkedHashMap<String, Group>();
 for (Group /* unit */q : h) p.merge(q.semanticGroupId, q, Group::absorb);
 var /* groups */r = new ArrayList<Group>(p.values());
 r.sort(Comparator.comparingLong((Group /* group */t) -> t.latestActivityAt).reversed()
 .thenComparing(/* group */u -> u.semanticGroupId));
 return new Result(r);
}

/** Reconciled counts over any group set (the search-filtered Ledger tables reuse this). */
static Summary summarize(Collection<Group> /* groups */b) {
 long /* gains */c = 0L;
 long /* costs */d = 0L;
 long /* amh */e = 0L;
 int /* entries */f = 0;
 boolean /* complete */g = true;
 var /* receipts */h = new LinkedHashSet<String>();
 if (b == null) return new Summary(0L, 0L, 0L, 0, 0, true);
 for (Group /* group */i : b) {
  if (i.reviewRequired || i.neutral || i.table == Table.AUDIT) continue;
  if (i.table == Table.MARKET) {
   if (i.coverage != Coverage.COMPLETE) {
    // Pending/unrealised Market is context, not a counted financial entry.
    g = false;
    continue;
   }
   e = safeAdd(e, i.value);
  } else if (i.table == Table.GAINS) {
   c = safeAdd(c, i.value);
  } else if (i.table == Table.COSTS_SUPPLIES || i.table == Table.COSTS_LOSS) {
   d = safeAdd(d, i.value);
  } else {
   continue;
  }
  if (i.coverage == Coverage.INCOMPLETE) g = false;
  if (!i.corrected) {
   f++;
   h.addAll(i.receiptIds);
  }
 }
 return new Summary(c, d, e, f, h.size(), g);
}

// ── unit construction ──────────────────────────────────────────────────────────────────────
static List<Group> akl(Ac /* transaction */b, Predicate<Ab> /* td */c) {
 List<Ab> /* flows */d = b.getFlows();
 boolean /* neutral */e = b.getType() == Ai.TRANSFER
 || b.getCorrection() == Ah.TRANSFER;
 boolean /* review */f = Eh.aal(b);
 boolean /* corrected */g = b.getCorrection() != AUTO;
 boolean /* split */h = Af.isSplit(b);
 boolean /* alj */i = !b.uo().isEmpty()
 || b.getActionKind() == Au.DEFERRED_CLAIM;
 long /* at */j = b.timestampEpochMillis;
 String /* source */k = Af.axb(b);
 if (d.isEmpty()) return auv(b, e, f, g, j, k);
 var /* projected */l = Af.project(b);
 // An opened claim's proceeds are the action's income: a loot minimum never hides them.
 boolean /* claimSpend */m = false;
 for (Af /* contribution */n : l) {
  m |= n.priceSource == Av.DEFERRED_CLAIM && n.quantityDelta < 0L;
 }
 var /* visible */o = new ArrayList<Af>();
 for (Af /* contribution */p : l) {
  boolean /* ara */q = false;
  for (Ab /* raw */r : p.rawFlows) q |= c.test(r);
  if (q || e || f || m || p.isUnpriced()) o.add(p);
 }
 if (o.isEmpty()) return emptyList();
 // An opened claim (a coin pouch, an unopened key) is one action row: the claim count and
 // the net it yielded, never a claim leg plus separate proceeds (owner 2026-09-29).
 if (m && !e && !f && !g && !h) {
  long /* spent */t = 0L;
  long /* net */u = 0L;
  boolean /* unpriced */v = false;
  boolean /* claimOnly */w = true;
  String /* name */x = null;
  int /* icon */y = -1;
  for (Af /* contribution */z : o) {
   long /* delta */aa = z.quantityDelta;
   if (aa < 0L) {
    if (z.priceSource != Av.DEFERRED_CLAIM) {
     w = false;
     break;
    }
    t -= aa;
    if (x == null) {
     x = z.itemName;
     y = z.itemId;
    } else if (!x.equals(z.itemName)) {
     x = "";
     y = -1;
    }
   } else if (aa > 0L) {
    u = safeAdd(u, z.effectiveValue);
    v |= z.isUnpriced();
   }
  }
  if (w && t > 0L && u > 0L) {
   String /* verb */ab = verbOf(b);
   String /* primary */ac = x == null || x.isEmpty() ? ab : x;
   String /* key */ad = "claim:" + ac + ":" + Long.signum(u) + ":"
   + ql(v) + ":" + ab + ":" + b.tm().name();
   var /* unit */ae = new Group(ad, y, Table.GAINS, ac, "", ab, "", false, j, u, coverage(v), t, false,
   false, false, false, false, false, "", b.getId(), o.get(0).contributionId, -1);
   for (Af /* contribution */af : o) ae.receipts.add(receipt(b, af, null, null));
   ae.search(ac, ab, k, b.getId());
   for (Af /* contribution */ag : o) ae.searchTerms.add(ag.itemName);
   return singletonList(ae);
  }
 }
 // An estimated charge use is one row named by the weapon.
 String /* note */ah = b.getNote();
 String /* prefix */ai = msg("jx");
 String /* ano */aj = ah.startsWith(ai) ? ah.substring(ai.length()).trim() : "";
 if (!aj.isEmpty()) {
  // A worn charge feeder (blood fury) is no cast: its estimate books the charged
  // resource "Used by" it — the same shape its measured Check produces (owner 2026-09-29).
  Ar.V /* feeder */ak = Ar.ajt(aj);
  // Owner 1.1: the blowpipe's rows are its Zulrah's scales (Charges) and darts (Supplies);
  // blood fury stays one "Blood fury" row, as a shard covers 10,000 hits.
  if (ak == Ar.V.EYE_OF_AYAK || ak == Ar.V.V1b) {
   var /* feasted */al = new ArrayList<Group>(o.size());
   for (Af /* contribution */am : o) {
    al.add(agx(b, am, dartFree(aj, am), e, f, g, k, j));
   }
   return al;
  }
  return singletonList(nw(b, o, aj, k, j, e, f, g));
 }
 // A measured charge spend books its resources as rows "Used by" the weapon, never a composite.
 String /* weapon */ao = b.getActionKind() == Au.FIRE || b.getActionKind() == Au.CAST
 ? aai(b) : "";
 if (Ar.ajt(ao) == Ar.V.BLOOD_FURY) return singletonList(nw(b, o, ao, k, j, e, f, g));
 if (ao.isEmpty() && vz(b, o, e, f, g, h, i)) {
  return singletonList(jx(b, o, k, j));
 }
 var /* units */ap = new ArrayList<Group>(o.size());
 for (Af /* contribution */aq : o) {
  ap.add(agx(b, aq, dartFree(ao, aq), e, f, g, k, j));
 }
 return ap;
}

/** A blowpipe's darts are Supplies like any ammo, never a charge "used by" the weapon. */
static String dartFree(String /* weapon */a, Af /* contribution */b) {
 return Ar.DARTS.containsValue(b.itemId) ? "" : a;
}

/**
* A safe action composite: pure counted costs of one financial category from one spell cast or
* one shot. Other actions (a sapling planted with its payment) stay per-item rows, as do mixed
* gain+cost, Market, Transfer, Review, Corrected, split and claim-linked rows.
*/
static boolean vz(Ac /* transaction */a,
List<Af> /* contributions */b, boolean /* neutral */c, boolean /* review */d, boolean /* corrected */e,
boolean /* split */f, boolean /* alj */g) {
 if (c || d || e || f || g
 || (a.getActionKind() != Au.CAST && a.getActionKind() != Au.FIRE)) {
  return false;
 }
 if (b.size() < 2) return false;
 Category /* category */h = sr(a, b.get(0));
 if (h != Category.SUPPLY && h != Category.LOSS && h != Category.FEE) {
  return false;
 }
 for (Af /* contribution */i : b) {
  if (sr(a, i) != h || !i.counted || i.auditOnly) {
   return false;
  }
 }
 return true;
}

static Group jx(Ac /* transaction */a, List<Af> /* contributions */b, String /* source */c, long /* at */d) {
 Category /* category */e = sr(a, b.get(0));
 boolean /* unpriced */f = false;
 long /* value */g = 0L;
 for (Af /* contribution */h : b) {
  f |= h.isUnpriced();
  g = safeAdd(g, h.effectiveValue);
 }
 String /* alu */i = a.spellName();
 String /* verb */j = verbOf(a);
 String /* primaryName */k = i.isEmpty() ? j : i;
 String /* context */l = pt(a, b);
 String /* key */m = i.isEmpty() ? "action:" + a.getActionKind().wireName() + ":generic="
 + ps(b) + ":cov=" + ql(f)
 : "action:" + a.getActionKind().wireName() + ":exact=" + i + ":cov=" + ql(f);
 String /* representative */n = b.get(0).contributionId;
 var /* unit */o = new Group(m, Spells.icon(i), axd(e), k,
 l, j, "", false, d, g, coverage(f), -1L, false, true,
 false, false, false, false, "", a.getId(), n, 0);
 for (Af /* contribution */p : b) {
  o.receipts.add(receipt(a, p, null, null));
 }
 o.search(k, l, j, c, a.getId());
 for (Af /* contribution */q : b) o.searchTerms.add(q.itemName);
 return o;
}

/** One resource row; with a weapon it is a measured charge spend "Used by" that weapon. */
static Group agx(Ac /* transaction */b, Af /* contribution */c, String /* weapon */d,
boolean /* neutral */e, boolean /* review */f, boolean /* corrected */g, String /* source */h, long /* at */i) {
 Category /* category */j = sr(b, c);
 boolean /* unpriced */k = c.isUnpriced();
 // An unopened claim (a key or a coin pouch) is held at zero, never shown as a value.
 int /* claimSign */l = c.priceSource == Av.DEFERRED_CLAIM
 ? (int) Long.signum(c.quantityDelta) : 0;
 long /* value */m = e || l != 0 || c.auditOnly ? 0L : c.effectiveValue;
 String /* verb */n = verbOf(b);
 String /* label */o = c.actionDisplayName;
 String /* actionLabel */p = d.isEmpty() ? o.isEmpty() ? n : n + " \u00b7 " + o : "";
 String /* context */q = d.isEmpty() ? p : "Used by " + d;
 // Owner 1.1: a production run's input and the same item its failures lost are one row.
 boolean /* run */r = b.getContext() == Aj.PRODUCTION && d.isEmpty() && c.quantityDelta < 0L;
 String /* key */t = r ? "run:" + axd(j) + ":" + c.itemId + ":" + ql(k) + ":"
 + f + ":" + g + ":" + e
 : (d.isEmpty() ? "res:" : "charge:" + d + ":") + axd(j) + ":"
 // Doses of one potion share a row whichever dose each came from (owner 2026-09-28).
 + (c.normalizedConsume ? c.itemName : c.itemId)
 + ":" + Long.signum(m) + ":" + ql(k) + ":"
 + c.priceSource.name() + ":" + o + ":" + n + ":" + b.tm().name()
 + ":" + (c.normalizedConsume ? "norm" : "raw")
 + ":" + (f ? "review" : "auto") + ":" + (g ? "corrected" : "auto")
 + ":" + (e || l != 0 ? "neutral" : "counted");
 var /* unit */u = new Group(t, c.itemId, axd(j),
 c.itemName, q, p, d, !d.isEmpty(), i, m,
 coverage(k), ahn(c), c.normalizedConsume, false,
 e || l != 0, false, f, g, "", b.getId(), c.contributionId, l);
 u.receipts.add(receipt(b, c, d, null));
 if (r && n.equals(msg("zz", "Failed"))) u.failed = nonNeg(u.quantity);
 u.search(c.itemName, q, n, o, d, h, b.getId());
 return u;
}

static List<Group> auv(Ac /* transaction */a, boolean /* neutral */b, boolean /* review */c,
boolean /* corrected */d, long /* at */e, String /* source */f) {
 Bp.Ax /* amounts */g = Bp.transaction(a);
 if (!g.included && !c && !b) return emptyList();
 long /* value */h = b ? 0L : g.getNet();
 Category /* category */i = Af.acf(a, g, b);
 String /* name */j = a.getNote().isEmpty() ? a.getActivityName() : a.getNote();
 String /* verb */k = verbOf(a);
 String /* key */l = "note:" + a.getId();
 var /* unit */m = new Group(l, -1, axd(i), j, k, k,
 "", false, e, h, Coverage.COMPLETE, 0L, false, true, b, false, c, d,
 "", a.getId(), a.getId() + ":composite", 0);
 m.receipts.add(new Receipt(a.getId() + ":composite", a.getId(), e, -1, j, 0L,
 h, a.tl(), false, b, c, d,
 Af.isSplit(a), i, k, a.spellName(),
 why(a, false, b), qj(a.getCorrection()), f, "", "", null));
 m.search(j, k, f, a.getId());
 return singletonList(m);
}

static Group aap(Row /* market */b, Ac /* settlement */c) {
 boolean /* realized */d = b.isRealizedIncluded() && b.realizedResultCorrectionAware;
 // Unknown-basis liquidation has no available Result: never show 0 as if break-even were
 // known. Its one exact contribution is a proven GE tax, which stays a market cost.
 boolean /* knownResult */e = d && (b.coverage != Bi.Coverage.FULLY_UNKNOWN
 || b.manualFinancialResult);
 boolean /* aot */f = d && b.knownCostOnly;
 boolean /* aos */g = e || f;
 boolean /* review */h = b.lifecycle == Lifecycle.AMBIGUOUS || b.lifecycle == Lifecycle.UNAVAILABLE
 || b.lifecycle == Lifecycle.CLOSED_UNOBSERVED
 || b.isRealizedIncluded() && !b.realizedResultCorrectionAware;
 String /* side */i = b.side.name().equals("SELL") ? "Sell" : "Buy";
 String /* status */j = e ? i : f ? i + msg("jy")
 : b.isRealizedIncluded() ? i + msg("jz") : yo(b);
 long /* quantity */k = b.isRealizedIncluded() ? b.settledQty : b.filledQty;
 String /* presentationId */l = b.presentationId;
 String /* key */m = "market:" + l;
 var /* unit */n = new Group(m, b.itemId, Table.MARKET,
 b.itemName, j, "", "", false, b.timestampEpochMillis, g ? b.realizedResultGp : 0L,
 g ? Coverage.COMPLETE : Coverage.INCOMPLETE, k, false, true, false, true, h, false,
 l, b.settlementId, "market:" + l, 0);
 n.search(b.itemName, j, i, b.lifecycle.name(), b.side.name(), l);
 if (!b.settlementId.isEmpty()) n.searchTerms.add(b.settlementId);
 // D2: a market group owns only its sale item's receipt. Its settlement's Coins leg is never
 // listed as a separate receipt or grouped with an unclaimed row.
 if (c != null) {
  for (Af /* contribution */o : Af.project(c)) {
   if (o.itemId == b.itemId) n.receipts.add(receipt(c, o, null, b));
  }
 }
 return n;
}

// ── helpers ────────────────────────────────────────────────────────────────────────────────
static Receipt receipt(Ac /* transaction */a, Af /* contribution */b, String /* usedBy */c, Row /* marketSettlement */d) {
 boolean /* neutral */e = b.category == Category.AUDIT && (a.getType() == Ai.TRANSFER
 || a.getCorrection() == Ah.TRANSFER);
 boolean /* review */f = a.getCorrection() == AUTO
 && (b.needsReview || Eh.aal(a));
 boolean /* atx */g = !e && !b.auditOnly;
 Category /* category */h = b.category;
 return new Receipt(b.contributionId, a.getId(),
 b.timestampEpochMillis, b.itemId, b.itemName,
 ahn(b), g ? b.effectiveValue : 0L,
 b.valueDelta, b.isUnpriced(), e, f,
 b.corrected, b.split, h, verbOf(a),
 b.actionDisplayName, why(a, b.isUnpriced(), e),
 qj(a.getCorrection()), Af.axb(a),
 c == null ? "" : c, chargeBasis(a, b), d);
}

static long ahn(Af /* contribution */a) {
 if (a.normalizedConsume) return a.normalizedQuantity;
 return abs(a.quantityDelta);
}

/** Display verb for a transaction: action kind first, then split/type direction. */
static String verbOf(Ac /* transaction */a) {
 if ("Dropped".equals(a.getNote())) return "Dropped";
 if (a.getActionKind() != null) return jy(a.getActionKind());
 if (Af.isSplit(a) && a.getCorrection() == AUTO) return "Split";
 if (a.getType() == CONSUMPTION && a.getContext() == Aj.PRODUCTION) {
  return msg("zz", "Failed");
 }
 return msg("verb." + a.getType(), a.getNet() < 0L ? "Used" : "Received");
}

static String jy(Au /* kind */a) {
 return msg("action." + a, "Used");
}

/** Why a receipt counted, told from stored records only. */
static String why(Ac /* transaction */b, boolean /* unpriced */c, boolean /* neutral */d) {
 if (b.getCorrection() != AUTO) return msg("why-correction." + b.getCorrection());
 if (d) return msg("ah");
 if (c) return msg("ho");
 String /* note */e = b.getNote();
 // Owner 2026-10-01 (F11): a Charges receipt names its retained confidence.
 if (e.startsWith(msg("jx"))) {
  return msg("ka");
 }
 if (e.startsWith(msg("kb"))) {
  return msg("kc");
 }
 if (Eh.aal(b)) return msg("fd");
 if (b.getType() == CONSUMPTION && b.getContext() == Aj.PRODUCTION) return msg("zy");
 return msg("why-type." + b.getType(), b.isCounted() ? msg("e3") : "Not counted.");
}

/**
* Charges: the exact captured unit price and its source, or "unpriced". Retained flow facts
* only - never re-derived from the value (owner 2026-10-01, F11).
*/
static String chargeBasis(Ac /* transaction */a, Af /* contribution */b) {
 String /* note */c = a.getNote();
 if (!c.startsWith(msg("jx")) && !c.startsWith(msg("kb"))) {
  return "";
 }
 if (b.isUnpriced() || b.rawFlows.isEmpty() || b.rawFlows.get(0).unitPrice <= 0) {
  return "unpriced";
 }
 Ab /* flow */d = b.rawFlows.get(0);
 return Fmt.exact(d.unitPrice) + msg("kd") + d.getPriceSource();
}

static String qj(Ah /* correction */a) {
 return msg("counted-as." + a, "Automatic");
}

/** Exact weapon identity already retained by the measured charge booking path. */
/** One estimated charge cast: the weapon names a single cast row, like a spell. */
static Group nw(Ac /* transaction */b, List<Af> /* contributions */c,
String /* weapon */d, String /* source */e, long /* at */f, boolean /* neutral */g, boolean /* review */h, boolean /* corrected */i) {
 boolean /* unpriced */j = false;
 long /* value */k = 0L;
 for (Af /* contribution */l : c) {
  j |= l.isUnpriced();
  k = safeAdd(k, l.effectiveValue);
 }
 String /* verb */m = verbOf(b);
 String /* context */n = pt(b, c);
 String /* key */o = "chargecast:" + d + ":" + ql(j) + ":" + (g ? "neutral" : "counted")
 + ":" + (h ? "review" : "auto") + ":" + (i ? "corrected" : "auto");
 int /* icon */p = b.aym;
 if (p <= 0) {
  // The weapon hint is transient; a restart must still draw the weapon sprite,
  // including estimate-only variants (the scythe has no Check mapping yet).
  var /* variant */q = Ar.ajt(d);
  if (q != null) p = q.itemIds[0];
 }
 var /* unit */r = new Group(o, p,
 axd(sr(b, c.get(0))), d, n, m, "", true, f,
 k, coverage(j), 1L, false, true, g, false, h, i, "",
 b.getId(), c.get(0).contributionId, 0);
 for (Af /* contribution */s : c) {
  r.receipts.add(receipt(b, s, null, null));
  r.searchTerms.add(s.itemName);
 }
 r.search(d, n, m, e, b.getId());
 return r;
}

static String aai(Ac /* transaction */a) {
 String /* note */b = a.getNote();
 String /* prefix */c = msg("kb");
 if (!b.startsWith(c)) return "";
 String /* weapon */d = b.substring(c.length()).trim();
 return d.length() > 80 ? d.substring(0, 80).trim() : d;
}

static String pt(Ac /* transaction */a, List<Af> /* contributions */b) {
 boolean /* anh */c = true;
 var /* aqc */d = new LinkedHashSet<Integer>();
 for (Af /* contribution */e : b) {
  String /* name */f = e.itemName.toLowerCase(ROOT);
  if (!f.endsWith(" rune")) c = false;
  else d.add(e.itemId);
 }
 if (a.getActionKind() == Au.CAST && c) return d.size() + " rune types";
 return b.size() + (a.getActionKind() == Au.CAST ? " components" : " items");
}

/**
* Presentation grouping signature for generic action composites: sorted resource ids with a
* GCD-normalised quantity ratio when every quantity is known, else raw quantities (split
* rather than over-merge), plus the price source so priced/incomplete shapes stay distinct.
*/
static String ps(List<Af> /* contributions */a) {
 long /* gcd */b = 0L;
 boolean /* aph */c = true;
 for (Af /* contribution */d : a) {
  long /* quantity */e = abs(d.quantityDelta);
  if (e <= 0L) {
   c = false;
   break;
  }
  b = gcd(b, e);
 }
 var /* sorted */f = new ArrayList<Af>(a);
 f.sort(Comparator.comparingInt((Af /* itemData */g) -> g.itemId)
 .thenComparing((/* itemData */h -> h.contributionId)));
 var /* parts */i = new ArrayList<String>(f.size());
 for (Af /* contribution */j : f) {
  long /* quantity */k = abs(j.quantityDelta);
  long /* ratio */l = c && b > 0L ? k / b : k;
  i.add(j.itemId + ":" + l + ":"
  + (j.isUnpriced() ? "unpriced" : j.priceSource.name()));
 }
 return String.join(",", i);
}

static long gcd(long /* left */c, long /* right */d) {
 long a = Math.abs(c);
 long b = Math.abs(d);
 while (b != 0L) {
  long /* next */e = a % b;
  a = b;
  b = e;
 }
 return a;
}

/**
* The financial destination of one contribution. A zero-value financial movement (an
* unpriced cost or gain) is routed by its direction and positive evidence — never to Audit —
* so an unpriced Supply stays in Costs/Supplies with an incomplete marker.
*/
static Category sr(Ac /* transaction */a, Af /* contribution */b) {
 Category /* category */c = b.category;
 if (c != Category.AUDIT) return c;
 if (b.auditOnly || a.getType() == Ai.TRANSFER
 || a.getCorrection() == Ah.TRANSFER) {
  return Category.AUDIT;
 }
 long /* quantity */d = b.quantityDelta;
 if (d > 0L) return Category.GAIN;
 if (d == 0L) return Category.AUDIT;
 Ai /* type */e = a.tm();
 if (e == Ai.PK_FEE) return Category.FEE;
 if (e == Ai.TRADE) return Category.MARKET;
 if (e == Ai.PK_DEATH_LOSS || e == PK_SUPPLY_COST
 || e == Ai.PROCESSING || a.getActionKind() != null) {
  return e == Ai.PK_DEATH_LOSS ? Category.LOSS : Category.SUPPLY;
 }
 return Category.LOSS;
}

static Table axd(Category /* category */a) {
 switch (a) {
  case GAIN: return Table.GAINS;
  case SUPPLY: return Table.COSTS_SUPPLIES;
  case LOSS:
  case FEE: return Table.COSTS_LOSS;
  case MARKET: return Table.MARKET;
  case AUDIT:
  default: return Table.AUDIT;
 }
}

static String ql(boolean /* unpriced */a) {
 return a ? "incomplete" : "complete";
}

static Coverage coverage(boolean /* unpriced */a) {
 return a ? Coverage.INCOMPLETE : Coverage.COMPLETE;
}

static String yo(Row /* market */a) {
 boolean /* sell */b = a.side == Aa.Side.SELL;
 switch (a.lifecycle) {
  case PENDING: return "×" + a.offeredQty;
  case PARTIALLY_EXECUTED:
  return (b ? "Part sold " : "Part bought ") + a.filledQty + "/" + a.offeredQty;
  case EXECUTED_UNSETTLED:
  return b ? msg("ke") : msg("kf");
  case PARTIALLY_REALIZED: return msg("e2");
  case CANCELLED_RETURNED: return "Returned";
  case RESUMED: return "Resumed";
  case AMBIGUOUS: return "Review";
  case CLOSED_UNOBSERVED: return msg("e1");
  case UNAVAILABLE:
  default: return msg("e0");
 }
}
}
