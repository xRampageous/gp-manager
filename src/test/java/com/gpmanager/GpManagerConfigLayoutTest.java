package com.gpmanager;

import java.lang.reflect.Method;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GpManagerConfigLayoutTest
{
    @Test
    public void everyConfigMethodIsBackedByARuneLiteConfigItem()
    {
        for (Method method : GpManagerConfig.class.getDeclaredMethods())
        {
            assertNotNull(method.getName() + " must have @ConfigItem",
                method.getAnnotation(ConfigItem.class));
        }
    }

    @Test
    public void coreSettingsAreGroupedIntoPredictableSections() throws Exception
    {
        assertSection("includeEquipment", GpManagerConfig.generalSection);
        assertSection("includeRunePouch", GpManagerConfig.generalSection);
        assertSection("autoStartSession", GpManagerConfig.sessionSection);
        assertSection("receiptRetentionDays", GpManagerConfig.sessionSection);
        assertSection("autoActivityDetection", GpManagerConfig.activitySection);
        assertSection("trackingDisplay", GpManagerConfig.infoBoxSection);
        assertSection("hudPlusFloatingDrops", GpManagerConfig.infoBoxSection);
        assertSection("feedbackStyle", GpManagerConfig.infoBoxSection);
        assertSection("showOverlay", GpManagerConfig.infoBoxSection);
        assertSection("reducedMotion", GpManagerConfig.infoBoxSection);
        assertSection("infoBoxNumberFormat", GpManagerConfig.infoBoxSection);
        assertSection("hudPlusWidth", GpManagerConfig.hudPlusSection);
        assertSection("hudPlusAutoResize", GpManagerConfig.hudPlusSection);
        assertSection("hudPlusCompactTimer", GpManagerConfig.hudPlusSection);
        assertSection("hudPlusHoverFullTitle", GpManagerConfig.hudPlusSection);
        assertSection("hudPlusMaxHeight", GpManagerConfig.hudPlusSection);
        assertSection("hudPlusShowBankingFlashes", GpManagerConfig.hudTraySection);
        assertSection("hudPlusMaxRewardRows", GpManagerConfig.hudTraySection);
        assertSection("hudPlusRewardPresentation", GpManagerConfig.hudTraySection);
        assertSection("hudPlusDetailTrigger", GpManagerConfig.hudTraySection);
        assertSection("keepRewardTrayExpanded", GpManagerConfig.hudTraySection);
        assertSection("infoBoxView", GpManagerConfig.hudSection);
        assertSection("infoBoxWidth", GpManagerConfig.hudSection);
        assertSection("infoBoxPadding", GpManagerConfig.hudSection);
        assertSection("showInfoBoxActivity", GpManagerConfig.infoBoxContentSection);
        assertSection("infoBoxCustomProfitColor", GpManagerConfig.hudAppearanceSection);
        assertSection("infoBoxTheme", GpManagerConfig.hudAppearanceSection);
        assertSection("hudPlusProfitColor", GpManagerConfig.hudPlusColorSection);
        assertSection("hudPlusLossColor", GpManagerConfig.hudPlusColorSection);
        assertSection("hudPlusNeutralColor", GpManagerConfig.hudPlusColorSection);
        assertSection("hudPlusHeaderColor", GpManagerConfig.hudPlusColorSection);
        assertSection("hudPlusLabelColor", GpManagerConfig.hudPlusColorSection);
        assertSection("hudPlusAccentColor", GpManagerConfig.hudPlusColorSection);
        assertSection("lootPresentationFilter", GpManagerConfig.lootVisibilitySection);
        assertSection("minimumDisplayedLootValue", GpManagerConfig.lootVisibilitySection);
        assertSection("itemChangesTopN", GpManagerConfig.itemChangesSection);
        assertSection("liveItemGridMaxHeight", GpManagerConfig.itemChangesSection);
        assertSection("hudPlusDensity", GpManagerConfig.hudPlusSection);
        assertSection("hudPlusShowWildernessRisk", GpManagerConfig.hudPlusSection);
        assertSection("showLiveGpDrops", GpManagerConfig.liveGpDropsSection);
        assertSection("gpDropPresentationPreset", GpManagerConfig.liveGpDropsSection);
        assertSection("gpDropDurationMillis", GpManagerConfig.liveGpDropsSection);
        assertSection("gpDropFontType", GpManagerConfig.floatingAppearanceSection);
        assertSection("gpDropTextBackground", GpManagerConfig.floatingAppearanceSection);
        assertSection("gpDropOutline", GpManagerConfig.floatingAppearanceSection);
        assertSection("gpDropAnimationStyle", GpManagerConfig.floatingAnimationSection);
        assertSection("gpDropPixelsPerSecondY", GpManagerConfig.floatingAnimationSection);
        assertSection("gpDropContentMode", GpManagerConfig.gpDropAdvancedSection);
        assertSection("gpDropIconSize", GpManagerConfig.gpDropAdvancedSection);
        assertSection("manualPriceOverrides", GpManagerConfig.pricingSection);
        assertSection("useHighAlchemyFallback", GpManagerConfig.pricingSection);
        assertSection("notableDropThresholdGp", GpManagerConfig.insightsWealthSection);
        assertSection("showGrandExchangeOfferWealth", GpManagerConfig.insightsWealthSection);
        assertSection("showGrandExchangeCollectionWealth", GpManagerConfig.insightsWealthSection);
        assertSection("wealthHistoryEnabled", GpManagerConfig.insightsWealthSection);
        assertSection("wealthBankReadEnabled", GpManagerConfig.insightsWealthSection);
        assertSection("wealthMilestoneGp", GpManagerConfig.insightsWealthSection);
        assertSection("enableDebugTrace", GpManagerConfig.advancedTrackingSection);
        assertSection("chargeLoadReviewMinutes", GpManagerConfig.advancedTrackingSection);
        assertSection("characterIdleDelayMillis", GpManagerConfig.activitySection);
        assertSection("idleTimeoutSeconds", GpManagerConfig.activitySection);
        assertSection("hudPlusRevealDurationMillis", GpManagerConfig.hudTraySection);
        assertSection("hudPlusLootCoalesceTicks", GpManagerConfig.hudTraySection);
        assertSection("hudPlusAccumulationMode", GpManagerConfig.hudTraySection);
        assertSection("idlePauseEnabled", GpManagerConfig.activitySection);
    }

    @Test
    public void displayTaxonomySectionsExistWithExpectedNames() throws Exception
    {
        assertSectionName("infoBoxSection", "Display selection");
        assertSectionName("hudPlusSection", "HUD+");
        assertSectionName("hudTraySection", "HUD+ - Item tray");
        assertSectionName("hudPlusColorSection", "HUD+ - Colours");
        assertSectionName("hudSection", "HUD");
        assertSectionName("infoBoxContentSection", "HUD - Custom rows");
        assertSectionName("hudAppearanceSection", "HUD appearance - Shared");
        assertSectionName("lootVisibilitySection", "Loot visibility");
        assertSectionName("insightsWealthSection", "Insights and Wealth");
        assertSectionName("liveGpDropsSection", "Floating drops");
        assertSectionName("floatingAppearanceSection", "Floating drops - Appearance");
        assertSectionName("floatingAnimationSection", "Floating drops - Animation");
        assertSectionName("gpDropAdvancedSection", "Floating drops - Custom preset");
    }

    @Test
    public void technicalAndLegacyControlsStayHidden() throws Exception
    {
        assertHidden("infoBoxTitleMode");
        assertTrue(!GpManagerConfig.class.getMethod("showInfoBoxActivity").getAnnotation(ConfigItem.class).hidden());
        assertTrue(!GpManagerConfig.class.getMethod("infoBoxPadding").getAnnotation(ConfigItem.class).hidden());
        assertTrue(!GpManagerConfig.class.getMethod("gpDropFontType").getAnnotation(ConfigItem.class).hidden());
        assertTrue(!GpManagerConfig.class.getMethod("gpDropTextBackground").getAnnotation(ConfigItem.class).hidden());
        assertTrue(!GpManagerConfig.class.getMethod("gpDropIconSize").getAnnotation(ConfigItem.class).hidden());
        assertTrue(!GpManagerConfig.class.getMethod("hudPlusDensity").getAnnotation(ConfigItem.class).hidden());
        assertHidden("gpDropOutline");
        assertHidden("gpDropFontStyle");
        assertHidden("configSchemaVersion");
        assertHidden("autoResumeOnActivity");
        assertHidden("defaultSessionName");
        assertHidden("keepRewardTrayExpanded");
        assertHidden("showOverlay");
        assertHidden("showLiveGpDrops");
        assertHidden("feedbackStyle");
        assertHidden("hudPlusMaxHeight");
        assertHidden("defaultSessionMode");
        assertTrue(!GpManagerConfig.class.getMethod("hudPlusTextSize").getAnnotation(ConfigItem.class).hidden());
        assertTrue(!GpManagerConfig.class.getMethod("hudPlusShowMetricLabels").getAnnotation(ConfigItem.class).hidden());
        assertTrue(!GpManagerConfig.class.getMethod("hudPlusShowActivityHeader").getAnnotation(ConfigItem.class).hidden());
        assertTrue(!GpManagerConfig.class.getMethod("hudPlusShowItemIcons").getAnnotation(ConfigItem.class).hidden());
        assertTrue(!GpManagerConfig.class.getMethod("hudPlusShowTrayTags").getAnnotation(ConfigItem.class).hidden());
        assertEquals(8, GpManagerConfig.class.getMethod("hudPlusShowActivityHeader").getAnnotation(ConfigItem.class).position());
        assertEquals(9, GpManagerConfig.class.getMethod("hudPlusShowMetricLabels").getAnnotation(ConfigItem.class).position());
        assertEquals(10, GpManagerConfig.class.getMethod("hudPlusShowItemIcons").getAnnotation(ConfigItem.class).position());
        assertEquals(11, GpManagerConfig.class.getMethod("hudPlusShowTrayTags").getAnnotation(ConfigItem.class).position());
        assertEquals(12, GpManagerConfig.class.getMethod("hudPlusShowWildernessRisk").getAnnotation(ConfigItem.class).position());
        assertTrue("Wilderness risk is shown by default, with an explicit user opt-out",
            new GpManagerConfig() { }.hudPlusShowWildernessRisk());
        GpManagerConfig defaults = new GpManagerConfig() { };
        assertEquals(10, defaults.chargeLoadReviewMinutes());
        assertEquals(10_000_000, defaults.notableDropThresholdGp());
        assertEquals(ReceiptRetentionPeriod.DAYS_90, defaults.receiptRetentionDays());
        assertEquals(2_000, defaults.maxHistorySessions());
        assertTrue(defaults.showGrandExchangeOfferWealth());
        assertTrue(defaults.showGrandExchangeCollectionWealth());
        assertTrue(defaults.wealthHistoryEnabled());
        assertTrue(defaults.wealthBankReadEnabled());
        assertEquals(0, defaults.wealthMilestoneGp());
        assertTrue(!GpManagerConfig.class.getMethod("hudPlusFloatingDrops").getAnnotation(ConfigItem.class).hidden());
        assertTrue(!GpManagerConfig.class.getMethod("hudPlusAutoResize").getAnnotation(ConfigItem.class).hidden());
        assertTrue(!GpManagerConfig.class.getMethod("hudPlusShowBankingFlashes").getAnnotation(ConfigItem.class).hidden());
    }

    @Test
    public void advancedSectionsStartCollapsed() throws Exception
    {
        assertClosedByDefault("gpDropAdvancedSection");
        assertClosedByDefault("advancedTrackingSection");
        assertClosedByDefault("hudAppearanceSection");
        assertClosedByDefault("hudPlusColorSection");
        assertClosedByDefault("lootVisibilitySection");
        assertClosedByDefault("floatingAppearanceSection");
        assertClosedByDefault("floatingAnimationSection");
        assertClosedByDefault("infoBoxContentSection");
    }

    private static void assertSection(String methodName, String expected) throws Exception
    {
        Method method = GpManagerConfig.class.getMethod(methodName);
        ConfigItem item = method.getAnnotation(ConfigItem.class);
        assertEquals(expected, item.section());
    }

    private static void assertSectionName(String fieldName, String expectedName) throws Exception
    {
        ConfigSection section = GpManagerConfig.class.getField(fieldName).getAnnotation(ConfigSection.class);
        assertEquals(expectedName, section.name());
    }

    private static void assertClosedByDefault(String fieldName) throws Exception
    {
        ConfigSection section = GpManagerConfig.class.getField(fieldName).getAnnotation(ConfigSection.class);
        assertTrue(fieldName + " should start collapsed", section.closedByDefault());
    }

    private static void assertHidden(String methodName) throws Exception
    {
        Method method = GpManagerConfig.class.getMethod(methodName);
        ConfigItem item = method.getAnnotation(ConfigItem.class);
        assertTrue(methodName + " should remain hidden", item.hidden());
    }
}
