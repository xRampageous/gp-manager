package com.gpmanager;
import java.util.*;
import static com.gpmanager.Ag.*;
import static com.gpmanager.Ae.safeAdd;
class Bx {
String id;
Be type;
long timestampEpochMillis;
String label;
/** Nullable region/zone text for presentation only; never an accounting input. */
Bd confidence;
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
Bx() {
 // Gson
}

Bx(Be type, long timestampEpochMillis, String label, Bd confidence,
String explanation) {
 this.id = UUID.randomUUID().toString();
 this.type = type;
 this.timestampEpochMillis = timestampEpochMillis;
 this.label = label == null || label.trim().isEmpty() ? (type == Be.DEATH ? "Player death" : "Player kill")
 : label.trim();
 this.confidence = confidence == null ? Bd.UNCERTAIN : confidence;
 this.explanation = axw(explanation);
}

void kg(String transactionId) {
 if (empty(transactionId)) return;
 if (!transactionIds.contains(transactionId)) transactionIds.add(transactionId);
}

void afh(String transactionId) {
 transactionIds.remove(transactionId);
}

String getId() {
 if (empty(id)) id = UUID.randomUUID().toString();
 return id;
}

Be getType() { return type == null ? Be.KILL : type; }
Bd getConfidence() {
 return confidence == null ? Bd.UNCERTAIN : confidence;
}

String getExplanation() { return axw(explanation); }
List<String> getTransactionIds() {
 return Collections.unmodifiableList(transactionIds);
}

/**
* Replaces this transaction's live financial snapshot using the shared,
* correction-aware accounting projection. Repeated observations of the
* same transaction id therefore update rather than double count it.
*/
void ahr(Ac transaction) {
 if (transaction == null) return;
 String transactionId = transaction.getId();
 if (blank(transactionId)) return;
 financialContributions.put(transactionId, PkMoney.of(transaction));
}

/** Removes one un-compacted receipt contribution, for correction undo. */
boolean afd(String transactionId) {
 if (transactionId == null) return false;
 return financialContributions.remove(transactionId) != null;
}

/**
* Moves one detailed receipt contribution into durable retained totals
* before its transaction is compacted. Compacted contributions are no
* longer individually undoable; callers should only compact receipts that
* have left the correction/undo window.
*/
boolean pf(String transactionId) {
 if (transactionId == null) return false;
 PkMoney contribution = financialContributions.remove(transactionId);
 if (contribution == null) return false;
 retainedFinancialNetGp = safeAdd(retainedFinancialNetGp, contribution.netGp);
 retainedFinancialCostsGp = safeAdd(retainedFinancialCostsGp, contribution.tr());
 if (contribution.costSplitAvailable) {
  retainedFinancialSuppliesCostsGp = safeAdd(retainedFinancialSuppliesCostsGp, contribution.uq());
 } else {
  financialCostSplitComplete = false;
 }
 return true;
}

/** Net GP across retained and per-transaction contributions. */
long ur() {
 return total().netGp;
}

/** Encounter loss follows the existing PK metric definition: max(0, costs - revenue). */
long uk() {
 return total().auk();
}

PkMoney total() {
 var total = new PkMoney(retainedFinancialNetGp, retainedFinancialCostsGp,
 retainedFinancialSuppliesCostsGp, financialCostSplitComplete);
 financialContributions.values().forEach(total::plus);
 return total;
}
}
