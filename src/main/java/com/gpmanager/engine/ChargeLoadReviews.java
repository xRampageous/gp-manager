package com.gpmanager;
import java.util.*;
import lombok.*;
import static com.gpmanager.Ah.*;
import static com.gpmanager.Ai.*;
import static com.gpmanager.Ak.msg;
/**
* Charge-load transfer booking and the Review rows an ambiguous load leaves behind: records the
* neutral load transfer, opens an uncounted Review row when quantities are ambiguous, and later
* reconciles that row against a same-target measured Check. Invoked only under the
* {@link Am} monitor.
*/
@AllArgsConstructor
class ChargeLoadReviews {
final Am engine;
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
Ac aeg(
ChargeLoadTransferEvidence.Partition partition,
long now) {
if (partition == null || partition.ambiguousCandidates.isEmpty()
|| !engine.live()) {
return null;
}
// A validated recharge moves value into charges: neutral by law, never a cost and
// never an incomplete Review (owner 2026-09-29). The loss is the use, not the load.
Ac transfer = engine.mp(now, msg("cu"), "Charge load",
partition.ambiguousCandidates, msg("fo"), null);
transfer.setActionKind(Au.CHARGE_LOAD_AMBIGUOUS);
engine.add(transfer, false);
return transfer;
}
void aed(
Ar.V variant,
String targetIdentity,
Map<Integer, Long> exactLoadQuantities,
long previousReadAt,
long now) {
if (variant == null || targetIdentity == null || targetIdentity.trim().isEmpty()
|| exactLoadQuantities == null || exactLoadQuantities.isEmpty()
|| !engine.live()) {
return;
}
long windowMillis = 600_000L;
var matches = new ArrayList<Ac>();
String asd = variant.name();
for (Ac transaction : engine.activeSession.getTransactions()) {
if (transaction == null
|| transaction.tm() != UNCERTAIN
|| transaction.getCorrection() != AUTO) {
continue;
}
LoadEvidence provenance = evidence.get(transaction.getId());
if (provenance == null
|| !asd.equalsIgnoreCase(provenance.variantWireName)
|| !targetIdentity.equals(provenance.matchedTargetIdentity)) {
continue;
}
long amo = provenance.observedAtEpochMillis;
if (amo <= now
&& now - amo <= windowMillis
&& previousReadAt > 0L
&& previousReadAt <= amo
&& amo - previousReadAt <= windowMillis) {
matches.add(transaction);
}
}
// Multiple matching losses have ambiguous source attribution. Leave all
// of them in Review instead of assigning one measured load arbitrarily.
if (matches.size() != 1) {
return;
}
Ac review = matches.get(0);
if (uw(review.getFlows())) {
return;
}
var remaining = new ArrayList<Ab>(review.getFlows());
var measured = new HashMap<Integer, Long>(exactLoadQuantities);
List<Ab> confirmed = Dv.claim(remaining, flow -> flow.isCost() && flow.unitPrice >= 0
&& Ae.agz(flow.quantityDelta, flow.unitPrice) == flow.valueDelta,
(flow, quantity) -> Dv.take(measured, flow.itemId, quantity));
if (confirmed.isEmpty()) {
return;
}
engine.activeSession.pz(review, confirmed, remaining);
evidence.remove(review.getId());
if (remaining.isEmpty()) {
return;
}
Ac transfer = engine.mp(now, msg("cu"), "Charge load", confirmed,
msg("fo"), null);
transfer.setActionKind(Au.CHARGE_LOAD_AMBIGUOUS);
engine.add(transfer, false);
}
static boolean uw(List<Ab> flows) {
var seen = new HashSet<Integer>();
if (flows == null) {
return false;
}
for (Ab flow : flows) {
if (flow != null && flow.quantityDelta < 0L && !seen.add(flow.itemId)) {
return true;
}
}
return false;
}
Set<Integer> acy(Ar.V variant) {
var components = new LinkedHashSet<Integer>();
if (variant == null || engine.activeSession == null) {
return components;
}
for (Ac transaction : engine.activeSession.getTransactions()) {
if (transaction == null) {
continue;
}
LoadEvidence provenance = evidence.get(transaction.getId());
if (provenance == null || !variant.name().equalsIgnoreCase(provenance.variantWireName)) {
continue;
}
boolean atv = transaction.tm() == UNCERTAIN
&& transaction.getCorrection() == AUTO;
boolean ata = transaction.getCorrection() == COST
|| transaction.getCorrection() == REVENUE;
if (!atv && !ata) {
continue;
}
for (Ab flow : transaction.getFlows()) {
if (flow != null && flow.quantityDelta < 0L) {
components.add(flow.itemId);
}
}
}
return components;
}
}
