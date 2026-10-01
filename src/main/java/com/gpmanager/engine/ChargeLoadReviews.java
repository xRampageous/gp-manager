package com.gpmanager;
import java.util.*;
import lombok.*;
import static com.gpmanager.Correction.*;
import static com.gpmanager.TransactionType.*;
import static com.gpmanager.GameData.msg;
/**
* Charge-load transfer booking and the Review rows an ambiguous load leaves behind: records the
* neutral load transfer, opens an uncounted Review row when quantities are ambiguous, and later
* reconciles that row against a same-target measured Check. Invoked only under the
* {@link Engine} monitor.
*/
@AllArgsConstructor
class ChargeLoadReviews {
final Engine engine;
/**
* What explains each open ambiguous-load Review row, keyed by transaction id. Transient: a
* same-target Check can only reconcile a row within the same client run (the measured read
* baseline is itself transient), so nothing here is persisted; the Review row stays for the
* owner to decide after a restart.
*/
final Map<String, LoadEvidence> evidence = new HashMap<>();
@AllArgsConstructor
static class LoadEvidence {
final String variantWireName;
final String matchedTargetIdentity;
final long observedAtEpochMillis;
}

/** Drops every transient explanation; called when the bound profile changes. */
void clear() {
evidence.clear();
}

Transaction recordAmbiguousChargeLoadReview(ChargeLoadTransferEvidence.Partition partition, long now) {
if (partition == null || partition.ambiguousCandidates.isEmpty() || !engine.live()) {
return null;
}
// A validated recharge moves value into charges: neutral by law, never a cost and
// never an incomplete Review (owner 2026-09-29). The loss is the use, not the load.
Transaction transfer = engine.bookNeutral(now, msg("cu"), "Charge load",
partition.ambiguousCandidates, msg("fo"), null);
transfer.setActionKind(ActionKind.CHARGE_LOAD_AMBIGUOUS);
engine.add(transfer, false);
return transfer;
}

void reconcileChargeLoadReviews(ChargeRead.Variant variant, String targetIdentity,
Map<Integer, Long> exactLoadQuantities, long previousReadAt, long now) {
if (variant == null || targetIdentity == null || targetIdentity.trim().isEmpty()
|| exactLoadQuantities == null || exactLoadQuantities.isEmpty() || !engine.live()) {
return;
}
long windowMillis = 600_000L;
var matches = new ArrayList<Transaction>();
String expectedVariant = variant.name();
for (Transaction transaction : engine.activeSession.getTransactions()) {
if (transaction == null || transaction.getAutomaticType() != UNCERTAIN || transaction.getCorrection() != AUTO) {
continue;
}
LoadEvidence provenance = evidence.get(transaction.getId());
if (provenance == null || !expectedVariant.equalsIgnoreCase(provenance.variantWireName)
|| !targetIdentity.equals(provenance.matchedTargetIdentity)) {
continue;
}
long observedAt = provenance.observedAtEpochMillis;
if (observedAt <= now && now - observedAt <= windowMillis && previousReadAt > 0L && previousReadAt <= observedAt
&& observedAt - previousReadAt <= windowMillis) {
matches.add(transaction);
}
}
// Multiple matching losses have ambiguous source attribution. Leave all
// of them in Review instead of assigning one measured load arbitrarily.
if (matches.size() != 1) return;
Transaction review = matches.get(0);
if (hasDuplicateChargeComponentIds(review.getFlows())) return;
var remaining = new ArrayList<Flow>(review.getFlows());
var measured = new HashMap<Integer, Long>(exactLoadQuantities);
List<Flow> confirmed = FlowFilters.claim(remaining, flow -> flow.isCost() && flow.unitPrice >= 0
&& SafeMath.safeMultiply(flow.quantityDelta, flow.unitPrice) == flow.valueDelta,
(flow, quantity) -> FlowFilters.take(measured, flow.itemId, quantity));
if (confirmed.isEmpty()) return;
engine.activeSession.confirmChargeLoad(review, confirmed, remaining);
evidence.remove(review.getId());
if (remaining.isEmpty()) return;
Transaction transfer = engine.bookNeutral(now, msg("cu"), "Charge load", confirmed, msg("fo"), null);
transfer.setActionKind(ActionKind.CHARGE_LOAD_AMBIGUOUS);
engine.add(transfer, false);
}

static boolean hasDuplicateChargeComponentIds(List<Flow> flows) {
var seen = new HashSet<Integer>();
if (flows == null) return false;
for (Flow flow : flows) {
if (flow != null && flow.quantityDelta < 0L && !seen.add(flow.itemId)) return true;
}
return false;
}

Set<Integer> pendingChargeLoadComponents(ChargeRead.Variant variant) {
var components = new LinkedHashSet<Integer>();
if (variant == null || engine.activeSession == null) return components;
for (Transaction transaction : engine.activeSession.getTransactions()) {
if (transaction == null) continue;
LoadEvidence provenance = evidence.get(transaction.getId());
if (provenance == null || !variant.name().equalsIgnoreCase(provenance.variantWireName)) continue;
boolean unresolvedReview = transaction.getAutomaticType() == UNCERTAIN && transaction.getCorrection() == AUTO;
boolean ownerCountedDecision = transaction.getCorrection() == COST || transaction.getCorrection() == REVENUE;
if (!unresolvedReview && !ownerCountedDecision) continue;
for (Flow flow : transaction.getFlows()) {
if (flow != null && flow.quantityDelta < 0L) components.add(flow.itemId);
}
}
return components;
}
}
