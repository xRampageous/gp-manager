package com.gpmanager;

import com.gpmanager.model.SessionMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.client.config.ConfigManager;

/**
 * One-time, local-only migration for GP Manager settings.
 *
 * <p>The established {@code profitmanager} configuration group is retained for
 * upgrade compatibility. Values from the older {@code smartprofittracker}
 * group are copied only when the matching GP Manager value is missing. Legacy
 * keys are deliberately left untouched as a recovery backup.</p>
 */
public final class GpManagerConfigMigration
{
    static final int CURRENT_SCHEMA_VERSION = 31;
    static final String LEGACY_GROUP = "smartprofittracker";
    private static final String SCHEMA_KEY = "configSchemaVersion";

    private GpManagerConfigMigration()
    {
    }

    public static void apply(ConfigManager configManager)
    {
        if (configManager == null)
        {
            return;
        }

        Map<String, String> legacy = readGroup(configManager, LEGACY_GROUP);
        Map<String, String> current = readGroup(configManager, GpManagerConfig.GROUP);

        // Legacy smartprofittracker fills gaps only on a true first-time / pre-schema
        // profile. Factory reset stamps the current schema so it never re-imports.
        Map<String, String> copied = Collections.emptyMap();
        if (shouldImportLegacy(current))
        {
            copied = missingLegacyValues(legacy, current);
            for (Map.Entry<String, String> entry : copied.entrySet())
            {
                configManager.setConfiguration(
                    GpManagerConfig.GROUP,
                    entry.getKey(),
                    entry.getValue());
            }
        }

        Map<String, String> merged = new LinkedHashMap<>();
        if (shouldImportLegacy(current))
        {
            merged.putAll(legacy);
        }
        merged.putAll(current);
        merged.putAll(copied);

        Map<String, String> updates = plan(merged);
        for (Map.Entry<String, String> update : updates.entrySet())
        {
            configManager.setConfiguration(
                GpManagerConfig.GROUP,
                update.getKey(),
                update.getValue());
        }

        migrateFloatingFontAndBackground(configManager, merged);
    }

    /**
     * True only for profiles that have never completed schema migration.
     * Once {@code configSchemaVersion} is set (including after Factory reset),
     * the legacy {@code smartprofittracker} group is backup-only.
     */
    static boolean shouldImportLegacy(Map<String, String> current)
    {
        if (current == null || current.isEmpty())
        {
            return true;
        }
        return parseInt(current.get(SCHEMA_KEY), 0) <= 0;
    }

    /**
     * Schema 18: promote legacy font enum + outline boolean into FontType and
     * {@link GpDropTextBackground}. Uses typed ConfigManager writes so FontType
     * serializes correctly.
     */
    static void migrateFloatingFontAndBackground(ConfigManager configManager, Map<String, String> before)
    {
        if (configManager == null)
        {
            return;
        }
        Map<String, String> source = before == null ? Collections.emptyMap() : before;
        int version = parseInt(source.get(SCHEMA_KEY), 0);
        if (version >= 18)
        {
            return;
        }

        String existingFont = configManager.getConfiguration(GpManagerConfig.GROUP, "gpDropFontType");
        if (isBlank(existingFont))
        {
            GpDropFontStyle style = parseEnum(
                source.get("gpDropFontStyle"),
                GpDropFontStyle.class,
                GpDropFontStyle.EXPANDED_XP);
            configManager.setConfiguration(
                GpManagerConfig.GROUP,
                "gpDropFontType",
                GpDropFontHandler.fromLegacyStyle(style));
        }

        String existingBackground = configManager.getConfiguration(
            GpManagerConfig.GROUP, "gpDropTextBackground");
        if (isBlank(existingBackground))
        {
            String outline = source.get("gpDropOutline");
            GpDropTextBackground background;
            if (outline == null)
            {
                background = GpDropTextBackground.SHADOW;
            }
            else if (Boolean.parseBoolean(outline))
            {
                background = GpDropTextBackground.OUTLINE;
            }
            else
            {
                background = GpDropTextBackground.NONE;
            }
            configManager.setConfiguration(
                GpManagerConfig.GROUP,
                "gpDropTextBackground",
                background);
        }
    }

    /**
     * Factory reset = fresh plugin install for the live {@code profitmanager} group.
     * Clears every stored key (including orphans), then writes typed interface
     * defaults via {@link ConfigManager#setDefaultConfiguration}. Does not import
     * {@code smartprofittracker}; that backup stays untouched and is ignored on
     * later {@link #apply} once the schema is stamped.
     *
     * @return number of keys unset before reseeding
     */
    public static int clearGroupAndReseed(ConfigManager configManager)
    {
        return resetToFreshInstall(configManager, null);
    }

    /**
     * Same as {@link #clearGroupAndReseed(ConfigManager)} but prefers RuneLite's
     * typed default writer when a live {@link GpManagerConfig} proxy is available.
     */
    public static int resetToFreshInstall(ConfigManager configManager, GpManagerConfig config)
    {
        if (configManager == null)
        {
            return 0;
        }
        int cleared = clearGroupFully(configManager, GpManagerConfig.GROUP);
        if (config != null)
        {
            // Typed defaults (Color, FontType, enums) — same path as first enabling
            // the plugin. Avoids string-only reseeds that can break Configuration UI.
            configManager.setDefaultConfiguration(config, true);
        }
        else
        {
            for (Map.Entry<String, String> entry : plan(Collections.emptyMap()).entrySet())
            {
                configManager.setConfiguration(
                    GpManagerConfig.GROUP, entry.getKey(), entry.getValue());
            }
            migrateFloatingFontAndBackground(configManager, Collections.emptyMap());
        }
        // Always stamp schema so apply() will not re-import legacy gaps.
        configManager.setConfiguration(
            GpManagerConfig.GROUP,
            SCHEMA_KEY,
            Integer.toString(CURRENT_SCHEMA_VERSION));
        return cleared;
    }

    /**
     * Unsets each key under {@code group}. RuneLite
     * {@link ConfigManager#getConfigurationKeys(String)} returns fully-qualified
     * {@code group.key} strings — strip the prefix before
     * {@link ConfigManager#unsetConfiguration(String, String)}.
     * Retries until empty so orphan / mid-unset listings still clear.
     */
    static int clearGroup(ConfigManager configManager, String group)
    {
        return clearGroupFully(configManager, group);
    }

    static int clearGroupFully(ConfigManager configManager, String group)
    {
        if (configManager == null || isBlank(group))
        {
            return 0;
        }
        int cleared = 0;
        for (int pass = 0; pass < 8; pass++)
        {
            int passCleared = clearGroupOnce(configManager, group);
            cleared += passCleared;
            if (passCleared == 0)
            {
                break;
            }
        }
        // Final sweep via readGroup in case listing prefixes differ.
        for (String key : readGroup(configManager, group).keySet())
        {
            configManager.unsetConfiguration(group, key);
            cleared++;
        }
        return cleared;
    }

    private static int clearGroupOnce(ConfigManager configManager, String group)
    {
        String prefix = group.endsWith(".") ? group : group + ".";
        List<String> keys = configManager.getConfigurationKeys(prefix);
        if (keys == null || keys.isEmpty())
        {
            // Some profiles also answer the bare group name; still strip safely.
            keys = configManager.getConfigurationKeys(group);
        }
        if (keys == null || keys.isEmpty())
        {
            return 0;
        }
        int cleared = 0;
        for (String wholeKey : keys)
        {
            String key = configurationKeySuffix(group, wholeKey);
            if (key == null)
            {
                continue;
            }
            configManager.unsetConfiguration(group, key);
            cleared++;
        }
        return cleared;
    }

    /**
     * Maps a RuneLite {@code group.key} listing entry to the bare key passed to
     * {@link ConfigManager#unsetConfiguration(String, String)}. Returns null when
     * the entry is blank, nested, or outside the group.
     */
    static String configurationKeySuffix(String group, String wholeKey)
    {
        if (isBlank(group) || isBlank(wholeKey))
        {
            return null;
        }
        String prefix = group.endsWith(".") ? group : group + ".";
        String key;
        if (wholeKey.startsWith(prefix))
        {
            key = wholeKey.substring(prefix.length());
        }
        else if (wholeKey.equals(group))
        {
            return null;
        }
        else if (!wholeKey.contains("."))
        {
            // Already a bare key from an unusual listing.
            key = wholeKey;
        }
        else
        {
            return null;
        }
        if (key.isEmpty() || key.indexOf('.') >= 0)
        {
            return null;
        }
        return key;
    }

    static Map<String, String> missingLegacyValues(
        Map<String, String> legacy,
        Map<String, String> current)
    {
        if (legacy == null || legacy.isEmpty())
        {
            return Collections.emptyMap();
        }

        Map<String, String> destination = current == null
            ? Collections.emptyMap()
            : current;
        Map<String, String> copied = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : legacy.entrySet())
        {
            if (!isBlank(entry.getKey())
                && !isBlank(entry.getValue())
                && isBlank(destination.get(entry.getKey())))
            {
                copied.put(entry.getKey(), entry.getValue());
            }
        }
        return copied;
    }

    static Map<String, String> plan(Map<String, String> source)
    {
        Map<String, String> current = source == null
            ? Collections.emptyMap()
            : source;
        int version = parseInt(current.get(SCHEMA_KEY), 0);
        if (version >= CURRENT_SCHEMA_VERSION)
        {
            return Collections.emptyMap();
        }

        Map<String, String> updates = new LinkedHashMap<>();

        if (isBlank(current.get("gpDropPresentationPreset")))
        {
            updates.put("gpDropPresentationPreset", derivePreset(current).name());
        }

        putIfMissing(current, updates, "activityResetSeconds", "0");
        putIfMissing(current, updates, "ignoreUnpricedItems", "false");
        putIfMissing(current, updates, "useHighAlchemyFallback", "false");
        putIfMissing(current, updates, "useCurrencyProxies", "true");
        putIfMissing(current, updates, "applyGeSellTax", "true");
        putIfMissing(current, updates, "infoBoxWidth", "220");
        putIfMissing(current, updates, "infoBoxView", InfoBoxView.COMPACT.name());
        putIfMissing(current, updates, "infoBoxTheme", InfoBoxTheme.DARK.name());
        putIfMissing(current, updates, "infoBoxNumberFormat", InfoBoxNumberFormat.COMPACT.name());
        putIfMissing(current, updates, "defaultSessionMode", SessionMode.AUTO.name());
        putIfMissing(current, updates, "gpDropAnimationStyle", GpDropAnimationStyle.CLASSIC_RISE.name());
        putIfMissing(current, updates, "enableDebugTrace", "false");
        putIfMissing(current, updates, "reducedMotion", "false");
        putIfMissing(current, updates, "keepRewardTrayExpanded", "false");
        putIfMissing(current, updates, "lootPresentationFilter", LootPresentationFilter.FOLLOW_GROUND_ITEMS.name());
        putIfMissing(current, updates, "accountingItemFilter", LootPresentationFilter.ALL_ITEMS.name());
        putIfMissing(current, updates, "minimumDisplayedLootValue", "0");
        putIfMissing(current, updates, "reuseGroundItemsHighlightColors", "false");
        putIfMissing(current, updates, "wealthHistoryEnabled", "true");
        putIfMissing(current, updates, "wealthBankReadEnabled", "true");
        putIfMissing(current, updates, "wealthMilestoneGp", "0");

        // Schema 23: Follow Ground Items is the presentation default. Profiles still on the
        // old factory All items value (or blank) move once; Highlighted list only is kept.
        if (version < 23)
        {
            String lootFilter = firstNonBlank(
                updates.get("lootPresentationFilter"), current.get("lootPresentationFilter"));
            if (isBlank(lootFilter)
                || LootPresentationFilter.ALL_ITEMS.name().equals(lootFilter))
            {
                updates.put("lootPresentationFilter", LootPresentationFilter.FOLLOW_GROUND_ITEMS.name());
            }
        }

        // Schema 12: dedicated HUD+ keys (independent of legacy infoBoxWidth).
        String migratedHudPlusWidth = firstNonBlank(current.get("infoBoxWidth"), "260");
        putIfMissing(current, updates, "hudPlusWidth", migratedHudPlusWidth);
        putIfMissing(current, updates, "hudPlusAutoResize", "true");
        putIfMissing(current, updates, "hudPlusShowBankingFlashes", "true");
        putIfMissing(current, updates, "idlePauseEnabled", "false");
        putIfMissing(current, updates, "hudPlusMaxHeight", "0");
        putIfMissing(current, updates, "hudPlusBackgroundOpacity", "90");
        putIfMissing(current, updates, "hudPlusTextSize", HudPlusTextSize.NORMAL.name());
        putIfMissing(current, updates, "hudPlusShowMetricLabels", "true");
        putIfMissing(current, updates, "hudPlusShowActivityHeader", "true");
        putIfMissing(current, updates, "hudPlusShowItemIcons", "true");
        putIfMissing(current, updates, "hudPlusShowTrayTags", "true");
        putIfMissing(current, updates, "hudPlusRevealDurationMillis", "5000");
        putIfMissing(current, updates, "hudPlusLootCoalesceTicks", "2");
        putIfMissing(current, updates, "hudPlusAccumulationMode", HudPlusAccumulationMode.SESSION.name());
        putIfMissing(current, updates, "hudPlusStreakInactivityMinutes", "0");
        putIfMissing(current, updates, "hudPlusMaxRewardRows", "5");
        putIfMissing(current, updates, "hudPlusDetailTrigger", HudPlusDetailTrigger.HOVER_OR_SHIFT.name());
        putIfMissing(current, updates, "hudPlusAccentColor", "139,147,167");
        putIfMissing(current, updates, "hudPlusCompactTimer", "true");
        putIfMissing(current, updates, "hudPlusHoverFullTitle", "false");
        putIfMissing(current, updates, "hudPlusProfitColor", "92,255,176");
        putIfMissing(current, updates, "hudPlusLossColor", "255,107,107");
        // Schema 20 Trip Beacon palette: remap prior factory greens/reds only.
        remapFactoryColor(current, updates, "hudPlusProfitColor",
            Set.of("83,214,128", "83,214,128,255", "0,255,0", "0,255,0,255"), "92,255,176");
        remapFactoryColor(current, updates, "hudPlusLossColor",
            Set.of("242,93,104", "242,93,104,255", "255,0,0", "255,0,0,255"), "255,107,107");
        // Schema 26: factory teal accent → muted blue-gray (do not clobber customs).
        if (version < 26)
        {
            remapFactoryColor(current, updates, "hudPlusAccentColor",
                Set.of("46,230,214", "46,230,214,255"), "139,147,167");
        }
        putIfMissing(current, updates, "itemChangesTopN", "5");
        putIfMissing(current, updates, "characterIdleDelayMillis", "5000");
        putIfMissing(current, updates, "liveItemGridMaxHeight", "220");
        if (isBlank(current.get("hudPlusRewardPresentation"))
            && !updates.containsKey("hudPlusRewardPresentation"))
        {
            boolean keepExpanded = Boolean.parseBoolean(
                firstNonBlank(current.get("keepRewardTrayExpanded"), "false"));
            updates.put(
                "hudPlusRewardPresentation",
                keepExpanded
                    ? HudPlusRewardPresentation.KEEP_EXPANDED.name()
                    : HudPlusRewardPresentation.AUTO_COLLAPSE.name());
        }
        if (isBlank(current.get("trackingDisplay")))
        {
            String legacyOverlay = current.get("showOverlay");
            if ("false".equalsIgnoreCase(legacyOverlay))
            {
                updates.put("trackingDisplay", TrackingDisplay.OFF.name());
            }
            else if (version == 0
                && isBlank(legacyOverlay)
                && isBlank(current.get("infoBoxView")))
            {
                // Fresh install: dedicated HUD+ is the default presentation.
                updates.put("trackingDisplay", TrackingDisplay.HUD_PLUS.name());
            }
            else
            {
                // Pre-HUD+ profiles keep the lightweight overlay.
                updates.put("trackingDisplay", TrackingDisplay.HUD.name());
            }
        }
        if (isBlank(current.get("feedbackStyle")))
        {
            String legacyDrops = current.get("showLiveGpDrops");
            updates.put("feedbackStyle",
                "false".equalsIgnoreCase(legacyDrops)
                    ? FeedbackStyle.OFF.name()
                    : FeedbackStyle.FLOATING.name());
        }

        // Schema 22 / 26: feedback surfaces follow Tracking display.
        // Pre-schema-22 upgrades with FLOATING feedback seed floating on.
        // Fresh installs and later gaps default HUD+ floating off (tray is enough).
        if (isBlank(current.get("hudPlusFloatingDrops"))
            && !updates.containsKey("hudPlusFloatingDrops"))
        {
            String feedback = firstNonBlank(
                updates.get("feedbackStyle"), current.get("feedbackStyle"));
            boolean floating = version > 0
                && version < 22
                && FeedbackStyle.FLOATING.name().equals(feedback);
            updates.put("hudPlusFloatingDrops", Boolean.toString(floating));
        }

        // Schema 10 used Tracking display = HUD + detail = Compact for the dedicated
        // overlay. Promote that exact combination to HUD+ so appearance is unchanged.
        // Minimal/Detailed/Custom stay on HUD. Earlier schemas are not reinterpreted.
        if (version == 10)
        {
            String display = firstNonBlank(
                updates.get("trackingDisplay"), current.get("trackingDisplay"));
            String view = firstNonBlank(
                updates.get("infoBoxView"), current.get("infoBoxView"));
            if (TrackingDisplay.HUD.name().equals(display)
                && (isBlank(view) || InfoBoxView.COMPACT.name().equals(view)))
            {
                updates.put("trackingDisplay", TrackingDisplay.HUD_PLUS.name());
            }
        }

        // Legacy: Integrated feedback required a visible presentation; Off coerces it.
        // Do not rewrite Floating when primary display is Off.
        String display = updates.containsKey("trackingDisplay")
            ? updates.get("trackingDisplay")
            : current.get("trackingDisplay");
        String feedback = updates.containsKey("feedbackStyle")
            ? updates.get("feedbackStyle")
            : current.get("feedbackStyle");
        if (FeedbackStyle.INTEGRATED.name().equals(feedback)
            && TrackingDisplay.OFF.name().equals(display))
        {
            updates.put("feedbackStyle", FeedbackStyle.OFF.name());
        }

        sanitizeInt(current, updates, "gpDropDurationMillis", 500, 10_000, 2_900);
        sanitizeInt(current, updates, "gpDropMinimumValue", 0, 10_000_000, 1);
        sanitizeInt(current, updates, "gpDropMaxVisible", 1, 10, 5);
        sanitizeInt(current, updates, "gpDropMaxItemRows", 1, 8, 4);
        sanitizeInt(current, updates, "gpDropMaxIcons", 1, 6, 3);
        sanitizeInt(current, updates, "infoBoxWidth", 120, 260, 220);
        sanitizeInt(current, updates, "hudPlusWidth", 120, 320, 260);
        sanitizeInt(current, updates, "hudPlusMaxHeight", 0, 480, 0);
        sanitizeInt(current, updates, "hudPlusBackgroundOpacity", 50, 100, 90);
        sanitizeInt(current, updates, "hudPlusRevealDurationMillis", 1_500, 8_000, 5_000);
        sanitizeInt(current, updates, "hudPlusLootCoalesceTicks", 1, 5, 2);
        sanitizeInt(current, updates, "hudPlusStreakInactivityMinutes", 0, 60, 0);
        sanitizeInt(current, updates, "hudPlusMaxRewardRows", 3, 10, 5);
        sanitizeInt(current, updates, "itemChangesTopN", 3, 10, 5);
        sanitizeInt(current, updates, "idleTimeoutSeconds", 15, 3_600, 120);
        sanitizeInt(current, updates, "characterIdleDelayMillis", 2_000, 30_000, 5_000);
        sanitizeInt(current, updates, "activityResetSeconds", 0, 3_600, 0);
        sanitizeInt(current, updates, "stabilizationTicks", 1, 10, 2);
        sanitizeInt(current, updates, "correlationTicks", 1, 30, 6);
        sanitizeInt(current, updates, "rollingRateMinutes", 1, 120, 15);
        sanitizeInt(current, updates, "lootCorrelationSeconds", 1, 300, 30);
        sanitizeInt(current, updates, "maxHistorySessions", 1, 5_000, 2_000);
        sanitizeInt(current, updates, "maxTransactionsPerSession", 100, 100_000, 2_000);
        sanitizeInt(current, updates, "pkSupplyWindowSeconds", 0, 600, 90);

        sanitizeEnum(current, updates, "receiptRetentionDays",
            ReceiptRetentionPeriod.class, ReceiptRetentionPeriod.DAYS_90.name());

        sanitizeEnum(current, updates, "gpDropPresentationPreset",
            GpDropPresentationPreset.class, GpDropPresentationPreset.COIN_TOTAL.name());
        sanitizeEnum(current, updates, "gpDropAnimationStyle",
            GpDropAnimationStyle.class, GpDropAnimationStyle.CLASSIC_RISE.name());
        sanitizeEnum(current, updates, "infoBoxView",
            InfoBoxView.class, InfoBoxView.COMPACT.name());
        sanitizeEnum(current, updates, "infoBoxTheme",
            InfoBoxTheme.class, InfoBoxTheme.DARK.name());
        sanitizeEnum(current, updates, "infoBoxNumberFormat",
            InfoBoxNumberFormat.class, InfoBoxNumberFormat.COMPACT.name());
        sanitizeEnum(current, updates, "feedbackStyle",
            FeedbackStyle.class, FeedbackStyle.FLOATING.name());
        sanitizeEnum(current, updates, "trackingDisplay",
            TrackingDisplay.class, TrackingDisplay.HUD_PLUS.name());
        sanitizeEnum(current, updates, "defaultSessionMode",
            SessionMode.class, SessionMode.AUTO.name());
        sanitizeEnum(current, updates, "hudPlusTextSize",
            HudPlusTextSize.class, HudPlusTextSize.NORMAL.name());
        sanitizeEnum(current, updates, "hudPlusRewardPresentation",
            HudPlusRewardPresentation.class, HudPlusRewardPresentation.AUTO_COLLAPSE.name());
        sanitizeEnum(current, updates, "hudPlusAccumulationMode",
            HudPlusAccumulationMode.class, HudPlusAccumulationMode.SESSION.name());
        sanitizeEnum(current, updates, "hudPlusDetailTrigger",
            HudPlusDetailTrigger.class, HudPlusDetailTrigger.HOVER_OR_SHIFT.name());
        sanitizeEnum(current, updates, "lootPresentationFilter",
            LootPresentationFilter.class, LootPresentationFilter.FOLLOW_GROUND_ITEMS.name());
        if (isBlank(current.get("gpDropTextBackground")) && !updates.containsKey("gpDropTextBackground"))
        {
            String outline = current.get("gpDropOutline");
            if (outline == null)
            {
                updates.put("gpDropTextBackground", GpDropTextBackground.SHADOW.name());
            }
            else if (Boolean.parseBoolean(outline))
            {
                updates.put("gpDropTextBackground", GpDropTextBackground.OUTLINE.name());
            }
            else
            {
                updates.put("gpDropTextBackground", GpDropTextBackground.NONE.name());
            }
        }
        putIfMissing(current, updates, "gpDropPixelsPerSecondY", "44");
        putIfMissing(current, updates, "gpDropPixelsPerSecondX", "0");
        putIfMissing(current, updates, "gpDropStaggerMillis", "400");
        putIfMissing(current, updates, "gpDropOverlayPriority", "1.0");
        sanitizeInt(current, updates, "gpDropPixelsPerSecondY", 0, 200, 44);
        sanitizeInt(current, updates, "gpDropPixelsPerSecondX", 0, 200, 0);
        sanitizeInt(current, updates, "gpDropStaggerMillis", 0, 2000, 400);
        sanitizeEnum(current, updates, "gpDropTextBackground",
            GpDropTextBackground.class, GpDropTextBackground.SHADOW.name());
        sanitizeEnum(current, updates, "gpDropMotionDirection",
            GpDropMotionDirection.class, GpDropMotionDirection.UP.name());

        updates.put(SCHEMA_KEY, Integer.toString(CURRENT_SCHEMA_VERSION));
        return updates;
    }

    private static String firstNonBlank(String first, String second)
    {
        if (!isBlank(first))
        {
            return first;
        }
        return second;
    }

    private static Map<String, String> readGroup(ConfigManager configManager, String group)
    {
        String prefix = group + ".";
        List<String> keys = configManager.getConfigurationKeys(prefix);
        Map<String, String> values = new LinkedHashMap<>();
        for (String wholeKey : keys)
        {
            if (wholeKey == null || !wholeKey.startsWith(prefix))
            {
                continue;
            }
            String key = wholeKey.substring(prefix.length());
            if (key.isEmpty() || key.indexOf('.') >= 0)
            {
                continue;
            }
            values.put(key, configManager.getConfiguration(group, key));
        }
        return values;
    }

    private static GpDropPresentationPreset derivePreset(Map<String, String> values)
    {
        boolean hasLegacyPresentation = !isBlank(values.get("gpDropIconMode"))
            || !isBlank(values.get("gpDropContentMode"))
            || !isBlank(values.get("gpDropItemTextMode"))
            || !isBlank(values.get("gpDropQuantityOnIcon"));
        if (!hasLegacyPresentation)
        {
            return GpDropPresentationPreset.COIN_TOTAL;
        }

        GpDropIconMode icon = parseEnum(
            values.get("gpDropIconMode"),
            GpDropIconMode.class,
            GpDropIconMode.SMART);
        GpDropContentMode content = parseEnum(
            values.get("gpDropContentMode"),
            GpDropContentMode.class,
            GpDropContentMode.SMART);
        GpDropItemTextMode text = parseEnum(
            values.get("gpDropItemTextMode"),
            GpDropItemTextMode.class,
            GpDropItemTextMode.QUANTITY);
        boolean quantityOnIcon = Boolean.parseBoolean(values.get("gpDropQuantityOnIcon"));

        if (icon == GpDropIconMode.NONE)
        {
            return GpDropPresentationPreset.VALUE_ONLY;
        }
        if (icon == GpDropIconMode.COINS && content == GpDropContentMode.TRANSACTION_TOTAL)
        {
            return GpDropPresentationPreset.COIN_TOTAL;
        }
        if (text == GpDropItemTextMode.VALUE_ONLY && !quantityOnIcon)
        {
            return GpDropPresentationPreset.ICON_VALUE_ONLY;
        }
        if (text == GpDropItemTextMode.QUANTITY || quantityOnIcon)
        {
            return GpDropPresentationPreset.ICON_QUANTITY_VALUE;
        }
        return GpDropPresentationPreset.SMART_DETAILED;
    }

    private static void putIfMissing(
        Map<String, String> current,
        Map<String, String> updates,
        String key,
        String value)
    {
        if (isBlank(current.get(key)) && !updates.containsKey(key))
        {
            updates.put(key, value);
        }
    }

    /** Remap known prior factory colours only — never overwrite a custom palette. */
    private static void remapFactoryColor(
        Map<String, String> current,
        Map<String, String> updates,
        String key,
        Set<String> priorFactoryValues,
        String nextFactoryValue)
    {
        if (updates.containsKey(key))
        {
            return;
        }
        String value = current.get(key);
        if (isBlank(value) || priorFactoryValues == null || !priorFactoryValues.contains(value.trim()))
        {
            return;
        }
        updates.put(key, nextFactoryValue);
    }

    private static void sanitizeInt(
        Map<String, String> current,
        Map<String, String> updates,
        String key,
        int minimum,
        int maximum,
        int fallback)
    {
        String value = updates.containsKey(key) ? updates.get(key) : current.get(key);
        if (isBlank(value))
        {
            return;
        }
        int parsed = parseInt(value, fallback);
        int sanitized = clamp(parsed, minimum, maximum);
        if (!Integer.toString(sanitized).equals(value))
        {
            updates.put(key, Integer.toString(sanitized));
        }
    }

    private static <E extends Enum<E>> void sanitizeEnum(
        Map<String, String> current,
        Map<String, String> updates,
        String key,
        Class<E> type,
        String fallback)
    {
        String value = updates.containsKey(key) ? updates.get(key) : current.get(key);
        if (isBlank(value))
        {
            return;
        }
        try
        {
            Enum.valueOf(type, value);
        }
        catch (IllegalArgumentException ex)
        {
            updates.put(key, fallback);
        }
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, E fallback)
    {
        if (isBlank(value))
        {
            return fallback;
        }
        try
        {
            return Enum.valueOf(type, value);
        }
        catch (IllegalArgumentException ex)
        {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback)
    {
        if (isBlank(value))
        {
            return fallback;
        }
        try
        {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException ex)
        {
            return fallback;
        }
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    private static int clamp(int value, int minimum, int maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
