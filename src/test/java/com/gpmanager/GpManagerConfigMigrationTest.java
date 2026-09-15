package com.gpmanager;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpManagerConfigMigrationTest
{
    @Test
    public void schema30SeedsReadOnlyWealthHistoryOptions()
    {
        Map<String, String> before = new HashMap<>();
        before.put("configSchemaVersion", "30");
        Map<String, String> updates = GpManagerConfigMigration.plan(before);
        assertEquals("true", updates.get("wealthHistoryEnabled"));
        assertEquals("true", updates.get("wealthBankReadEnabled"));
        assertEquals("0", updates.get("wealthMilestoneGp"));
        assertEquals("31", updates.get("configSchemaVersion"));
    }

    @Test
    public void schema21SeedsCompactTimerAndHoverTitleWhenMissing()
    {
        Map<String, String> before = new HashMap<>();
        before.put("configSchemaVersion", "20");

        Map<String, String> updates = GpManagerConfigMigration.plan(before);

        assertEquals("true", updates.get("hudPlusCompactTimer"));
        assertEquals("false", updates.get("hudPlusHoverFullTitle"));
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION),
            updates.get("configSchemaVersion"));
    }

    @Test
    public void receiptRetentionChoicesMigrateAndPreserveExplicitHistoryLimit()
    {
        Map<String, String> before = new HashMap<>();
        before.put("configSchemaVersion", "29");
        before.put("receiptRetentionDays", ReceiptRetentionPeriod.FOREVER.name());
        before.put("maxHistorySessions", "100");

        Map<String, String> updates = GpManagerConfigMigration.plan(before);

        assertFalse(updates.containsKey("receiptRetentionDays"));
        assertFalse(updates.containsKey("maxHistorySessions"));
        assertEquals(0, ReceiptRetentionPeriod.FOREVER.getDays());
        assertEquals(30, ReceiptRetentionPeriod.DAYS_30.getDays());
        assertEquals(90, new GpManagerConfig() { }.receiptRetentionDays().getDays());
        assertEquals(180, ReceiptRetentionPeriod.DAYS_180.getDays());
        assertEquals(365, ReceiptRetentionPeriod.DAYS_365.getDays());
        assertEquals(2_000, new GpManagerConfig() { }.maxHistorySessions());
    }

    @Test
    public void invalidRetentionAndHistoryValuesFallBackSafely()
    {
        Map<String, String> before = new HashMap<>();
        before.put("configSchemaVersion", "29");
        before.put("receiptRetentionDays", "14");
        before.put("maxHistorySessions", "not-a-number");

        Map<String, String> updates = GpManagerConfigMigration.plan(before);

        assertEquals(ReceiptRetentionPeriod.DAYS_90.name(), updates.get("receiptRetentionDays"));
        assertEquals("2000", updates.get("maxHistorySessions"));
    }

    @Test
    public void schema20SeedsDetailTriggerWhenMissing()
    {
        Map<String, String> before = new HashMap<>();
        before.put("configSchemaVersion", "19");

        Map<String, String> updates = GpManagerConfigMigration.plan(before);

        assertEquals(HudPlusDetailTrigger.HOVER_OR_SHIFT.name(), updates.get("hudPlusDetailTrigger"));
        assertEquals("139,147,167", updates.get("hudPlusAccentColor"));
        assertEquals("92,255,176", updates.get("hudPlusProfitColor"));
        assertEquals("255,107,107", updates.get("hudPlusLossColor"));
        assertEquals("true", updates.get("hudPlusCompactTimer"));
        assertEquals("false", updates.get("hudPlusHoverFullTitle"));
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION),
            updates.get("configSchemaVersion"));
    }

    @Test
    public void schema20RemapsPriorFactoryProfitLossOnly()
    {
        Map<String, String> before = new HashMap<>();
        before.put("configSchemaVersion", "19");
        before.put("hudPlusProfitColor", "83,214,128");
        before.put("hudPlusLossColor", "242,93,104");
        before.put("hudPlusAccentColor", "1,2,3");

        Map<String, String> updates = GpManagerConfigMigration.plan(before);

        assertEquals("92,255,176", updates.get("hudPlusProfitColor"));
        assertEquals("255,107,107", updates.get("hudPlusLossColor"));
        assertFalse(updates.containsKey("hudPlusAccentColor"));
    }

    @Test
    public void schema19SanitizesCharacterIdleDelayMillis()
    {
        Map<String, String> before = new HashMap<>();
        before.put("configSchemaVersion", "18");
        before.put("characterIdleDelayMillis", "500");

        Map<String, String> updates = GpManagerConfigMigration.plan(before);

        assertEquals("2000", updates.get("characterIdleDelayMillis"));
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION),
            updates.get("configSchemaVersion"));
    }

    @Test
    public void schema18SeedsFloatingXpDropParityDefaults()
    {
        Map<String, String> updates = GpManagerConfigMigration.plan(new HashMap<>());
        assertEquals(GpDropTextBackground.SHADOW.name(), updates.get("gpDropTextBackground"));
        assertEquals("44", updates.get("gpDropPixelsPerSecondY"));
        assertEquals("0", updates.get("gpDropPixelsPerSecondX"));
        assertEquals("400", updates.get("gpDropStaggerMillis"));
        assertEquals("1.0", updates.get("gpDropOverlayPriority"));
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION),
            updates.get("configSchemaVersion"));
        assertEquals("5000", updates.get("characterIdleDelayMillis"));
    }

    @Test
    public void migratesOutlineTrueToTextBackgroundOutline()
    {
        Map<String, String> before = new HashMap<>();
        before.put("configSchemaVersion", "17");
        before.put("gpDropOutline", "true");

        Map<String, String> updates = GpManagerConfigMigration.plan(before);
        assertEquals(GpDropTextBackground.OUTLINE.name(), updates.get("gpDropTextBackground"));
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION), updates.get("configSchemaVersion"));
    }

    @Test
    public void newProfilesDefaultToHudPlus()
    {
        Map<String, String> updates = GpManagerConfigMigration.plan(new HashMap<>());

        assertEquals(
            GpDropPresentationPreset.COIN_TOTAL.name(),
            updates.get("gpDropPresentationPreset"));
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION), updates.get("configSchemaVersion"));
        assertEquals(TrackingDisplay.HUD_PLUS.name(), updates.get("trackingDisplay"));
        assertEquals("false", updates.get("useHighAlchemyFallback"));
        assertEquals("false", updates.get("ignoreUnpricedItems"));
        assertEquals(FeedbackStyle.FLOATING.name(), updates.get("feedbackStyle"));
        assertEquals("false", updates.get("hudPlusFloatingDrops"));
        assertEquals("true", updates.get("hudPlusAutoResize"));
        assertEquals("true", updates.get("hudPlusShowBankingFlashes"));
        assertEquals("false", updates.get("idlePauseEnabled"));
        assertEquals("false", updates.get("enableDebugTrace"));
        assertEquals("220", updates.get("infoBoxWidth"));
        assertEquals("260", updates.get("hudPlusWidth"));
        assertEquals("true", updates.get("hudPlusCompactTimer"));
        assertEquals("false", updates.get("hudPlusHoverFullTitle"));
        assertEquals("90", updates.get("hudPlusBackgroundOpacity"));
        assertEquals(HudPlusTextSize.NORMAL.name(), updates.get("hudPlusTextSize"));
        assertEquals("true", updates.get("hudPlusShowMetricLabels"));
        assertEquals("true", updates.get("hudPlusShowActivityHeader"));
        assertEquals("true", updates.get("hudPlusShowItemIcons"));
        assertEquals("true", updates.get("hudPlusShowTrayTags"));
        assertEquals("139,147,167", updates.get("hudPlusAccentColor"));
        assertEquals("5000", updates.get("hudPlusRevealDurationMillis"));
        assertEquals("2", updates.get("hudPlusLootCoalesceTicks"));
        assertEquals(HudPlusAccumulationMode.SESSION.name(), updates.get("hudPlusAccumulationMode"));
        assertEquals("0", updates.get("hudPlusStreakInactivityMinutes"));
        assertEquals("5", updates.get("hudPlusMaxRewardRows"));
        assertEquals(HudPlusRewardPresentation.AUTO_COLLAPSE.name(), updates.get("hudPlusRewardPresentation"));
        assertEquals("5", updates.get("itemChangesTopN"));
        assertEquals(InfoBoxTheme.DARK.name(), updates.get("infoBoxTheme"));
        assertEquals("false", updates.get("keepRewardTrayExpanded"));
        assertEquals(LootPresentationFilter.FOLLOW_GROUND_ITEMS.name(), updates.get("lootPresentationFilter"));
        assertEquals("false", updates.get("reuseGroundItemsHighlightColors"));
        assertEquals(
            GpDropAnimationStyle.CLASSIC_RISE.name(),
            updates.get("gpDropAnimationStyle"));
        assertEquals(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION,
            new GpManagerConfig() { }.configSchemaVersion());
    }

    @Test
    public void copiesOnlyMissingLegacyGroupValues()
    {
        Map<String, String> legacy = new HashMap<>();
        legacy.put("infoBoxWidth", "185");
        legacy.put("gpDropPresentationPreset", GpDropPresentationPreset.ICON_VALUE_ONLY.name());

        Map<String, String> current = new HashMap<>();
        current.put("gpDropPresentationPreset", GpDropPresentationPreset.COIN_TOTAL.name());

        Map<String, String> copied = GpManagerConfigMigration.missingLegacyValues(legacy, current);

        assertEquals("185", copied.get("infoBoxWidth"));
        assertFalse(copied.containsKey("gpDropPresentationPreset"));
    }

    @Test
    public void derivesIconValuePresetFromLegacySettings()
    {
        Map<String, String> legacy = new HashMap<>();
        legacy.put("gpDropIconMode", GpDropIconMode.SMART.name());
        legacy.put("gpDropContentMode", GpDropContentMode.EACH_ITEM.name());
        legacy.put("gpDropItemTextMode", GpDropItemTextMode.VALUE_ONLY.name());
        legacy.put("gpDropQuantityOnIcon", "false");

        Map<String, String> updates = GpManagerConfigMigration.plan(legacy);

        assertEquals(
            GpDropPresentationPreset.ICON_VALUE_ONLY.name(),
            updates.get("gpDropPresentationPreset"));
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION), updates.get("configSchemaVersion"));
    }

    @Test
    public void migratesDisabledLiveDropsToFeedbackOff()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "7");
        current.put("showLiveGpDrops", "false");

        Map<String, String> updates = GpManagerConfigMigration.plan(current);

        assertEquals(FeedbackStyle.OFF.name(), updates.get("feedbackStyle"));
        assertEquals("false", updates.get("hudPlusFloatingDrops"));
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION), updates.get("configSchemaVersion"));
    }

    @Test
    public void migratesIntegratedFeedbackToHudPlusTrayOnly()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "21");
        current.put("trackingDisplay", TrackingDisplay.HUD_PLUS.name());
        current.put("feedbackStyle", FeedbackStyle.INTEGRATED.name());

        Map<String, String> updates = GpManagerConfigMigration.plan(current);

        assertEquals("false", updates.get("hudPlusFloatingDrops"));
        assertFalse(updates.containsKey("feedbackStyle"));
    }

    @Test
    public void migratesFloatingFeedbackToHudPlusFloatingOn()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "21");
        current.put("trackingDisplay", TrackingDisplay.HUD_PLUS.name());
        current.put("feedbackStyle", FeedbackStyle.FLOATING.name());

        Map<String, String> updates = GpManagerConfigMigration.plan(current);

        assertEquals("true", updates.get("hudPlusFloatingDrops"));
    }

    @Test
    public void coercesIntegratedFeedbackWhenTrackingDisplayIsOff()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "7");
        current.put("trackingDisplay", TrackingDisplay.OFF.name());
        current.put("feedbackStyle", FeedbackStyle.INTEGRATED.name());

        Map<String, String> updates = GpManagerConfigMigration.plan(current);

        assertEquals(FeedbackStyle.OFF.name(), updates.get("feedbackStyle"));
        assertEquals("false", updates.get("hudPlusFloatingDrops"));
    }

    @Test
    public void keepsIntegratedFeedbackForInfoboxPresentation()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "7");
        current.put("trackingDisplay", TrackingDisplay.INFOBOX.name());
        current.put("feedbackStyle", FeedbackStyle.INTEGRATED.name());

        Map<String, String> updates = GpManagerConfigMigration.plan(current);

        assertFalse(updates.containsKey("feedbackStyle"));
    }

    @Test
    public void migratesDisabledOverlayToTrackingDisplayOff()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "7");
        current.put("showOverlay", "false");

        Map<String, String> updates = GpManagerConfigMigration.plan(current);

        assertEquals(TrackingDisplay.OFF.name(), updates.get("trackingDisplay"));
    }

    @Test
    public void sanitizesHudPlusWidthAboveNewCeiling()
    {
        Map<String, String> legacy = new HashMap<>();
        legacy.put("configSchemaVersion", "20");
        legacy.put("hudPlusWidth", "400");

        Map<String, String> updates = GpManagerConfigMigration.plan(legacy);
        assertEquals("320", updates.get("hudPlusWidth"));
    }

    @Test
    public void sanitizesMalformedAndOutOfRangeValues()
    {
        Map<String, String> legacy = new HashMap<>();
        legacy.put("gpDropDurationMillis", "40");
        legacy.put("infoBoxWidth", "30");
        legacy.put("hudPlusWidth", "40");
        legacy.put("hudPlusRevealDurationMillis", "100");
        legacy.put("infoBoxView", "UNKNOWN_VIEW");
        legacy.put("defaultSessionMode", "UNKNOWN_MODE");
        legacy.put("hudPlusTextSize", "HUGE");

        Map<String, String> updates = GpManagerConfigMigration.plan(legacy);

        assertEquals("500", updates.get("gpDropDurationMillis"));
        assertEquals("120", updates.get("infoBoxWidth"));
        assertEquals("120", updates.get("hudPlusWidth"));
        assertEquals("1500", updates.get("hudPlusRevealDurationMillis"));
        assertEquals(InfoBoxView.COMPACT.name(), updates.get("infoBoxView"));
        assertEquals("AUTO", updates.get("defaultSessionMode"));
        assertEquals(HudPlusTextSize.NORMAL.name(), updates.get("hudPlusTextSize"));
    }

    @Test
    public void schemaTwelveAddsLootPresentationFilterDefaults()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "12");
        current.put("trackingDisplay", TrackingDisplay.HUD_PLUS.name());

        Map<String, String> updates = GpManagerConfigMigration.plan(current);
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION), updates.get("configSchemaVersion"));
        assertEquals(LootPresentationFilter.FOLLOW_GROUND_ITEMS.name(), updates.get("lootPresentationFilter"));
        assertEquals("false", updates.get("reuseGroundItemsHighlightColors"));
    }

    @Test
    public void schema23PromotesFactoryAllItemsToFollowGroundItems()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "22");
        current.put("lootPresentationFilter", LootPresentationFilter.ALL_ITEMS.name());

        Map<String, String> updates = GpManagerConfigMigration.plan(current);
        assertEquals(LootPresentationFilter.FOLLOW_GROUND_ITEMS.name(), updates.get("lootPresentationFilter"));
    }

    @Test
    public void schema23KeepsHighlightedListOnly()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "22");
        current.put("lootPresentationFilter", LootPresentationFilter.HIGHLIGHTED_LIST_ONLY.name());

        Map<String, String> updates = GpManagerConfigMigration.plan(current);
        assertFalse(updates.containsKey("lootPresentationFilter"));
    }

    @Test
    public void migrationIsIdempotentAfterCurrentSchema()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION));

        assertTrue(GpManagerConfigMigration.plan(current).isEmpty());
    }

    @Test
    public void schemaElevenSeedsHudPlusWithoutOverwriting()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "11");
        current.put("infoBoxWidth", "200");
        current.put("keepRewardTrayExpanded", "true");
        current.put("hudPlusWidth", "240");

        Map<String, String> updates = GpManagerConfigMigration.plan(current);
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION), updates.get("configSchemaVersion"));
        assertFalse(updates.containsKey("hudPlusWidth"));
        assertEquals(HudPlusRewardPresentation.KEEP_EXPANDED.name(), updates.get("hudPlusRewardPresentation"));
        assertEquals("90", updates.get("hudPlusBackgroundOpacity"));
    }

    @Test
    public void migratedKeepExpandedDoesNotChangeTheRuntimePreferenceDefault()
    {
        Map<String, String> legacy = new HashMap<>();
        legacy.put("configSchemaVersion", "11");
        legacy.put("keepRewardTrayExpanded", "true");

        Map<String, String> updates = GpManagerConfigMigration.plan(legacy);
        assertEquals(HudPlusRewardPresentation.KEEP_EXPANDED.name(), updates.get("hudPlusRewardPresentation"));

        // Migration seeds a one-time value. Runtime reads the enum setting only,
        // so users can subsequently switch this preference back to auto-collapse.
        GpManagerConfig runtimeConfig = new GpManagerConfig() { };
        assertEquals(HudPlusRewardPresentation.AUTO_COLLAPSE, runtimeConfig.hudPlusRewardPresentation());
    }

    @Test
    public void schemaElevenCopiesInfoBoxWidthIntoHudPlusWhenMissing()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "11");
        current.put("infoBoxWidth", "185");

        Map<String, String> updates = GpManagerConfigMigration.plan(current);
        assertEquals("185", updates.get("hudPlusWidth"));
        assertEquals(HudPlusRewardPresentation.AUTO_COLLAPSE.name(), updates.get("hudPlusRewardPresentation"));
    }

    @Test
    public void schemaTenCompactHudBecomesHudPlus()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "10");
        current.put("trackingDisplay", TrackingDisplay.HUD.name());
        current.put("infoBoxView", InfoBoxView.COMPACT.name());
        current.put("infoBoxWidth", "220");
        current.put("autoStartSession", "false");

        Map<String, String> updates = GpManagerConfigMigration.plan(current);
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION), updates.get("configSchemaVersion"));
        assertEquals(TrackingDisplay.HUD_PLUS.name(), updates.get("trackingDisplay"));
        assertFalse(updates.containsKey("infoBoxWidth"));
        assertFalse(updates.containsKey("autoStartSession"));
    }

    @Test
    public void schemaTenDetailedHudStaysHud()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "10");
        current.put("trackingDisplay", TrackingDisplay.HUD.name());
        current.put("infoBoxView", InfoBoxView.DETAILED.name());

        Map<String, String> updates = GpManagerConfigMigration.plan(current);
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION), updates.get("configSchemaVersion"));
        assertFalse(TrackingDisplay.HUD_PLUS.name().equals(updates.get("trackingDisplay")));
        assertFalse(updates.containsKey("trackingDisplay"));
    }

    @Test
    public void schemaTenInfoboxAndOffPreserved()
    {
        Map<String, String> box = new HashMap<>();
        box.put("configSchemaVersion", "10");
        box.put("trackingDisplay", TrackingDisplay.INFOBOX.name());
        assertFalse(GpManagerConfigMigration.plan(box).containsKey("trackingDisplay"));

        Map<String, String> off = new HashMap<>();
        off.put("configSchemaVersion", "10");
        off.put("trackingDisplay", TrackingDisplay.OFF.name());
        assertFalse(GpManagerConfigMigration.plan(off).containsKey("trackingDisplay"));
    }

    @Test
    public void earlierSchemaHudIsNotReinterpretedAsHudPlus()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "9");
        current.put("trackingDisplay", TrackingDisplay.HUD.name());
        current.put("infoBoxView", InfoBoxView.COMPACT.name());

        Map<String, String> updates = GpManagerConfigMigration.plan(current);
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION), updates.get("configSchemaVersion"));
        assertFalse(updates.containsKey("trackingDisplay"));
    }

    @Test
    public void schemaEightBumpsTowardCurrentWithoutChangingAutoStartPreference()
    {
        Map<String, String> current = new HashMap<>();
        current.put("configSchemaVersion", "8");
        current.put("autoStartSession", "false");

        Map<String, String> updates = GpManagerConfigMigration.plan(current);
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION), updates.get("configSchemaVersion"));
        assertFalse(updates.containsKey("autoStartSession"));
    }

    @Test
    public void configurationKeySuffixStripsQualifiedGroupKeys()
    {
        assertEquals(
            "trackingDisplay",
            GpManagerConfigMigration.configurationKeySuffix(
                "profitmanager", "profitmanager.trackingDisplay"));
        assertEquals(
            "feedbackStyle",
            GpManagerConfigMigration.configurationKeySuffix(
                "profitmanager", "feedbackStyle"));
        assertEquals(
            null,
            GpManagerConfigMigration.configurationKeySuffix(
                "profitmanager", "profitmanager.nested.bad"));
        assertEquals(
            null,
            GpManagerConfigMigration.configurationKeySuffix(
                "profitmanager", "other.trackingDisplay"));
    }

    @Test
    public void schema26RemapsFactoryTealAccentAndSeedsAutoResize()
    {
        Map<String, String> before = new HashMap<>();
        before.put("configSchemaVersion", "25");
        before.put("hudPlusAccentColor", "46,230,214");

        Map<String, String> updates = GpManagerConfigMigration.plan(before);

        assertEquals("139,147,167", updates.get("hudPlusAccentColor"));
        assertEquals("true", updates.get("hudPlusAutoResize"));
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION),
            updates.get("configSchemaVersion"));
    }

    @Test
    public void schema26KeepsCustomAccent()
    {
        Map<String, String> before = new HashMap<>();
        before.put("configSchemaVersion", "25");
        before.put("hudPlusAccentColor", "1,2,3");
        before.put("hudPlusAutoResize", "false");

        Map<String, String> updates = GpManagerConfigMigration.plan(before);

        assertFalse(updates.containsKey("hudPlusAccentColor"));
        assertFalse(updates.containsKey("hudPlusAutoResize"));
    }

    @Test
    public void emptyProfilePlanAfterFactoryClearSeedsHudPlus()
    {
        // Simulates clearGroup removing every live key, then apply()/plan on empty.
        Map<String, String> updates = GpManagerConfigMigration.plan(new HashMap<>());
        assertEquals(TrackingDisplay.HUD_PLUS.name(), updates.get("trackingDisplay"));
        assertEquals(FeedbackStyle.FLOATING.name(), updates.get("feedbackStyle"));
        assertEquals("false", updates.get("hudPlusFloatingDrops"));
        assertEquals(Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION), updates.get("configSchemaVersion"));
        assertEquals("260", updates.get("hudPlusWidth"));
    }

    @Test
    public void shouldImportLegacyOnlyBeforeSchemaStamp()
    {
        assertTrue(GpManagerConfigMigration.shouldImportLegacy(null));
        assertTrue(GpManagerConfigMigration.shouldImportLegacy(new HashMap<>()));

        Map<String, String> preSchema = new HashMap<>();
        preSchema.put("trackingDisplay", TrackingDisplay.HUD.name());
        assertTrue(GpManagerConfigMigration.shouldImportLegacy(preSchema));

        Map<String, String> afterFactory = new HashMap<>();
        afterFactory.put("configSchemaVersion",
            Integer.toString(GpManagerConfigMigration.CURRENT_SCHEMA_VERSION));
        assertFalse(GpManagerConfigMigration.shouldImportLegacy(afterFactory));

        Map<String, String> schemaOne = new HashMap<>();
        schemaOne.put("configSchemaVersion", "1");
        assertFalse(GpManagerConfigMigration.shouldImportLegacy(schemaOne));
    }
}
