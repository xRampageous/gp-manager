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

Bx(Be /* type */a, long /* timestampEpochMillis */b, String /* label */c, Bd /* confidence */d,
String /* explanation */e) {
 this.id = UUID.randomUUID().toString();
 this.type = a;
 this.timestampEpochMillis = b;
 this.label = c == null || c.trim().isEmpty() ? (a == Be.DEATH ? "Player death" : "Player kill")
 : c.trim();
 this.confidence = d == null ? Bd.UNCERTAIN : d;
 this.explanation = axw(e);
}

void kg(String /* transactionId */a) {
 if (empty(a)) return;
 if (!transactionIds.contains(a)) transactionIds.add(a);
}

void afh(String /* transactionId */a) {
 transactionIds.remove(a);
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
void ahr(Ac /* transaction */a) {
 if (a == null) return;
 String /* transactionId */b = a.getId();
 if (blank(b)) return;
 financialContributions.put(b, PkMoney.of(a));
}

/** Removes one un-compacted receipt contribution, for correction undo. */
boolean afd(String /* transactionId */a) {
 if (a == null) return false;
 return financialContributions.remove(a) != null;
}

/**
* Moves one detailed receipt contribution into durable retained totals
* before its transaction is compacted. Compacted contributions are no
* longer individually undoable; callers should only compact receipts that
* have left the correction/undo window.
*/
boolean pf(String /* transactionId */a) {
 if (a == null) return false;
 PkMoney /* contribution */b = financialContributions.remove(a);
 if (b == null) return false;
 retainedFinancialNetGp = safeAdd(retainedFinancialNetGp, b.netGp);
 retainedFinancialCostsGp = safeAdd(retainedFinancialCostsGp, b.tr());
 if (b.costSplitAvailable) {
  retainedFinancialSuppliesCostsGp = safeAdd(retainedFinancialSuppliesCostsGp, b.uq());
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
 var /* total */a = new PkMoney(retainedFinancialNetGp, retainedFinancialCostsGp,
 retainedFinancialSuppliesCostsGp, financialCostSplitComplete);
 financialContributions.values().forEach(a::plus);
 return a;
}
}
