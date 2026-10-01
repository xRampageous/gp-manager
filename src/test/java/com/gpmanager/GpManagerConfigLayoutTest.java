package com.gpmanager;

import java.lang.reflect.Method;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The visible configuration contract after legacy HUD/drop surfaces are removed. */
public class GpManagerConfigLayoutTest
{
    @Test
    public void everyConfigMethodIsBackedByARuneLiteConfigItem()
    {
        for (Method method : GpManagerConfig.class.getDeclaredMethods())
        {
            assertNotNull(method.getName() + " must have @ConfigItem", method.getAnnotation(ConfigItem.class));
        }
    }

    /** Owner 2026-09-28: Tracking, Accounting, Loot display, then HUD+ and History start collapsed. */
    @Test
    public void currentSectionsHaveStableNames() throws Exception
    {
        assertSectionName("generalSection", "Tracking");
        assertSectionName("accountingSection", "Accounting");
        assertSectionName("lootSection", "Loot display");
        assertSectionName("hudLiveSection", "HUD+");
        assertSectionName("grindsSection", "History");
        assertTrue(GpManagerConfig.class.getField("hudLiveSection").getAnnotation(ConfigSection.class).closedByDefault());
        assertTrue(GpManagerConfig.class.getField("grindsSection").getAnnotation(ConfigSection.class).closedByDefault());
    }

    /** Owner 2026-10-01 (F12/F24): settings copy names its lists, its window and its policies. */
    @Test
    public void settingsCopyNamesTheRightListsAndPolicies() throws Exception
    {
        assertTrue(description("autoStartSession").contains("Free play"));
        assertFalse(description("autoStartSession").contains("Overall"));
        assertTrue(description("idlePauseEnabled").contains("idle time"));
        assertFalse(description("idlePauseEnabled").contains("wall-clock"));
        assertTrue(description("hudTimer").contains("tracked active time"));
        assertTrue("the tray mirrors filters while Ledger costs stay visible: "
            + description("lootPresentationFilter"),
            description("lootPresentationFilter").contains("tray mirrors display filters")
                && description("lootPresentationFilter").contains("Ledger costs stay visible"));
    }

    private static String description(String method) throws Exception
    {
        return GpManagerConfig.class.getMethod(method).getAnnotation(ConfigItem.class).description();
    }

    /** Regrouping moves items between sections but never renames a key, so saved settings carry over. */
    @Test
    public void movedItemsKeepTheirKeysInTheirNewSections() throws Exception
    {
        assertIn("lootPresentationFilter", "lootPresentationFilter", GpManagerConfig.lootSection);
        assertIn("minimumDisplayedLootValue", "minimumDisplayedLootValue", GpManagerConfig.lootSection);
        assertIn("reducedMotion", "reducedMotion", GpManagerConfig.hudLiveSection);
        assertIn("manualPriceOverrides", "manualPriceOverrides", GpManagerConfig.accountingSection);
    }

    private static void assertIn(String method, String key, String section) throws Exception
    {
        ConfigItem item = GpManagerConfig.class.getMethod(method).getAnnotation(ConfigItem.class);
        assertEquals(key, item.keyName());
        assertEquals(method, section, item.section());
    }

    @Test
    public void hudPlusAndAccountingDefaultsRemainStable()
    {
        GpManagerConfig defaults = new GpManagerConfig() {};
        assertEquals(2_000, defaults.maxHistorySessions());
    }

    @Test
    public void deletedSurfacesAreNotVisibleConfigMethods()
    {
        assertMissing("trackingDisplay");
        assertMissing("infoBoxView");
        assertMissing("showLiveGpDrops");
        assertMissing("gpDropPresentationPreset");
        assertMissing("feedbackStyle");
        assertMissing("enableDebugTrace");
        // Retired pre-release settings are gone from the config surface entirely.
        assertMissing("accountingItemFilter");
        assertMissing("ignoreUnpricedItems");
        assertMissing("minimumTransactionValue");
        assertMissing("ignoredItemIds");
        assertMissing("geBookingObserved");
        assertMissing("geSellSpentIsNet");
        assertMissing("useCurrencyProxies");
        assertMissing("useHighAlchemyFallback");
        assertMissing("applyGeSellTax");
        // Removed by SIDEBAR_SPEC.md §6b.
        assertMissing("storeOpponentNames");
        assertMissing("characterIdleDelayMillis");
        assertMissing("rowsPerPage");
        // Timing and limit knobs are fixed at their defaults (owner, 2026-09-26).
        assertMissing("correlationTicks");
        assertMissing("lootCorrelationSeconds");
        assertMissing("chargeLoadReviewMinutes");
        assertMissing("pkSupplyWindowSeconds");
        assertMissing("activityResetSeconds");
    }

    @Test
    public void theRemainingTuningKnobsAreHiddenFromTheSettingsPanel() throws Exception
    {
        for (String name : new String[] {"stabilizationTicks", "keepTransferAuditRows", "maxHistorySessions",
            "maxTransactionsPerSession"})
        {
            assertTrue(name, GpManagerConfig.class.getMethod(name).getAnnotation(ConfigItem.class).hidden());
        }
    }

    private static void assertSectionName(String fieldName, String expectedName) throws Exception
    {
        ConfigSection section = GpManagerConfig.class.getField(fieldName).getAnnotation(ConfigSection.class);
        assertNotNull(fieldName, section);
        assertEquals(expectedName, section.name());
    }

    private static void assertMissing(String methodName)
    {
        try
        {
            GpManagerConfig.class.getMethod(methodName);
            throw new AssertionError(methodName + " should be removed from visible configuration");
        }
        catch (NoSuchMethodException expected)
        {
            // Expected after Checkpoint 1.3 deletion.
        }
    }

}
