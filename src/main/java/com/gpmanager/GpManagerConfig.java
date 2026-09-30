package com.gpmanager;
import net.runelite.client.config.*;
@ConfigGroup(GpManagerConfig.GROUP)
public interface GpManagerConfig extends Config {
String GROUP = "gpmanager";
// Sections in SIDEBAR_SPEC.md §6b order; defaults are the product. Timing and limit knobs are
// fixed (owner, 2026-09-26): the hidden ones below stay only so tests can tune them.
// Owner 2026-09-28: Tracking, Accounting, Loot display, then HUD+ and History collapsed. Key
// names never change, so saved settings carry over whatever section shows them.
@ConfigSection(
name = "Tracking",
description = "When and how tracking runs",
position = 0
)
String generalSection = "generalSection";
@ConfigSection(
name = "Accounting",
description = "What counts toward Net, and manual prices",
position = 10
)
String accountingSection = "accountingSection";
@ConfigSection(
name = "Loot display",
description = "What the tray, Live and Ledger lists show. Does not change Net",
position = 20
)
String lootSection = "lootSection";
@ConfigSection(
name = "HUD+",
description = "The HUD+ overlay. Does not change Net or Ledger",
position = 30,
closedByDefault = true
)
String hudLiveSection = "hudLiveSection";
@ConfigSection(
name = "History",
description = "Saved Grinds and receipt detail",
position = 40,
closedByDefault = true
)
String grindsSection = "grindsSection";
@ConfigItem(
keyName = "lootPresentationFilter",
name = "Display loot filter",
description = "Hide Ground Items-hidden gains on the tray, Live lists and Ledger; the tray mirrors display filters and Ledger costs stay visible. Totals stay unchanged",
position = 0,
section = lootSection
)
default Dl lootPresentationFilter() {
return Dl.FOLLOW_GROUND_ITEMS;
}
@ConfigItem(
keyName = "minimumDisplayedLootValue",
name = "Minimum displayed loot value",
description = "Hide gains below this unit GE value on tray and Live lists. Does not change Net. 0 shows all",
position = 1,
section = lootSection
)
default String minimumDisplayedLootValue() {
return "0";
}
@ConfigItem(keyName = "hiddenRecentItems", name = "Hidden Recent items",
description = "Names hidden from Recent only, comma separated. Remove a name to show it again. Net and Ledger stay unchanged",
position = 2, section = lootSection)
default String hiddenRecentItems() {
return "";
}
enum Cr { Small, Normal, Large }
enum HudTimer { Compact, Full }
enum HudTray {
AUTO_FOLD("Auto-fold"), ALWAYS_OPEN("Always open");
final String label;
HudTray(String label) { this.label = label; }
public String toString() { return label; }
}
enum HudTrayKeeps {
STREAK("This streak"), SESSION("Whole session");
final String label;
HudTrayKeeps(String label) { this.label = label; }
public String toString() { return label; }
}
enum HudDetail { Hover, Off }
@ConfigItem(keyName = "showHud", name = "Show HUD+", description = "Show the HUD+ overlay",
position = 2, section = hudLiveSection)
default boolean showHud() {
return true;
}
@ConfigItem(keyName = "hudHideWhenIdle", name = "Hide when not tracking",
description = "Hide HUD+ when nothing is tracking, and in Free play until something is booked",
position = 3, section = hudLiveSection)
default boolean hudHideWhenIdle() {
return true;
}
@ConfigItem(keyName = "hudTextSize", name = "Text size", description = "HUD+ text size",
position = 4, section = hudLiveSection)
default Cr hudTextSize() {
return Cr.Normal;
}
@Range(min = 120, max = 320)
@ConfigItem(keyName = "hudMaxWidth", name = "Max width",
description = "HUD+ fits its content up to this width; longer names end in …",
position = 5, section = hudLiveSection)
default int hudMaxWidth() {
return 260;
}
@Range(max = 100)
@ConfigItem(keyName = "hudOpacity", name = "Background opacity", description = "HUD+ background opacity, percent",
position = 6, section = hudLiveSection)
default int hudOpacity() {
return 75;
}
@ConfigItem(keyName = "hudTimer", name = "Timer", description = "Compact (39m52s) or full (0:39:52); counts tracked active time",
position = 7, section = hudLiveSection)
default HudTimer hudTimer() {
return HudTimer.Compact;
}
@ConfigItem(keyName = "hudTray", name = "Item tray", description = "Fold the tray after loot, or keep it open",
position = 8, section = hudLiveSection)
default HudTray hudTray() {
return HudTray.AUTO_FOLD;
}
@Range(min = 1, max = 60)
@ConfigItem(keyName = "hudTraySeconds", name = "Tray stays open", description = "Seconds the tray stays open after loot",
position = 9, section = hudLiveSection)
default int hudTraySeconds() {
return 5;
}
@Range(min = 1, max = 10)
@ConfigItem(keyName = "hudTrayRows", name = "Visible item rows", description = "Tray rows before \"+N more\"",
position = 10, section = hudLiveSection)
default int hudTrayRows() {
return 4;
}
@ConfigItem(keyName = "hudTrayKeeps", name = "Tray keeps rows for",
description = "Keep this streak's items (another NPC, other work or a quiet spell starts the next), or the whole session's",
position = 11, section = hudLiveSection)
default HudTrayKeeps hudTrayKeeps() {
return HudTrayKeeps.STREAK;
}
@Range(min = 10, max = 3600)
@ConfigItem(keyName = "streakEndSeconds", name = "Streak ends after",
description = "Seconds after the last kill before the HUD tray streak ends.",
position = 14, section = hudLiveSection)
default int streakEndSeconds() {
return 60;
}
@ConfigItem(keyName = "hudDetail", name = "Detail panel", description = "Open the detail panel when hovering HUD+",
position = 12, section = hudLiveSection)
default HudDetail hudDetail() {
return HudDetail.Hover;
}
@ConfigItem(
keyName = "reducedMotion",
name = "Reduced motion",
description = "Skip HUD+ tray entry animation; loot still shows.",
position = 13,
section = hudLiveSection
)
default boolean reducedMotion() {
return false;
}
@ConfigItem(
keyName = "autoStartSession",
name = "Automatic tracking",
description = "Start Free play tracking when gameplay is detected. Login alone does not start a session.",
position = 0,
section = generalSection
)
default boolean autoStartSession() {
return true;
}
@ConfigItem(
keyName = "includeEquipment",
name = "Include equipment",
description = "Combine equipment with inventory so gear swaps remain neutral",
position = 0,
section = accountingSection
)
default boolean includeEquipment() {
return true;
}
@ConfigItem(
keyName = "includeRunePouch",
name = "Include rune pouch",
description = "Count runes stored in the rune pouch so filling it stays neutral and casting from it is a cost",
position = 1,
section = accountingSection
)
default boolean includeRunePouch() {
return true;
}
@ConfigItem(keyName = "stabilizationTicks", name = "", description = "", hidden = true)
default int stabilizationTicks() {
return 2;
}
@Range(min = 1, max = 120)
@ConfigItem(
keyName = "rollingRateMinutes",
name = "Rate window",
description = "Window used for the live rolling GP/h rate; a finished Grind reports its whole-run rate.",
position = 4,
section = generalSection
)
default int rollingRateMinutes() {
return 15;
}
@ConfigItem(keyName = "keepTransferAuditRows", name = "", description = "", hidden = true)
default boolean keepTransferAuditRows() {
return false;
}
@ConfigItem(
keyName = "persistHistory",
name = "Save history",
description = "Save sessions locally between RuneLite launches",
position = 0,
section = grindsSection
)
default boolean persistHistory() {
return true;
}
@ConfigItem(keyName = "maxHistorySessions", name = "", description = "", hidden = true)
default int maxHistorySessions() {
return 2_000;
}
@ConfigItem(
keyName = "receiptRetentionDays",
name = "Receipt detail retention",
description = "Compact closed sessions older than this window; session summaries remain saved",
position = 1,
section = grindsSection
)
default Db receiptRetentionDays() {
return Db.DAYS_90;
}
@ConfigItem(keyName = "maxTransactionsPerSession", name = "", description = "", hidden = true)
default int maxTransactionsPerSession() {
return 2_000;
}
@ConfigItem(
keyName = "enablePkTracking",
name = "PK accounting",
description = "Passively group RuneLite player-loot and confirmed player-death events",
position = 3,
section = accountingSection
)
default boolean enablePkTracking() {
return true;
}
@ConfigItem(
keyName = "countUncertainMixedChanges",
name = "Count uncertain mixed changes",
description = "Count simultaneous gains and costs without a confirmed context",
position = 2,
section = accountingSection
)
default boolean countUncertainMixedChanges() {
return false;
}
@ConfigItem(
keyName = "manualPriceOverrides",
name = "Manual price overrides",
description = "Comma-separated itemId=gp pairs, for example 995=1,1234=250",
position = 4,
section = accountingSection
)
default String manualPriceOverrides() {
return "";
}
@ConfigItem(
keyName = "autoActivityDetection",
name = "Automatic activity detection",
description = "Passively infer PvM, skilling, trading, PKing, or general activity in Auto mode",
position = 1,
section = generalSection
)
default boolean autoActivityDetection() {
return true;
}
@ConfigItem(
keyName = "idlePauseEnabled",
name = "AFK pause",
description = "Pause active time after the AFK timeout and exclude it from GP/h. Off counts idle time as active; manual and login pauses stay excluded.",
position = 2,
section = generalSection
)
default boolean idlePauseEnabled() {
return false;
}
@Range(min = 15, max = 3600)
@ConfigItem(
keyName = "idleTimeoutSeconds",
name = "AFK pause timeout",
description = "Idle seconds before AFK pause when AFK pause is on.",
position = 3,
section = generalSection
)
default int idleTimeoutSeconds() {
return 120;
}
@Range(min = 5, max = 300)
@ConfigItem(
keyName = "activityLabelSeconds",
name = "Activity label timeout",
description = "Seconds without an attack click before the activity label and HUD+ header blank.",
position = 5,
section = generalSection
)
default int activityLabelSeconds() {
return 15;
}
}
