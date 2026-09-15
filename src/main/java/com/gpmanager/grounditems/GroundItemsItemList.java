package com.gpmanager.grounditems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;

/**
 * Highlighted/hidden list matcher matching Ground Items {@code ItemList}:
 * exact name matches beat wildcards; quantity thresholds apply to both.
 */
final class GroundItemsItemList
{
    static final int NONE = 0;
    static final int WILDCARD = 1;
    static final int EXACT = 2;

    private final List<GroundItemsItemThreshold> items;

    GroundItemsItemList(String csv)
    {
        List<GroundItemsItemThreshold> parsed = new ArrayList<>();
        for (String entry : Text.fromCSV(csv == null ? "" : csv))
        {
            GroundItemsItemThreshold threshold = GroundItemsItemThreshold.fromName(entry);
            if (threshold != null)
            {
                parsed.add(threshold);
            }
        }
        this.items = Collections.unmodifiableList(parsed);
    }

    int matches(String itemName, int quantity)
    {
        String name = itemName == null ? "" : itemName;
        for (GroundItemsItemThreshold it : items)
        {
            if (!it.isWildcard()
                && it.getName().equalsIgnoreCase(name)
                && it.quantityHolds(quantity))
            {
                return EXACT;
            }
        }

        for (GroundItemsItemThreshold it : items)
        {
            if (it.isWildcard()
                && WildcardMatcher.matches(it.getName(), name)
                && it.quantityHolds(quantity))
            {
                return WILDCARD;
            }
        }

        return NONE;
    }

    int matchesUnconditionally(String itemName)
    {
        String name = itemName == null ? "" : itemName;
        for (GroundItemsItemThreshold item : items)
        {
            if (!item.isWildcard() && item.isUnconditional()
                && item.getName().equalsIgnoreCase(name))
            {
                return EXACT;
            }
        }
        for (GroundItemsItemThreshold item : items)
        {
            if (item.isWildcard() && item.isUnconditional()
                && WildcardMatcher.matches(item.getName(), name))
            {
                return WILDCARD;
            }
        }
        return NONE;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof GroundItemsItemList))
        {
            return false;
        }
        GroundItemsItemList that = (GroundItemsItemList) o;
        return Objects.equals(items, that.items);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(items);
    }
}
