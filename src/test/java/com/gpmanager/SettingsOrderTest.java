package com.gpmanager;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Settings read top to bottom in {@code position} order; two items sharing a position in one
 * section would show in declaration order instead (owner 1.3.4 reorg).
 */
public class SettingsOrderTest
{
    @Test
    public void everySectionKeepsUniquePositions()
    {
        Map<String, Set<Integer>> positionsBySection = new HashMap<>();
        for (Method method : GpManagerConfig.class.getDeclaredMethods())
        {
            ConfigItem item = method.getAnnotation(ConfigItem.class);
            if (item == null || item.hidden()) continue;
            Set<Integer> positions =
                    positionsBySection.computeIfAbsent(item.section(), key -> new HashSet<>());
            assertTrue(
                    method.getName()
                            + " reuses position "
                            + item.position()
                            + " in section "
                            + item.section(),
                    positions.add(item.position()));
        }
        assertTrue("expected the five visible sections", positionsBySection.size() >= 5);
    }
}
