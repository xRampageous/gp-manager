package com.gpmanager;

import net.runelite.client.config.ConfigManager;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
        assertEquals(Dl.FOLLOW_GROUND_ITEMS, config.lootPresentationFilter());
        assertEquals("", config.hiddenRecentItems());
    }

    @Test
    public void savedEnumChoicesRemainReadableThroughTheRuneLiteProxy()
    {
        ConfigManager manager = IsolatedConfigManager.create(null);
        manager.setConfiguration(GpManagerConfig.GROUP, "lootPresentationFilter", "HIGHLIGHTED_LIST_ONLY");
        manager.setConfiguration(GpManagerConfig.GROUP, "receiptRetentionDays", "DAYS_365");
        manager.setConfiguration(GpManagerConfig.GROUP, "hudTrayKeeps", "SESSION");
        manager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentItems", "Shark, Prayer potion");
        GpManagerConfig config = manager.getConfig(GpManagerConfig.class);
        assertEquals(Dl.HIGHLIGHTED_LIST_ONLY, config.lootPresentationFilter());
        assertEquals(Db.DAYS_365, config.receiptRetentionDays());
        assertEquals(GpManagerConfig.HudTrayKeeps.SESSION, config.hudTrayKeeps());
        assertEquals("Shark, Prayer potion", config.hiddenRecentItems());
    }

    @Test
    public void settingsSurviveTheGroupRename()
    {
        ConfigManager manager = IsolatedConfigManager.seeded(null, "profitmanager", Map.of(
            "hiddenRecentItems", "Shark", "receiptRetentionDays", "DAYS_365"));
        GpManagerPlugin.migrateGroup(manager);
        assertEquals("Shark", manager.getConfiguration("gpmanager", "hiddenRecentItems"));
        assertEquals("DAYS_365", manager.getConfiguration("gpmanager", "receiptRetentionDays"));
        assertNotNull("the marker stops a second copy",
            manager.getConfiguration("gpmanager", "groupMigrated"));
        manager.setConfiguration("gpmanager", "hiddenRecentItems", "Lobster");
        GpManagerPlugin.migrateGroup(manager);
        assertEquals("a later choice is never clobbered", "Lobster",
            manager.getConfiguration("gpmanager", "hiddenRecentItems"));
    }
}
