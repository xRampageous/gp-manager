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
/** Explicit confirmed own-drop proof, never inferred from editable presentation text. */
Boolean ownDropRecoveryEligible;
/** Transient icon hint for an estimated charge cast: the weapon's item id; never saved. */
transient int aym;
Ac() {
 // Gson
}

Ac(long timestampEpochMillis, Long activeElapsedMillis, Ai type, Aj context, String note,
String activityName, boolean counted, List<Ab> flows, Bd confidence, String explanation,
String encounterId) {
 this.id = UUID.randomUUID().toString();
 this.timestampEpochMillis = timestampEpochMillis;
 this.activeElapsedMillis = activeElapsedMillis;
 this.type = type;
 this.context = context;
 this.note = axw(note);
 this.activityName = aas(activityName);
 this.counted = counted;
 this.flows = flows == null ? new ArrayList<>() : new ArrayList<>(flows);
 this.confidence = confidence == null ? Bd.UNCERTAIN : confidence;
 this.explanation = axw(explanation);
 this.correction = Ah.AUTO;
 this.correctionReason = "";
 this.encounterId = encounterId;
 recalculate();
}

void recalculate() {
 revenue = 0L;
 costs = 0L;
 if (flows == null) flows = new ArrayList<>();
 for (Ab flow : flows) {
  if (flow.valueDelta > 0) revenue = safeAdd(revenue, flow.valueDelta);
  else if (flow.valueDelta < 0) costs = safeAdd(costs, abs(flow.valueDelta));
 }
}

void ko(Ah newCorrection, long now) {
 ko(newCorrection, now, "Manual correction");
}

void ko(Ah newCorrection, long now, String reason) {
 correction = newCorrection == null ? Ah.AUTO : newCorrection;
 correctionReason = aat(reason);
}

/**
* Takes {@code amount} of one item's gains (newest flow first) or costs (oldest first) out of
* the flows, each remainder keeping its own unit value, and returns the quantity left.
*/
long afg(int itemId, boolean gains, long amount) {
 var ordered = new ArrayList<Ab>(getFlows());
 if (gains) reverse(ordered);
 var next = new ArrayList<Ab>();
 for (Ab flow : ordered) {
  long magnitude = flow == null ? 0L : abs(flow.quantityDelta);
  long take = flow != null && flow.itemId == itemId && (gains ? flow.isGain() : flow.isCost())
  ? Math.min(nonNeg(amount), magnitude) : 0L;
  amount -= take;
  long left = magnitude - take;
  if (take <= 0L) {
   next.add(flow);
  } else if (left > 0L) {
   long unit = flow.unitPrice;
   long signed = gains ? left : -left;
   next.add(new Ab(itemId, flow.itemName, signed, flow.unitPrice,
   unit == 0L ? flow.valueDelta / magnitude * left : signed * unit,
   flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
  }
 }
 if (gains) reverse(next);
 afj(next);
 return quantity(itemId, gains);
}

void afj(List<Ab> nextFlows) {
 flows = nextFlows == null ? new ArrayList<>() : new ArrayList<>(nextFlows);
 recalculate();
}

/**
* Resolve an automatic charge-load Review row as a neutral transfer after
* a same-target measured Check confirms its component quantities.
*/
boolean agg(List<Ab> confirmedFlows) {
 if (tm() != UNCERTAIN || getCorrection() != Ah.AUTO || getActionKind() != CHARGE_LOAD_AMBIGUOUS
 || empty(confirmedFlows)) {
  return false;
 }
 type = Ai.TRANSFER;
 context = Aj.TRANSFER;
 note = msg("er");
 activityName = "Charge load";
 counted = false;
 confidence = Bd.CONFIRMED;
 explanation = msg("fm");
 afj(confirmedFlows);
 return true;
}

/**
* Stamps split provenance on explanation/reason without changing the
* correction enum (partial keep stays counted).
*/
void ajk(String reason, long now) {
 String mark = reason == null ? Dm.SPLIT_SHARE_MARK : reason.trim();
 if (mark.isEmpty()) mark = Dm.SPLIT_SHARE_MARK;
 correctionReason = aat(mark);
 String current = getExplanation();
 if (!Dm.xr(current)) {
  explanation = current.isEmpty() ? mark : current + " — " + mark;
 } else if (!current.contains(mark)) {
  explanation = current + " — " + mark;
 }
}

void agt(String next) {
 explanation = axw(next);
}

/** Total gained ({@code gains}) or lost quantity of one item across the remaining flows. */
long quantity(int itemId, boolean gains) {
 long total = 0L;
 for (Ab flow : flows == null ? Collections.<Ab>emptyList() : flows) {
  if (flow != null && flow.itemId == itemId && (gains ? flow.isGain() : flow.isCost())) {
   total = safeAdd(total, abs(flow.quantityDelta));
  }
 }
 return total;
}

void lf(String newEncounterId, boolean asPkSupplyCost) {
 encounterId = newEncounterId;
 if (asPkSupplyCost && tm() == CONSUMPTION) {
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
 String aqf = getNote();
 String prefix = "Loot from ";
 return aqf.startsWith(prefix) && aqf.length() > prefix.length() ? aqf.substring(prefix.length()).trim()
 : "General";
}

boolean isCounted() {
 Ah safeCorrection = getCorrection();
 return safeCorrection == Ah.REVENUE || safeCorrection == Ah.COST
 || (safeCorrection == Ah.AUTO && counted);
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

void aie(List<Cd> shares) {
 trackedSinkShares = shares == null ? new ArrayList<>() : new ArrayList<>(shares);
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
long awp(Ah hypothetical) {
 switch (hypothetical == null ? Ah.AUTO : hypothetical) {
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

/**
* Short, human-readable pricing provenance for transaction tooltips and
* diagnostics. It is derived from the persisted item flows so old sessions
* remain readable even when they predate provenance tracking.
*/
String uu() {
 var counts = new EnumMap<Av, Integer>(Av.class);
 for (Ab flow : getFlows()) {
  if (flow == null) continue;
  Av source = flow.getPriceSource();
  counts.put(source, counts.getOrDefault(source, 0) + 1);
 }
 if (counts.isEmpty()) return "Pricing: none";
 var summary = new StringBuilder("Pricing: ");
 boolean first = true;
 for (Av source : Av.values()) {
  Integer count = counts.get(source);
  if (count == null || count == 0) continue;
  if (!first) summary.append(", ");
  summary.append(source).append(" (").append(count).append(")");
  first = false;
 }
 return summary.toString();
}

Ah getCorrection() {
 return correction == null ? Ah.AUTO : correction;
}

String tq() { return correctionReason == null ? "" : correctionReason.trim(); }
String getEncounterId() { return axw(encounterId); }
String uo() { return axw(sourceClaimId); }
/** Links this settlement to the claim it resolved; set once, in the same revision the claim is removed. */
void aid(String claimId) {
 sourceClaimId = awq(claimId, null);
}

/** Observed action behind this change, or null when no honest verb was evidenced. */
Au getActionKind() {
 return us(actionKind);
}

/** Presentation-only stamp; never alters type, valuation, counted state or ownership. */
void setActionKind(Au kind) {
 this.actionKind = kind == null ? null : kind.wireName();
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
  String spell = getActionKind() == CAST ? Spells.named(getFlows()) : null;
  Bb label = uc();
  return spell != null ? spell : label == null ? "" : label.value();
 });
}

/** Derived-value memo (release pass): every refresh reads each receipt; never stored. */
transient Object[] memo;
/**
* Slots 6+ hold derived values; the array is replaced as soon as flows, correction, type, counted,
* action or label change (each is replaced, never edited in place).
*/
@SuppressWarnings("unchecked")
<T> T memo(int slot, java.util.function.Supplier<T> build) {
 Object[] m = memo;
 if (m == null || m[0] != flows || m[1] != correction || m[2] != type || m[3] != (Boolean) counted
 || m[4] != actionKind || m[5] != observedActionLabel) {
  m = new Object[] {flows, correction, type, counted, actionKind, observedActionLabel, null, null, null, null, null};
  memo = m;
 }
 if (m[slot] == null) m[slot] = build.get();
 return (T) m[slot];
}

/** Presentation only. Stores only the normalized bounded label text. */
void ahu(Bb label) {
 observedActionLabel = label == null ? null : label.value();
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
 if (getActionKind() != CAST) return null;
 Bb normalized = Bb.of(observedActionLabel);
 return normalized == null ? null : normalized.value();
}

static String aat(String value) {
 if (blank(value)) return "Manual correction";
 String trimmed = value.trim();
 return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
}
}
