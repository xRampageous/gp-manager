package com.gpmanager;
/** Shared accounting predicate for receipts that still need an owner decision. */
class Eh {
/**
* Only unresolved automatic classifier decisions belong in the decision inbox.
* Ledger may separately surface unknown pricing or provenance as review hints.
*/
static boolean aal(Ac transaction) {
return transaction != null
&& transaction.getCorrection() == Ah.AUTO
&& transaction.getConfidence() == Bd.UNCERTAIN;
}
}
