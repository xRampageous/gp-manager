package com.gpmanager.model;

/** Shared accounting predicate for receipts that still need an owner decision. */
public final class ReviewEligibility
{
    private ReviewEligibility()
    {
    }

    /**
     * Only unresolved automatic classifier decisions belong in the decision inbox.
     * Ledger may separately surface unknown pricing or provenance as review hints.
     */
    public static boolean needsOwnerDecision(ProfitTransaction transaction)
    {
        return transaction != null
            && transaction.getCorrection() == TransactionCorrection.AUTO
            && transaction.getConfidence() == ClassificationConfidence.UNCERTAIN;
    }
}
