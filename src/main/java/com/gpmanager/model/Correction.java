package com.gpmanager;

import static com.gpmanager.Correction.*;
import lombok.AllArgsConstructor;

enum Correction {
    AUTO,
    REVENUE,
    COST,
    TRANSFER,
    IGNORE
}

enum Confidence {
    CONFIRMED,
    LIKELY,
    UNCERTAIN
}

/** A deliberate correction choice for an unresolved transaction. */
@AllArgsConstructor
enum Decision {
    GAIN(REVENUE),
    COST(Correction.COST),
    TRANSFER(Correction.TRANSFER),
    IGNORE(Correction.IGNORE);
    Correction correction;

    Correction toCorrection() {
        return correction;
    }
}

/** Shared accounting predicate for receipts that still need an owner decision. */
class ReviewRules {
    /**
     * Only unresolved automatic classifier decisions belong in the decision inbox. Ledger may
     * separately surface unknown pricing or provenance as review hints.
     */
    static boolean needsOwnerDecision(Transaction transaction) {
        return transaction != null
                && transaction.getCorrection() == Correction.AUTO
                && transaction.getConfidence() == Confidence.UNCERTAIN;
    }
}
