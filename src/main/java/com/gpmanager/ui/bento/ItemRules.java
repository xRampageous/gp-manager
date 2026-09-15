package com.gpmanager.ui.bento;

import com.gpmanager.GpManagerConfig;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.client.config.ConfigManager;

/**
 * Reads and rewrites the two item rule settings (accounting exclusions, manual price
 * overrides) the sidebar edits, in the exact format {@code ItemRuleParser} reads.
 */
public final class ItemRules
{
    private final GpManagerConfig config;
    @Nullable
    private final ConfigManager configManager;

    public ItemRules(GpManagerConfig config, @Nullable ConfigManager configManager)
    {
        this.config = config;
        this.configManager = configManager;
    }

    public boolean canWrite()
    {
        return configManager != null;
    }

    public Set<Integer> excluded()
    {
        Set<Integer> ids = new LinkedHashSet<>();
        String value = config.ignoredItemIds();
        if (value != null)
        {
            for (String token : value.split(","))
            {
                try
                {
                    int id = Integer.parseInt(token.trim());
                    if (id >= 0)
                    {
                        ids.add(id);
                    }
                }
                catch (NumberFormatException ignored)
                {
                    // keep the rest
                }
            }
        }
        return ids;
    }

    public boolean isExcluded(int itemId)
    {
        return excluded().contains(itemId);
    }

    public void setExcluded(int itemId, boolean excluded)
    {
        Set<Integer> ids = excluded();
        if (excluded)
        {
            ids.add(itemId);
        }
        else
        {
            ids.remove(itemId);
        }
        write("ignoredItemIds", join(ids));
    }

    public Map<Integer, Integer> overrides()
    {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        String value = config.manualPriceOverrides();
        if (value != null)
        {
            for (String token : value.split(","))
            {
                String[] parts = token.trim().split("=", 2);
                if (parts.length != 2)
                {
                    continue;
                }
                try
                {
                    int id = Integer.parseInt(parts[0].trim());
                    int price = Integer.parseInt(parts[1].trim());
                    if (id >= 0 && price >= 0)
                    {
                        map.put(id, price);
                    }
                }
                catch (NumberFormatException ignored)
                {
                    // keep the rest
                }
            }
        }
        return map;
    }

    @Nullable
    public Integer override(int itemId)
    {
        return overrides().get(itemId);
    }

    public void setOverride(int itemId, @Nullable Integer price)
    {
        Map<Integer, Integer> map = overrides();
        if (price == null || price < 0)
        {
            map.remove(itemId);
        }
        else
        {
            map.put(itemId, price);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : map.entrySet())
        {
            if (sb.length() > 0)
            {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        write("manualPriceOverrides", sb.toString());
    }

    private void write(String key, String value)
    {
        if (configManager != null)
        {
            configManager.setConfiguration(GpManagerConfig.GROUP, key, value);
        }
    }

    private static String join(Set<Integer> ids)
    {
        StringBuilder sb = new StringBuilder();
        for (Integer id : ids)
        {
            if (sb.length() > 0)
            {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }
}
