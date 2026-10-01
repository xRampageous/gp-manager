package com.gpmanager;
/** Shared accounting predicate for receipts that still need an owner decision. */
class ReviewEligibility {
/**
* Only unresolved automatic classifier decisions belong in the decision inbox.
* Ledger may separately surface unknown pricing or provenance as review hints.
*/
static boolean needsOwnerDecision(Transaction transaction) {
 return transaction != null && transaction.getCorrection() == Correction.AUTO
 && transaction.getConfidence() == ClassificationConfidence.UNCERTAIN;
}
}
