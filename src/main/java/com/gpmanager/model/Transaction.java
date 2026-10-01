package com.gpmanager;
import java.util.*;
import static com.gpmanager.ModelText.*;
import static java.util.Collections.*;
import static com.gpmanager.ActionKind.*;
import static com.gpmanager.TransactionType.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.SafeMath.*;
/**
* Canonical durable financial fact: stable id, timestamp, type/context, booked item flows, the
* automatic counted state, confidence, correction and split state, and optional links to a PvP
* encounter or the pending claim it settled. Booked values are never repriced.
*/
class Transaction {
String id;
long timestampEpochMillis;
Long activeElapsedMillis;
TransactionType type;
Context context;
String note;
String activityName;
boolean counted;
List<Flow> flows;
long revenue;
long costs;
ClassificationConfidence confidence;
String explanation;
Correction correction;
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
List<SinkShare> trackedSinkShares;
Boolean trackedSinkObserved;
/** Explicit confirmed own-drop proof, never inferred from editable presentation text. */
Boolean ownDropRecoveryEligible;
/** Transient icon hint for an estimated charge cast: the weapon's item id; never saved. */
transient int chargeCastItemId;
Transaction() {
 // Gson
}

Transaction(long timestampEpochMillis, Long activeElapsedMillis, TransactionType type, Context context, String note,
String activityName, boolean counted, List<Flow> flows, ClassificationConfidence confidence, String explanation,
String encounterId) {
 this.id = UUID.randomUUID().toString();
 this.timestampEpochMillis = timestampEpochMillis;
 this.activeElapsedMillis = activeElapsedMillis;
 this.type = type;
 this.context = context;
 this.note = orEmpty(note);
 this.activityName = normalizeActivity(activityName);
 this.counted = counted;
 this.flows = flows == null ? new ArrayList<>() : new ArrayList<>(flows);
 this.confidence = confidence == null ? ClassificationConfidence.UNCERTAIN : confidence;
 this.explanation = orEmpty(explanation);
 this.correction = Correction.AUTO;
 this.correctionReason = "";
 this.encounterId = encounterId;
 recalculate();
}

void recalculate() {
 revenue = 0L;
 costs = 0L;
 if (flows == null) flows = new ArrayList<>();
 for (Flow flow : flows) {
  if (flow.valueDelta > 0) revenue = safeAdd(revenue, flow.valueDelta);
  else if (flow.valueDelta < 0) costs = safeAdd(costs, abs(flow.valueDelta));
 }
}

void applyCorrection(Correction newCorrection, long now) {
 applyCorrection(newCorrection, now, "Manual correction");
}

void applyCorrection(Correction newCorrection, long now, String reason) {
 correction = newCorrection == null ? Correction.AUTO : newCorrection;
 correctionReason = normalizeCorrectionReason(reason);
}

/**
* Takes {@code amount} of one item's gains (newest flow first) or costs (oldest first) out of
* the flows, each remainder keeping its own unit value, and returns the quantity left.
*/
long removeQuantity(int itemId, boolean gains, long amount) {
 var ordered = new ArrayList<Flow>(getFlows());
 if (gains) reverse(ordered);
 var next = new ArrayList<Flow>();
 for (Flow flow : ordered) {
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
   next.add(new Flow(itemId, flow.itemName, signed, flow.unitPrice,
   unit == 0L ? flow.valueDelta / magnitude * left : signed * unit,
   flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
  }
 }
 if (gains) reverse(next);
 replaceFlows(next);
 return quantity(itemId, gains);
}

void replaceFlows(List<Flow> nextFlows) {
 flows = nextFlows == null ? new ArrayList<>() : new ArrayList<>(nextFlows);
 recalculate();
}

/**
* Resolve an automatic charge-load Review row as a neutral transfer after
* a same-target measured Check confirms its component quantities.
*/
boolean resolveChargeLoadReviewAsTransfer(List<Flow> confirmedFlows) {
 if (getAutomaticType() != UNCERTAIN || getCorrection() != Correction.AUTO || getActionKind() != CHARGE_LOAD_AMBIGUOUS
 || empty(confirmedFlows)) {
  return false;
 }
 type = TransactionType.TRANSFER;
 context = Context.TRANSFER;
 note = msg("er");
 activityName = "Charge load";
 counted = false;
 confidence = ClassificationConfidence.CONFIRMED;
 explanation = msg("fm");
 replaceFlows(confirmedFlows);
 return true;
}

/**
* Stamps split provenance on explanation/reason without changing the
* correction enum (partial keep stays counted).
*/
void stampSplitProvenance(String reason, long now) {
 String mark = reason == null ? ItemSplitAccounting.SPLIT_SHARE_MARK : reason.trim();
 if (mark.isEmpty()) mark = ItemSplitAccounting.SPLIT_SHARE_MARK;
 correctionReason = normalizeCorrectionReason(mark);
 String current = getExplanation();
 if (!ItemSplitAccounting.isSplitReason(current)) {
  explanation = current.isEmpty() ? mark : current + " — " + mark;
 } else if (!current.contains(mark)) {
  explanation = current + " — " + mark;
 }
}

void rewriteExplanation(String next) {
 explanation = orEmpty(next);
}

/** Total gained ({@code gains}) or lost quantity of one item across the remaining flows. */
long quantity(int itemId, boolean gains) {
 long total = 0L;
 for (Flow flow : flows == null ? Collections.<Flow>emptyList() : flows) {
  if (flow != null && flow.itemId == itemId && (gains ? flow.isGain() : flow.isCost())) {
   total = safeAdd(total, abs(flow.quantityDelta));
  }
 }
 return total;
}

void assignEncounter(String newEncounterId, boolean asPkSupplyCost) {
 encounterId = newEncounterId;
 if (asPkSupplyCost && getAutomaticType() == CONSUMPTION) {
  type = PK_SUPPLY_COST;
  activityName = "PKing";
  confidence = ClassificationConfidence.LIKELY;
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

TransactionType getAutomaticType() {
 return type == null ? ADJUSTMENT : type;
}

TransactionType getType() {
 switch (getCorrection()) {
  case REVENUE:
  return GAIN;
  case COST:
  return CONSUMPTION;
  case TRANSFER:
  return TransactionType.TRANSFER;
  case IGNORE:
  return ADJUSTMENT;
  case AUTO:
  default:
  return getAutomaticType();
 }
}

Context getContext() { return context == null ? Context.GENERIC : context; }
String getNote() { return orEmpty(note); }
/** Presentation-only audit-row note update; accounting fields are untouched. */
String getActivityName() {
 if (activityName != null && !activityName.trim().isEmpty()) return activityName.trim();
 String safeNote = getNote();
 String prefix = "Loot from ";
 return safeNote.startsWith(prefix) && safeNote.length() > prefix.length() ? safeNote.substring(prefix.length()).trim()
 : "General";
}

boolean isCounted() {
 Correction safeCorrection = getCorrection();
 return safeCorrection == Correction.REVENUE || safeCorrection == Correction.COST
 || (safeCorrection == Correction.AUTO && counted);
}

/** Raw automatic counted flag, before any correction makes the row count. */
boolean isAutomaticallyCounted() {
 return counted;
}

boolean isTrackedSinkObserved() { return Boolean.TRUE.equals(trackedSinkObserved); }
boolean isOwnDropRecoveryEligible() { return Boolean.TRUE.equals(ownDropRecoveryEligible); }
void markOwnDropRecoveryEligible() { ownDropRecoveryEligible = true; }
List<SinkShare> getTrackedSinkShares() {
 if (trackedSinkShares == null) trackedSinkShares = new ArrayList<>();
 return trackedSinkShares;
}

void setTrackedSinkShares(List<SinkShare> shares) {
 trackedSinkShares = shares == null ? new ArrayList<>() : new ArrayList<>(shares);
 trackedSinkObserved = true;
}

List<Flow> getFlows() {
 if (flows == null) flows = new ArrayList<>();
 return unmodifiableList(flows);
}

long getAutomaticNet() { return safeSubtract(revenue, costs); }
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
 return safeSubtract(getRevenue(), getCosts());
}

/** Personal-Net contribution this row would have under {@code hypothetical} (0 when it would not count). */
long netUnder(Correction hypothetical) {
 switch (hypothetical == null ? Correction.AUTO : hypothetical) {
  case REVENUE:
  return gross();
  case COST:
  return safeSubtract(0L, gross());
  case TRANSFER:
  case IGNORE:
  return 0L;
  case AUTO:
  default:
  return counted ? safeSubtract(revenue, costs) : 0L;
 }
}

ClassificationConfidence getConfidence() {
 return confidence == null ? ClassificationConfidence.UNCERTAIN : confidence;
}

String getExplanation() {
 return orEmpty(explanation);
}

/**
* Short, human-readable pricing provenance for transaction tooltips and
* diagnostics. It is derived from the persisted item flows so old sessions
* remain readable even when they predate provenance tracking.
*/
String getPricingSummary() {
 var counts = new EnumMap<PriceSource, Integer>(PriceSource.class);
 for (Flow flow : getFlows()) {
  if (flow == null) continue;
  PriceSource source = flow.getPriceSource();
  counts.put(source, counts.getOrDefault(source, 0) + 1);
 }
 if (counts.isEmpty()) return "Pricing: none";
 var summary = new StringBuilder("Pricing: ");
 boolean first = true;
 for (PriceSource source : PriceSource.values()) {
  Integer count = counts.get(source);
  if (count == null || count == 0) continue;
  if (!first) summary.append(", ");
  summary.append(source).append(" (").append(count).append(")");
  first = false;
 }
 return summary.toString();
}

Correction getCorrection() {
 return correction == null ? Correction.AUTO : correction;
}

String getCorrectionReason() { return correctionReason == null ? "" : correctionReason.trim(); }
String getEncounterId() { return orEmpty(encounterId); }
String getSourceClaimId() { return orEmpty(sourceClaimId); }
/** Links this settlement to the claim it resolved; set once, in the same revision the claim is removed. */
void setSourceClaimId(String claimId) {
 sourceClaimId = nonBlank(claimId, null);
}

/** Observed action behind this change, or null when no honest verb was evidenced. */
ActionKind getActionKind() {
 return fromWireName(actionKind);
}

/** Presentation-only stamp; never alters type, valuation, counted state or ownership. */
void setActionKind(ActionKind kind) {
 this.actionKind = kind == null ? null : kind.wireName();
}

/** Exact action text captured from a trusted client widget, or null for generic/legacy rows. */
ActionLabel getObservedActionLabel() {
 return ActionLabel.of(normalizedActionLabelWire());
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
  ActionLabel label = getObservedActionLabel();
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
void setObservedActionLabel(ActionLabel label) {
 observedActionLabel = label == null ? null : label.value();
}

/**
* Load-time revalidation of the optional schema-105 presentation field. Oversized, markup,
* control-character or generic values become null, and only CAST transactions keep a label.
*/
void normalizeObservedActionLabel() {
 observedActionLabel = normalizedActionLabelWire();
}

/**
* The only accepted label value: bounded, sanitized, non-generic and attached to a CAST
* transaction. Non-CAST rows never read or keep a stale persisted spell name.
*/
String normalizedActionLabelWire() {
 if (empty(observedActionLabel)) return null;
 if (getActionKind() != CAST) return null;
 ActionLabel normalized = ActionLabel.of(observedActionLabel);
 return normalized == null ? null : normalized.value();
}

static String normalizeCorrectionReason(String value) {
 if (blank(value)) return "Manual correction";
 String trimmed = value.trim();
 return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
}
}
