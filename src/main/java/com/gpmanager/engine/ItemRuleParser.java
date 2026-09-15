package com.gpmanager.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class ItemRuleParser
{
    private ItemRuleParser()
    {
    }

    static Set<Integer> parseIgnoredIds(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return Collections.emptySet();
        }

        Set<Integer> result = new HashSet<>();
        for (String token : value.split(","))
        {
            try
            {
                int itemId = Integer.parseInt(token.trim());
                if (itemId >= 0)
                {
                    result.add(itemId);
                }
            }
            catch (NumberFormatException ignored)
            {
                // Invalid entries are ignored so a typo cannot break tracking.
            }
        }
        return result;
    }

    static Map<Integer, Integer> parsePriceOverrides(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return Collections.emptyMap();
        }

        Map<Integer, Integer> result = new HashMap<>();
        for (String token : value.split(","))
        {
            String[] parts = token.trim().split("=", 2);
            if (parts.length != 2)
            {
                continue;
            }
            try
            {
                int itemId = Integer.parseInt(parts[0].trim());
                int price = Integer.parseInt(parts[1].trim());
                if (itemId >= 0 && price >= 0)
                {
                    result.put(itemId, price);
                }
            }
            catch (NumberFormatException ignored)
            {
                // Invalid entries are ignored so a typo cannot break tracking.
            }
        }
        return result;
    }
}
