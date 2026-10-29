package com.gpmanager;

import lombok.AllArgsConstructor;

import net.runelite.client.config.*;

@ConfigGroup(GpManagerConfig.GROUP)
public interface GpManagerConfig extends Config {
    String GROUP = "gpmanager";

    // Sections in SIDEBAR_SPEC.md §6b order; defaults are the product. Internal timing/limit knobs
    // stay hidden test seams; visible row counts are display preferences (owner, 2026-10-19).
    // Owner 2026-09-28: Tracking, Accounting, Loot display, then HUD+ and History collapsed. Key
    // names never change, so saved settings carry over whatever section shows them.
    @ConfigSection(name = "Tracking", description = "", position = 0)
    String generalSection = "generalSection";

    @ConfigSection(
            name = "Accounting", description = "", position = 10)
    String accountingSection = "accountingSection";

    @ConfigSection(
            name = "Loot display", description = "",
            position = 20)
    String lootSection = "lootSection";

    @ConfigSection(
            name = "HUD+", description = "", position = 30,
            closedByDefault = true)
    String hudLiveSection = "hudLiveSection";

    @ConfigSection(
            name = "History", description = "", position = 40, closedByDefault = true)
    String grindsSection = "grindsSection";

    @ConfigItem(
            keyName = "lootPresentationFilter", name = "Loot filter", description = "tray mirrors display filters; Ledger costs stay visible",
            position = 0, section = lootSection)
    default LootFilter lootPresentationFilter() {
        return LootFilter.FOLLOW_GROUND_ITEMS;
    }

    @ConfigItem(
            keyName = "minimumDisplayedLootValue", name = "Minimum loot",
            description = "", position = 1,
            section = lootSection)
    default String minimumDisplayedLootValue() {
        return "0";
    }

    @ConfigItem(
            keyName = "showItemIcons", name = "Icons",
            description = "", position = 2, section = lootSection)
    default boolean showItemIcons() {
        return true;
    }

    @ConfigItem(
            keyName = "alwaysShowItems", name = "Always show", description = "",
            position = 3, section = lootSection)
    default String alwaysShowItems() {
        return "";
    }

    @ConfigItem(
            keyName = "hiddenRecentItems", name = "Hidden items",
            description = "", position = 4,
            section = lootSection)
    default String hiddenRecentItems() {
        return "";
    }

    /** Keep the original key so existing charge/dose hide preferences survive the broader label. */
    @ConfigItem(
            keyName = "hiddenRecentCharges", name = "Hidden Recent supplies", description = "",
            position = 5, section = lootSection)
    default String hiddenRecentCharges() {
        return "";
    }

    @Range(min = 1, max = 30)
    @ConfigItem(keyName = "recentRows", name = "Recent rows",
            description = "",
            position = 6, section = lootSection)
    default int recentRows() {
        return 15;
    }

    enum PvpView { Auto, Always }

    @ConfigItem(keyName = "pvpView", name = "PvP view",
            description = "",
            position = 7, section = lootSection)
    default PvpView pvpView() {
        return PvpView.Auto;
    }

    enum TextSize {
        Small, Normal, Large
    }

    enum HudTimer {
        Compact, Full
    }

    @AllArgsConstructor
    enum HudTray {
        AUTO_FOLD("Auto-fold"), ALWAYS_OPEN("Always open");
        final String label;

        public String toString() {
            return label;
        }
    }

    /** Bounded inspectable-detail windows (section L). */
    @AllArgsConstructor
    enum Retention {
        DAYS_30(30), DAYS_90(90), DAYS_180(180), DAYS_365(365);
        @lombok.Getter final int days;

        public String toString() {
            return days + " days";
        }
    }

    /**
     * Optional item eligibility filter shared by reward presentation and all local accounting
     * projections. Stored source history is never deleted.
     */
    @AllArgsConstructor
    enum LootFilter {
        ALL_ITEMS("All items"), FOLLOW_GROUND_ITEMS(GameData.msg("follow-ground")),
        HIGHLIGHTED_LIST_ONLY(GameData.msg("highlighted-list"));
        final String label;

        public String toString() {
            return label;
        }
    }

    @AllArgsConstructor
    enum HudTrayKeeps {
        STREAK("This streak"), SESSION("Whole session");
        final String label;

        public String toString() {
            return label;
        }
    }

    @ConfigItem(
            keyName = "showHud", name = "Show HUD+", description = "", position = 0,
            section = hudLiveSection)
    default boolean showHud() {
        return true;
    }

    @ConfigItem(
            keyName = "hudHideWhenIdle", name = "Hide when idle", description = "", position = 1,
            section = hudLiveSection)
    default boolean hudHideWhenIdle() {
        return true;
    }

    @ConfigItem(
            keyName = "hudTextSize", name = "Text size", description = "", position = 9,
            section = hudLiveSection)
    default TextSize hudTextSize() {
        return TextSize.Small;
    }

    @Range(min = 120, max = 320)
    @ConfigItem(
            keyName = "hudMaxWidth", name = "Max width",
            description = "", position = 10,
            section = hudLiveSection)
    default int hudMaxWidth() {
        return 260;
    }

    @Range(max = 100)
    @ConfigItem(
            keyName = "hudOpacity", name = "Background opacity", description = "",
            position = 11, section = hudLiveSection)
    default int hudOpacity() {
        return 75;
    }

    @ConfigItem(
            keyName = "hudTimer", name = "Timer",
            description = "tracked active time", position = 2,
            section = hudLiveSection)
    default HudTimer hudTimer() {
        return HudTimer.Compact;
    }

    @ConfigItem(
            keyName = "hudTray", name = "Item tray", description = "",
            position = 3, section = hudLiveSection)
    default HudTray hudTray() {
        return HudTray.AUTO_FOLD;
    }

    @Range(min = 1, max = 60)
    @ConfigItem(
            keyName = "hudTraySeconds", name = "Tray seconds",
            description = "", position = 4, section = hudLiveSection)
    default int hudTraySeconds() {
        return 5;
    }

    @Range(min = 1, max = 10)
    @ConfigItem(
            keyName = "hudTrayRows", name = "Item rows", description = "",
            position = 5, section = hudLiveSection)
    default int hudTrayRows() {
        return 4;
    }

    @ConfigItem(
            keyName = "hudCompactItems", name = "Compact rows",
            description = "",
            position = 13, section = hudLiveSection)
    default boolean hudCompactItems() {
        return false;
    }

    @ConfigItem(
            keyName = "hudSemiCompactValues", name = "Full values",
            description = "",
            position = 14, section = hudLiveSection)
    default boolean hudSemiCompactValues() {
        return false;
    }

    @ConfigItem(
            keyName = "hudTrayKeeps", name = "Tray keeps",
            description = "", position = 6, section = hudLiveSection)
    default HudTrayKeeps hudTrayKeeps() {
        return HudTrayKeeps.STREAK;
    }

    @Range(min = 10, max = 3600)
    @ConfigItem(
            keyName = "streakEndSeconds", name = "Streak ends",
            description = "", position = 7,
            section = hudLiveSection)
    default int streakEndSeconds() {
        return 60;
    }

    @ConfigItem(
            keyName = "reducedMotion", name = "Reduced motion",
            description = "", position = 8, section = hudLiveSection)
    default boolean reducedMotion() {
        return false;
    }

    @Range(min = 0, max = 300)
    @ConfigItem(
            keyName = "hudTargetWarningSeconds", name = "Countdown warning",
            description = "",
            position = 12, section = hudLiveSection)
    default int hudTargetWarningSeconds() {
        return 10;
    }

    @ConfigItem(
            keyName = "autoStartSession", name = "Auto tracking",
            description = "Free play",
            position = 0, section = generalSection)
    default boolean autoStartSession() {
        return true;
    }

    @ConfigItem(
            keyName = "autoStartOnLogin", name = "Start on login",
            description = "",
            position = 1, section = generalSection)
    default boolean autoStartOnLogin() {
        return true;
    }

    @ConfigItem(keyName = "stabilizationTicks", name = "", description = "", hidden = true)
    default int stabilizationTicks() {
        return 2;
    }

    @ConfigItem(keyName = "keepTransferAuditRows", name = "", description = "", hidden = true)
    default boolean keepTransferAuditRows() {
        return false;
    }

    @ConfigItem(keyName = "maxHistorySessions", name = "", description = "", hidden = true)
    default int maxHistorySessions() {
        return 2000;
    }

    @ConfigItem(
            keyName = "receiptRetentionDays", name = "Detail retention",
            description = "", position = 1, section = grindsSection)
    default Retention receiptRetentionDays() {
        return Retention.DAYS_90;
    }

    @ConfigItem(keyName = "maxTransactionsPerSession", name = "", description = "", hidden = true)
    default int maxTransactionsPerSession() {
        return 2000;
    }

    @ConfigItem(
            keyName = "enablePkTracking", name = "PK accounting",
            description = "", position = 2,
            section = accountingSection)
    default boolean enablePkTracking() {
        return true;
    }

    @ConfigItem(
            keyName = "countUncertainMixedChanges", name = "Mixed changes",
            description = "", position = 3,
            section = accountingSection)
    default boolean countUncertainMixedChanges() {
        return false;
    }

    @ConfigItem(
            keyName = "manualPriceOverrides", name = "Price overrides",
            description = "", position = 5,
            section = accountingSection)
    default String manualPriceOverrides() {
        return "";
    }

    @ConfigItem(
            keyName = "ironmanAlch", name = "Ironman high alch", description = "",
            position = 4, section = accountingSection)
    default boolean ironmanAlch() {
        return false;
    }

    @ConfigItem(
            keyName = "autoActivityDetection", name = "Detect activity",
            description = "", position = 2, section = generalSection)
    default boolean autoActivityDetection() {
        return true;
    }

    @Range(min = 5, max = 300)
    @ConfigItem(
            keyName = "activityLabelSeconds", name = "Label timeout",
            description = "", position = 3,
            section = generalSection)
    default int activityLabelSeconds() {
        return 15;
    }

    @Units(Units.MINUTES)
    @Range(max = 60)
    @ConfigItem(
            keyName = "afkPauseMinutes", name = "AFK pause",
            description = "",
            position = 4, section = generalSection)
    default int afkPauseMinutes() {
        // On by default (owner 1.5.1): only standing still pauses; skilling animates.
        return 3;
    }
}
