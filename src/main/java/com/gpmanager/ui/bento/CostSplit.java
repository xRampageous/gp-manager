package com.gpmanager.ui.bento;

import javax.annotation.Nullable;

/**
 * The Supplies / Loss split the model vouches for (SIDEBAR_BENTO.md §4): Supplies are the consumables
 * the engine evidenced as used; Loss is everything else that left the account for good. Net = Gains
 * − Loss − Supplies. Since pass 10 step 39 the split is the model's alone — the sidebar no longer
 * re-derives it from receipts; where the model cannot vouch (legacy compacted data whose split was
 * never retained) the whole cost total reads as Loss and {@link #available} is false.
 */
final class CostSplit
{
    final long supplies;
    final long loss;
    /** False when the model could not vouch for the split; the figures then show all costs as Loss. */
    final boolean available;

    private CostSplit(long supplies, long loss, boolean available)
    {
        this.supplies = supplies;
        this.loss = loss;
        this.available = available;
    }

    static CostSplit of(@Nullable com.gpmanager.model.SessionMetrics metrics)
    {
        if (metrics != null && metrics.isCostSplitAvailable())
        {
            return new CostSplit(Math.max(0L, metrics.getSuppliesCosts()), Math.max(0L, metrics.getOtherCosts()), true);
        }
        return unavailable(metrics == null ? 0L : metrics.getCosts());
    }

    /** PvP figures: the PK projection's split when vouched for. */
    static CostSplit of(@Nullable com.gpmanager.model.PkMetrics pk, long costs)
    {
        if (pk != null && pk.isCostSplitAvailable())
        {
            return new CostSplit(Math.max(0L, pk.getSuppliesCosts()), Math.max(0L, pk.getOtherCosts()), true);
        }
        return unavailable(costs);
    }

    private static CostSplit unavailable(long costs)
    {
        return new CostSplit(0L, Math.max(0L, costs), false);
    }
}
