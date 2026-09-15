package com.gpmanager;

import com.gpmanager.diagnostics.RateAvailability;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.PartyProfitSummary;
import com.gpmanager.model.PkMetrics;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.party.PartyProfitTracker;
import com.gpmanager.reward.RewardObservation;
import com.gpmanager.reward.RewardPresentationPhase;
import com.gpmanager.reward.RewardSourceKind;
import com.gpmanager.reward.SessionItemLedger;
import com.gpmanager.ui.DedicatedHudRenderer;
import com.gpmanager.ui.HudClock;
import com.gpmanager.ui.HudDropRowComponent;
import com.gpmanager.ui.HudMetricPairFormatter;
import com.gpmanager.ui.HudNames;
import com.gpmanager.ui.HudPlusHeaderLabel;
import com.gpmanager.ui.ModernTheme;
import com.gpmanager.ui.TrackingDisplayModel;
import com.gpmanager.ui.TrackingDisplaySnapshot;
import com.gpmanager.util.QuantityFormatter;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import javax.annotation.Nullable;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * GP Manager tracking overlay. HUD+ uses the dedicated custom Graphics2D
 * renderer. HUD uses the previous lightweight PanelComponent layouts
 * (Compact / Minimal / Detailed / Custom). Mode selection picks the renderer
 * directly — Compact detail never routes into HUD+.
 */
public class GpManagerOverlay extends Overlay
{
    public static final String MENU_VIEW_LATEST_LOOT = "View latest items";
    public static final String MENU_OPEN_PANEL = "Open panel";
    public static final String MENU_CONFIGURE = "Configure";
    public static final String MENU_PAUSE_TRACKING = "Pause tracking";
    public static final String MENU_RESUME_TRACKING = "Resume tracking";
    public static final String MENU_PREVIEW_HUD_PLUS = "Preview HUD+ sample";
    public static final String MENU_RESET_HUD_PLUS = "Reset HUD+ position";
    static final String OVERLAY_TITLE = "GP Manager";
    private static final int MIN_HUD_WIDTH = DedicatedHudRenderer.MIN_WIDTH;
    private static final int MAX_HUD_WIDTH = DedicatedHudRenderer.MAX_WIDTH;
    private static final long PREVIEW_MILLIS = 4_000L;

    private final GpManagerEngine engine;
    private final GpManagerConfig config;
    private final PartyProfitTracker partyProfitTracker;
    private final TrackingDisplayModel displayModel;
    private final HudClock clock;
    @Nullable
    private final ItemManager itemManager;
    @Inject
    @Nullable
    private Client client;
    @Nullable
    private final net.runelite.client.ui.overlay.tooltip.TooltipManager tooltipManager;
    private final DedicatedHudRenderer dedicated = new DedicatedHudRenderer();
    private final PanelComponent legacyPanel = new PanelComponent();
    private DedicatedHudRenderer.PaintResult lastDedicatedPaint;
    private long previewUntilEpochMillis;
    @Nullable
    private TrackingDisplaySnapshot previewSnapshot;
    /** Tracks which of the mutually-exclusive Pause/Resume labels is currently registered. */
    private String pauseResumeMenuLabel = "";
    private long folioLingerUntilEpochMillis;
    private static final long FOLIO_LINGER_MILLIS = 180L;

    @Inject
    private SessionItemLedger sessionItemLedger;

    @Inject
    private com.gpmanager.grounditems.LootPresentationFilterService lootPresentationFilter;

    @Inject
    public GpManagerOverlay(
        GpManagerPlugin plugin,
        GpManagerEngine engine,
        GpManagerConfig config,
        PartyProfitTracker partyProfitTracker,
        TrackingDisplayModel displayModel,
        HudClock clock,
        @Nullable ItemManager itemManager,
        @Nullable net.runelite.client.ui.overlay.tooltip.TooltipManager tooltipManager)
    {
        super(plugin);
        this.engine = engine;
        this.config = config;
        this.partyProfitTracker = partyProfitTracker;
        this.displayModel = displayModel;
        this.clock = clock;
        this.itemManager = itemManager;
        this.tooltipManager = tooltipManager;
        setPosition(OverlayPosition.TOP_LEFT);
        // Use RUNELITE_OVERLAY (not RUNELITE_OVERLAY_CONFIG): ConfigPlugin's name
        // lookup often fails for loadBuiltin and only opens the plugin list.
        getMenuEntries().add(new OverlayMenuEntry(
            MenuAction.RUNELITE_OVERLAY,
            MENU_CONFIGURE,
            OVERLAY_TITLE));
        getMenuEntries().add(new OverlayMenuEntry(
            MenuAction.RUNELITE_OVERLAY,
            MENU_VIEW_LATEST_LOOT,
            OVERLAY_TITLE));
        getMenuEntries().add(new OverlayMenuEntry(
            MenuAction.RUNELITE_OVERLAY,
            MENU_OPEN_PANEL,
            OVERLAY_TITLE));
        getMenuEntries().add(new OverlayMenuEntry(
            MenuAction.RUNELITE_OVERLAY,
            MENU_PREVIEW_HUD_PLUS,
            OVERLAY_TITLE));
        getMenuEntries().add(new OverlayMenuEntry(
            MenuAction.RUNELITE_OVERLAY,
            MENU_RESET_HUD_PLUS,
            OVERLAY_TITLE));
    }

    /** Test/preview constructor without Guice HudClock. */
    public GpManagerOverlay(
        GpManagerEngine engine,
        GpManagerConfig config,
        PartyProfitTracker partyProfitTracker,
        TrackingDisplayModel displayModel,
        @Nullable ItemManager itemManager)
    {
        this(null, engine, config, partyProfitTracker, displayModel, new HudClock(), itemManager, null);
    }

    public GpManagerOverlay(
        GpManagerEngine engine,
        GpManagerConfig config,
        PartyProfitTracker partyProfitTracker,
        TrackingDisplayModel displayModel,
        HudClock clock,
        @Nullable ItemManager itemManager)
    {
        this(null, engine, config, partyProfitTracker, displayModel, clock, itemManager, null);
    }

    public HudClock clock()
    {
        return clock;
    }

    public DedicatedHudRenderer.PaintResult lastDedicatedPaint()
    {
        return lastDedicatedPaint;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        ProfitSession active = engine.getActiveSession();
        updatePauseResumeMenuEntry(active);
        TrackingDisplay display = config.trackingDisplay();
        if (!display.isOverlay() || active == null)
        {
            lastDedicatedPaint = null;
            return null;
        }

        long now = clock.now();
        TrackingDisplaySnapshot snapshot = displayModel.snapshot(now);
        if (display.isHudPlus() && now < previewUntilEpochMillis && previewSnapshot != null)
        {
            snapshot = previewSnapshot;
        }

        if (display.isHudPlus())
        {
            boolean autoResize = config.hudPlusAutoResize();
            int preferredWidth = autoResize
                ? MAX_HUD_WIDTH
                : clamp(config.hudPlusWidth(), MIN_HUD_WIDTH, MAX_HUD_WIDTH);
            return renderDedicated(graphics, snapshot, preferredWidth);
        }

        int preferredWidth = clamp(config.infoBoxWidth(), MIN_HUD_WIDTH, MAX_HUD_WIDTH);
        // Legacy HUD — Compact uses the previous lightweight three-row overlay.
        lastDedicatedPaint = null;
        InfoBoxView view = config.infoBoxView();
        if (view == InfoBoxView.COMPACT || view == null)
        {
            return renderLegacyCompact(graphics, snapshot, preferredWidth);
        }
        return renderLegacyDetailed(graphics, snapshot, view, preferredWidth);
    }

    /** Isolated sample paint — does not mutate engine or reward model state. */
    public void startHudPlusPreview()
    {
        long now = clock.now();
        previewUntilEpochMillis = now + PREVIEW_MILLIS;
        previewSnapshot = buildPreviewSnapshot(now);
    }

    public void resetHudPlusPosition()
    {
        setPreferredLocation(null);
        setPreferredPosition(OverlayPosition.TOP_LEFT);
    }

    /**
     * Keeps exactly one of {@link #MENU_PAUSE_TRACKING} / {@link #MENU_RESUME_TRACKING}
     * registered on the right-click menu, matching the active session's pause state.
     * Cheap no-op once the label already matches; only swaps entries on change.
     */
    private void updatePauseResumeMenuEntry(@Nullable ProfitSession active)
    {
        String desired = active != null && active.isPaused() ? MENU_RESUME_TRACKING : MENU_PAUSE_TRACKING;
        if (desired.equals(pauseResumeMenuLabel))
        {
            return;
        }
        if (!pauseResumeMenuLabel.isEmpty())
        {
            removeMenuEntry(MenuAction.RUNELITE_OVERLAY, pauseResumeMenuLabel, OVERLAY_TITLE);
        }
        addMenuEntry(MenuAction.RUNELITE_OVERLAY, desired, OVERLAY_TITLE);
        pauseResumeMenuLabel = desired;
    }

    private static TrackingDisplaySnapshot buildPreviewSnapshot(long now)
    {
        RewardObservation sample = new RewardObservation(
            "preview",
            "preview",
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "preview",
            now,
            java.util.Arrays.asList(
                new com.gpmanager.reward.RewardItem(
                    526, "Bones", 1L, 35L, true, com.gpmanager.model.ItemPriceSource.GRAND_EXCHANGE),
                new com.gpmanager.reward.RewardItem(
                    2132, "Raw chicken", 1L, 40L, true, com.gpmanager.model.ItemPriceSource.GRAND_EXCHANGE)),
            false,
            1,
            "");
        return new TrackingDisplaySnapshot(
            12_400L,
            180_000L,
            320_000L,
            false,
            true,
            "PvM",
            "Live",
            null,
            sample,
            RewardPresentationPhase.REVEALING,
            true,
            1f,
            75L,
            null,
            com.gpmanager.ui.ProfitTargetPresentation.of(25_000L, 12_400L, false));
    }

    private Dimension renderDedicated(Graphics2D graphics, TrackingDisplaySnapshot snapshot, int preferredWidth)
    {
        HudPlusDensity density = config.hudPlusDensity() == null
            ? HudPlusDensity.TRIP
            : config.hudPlusDensity();
        boolean showRewardTray = density.showTray()
            && (ChangeFeedbackPolicy.showHudPlusTray(config)
                || (previewUntilEpochMillis > clock.now() && previewSnapshot != null));
        boolean keepExpanded = config.hudPlusRewardPresentation() == HudPlusRewardPresentation.KEEP_EXPANDED;
        long now = clock.now();
        boolean pointerNear = isMouseOverHud();
        boolean shiftDown = client != null && client.isKeyPressed(KeyCode.KC_SHIFT);
        HudPlusDetailTrigger trigger = config.hudPlusDetailTrigger() == null
            ? HudPlusDetailTrigger.HOVER_OR_SHIFT
            : config.hudPlusDetailTrigger();
        boolean wantFolio = false;
        if (!engine.isStopped() && (snapshot.getIdentityBlockReason() == null
            || snapshot.getIdentityBlockReason().isEmpty()))
        {
            if (trigger == HudPlusDetailTrigger.HOVER || trigger == HudPlusDetailTrigger.HOVER_OR_SHIFT)
            {
                wantFolio |= pointerNear;
            }
            if (trigger == HudPlusDetailTrigger.SHIFT || trigger == HudPlusDetailTrigger.HOVER_OR_SHIFT)
            {
                wantFolio |= shiftDown && pointerNear;
            }
        }
        if (wantFolio)
        {
            folioLingerUntilEpochMillis = now + FOLIO_LINGER_MILLIS;
        }
        boolean showFolio = wantFolio || now < folioLingerUntilEpochMillis;
        boolean activityHold = displayModel != null
            && displayModel.rewardModel() != null
            && displayModel.rewardModel().isActivityHold();
        // Folio flips left when trip + folio would spill past the canvas edge.
        boolean folioOnLeft = false;
        int tripEstimate = preferredWidth;
        int folioEstimate = DedicatedHudRenderer.FOLIO_MIN;
        if (lastDedicatedPaint != null && lastDedicatedPaint.tripWidth > 0)
        {
            tripEstimate = lastDedicatedPaint.tripWidth;
            if (lastDedicatedPaint.folioWidth > 0)
            {
                folioEstimate = lastDedicatedPaint.folioWidth;
            }
        }
        if (showFolio && client != null && client.getCanvasWidth() > 0)
        {
            Rectangle bounds = getBounds();
            if (bounds != null && bounds.x + tripEstimate + folioEstimate + 8
                > client.getCanvasWidth())
            {
                folioOnLeft = true;
            }
        }
        long lootMin = com.gpmanager.model.ProfitTargetParser.parseOrZero(config.minimumDisplayedLootValue());
        SessionItemLedger.SessionLedgerSnapshot ledger = sessionItemLedger == null
            ? null
            : sessionItemLedger.snapshot(
                lootMin,
                config.hudPlusMaxRewardRows(),
                config.hudPlusMaxRewardRows(),
                lootPresentationFilter,
                config.lootPresentationFilter(),
                config.accountingItemFilter());
        ThemeColors colors = resolveDedicatedColors(snapshot);
        Color accent = safeColor(config.hudPlusAccentColor(), DedicatedHudRenderer.ACCENT);
        boolean autoResize = config.hudPlusAutoResize();
        int maxHeight = 0; // Height always hugs; Max height setting retired.
        boolean hoverTitle = autoResize && config.hudPlusHoverFullTitle() && isMouseOverHud();
        int paintWidth = preferredWidth;
        if (hoverTitle)
        {
            paintWidth = MAX_HUD_WIDTH;
        }
        // Text size / metric labels / activity header from config.
        dedicated.setPvpLine(pvpLine(snapshot));
        dedicated.setShowIcons(config.hudPlusShowItemIcons());
        lastDedicatedPaint = dedicated.renderTripBeacon(
            graphics,
            snapshot,
            itemManager,
            paintWidth,
            showRewardTray,
            config.hudPlusAccumulationMode() == HudPlusAccumulationMode.STREAK,
            keepExpanded,
            config.hudPlusTextSize(),
            config.hudPlusBackgroundOpacity(),
            config.hudPlusMaxRewardRows(),
            maxHeight,
            colors.background,
            colors.title,
            colors.label,
            colors.value,
            colors.positive,
            colors.negative,
            colors.header,
            accent,
            density.showTarget(),
            config.infoBoxNumberFormat() == InfoBoxNumberFormat.EXACT,
            showFolio,
            folioOnLeft,
            ledger,
            activityHold,
            now,
            config.hudPlusCompactTimer(),
            config.hudPlusShowMetricLabels(),
            config.hudPlusShowActivityHeader(),
            config.hudPlusShowItemIcons(),
            config.hudPlusShowTrayTags(),
            autoResize,
            config.reducedMotion(),
            config.hudPlusShowWildernessRisk());
        if (hoverTitle
            && lastDedicatedPaint != null
            && tooltipManager != null)
        {
            String tip = HudPlusHeaderLabel.resolveHover(snapshot);
            if (tip != null && !tip.isEmpty()
                && (snapshot.isCustomSessionActive()
                    || (lastDedicatedPaint.truncatedHeaderTitle != null
                        && !lastDedicatedPaint.truncatedHeaderTitle.isEmpty())))
            {
                tooltipManager.add(new net.runelite.client.ui.overlay.tooltip.Tooltip(tip));
            }
        }
        return lastDedicatedPaint == null ? null : lastDedicatedPaint.size;
    }

    private boolean isMouseOverHud()
    {
        if (client == null)
        {
            return false;
        }
        Rectangle bounds = getBounds();
        net.runelite.api.Point mouse = client.getMouseCanvasPosition();
        return bounds != null
            && mouse != null
            && bounds.contains(mouse.getX(), mouse.getY());
    }

    private Dimension renderLegacyCompact(
        Graphics2D graphics,
        TrackingDisplaySnapshot snapshot,
        int preferredWidth)
    {
        ThemeColors palette = resolveLegacyPalette(snapshot);
        Font rowFont = FontManager.getRunescapeSmallFont();
        FontMetrics metrics = graphics.getFontMetrics(rowFont);

        legacyPanel.getChildren().clear();
        legacyPanel.setPreferredSize(new Dimension(preferredWidth, 0));
        int padding = clamp(config.infoBoxPadding(), 2, 12);
        legacyPanel.setBorder(new Rectangle(padding, padding, padding, padding));
        legacyPanel.setGap(new Point(0, clamp(config.infoBoxRowGap(), 0, 6)));
        legacyPanel.setBackgroundColor(palette.background);

        String leftHeader = HudPlusHeaderLabel.resolveFull(snapshot, false);
        String elapsed = QuantityFormatter.duration(snapshot.getElapsedMillis());
        int timeWidth = metrics.stringWidth(elapsed);
        leftHeader = HudNames.compact(
            leftHeader, Math.max(24, preferredWidth - timeWidth - 16), metrics);
        legacyPanel.getChildren().add(LineComponent.builder()
            .left(leftHeader)
            .right(elapsed)
            .leftColor(palette.label)
            .rightColor(palette.value)
            .leftFont(rowFont)
            .rightFont(rowFont)
            .build());

        int contentW = Math.max(24, preferredWidth - padding * 2);
        boolean preferExact = config.infoBoxNumberFormat() == InfoBoxNumberFormat.EXACT;
        String rate = snapshot.isRateAvailable()
            ? HudMetricPairFormatter.formatRateLine(
                snapshot.getProfitPerHour(), true, preferExact, false)
            : RateAvailability.unavailableLabel();
        com.gpmanager.ui.ProfitTargetPresentation target = snapshot.getProfitTarget();
        String total = HudMetricPairFormatter.formatTotalLine(
            snapshot.getNet(), target, preferExact, false);
        Color rateColor = snapshot.isRateAvailable()
            ? amountColor(snapshot.getProfitPerHour(), palette)
            : palette.label;
        Color totalColor = amountColor(snapshot.getNet(), palette);
        boolean stacked = !HudMetricPairFormatter.metricsFitSideBySide(metrics, rate, total, contentW);
        if (stacked)
        {
            legacyPanel.getChildren().add(LineComponent.builder()
                .left(rate)
                .right("")
                .leftColor(rateColor)
                .rightColor(totalColor)
                .leftFont(rowFont)
                .rightFont(rowFont)
                .build());
            legacyPanel.getChildren().add(LineComponent.builder()
                .left("")
                .right(total)
                .leftColor(palette.label)
                .rightColor(totalColor)
                .leftFont(rowFont)
                .rightFont(rowFont)
                .build());
        }
        else
        {
            legacyPanel.getChildren().add(LineComponent.builder()
                .left(rate)
                .right(total)
                .leftColor(rateColor)
                .rightColor(totalColor)
                .leftFont(rowFont)
                .rightFont(rowFont)
                .build());
        }
        if (target != null && target.isPresent())
        {
            legacyPanel.getChildren().add(LineComponent.builder()
                .left("")
                .right(target.centeredProgressLine())
                .leftColor(palette.label)
                .rightColor(target.isReached() ? palette.positive : palette.value)
                .leftFont(rowFont)
                .rightFont(rowFont)
                .build());
        }
        // No status footer — floating drops own change feedback; header carries Idle/AFK.

        Font originalFont = graphics.getFont();
        graphics.setFont(rowFont);
        try
        {
            return legacyPanel.render(graphics);
        }
        finally
        {
            graphics.setFont(originalFont);
        }
    }

    private Dimension renderLegacyDetailed(
        Graphics2D graphics,
        TrackingDisplaySnapshot snapshot,
        InfoBoxView view,
        int preferredWidth)
    {
        lastDedicatedPaint = null;
        ThemeColors palette = resolveLegacyPalette(snapshot);
        Font rowFont = FontManager.getRunescapeSmallFont();
        Font titleFont = FontManager.getRunescapeBoldFont();

        legacyPanel.getChildren().clear();
        legacyPanel.setPreferredSize(new Dimension(preferredWidth, 0));
        int padding = clamp(config.infoBoxPadding(), 2, 12);
        legacyPanel.setBorder(new Rectangle(padding, padding, padding, padding));
        legacyPanel.setGap(new Point(0, clamp(config.infoBoxRowGap(), 0, 6)));
        legacyPanel.setBackgroundColor(palette.background);

        FontMetrics titleMetrics = graphics.getFontMetrics(titleFont);
        String title = HudNames.compact(
            HudPlusHeaderLabel.resolveFull(snapshot, false),
            Math.max(24, preferredWidth - padding * 2),
            titleMetrics);
        legacyPanel.getChildren().add(TitleComponent.builder()
            .text(title)
            .color(palette.title)
            .build());

        if (snapshot.isPaused())
        {
            addNeutralLine("Status:", snapshot.getStatusLabel(), palette, rowFont);
        }
        if (showActivity(view))
        {
            // Compare with the full semantic title: the rendered title above may be compacted
            // (for example, "GE Clerk"), while the activity name remains complete.
            String semanticHeader = HudPlusHeaderLabel.resolveFull(snapshot, false);
            String activity = snapshot.getDisplayActivity();
            if (shouldShowActivityLine(semanticHeader, activity))
            {
                addNeutralLine("Activity:", activity, palette, rowFont);
            }
        }
        boolean preferExact = config.infoBoxNumberFormat() == InfoBoxNumberFormat.EXACT;
        SessionMetrics metrics = engine.getMetrics(clock.now());
        if (showRate(view))
        {
            legacyPanel.getChildren().add(LineComponent.builder()
                .left("GP/hr:")
                .right(snapshot.isRateAvailable()
                    ? HudMetricPairFormatter.formatRateLine(
                        snapshot.getProfitPerHour(), true, preferExact, false)
                    : RateAvailability.unavailableLabel())
                .leftColor(palette.label)
                .rightColor(snapshot.isRateAvailable()
                    ? amountColor(snapshot.getProfitPerHour(), palette)
                    : palette.label)
                .leftFont(rowFont)
                .rightFont(rowFont)
                .build());
        }
        addAmountLine("Total:", snapshot.getNet(), palette, rowFont);
        if (view == InfoBoxView.CUSTOM && config.showInfoBoxRevenue())
        {
            addAmountLine("Revenue:", metrics.getRevenue(), palette, rowFont);
        }
        if (view == InfoBoxView.CUSTOM && config.showInfoBoxCosts())
        {
            addAmountLine("Costs:", -metrics.getCosts(), palette, rowFont);
        }
        if (config.showPartyMetricsInOverlay()
            && (view == InfoBoxView.DETAILED || view == InfoBoxView.CUSTOM))
        {
            PartyProfitSummary party = partyProfitTracker.getSummary(config.enablePartyTracking());
            if (party.isInParty())
            {
                addAmountLine(party.getNet() < 0L ? "Party loss:" : "Party profit:", party.getNet(), palette, rowFont);
            }
        }
        if (showTime(view))
        {
            addNeutralLine("Time:", QuantityFormatter.duration(snapshot.getElapsedMillis()), palette, rowFont);
        }
        if (view == InfoBoxView.CUSTOM && config.showInfoBoxActions())
        {
            addNeutralLine("Actions:", Integer.toString(metrics.getActionCount()), palette, rowFont);
        }
        if (view == InfoBoxView.CUSTOM && config.showInfoBoxChanges())
        {
            addNeutralLine("Changes:", Integer.toString(metrics.getTransactionCount()), palette, rowFont);
        }
        if (config.enablePkTracking()
            && (view != InfoBoxView.CUSTOM || config.showPkMetricsInOverlay()))
        {
            PkMetrics pk = engine.getPkMetrics();
            if (pk.getKills() > 0 || pk.getDeaths() > 0)
            {
                addNeutralLine("K/D:", pk.getKills() + " / " + pk.getDeaths(), palette, rowFont);
            }
        }

        // Legacy HUD: metrics only. Floating drops / HUD+ own item feedback.

        Font originalFont = graphics.getFont();
        graphics.setFont(titleFont);
        try
        {
            return legacyPanel.render(graphics);
        }
        finally
        {
            graphics.setFont(originalFont);
        }
    }

    /**
     * HUD+ PvP layout follows the same policy as the sidebar: Auto in the Wilderness, on PvP
     * worlds or in a PK run; Always; or Never for players who PvM there.
     */
    @Nullable
    private String pvpLine(com.gpmanager.ui.TrackingDisplaySnapshot snapshot)
    {
        com.gpmanager.ui.bento.PvpMode mode = config.pvpLayoutMode();
        PkMetrics pk = engine.getPkMetrics();
        boolean pkRun = pk != null && pk.getEncounterCount() > 0;
        boolean on = mode == com.gpmanager.ui.bento.PvpMode.ALWAYS
            || (mode == com.gpmanager.ui.bento.PvpMode.AUTO && (snapshot.isPvpPossible() || pkRun));
        if (!on)
        {
            return null;
        }
        int kills = pk == null ? 0 : pk.getKills();
        int deaths = pk == null ? 0 : pk.getDeaths();
        StringBuilder sb = new StringBuilder("K/D ").append(kills).append(" / ").append(deaths);
        if (kills > 0)
        {
            sb.append("  ·  +").append(formatGp(pk.getProfitPerKill())).append("/kill");
        }
        return sb.toString();
    }

    private void addNeutralLine(String left, String right, ThemeColors palette, Font rowFont)
    {
        legacyPanel.getChildren().add(LineComponent.builder()
            .left(left)
            .right(right == null ? "" : right)
            .leftColor(palette.label)
            .rightColor(palette.value)
            .leftFont(rowFont)
            .rightFont(rowFont)
            .build());
    }

    private void addAmountLine(String left, long amount, ThemeColors palette, Font rowFont)
    {
        legacyPanel.getChildren().add(LineComponent.builder()
            .left(left)
            .right((amount > 0L ? "+" : "") + formatGp(amount))
            .leftColor(palette.label)
            .rightColor(amountColor(amount, palette))
            .leftFont(rowFont)
            .rightFont(rowFont)
            .build());
    }

    private boolean showActivity(InfoBoxView view)
    {
        return view == InfoBoxView.CUSTOM
            ? config.showInfoBoxActivity()
            : view != InfoBoxView.MINIMAL;
    }

    static boolean shouldShowActivityLine(String fullSemanticHeader, String activity)
    {
        if (fullSemanticHeader == null || activity == null || activity.trim().isEmpty())
        {
            return false;
        }
        return !fullSemanticHeader.contains(activity.trim());
    }

    private boolean showRate(InfoBoxView view)
    {
        return view != InfoBoxView.CUSTOM || config.showInfoBoxRate();
    }

    private boolean showTime(InfoBoxView view)
    {
        return view == InfoBoxView.CUSTOM
            ? config.showInfoBoxTime()
            : view != InfoBoxView.MINIMAL;
    }

    private String formatGp(long amount)
    {
        if (config.infoBoxNumberFormat() == InfoBoxNumberFormat.COMPACT)
        {
            return QuantityFormatter.compactGp(Math.abs(amount)) + " gp";
        }
        return QuantityFormatter.gp(Math.abs(amount));
    }

    private Color amountColor(long amount, ThemeColors palette)
    {
        if (amount > 0L)
        {
            return palette.positive;
        }
        if (amount < 0L)
        {
            return palette.negative;
        }
        return palette.value;
    }

    /**
     * Dedicated HUD+ shell colours. Background/title still honor CUSTOM/RUNELITE/DARK
     * theme selection (PROFIT_LOSS no longer washes the background green/brown — the
     * approved concept uses charcoal). Label/value/profit/loss/header are HUD+-owned
     * (see {@code hudPlusColorSection}) and are always used here regardless of theme;
     * the legacy {@code infoBoxCustom*} keys remain scoped to {@link #resolveLegacyPalette}.
     */
    ThemeColors resolveDedicatedColors(TrackingDisplaySnapshot snapshot)
    {
        Color background;
        Color title;
        switch (config.infoBoxTheme())
        {
            case RUNELITE:
                background = ComponentConstants.STANDARD_BACKGROUND_COLOR;
                title = new Color(255, 152, 31);
                break;
            case CUSTOM:
                background = safeColor(config.infoBoxCustomBackground(), DedicatedHudRenderer.SHELL_BG);
                title = safeColor(config.infoBoxCustomTitleColor(), ModernTheme.TEXT);
                break;
            case DARK:
            case PROFIT_LOSS:
            default:
                background = DedicatedHudRenderer.SHELL_BG;
                title = ModernTheme.TEXT;
                break;
        }
        Color label = safeColor(config.hudPlusLabelColor(), DedicatedHudRenderer.LABEL);
        Color value = safeColor(config.hudPlusNeutralColor(), DedicatedHudRenderer.VALUE);
        Color positive = safeColor(config.hudPlusProfitColor(), DedicatedHudRenderer.POSITIVE);
        Color negative = safeColor(config.hudPlusLossColor(), DedicatedHudRenderer.NEGATIVE);
        Color header = safeColor(config.hudPlusHeaderColor(), label);
        return new ThemeColors(background, title, label, value, positive, negative, header);
    }

    private ThemeColors resolveLegacyPalette(TrackingDisplaySnapshot snapshot)
    {
        switch (config.infoBoxTheme())
        {
            case RUNELITE:
                return new ThemeColors(
                    ComponentConstants.STANDARD_BACKGROUND_COLOR,
                    new Color(255, 152, 31),
                    Color.WHITE,
                    Color.WHITE,
                    new Color(0, 255, 0),
                    new Color(255, 64, 64));
            case DARK:
                return new ThemeColors(
                    new Color(20, 20, 23, 190),
                    ModernTheme.TEXT,
                    ModernTheme.MUTED,
                    ModernTheme.TEXT,
                    ModernTheme.POSITIVE,
                    ModernTheme.NEGATIVE);
            case CUSTOM:
                return new ThemeColors(
                    safeColor(config.infoBoxCustomBackground(), new Color(25, 25, 28, 190)),
                    safeColor(config.infoBoxCustomTitleColor(), ModernTheme.TEXT),
                    safeColor(config.infoBoxCustomLabelColor(), ModernTheme.MUTED),
                    safeColor(config.infoBoxCustomValueColor(), ModernTheme.TEXT),
                    safeColor(config.infoBoxCustomProfitColor(), ModernTheme.POSITIVE),
                    safeColor(config.infoBoxCustomLossColor(), ModernTheme.NEGATIVE));
            case PROFIT_LOSS:
            default:
                Color background;
                if (snapshot.isPaused())
                {
                    background = new Color(74, 56, 25, 185);
                }
                else if (snapshot.getNet() > 0L)
                {
                    background = new Color(24, 55, 37, 185);
                }
                else if (snapshot.getNet() < 0L)
                {
                    background = new Color(62, 29, 34, 185);
                }
                else
                {
                    background = new Color(35, 35, 39, 185);
                }
                return new ThemeColors(
                    background,
                    ModernTheme.TEXT,
                    new Color(190, 190, 198),
                    ModernTheme.TEXT,
                    ModernTheme.POSITIVE,
                    ModernTheme.NEGATIVE);
        }
    }

    private Color safeColor(Color value, Color fallback)
    {
        return value == null ? fallback : value;
    }

    private int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    static final class ThemeColors
    {
        final Color background;
        final Color title;
        final Color label;
        final Color value;
        final Color positive;
        final Color negative;
        /** HUD+ header (activity + elapsed time) colour; unused by the legacy HUD palette. */
        final Color header;

        private ThemeColors(
            Color background,
            Color title,
            Color label,
            Color value,
            Color positive,
            Color negative)
        {
            this(background, title, label, value, positive, negative, label);
        }

        private ThemeColors(
            Color background,
            Color title,
            Color label,
            Color value,
            Color positive,
            Color negative,
            Color header)
        {
            this.background = background;
            this.title = title;
            this.label = label;
            this.value = value;
            this.positive = positive;
            this.negative = negative;
            this.header = header;
        }
    }
}
