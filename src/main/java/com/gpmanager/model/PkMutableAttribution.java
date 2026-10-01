package com.gpmanager;
import java.util.*;
/**
* Bounded correction-eligible mutable attribution anchor for one encounter. Identity is
* (sessionId, encounterId); children are correction-eligible canonical receipts keyed by
* transactionId. A receipt finalization folds its child into the finalized subtotal exactly once;
* the last child finalization folds the encounter into the Session projection and removes the
* anchor. Minimal attribution state only — no labels, locations, explanations or transaction rows.
*/
class PkMutableAttribution {
String encounterId;
EncounterType type;
long finalizedNet;
long finalizedCosts;
long finalizedSupplies;
boolean finalizedCostSplitAvailable = true;
Map<String, PkMoney> children = new LinkedHashMap<>();
PkMutableAttribution() {
}

PkMutableAttribution(String encounterId, EncounterType type) {
this.encounterId = encounterId;
this.type = type == null ? EncounterType.KILL : type;
}

String getEncounterId() { return ModelText.orEmpty(encounterId); }
EncounterType getType() { return type == null ? EncounterType.KILL : type; }
/** The finalized subtotal plus every receipt still open to correction. */
PkMoney current() {
var total = new PkMoney(finalizedNet, finalizedCosts, finalizedSupplies, finalizedCostSplitAvailable);
children().values().forEach(total::plus);
return total;
}

boolean holds(String transactionId) {
return children().containsKey(transactionId);
}

boolean hasChildren() {
return !children().isEmpty();
}

void putChild(String transactionId, PkMoney money) {
if (transactionId != null && !transactionId.isEmpty()) children().put(transactionId, money);
}

boolean removeChild(String transactionId) {
return children().remove(transactionId) != null;
}

/** Folds one receipt child into the durable finalized subtotal exactly once. */
boolean finalizeChild(String transactionId) {
PkMoney child = children().remove(transactionId);
if (child == null) return false;
var finalized = new PkMoney(finalizedNet, finalizedCosts, finalizedSupplies, finalizedCostSplitAvailable).plus(child);
finalizedNet = finalized.netGp;
finalizedCosts = finalized.getCostsGp();
finalizedSupplies = finalized.getSuppliesCostsGp();
finalizedCostSplitAvailable = finalized.costSplitAvailable;
return true;
}

Map<String, PkMoney> children() {
if (children == null) children = new LinkedHashMap<>();
return children;
}
}
