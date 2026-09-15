package com.gpmanager.ui.ledger;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Builds By-item contribution groups from retained transactions without
 * rewriting accounting rows.
 */
public final class LedgerItemGrouping
{
    private LedgerItemGrouping()
    {
    }

    public static List<LedgerItemContribution> extractContributions(List<ProfitTransaction> transactions)
    {
        return extractContributions(transactions, false);
    }

    /**
     * @param includeTransfers when true, ownership-neutral TRANSFER rows are included
     *                         (Ledger Transfers chip). Default extract still skips them.
     */
    public static List<LedgerItemContribution> extractContributions(
        List<ProfitTransaction> transactions,
        boolean includeTransfers)
    {
        List<LedgerItemContribution> result = new ArrayList<>();
        if (transactions == null)
        {
            return result;
        }
        for (ProfitTransaction transaction : transactions)
        {
            if (transaction == null || transaction.getFlows() == null)
            {
                continue;
            }
            if (transaction.getType() == com.gpmanager.model.TransactionType.TRANSFER
                && !includeTransfers)
            {
                continue;
            }
            List<ItemFlow> flows = transaction.getFlows();
            for (int i = 0; i < flows.size(); i++)
            {
                ItemFlow flow = flows.get(i);
                if (flow == null || flow.getQuantityDelta() == 0L)
                {
                    continue;
                }
                LedgerItemContribution contribution = LedgerItemContribution.from(transaction, flow, i);
                if (contribution != null)
                {
                    result.add(contribution);
                }
            }
        }
        return result;
    }

    public static List<LedgerItemGroup> group(
        List<ProfitTransaction> transactions,
        Predicate<LedgerItemContribution> contributionFilter)
    {
        return group(transactions, contributionFilter, false);
    }

    public static List<LedgerItemGroup> group(
        List<ProfitTransaction> transactions,
        Predicate<LedgerItemContribution> contributionFilter,
        boolean includeTransfers)
    {
        List<LedgerItemContribution> contributions = extractContributions(transactions, includeTransfers);
        Map<String, List<LedgerItemContribution>> byKey = new LinkedHashMap<>();
        for (LedgerItemContribution contribution : contributions)
        {
            if (contributionFilter != null && !contributionFilter.test(contribution))
            {
                continue;
            }
            byKey.computeIfAbsent(contribution.groupKey(), ignored -> new ArrayList<>()).add(contribution);
        }
        List<LedgerItemGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<LedgerItemContribution>> entry : byKey.entrySet())
        {
            groups.add(new LedgerItemGroup(entry.getKey(), entry.getValue()));
        }
        groups.sort((left, right) ->
        {
            int name = left.displayName().compareToIgnoreCase(right.displayName());
            if (name != 0)
            {
                return name;
            }
            int value = Long.compare(right.getTotalValue(), left.getTotalValue());
            if (value != 0)
            {
                return value;
            }
            return left.getGroupId().compareTo(right.getGroupId());
        });
        return groups;
    }

    public static boolean matchesGainFilter(LedgerItemContribution contribution)
    {
        return contribution != null && contribution.isCounted() && contribution.getValueDelta() > 0L;
    }

    public static boolean matchesCostFilter(LedgerItemContribution contribution)
    {
        return contribution != null && contribution.isCounted() && contribution.getValueDelta() < 0L;
    }

    public static boolean matchesReviewFilter(LedgerItemContribution contribution)
    {
        return contribution != null && contribution.isNeedsReview();
    }

    public static boolean matchesTransferFilter(LedgerItemContribution contribution)
    {
        return contribution != null
            && "TRANSFER".equalsIgnoreCase(contribution.getTypeName());
    }

    public static boolean matchesTaxFilter(LedgerItemContribution contribution)
    {
        if (contribution == null)
        {
            return false;
        }
        if (contribution.getItemId() == com.gpmanager.engine.GeSellTaxBooking.TAX_ITEM_ID)
        {
            return true;
        }
        String explanation = contribution.getExplanation();
        return explanation != null && explanation.contains("GE tax");
    }

    public static boolean matchesChargeFilter(LedgerItemContribution contribution)
    {
        if (contribution == null)
        {
            return false;
        }
        String explanation = contribution.getExplanation() == null
            ? "" : contribution.getExplanation().toLowerCase();
        String name = contribution.getItemName() == null
            ? "" : contribution.getItemName().toLowerCase();
        return explanation.contains("charge") || explanation.contains("calibrat")
            || name.contains("charge");
    }

    public static boolean matchesSplitFilter(LedgerItemContribution contribution)
    {
        if (contribution == null)
        {
            return false;
        }
        return com.gpmanager.model.ItemSplitAccounting.isSplitReason(contribution.getExplanation())
            || com.gpmanager.model.ItemSplitAccounting.isSplitReason(contribution.getCorrectionReason());
    }
}
