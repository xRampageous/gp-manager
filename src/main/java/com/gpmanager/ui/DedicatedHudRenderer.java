package com.gpmanager.ui;

import com.gpmanager.HudPlusTextSize;
import com.gpmanager.grounditems.FilteredRewardView;
import com.gpmanager.reward.CollectionStatus;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardObservation;
import com.gpmanager.reward.RewardPresentationPhase;
import com.gpmanager.reward.RewardSourceKind;
import com.gpmanager.util.QuantityFormatter;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.client.game.ItemManager;

/**
 * Owns the complete dedicated HUD paint path (custom {@link Graphics2D}, not
 * PanelComponent). Inspired by OSRS TCG's pack-reveal overlay approach —
 * direct rounded shell + horizontal item rows — without modal dim, focus
 * capture, or click-through pack interaction.
 */
public final class DedicatedHudRenderer
{
    public static final int DEFAULT_WIDTH = 260;
    public static final int MIN_WIDTH = 120;
    public static final int MAX_WIDTH = 320;
    public static final int FOLIO_MIN = 140;
    public static final int FOLIO_MAX = MAX_WIDTH;
    /** Fallback when no measured folio yet (edge-flip). Prefer {@link PaintResult#folioWidth}. */
    public static final int FOLIO_WIDTH = FOLIO_MIN;
    public static final int PAD = 6;
    public static final int RADIUS = 6;
    public static final int ICON_SLOT = HudPlusLayout.ICON_NORMAL;
    public static final int ROW_ICON = HudPlusLayout.ICON_NORMAL;
    public static final int MAX_REVEAL_ROWS = 6;

    public static final Color SHELL_BG = new Color(0x0A, 0x0C, 0x10, 230);
    public static final Color SHELL_BORDER = new Color(0x1E, 0x22, 0x2B, 230);
    public static final Color SEPARATOR = new Color(0x2A, 0x30, 0x3A, 200);
    public static final Color LABEL = new Color(0x8B, 0x93, 0xA7);
    public static final Color MUTED = new Color(0x8B, 0x93, 0xA7);
    public static final Color VALUE = new Color(0xE8, 0xEC, 0xF4);
    public static final Color ACCENT = new Color(0x8B, 0x93, 0xA7);
    public static final Color POSITIVE = new Color(0x5C, 0xFF, 0xB0);
    public static final Color NEGATIVE = new Color(0xFF, 0x6B, 0x6B);
    public static final Color LIVE_DOT = new Color(0x8B, 0x93, 0xA7);

    private final TripBeaconPainter tripBeacon = new TripBeaconPainter();

    /** PvP layout line for the next paint; null for the General layout. */
    private boolean showIcons = true;

    /** Item icons on the tray and drop rows; text shifts left when they are off. */
    public void setShowIcons(boolean value)
    {
        showIcons = value;
    }

    public void setPvpLine(@javax.annotation.Nullable String text)
    {
        tripBeacon.setPvpLine(text);
    }

    public static final class PaintResult
    {
        public final Dimension size;
        public final RewardPresentationPhase phase;
        public final boolean drewRevealTray;
        public final boolean drewSettledRow;
        public final boolean singleItemReveal;
        public final int headerHeight;
        public final int contentWidth;
        public final int itemRowCount;
        /** Trip shell width only (excludes satellite folio). */
        public final int tripWidth;
        /** Satellite folio shell width; 0 when folio is not painted. */
        public final int folioWidth;
        /** Full activity title when header text was ellipsized; null/empty otherwise. */
        @Nullable
        public final String truncatedHeaderTitle;

        PaintResult(
            Dimension size,
            RewardPresentationPhase phase,
            boolean drewRevealTray,
            boolean drewSettledRow,
            boolean singleItemReveal,
            int headerHeight,
            int contentWidth,
            int itemRowCount)
        {
            this(size, phase, drewRevealTray, drewSettledRow, singleItemReveal,
                headerHeight, contentWidth, itemRowCount, size == null ? 0 : size.width, 0, null);
        }

        PaintResult(
            Dimension size,
            RewardPresentationPhase phase,
            boolean drewRevealTray,
            boolean drewSettledRow,
            boolean singleItemReveal,
            int headerHeight,
            int contentWidth,
            int itemRowCount,
            int tripWidth,
            @Nullable String truncatedHeaderTitle)
        {
            this(size, phase, drewRevealTray, drewSettledRow, singleItemReveal,
                headerHeight, contentWidth, itemRowCount, tripWidth, 0, truncatedHeaderTitle);
        }

        PaintResult(
            Dimension size,
            RewardPresentationPhase phase,
            boolean drewRevealTray,
            boolean drewSettledRow,
            boolean singleItemReveal,
            int headerHeight,
            int contentWidth,
            int itemRowCount,
            int tripWidth,
            int folioWidth,
            @Nullable String truncatedHeaderTitle)
        {
            this.size = size;
            this.phase = phase;
            this.drewRevealTray = drewRevealTray;
            this.drewSettledRow = drewSettledRow;
            this.singleItemReveal = singleItemReveal;
            this.headerHeight = headerHeight;
            this.contentWidth = contentWidth;
            this.itemRowCount = itemRowCount;
            this.tripWidth = tripWidth;
            this.folioWidth = folioWidth;
            this.truncatedHeaderTitle = truncatedHeaderTitle;
        }
    }

    private final Map<Integer, BufferedImage> spriteCache = new HashMap<>();
    private int spriteGeneration = -1;
    private BufferedImage fallbackSprite;
    private PaintResult lastResult;

    public PaintResult lastResult()
    {
        return lastResult;
    }

    /**
     * @param showRewardTray when true, paint observed/confirmed reward tray body;
     *                       HUD+ passes true for Floating and Integrated (not Off)
     * @param showTarget when false (Glance density), omit the profit-target block
     * @param showHoverDetail when true, append gains/costs/last-change under the header
     */
    public PaintResult render(
        Graphics2D graphics,
        TrackingDisplaySnapshot snapshot,
        @Nullable ItemManager itemManager,
        int preferredWidth,
        boolean showRewardTray,
        boolean streakMode,
        boolean reducedMotion,
        boolean keepTrayExpanded,
        boolean showMetricLabels,
        HudPlusTextSize textSize,
        int backgroundOpacityPercent,
        int maxRewardRows,
        int maxHeightPx,
        @Nullable Color shellBackground,
        @Nullable Color titleColor,
        @Nullable Color labelColor,
        @Nullable Color valueColor,
        @Nullable Color positiveColor,
        @Nullable Color negativeColor,
        @Nullable Color headerColor)
    {
        return render(
            graphics, snapshot, itemManager, preferredWidth, showRewardTray, streakMode,
            reducedMotion, keepTrayExpanded, showMetricLabels, textSize, backgroundOpacityPercent,
            maxRewardRows, maxHeightPx, shellBackground, titleColor, labelColor, valueColor,
            positiveColor, negativeColor, headerColor, true, false, 0L, 0L, false);
    }

    public PaintResult render(
        Graphics2D graphics,
        TrackingDisplaySnapshot snapshot,
        @Nullable ItemManager itemManager,
        int preferredWidth,
        boolean showRewardTray,
        boolean streakMode,
        boolean reducedMotion,
        boolean keepTrayExpanded,
        boolean showMetricLabels,
        HudPlusTextSize textSize,
        int backgroundOpacityPercent,
        int maxRewardRows,
        int maxHeightPx,
        @Nullable Color shellBackground,
        @Nullable Color titleColor,
        @Nullable Color labelColor,
        @Nullable Color valueColor,
        @Nullable Color positiveColor,
        @Nullable Color negativeColor,
        @Nullable Color headerColor,
        boolean showTarget,
        boolean showHoverDetail,
        long hoverRevenue,
        long hoverCosts)
    {
        return render(
            graphics, snapshot, itemManager, preferredWidth, showRewardTray, streakMode,
            reducedMotion, keepTrayExpanded, showMetricLabels, textSize, backgroundOpacityPercent,
            maxRewardRows, maxHeightPx, shellBackground, titleColor, labelColor, valueColor,
            positiveColor, negativeColor, headerColor, showTarget, showHoverDetail,
            hoverRevenue, hoverCosts, false);
    }

    public PaintResult render(
        Graphics2D graphics,
        TrackingDisplaySnapshot snapshot,
        @Nullable ItemManager itemManager,
        int preferredWidth,
        boolean showRewardTray,
        boolean streakMode,
        boolean reducedMotion,
        boolean keepTrayExpanded,
        boolean showMetricLabels,
        HudPlusTextSize textSize,
        int backgroundOpacityPercent,
        int maxRewardRows,
        int maxHeightPx,
        @Nullable Color shellBackground,
        @Nullable Color titleColor,
        @Nullable Color labelColor,
        @Nullable Color valueColor,
        @Nullable Color positiveColor,
        @Nullable Color negativeColor,
        @Nullable Color headerColor,
        boolean showTarget,
        boolean showHoverDetail,
        long hoverRevenue,
        long hoverCosts,
        boolean preferExact)
    {
        return renderTripBeacon(
            graphics,
            snapshot,
            itemManager,
            preferredWidth,
            showRewardTray,
            streakMode,
            keepTrayExpanded,
            textSize,
            backgroundOpacityPercent,
            maxRewardRows,
            maxHeightPx,
            shellBackground,
            titleColor,
            labelColor,
            valueColor,
            positiveColor,
            negativeColor,
            headerColor,
            ACCENT,
            showTarget,
            preferExact,
            false,
            false,
            null,
            false,
            System.currentTimeMillis());
    }

    /**
     * Trip Beacon + optional satellite folio.
     */
    public PaintResult renderTripBeacon(
        Graphics2D graphics,
        TrackingDisplaySnapshot snapshot,
        @Nullable ItemManager itemManager,
        int preferredWidth,
        boolean showRewardTray,
        boolean streakMode,
        boolean keepTrayExpanded,
        HudPlusTextSize textSize,
        int backgroundOpacityPercent,
        int maxRewardRows,
        int maxHeightPx,
        @Nullable Color shellBackground,
        @Nullable Color titleColor,
        @Nullable Color labelColor,
        @Nullable Color valueColor,
        @Nullable Color positiveColor,
        @Nullable Color negativeColor,
        @Nullable Color headerColor,
        @Nullable Color accentColor,
        boolean showTarget,
        boolean preferExact,
        boolean showFolio,
        boolean folioOnLeft,
        @Nullable com.gpmanager.reward.SessionItemLedger.SessionLedgerSnapshot ledger,
        boolean activityHold,
        long nowEpochMillis)
    {
        return renderTripBeacon(
            graphics, snapshot, itemManager, preferredWidth, showRewardTray, streakMode,
            keepTrayExpanded, textSize, backgroundOpacityPercent, maxRewardRows, maxHeightPx,
            shellBackground, titleColor, labelColor, valueColor, positiveColor, negativeColor,
            headerColor, accentColor, showTarget, preferExact, showFolio, folioOnLeft, ledger,
            activityHold, nowEpochMillis, true, false);
    }

    public PaintResult renderTripBeacon(
        Graphics2D graphics,
        TrackingDisplaySnapshot snapshot,
        @Nullable ItemManager itemManager,
        int preferredWidth,
        boolean showRewardTray,
        boolean streakMode,
        boolean keepTrayExpanded,
        HudPlusTextSize textSize,
        int backgroundOpacityPercent,
        int maxRewardRows,
        int maxHeightPx,
        @Nullable Color shellBackground,
        @Nullable Color titleColor,
        @Nullable Color labelColor,
        @Nullable Color valueColor,
        @Nullable Color positiveColor,
        @Nullable Color negativeColor,
        @Nullable Color headerColor,
        @Nullable Color accentColor,
        boolean showTarget,
        boolean preferExact,
        boolean showFolio,
        boolean folioOnLeft,
        @Nullable com.gpmanager.reward.SessionItemLedger.SessionLedgerSnapshot ledger,
        boolean activityHold,
        long nowEpochMillis,
        boolean compactTimer)
    {
        return renderTripBeacon(
            graphics, snapshot, itemManager, preferredWidth, showRewardTray, streakMode,
            keepTrayExpanded, textSize, backgroundOpacityPercent, maxRewardRows, maxHeightPx,
            shellBackground, titleColor, labelColor, valueColor, positiveColor, negativeColor,
            headerColor, accentColor, showTarget, preferExact, showFolio, folioOnLeft, ledger,
            activityHold, nowEpochMillis, compactTimer, false);
    }

    public PaintResult renderTripBeacon(
        Graphics2D graphics,
        TrackingDisplaySnapshot snapshot,
        @Nullable ItemManager itemManager,
        int preferredWidth,
        boolean showRewardTray,
        boolean streakMode,
        boolean keepTrayExpanded,
        HudPlusTextSize textSize,
        int backgroundOpacityPercent,
        int maxRewardRows,
        int maxHeightPx,
        @Nullable Color shellBackground,
        @Nullable Color titleColor,
        @Nullable Color labelColor,
        @Nullable Color valueColor,
        @Nullable Color positiveColor,
        @Nullable Color negativeColor,
        @Nullable Color headerColor,
        @Nullable Color accentColor,
        boolean showTarget,
        boolean preferExact,
        boolean showFolio,
        boolean folioOnLeft,
        @Nullable com.gpmanager.reward.SessionItemLedger.SessionLedgerSnapshot ledger,
        boolean activityHold,
        long nowEpochMillis,
        boolean compactTimer,
        boolean reducedMotion)
    {
        return renderTripBeacon(
            graphics, snapshot, itemManager, preferredWidth, showRewardTray, streakMode,
            keepTrayExpanded, textSize, backgroundOpacityPercent, maxRewardRows, maxHeightPx,
            shellBackground, titleColor, labelColor, valueColor, positiveColor, negativeColor,
            headerColor, accentColor, showTarget, preferExact, showFolio, folioOnLeft, ledger,
            activityHold, nowEpochMillis, compactTimer, true, reducedMotion);
    }

    public PaintResult renderTripBeacon(
        Graphics2D graphics,
        TrackingDisplaySnapshot snapshot,
        @Nullable ItemManager itemManager,
        int preferredWidth,
        boolean showRewardTray,
        boolean streakMode,
        boolean keepTrayExpanded,
        HudPlusTextSize textSize,
        int backgroundOpacityPercent,
        int maxRewardRows,
        int maxHeightPx,
        @Nullable Color shellBackground,
        @Nullable Color titleColor,
        @Nullable Color labelColor,
        @Nullable Color valueColor,
        @Nullable Color positiveColor,
        @Nullable Color negativeColor,
        @Nullable Color headerColor,
        @Nullable Color accentColor,
        boolean showTarget,
        boolean preferExact,
        boolean showFolio,
        boolean folioOnLeft,
        @Nullable com.gpmanager.reward.SessionItemLedger.SessionLedgerSnapshot ledger,
        boolean activityHold,
        long nowEpochMillis,
        boolean compactTimer,
        boolean autoResize,
        boolean reducedMotion)
    {
        return renderTripBeacon(
            graphics, snapshot, itemManager, preferredWidth, showRewardTray, streakMode,
            keepTrayExpanded, textSize, backgroundOpacityPercent, maxRewardRows, maxHeightPx,
            shellBackground, titleColor, labelColor, valueColor, positiveColor, negativeColor,
            headerColor, accentColor, showTarget, preferExact, showFolio, folioOnLeft, ledger,
            activityHold, nowEpochMillis, compactTimer, true, true, true, true, autoResize, reducedMotion);
    }

    public PaintResult renderTripBeacon(
        Graphics2D graphics,
        TrackingDisplaySnapshot snapshot,
        @Nullable ItemManager itemManager,
        int preferredWidth,
        boolean showRewardTray,
        boolean streakMode,
        boolean keepTrayExpanded,
        HudPlusTextSize textSize,
        int backgroundOpacityPercent,
        int maxRewardRows,
        int maxHeightPx,
        @Nullable Color shellBackground,
        @Nullable Color titleColor,
        @Nullable Color labelColor,
        @Nullable Color valueColor,
        @Nullable Color positiveColor,
        @Nullable Color negativeColor,
        @Nullable Color headerColor,
        @Nullable Color accentColor,
        boolean showTarget,
        boolean preferExact,
        boolean showFolio,
        boolean folioOnLeft,
        @Nullable com.gpmanager.reward.SessionItemLedger.SessionLedgerSnapshot ledger,
        boolean activityHold,
        long nowEpochMillis,
        boolean compactTimer,
        boolean showMetricLabels,
        boolean showActivityHeader,
        boolean autoResize,
        boolean reducedMotion)
    {
        return renderTripBeacon(
            graphics, snapshot, itemManager, preferredWidth, showRewardTray, streakMode,
            keepTrayExpanded, textSize, backgroundOpacityPercent, maxRewardRows, maxHeightPx,
            shellBackground, titleColor, labelColor, valueColor, positiveColor, negativeColor,
            headerColor, accentColor, showTarget, preferExact, showFolio, folioOnLeft, ledger,
            activityHold, nowEpochMillis, compactTimer, showMetricLabels, showActivityHeader,
            true, true, autoResize, reducedMotion);
    }

    public PaintResult renderTripBeacon(
        Graphics2D graphics,
        TrackingDisplaySnapshot snapshot,
        @Nullable ItemManager itemManager,
        int preferredWidth,
        boolean showRewardTray,
        boolean streakMode,
        boolean keepTrayExpanded,
        HudPlusTextSize textSize,
        int backgroundOpacityPercent,
        int maxRewardRows,
        int maxHeightPx,
        @Nullable Color shellBackground,
        @Nullable Color titleColor,
        @Nullable Color labelColor,
        @Nullable Color valueColor,
        @Nullable Color positiveColor,
        @Nullable Color negativeColor,
        @Nullable Color headerColor,
        @Nullable Color accentColor,
        boolean showTarget,
        boolean preferExact,
        boolean showFolio,
        boolean folioOnLeft,
        @Nullable com.gpmanager.reward.SessionItemLedger.SessionLedgerSnapshot ledger,
        boolean activityHold,
        long nowEpochMillis,
        boolean compactTimer,
        boolean showMetricLabels,
        boolean showActivityHeader,
        boolean showItemIcons,
        boolean showTrayTags,
        boolean autoResize,
        boolean reducedMotion)
    {
        return renderTripBeacon(
            graphics, snapshot, itemManager, preferredWidth, showRewardTray, streakMode,
            keepTrayExpanded, textSize, backgroundOpacityPercent, maxRewardRows, maxHeightPx,
            shellBackground, titleColor, labelColor, valueColor, positiveColor, negativeColor,
            headerColor, accentColor, showTarget, preferExact, showFolio, folioOnLeft, ledger,
            activityHold, nowEpochMillis, compactTimer, showMetricLabels, showActivityHeader,
            showItemIcons, showTrayTags, autoResize, reducedMotion, true);
    }

    public PaintResult renderTripBeacon(
        Graphics2D graphics,
        TrackingDisplaySnapshot snapshot,
        @Nullable ItemManager itemManager,
        int preferredWidth,
        boolean showRewardTray,
        boolean streakMode,
        boolean keepTrayExpanded,
        HudPlusTextSize textSize,
        int backgroundOpacityPercent,
        int maxRewardRows,
        int maxHeightPx,
        @Nullable Color shellBackground,
        @Nullable Color titleColor,
        @Nullable Color labelColor,
        @Nullable Color valueColor,
        @Nullable Color positiveColor,
        @Nullable Color negativeColor,
        @Nullable Color headerColor,
        @Nullable Color accentColor,
        boolean showTarget,
        boolean preferExact,
        boolean showFolio,
        boolean folioOnLeft,
        @Nullable com.gpmanager.reward.SessionItemLedger.SessionLedgerSnapshot ledger,
        boolean activityHold,
        long nowEpochMillis,
        boolean compactTimer,
        boolean showMetricLabels,
        boolean showActivityHeader,
        boolean showItemIcons,
        boolean showTrayTags,
        boolean autoResize,
        boolean reducedMotion,
        boolean showWildernessRisk)
    {
        lastResult = tripBeacon.paint(
            graphics,
            snapshot,
            itemManager,
            preferredWidth,
            showRewardTray,
            streakMode,
            keepTrayExpanded,
            textSize,
            backgroundOpacityPercent,
            maxRewardRows,
            maxHeightPx,
            shellBackground,
            titleColor,
            labelColor,
            valueColor,
            positiveColor,
            negativeColor,
            headerColor,
            accentColor,
            showTarget,
            preferExact,
            showFolio,
            folioOnLeft,
            ledger,
            activityHold,
            nowEpochMillis,
            compactTimer,
            showMetricLabels,
            showActivityHeader,
            showItemIcons,
            showTrayTags,
            autoResize,
            reducedMotion,
            showWildernessRisk);
        return lastResult;
    }

    private int measureHeader(
        FontMetrics metrics,
        int line,
        boolean showMetricLabels,
        TrackingDisplaySnapshot snapshot,
        int contentW,
        boolean showTarget,
        boolean preferExact,
        boolean showHoverDetail,
        @Nullable RewardObservation reward)
    {
        ProfitTargetPresentation target = snapshot.getProfitTarget();
        String rateValue = HudMetricPairFormatter.formatRateLine(
            snapshot.getProfitPerHour(),
            snapshot.isRateAvailable(),
            preferExact,
            false);
        String totalValue = HudMetricPairFormatter.formatTotalLine(
            snapshot.getNet(),
            showTarget ? target : null,
            preferExact,
            false);
        boolean stacked = !HudMetricPairFormatter.metricsFitSideBySide(metrics, rateValue, totalValue, contentW);
        int height = line + 2;
        if (showMetricLabels)
        {
            height += line + 2;
        }
        height += line;
        if (stacked)
        {
            height += (showMetricLabels ? line + 2 : 0) + line + 2;
        }
        if (showTarget && target != null && target.isPresent())
        {
            height += 6 + 2 + line;
        }
        if (showHoverDetail)
        {
            height += planHoverLines(snapshot, reward, showTarget, 0L, 0L).size() * (line + 1);
        }
        return height;
    }

    private static int waitingBodyHeight(
        TrackingDisplaySnapshot snapshot,
        FontMetrics metrics,
        int line,
        boolean showHoverDetail)
    {
        if (showHoverDetail && junkExpandRowCount(snapshot) > 0)
        {
            int rowH = Math.max(ICON_SLOT, line) + 2;
            return junkExpandRowCount(snapshot) * rowH;
        }
        String waiting = waitingBodyCopy(snapshot);
        return waiting == null || waiting.isEmpty() ? 0 : line + 2;
    }

    /** Empty Trip body — never restate Idle/Paused already shown in the header. */
    static String waitingBodyCopy(TrackingDisplaySnapshot snapshot)
    {
        String junk = junkAllFilteredBody(snapshot);
        if (junk != null)
        {
            return junk;
        }
        // Header-only when idle — no "Waiting for items" fluff.
        return "";
    }

    /** Whole seconds left until Auto-collapse / peer-flash clear. */
    static String dwellCountdownLabel(TrackingDisplaySnapshot snapshot, long now)
    {
        if (snapshot == null)
        {
            return "";
        }
        int seconds = snapshot.dwellSecondsRemaining(now);
        return seconds > 0 ? Integer.toString(seconds) : "";
    }

    /** Live observation whose stacks were all presentation-filtered. Always Expanded only. */
    static String junkAllFilteredBody(TrackingDisplaySnapshot snapshot)
    {
        if (snapshot == null || snapshot.getCompleteReward() == null)
        {
            return null;
        }
        FilteredRewardView filtered = snapshot.getFilteredReward();
        if (filtered == null || !filtered.isAllFiltered())
        {
            return null;
        }
        int n = filtered.getHiddenStackCount();
        return n > 0 ? "Hidden items (" + n + ")" : "Hidden items";
    }

    /** Partial filter footer under visible tray rows — Always Expanded only. */
    static String junkHiddenFooter(TrackingDisplaySnapshot snapshot)
    {
        return junkHiddenFooter(snapshot, true);
    }

    /**
     * Partial filter footer under visible tray rows.
     * Auto-collapse must not show {@code +N hidden items}; Always Expanded may.
     */
    static String junkHiddenFooter(TrackingDisplaySnapshot snapshot, boolean keepTrayExpanded)
    {
        if (!keepTrayExpanded || snapshot == null)
        {
            return null;
        }
        FilteredRewardView filtered = snapshot.getFilteredReward();
        if (filtered == null || !filtered.isPartiallyFiltered())
        {
            return null;
        }
        int n = filtered.getHiddenStackCount();
        if (n <= 0)
        {
            return null;
        }
        return n == 1 ? "+1 hidden item" : "+" + n + " hidden items";
    }

    /** Up to 3 hidden stacks for hover body expand. */
    static int junkExpandRowCount(TrackingDisplaySnapshot snapshot)
    {
        return Math.min(3, junkExpandItems(snapshot).size());
    }

    static List<RewardItem> junkExpandItems(TrackingDisplaySnapshot snapshot)
    {
        if (snapshot == null || snapshot.getFilteredReward() == null)
        {
            return List.of();
        }
        FilteredRewardView filtered = snapshot.getFilteredReward();
        if (!filtered.isAllFiltered() && !filtered.isPartiallyFiltered())
        {
            return List.of();
        }
        List<RewardItem> hidden = filtered.getHiddenItems();
        if (hidden == null || hidden.isEmpty())
        {
            return List.of();
        }
        int cap = Math.min(3, hidden.size());
        return hidden.subList(0, cap);
    }

    /** Lean hover row — shared by measure and paint. Package-visible for tests. */
    static final class HoverLine
    {
        final String left;
        final String right;
        final boolean amount;
        final long amountValue;

        HoverLine(String left, String right)
        {
            this.left = left;
            this.right = right == null ? "" : right;
            this.amount = false;
            this.amountValue = 0L;
        }

        HoverLine(String left, long amountValue)
        {
            this.left = left;
            this.right = "";
            this.amount = true;
            this.amountValue = amountValue;
        }
    }

    /**
     * Lean hover panel planner — measure and paint share this list so height hugs content.
     * Cap 6. Priority: Status, State, Hidden(+note), Gains, Costs, Latest.
     * Drops Rate/Active; Top/Recent only when no Hidden and budget remains.
     */
    static List<HoverLine> planHoverLines(
        TrackingDisplaySnapshot snapshot,
        @Nullable RewardObservation displayReward,
        boolean showTarget,
        long hoverRevenue,
        long hoverCosts)
    {
        List<HoverLine> planned = new ArrayList<>(6);
        final int cap = 6;
        RewardObservation stateReward = snapshot != null && snapshot.getCompleteReward() != null
            ? snapshot.getCompleteReward()
            : displayReward;
        FilteredRewardView filtered = snapshot == null ? null : snapshot.getFilteredReward();
        boolean filterActive = filtered != null
            && (filtered.isAllFiltered() || filtered.isPartiallyFiltered())
            && filtered.getHiddenStackCount() > 0;

        String status = snapshot == null ? "" : snapshot.getStatusLabel();
        if (status == null || status.trim().isEmpty() || "Tracking".equalsIgnoreCase(status.trim()))
        {
            status = "Live";
        }
        planned.add(new HoverLine("Status", status));
        planned.add(new HoverLine(
            "State",
            stateReward == null ? "-" : HudTrayState.tagLine(stateReward)));

        if (filterActive)
        {
            int shown = 0;
            for (RewardItem hidden : filtered.getHiddenItems())
            {
                if (hidden == null || shown >= 3 || planned.size() >= cap - 1)
                {
                    break;
                }
                planned.add(new HoverLine("Hidden", HudTrayState.itemDetailLine(hidden)));
                shown++;
            }
            if (planned.size() < cap)
            {
                planned.add(new HoverLine("Filter", "Hidden still counted"));
            }
        }

        if (planned.size() < cap)
        {
            planned.add(new HoverLine("Gains", hoverRevenue));
        }
        if (planned.size() < cap)
        {
            planned.add(new HoverLine("Costs", -Math.abs(hoverCosts)));
        }
        if (planned.size() < cap && snapshot != null)
        {
            planned.add(new HoverLine("Latest", snapshot.getLastChangeNet()));
        }

        if (!filterActive && planned.size() < cap && displayReward != null
            && displayReward.bestSettledDisplayItem() != null)
        {
            planned.add(new HoverLine(
                "Top item", HudTrayState.itemDetailLine(displayReward.bestSettledDisplayItem())));
        }
        if (!filterActive && planned.size() < cap && displayReward != null
            && !displayReward.presentationStacks().isEmpty())
        {
            List<RewardItem> shown = displayReward.presentationStacks();
            RewardItem recent = shown.get(shown.size() - 1);
            planned.add(new HoverLine("Recent item", HudTrayState.itemDetailLine(recent)));
        }
        if (planned.size() < cap && displayReward != null && displayReward.getBatch() != null
            && displayReward.getBatch().getEncounterCount() > 1)
        {
            planned.add(new HoverLine(
                "Batch", displayReward.getBatch().getEncounterCount() + " encounters"));
        }
        if (planned.size() < cap && showTarget && snapshot != null
            && snapshot.getProfitTarget() != null && snapshot.getProfitTarget().isPresent())
        {
            planned.add(new HoverLine("Target", snapshot.getProfitTarget().centeredProgressLine()));
        }

        if (planned.size() > cap)
        {
            return planned.subList(0, cap);
        }
        return planned;
    }

    private int measureSettled(
        FontMetrics metrics,
        int line,
        RewardObservation reward,
        TrackingDisplaySnapshot snapshot,
        boolean showHoverDetail)
    {
        int height = Math.max(ICON_SLOT, line) + 2;
        if (collectionFooter(reward) != null)
        {
            height += line + 2;
        }
        if (showHoverDetail && junkExpandRowCount(snapshot) > 0
            && snapshot.getFilteredReward() != null
            && snapshot.getFilteredReward().isPartiallyFiltered())
        {
            height += junkExpandRowCount(snapshot) * (Math.max(ICON_SLOT, line) + 2);
        }
        else if (junkHiddenFooter(snapshot) != null)
        {
            height += line + 2;
        }
        return height;
    }

    private int paintWaitingBody(
        Graphics2D g,
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        @Nullable ItemManager itemManager,
        int x,
        int y,
        int contentW,
        int line,
        boolean showHoverDetail)
    {
        if (showHoverDetail && junkExpandRowCount(snapshot) > 0)
        {
            return paintJunkExpandRows(g, metrics, snapshot, itemManager, x, y, contentW, line);
        }
        String waiting = waitingBodyCopy(snapshot);
        if (waiting != null && !waiting.isEmpty())
        {
            g.setColor(MUTED);
            g.drawString(truncate(waiting, metrics, contentW), x, y + metrics.getAscent());
        }
        return y;
    }

    private int paintJunkExpandRows(
        Graphics2D g,
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        @Nullable ItemManager itemManager,
        int x,
        int y,
        int contentW,
        int line)
    {
        List<RewardItem> items = junkExpandItems(snapshot);
        if (items.isEmpty())
        {
            return y;
        }
        RewardObservation complete = snapshot.getCompleteReward();
        if (complete != null)
        {
            bindSprites(complete, itemManager);
        }
        int rowH = Math.max(ICON_SLOT, line) + 2;
        for (RewardItem item : items)
        {
            if (item == null)
            {
                continue;
            }
            y = paintItemRow(
                g, metrics, item, x, y, contentW, line, rowH, 1f, MUTED, false, MUTED);
        }
        return y;
    }

    private int paintSettled(
        Graphics2D g,
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        RewardObservation reward,
        @Nullable ItemManager itemManager,
        int x,
        int y,
        int contentW,
        int line,
        Color label,
        Color value,
        boolean showHoverDetail)
    {
        bindSprites(reward, itemManager);
        // Prefer a confirmed stack so an unpicked top drop is never painted as counted.
        // Unconfirmed observed loot clears at end of reveal under Auto-collapse.
        // Always paint the filtered projection — never completeReward (junk leak).
        RewardItem best = reward.bestSettledDisplayItem();
        boolean confirmedHighlight = reward.bestConfirmedItem() != null
            || reward.getSourceKind().isSkilling()
            || reward.getSourceKind().isRecentPickup();
        boolean lossRow = best != null && best.isLoss();
        int rowH = Math.max(ICON_SLOT, line);
        BufferedImage sprite = !showIcons ? null : best == null ? fallback() : sprite(best.getItemId());
        if (sprite != null)
        {
            drawSprite(g, sprite, x, y + (rowH - ICON_SLOT) / 2, ICON_SLOT);
        }
        int textX = showIcons ? x + ICON_SLOT + 3 : x + 2;
        int rightReserve = 0;
        String rightTop = "";
        if (best != null && best.isValueKnown())
        {
            long shown = best.getRecordedValue();
            // Tray value: +182 (no " gp" — header already speaks GP).
            rightTop = (shown > 0L ? "+" : "") + QuantityFormatter.compactGp(shown);
            rightReserve = metrics.stringWidth(rightTop) + 4;
        }
        else if (best != null)
        {
            rightTop = "unpriced";
            rightReserve = metrics.stringWidth(rightTop) + 4;
        }
        String primary = best == null ? "" : best.compactLabel();
        int maxLeft = Math.max(20, contentW - ICON_SLOT - 10 - rightReserve);
        // Name stays neutral; only the GP value is profit/loss coloured.
        Color nameColor = confirmedHighlight ? value : MUTED;
        g.setColor(nameColor);
        g.drawString(truncate(primary, metrics, maxLeft), textX, y + (rowH + metrics.getAscent()) / 2 - 1);
        if (!rightTop.isEmpty())
        {
            Color rightColor = lossRow
                ? NEGATIVE
                : (confirmedHighlight && best != null && best.isValueKnown() ? ACCENT : MUTED);
            g.setColor(rightColor);
            g.drawString(rightTop, x + contentW - metrics.stringWidth(rightTop),
                y + (rowH + metrics.getAscent()) / 2 - 1);
        }
        y += rowH + 2;
        String footer = collectionFooter(reward);
        if (footer != null)
        {
            g.setColor(MUTED);
            g.drawString(truncate(footer, metrics, contentW), x, y + metrics.getAscent());
            y += line + 2;
        }
        if (showHoverDetail
            && snapshot.getFilteredReward() != null
            && snapshot.getFilteredReward().isPartiallyFiltered()
            && junkExpandRowCount(snapshot) > 0)
        {
            y = paintJunkExpandRows(g, metrics, snapshot, itemManager, x, y, contentW, line);
        }
        else
        {
            String junk = junkHiddenFooter(snapshot);
            if (junk != null)
            {
                g.setColor(MUTED);
                g.drawString(truncate(junk, metrics, contentW), x, y + metrics.getAscent());
                y += line + 2;
            }
        }
        return y;
    }

    private int measureReveal(
        FontMetrics metrics,
        int line,
        RewardObservation reward,
        RewardObservation completeForFooter,
        int rowCap)
    {
        int items = reward == null ? 0 : reward.presentationStacks().size();
        int shown = Math.min(rowCap, Math.max(0, items));
        int rowH = Math.max(ROW_ICON, line) + 2;
        int more = reward != null && reward.presentationStacks().size() > shown ? line + 2 : 0;
        String footer = collectionFooter(completeForFooter != null ? completeForFooter : reward);
        int footerH = footer == null ? 0 : line + 2;
        // Tag line + exact full rows only (no partial last row). Junk footer measured by caller via snapshot.
        return line + 2 + shown * rowH + more + footerH;
    }

    private int measureReveal(
        FontMetrics metrics,
        int line,
        RewardObservation reward,
        RewardObservation completeForFooter,
        TrackingDisplaySnapshot snapshot,
        int rowCap,
        boolean showHoverDetail)
    {
        int height = measureReveal(metrics, line, reward, completeForFooter, rowCap);
        if (showHoverDetail && junkExpandRowCount(snapshot) > 0
            && snapshot.getFilteredReward() != null
            && snapshot.getFilteredReward().isPartiallyFiltered())
        {
            height += junkExpandRowCount(snapshot) * (Math.max(ROW_ICON, line) + 2);
        }
        else if (junkHiddenFooter(snapshot) != null)
        {
            height += line + 2;
        }
        return height;
    }

    private int paintHeader(
        Graphics2D g,
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        int x,
        int y,
        int contentW,
        int line,
        Color title,
        Color label,
        Color value,
        Color positive,
        Color negative,
        Color header,
        boolean showMetricLabels,
        boolean streakReward,
        boolean showTarget,
        boolean showHoverDetail,
        long hoverRevenue,
        long hoverCosts,
        boolean preferExact,
        @Nullable RewardObservation reward,
        Color tagAccent)
    {
        String status = snapshot.getStatusLabel();
        String activity = HudPlusHeaderLabel.resolveFull(snapshot, false);
        if (streakReward)
        {
            activity += " · Streak";
        }
        String time = QuantityFormatter.duration(snapshot.getElapsedMillis());
        int timeW = metrics.stringWidth(time);
        int dot = Math.max(5, metrics.getAscent() / 2);
        g.setColor(statusColor(status));
        g.fillOval(x, y + (metrics.getAscent() - dot) / 2 + 1, dot, dot);
        String left = HudNames.compact(activity, Math.max(24, contentW - timeW - 8 - dot - 4), metrics);
        g.setColor(header);
        g.drawString(left, x + dot + 4, y + metrics.getAscent());
        g.drawString(time, x + contentW - timeW, y + metrics.getAscent());
        y += line + 2;

        ProfitTargetPresentation target = snapshot.getProfitTarget();
        String rateValue = HudMetricPairFormatter.formatRateLine(
            snapshot.getProfitPerHour(),
            snapshot.isRateAvailable(),
            preferExact,
            false);
        String totalValue = HudMetricPairFormatter.formatTotalLine(
            snapshot.getNet(),
            showTarget ? target : null,
            preferExact,
            false);
        Color rateColor = snapshot.isRateAvailable()
            ? amountColor(snapshot.getProfitPerHour(), positive, negative, value)
            : label;
        Color totalColor = amountColor(snapshot.getNet(), positive, negative, value);
        boolean stacked = !HudMetricPairFormatter.metricsFitSideBySide(metrics, rateValue, totalValue, contentW);
        if (stacked)
        {
            if (showMetricLabels)
            {
                g.setColor(label);
                g.drawString("GP/hr:", x, y + metrics.getAscent());
                y += line + 2;
            }
            g.setColor(rateColor);
            g.drawString(truncate(rateValue, metrics, contentW), x, y + metrics.getAscent());
            y += line + 2;
            if (showMetricLabels)
            {
                g.setColor(label);
                g.drawString("Total:", x, y + metrics.getAscent());
                y += line + 2;
            }
            g.setColor(totalColor);
            g.drawString(truncate(totalValue, metrics, contentW), x, y + metrics.getAscent());
            y += line;
        }
        else
        {
            if (showMetricLabels)
            {
                g.setColor(label);
                g.drawString("GP/hr:", x, y + metrics.getAscent());
                g.drawString("Total:", x + contentW - metrics.stringWidth("Total:"), y + metrics.getAscent());
                y += line + 2;
            }
            g.setColor(rateColor);
            g.drawString(rateValue, x, y + metrics.getAscent());
            g.setColor(totalColor);
            g.drawString(totalValue, x + contentW - metrics.stringWidth(totalValue), y + metrics.getAscent());
            y += line;
        }

        if (showTarget && target != null && target.isPresent())
        {
            y += 2;
            int barH = 6;
            g.setColor(SEPARATOR);
            g.fillRect(x, y, contentW, barH);
            int fillWidth = Math.round(contentW * target.getFillRatio());
            if (fillWidth > 0)
            {
                g.setColor(target.isReached() ? positive : ACCENT);
                g.fillRect(x, y, Math.min(contentW, fillWidth), barH);
            }
            y += barH + 2;
            String targetText = truncate(target.centeredProgressLine(), metrics, contentW);
            g.setColor(target.isReached() ? positive : label);
            int progressX = x + Math.max(0, (contentW - metrics.stringWidth(targetText)) / 2);
            g.drawString(targetText, progressX, y + metrics.getAscent());
            y += line;
        }
        if (showHoverDetail)
        {
            y = paintHoverDetail(
                g, metrics, snapshot, reward, x, y, contentW, line,
                label, value, positive, negative, showTarget, hoverRevenue, hoverCosts);
        }
        return y;
    }

    private int paintHoverDetail(
        Graphics2D g,
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        @Nullable RewardObservation reward,
        int x,
        int y,
        int contentW,
        int line,
        Color label,
        Color value,
        Color positive,
        Color negative,
        boolean showTarget,
        long hoverRevenue,
        long hoverCosts)
    {
        y += 2;
        List<HoverLine> lines = planHoverLines(snapshot, reward, showTarget, hoverRevenue, hoverCosts);
        for (HoverLine hoverLine : lines)
        {
            if (hoverLine.amount)
            {
                Color amountColor = amountColor(hoverLine.amountValue, positive, negative, value);
                if ("Gains".equals(hoverLine.left))
                {
                    amountColor = positive;
                }
                else if ("Costs".equals(hoverLine.left))
                {
                    amountColor = negative;
                }
                y = paintHoverLine(
                    g, metrics, x, y, contentW, line, label, amountColor,
                    hoverLine.left, hoverLine.amountValue);
            }
            else
            {
                Color rightColor = "Hidden".equals(hoverLine.left) ? MUTED : value;
                y = paintHoverText(
                    g, metrics, x, y, contentW, line, label, rightColor,
                    hoverLine.left, hoverLine.right);
            }
        }
        return y;
    }

    private static int paintHoverText(
        Graphics2D g,
        FontMetrics metrics,
        int x,
        int y,
        int contentW,
        int line,
        Color labelColor,
        Color valueColor,
        String left,
        String right)
    {
        g.setColor(labelColor);
        g.drawString(left, x, y + metrics.getAscent());
        String shown = truncate(right == null ? "" : right, metrics,
            Math.max(20, contentW - metrics.stringWidth(left) - 8));
        g.setColor(valueColor);
        g.drawString(shown, x + contentW - metrics.stringWidth(shown), y + metrics.getAscent());
        return y + line + 1;
    }

    private static int paintHoverLine(
        Graphics2D g,
        FontMetrics metrics,
        int x,
        int y,
        int contentW,
        int line,
        Color labelColor,
        Color valueColor,
        String label,
        long amount)
    {
        String right = (amount > 0L ? "+" : "") + QuantityFormatter.compactGp(amount);
        g.setColor(labelColor);
        g.drawString(label, x, y + metrics.getAscent());
        g.setColor(valueColor);
        g.drawString(right, x + contentW - metrics.stringWidth(right), y + metrics.getAscent());
        return y + line + 1;
    }

    private int paintReveal(
        Graphics2D g,
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        RewardObservation reward,
        @Nullable ItemManager itemManager,
        int x,
        int y,
        int contentW,
        int line,
        Color label,
        Color value,
        float entryProgress,
        int rowCap,
        boolean showHoverDetail)
    {
        bindSprites(reward, itemManager);
        float alpha = Math.max(0.45f, Math.min(1f, 0.45f + 0.55f * entryProgress));
        int slide = entryProgress >= 0.999f ? 0 : Math.round((1f - entryProgress) * 14f);
        y += slide;

        List<RewardItem> ranked = reward.presentationItemsByValueDesc();
        boolean multi = ranked.size() > 1;

        String tag = HudTrayState.tagLine(reward);
        g.setColor(withAlpha(ACCENT, alpha));
        g.drawString(truncate(tag, metrics, contentW), x, y + metrics.getAscent());
        boolean anyUnpriced = false;
        for (RewardItem item : ranked)
        {
            if (item != null && !item.isValueKnown())
            {
                anyUnpriced = true;
                break;
            }
        }
        String dwell = dwellCountdownLabel(snapshot, System.currentTimeMillis());
        if (!dwell.isEmpty())
        {
            g.setColor(withAlpha(MUTED, alpha));
            g.drawString(dwell, x + contentW - metrics.stringWidth(dwell), y + metrics.getAscent());
        }
        else if (multi && reward.isLootValueKnown() && !anyUnpriced)
        {
            String total = QuantityFormatter.compactGp(reward.getLootValueTotal());
            g.setColor(withAlpha(value, alpha));
            g.drawString(total, x + contentW - metrics.stringWidth(total), y + metrics.getAscent());
        }
        else if (multi && anyUnpriced)
        {
            String incomplete = "Total incomplete";
            g.setColor(withAlpha(MUTED, alpha));
            g.drawString(incomplete, x + contentW - metrics.stringWidth(incomplete), y + metrics.getAscent());
        }
        y += line + 2;

        RewardItem best = reward.bestSettledDisplayItem();
        int shown = Math.min(rowCap, ranked.size());
        int rowH = Math.max(ROW_ICON, line) + 2;
        for (int i = 0; i < shown; i++)
        {
            RewardItem item = ranked.get(i);
            boolean highlight = multi && best != null && item.getItemId() == best.getItemId();
            Color rowAccent = resolveRowAccent(snapshot, item.getItemId(), highlight);
            y = paintItemRow(
                g, metrics, item, x, y, contentW, line, rowH, alpha, value, highlight, rowAccent);
        }
        if (ranked.size() > shown)
        {
            g.setColor(withAlpha(MUTED, alpha));
            g.drawString("+" + (ranked.size() - shown) + " more", x, y + metrics.getAscent());
            y += line + 2;
        }

        RewardObservation complete = snapshot.getCompleteReward() != null
            ? snapshot.getCompleteReward()
            : reward;
        String footer = collectionFooter(complete);
        if (footer != null)
        {
            g.setColor(withAlpha(MUTED, alpha));
            g.drawString(truncate(footer, metrics, contentW), x, y + metrics.getAscent());
            y += line + 2;
        }
        if (showHoverDetail
            && snapshot.getFilteredReward() != null
            && snapshot.getFilteredReward().isPartiallyFiltered()
            && junkExpandRowCount(snapshot) > 0)
        {
            y = paintJunkExpandRows(g, metrics, snapshot, itemManager, x, y, contentW, line);
        }
        else
        {
            String junk = junkHiddenFooter(snapshot);
            if (junk != null)
            {
                g.setColor(withAlpha(MUTED, alpha));
                g.drawString(truncate(junk, metrics, contentW), x, y + metrics.getAscent());
                y += line + 2;
            }
        }
        return y;
    }

    private static Color resolveRowAccent(
        TrackingDisplaySnapshot snapshot,
        int itemId,
        boolean highlightBest)
    {
        if (snapshot != null && snapshot.getFilteredReward() != null)
        {
            Color reused = snapshot.getFilteredReward().colorFor(itemId);
            if (reused != null)
            {
                return reused;
            }
        }
        return highlightBest ? ACCENT : MUTED;
    }

    private int paintItemRow(
        Graphics2D g,
        FontMetrics metrics,
        RewardItem item,
        int x,
        int y,
        int contentW,
        int line,
        int rowH,
        float alpha,
        Color value,
        boolean highlightBest,
        Color accent)
    {
        Color edge = accent == null ? ACCENT : accent;
        BufferedImage image = showIcons ? sprite(item.getItemId()) : null;
        if (image != null)
        {
            drawSprite(g, image, x, y + (rowH - ROW_ICON) / 2, ROW_ICON);
        }
        if (highlightBest || (accent != null && !accent.equals(MUTED)))
        {
            // Fine left edge accent inside content padding — not outside the shell.
            g.setColor(withAlpha(edge, alpha));
            g.fillRect(x, y + 1, 2, rowH - 2);
        }

        String price = item.isValueKnown()
            ? ((item.getRecordedValue() > 0L ? "+" : "")
                + QuantityFormatter.compactGp(item.getRecordedValue()))
            : "unpriced";
        int priceW = metrics.stringWidth(price) + 4;
        int iconW = showIcons ? ROW_ICON : 0;
        int textX = x + iconW + 3;
        int maxName = Math.max(20, contentW - iconW - 10 - priceW);
        boolean loss = item.isLoss();
        g.setColor(withAlpha(value, alpha));
        g.drawString(
            truncate(item.compactLabel(), metrics, maxName),
            textX,
            y + (rowH + metrics.getAscent()) / 2 - 1);
        g.setColor(withAlpha(loss ? NEGATIVE : (highlightBest ? edge : MUTED), alpha));
        g.drawString(price, x + contentW - metrics.stringWidth(price), y + (rowH + metrics.getAscent()) / 2 - 1);
        return y + rowH;
    }

    /** Concise footer from {@link CollectionStatus}; null when no status should show. */
    static String collectionFooter(RewardObservation reward)
    {
        if (reward == null
            || reward.getSourceKind().isSkilling()
            || reward.getSourceKind() == RewardSourceKind.USED
            || reward.getSourceKind() == RewardSourceKind.LOST
            || reward.getSourceKind().isPeerPresentationFlash())
        {
            return null;
        }
        CollectionStatus status = reward.collectionStatus();
        // Auto-collapse preview of unpicked loot must not claim "Collection unconfirmed".
        // Unconfirmed trays just show the top item briefly, then clear.
        if (status == CollectionStatus.UNCONFIRMED)
        {
            return null;
        }
        return status.getLabel();
    }

    private void bindSprites(RewardObservation reward, @Nullable ItemManager itemManager)
    {
        if (reward == null)
        {
            return;
        }
        if (reward.getGeneration() != spriteGeneration)
        {
            spriteCache.clear();
            spriteGeneration = reward.getGeneration();
        }
        for (RewardItem item : reward.presentationStacks())
        {
            if (item == null || item.getItemId() <= 0 || spriteCache.containsKey(item.getItemId()))
            {
                continue;
            }
            BufferedImage image = null;
            if (itemManager != null)
            {
                try
                {
                    image = itemManager.getImage(item.getItemId(), 1, false);
                }
                catch (RuntimeException ignored)
                {
                    // fall through
                }
            }
            spriteCache.put(item.getItemId(), image == null ? fallback() : image);
        }
    }

    private BufferedImage sprite(int itemId)
    {
        BufferedImage cached = spriteCache.get(itemId);
        return cached == null ? fallback() : cached;
    }

    private BufferedImage fallback()
    {
        if (fallbackSprite == null)
        {
            fallbackSprite = new BufferedImage(ICON_SLOT, ICON_SLOT, BufferedImage.TYPE_INT_ARGB);
            for (int yy = 3; yy < ICON_SLOT - 3; yy++)
            {
                for (int xx = 3; xx < ICON_SLOT - 3; xx++)
                {
                    fallbackSprite.setRGB(xx, yy, 0xFFE2B84B);
                }
            }
        }
        return fallbackSprite;
    }

    private static void drawSprite(Graphics2D g, BufferedImage sprite, int x, int y, int slot)
    {
        HudPlusLayout.drawSprite(g, sprite, x, y, slot);
    }

    private static Font resolveFont(HudPlusTextSize textSize)
    {
        return HudPlusLayout.resolveFont(textSize == null ? HudPlusTextSize.NORMAL : textSize);
    }

    private static Color applyOpacity(Color color, int opacityPercent)
    {
        int pct = clamp(opacityPercent, 50, 100);
        int alpha = Math.round(255f * pct / 100f);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /** Custom background supplies RGB only on HUD+; alpha comes from opacity. */
    private static Color opaqueRgb(Color color)
    {
        return new Color(color.getRed(), color.getGreen(), color.getBlue());
    }

    private static Color statusColor(String status)
    {
        if ("Live".equals(status))
        {
            return LIVE_DOT;
        }
        if ("Paused".equals(status) || "Idle".equals(status) || "Banking".equals(status)
            || "AFK".equals(status)
            || "Recovery".equals(status)
            || "Account hold".equals(status))
        {
            return ACCENT;
        }
        if ("Stopped".equals(status))
        {
            return NEGATIVE;
        }
        return LIVE_DOT;
    }

    static Color amountColor(long amount, Color positive, Color negative, Color neutral)
    {
        if (amount > 0L)
        {
            return positive;
        }
        if (amount < 0L)
        {
            return negative;
        }
        return neutral;
    }

    private static Color withAlpha(Color color, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(color.getAlpha() * alpha)));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
    }

    private static String formatGp(long amount)
    {
        return QuantityFormatter.compactGp(Math.abs(amount)) + " gp";
    }

    static String truncate(String text, FontMetrics metrics, int maxWidth)
    {
        return HudDropRowComponent.truncateToWidth(text == null ? "" : text, metrics, maxWidth);
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }
}
