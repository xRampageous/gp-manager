package com.gpmanager;
import java.util.*;
import static com.gpmanager.ModelText.*;
import static com.gpmanager.SafeMath.safeAdd;
class PkEncounter {
String id;
EncounterType type;
long timestampEpochMillis;
String label;
/** Nullable region/zone text for presentation only; never an accounting input. */
ClassificationConfidence confidence;
String explanation;
List<String> transactionIds = new ArrayList<>();
/** Contributions still backed by transaction detail, indexed for correction and undo. */
Map<String, PkMoney> financialContributions = new LinkedHashMap<>();
/** Contributions whose transaction detail has been compacted. */
long retainedFinancialNetGp;
long retainedFinancialCostsGp;
long retainedFinancialSuppliesCostsGp;
boolean financialCostSplitComplete = true;
/** Profile-wide monotonic detail-retention position; -1 until explicitly assigned. */
long completionSequence = -1L;
PkEncounter() {
 // Gson
}

PkEncounter(EncounterType type, long timestampEpochMillis, String label, ClassificationConfidence confidence,
String explanation) {
 this.id = UUID.randomUUID().toString();
 this.type = type;
 this.timestampEpochMillis = timestampEpochMillis;
 this.label = label == null || label.trim().isEmpty() ? (type == EncounterType.DEATH ? "Player death" : "Player kill")
 : label.trim();
 this.confidence = confidence == null ? ClassificationConfidence.UNCERTAIN : confidence;
 this.explanation = orEmpty(explanation);
}

void addTransactionId(String transactionId) {
 if (empty(transactionId)) return;
 if (!transactionIds.contains(transactionId)) transactionIds.add(transactionId);
}

void removeTransactionId(String transactionId) {
 transactionIds.remove(transactionId);
}

String getId() {
 if (empty(id)) id = UUID.randomUUID().toString();
 return id;
}

EncounterType getType() { return type == null ? EncounterType.KILL : type; }
ClassificationConfidence getConfidence() {
 return confidence == null ? ClassificationConfidence.UNCERTAIN : confidence;
}

String getExplanation() { return orEmpty(explanation); }
List<String> getTransactionIds() {
 return Collections.unmodifiableList(transactionIds);
}

/**
* Replaces this transaction's live financial snapshot using the shared,
* correction-aware accounting projection. Repeated observations of the
* same transaction id therefore update rather than double count it.
*/
void setFinancialContribution(Transaction transaction) {
 if (transaction == null) return;
 String transactionId = transaction.getId();
 if (blank(transactionId)) return;
 financialContributions.put(transactionId, PkMoney.of(transaction));
}

/** Removes one un-compacted receipt contribution, for correction undo. */
boolean removeFinancialContribution(String transactionId) {
 if (transactionId == null) return false;
 return financialContributions.remove(transactionId) != null;
}

/**
* Moves one detailed receipt contribution into durable retained totals
* before its transaction is compacted. Compacted contributions are no
* longer individually undoable; callers should only compact receipts that
* have left the correction/undo window.
*/
boolean compactFinancialContribution(String transactionId) {
 if (transactionId == null) return false;
 PkMoney contribution = financialContributions.remove(transactionId);
 if (contribution == null) return false;
 retainedFinancialNetGp = safeAdd(retainedFinancialNetGp, contribution.netGp);
 retainedFinancialCostsGp = safeAdd(retainedFinancialCostsGp, contribution.getCostsGp());
 if (contribution.costSplitAvailable) {
  retainedFinancialSuppliesCostsGp = safeAdd(retainedFinancialSuppliesCostsGp, contribution.getSuppliesCostsGp());
 } else {
  financialCostSplitComplete = false;
 }
 return true;
}

/** Net GP across retained and per-transaction contributions. */
long getFinancialNetGp() {
 return total().netGp;
}

/** Encounter loss follows the existing PK metric definition: max(0, costs - revenue). */
long getFinancialLossGp() {
 return total().getLossGp();
}

PkMoney total() {
 var total = new PkMoney(retainedFinancialNetGp, retainedFinancialCostsGp,
 retainedFinancialSuppliesCostsGp, financialCostSplitComplete);
 financialContributions.values().forEach(total::plus);
 return total;
}
}
