package com.gpmanager;

import java.awt.Color;
import com.gpmanager.model.SessionMode;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.FontType;
import net.runelite.client.config.Range;

@ConfigGroup(GpManagerConfig.GROUP)
public interface GpManagerConfig extends Config
{
    String GROUP = "profitmanager";

    // Sections ordered by panel position. Advanced / look-and-feel groups collapse by default
    // (Profit Tracker–style Visual vs Behaviour split; XP Tracker–style Overlay vs panel).

    @ConfigSection(
        name = "General",
        description = "Core tracker and panel behaviour",
        position = 0
    )
    String generalSection = "generalSection";

    @ConfigSection(
        name = "Session tracking",
        description = "Session start, history, and local retention",
        position = 10
    )
    String sessionSection = "sessionSection";

    @ConfigSection(
        name = "Activity detection",
        description = "Activity labels, idle timing, and PK grouping",
        position = 20
    )
    String activitySection = "activitySection";

    @ConfigSection(
        name = "Sidebar Item changes",
        description = "How many Live sidebar item rows show before Expand",
        position = 25
    )
    String itemChangesSection = "itemChangesSection";

    @ConfigSection(
        name = "Sidebar",
        description = "Sidebar appearance and layout",
        position = 26
    )
    String sidebarSection = "sidebarSection";

    @ConfigItem(
        keyName = "sidebarAccent",
        name = "Accent",
        description = "Sidebar accent colour.",
        position = 0,
        section = sidebarSection
    )
    default com.gpmanager.ui.bento.BentoTheme.Accent sidebarAccent()
    {
        return com.gpmanager.ui.bento.BentoTheme.Accent.MINT;
    }

    @ConfigItem(
        keyName = "pvpLayoutMode",
        name = "PvP layout",
        description = "Auto switches the sidebar and HUD+ to the PvP layout in the Wilderness, on PvP worlds and in PK runs. "
            + "Never keeps the General layout everywhere (Wilderness slayer, Rogues' chest, the agility course).",
        position = 2,
        section = sidebarSection
    )
    default com.gpmanager.ui.bento.PvpMode pvpLayoutMode()
    {
        return com.gpmanager.ui.bento.PvpMode.AUTO;
    }

    @ConfigItem(
        keyName = "boundarySlayer",
        name = "Slayer task boundary",
        description = "Off: nothing. Mark: dots on the Live ribbon when a task is assigned and done. "
            + "New session: a session named after the task starts at the first kill and ends when the task completes.",
        position = 10,
        section = sidebarSection
    )
    default com.gpmanager.ui.bento.BoundaryMode boundarySlayer()
    {
        return com.gpmanager.ui.bento.BoundaryMode.OFF;
    }

    @ConfigItem(
        keyName = "boundaryRaid",
        name = "Raid boundary",
        description = "Off: nothing. Mark: dots on the Live ribbon entering and leaving a raid. "
            + "New session: a session named after the raid (CoX, ToB, ToA) starts on entry and ends when you leave.",
        position = 11,
        section = sidebarSection
    )
    default com.gpmanager.ui.bento.BoundaryMode boundaryRaid()
    {
        return com.gpmanager.ui.bento.BoundaryMode.OFF;
    }

    @ConfigItem(
        keyName = "boundaryBank",
        name = "Bank visit boundary",
        description = "Off: nothing. Mark: a dot on the Live ribbon each time you close the bank. "
            + "New session: closing the bank ends the live session and starts another with the same name (one per trip).",
        position = 12,
        section = sidebarSection
    )
    default com.gpmanager.ui.bento.BoundaryMode boundaryBank()
    {
        return com.gpmanager.ui.bento.BoundaryMode.OFF;
    }

    @ConfigItem(
        keyName = "alertGoalReached",
        name = "Goal reached alert",
        description = "A toast in the sidebar when the session goal is reached.",
        position = 13,
        section = sidebarSection
    )
    default boolean alertGoalReached()
    {
        return true;
    }

    @ConfigItem(
        keyName = "sessionIdleAutoEndMinutes",
        name = "End session after idle",
        description = "Automatically end a named session after this many minutes idle (0 = off). Free play is never auto-ended.",
        position = 14,
        section = sidebarSection
    )
    default int sessionIdleAutoEndMinutes()
    {
        return 0;
    }

    @ConfigItem(
        keyName = "sidebarExactFigures",
        name = "Exact figures",
        description = "Show the full number on the net card (−6,544) instead of the compact form (−6.5k).",
        position = 15,
        section = sidebarSection
    )
    default boolean sidebarExactFigures()
    {
        return false;
    }

    @ConfigItem(
        keyName = "sidebarNetGraph",
        name = "Net graph",
        description = "Draw the session net sparkline behind the Live figure.",
        position = 3,
        section = sidebarSection
    )
    default boolean sidebarNetGraph()
    {
        return false;
    }

    @ConfigSection(
        name = "Display selection",
        description = "Where the tracker appears: HUD+, HUD, Infobox, or Off",
        position = 28
    )
    String infoBoxSection = "infoBoxSection";

    @ConfigSection(
        name = "HUD+",
        description = "HUD+ size, opacity, timer, and density",
        position = 29
    )
    String hudPlusSection = "hudPlusSection";

    @ConfigSection(
        name = "HUD+ - Item tray",
        description = "HUD+ reward tray, dwell, and visible item rows",
        position = 30
    )
    String hudTraySection = "hudTraySection";

    @ConfigSection(
        name = "HUD+ - Colours",
        description = "HUD+ colours for metrics, header, and labels",
        position = 31,
        closedByDefault = true
    )
    String hudPlusColorSection = "hudPlusColorSection";

    @ConfigSection(
        name = "HUD",
        description = "Legacy HUD layout only",
        position = 32
    )
    String hudSection = "hudSection";

    @ConfigSection(
        name = "HUD - Custom rows",
        description = "Optional rows when HUD detail is Custom",
        position = 33,
        closedByDefault = true
    )
    String infoBoxContentSection = "infoBoxContentSection";

    @ConfigSection(
        name = "HUD appearance - Shared",
        description = "Shared theme background for HUD+ and legacy HUD",
        position = 34,
        closedByDefault = true
    )
    String hudAppearanceSection = "hudAppearanceSection";

    @ConfigSection(
        name = "Loot visibility",
        description = "What tray and floating drops show. Does not change Net or Ledger",
        position = 35,
        closedByDefault = true
    )
    String lootVisibilitySection = "lootVisibilitySection";

    @ConfigSection(
        name = "Floating drops",
        description = "Floating GP-drop preset, visibility, and hang time",
        position = 40
    )
    String liveGpDropsSection = "liveGpDropsSection";

    @ConfigSection(
        name = "Floating drops - Appearance",
        description = "Floating drop text, colours, and spacing",
        position = 41,
        closedByDefault = true
    )
    String floatingAppearanceSection = "floatingAppearanceSection";

    @ConfigSection(
        name = "Floating drops - Animation",
        description = "Floating drop motion and fading",
        position = 42,
        closedByDefault = true
    )
    String floatingAnimationSection = "floatingAnimationSection";

    @ConfigSection(
        name = "Floating drops - Custom preset",
        description = "Extra floating controls when preset is Custom",
        position = 43,
        closedByDefault = true
    )
    String gpDropAdvancedSection = "gpDropAdvancedSection";

    @ConfigSection(
        name = "Pricing and item rules",
        description = "Thresholds, exclusions, and manual prices",
        position = 50
    )
    String pricingSection = "pricingSection";

    @ConfigSection(
        name = "Insights and Wealth",
        description = "Notable drop threshold and read-only wealth locations",
        position = 55
    )
    String insightsWealthSection = "insightsWealthSection";

    @ConfigSection(
        name = "Advanced tracking",
        description = "Correlation timing and optional Net exclusions",
        position = 60,
        closedByDefault = true
    )
    String advancedTrackingSection = "advancedTrackingSection";

    @ConfigSection(
        name = "Party tracker",
        description = "Share session totals with your RuneLite Party",
        position = 70
    )
    String partySection = "partySection";

    @ConfigItem(
        keyName = "autoResumeOnActivity",
        name = "Auto-resume paused session (legacy)",
        description = "Kept for saved settings",
        position = 0,
        section = sessionSection,
        hidden = true
    )
    default boolean autoResumeOnActivity()
    {
        return false;
    }

    @ConfigItem(
        keyName = "trackingDisplay",
        name = "Tracking display",
        description = "HUD+, HUD, Infobox, or Off",
        position = 0,
        section = infoBoxSection
    )
    default TrackingDisplay trackingDisplay()
    {
        return TrackingDisplay.HUD_PLUS;
    }

    @ConfigItem(
        keyName = "showOverlay",
        name = "Show HUD (legacy)",
        description = "Kept for saved settings. Prefer Tracking display",
        position = 1,
        section = infoBoxSection,
        hidden = true
    )
    default boolean showOverlay()
    {
        return true;
    }

    @ConfigItem(
        keyName = "infoBoxView",
        name = "HUD detail",
        description = "Legacy HUD layout. Custom unlocks HUD - Custom rows",
        position = 0,
        section = hudSection
    )
    default InfoBoxView infoBoxView()
    {
        return InfoBoxView.COMPACT;
    }

    @ConfigItem(
        keyName = "hudPlusFloatingDrops",
        name = "HUD+ floating drops",
        description = "Also show floating GP drops on HUD+. Off keeps tray-only feedback",
        position = 1,
        section = infoBoxSection
    )
    default boolean hudPlusFloatingDrops()
    {
        return false;
    }

    @ConfigItem(
        keyName = "feedbackStyle",
        name = "Change feedback (legacy)",
        description = "Kept for saved settings. Replaced by Tracking display",
        position = 2,
        section = infoBoxSection,
        hidden = true
    )
    default FeedbackStyle feedbackStyle()
    {
        return FeedbackStyle.FLOATING;
    }

    @ConfigItem(
        keyName = "lootPresentationFilter",
        name = "Display loot filter",
        description = "Hide Ground Items-hidden gains on tray, drops, Live lists, Ledger, and Insights. Costs stay visible unless Accounting exclusions hide them",
        position = 0,
        section = lootVisibilitySection
    )
    default LootPresentationFilter lootPresentationFilter()
    {
        return LootPresentationFilter.FOLLOW_GROUND_ITEMS;
    }

    @ConfigItem(
        keyName = "accountingItemFilter",
        name = "Accounting exclusions (Net)",
        description = "Exclude collected items from Net using Ground Items rules. Default All items counts everything",
        position = 0,
        section = advancedTrackingSection
    )
    default LootPresentationFilter accountingItemFilter()
    {
        return LootPresentationFilter.ALL_ITEMS;
    }

    @ConfigItem(
        keyName = "minimumDisplayedLootValue",
        name = "Minimum displayed loot value",
        description = "Hide gains below this unit GE value on tray and drops. Does not change Net. 0 shows all",
        position = 1,
        section = lootVisibilitySection
    )
    default String minimumDisplayedLootValue()
    {
        return "0";
    }

    @ConfigItem(
        keyName = "reuseGroundItemsHighlightColors",
        name = "Reuse Ground Items colours",
        description = "Reuse Ground Items highlight colours on visible loot rows",
        position = 2,
        section = lootVisibilitySection
    )
    default boolean reuseGroundItemsHighlightColors()
    {
        return false;
    }

    @ConfigItem(
        keyName = "hudPlusAutoResize",
        name = "Auto Resize",
        description = "Hug content width up to 320px. Off uses Max width",
        position = 0,
        section = hudPlusSection
    )
    default boolean hudPlusAutoResize()
    {
        return true;
    }

    @Range(min = 120, max = 320)
    @ConfigItem(
        keyName = "hudPlusWidth",
        name = "Max width",
        description = "Fixed Trip and folio width when Auto Resize is off (120–320)",
        position = 1,
        section = hudPlusSection
    )
    default int hudPlusWidth()
    {
        return 260;
    }

    @Range(min = 0, max = 480)
    @ConfigItem(
        keyName = "hudPlusMaxHeight",
        name = "Max height",
        description = "Kept for saved settings. Height always hugs content",
        position = 2,
        section = hudPlusSection,
        hidden = true
    )
    default int hudPlusMaxHeight()
    {
        return 0;
    }

    @ConfigItem(
        keyName = "hudPlusCompactTimer",
        name = "Compact timer",
        description = "Show elapsed as 4h10m. Off uses HH:MM:SS",
        position = 3,
        section = hudPlusSection
    )
    default boolean hudPlusCompactTimer()
    {
        return true;
    }

    @ConfigItem(
        keyName = "hudPlusHoverFullTitle",
        name = "Hover full title",
        description = "Tooltip with the full activity title on hover",
        position = 4,
        section = hudPlusSection
    )
    default boolean hudPlusHoverFullTitle()
    {
        return false;
    }

    @Range(min = 50, max = 100)
    @ConfigItem(
        keyName = "hudPlusBackgroundOpacity",
        name = "Background opacity",
        description = "HUD+ shell background opacity (50–100%)",
        position = 5,
        section = hudPlusSection
    )
    default int hudPlusBackgroundOpacity()
    {
        return 90;
    }

    @ConfigItem(
        keyName = "hudPlusTextSize",
        name = "HUD+ text size",
        description = "Small, Normal, or Large text and icons.",
        position = 6,
        section = hudPlusSection
    )
    default HudPlusTextSize hudPlusTextSize()
    {
        return HudPlusTextSize.NORMAL;
    }

    @ConfigItem(
        keyName = "hudPlusDensity",
        name = "Density",
        description = "Glance = metrics only. Standard adds target. Trip adds the reward tray.",
        position = 7,
        section = hudPlusSection
    )
    default HudPlusDensity hudPlusDensity()
    {
        return HudPlusDensity.TRIP;
    }

    @ConfigItem(
        keyName = "hudPlusShowActivityHeader",
        name = "Show activity header",
        description = "Show activity name, status gem, and timer.",
        position = 8,
        section = hudPlusSection
    )
    default boolean hudPlusShowActivityHeader()
    {
        return true;
    }

    @ConfigItem(
        keyName = "hudPlusShowMetricLabels",
        name = "Show metric labels",
        description = "Show GP/hr: and Total: text beside the numbers.",
        position = 9,
        section = hudPlusSection
    )
    default boolean hudPlusShowMetricLabels()
    {
        return true;
    }

    @ConfigItem(
        keyName = "hudPlusShowItemIcons",
        name = "Show item icons",
        description = "Show item sprites on the tray and folio.",
        position = 10,
        section = hudPlusSection
    )
    default boolean hudPlusShowItemIcons()
    {
        return false;
    }

    @ConfigItem(
        keyName = "hudPlusShowTrayTags",
        name = "Show tray tags",
        description = "Show Chopped, Ground Loot, and similar tray labels.",
        position = 11,
        section = hudPlusSection
    )
    default boolean hudPlusShowTrayTags()
    {
        return true;
    }

    @ConfigItem(
        keyName = "hudPlusShowWildernessRisk",
        name = "Show Wilderness risk",
        description = "Show measured carried-value risk while in a PvP-capable area; unknown prices show ?",
        position = 12,
        section = hudPlusSection
    )
    default boolean hudPlusShowWildernessRisk()
    {
        return true;
    }

    @Range(min = 80, max = 480)
    @ConfigItem(
        keyName = "liveItemGridMaxHeight",
        name = "Live item grid max height",
        description = "Max height of the Live sidebar item list (80–480)",
        position = 1,
        section = itemChangesSection
    )
    default int liveItemGridMaxHeight()
    {
        return 220;
    }

    @ConfigItem(
        keyName = "hudPlusRewardPresentation",
        name = "Reward tray",
        description = "Auto-collapse folds after dwell. Always expanded stays open across skills",
        position = 0,
        section = hudTraySection
    )
    default HudPlusRewardPresentation hudPlusRewardPresentation()
    {
        return HudPlusRewardPresentation.AUTO_COLLAPSE;
    }

    @ConfigItem(
        keyName = "hudPlusDetailTrigger",
        name = "Detail panel",
        description = "Open the Gained/Spent folio beside HUD+. Hover or Shift — not Alt",
        position = 1,
        section = hudTraySection
    )
    default HudPlusDetailTrigger hudPlusDetailTrigger()
    {
        return HudPlusDetailTrigger.HOVER_OR_SHIFT;
    }

    @Range(min = 1500, max = 8000)
    @ConfigItem(
        keyName = "hudPlusRevealDurationMillis",
        name = "Tray dwell",
        description = "How long the item tray stays open after loot settles (1500–8000 ms)",
        position = 2,
        section = hudTraySection
    )
    default int hudPlusRevealDurationMillis()
    {
        return 5000;
    }

    @Range(min = 1, max = 5)
    @ConfigItem(
        keyName = "hudPlusLootCoalesceTicks",
        name = "Loot coalesce ticks",
        description = "Ticks to stack same-NPC ground loot before the tray countdown",
        position = 3,
        section = hudTraySection
    )
    default int hudPlusLootCoalesceTicks()
    {
        return 2;
    }

    @ConfigItem(
        keyName = "hudPlusAccumulationMode",
        name = "Reward accumulation",
        description = "How long NPC loot rows stay on the tray. Does not change Net",
        position = 4,
        section = hudTraySection
    )
    default HudPlusAccumulationMode hudPlusAccumulationMode()
    {
        return HudPlusAccumulationMode.SESSION;
    }

    @Range(min = 0, max = 60)
    @ConfigItem(
        keyName = "hudPlusStreakInactivityMinutes",
        name = "Reset streak after inactivity",
        description = "Streak only: minutes idle before that source's tray streak resets. 0 = never",
        position = 5,
        section = hudTraySection
    )
    default int hudPlusStreakInactivityMinutes()
    {
        return 0;
    }

    @Range(min = 3, max = 10)
    @ConfigItem(
        keyName = "hudPlusMaxRewardRows",
        name = "Visible item rows",
        description = "Max distinct item rows on tray and folio (3–10)",
        position = 6,
        section = hudTraySection
    )
    default int hudPlusMaxRewardRows()
    {
        return 5;
    }

    @ConfigItem(
        keyName = "hudPlusShowBankingFlashes",
        name = "Show banking flashes",
        description = "Brief Deposit / Withdrew flashes on the tray",
        position = 7,
        section = hudTraySection
    )
    default boolean hudPlusShowBankingFlashes()
    {
        return true;
    }

    @Alpha
    @ConfigItem(
        keyName = "hudPlusProfitColor",
        name = "HUD+ profit colour",
        description = "Colour for positive GP/hr and Total",
        position = 0,
        section = hudPlusColorSection
    )
    default Color hudPlusProfitColor()
    {
        return new Color(0x5C, 0xFF, 0xB0);
    }

    @Alpha
    @ConfigItem(
        keyName = "hudPlusLossColor",
        name = "HUD+ loss colour",
        description = "Colour for negative GP/hr and Total",
        position = 1,
        section = hudPlusColorSection
    )
    default Color hudPlusLossColor()
    {
        return new Color(0xFF, 0x6B, 0x6B);
    }

    @Alpha
    @ConfigItem(
        keyName = "hudPlusNeutralColor",
        name = "HUD+ neutral colour",
        description = "Colour for zero and other neutral amounts",
        position = 2,
        section = hudPlusColorSection
    )
    default Color hudPlusNeutralColor()
    {
        return new Color(0xE8, 0xEC, 0xF4);
    }

    @Alpha
    @ConfigItem(
        keyName = "hudPlusHeaderColor",
        name = "HUD+ header colour",
        description = "Colour for activity name and timer",
        position = 3,
        section = hudPlusColorSection
    )
    default Color hudPlusHeaderColor()
    {
        return new Color(0xE8, 0xEC, 0xF4);
    }

    @Alpha
    @ConfigItem(
        keyName = "hudPlusLabelColor",
        name = "HUD+ label colour",
        description = "Colour for secondary labels",
        position = 4,
        section = hudPlusColorSection
    )
    default Color hudPlusLabelColor()
    {
        return new Color(0x8B, 0x93, 0xA7);
    }

    @Alpha
    @ConfigItem(
        keyName = "hudPlusAccentColor",
        name = "HUD+ accent colour",
        description = "Colour for status gem and tray accents",
        position = 5,
        section = hudPlusColorSection
    )
    default Color hudPlusAccentColor()
    {
        return new Color(0x8B, 0x93, 0xA7);
    }

    @ConfigItem(
        keyName = "reducedMotion",
        name = "Reduced motion",
        description = "Skip tray entry animation and floating-drop motion; loot still shows.",
        position = 3,
        section = infoBoxSection
    )
    default boolean reducedMotion()
    {
        return false;
    }

    @ConfigItem(
        keyName = "keepRewardTrayExpanded",
        name = "Keep reward tray expanded (legacy)",
        description = "Kept for saved settings. Prefer Reward tray = Always expanded.",
        position = 5,
        section = hudTraySection,
        hidden = true
    )
    default boolean keepRewardTrayExpanded()
    {
        return false;
    }

    @Range(min = 3, max = 10)
    @ConfigItem(
        keyName = "itemChangesTopN",
        name = "Item changes Top N",
        description = "Live sidebar item rows before Expand (3–10).",
        position = 0,
        section = itemChangesSection
    )
    default int itemChangesTopN()
    {
        return 5;
    }

    @ConfigItem(
        keyName = "enableDebugTrace",
        name = "Debug event trace",
        description = "Local diagnostic event log. Off by default; never uploads.",
        position = 0,
        section = advancedTrackingSection
    )
    default boolean enableDebugTrace()
    {
        return false;
    }

    @ConfigItem(
        keyName = "infoBoxTitleMode",
        name = "Title",
        description = "Kept for saved settings.",
        position = 4,
        section = infoBoxSection,
        hidden = true
    )
    default InfoBoxTitleMode infoBoxTitleMode()
    {
        return InfoBoxTitleMode.ACTIVITY;
    }

    @ConfigItem(
        keyName = "infoBoxNumberFormat",
        name = "Number format",
        description = "Compact (1.2k) or exact digits for HUD+, HUD, and Infobox.",
        position = 4,
        section = infoBoxSection
    )
    default InfoBoxNumberFormat infoBoxNumberFormat()
    {
        return InfoBoxNumberFormat.COMPACT;
    }

    @ConfigItem(
        keyName = "autoStartSession",
        name = "Automatic tracking",
        description = "Start Overall tracking when gameplay is detected. Login alone does not start a session.",
        position = 1,
        section = sessionSection
    )
    default boolean autoStartSession()
    {
        return true;
    }

    @ConfigItem(
        keyName = "defaultSessionName",
        name = "Default session name (legacy)",
        description = "Kept for saved settings. Overall is always used.",
        position = 2,
        section = sessionSection,
        hidden = true
    )
    default String defaultSessionName()
    {
        return "Overall";
    }

    @ConfigItem(
        keyName = "includeEquipment",
        name = "Include equipment",
        description = "Combine equipment with inventory so gear swaps remain neutral",
        position = 1,
        section = generalSection
    )
    default boolean includeEquipment()
    {
        return true;
    }

    @ConfigItem(
        keyName = "includeRunePouch",
        name = "Include rune pouch",
        description = "Count runes stored in the rune pouch so filling it stays neutral and casting from it is a cost",
        position = 2,
        section = generalSection
    )
    default boolean includeRunePouch()
    {
        return true;
    }

    @ConfigItem(
        keyName = "ignoreUnpricedItems",
        name = "Ignore unpriced items",
        description = "Exclude items with no manual, market, or high-alchemy price.",
        position = 3,
        section = generalSection
    )
    default boolean ignoreUnpricedItems()
    {
        return false;
    }

    @ConfigItem(
        keyName = "useHighAlchemyFallback",
        name = "Use high-alchemy fallback",
        description = "Value unpriced items with high alchemy after manual and market prices.",
        position = 0,
        section = pricingSection
    )
    default boolean useHighAlchemyFallback()
    {
        return false;
    }

    @Range(min = 0, max = 10000000)
    @ConfigItem(
        keyName = "minimumTransactionValue",
        name = "Minimum accounting value (advanced)",
        description = "Skip correlated changes below this total GP from Net accounting.",
        position = 1,
        section = pricingSection
    )
    default int minimumTransactionValue()
    {
        return 1;
    }

    @Range(min = 1, max = 10)
    @ConfigItem(
        keyName = "stabilizationTicks",
        name = "Stabilization ticks",
        description = "Quiet ticks required before a combined inventory/equipment change is committed",
        position = 1,
        section = advancedTrackingSection
    )
    default int stabilizationTicks()
    {
        return 2;
    }

    @Range(min = 1, max = 30)
    @ConfigItem(
        keyName = "correlationTicks",
        name = "Correlation ticks",
        description = "How many idle ticks bank, market, and loot context remains available",
        position = 2,
        section = advancedTrackingSection
    )
    default int correlationTicks()
    {
        return 6;
    }

    @Range(min = 1, max = 120)
    @ConfigItem(
        keyName = "rollingRateMinutes",
        name = "Rate window minutes",
        description = "Window used for the live Rate GP/hour metric",
        position = 3,
        section = advancedTrackingSection
    )
    default int rollingRateMinutes()
    {
        return 15;
    }

    @Range(min = 1, max = 300)
    @ConfigItem(
        keyName = "lootCorrelationSeconds",
        name = "Loot pickup memory",
        description = "Seconds an NPC drop remains available for delayed or partial pickup matching",
        position = 4,
        section = advancedTrackingSection
    )
    default int lootCorrelationSeconds()
    {
        return 30;
    }

    @Range(min = 1, max = 60)
    @ConfigItem(
        keyName = "chargeLoadReviewMinutes",
        name = "Charge load Check window",
        description = "Minutes a charge-load Review row may wait for a same-item Check before it remains owner-review only",
        position = 6,
        section = advancedTrackingSection
    )
    default int chargeLoadReviewMinutes()
    {
        return 10;
    }

    @ConfigItem(
        keyName = "showPhase2Insights",
        name = "Show activity insights",
        description = "Show passive profit-per-action and per-activity breakdowns",
        position = 4,
        section = generalSection
    )
    default boolean showPhase2Insights()
    {
        return true;
    }

    @Range(min = 1, max = 2147483647)
    @ConfigItem(
        keyName = "notableDropThresholdGp",
        name = "Notable drop threshold",
        description = "Minimum item value for an Insights milestone when durable encounter evidence is available",
        position = 0,
        section = insightsWealthSection
    )
    default int notableDropThresholdGp()
    {
        return 10_000_000;
    }

    @ConfigItem(
        keyName = "showGrandExchangeOfferWealth",
        name = "Show Grand Exchange offers in Wealth",
        description = "Show remaining active sell offers at their offer price; never included in Net",
        position = 1,
        section = insightsWealthSection
    )
    default boolean showGrandExchangeOfferWealth()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showGrandExchangeCollectionWealth",
        name = "Show collection box in Wealth",
        description = "Read visible collection-box contents at cached market/face value; never included in Net",
        position = 2,
        section = insightsWealthSection
    )
    default boolean showGrandExchangeCollectionWealth()
    {
        return true;
    }

    @ConfigItem(
        keyName = "wealthHistoryEnabled",
        name = "Track wealth history",
        description = "Save read-only wealth snapshots for change-over-time estimates; never changes Net",
        position = 3,
        section = insightsWealthSection
    )
    default boolean wealthHistoryEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "wealthBankReadEnabled",
        name = "Read bank for wealth history",
        description = "Capture bank contents once after they stabilize during each open-bank visit",
        position = 4,
        section = insightsWealthSection
    )
    default boolean wealthBankReadEnabled()
    {
        return true;
    }

    @Range(min = 0, max = 2000000000)
    @ConfigItem(
        keyName = "wealthMilestoneGp",
        name = "Future wealth milestone (GP)",
        description = "Stored threshold for a later wealth notification; 0 disables the future hook",
        position = 5,
        section = insightsWealthSection
    )
    default int wealthMilestoneGp()
    {
        return 0;
    }

    @ConfigItem(
        keyName = "keepTransferAuditRows",
        name = "Keep transfer audit rows",
        description = "Store excluded bank/deposit transfers in session history",
        position = 5,
        section = advancedTrackingSection
    )
    default boolean keepTransferAuditRows()
    {
        return false;
    }

    @ConfigItem(
        keyName = "persistHistory",
        name = "Persist history",
        description = "Save sessions locally between RuneLite launches",
        position = 3,
        section = sessionSection
    )
    default boolean persistHistory()
    {
        return true;
    }

    @Range(min = 1, max = 5000)
    @ConfigItem(
        keyName = "maxHistorySessions",
        name = "Maximum saved sessions",
        description = "Maximum number of completed sessions kept locally, including compacted summaries",
        position = 4,
        section = sessionSection
    )
    default int maxHistorySessions()
    {
        return 2_000;
    }

    @ConfigItem(
        keyName = "receiptRetentionDays",
        name = "Receipt detail retention",
        description = "Compact closed sessions older than this window; session summaries remain saved",
        position = 5,
        section = sessionSection
    )
    default ReceiptRetentionPeriod receiptRetentionDays()
    {
        return ReceiptRetentionPeriod.DAYS_90;
    }

    @Range(min = 100, max = 100000)
    @ConfigItem(
        keyName = "maxTransactionsPerSession",
        name = "Maximum transactions",
        description = "Max detail rows per session; totals keep older revenue and costs.",
        position = 6,
        section = sessionSection
    )
    default int maxTransactionsPerSession()
    {
        return 2_000;
    }

    @ConfigItem(
        keyName = "enablePartyTracking",
        name = "Enable party tracker",
        description = "Share your current session totals with members of your RuneLite Party",
        position = 0,
        section = partySection
    )
    default boolean enablePartyTracking()
    {
        return false;
    }

    @ConfigItem(
        keyName = "showPartyMetricsInOverlay",
        name = "Party totals in Detailed / Custom HUD",
        description = "Party totals on Detailed or Custom HUD only.",
        position = 1,
        section = partySection
    )
    default boolean showPartyMetricsInOverlay()
    {
        return true;
    }

    @ConfigItem(
        keyName = "defaultSessionMode",
        name = "Default session mode (legacy)",
        description = "Kept for saved settings. New sessions start as Auto.",
        position = 6,
        section = sessionSection,
        hidden = true
    )
    default SessionMode defaultSessionMode()
    {
        return SessionMode.AUTO;
    }

    @ConfigItem(
        keyName = "enablePkTracking",
        name = "Enable PK accounting",
        description = "Passively group RuneLite player-loot and confirmed player-death events",
        position = 5,
        section = activitySection
    )
    default boolean enablePkTracking()
    {
        return true;
    }

    @Range(min = 0, max = 600)
    @ConfigItem(
        keyName = "pkSupplyWindowSeconds",
        name = "PK supply lookback",
        description = "Seconds of recent consumption attached to a confirmed player kill",
        position = 6,
        section = activitySection
    )
    default int pkSupplyWindowSeconds()
    {
        return 90;
    }

    @ConfigItem(
        keyName = "storeOpponentNames",
        name = "Store opponent names",
        description = "Store player names locally in PK encounters; disabled by default",
        position = 7,
        section = activitySection
    )
    default boolean storeOpponentNames()
    {
        return false;
    }

    @ConfigItem(
        keyName = "showPkMetricsInOverlay",
        name = "Show PK metrics",
        description = "PK profit and K/D on Custom HUD; Detailed when PK tracking is on.",
        position = 0,
        section = infoBoxContentSection
    )
    default boolean showPkMetricsInOverlay()
    {
        return true;
    }

    @ConfigItem(
        keyName = "countUncertainMixedChanges",
        name = "Count uncertain mixed changes",
        description = "Count simultaneous gains and costs without a confirmed context",
        position = 2,
        section = pricingSection
    )
    default boolean countUncertainMixedChanges()
    {
        return false;
    }

    @ConfigItem(
        keyName = "ignoredItemIds",
        name = "Ignored item IDs",
        description = "Comma-separated canonical item IDs excluded from accounting",
        position = 3,
        section = pricingSection
    )
    default String ignoredItemIds()
    {
        return "";
    }

    @ConfigItem(
        keyName = "manualPriceOverrides",
        name = "Manual price overrides",
        description = "Comma-separated itemId=gp pairs, for example 995=1,1234=250",
        position = 4,
        section = pricingSection
    )
    default String manualPriceOverrides()
    {
        return "";
    }

    @ConfigItem(
        keyName = "useCurrencyProxies",
        name = "Currency proxy prices",
        description = "Value untradeable currencies via curated proxies; manual overrides win.",
        position = 5,
        section = pricingSection
    )
    default boolean useCurrencyProxies()
    {
        return true;
    }

    @ConfigItem(
        keyName = "applyGeSellTax",
        name = "Apply GE sell tax",
        description = "Book GE sell tax (2%, 5m cap) when a sell offer is collected.",
        position = 6,
        section = pricingSection
    )
    default boolean applyGeSellTax()
    {
        return true;
    }

    @ConfigItem(
        keyName = "geBookingObserved",
        name = "Book GE trades from offers",
        description = "Settle Grand Exchange buys and sells from the observed offer slots instead of inventory changes. "
            + "Off until the live completed-offer comparison confirms the sell-side coin reading.",
        position = 7,
        section = pricingSection
    )
    default boolean geBookingObserved()
    {
        return false;
    }

    @ConfigItem(
        keyName = "geSellSpentIsNet",
        name = "GE sell coins are after tax",
        description = "With offers booked from observations: the coins a sell offer reports already exclude the 2% tax "
            + "(no separate tax row). Turn off if the live comparison shows the reported coins are gross.",
        position = 8,
        section = pricingSection
    )
    default boolean geSellSpentIsNet()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showInfoBoxRate",
        name = "Show rate",
        description = "Rate row when HUD detail is Custom only.",
        position = 1,
        section = infoBoxContentSection
    )
    default boolean showInfoBoxRate()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showInfoBoxRevenue",
        name = "Show revenue",
        description = "Revenue row when HUD detail is Custom only.",
        position = 2,
        section = infoBoxContentSection
    )
    default boolean showInfoBoxRevenue()
    {
        return false;
    }

    @ConfigItem(
        keyName = "showInfoBoxCosts",
        name = "Show costs (Custom HUD)",
        description = "Costs row when HUD detail is Custom only.",
        position = 3,
        section = infoBoxContentSection
    )
    default boolean showInfoBoxCosts()
    {
        return false;
    }

    @ConfigItem(
        keyName = "showInfoBoxTime",
        name = "Show time",
        description = "Time row when HUD detail is Custom only.",
        position = 4,
        section = infoBoxContentSection
    )
    default boolean showInfoBoxTime()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showInfoBoxActivity",
        name = "Show activity",
        description = "Activity row when HUD detail is Custom only.",
        position = 5,
        section = infoBoxContentSection
    )
    default boolean showInfoBoxActivity()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showInfoBoxActions",
        name = "Show actions",
        description = "Actions row when HUD detail is Custom only.",
        position = 6,
        section = infoBoxContentSection
    )
    default boolean showInfoBoxActions()
    {
        return false;
    }

    @ConfigItem(
        keyName = "showInfoBoxChanges",
        name = "Show changes",
        description = "Changes row when HUD detail is Custom only.",
        position = 7,
        section = infoBoxContentSection
    )
    default boolean showInfoBoxChanges()
    {
        return false;
    }

    @ConfigItem(
        keyName = "infoBoxTheme",
        name = "Theme (background)",
        description = "Shell background for HUD+ and legacy HUD. HUD+ text: HUD+ - Colours.",
        position = 0,
        section = hudAppearanceSection
    )
    default InfoBoxTheme infoBoxTheme()
    {
        return InfoBoxTheme.DARK;
    }

    @Range(min = 120, max = 260)
    @ConfigItem(
        keyName = "infoBoxWidth",
        name = "HUD width",
        description = "Legacy HUD width (120–260). HUD+ uses Max width.",
        position = 2,
        section = hudSection
    )
    default int infoBoxWidth()
    {
        return 220;
    }

    @Range(min = 2, max = 12)
    @ConfigItem(
        keyName = "infoBoxPadding",
        name = "HUD padding",
        description = "Inner padding for legacy HUD only.",
        position = 3,
        section = hudSection
    )
    default int infoBoxPadding()
    {
        return 3;
    }

    @Range(min = 0, max = 6)
    @ConfigItem(
        keyName = "infoBoxRowGap",
        name = "HUD row spacing",
        description = "Row spacing for legacy HUD only.",
        position = 4,
        section = hudSection
    )
    default int infoBoxRowGap()
    {
        return 0;
    }

    @Alpha
    @ConfigItem(
        keyName = "infoBoxCustomBackground",
        name = "Custom background",
        description = "Custom background shared by HUD+ and HUD; HUD+ uses background opacity for alpha.",
        position = 1,
        section = hudAppearanceSection
    )
    default Color infoBoxCustomBackground()
    {
        return new Color(25, 25, 28, 190);
    }

    @Alpha
    @ConfigItem(
        keyName = "infoBoxCustomTitleColor",
        name = "Legacy HUD title colour",
        description = "Custom theme title on legacy HUD; unused on Compact and HUD+.",
        position = 2,
        section = hudAppearanceSection
    )
    default Color infoBoxCustomTitleColor()
    {
        return new Color(238, 238, 241);
    }

    @Alpha
    @ConfigItem(
        keyName = "infoBoxCustomLabelColor",
        name = "Custom metric labels",
        description = "Custom theme metric labels on legacy HUD.",
        position = 3,
        section = hudAppearanceSection
    )
    default Color infoBoxCustomLabelColor()
    {
        return new Color(185, 185, 194);
    }

    @Alpha
    @ConfigItem(
        keyName = "infoBoxCustomValueColor",
        name = "Custom values",
        description = "Custom theme neutral values on legacy HUD.",
        position = 4,
        section = hudAppearanceSection
    )
    default Color infoBoxCustomValueColor()
    {
        return new Color(245, 245, 247);
    }

    @Alpha
    @ConfigItem(
        keyName = "infoBoxCustomProfitColor",
        name = "Custom profit",
        description = "Custom theme positive net/rate on legacy HUD.",
        position = 5,
        section = hudAppearanceSection
    )
    default Color infoBoxCustomProfitColor()
    {
        return new Color(83, 214, 128);
    }

    @Alpha
    @ConfigItem(
        keyName = "infoBoxCustomLossColor",
        name = "Custom loss",
        description = "Custom theme negative net/rate on legacy HUD.",
        position = 6,
        section = hudAppearanceSection
    )
    default Color infoBoxCustomLossColor()
    {
        return new Color(242, 93, 104);
    }

    @ConfigItem(
        keyName = "showLiveGpDrops",
        name = "Show live GP drops (legacy)",
        description = "Kept for saved settings. Prefer Change feedback = Floating.",
        position = 0,
        section = liveGpDropsSection,
        hidden = true
    )
    default boolean showLiveGpDrops()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showGpDropCosts",
        name = "Show costs (Floating)",
        description = "Floating overlay only: show negative supply, fee, and item-cost drops",
        position = 1,
        section = liveGpDropsSection
    )
    default boolean showGpDropCosts()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showUncertainGpDrops",
        name = "Show uncertain changes",
        description = "Floating overlay only: show amber changes that could not be confidently classified",
        position = 2,
        section = liveGpDropsSection
    )
    default boolean showUncertainGpDrops()
    {
        return false;
    }

    @ConfigItem(
        keyName = "gpDropPresentationPreset",
        name = "Floating preset",
        description = "Classic coin + signed value; Custom unlocks Custom preset controls.",
        position = 3,
        section = liveGpDropsSection
    )
    default GpDropPresentationPreset gpDropPresentationPreset()
    {
        return GpDropPresentationPreset.COIN_TOTAL;
    }

    @Range(min = 0, max = 10_000_000)
    @ConfigItem(
        keyName = "gpDropMinimumValue",
        name = "Floating minimum value",
        description = "Hide floating rows below this drop amount (0 = all). Not per-item unit price.",
        position = 4,
        section = liveGpDropsSection
    )
    default int gpDropMinimumValue()
    {
        return 1;
    }

    @ConfigItem(
        keyName = "gpDropContentMode",
        name = "Drop contents",
        description = "Used by the Custom preset; choose item rows, totals, or Smart grouping",
        position = 0,
        section = gpDropAdvancedSection
    )
    default GpDropContentMode gpDropContentMode()
    {
        return GpDropContentMode.SMART;
    }

    @Range(min = 1, max = 8)
    @ConfigItem(
        keyName = "gpDropMaxItemRows",
        name = "Maximum item rows",
        description = "Maximum item-specific rows created from one transaction",
        position = 1,
        section = gpDropAdvancedSection
    )
    default int gpDropMaxItemRows()
    {
        return 4;
    }

    @ConfigItem(
        keyName = "gpDropItemTextMode",
        name = "Item row text",
        description = "Choose whether item rows include names, quantities, both, or only GP value",
        position = 2,
        section = gpDropAdvancedSection,
        hidden = true
    )
    default GpDropItemTextMode gpDropItemTextMode()
    {
        return GpDropItemTextMode.QUANTITY;
    }

    @Range(min = 8, max = 28)
    @ConfigItem(
        keyName = "gpDropMaxNameLength",
        name = "Maximum item-name length",
        description = "Truncate long item names in live drops to keep the stack compact",
        position = 3,
        section = gpDropAdvancedSection,
        hidden = true
    )
    default int gpDropMaxNameLength()
    {
        return 16;
    }

    @ConfigItem(
        keyName = "gpDropCombineMode",
        name = "Combine nearby drops",
        description = "Smart combines matching item rows and combines summary rows by gain/loss direction",
        position = 4,
        section = gpDropAdvancedSection
    )
    default GpDropCombineMode gpDropCombineMode()
    {
        return GpDropCombineMode.SMART;
    }

    @Range(min = 0, max = 5000)
    @ConfigItem(
        keyName = "gpDropCombineMillis",
        name = "Combine window",
        description = "Milliseconds in which compatible GP-drop rows may merge",
        position = 5,
        section = gpDropAdvancedSection,
        hidden = true
    )
    default int gpDropCombineMillis()
    {
        return 600;
    }

    @ConfigItem(
        keyName = "gpDropIconMode",
        name = "Icon mode",
        description = "Used by the Custom preset; choose matching item, coin, strip, or no icons",
        position = 6,
        section = gpDropAdvancedSection
    )
    default GpDropIconMode gpDropIconMode()
    {
        return GpDropIconMode.SMART;
    }

    @Range(min = 1, max = 6)
    @ConfigItem(
        keyName = "gpDropMaxIcons",
        name = "Maximum icons per row",
        description = "Maximum contributing item sprites shown in a summary-row icon strip",
        position = 7,
        section = gpDropAdvancedSection
    )
    default int gpDropMaxIcons()
    {
        return 3;
    }

    @Range(min = 12, max = 32)
    @ConfigItem(
        keyName = "gpDropIconSize",
        name = "Icon size",
        description = "Sprite size for item or coin icons (12–32).",
        position = 8,
        section = gpDropAdvancedSection
    )
    default int gpDropIconSize()
    {
        return 18;
    }

    @Range(min = 0, max = 14)
    @ConfigItem(
        keyName = "gpDropIconOverlap",
        name = "Icon-strip overlap",
        description = "Pixels each additional icon overlaps the preceding icon",
        position = 9,
        section = gpDropAdvancedSection,
        hidden = true
    )
    default int gpDropIconOverlap()
    {
        return 6;
    }

    @Range(min = 0, max = 10)
    @ConfigItem(
        keyName = "gpDropIconGap",
        name = "Icon/text gap",
        description = "Horizontal gap between the icon block and GP-drop text",
        position = 10,
        section = gpDropAdvancedSection,
        hidden = true
    )
    default int gpDropIconGap()
    {
        return 3;
    }

    @ConfigItem(
        keyName = "gpDropIconSide",
        name = "Icon position",
        description = "Place icons before or after the GP-drop text",
        position = 11,
        section = gpDropAdvancedSection,
        hidden = true
    )
    default GpDropIconSide gpDropIconSide()
    {
        return GpDropIconSide.LEFT;
    }

    @ConfigItem(
        keyName = "gpDropQuantityOnIcon",
        name = "Quantity on item icon",
        description = "Draw compact OSRS-style quantities over item-specific icons",
        position = 12,
        section = gpDropAdvancedSection,
        hidden = true
    )
    default boolean gpDropQuantityOnIcon()
    {
        return false;
    }

    @ConfigItem(
        keyName = "gpDropFontType",
        name = "Font",
        description = "Floating overlay font. Bitmap fonts best at 16/32/48.",
        position = 0,
        section = floatingAppearanceSection
    )
    default FontType gpDropFontType()
    {
        return FontType.REGULAR;
    }

    @ConfigItem(
        keyName = "gpDropFontStyle",
        name = "Text style (legacy)",
        description = "Deprecated. Migrated to Font.",
        position = 0,
        section = floatingAppearanceSection,
        hidden = true
    )
    default GpDropFontStyle gpDropFontStyle()
    {
        return GpDropFontStyle.EXPANDED_XP;
    }

    @ConfigItem(
        keyName = "gpDropAlignment",
        name = "Row alignment",
        description = "Align differently sized drop rows inside the movable overlay",
        position = 1,
        section = floatingAppearanceSection
    )
    default GpDropAlignment gpDropAlignment()
    {
        return GpDropAlignment.RIGHT;
    }

    @ConfigItem(
        keyName = "gpDropStackDirection",
        name = "Stack direction",
        description = "Choose whether new GP drops appear at the top or bottom",
        position = 2,
        section = floatingAppearanceSection
    )
    default GpDropStackDirection gpDropStackDirection()
    {
        return GpDropStackDirection.NEWEST_BOTTOM;
    }

    @Range(min = 0, max = 12)
    @ConfigItem(
        keyName = "gpDropLineSpacing",
        name = "Line spacing",
        description = "Vertical spacing between simultaneous GP-drop rows",
        position = 3,
        section = floatingAppearanceSection
    )
    default int gpDropLineSpacing()
    {
        return 1;
    }

    @Range(min = 1, max = 10)
    @ConfigItem(
        keyName = "gpDropMaxVisible",
        name = "Maximum visible rows",
        description = "Maximum number of live GP-drop rows displayed together",
        position = 4,
        section = floatingAppearanceSection
    )
    default int gpDropMaxVisible()
    {
        return 5;
    }

    @Range(min = 500, max = 10000)
    @ConfigItem(
        keyName = "gpDropDurationMillis",
        name = "Floating hang time",
        description = "Milliseconds each floating row stays visible.",
        position = 5,
        section = liveGpDropsSection
    )
    default int gpDropDurationMillis()
    {
        return 2900;
    }

    @ConfigItem(
        keyName = "gpDropTextBackground",
        name = "Text background",
        description = "Shadow, outline, or none behind drop text.",
        position = 5,
        section = floatingAppearanceSection
    )
    default GpDropTextBackground gpDropTextBackground()
    {
        return GpDropTextBackground.SHADOW;
    }

    @ConfigItem(
        keyName = "gpDropOutline",
        name = "Black text outline (legacy)",
        description = "Deprecated. Migrated to Text background.",
        position = 5,
        section = floatingAppearanceSection,
        hidden = true
    )
    default boolean gpDropOutline()
    {
        return true;
    }

    @ConfigItem(
        keyName = "gpDropFade",
        name = "Fade near expiry",
        description = "Fade GP drops over the last third of hang time (Customizable XP Drops style)",
        position = 0,
        section = floatingAnimationSection
    )
    default boolean gpDropFade()
    {
        return true;
    }

    @ConfigItem(
        keyName = "gpDropCompactNumbers",
        name = "Compact GP values",
        description = "Use 4.8K instead of the XP-drop-style 4,800",
        position = 6,
        section = floatingAppearanceSection
    )
    default boolean gpDropCompactNumbers()
    {
        return false;
    }

    @ConfigItem(
        keyName = "gpDropShowSuffix",
        name = "Show gp suffix",
        description = "Append gp to live-drop values",
        position = 7,
        section = floatingAppearanceSection
    )
    default boolean gpDropShowSuffix()
    {
        return false;
    }

    @Alpha
    @ConfigItem(
        keyName = "gpDropProfitColor",
        name = "Profit colour",
        description = "Colour used for positive live GP drops",
        position = 8,
        section = floatingAppearanceSection
    )
    default Color gpDropProfitColor()
    {
        return Color.GREEN;
    }

    @Alpha
    @ConfigItem(
        keyName = "gpDropLossColor",
        name = "Loss colour",
        description = "Colour used for negative live GP drops",
        position = 9,
        section = floatingAppearanceSection
    )
    default Color gpDropLossColor()
    {
        return Color.RED;
    }

    @Alpha
    @ConfigItem(
        keyName = "gpDropUncertainColor",
        name = "Uncertain colour",
        description = "Colour used for uncertain live GP drops",
        position = 10,
        section = floatingAppearanceSection
    )
    default Color gpDropUncertainColor()
    {
        return new Color(236, 184, 88);
    }

    @Range(min = 0, max = 200)
    @ConfigItem(
        keyName = "gpDropPixelsPerSecondY",
        name = "Vertical float speed",
        description = "Vertical drift speed in px/s; 0 disables motion.",
        position = 0,
        section = floatingAnimationSection
    )
    default int gpDropPixelsPerSecondY()
    {
        return 44;
    }

    @Range(min = 0, max = 200)
    @ConfigItem(
        keyName = "gpDropPixelsPerSecondX",
        name = "Horizontal float speed",
        description = "Pixels per second of continuous horizontal drift. 0 disables.",
        position = 1,
        section = floatingAnimationSection
    )
    default int gpDropPixelsPerSecondX()
    {
        return 0;
    }

    @Range(min = 0, max = 2000)
    @ConfigItem(
        keyName = "gpDropStaggerMillis",
        name = "Cascade stagger",
        description = "Delay between rows from one loot so they cascade; 0 shows together.",
        position = 2,
        section = floatingAnimationSection
    )
    default int gpDropStaggerMillis()
    {
        return 400;
    }

    @ConfigItem(
        keyName = "gpDropAnimationStyle",
        name = "Entry animation",
        description = "Entry motion preset; Reduced motion forces static.",
        position = 3,
        section = floatingAnimationSection
    )
    default GpDropAnimationStyle gpDropAnimationStyle()
    {
        return GpDropAnimationStyle.CLASSIC_RISE;
    }

    @ConfigItem(
        keyName = "gpDropMotionDirection",
        name = "Motion direction",
        description = "Direction used by continuous float and by rise/slide/float entry animations",
        position = 4,
        section = floatingAnimationSection
    )
    default GpDropMotionDirection gpDropMotionDirection()
    {
        return GpDropMotionDirection.UP;
    }

    @Range(min = 0, max = 160)
    @ConfigItem(
        keyName = "gpDropMotionDistance",
        name = "Entry motion distance",
        description = "Pixels for rise/slide/pop entry; continuous float uses speed sliders.",
        position = 5,
        section = floatingAnimationSection
    )
    default int gpDropMotionDistance()
    {
        return 24;
    }

    @Range(min = 100, max = 2000)
    @ConfigItem(
        keyName = "gpDropAnimationMillis",
        name = "Entry duration",
        description = "Milliseconds used by rise, slide, pop, and fade-in entry animations",
        position = 6,
        section = floatingAnimationSection
    )
    default int gpDropAnimationMillis()
    {
        return 180;
    }

    @ConfigItem(
        keyName = "gpDropEasing",
        name = "Motion easing",
        description = "Controls how quickly entry-animated rows settle into place",
        position = 7,
        section = floatingAnimationSection
    )
    default GpDropEasing gpDropEasing()
    {
        return GpDropEasing.EASE_OUT;
    }

    @ConfigItem(
        keyName = "gpDropFadeIn",
        name = "Fade in animated rows",
        description = "Blend animated rows in during their entry motion",
        position = 8,
        section = floatingAnimationSection
    )
    default boolean gpDropFadeIn()
    {
        return false;
    }

    @ConfigItem(
        keyName = "gpDropAnimateIcons",
        name = "Animate icons with text",
        description = "Move and scale item or coin icons together with their matching text",
        position = 9,
        section = floatingAnimationSection
    )
    default boolean gpDropAnimateIcons()
    {
        return true;
    }

    @ConfigItem(
        keyName = "gpDropReanimateOnCombine",
        name = "Reanimate merged rows",
        description = "Restart the selected animation when a nearby compatible drop merges into a row",
        position = 10,
        section = floatingAnimationSection
    )
    default boolean gpDropReanimateOnCombine()
    {
        return false;
    }

    @Range(min = -2, max = 2)
    @ConfigItem(
        keyName = "gpDropOverlayPriority",
        name = "Overlay priority",
        description = "Draw order vs other corner overlays; higher draws on top.",
        position = 11,
        section = floatingAnimationSection
    )
    default double gpDropOverlayPriority()
    {
        return 1.0d;
    }

    @ConfigItem(
        keyName = "autoActivityDetection",
        name = "Automatic activity detection",
        description = "Passively infer PvM, skilling, trading, PKing, or general activity in Auto mode",
        position = 3,
        section = activitySection
    )
    default boolean autoActivityDetection()
    {
        return true;
    }

    @Range(min = 0, max = 3600)
    @ConfigItem(
        keyName = "activityResetSeconds",
        name = "Return to Tracking",
        description = "Seconds before Auto activity returns to Tracking; 0 keeps last activity.",
        position = 4,
        section = activitySection
    )
    default int activityResetSeconds()
    {
        return 0;
    }

    @ConfigItem(
        keyName = "idlePauseEnabled",
        name = "AFK pause",
        description = "Pause active time after AFK timeout and exclude it from GP/hr. Off uses wall-clock.",
        position = 0,
        section = activitySection
    )
    default boolean idlePauseEnabled()
    {
        return false;
    }

    @Range(min = 15, max = 3600)
    @ConfigItem(
        keyName = "idleTimeoutSeconds",
        name = "AFK pause timeout",
        description = "Idle seconds before AFK pause when AFK pause is on.",
        position = 1,
        section = activitySection
    )
    default int idleTimeoutSeconds()
    {
        return 120;
    }

    @Range(min = 2000, max = 30000)
    @ConfigItem(
        keyName = "characterIdleDelayMillis",
        name = "HUD character Idle delay",
        description = "Ms before HUD+ shows Idle (look only). Does not pause tracking or GP/hr.",
        position = 2,
        section = activitySection
    )
    default int characterIdleDelayMillis()
    {
        return 5000;
    }

    @ConfigItem(
        keyName = "configSchemaVersion",
        name = "Configuration schema",
        description = "Internal key for one-time settings migration.",
        position = 6,
        section = advancedTrackingSection,
        hidden = true
    )
    default int configSchemaVersion()
    {
        return 31;
    }

}
