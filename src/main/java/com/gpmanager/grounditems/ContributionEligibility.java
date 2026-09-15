package com.gpmanager.grounditems;

import com.gpmanager.LootPresentationFilter;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import java.util.function.BiPredicate;
import javax.annotation.Nullable;

/**
 * The accounting half of the shared item-visibility contract.
 *
 * <p><b>Accounting vs. presentation — these are deliberately two different
 * rules, both resolved by {@link LootPresentationFilterService}:</b>
 * <ul>
 *   <li><b>Presentation</b> ({@code lootPresentationFilter} /
 *       {@link LootPresentationFilterService#filterReward}) only controls what
 *       reward trays, floating drops, and Ground Items highlighting show.
     *       It never changes revenue, costs, net, rate, or target progress. See
     *       {@code LootFilterAccountingIsolationTest} in the test sources.</li>
 *   <li><b>Accounting eligibility</b> (this class, backed by the advanced
 *       {@code accountingItemFilter} setting) is the ONLY item-visibility rule
 *       allowed to change what counts. When active, it is applied uniformly
 *       to every calculated surface — HUD/HUD+/Infobox metrics, the Ledger,
 *       session history, Insights, CSV exports, and Party totals — by routing
 *       all of them through {@code GpManagerEngine#getMetrics},
 *       {@code GpManagerEngine#filteredMetrics}, or
 *       {@code GpManagerEngine#activeContributionEligibility()}. Never wire a
 *       new display/export path directly to {@code lootPresentationFilter}
 *       for accounting purposes — reuse one of those engine entry points
 *       instead so accounting and presentation cannot drift apart.</li>
 * </ul>
 *
 * <p><b>Historical compacted totals:</b> {@code ProfitSession} compacts old
 * transactions into aggregate revenue/cost totals plus a per-item
 * {@code RetainedItemContribution} breakdown (see
 * {@code ProfitSession#compactTransaction}). This class's predicate is applied
 * against that retained per-item breakdown too, so turning on an accounting
 * filter after compaction has already happened still excludes the right
 * items — it must never silently re-include (or zero out) a compacted
 * total just because per-flow detail is gone. See
 * {@code FollowupBoundaryReviewTest#compactionMustNotReintroduceHiddenItemProfit}.
 *
 * <p>See {@code docs/SETTINGS_MAPPING.md} for the full settings-to-runtime
 * mapping of {@code accountingItemFilter} vs {@code lootPresentationFilter}.
 */
public final class ContributionEligibility implements BiPredicate<ProfitTransaction, ItemFlow>
{
    private final LootPresentationFilterService filterService;
    private final LootPresentationFilter mode;

    private ContributionEligibility(LootPresentationFilterService filterService, LootPresentationFilter mode)
    {
        this.filterService = filterService;
        this.mode = mode;
    }

    /**
     * Returns an eligibility predicate for the given advanced accounting
     * filter mode, or {@code null} when no filter should be applied (mode is
     * {@link LootPresentationFilter#ALL_ITEMS}, unset, or the resolver
     * service is unavailable). Callers should treat a {@code null} result the
     * same as "count everything" rather than constructing a permissive
     * predicate, so unfiltered call sites keep using the cheaper legacy
     * aggregate-total path (see {@code ProfitSession#metrics}).
     */
    @Nullable
    public static ContributionEligibility forAccounting(
        @Nullable LootPresentationFilterService filterService,
        @Nullable LootPresentationFilter mode)
    {
        if (filterService == null || mode == null || mode == LootPresentationFilter.ALL_ITEMS)
        {
            return null;
        }
        return new ContributionEligibility(filterService, mode);
    }

    /**
     * True if {@code flow} should be counted toward revenue/costs/net under
     * the active accounting filter. {@code transaction} is accepted only to
     * satisfy {@link BiPredicate}'s shape used across the codebase (manual
     * correction/undo call sites key off the transaction too); this
     * eligibility decision itself is purely per-item.
     */
    @Override
    public boolean test(@Nullable ProfitTransaction transaction, @Nullable ItemFlow flow)
    {
        return filterService.isFlowIncluded(flow, mode);
    }
}
