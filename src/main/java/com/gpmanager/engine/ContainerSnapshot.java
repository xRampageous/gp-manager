package com.gpmanager.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ContainerSnapshot
{
    private final Map<Integer, Long> quantities;

    public ContainerSnapshot(Map<Integer, Long> quantities)
    {
        this.quantities = Collections.unmodifiableMap(new HashMap<>(quantities));
    }

    public static ContainerSnapshot empty()
    {
        return new ContainerSnapshot(Collections.emptyMap());
    }

    public Map<Integer, Long> diff(ContainerSnapshot previous)
    {
        Map<Integer, Long> result = new HashMap<>();
        Set<Integer> itemIds = new HashSet<>(quantities.keySet());
        itemIds.addAll(previous.quantities.keySet());

        for (Integer itemId : itemIds)
        {
            long currentQuantity = quantities.getOrDefault(itemId, 0L);
            long previousQuantity = previous.quantities.getOrDefault(itemId, 0L);
            long delta = currentQuantity - previousQuantity;
            if (delta != 0L)
            {
                result.put(itemId, delta);
            }
        }

        return result;
    }

    public Map<Integer, Long> getQuantities()
    {
        return quantities;
    }

    /** Quantity of one item id in this snapshot (0 when absent). */
    public long quantityOf(int itemId)
    {
        return quantities.getOrDefault(itemId, 0L);
    }

    @Override
    public boolean equals(Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof ContainerSnapshot))
        {
            return false;
        }
        ContainerSnapshot that = (ContainerSnapshot) other;
        return quantities.equals(that.quantities);
    }

    @Override
    public int hashCode()
    {
        return quantities.hashCode();
    }
}
