package com.gpmanager.model;

import java.util.Locale;

/**
 * Model-layer classification for one effective counted item cost.
 *
 * <p>The inputs intentionally match the Ledger's item contribution boundary:
 * a transaction plus one flow. This keeps classification independent of Swing
 * and allows every read model to apply the same rule after compaction.</p>
 */
public enum CostKind
{
    NONE,
    SUPPLIES,
    LOSS,
    /** Trade costs are market movement and remain inside the non-supplies total. */
    MARKET;

    /** Stable synthetic id used by GE tax booking and the Ledger tax filter. */
    public static final int GE_TAX_ITEM_ID = -99502;

    /**
     * Classifies only an effective cost after counted/correction semantics.
     * Non-cost, ignored, transfer, uncounted and deferred claim flows return
     * {@link #NONE}.
     */
    public static CostKind of(ProfitTransaction transaction, ItemFlow flow)
    {
        if (transaction == null || flow == null
            || transaction.getActionKind() == ActionKind.DEFERRED_CLAIM)
        {
            return NONE;
        }

        AccountingProjection.TransactionAmounts effective =
            AccountingProjection.flow(transaction, flow, null);
        if (!effective.isAvailable() || !effective.isIncluded() || effective.getCosts() <= 0L)
        {
            return NONE;
        }

        if (isTax(transaction, flow)
            || transaction.getAutomaticType() == TransactionType.PK_FEE
            || transaction.getAutomaticType() == TransactionType.PK_DEATH_LOSS
            || isDeathReclaim(transaction))
        {
            return LOSS;
        }

        if (transaction.getAutomaticType() == TransactionType.TRADE)
        {
            return MARKET;
        }

        TransactionType type = transaction.getAutomaticType();
        // Supplies are consumables the player used (eat / drink / cast / fire / charges / processing
        // inputs), which the engine evidences with an action kind or a supply-shaped type. A plain
        // "value decreased" consumption with no evidence is an item gone, i.e. a loss.
        if (type == TransactionType.PK_SUPPLY_COST
            || type == TransactionType.PROCESSING
            || transaction.getActionKind() != null)
        {
            return SUPPLIES;
        }
        return LOSS;
    }

    private static boolean isTax(ProfitTransaction transaction, ItemFlow flow)
    {
        if (flow.getItemId() == GE_TAX_ITEM_ID)
        {
            return true;
        }
        return containsTaxText(transaction.getExplanation())
            || containsTaxText(transaction.getCorrectionReason());
    }

    private static boolean containsTaxText(String value)
    {
        return value != null && value.toLowerCase(Locale.ROOT).contains("ge tax");
    }

    private static boolean isDeathReclaim(ProfitTransaction transaction)
    {
        String activity = transaction.getActivityName();
        return activity != null && "death reclaim".equalsIgnoreCase(activity.trim());
    }
}
