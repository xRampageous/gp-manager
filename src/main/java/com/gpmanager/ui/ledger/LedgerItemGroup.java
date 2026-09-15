package com.gpmanager.ui.ledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Aggregated By-item presentation group. Identity is the group contract key,
 * not the newest transaction. Values are summed contribution amounts — never
 * the parent transaction net applied per item.
 */
public final class LedgerItemGroup
{
    private final String groupId;
    private final List<LedgerItemContribution> contributions;
    private final long receivedQuantity;
    private final long usedLostQuantity;
    private final long totalValue;
    private final boolean anyUnpriced;
    private final boolean multiItemParents;

    public LedgerItemGroup(String groupId, List<LedgerItemContribution> contributions)
    {
        this.groupId = groupId == null ? "" : groupId;
        List<LedgerItemContribution> copy = contributions == null
            ? Collections.emptyList()
            : new ArrayList<>(contributions);
        copy.sort((a, b) ->
        {
            int newest = Long.compare(b.getTimestampEpochMillis(), a.getTimestampEpochMillis());
            if (newest != 0)
            {
                return newest;
            }
            return a.getContributionId().compareTo(b.getContributionId());
        });
        this.contributions = Collections.unmodifiableList(copy);
        long received = 0L;
        long usedLost = 0L;
        long value = 0L;
        boolean unpriced = false;
        boolean multi = false;
        for (LedgerItemContribution c : this.contributions)
        {
            if (c.getQuantityDelta() > 0L)
            {
                received += c.getQuantityDelta();
            }
            else if (c.getQuantityDelta() < 0L)
            {
                usedLost += Math.abs(c.getQuantityDelta());
            }
            if (c.isUnpriced())
            {
                unpriced = true;
            }
            else
            {
                value += c.getValueDelta();
            }
            if (c.getFlowCountInTransaction() > 1)
            {
                multi = true;
            }
        }
        this.receivedQuantity = received;
        this.usedLostQuantity = usedLost;
        this.totalValue = value;
        this.anyUnpriced = unpriced;
        this.multiItemParents = multi;
    }

    public String getGroupId()
    {
        return groupId;
    }

    public List<LedgerItemContribution> getContributions()
    {
        return contributions;
    }

    public LedgerItemContribution newest()
    {
        return contributions.isEmpty() ? null : contributions.get(0);
    }

    public String displayName()
    {
        LedgerItemContribution newest = newest();
        if (newest == null)
        {
            return "Item";
        }
        String name = newest.getItemName();
        String qty = quantitySummary();
        return qty.isEmpty() ? name : name + " " + qty;
    }

    /** Received and used/lost shown separately — never abs-summed into one ×N. */
    public String quantitySummary()
    {
        if (receivedQuantity > 0L && usedLostQuantity > 0L)
        {
            return "+" + receivedQuantity + " / −" + usedLostQuantity;
        }
        if (receivedQuantity > 0L)
        {
            return "×" + receivedQuantity;
        }
        if (usedLostQuantity > 0L)
        {
            return "−" + usedLostQuantity;
        }
        return "";
    }

    public long getTotalQuantityAbs()
    {
        return receivedQuantity + usedLostQuantity;
    }

    public long getReceivedQuantity()
    {
        return receivedQuantity;
    }

    public long getUsedLostQuantity()
    {
        return usedLostQuantity;
    }

    public long getTotalValue()
    {
        return totalValue;
    }

    public boolean isAnyUnpriced()
    {
        return anyUnpriced;
    }

    public boolean hasMultiItemParents()
    {
        return multiItemParents;
    }

    public int contributionCount()
    {
        return contributions.size();
    }

    public Set<String> uniqueTransactionIds()
    {
        Set<String> ids = new LinkedHashSet<>();
        for (LedgerItemContribution c : contributions)
        {
            if (!c.getTransactionId().isEmpty())
            {
                ids.add(c.getTransactionId());
            }
        }
        return ids;
    }

    /** Other item names present on multi-item parents (excluding this group's item). */
    public Set<String> otherItemNamesOnParents()
    {
        Set<String> names = new LinkedHashSet<>();
        int itemId = newest() == null ? 0 : newest().getItemId();
        for (LedgerItemContribution c : contributions)
        {
            if (c.getFlowCountInTransaction() <= 1)
            {
                continue;
            }
            // Contribution itself is this item; callers may enrich from full txn.
            if (c.getItemId() != itemId)
            {
                names.add(c.getItemName());
            }
        }
        return names;
    }

    public long newestTimestamp()
    {
        return newest() == null ? 0L : newest().getTimestampEpochMillis();
    }
}
