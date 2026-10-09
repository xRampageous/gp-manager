package com.gpmanager;
import java.util.*;
import static com.gpmanager.Ag.*;
import static java.util.Collections.*;
import static com.gpmanager.Au.*;
import static com.gpmanager.Ai.*;
import static com.gpmanager.Ak.msg;
import static com.gpmanager.Ae.*;
/**
* Canonical durable financial fact: stable id, timestamp, type/context, booked item flows, the
* automatic counted state, confidence, correction and split state, and optional links to a PvP
* encounter or the pending claim it settled. Booked values are never repriced.
*/
class Ac {
String id;
long timestampEpochMillis;
Long activeElapsedMillis;
Ai type;
Aj context;
String note;
String activityName;
boolean counted;
List<Ab> flows;
long revenue;
long costs;
Bd confidence;
String explanation;
Ah correction;
String correctionReason;
String encounterId;
/** The pending claim this settlement resolved, when any; idempotency key on load. */
String sourceClaimId;
/** Observed action wire name (B10 presentation evidence); null when unsupported/legacy. */
String actionKind;
/**
* Durable bounded exact action label (schema 105), e.g. "Ice Burst". Presentation-only
* metadata attached to an already-booked canonical transaction: it never feeds
* classification, valuation, counted state, ownership, Review or correction. Revalidated on
* load; invalid text degrades to null and generic presentation.
*/
String observedActionLabel;
/** Exact remaining known-pool shares for recoverable counted item removals. Supporting state. */
List<Cd> trackedSinkShares;
Boolean trackedSinkObserved;
/** Explicit proof a pickup may recover this loss (an own drop, fired ammo); never inferred from text. */
Boolean ownDropRecoveryEligible;
/** Transient icon hint for an estimated charge cast: the weapon's item id; never saved. */
transient int aym;
Ac() {
 // Gson
}

Ac(long /* timestampEpochMillis */a, Long /* activeElapsedMillis */b, Ai /* type */c, Aj /* context */d, String /* note */e,
String /* activityName */f, boolean /* counted */g, List<Ab> /* flows */h, Bd /* confidence */i, String /* explanation */j,
String /* encounterId */k) {
 this.id = UUID.randomUUID().toString();
 this.timestampEpochMillis = a;
 this.activeElapsedMillis = b;
 this.type = c;
 this.context = d;
 this.note = axw(e);
 this.activityName = aas(f);
 this.counted = g;
 this.flows = h == null ? new ArrayList<>() : new ArrayList<>(h);
 this.confidence = i == null ? Bd.UNCERTAIN : i;
 this.explanation = axw(j);
 this.correction = Ah.AUTO;
 this.correctionReason = "";
 this.encounterId = k;
 recalculate();
}

void recalculate() {
 revenue = 0L;
 costs = 0L;
 if (flows == null) flows = new ArrayList<>();
 for (Ab /* flow */a : flows) {
  if (a.valueDelta > 0) revenue = safeAdd(revenue, a.valueDelta);
  else if (a.valueDelta < 0) costs = safeAdd(costs, abs(a.valueDelta));
 }
}

void ko(Ah /* newCorrection */a, long /* now */b) {
 ko(a, b, "Manual correction");
}

void ko(Ah /* newCorrection */a, long /* now */b, String /* reason */c) {
 correction = a == null ? Ah.AUTO : a;
 correctionReason = aat(c);
}

/**
* Takes {@code amount} of one item's gains (newest flow first) or costs (oldest first) out of
* the flows, each remainder keeping its own unit value, and returns the quantity left.
*/
long afg(int /* itemId */a, boolean /* gains */b, long /* amount */c) {
 var /* ordered */d = new ArrayList<Ab>(getFlows());
 if (b) reverse(d);
 var /* next */e = new ArrayList<Ab>();
 for (Ab /* flow */f : d) {
  long /* magnitude */g = f == null ? 0L : abs(f.quantityDelta);
  long /* take */h = f != null && f.itemId == a && (b ? f.isGain() : f.isCost())
  ? Math.min(nonNeg(c), g) : 0L;
  c -= h;
  long /* left */i = g - h;
  if (h <= 0L) {
   e.add(f);
  } else if (i > 0L) {
   long /* unit */j = f.unitPrice;
   long /* signed */k = b ? i : -i;
   e.add(new Ab(a, f.itemName, k, f.unitPrice,
   j == 0L ? f.valueDelta / g * i : k * j,
   f.getPriceSource(), f.getPriceCapturedAtEpochMillis()));
  }
 }
 if (b) reverse(e);
 afj(e);
 return quantity(a, b);
}

void afj(List<Ab> /* nextFlows */a) {
 flows = a == null ? new ArrayList<>() : new ArrayList<>(a);
 recalculate();
}

/**
* Resolve an automatic charge-load Review row as a neutral transfer after
* a same-target measured Check confirms its component quantities.
*/
boolean agg(List<Ab> /* confirmedFlows */a) {
 if (tm() != UNCERTAIN || getCorrection() != Ah.AUTO || getActionKind() != CHARGE_LOAD_AMBIGUOUS
 || empty(a)) {
  return false;
 }
 type = Ai.TRANSFER;
 context = Aj.TRANSFER;
 note = msg("er");
 activityName = "Charge load";
 counted = false;
 confidence = Bd.CONFIRMED;
 explanation = msg("fm");
 afj(a);
 return true;
}

/**
* Stamps split provenance on explanation/reason without changing the
* correction enum (partial keep stays counted).
*/
void ajk(String /* reason */a, long /* now */b) {
 String /* mark */c = a == null ? Dm.SPLIT_SHARE_MARK : a.trim();
 if (c.isEmpty()) c = Dm.SPLIT_SHARE_MARK;
 correctionReason = aat(c);
 String /* current */d = getExplanation();
 if (!Dm.xr(d)) {
  explanation = d.isEmpty() ? c : d + " — " + c;
 } else if (!d.contains(c)) {
  explanation = d + " — " + c;
 }
}

void agt(String /* next */a) {
 explanation = axw(a);
}

/** Total gained ({@code gains}) or lost quantity of one item across the remaining flows. */
long quantity(int /* itemId */a, boolean /* gains */b) {
 long /* total */c = 0L;
 for (Ab /* flow */d : flows == null ? Collections.<Ab>emptyList() : flows) {
  if (d != null && d.itemId == a && (b ? d.isGain() : d.isCost())) {
   c = safeAdd(c, abs(d.quantityDelta));
  }
 }
 return c;
}

void lf(String /* newEncounterId */a, boolean /* asPkSupplyCost */b) {
 encounterId = a;
 if (b && tm() == CONSUMPTION) {
  type = PK_SUPPLY_COST;
  activityName = "PKing";
  confidence = Bd.LIKELY;
  explanation = msg("hw");
 }
}

long gross() {
 return safeAdd(revenue, costs);
}

String getId() {
 if (empty(id)) id = UUID.randomUUID().toString();
 return id;
}

Ai tm() {
 return type == null ? ADJUSTMENT : type;
}

Ai getType() {
 switch (getCorrection()) {
  case REVENUE:
  return GAIN;
  case COST:
  return CONSUMPTION;
  case TRANSFER:
  return Ai.TRANSFER;
  case IGNORE:
  return ADJUSTMENT;
  case AUTO:
  default:
  return tm();
 }
}

Aj getContext() { return context == null ? Aj.GENERIC : context; }
String getNote() { return axw(note); }
/** Presentation-only audit-row note update; accounting fields are untouched. */
String getActivityName() {
 if (activityName != null && !activityName.trim().isEmpty()) return activityName.trim();
 String /* aqf */a = getNote();
 String /* prefix */b = "Loot from ";
 return a.startsWith(b) && a.length() > b.length() ? a.substring(b.length()).trim()
 : "General";
}

boolean isCounted() {
 Ah /* safeCorrection */a = getCorrection();
 return a == Ah.REVENUE || a == Ah.COST
 || (a == Ah.AUTO && counted);
}

/** Raw automatic counted flag, before any correction makes the row count. */
boolean vn() {
 return counted;
}

boolean yi() { return Boolean.TRUE.equals(trackedSinkObserved); }
boolean xe() { return Boolean.TRUE.equals(ownDropRecoveryEligible); }
void zd() { ownDropRecoveryEligible = true; }
List<Cd> va() {
 if (trackedSinkShares == null) trackedSinkShares = new ArrayList<>();
 return trackedSinkShares;
}

void aie(List<Cd> /* shares */a) {
 trackedSinkShares = a == null ? new ArrayList<>() : new ArrayList<>(a);
 trackedSinkObserved = true;
}

List<Ab> getFlows() {
 if (flows == null) flows = new ArrayList<>();
 return unmodifiableList(flows);
}

long tl() { return aha(revenue, costs); }
long getRevenue() {
 switch (getCorrection()) {
  case REVENUE:
  return gross();
  case COST:
  case TRANSFER:
  case IGNORE:
  return 0L;
  case AUTO:
  default:
  return revenue;
 }
}

long getCosts() {
 switch (getCorrection()) {
  case COST:
  return gross();
  case REVENUE:
  case TRANSFER:
  case IGNORE:
  return 0L;
  case AUTO:
  default:
  return costs;
 }
}

long getNet() {
 return aha(getRevenue(), getCosts());
}

/** Personal-Net contribution this row would have under {@code hypothetical} (0 when it would not count). */
long awp(Ah /* hypothetical */a) {
 switch (a == null ? Ah.AUTO : a) {
  case REVENUE:
  return gross();
  case COST:
  return aha(0L, gross());
  case TRANSFER:
  case IGNORE:
  return 0L;
  case AUTO:
  default:
  return counted ? aha(revenue, costs) : 0L;
 }
}

Bd getConfidence() {
 return confidence == null ? Bd.UNCERTAIN : confidence;
}

String getExplanation() {
 return axw(explanation);
}

Ah getCorrection() {
 return correction == null ? Ah.AUTO : correction;
}

String tq() { return correctionReason == null ? "" : correctionReason.trim(); }
String getEncounterId() { return axw(encounterId); }
String uo() { return axw(sourceClaimId); }
/** Links this settlement to the claim it resolved; set once, in the same revision the claim is removed. */
void aid(String /* claimId */a) {
 sourceClaimId = awq(a, null);
}

/** Observed action behind this change, or null when no honest verb was evidenced. */
Au getActionKind() {
 return us(actionKind);
}

/** Presentation-only stamp; never alters type, valuation, counted state or ownership. */
void setActionKind(Au /* kind */a) {
 this.actionKind = a == null ? null : a.wireName();
}

/** Exact action text captured from a trusted client widget, or null for generic/legacy rows. */
Bb uc() {
 return Bb.of(aaz());
}

/**
* A Cast's name for presentation: the captured spell, else (autocast never clicks one) the spell
* its exact runes pay for; "" otherwise. Never stored.
*/
String spellName() {
 return memo(7, () -> {
  // Runes that identify a spell win over a click queued behind an autocast (owner 2026-09-28);
  // otherwise the clicked name stands (a staff-covered or non-combat spell).
  String /* spell */b = getActionKind() == CAST ? Spells.a1(getFlows()) : null;
  Bb /* label */c = uc();
  return b != null ? b : c == null ? "" : c.value();
 });
}

/** Derived-value memo (release pass): every refresh reads each receipt; never stored. */
transient Object[] memo;
/**
* Slots 6+ hold derived values; the array is replaced as soon as flows, correction, type, counted,
* action or label change (each is replaced, never edited in place).
*/
@SuppressWarnings("unchecked")
<T> T memo(int /* slot */a, java.util.function.Supplier<T> /* build */b) {
 Object[] m = memo;
 if (m == null || m[0] != flows || m[1] != correction || m[2] != type || m[3] != (Boolean) counted
 || m[4] != actionKind || m[5] != observedActionLabel) {
  m = new Object[] {flows, correction, type, counted, actionKind, observedActionLabel, null, null, null, null, null};
  memo = m;
 }
 if (m[a] == null) m[a] = b.get();
 return (T) m[a];
}

/** Presentation only. Stores only the normalized bounded label text. */
void ahu(Bb /* label */a) {
 observedActionLabel = a == null ? null : a.value();
}

/**
* Load-time revalidation of the optional schema-105 presentation field. Oversized, markup,
* control-character or generic values become null, and only CAST transactions keep a label.
*/
void aax() {
 observedActionLabel = aaz();
}

/**
* The only accepted label value: bounded, sanitized, non-generic and attached to a CAST
* transaction. Non-CAST rows never read or keep a stale persisted spell name.
*/
String aaz() {
 if (empty(observedActionLabel)) return null;
 if (getActionKind() != CAST && getActionKind() != Au.DRINK) return null;
 Bb /* normalized */a = Bb.of(observedActionLabel);
 return a == null ? null : a.value();
}

static String aat(String /* value */a) {
 if (blank(a)) return "Manual correction";
 String /* trimmed */b = a.trim();
 return b.length() > 200 ? b.substring(0, 200) : b;
}
}
