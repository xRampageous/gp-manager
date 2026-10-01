package com.gpmanager;
import static com.gpmanager.TransactionType.*;
/**
* Model-layer classification for one effective counted item cost.
*
* <p>The inputs intentionally match the Ledger's item contribution boundary:
* a transaction plus one flow. This keeps classification independent of Swing
* and allows every read model to apply the same rule after compaction.</p>
*
* <p>The category is selected by typed canonical data only: the synthetic tax flow id, the
* transaction type and the observed action kind. Item names, activity labels, notes,
* explanations and owner correction reasons are presentation; renaming any of them can never
* change the booked category.</p>
*/
enum CostKind {
NONE, SUPPLIES, LOSS,
/** Trade costs are market movement and remain inside the non-supplies total. */
MARKET;
/**
* Historical synthetic GE sell-tax flow id. New booking stopped in Band 3 C2; the constant
* stays so saved history keeps its typed loss category and the Ledger tax filter still reads.
*/
static final int GE_TAX_ITEM_ID = -99502;
/**
* Classifies only an effective cost after counted/correction semantics.
* Non-cost, ignored, transfer, uncounted and deferred claim flows return
* {@link #NONE}.
*/
static CostKind of(Transaction transaction, Flow flow) {
if (transaction == null || flow == null || transaction.getActionKind() == ActionKind.DEFERRED_CLAIM) {
return NONE;
}
AccountingProjection.TransactionAmounts effective = AccountingProjection.flow(transaction, flow);
if (!effective.available || !effective.included || effective.costs <= 0L) return NONE;
if (flow.itemId == GE_TAX_ITEM_ID || transaction.getAutomaticType() == PK_FEE
|| transaction.getAutomaticType() == PK_DEATH_LOSS) {
return LOSS;
}
if (transaction.getAutomaticType() == TRADE) return MARKET;
TransactionType type = transaction.getAutomaticType();
// Supplies are consumables the player used (eat / drink / cast / fire / charges / processing
// inputs), which the engine evidences with an action kind or a supply-shaped type. A plain
// "value decreased" consumption with no evidence is an item gone, i.e. a loss.
// A production run's input is a supply even when that action made nothing (a failed smelt).
if (type == PK_SUPPLY_COST || type == PROCESSING || transaction.getActionKind() != null
|| transaction.getContext() == Context.PRODUCTION) {
return SUPPLIES;
}
return LOSS;
}
}
