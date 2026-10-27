package com.gpmanager;

import lombok.RequiredArgsConstructor;

import net.runelite.client.config.*;

@ConfigGroup(GpManagerConfig.GROUP)
public interface GpManagerConfig extends Config {
    String GROUP = "gpmanager";

    // Sections in SIDEBAR_SPEC.md §6b order; defaults are the product. Internal timing/limit knobs
    // stay hidden test seams; visible row counts are display preferences (owner, 2026-10-19).
    // Owner 2026-09-28: Tracking, Accounting, Loot display, then HUD+ and History collapsed. Key
    // names never change, so saved settings carry over whatever section shows them.
    @ConfigSection(name = "Tracking", description = "When and how tracking runs", position = 0)
    String generalSection = "generalSection";

    @ConfigSection(
            name = "Accounting", description = "What counts toward Net, and manual prices", position = 10)
    String accountingSection = "accountingSection";

    @ConfigSection(
            name = "Loot display", description = "What the tray, Live and Ledger lists show. Does not change Net",
            position = 20)
    String lootSection = "lootSection";

    @ConfigSection(
            name = "HUD+", description = "The HUD+ overlay. Does not change Net or Ledger", position = 30,
            closedByDefault = true)
    String hudLiveSection = "hudLiveSection";

    @ConfigSection(
            name = "History", description = "Saved Grinds and entry detail", position = 40, closedByDefault = true)
    String grindsSection = "grindsSection";

    @ConfigItem(
            keyName = "lootPresentationFilter", name = "Display loot filter", description =
                    "Hide Ground Items-hidden gains on Live and Ledger; the tray mirrors display filters. Ledger costs stay visible; totals stay unchanged",
            position = 0, section = lootSection)
    default LootFilter lootPresentationFilter() {
        return LootFilter.FOLLOW_GROUND_ITEMS;
    }

    @ConfigItem(
            keyName = "minimumDisplayedLootValue", name = "Minimum displayed loot value",
            description = "Hide gains worth less than this each on the tray and Live. 0 shows all", position = 1,
            section = lootSection)
    default String minimumDisplayedLootValue() {
        return "0";
    }

    @ConfigItem(
            keyName = "showItemIcons", name = "Item icons",
            description = "Show item and spell icons on the tray, Live and Ledger", position = 2, section = lootSection)
    default boolean showItemIcons() {
        return true;
    }

    @ConfigItem(
            keyName = "alwaysShowItems", name = "Always show items", description =
                    "Comma-separated names shown on the tray and Live regardless of Ground Items or minimum value. Hidden Recent items takes precedence",
            position = 3, section = lootSection)
    default String alwaysShowItems() {
        return "";
    }

    @ConfigItem(
            keyName = "hiddenRecentItems", name = "Hidden Recent items",
            description = "Item/spell names separated by commas, hidden from Recent only", position = 4,
            section = lootSection)
    default String hiddenRecentItems() {
        return "";
    }

    /** Keep the original key so existing charge/dose hide preferences survive the broader label. */
    @ConfigItem(
            keyName = "hiddenRecentCharges", name = "Hidden Recent supplies", description =
                    "Name patterns separated by commas: hide supply spends from Recent only; gains, Net and Ledger stay unchanged",
            position = 5, section = lootSection)
    default String hiddenRecentCharges() {
        return "";
    }

    @Range(min = 1, max = 30)
    @ConfigItem(keyName = "recentRows", name = "Recent rows",
            description = "Visible Recent rows. Does not change bookings or category tables",
            position = 6, section = lootSection)
    default int recentRows() {
        return 15;
    }

    enum PvpView { Auto, Always }

    @ConfigItem(keyName = "pvpView", name = "PvP hero view",
            description = "Auto: dangerous PvP zones. Always: show recorded stats anywhere. Risk stays zone-specific",
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

    @RequiredArgsConstructor
    enum HudTray {
        AUTO_FOLD("Auto-fold"), ALWAYS_OPEN("Always open");
        final String label;

        public String toString() {
            return label;
        }
    }

    /** Bounded inspectable-detail windows (section L). */
    @RequiredArgsConstructor
    enum Retention {
        DAYS_30("30 days", 30), DAYS_90("90 days", 90), DAYS_180("180 days", 180), DAYS_365("365 days", 365);
        final String label;
        @lombok.Getter final int days;

        public String toString() {
            return label;
        }
    }

    /**
     * Optional item eligibility filter shared by reward presentation and all local accounting
     * projections. Stored source history is never deleted.
     */
    @RequiredArgsConstructor
    enum LootFilter {
        ALL_ITEMS("All items"), FOLLOW_GROUND_ITEMS(GameData.msg("follow-ground")),
        HIGHLIGHTED_LIST_ONLY(GameData.msg("highlighted-list"));
        final String label;

        public String toString() {
            return label;
        }
    }

    @RequiredArgsConstructor
    enum HudTrayKeeps {
        STREAK("This streak"), SESSION("Whole session");
        final String label;

        public String toString() {
            return label;
        }
    }

    @ConfigItem(
            keyName = "showHud", name = "Show HUD+", description = "Show the HUD+ overlay", position = 0,
            section = hudLiveSection)
    default boolean showHud() {
        return true;
    }

    @ConfigItem(
            keyName = "hudHideWhenIdle", name = "Hide when not tracking", description =
                    "Hide HUD+ while stopped, or until Free play books an entry", position = 1,
            section = hudLiveSection)
    default boolean hudHideWhenIdle() {
        return true;
    }

    @ConfigItem(
            keyName = "hudTextSize", name = "Text size", description = "HUD+ text size", position = 9,
            section = hudLiveSection)
    default TextSize hudTextSize() {
        return TextSize.Small;
    }

    @Range(min = 120, max = 320)
    @ConfigItem(
            keyName = "hudMaxWidth", name = "Max width",
            description = "HUD+ fits its content up to this width; longer names end in …", position = 10,
            section = hudLiveSection)
    default int hudMaxWidth() {
        return 260;
    }

    @Range(max = 100)
    @ConfigItem(
            keyName = "hudOpacity", name = "Background opacity", description = "HUD+ background opacity, percent",
            position = 11, section = hudLiveSection)
    default int hudOpacity() {
        return 75;
    }

    @ConfigItem(
            keyName = "hudTimer", name = "Timer",
            description = "Counts tracked active time: compact 39m52s or full 0:39:52", position = 2,
            section = hudLiveSection)
    default HudTimer hudTimer() {
        return HudTimer.Compact;
    }

    @ConfigItem(
            keyName = "hudTray", name = "Item tray", description = "Fold the tray after loot, or keep it open",
            position = 3, section = hudLiveSection)
    default HudTray hudTray() {
        return HudTray.AUTO_FOLD;
    }

    @Range(min = 1, max = 60)
    @ConfigItem(
            keyName = "hudTraySeconds", name = "Tray stays open",
            description = "Seconds the tray stays open after loot", position = 4, section = hudLiveSection)
    default int hudTraySeconds() {
        return 5;
    }

    @Range(min = 1, max = 10)
    @ConfigItem(
            keyName = "hudTrayRows", name = "Visible item rows", description = "Tray rows before \"+N more\"",
            position = 5, section = hudLiveSection)
    default int hudTrayRows() {
        return 4;
    }

    @ConfigItem(
            keyName = "hudCompactItems", name = "Compact item rows",
            description = "Show icon, compact value and quantity; missing icons keep names",
            position = 13, section = hudLiveSection)
    default boolean hudCompactItems() {
        return false;
    }

    @ConfigItem(
            keyName = "hudSemiCompactValues", name = "Full values below 10M",
            description = "With Compact item rows, show full values below 10M; shorten only if needed to fit",
            position = 14, section = hudLiveSection)
    default boolean hudSemiCompactValues() {
        return false;
    }

    @ConfigItem(
            keyName = "hudTrayKeeps", name = "Tray keeps rows for",
            description = "Keep this streak's items, or the whole session's", position = 6, section = hudLiveSection)
    default HudTrayKeeps hudTrayKeeps() {
        return HudTrayKeeps.STREAK;
    }

    @Range(min = 10, max = 3600)
    @ConfigItem(
            keyName = "streakEndSeconds", name = "Streak ends after",
            description = "Seconds after the last kill before the HUD tray streak ends.", position = 7,
            section = hudLiveSection)
    default int streakEndSeconds() {
        return 60;
    }

    @ConfigItem(
            keyName = "reducedMotion", name = "Reduced motion",
            description = "Skip HUD+ tray entry animation; loot still shows.", position = 8, section = hudLiveSection)
    default boolean reducedMotion() {
        return false;
    }

    @Range(min = 0, max = 300)
    @ConfigItem(
            keyName = "hudTargetWarningSeconds", name = "Target countdown warning",
            description = "Flash the final countdown seconds; Reduced motion stays solid. 0 disables",
            position = 12, section = hudLiveSection)
    default int hudTargetWarningSeconds() {
        return 10;
    }

    @ConfigItem(
            keyName = "autoStartSession", name = "Automatic tracking",
            description = "Start Free play on gameplay, or on login when Start tracking on login is enabled",
            position = 0, section = generalSection)
    default boolean autoStartSession() {
        return true;
    }

    @ConfigItem(
            keyName = "autoStartOnLogin", name = "Start tracking on login",
            description = "Start or resume tracking at each RuneLite session's first login",
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
            keyName = "receiptRetentionDays", name = "Entry detail retention",
            description = "Compact entries older than this; their totals stay", position = 1, section = grindsSection)
    default Retention receiptRetentionDays() {
        return Retention.DAYS_90;
    }

    @ConfigItem(keyName = "maxTransactionsPerSession", name = "", description = "", hidden = true)
    default int maxTransactionsPerSession() {
        return 2000;
    }

    @ConfigItem(
            keyName = "enablePkTracking", name = "PK accounting",
            description = "Passively group RuneLite player-loot and confirmed player-death events", position = 2,
            section = accountingSection)
    default boolean enablePkTracking() {
        return true;
    }

    @ConfigItem(
            keyName = "countUncertainMixedChanges", name = "Count uncertain mixed changes",
            description = "Count simultaneous gains and costs without a confirmed context", position = 3,
            section = accountingSection)
    default boolean countUncertainMixedChanges() {
        return false;
    }

    @ConfigItem(
            keyName = "manualPriceOverrides", name = "Manual price overrides",
            description = "Comma-separated itemId=gp pairs, for example 995=1,1234=250", position = 5,
            section = accountingSection)
    default String manualPriceOverrides() {
        return "";
    }

    @ConfigItem(
            keyName = "ironmanAlch", name = "Ironman: value at high alch", description =
                    "Use high alch prices instead of GE for ironmen",
            position = 4, section = accountingSection)
    default boolean ironmanAlch() {
        return false;
    }

    @ConfigItem(
            keyName = "autoActivityDetection", name = "Automatic activity detection",
            description = "Name the activity from what you do", position = 2, section = generalSection)
    default boolean autoActivityDetection() {
        return true;
    }

    @Range(min = 5, max = 300)
    @ConfigItem(
            keyName = "activityLabelSeconds", name = "Activity label timeout",
            description = "Seconds without an attack before the activity label clears", position = 3,
            section = generalSection)
    default int activityLabelSeconds() {
        return 15;
    }

    @Units(Units.MINUTES)
    @Range(max = 60)
    @ConfigItem(
            keyName = "afkPauseMinutes", name = "AFK pause",
            description = "Pause tracking after this long without playing, so GP/hr leaves AFK time out (0 = off)",
            position = 4, section = generalSection)
    default int afkPauseMinutes() {
        // On by default (owner 1.5.1): only standing still pauses; skilling animates.
        return 3;
    }
}
