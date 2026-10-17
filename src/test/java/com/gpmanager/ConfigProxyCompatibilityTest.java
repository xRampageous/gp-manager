package com.gpmanager;

import com.gpmanager.GpManagerConfig.Retention;
import com.gpmanager.GpManagerConfig.LootFilter;

import net.runelite.client.config.ConfigManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Settings must work through RuneLite's real proxy, including enum-valued settings. */
public class ConfigProxyCompatibilityTest
{
    @Test
    public void enumDefaultsRemainReadableThroughTheRuneLiteProxy()
    {
        GpManagerConfig defaults = new GpManagerConfig() {};
        GpManagerConfig config = IsolatedConfigManager.create(null).getConfig(GpManagerConfig.class);
        assertEquals(defaults.lootPresentationFilter(), config.lootPresentationFilter());
        assertEquals(defaults.receiptRetentionDays(), config.receiptRetentionDays());
        assertEquals(defaults.hudTray(), config.hudTray());
        assertEquals(GpManagerConfig.HudTrayKeeps.STREAK, config.hudTrayKeeps());
        assertEquals(LootFilter.FOLLOW_GROUND_ITEMS, config.lootPresentationFilter());
        assertEquals("", config.hiddenRecentItems());
        assertEquals("", config.hiddenRecentCharges());
    }

    @Test
    public void savedEnumChoicesRemainReadableThroughTheRuneLiteProxy()
    {
        ConfigManager manager = IsolatedConfigManager.create(null);
        manager.setConfiguration(GpManagerConfig.GROUP, "lootPresentationFilter", "HIGHLIGHTED_LIST_ONLY");
        manager.setConfiguration(GpManagerConfig.GROUP, "receiptRetentionDays", "DAYS_365");
        manager.setConfiguration(GpManagerConfig.GROUP, "hudTrayKeeps", "SESSION");
        manager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentItems", "Shark, Prayer potion");
        manager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentCharges", "Burning amulet*");
        GpManagerConfig config = manager.getConfig(GpManagerConfig.class);
        assertEquals(LootFilter.HIGHLIGHTED_LIST_ONLY, config.lootPresentationFilter());
        assertEquals(Retention.DAYS_365, config.receiptRetentionDays());
        assertEquals(GpManagerConfig.HudTrayKeeps.SESSION, config.hudTrayKeeps());
        assertEquals("Shark, Prayer potion", config.hiddenRecentItems());
        assertEquals("Burning amulet*", config.hiddenRecentCharges());
    }
}
