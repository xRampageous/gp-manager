package com.gpmanager;
import java.util.*;
import static com.gpmanager.SafeMath.nonNeg;
/**
* Persisted schema-106 tracked-basis continuity: the minimum state a pooled weighted-average
* double-count-prevention memory needs across restart.
*
* <p>{@code basisEpochMillis} is the migration boundary: acquisitions booked before it are
* untracked by law (no backfill, no bank-price inference), and basis-affecting corrections of
* those rows are fenced. Open SELL reservation shares live on their durable custody records; the
* ledger rebinds the pooled reserved totals from them on restore.</p>
*/
class TrackedBasisState {
long basisEpochMillis;
List<BasisPool> pools = new ArrayList<>();
long getBasisEpochMillis() { return nonNeg(basisEpochMillis); }
/** Never null: a pre-106 file upgrades with an empty pool. */
List<BasisPool> getPools() {
if (pools == null) pools = new ArrayList<>();
return pools;
}
}
