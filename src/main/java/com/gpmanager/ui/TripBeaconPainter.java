package com.gpmanager.ui;

import com.gpmanager.HudPlusTextSize;
import com.gpmanager.grounditems.FilteredRewardView;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardObservation;
import com.gpmanager.reward.RewardPresentationPhase;
import com.gpmanager.reward.SessionItemLedger;
import com.gpmanager.util.QuantityFormatter;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.client.game.ItemManager;

/**
 * Trip Beacon + satellite folio paint path for HUD+.
 * Dark glass, muted accent — GP/hr left, Total right; no nested tray well.
 */
final class TripBeaconPainter
{
    static final Color SHELL_BG = new Color(0x0A, 0x0C, 0x10, 230);
    static final Color SHELL_BORDER = new Color(0x1E, 0x22, 0x2B, 230);
    static final Color SEPARATOR = new Color(0x2A, 0x30, 0x3A, 200);
    /** Fallback accent when config colour is null — matches factory HUD+ accent. */
    static final Color TEAL = new Color(0x8B, 0x93, 0xA7);
    static final Color MINT = new Color(0x5C, 0xFF, 0xB0);
    static final Color CORAL = new Color(0xFF, 0x6B, 0x6B);
    static final Color LABEL = new Color(0x8B, 0x93, 0xA7);
    static final Color VALUE = new Color(0xE8, 0xEC, 0xF4);
    static final int PAD = 5;
    static final int RADIUS = 7;
    static final int GAP = 5;
    static final int FOLIO_MIN = 140;
    static final int FOLIO_MAX = DedicatedHudRenderer.MAX_WIDTH;
    /** Alias of {@link #FOLIO_MIN} for callers that need a pre-measure fallback. */
    static final int FOLIO_WIDTH = FOLIO_MIN;
    static final int ROW_ICON = HudPlusLayout.ICON_NORMAL;
    static final int TARGET_BAR_H = 3;
    static final int METRIC_GAP = 10;
    /** Gap above the cassette separator line. */
    static final int SEP_BEFORE = 2;
    /** Gap below the cassette separator line before tag/rows. */
    static final int SEP_AFTER = 3;
    /** Gap after the Chopped/Received tag before item rows. */
    static final int TAG_AFTER = 2;
    /** Breathing room after the last cassette row before shell bottom pad. */
    static final int CASSETTE_TAIL = 3;
    /** Extra vertical air between cassette / folio item rows. */
    static final int ROW_GAP = 1;
    /** Gap between icon slot and item label (left cluster). */
    static final int ICON_TEXT_GAP = 3;
    /** Minimum gap between truncated label and right-anchored GP. */
    static final int VALUE_GAP = 6;
    /** Vertical rhythm inside hero / folio section headers. */
    static final int LINE_GAP = 1;
    static final int SECTION_GAP = 3;

    private final Map<Integer, BufferedImage> spriteCache = new HashMap<>();
    private int spriteGeneration = -1;
    private BufferedImage fallbackSprite;

    /** Soft pulse when session Net changes (not tray-sum substitution). */
    private static final long NET_PUNCH_MS = 160L;
    /** Brief latest net delta badge, visible without taking permanent HUD space. */
    private static final long NET_CHANGE_CHIP_MS = 2_600L;
    private long lastPaintedNet = Long.MIN_VALUE;
    private long lastPaintedElapsed = Long.MIN_VALUE;
    private String lastPaintedSessionKey;
    private long netPunchStartMs;
    private long recentNetChange;
    private long netChangeStartedAtMs = Long.MIN_VALUE;

    DedicatedHudRenderer.PaintResult paint(
        Graphics2D g,
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
        @Nullable SessionItemLedger.SessionLedgerSnapshot ledger,
        boolean activityHold,
        long nowEpochMillis,
        boolean compactTimer,
        boolean autoResize,
        boolean reducedMotion)
    {
        return paint(
            g, snapshot, itemManager, preferredWidth, showRewardTray, streakMode, keepTrayExpanded,
            textSize, backgroundOpacityPercent, maxRewardRows, maxHeightPx, shellBackground,
            titleColor, labelColor, valueColor, positiveColor, negativeColor, headerColor,
            accentColor, showTarget, preferExact, showFolio, folioOnLeft, ledger, activityHold,
            nowEpochMillis, compactTimer, true, true, true, true, autoResize, reducedMotion, true);
    }

    DedicatedHudRenderer.PaintResult paint(
        Graphics2D g,
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
        @Nullable SessionItemLedger.SessionLedgerSnapshot ledger,
        boolean activityHold,
        long nowEpochMillis,
        boolean compactTimer,
        boolean showMetricLabels,
        boolean showActivityHeader,
        boolean autoResize,
        boolean reducedMotion)
    {
        return paint(
            g, snapshot, itemManager, preferredWidth, showRewardTray, streakMode, keepTrayExpanded,
            textSize, backgroundOpacityPercent, maxRewardRows, maxHeightPx, shellBackground,
            titleColor, labelColor, valueColor, positiveColor, negativeColor, headerColor,
            accentColor, showTarget, preferExact, showFolio, folioOnLeft, ledger, activityHold,
            nowEpochMillis, compactTimer, showMetricLabels, showActivityHeader, true, true,
            autoResize, reducedMotion, true);
    }

    DedicatedHudRenderer.PaintResult paint(
        Graphics2D g,
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
        @Nullable SessionItemLedger.SessionLedgerSnapshot ledger,
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
        HudPlusTextSize size = textSize == null ? HudPlusTextSize.NORMAL : textSize;
        Font font = HudPlusLayout.resolveFont(size);
        FontMetrics metrics = g.getFontMetrics(font);
        int line = metrics.getHeight();
        int iconSlot = showItemIcons ? HudPlusLayout.iconSlot(size) : 0;
        int rowCap = clamp(maxRewardRows, 3, 10);
        int ceiling = clamp(preferredWidth, DedicatedHudRenderer.MIN_WIDTH, DedicatedHudRenderer.MAX_WIDTH);

        Color bg = applyOpacity(opaque(shellBackground == null ? SHELL_BG : shellBackground), backgroundOpacityPercent);
        Color header = headerColor == null ? VALUE : headerColor;
        Color label = labelColor == null ? LABEL : labelColor;
        Color value = valueColor == null ? VALUE : valueColor;
        Color positive = positiveColor == null ? MINT : positiveColor;
        Color negative = negativeColor == null ? CORAL : negativeColor;
        Color accent = accentColor == null ? TEAL : accentColor;
        Color title = titleColor == null ? header : titleColor;
        boolean showRiskRow = showWildernessRisk && snapshot.isPvpPossible();

        RewardObservation reward = showRewardTray ? snapshot.getReward() : null;
        RewardPresentationPhase phase = snapshot.getRewardPhase();
        boolean showCassette = reward != null
            && reward.getSourceKind().supportsExpandedTray()
            && (phase == RewardPresentationPhase.REVEALING || keepTrayExpanded || activityHold);

        if (reward != null && showItemIcons)
        {
            bindSprites(reward, itemManager);
        }
        if (ledger != null && showItemIcons)
        {
            bindSprites(ledger.getGains(), itemManager);
            bindSprites(ledger.getLosses(), itemManager);
        }

        int measured = measureTripWidth(
            metrics, snapshot, reward, showCassette, streakMode && reward != null,
            showTarget, preferExact, nowEpochMillis, rowCap, compactTimer, iconSlot,
            showMetricLabels, showActivityHeader, showTrayTags, showRiskRow);
        int tripW;
        // Auto-size hugs the painted (compact) title, not the full semantic name —
        // otherwise "Grand Exchange Clerk" sizes the shell while "GE Clerk" is drawn.
        String headerActivity = HudNames.compact(HudPlusHeaderLabel.resolveFull(snapshot, false));
        if (streakMode && reward != null)
        {
            headerActivity += " · Streak";
        }
        int gemNeed = gemDiameter(metrics);
        int titleFit = showActivityHeader
            ? PAD * 2 + gemNeed + 6 + metrics.stringWidth(headerActivity)
                + 8 + metrics.stringWidth(formatElapsed(snapshot.getElapsedMillis(), compactTimer))
            : DedicatedHudRenderer.MIN_WIDTH;
        if (autoResize)
        {
            tripW = clamp(measured, DedicatedHudRenderer.MIN_WIDTH, ceiling);
            // Title-first: grow past preferred ceiling up to hard max.
            if (showActivityHeader && titleFit > tripW)
            {
                tripW = Math.min(DedicatedHudRenderer.MAX_WIDTH, Math.max(tripW, titleFit));
            }
        }
        else
        {
            // Fixed shell — Max width is the width.
            tripW = ceiling;
        }
        int contentW = tripW - PAD * 2;
        // Height always hugs content (Visible item rows caps cassette). Max height retired.

        int headerH = showActivityHeader ? line + 4 : 0;
        int heroH = measureHero(metrics, line, snapshot, showTarget, preferExact,
            showMetricLabels, contentW) + (pvpLine == null ? 0 : line + LINE_GAP);
        int riskH = showRiskRow ? line : 0;
        int rowH = Math.max(Math.max(iconSlot, 1), line) + ROW_GAP;
        if (!showItemIcons)
        {
            rowH = line + ROW_GAP;
        }
        boolean tagRow = cassetteTagRowVisible(snapshot, reward, showTrayTags, activityHold, nowEpochMillis);
        int cassetteH = 0;
        int itemRows = 0;
        if (showCassette)
        {
            // Visible filtered stacks only (snapshot reward is already filtered).
            int visible = Math.max(0, presentationCount(reward));
            if (visible <= 0)
            {
                showCassette = false;
                cassetteH = 0;
                itemRows = 0;
            }
            else
            {
                int n = Math.min(rowCap, visible);
                itemRows = n;
                cassetteH = SEP_BEFORE + SEP_AFTER + n * rowH + CASSETTE_TAIL;
                if (tagRow)
                {
                    cassetteH += line + TAG_AFTER;
                }
                if (visible > rowCap)
                {
                    cassetteH += line;
                }
                // Always Expanded only: honesty line under icon+qty+price rows.
                if (DedicatedHudRenderer.junkHiddenFooter(snapshot, keepTrayExpanded) != null)
                {
                    cassetteH += line;
                }
            }
        }
        else if (showRewardTray && reward == null && keepTrayExpanded)
        {
            // Auto-collapse: filtered-only loot must not open the tray. Always Expanded
            // keeps an honesty line for Hidden items.
            String junk = DedicatedHudRenderer.junkAllFilteredBody(snapshot);
            if (junk != null)
            {
                cassetteH = SEP_BEFORE + SEP_AFTER + line + CASSETTE_TAIL;
            }
        }

        // Paint inserts SEP_BEFORE+SEP_AFTER inside cassetteH — do not add another gap.
        int headerGap = showActivityHeader ? 2 : 0;
        int tripContentH = PAD + headerH + headerGap + heroH + riskH + cassetteH + PAD;
        int tripH = tripContentH;

        int folioH = 0;
        int folioW = 0;
        int folioContentRows = 0;
        if (showFolio)
        {
            folioH = measureFolio(metrics, line, ledger, rowCap, iconSlot, showItemIcons);
            folioContentRows = countFolioRows(ledger, rowCap);
            if (autoResize)
            {
                folioW = clamp(measureFolioWidth(metrics, ledger, rowCap, iconSlot, showItemIcons),
                    FOLIO_MIN, FOLIO_MAX);
            }
            else
            {
                folioW = clamp(ceiling, FOLIO_MIN, FOLIO_MAX);
            }
        }

        int totalW = tripW + (showFolio ? GAP + folioW : 0);
        int totalH = Math.max(tripH, showFolio ? folioH : 0);

        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Object oldText = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
        Object oldFrac = g.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS);
        Object oldInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        Object oldRender = g.getRenderingHint(RenderingHints.KEY_RENDERING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // RuneScape bitmap fonts: no AA, no fractional metrics (deriveFont blur).
        HudPlusLayout.applyCrispHints(g);
        g.setFont(font);

        int tripX = folioOnLeft && showFolio ? folioW + GAP : 0;
        int folioX = folioOnLeft ? 0 : tripW + GAP;

        paintShell(g, tripX, 0, tripW, tripH, bg);
        int y = PAD;
        String truncatedTitle = null;
        if (showActivityHeader)
        {
            truncatedTitle = paintTripHeader(g, metrics, snapshot, tripX + PAD, y, contentW, line,
                title, label, accent, value, negative, positive, streakMode && reward != null,
                nowEpochMillis, compactTimer, activityHold, reducedMotion);
            y += headerH;
            y += headerGap;
        }
        y = paintHero(g, metrics, snapshot, tripX + PAD, y, contentW, line,
            value, label, positive, negative, accent, showTarget, preferExact,
            showMetricLabels, reducedMotion, nowEpochMillis);
        if (showRiskRow)
        {
            paintRiskRow(g, metrics, snapshot, tripX + PAD, y, contentW, label, value);
            y += riskH;
        }
        if (cassetteH > 0)
        {
            int cassetteTop = y;
            int wellBottom = tripH - PAD;
            paintCassette(g, metrics, snapshot, reward, tripX + PAD, cassetteTop, contentW, line,
                Math.max(0, wellBottom - cassetteTop), rowCap, iconSlot, label, value, positive, negative,
                accent, keepTrayExpanded, activityHold, reducedMotion, nowEpochMillis,
                showItemIcons, showTrayTags);
        }

        if (showFolio)
        {
            paintShell(g, folioX, 0, folioW, Math.max(folioH, tripH), bg);
            paintFolio(g, metrics, ledger, folioX + PAD, PAD, folioW - PAD * 2, line,
                rowCap, iconSlot, label, value, positive, negative, accent, showItemIcons);
        }

        if (oldAA != null)
        {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
        if (oldText != null)
        {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, oldText);
        }
        if (oldFrac != null)
        {
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, oldFrac);
        }
        if (oldInterp != null)
        {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
        }
        if (oldRender != null)
        {
            g.setRenderingHint(RenderingHints.KEY_RENDERING, oldRender);
        }

        return new DedicatedHudRenderer.PaintResult(
            new Dimension(totalW, totalH),
            phase,
            showCassette,
            !showCassette && reward != null,
            reward != null && presentationCount(reward) <= 1,
            headerH + heroH + riskH,
            contentW,
            Math.max(itemRows, folioContentRows),
            tripW,
            folioW,
            truncatedTitle);
    }

    private static void paintShell(Graphics2D g, int x, int y, int w, int h, Color bg)
    {
        RoundRectangle2D shell = new RoundRectangle2D.Float(x, y, w, h, RADIUS * 2f, RADIUS * 2f);
        g.setColor(bg);
        g.fill(shell);
        g.setColor(SHELL_BORDER);
        g.setStroke(new BasicStroke(1f));
        g.draw(shell);
    }

    /**
     * @return full activity title when painted text was truncated; otherwise null
     */
    @Nullable
    private String paintTripHeader(
        Graphics2D g,
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        int x,
        int y,
        int contentW,
        int line,
        Color title,
        Color label,
        Color accent,
        Color neutral,
        Color coral,
        Color profit,
        boolean streak,
        long nowEpochMillis,
        boolean compactTimer,
        boolean activityHold,
        boolean reducedMotion)
    {
        String activity = HudPlusHeaderLabel.resolveFull(snapshot, activityHold);
        if (streak)
        {
            activity += " · Streak";
        }
        String time = formatElapsed(snapshot.getElapsedMillis(), compactTimer);
        int gem = gemDiameter(metrics);
        HudPlusStatusGem.Kind kind = HudPlusStatusGem.resolve(snapshot, activityHold);
        Color gemColor = HudPlusStatusGem.color(
            kind, accent, coral, label, neutral, profit, reducedMotion, nowEpochMillis);
        g.setColor(gemColor);
        g.fillOval(x, y + (metrics.getAscent() - gem) / 2 + 1, gem, gem);

        int timeW = metrics.stringWidth(time);
        int titleBudgetWithTime = contentW - gem - 6 - timeW - 8;
        String withTime = HudNames.compact(activity, Math.max(24, titleBudgetWithTime), metrics);
        int titleNeed = metrics.stringWidth(withTime);
        boolean paintTime = titleNeed <= Math.max(24, titleBudgetWithTime);
        int maxTitle = paintTime
            ? Math.max(24, titleBudgetWithTime)
            : Math.max(24, contentW - gem - 6);
        String painted = HudNames.compact(activity, maxTitle, metrics);
        g.setColor(title);
        g.drawString(painted, x + gem + 5, y + metrics.getAscent());

        if (paintTime)
        {
            g.setColor(label);
            g.drawString(time, x + contentW - timeW, y + metrics.getAscent());
        }
        return painted.equals(activity) ? null : activity;
    }

    @Nullable
    private String pvpLine;

    /** PvP layout line for HUD+ ("K/D 3 / 1 · +545k/kill"); null paints the General layout. */
    public void setPvpLine(@Nullable String text)
    {
        pvpLine = text == null || text.trim().isEmpty() ? null : text.trim();
    }

    private int paintHero(
        Graphics2D g,
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        int x,
        int y,
        int contentW,
        int line,
        Color value,
        Color label,
        Color positive,
        Color negative,
        Color accent,
        boolean showTarget,
        boolean preferExact,
        boolean showMetricLabels,
        boolean reducedMotion,
        long now)
    {
        boolean netChanged = updateNetChange(snapshot, now);
        if (netChanged && !reducedMotion)
        {
            netPunchStartMs = now;
        }

        String sessionTotal = HudMetricPairFormatter.formatTotalLine(
            snapshot.getNet(),
            showTarget ? snapshot.getProfitTarget() : null,
            preferExact,
            false);
        String rate = snapshot.isRateAvailable()
            ? HudMetricPairFormatter.formatRateLine(
                snapshot.getProfitPerHour(), true, preferExact, false)
            : com.gpmanager.diagnostics.RateAvailability.unavailableLabel();
        Color rateColor = snapshot.isRateAvailable()
            ? amountColor(snapshot.getProfitPerHour(), positive, negative, value)
            : label;

        // Session Net only — never substitute tray loot sum (banking/peer flashes).
        long net = snapshot.getNet();

        String rightText = sessionTotal;
        String chipText = visibleNetChangeChip(now);
        int chipWidth = chipText.isEmpty() ? 0 : netChangeChipWidth(metrics, chipText);
        Color rightColor = amountColor(net, positive, negative, value);
        boolean stacked = !HudMetricPairFormatter.metricsFitSideBySide(
            metrics, rate, rightText, contentW);

        if (stacked)
        {
            if (showMetricLabels)
            {
                g.setColor(label);
                g.drawString("GP/hr:", x, y + metrics.getAscent());
                y += line + LINE_GAP;
            }
            g.setColor(rateColor);
            g.drawString(truncate(rate, metrics, contentW), x, y + metrics.getAscent());
            y += line + LINE_GAP;
            if (showMetricLabels)
            {
                g.setColor(label);
                g.drawString("Total:", x, y + metrics.getAscent());
                y += line + LINE_GAP;
            }
            float pulse = reducedMotion ? 1f : netPunchScale(now);
            if (pulse < 0.995f)
            {
                g.setColor(withAlpha(rightColor, Math.min(1f, 0.72f + 0.28f * pulse)));
            }
            else
            {
                g.setColor(rightColor);
            }
            String paintedTotal = truncate(rightText, metrics, contentW);
            int totalWidth = metrics.stringWidth(paintedTotal);
            int totalX = x + contentW - totalWidth;
            if (chipWidth > 0 && totalWidth + chipWidth + 4 <= contentW)
            {
                drawNetChangeChip(g, metrics, chipText, totalX - chipWidth - 4,
                    y + metrics.getAscent(), netChangeColor(recentNetChange, positive, negative),
                    netChangeOpacity(now, reducedMotion));
            }
            g.drawString(paintedTotal, totalX, y + metrics.getAscent());
            y += line + LINE_GAP;
        }
        else
        {
            if (showMetricLabels)
            {
                g.setColor(label);
                g.drawString("GP/hr:", x, y + metrics.getAscent());
                g.drawString("Total:", x + contentW - metrics.stringWidth("Total:"), y + metrics.getAscent());
                y += line + LINE_GAP;
            }
            int rightW = metrics.stringWidth(rightText);
            boolean showChip = chipWidth > 0
                && contentW - rightW - METRIC_GAP - chipWidth - 4 >= 40;
            int rateMax = Math.max(40, contentW - rightW - METRIC_GAP
                - (showChip ? chipWidth + 4 : 0));
            g.setColor(rateColor);
            g.drawString(truncate(rate, metrics, rateMax), x, y + metrics.getAscent());

            int rightX = x + contentW - rightW;
            if (showChip)
            {
                drawNetChangeChip(g, metrics, chipText, rightX - chipWidth - 4,
                    y + metrics.getAscent(), netChangeColor(recentNetChange, positive, negative),
                    netChangeOpacity(now, reducedMotion));
            }
            float pulse = reducedMotion ? 1f : netPunchScale(now);
            if (pulse < 0.995f)
            {
                g.setColor(withAlpha(rightColor, Math.min(1f, 0.72f + 0.28f * pulse)));
            }
            else
            {
                g.setColor(rightColor);
            }
            g.drawString(rightText, rightX, y + metrics.getAscent());
            y += line + LINE_GAP;
        }

        if (pvpLine != null)
        {
            // PvP layout: kills / deaths and the per-kill figure sit under the hero.
            g.setColor(accent);
            g.drawString(truncate(pvpLine, metrics, contentW), x, y + metrics.getAscent());
            y += line + LINE_GAP;
        }

        if (snapshot.isOverallPeekPresent())
        {
            Color muted = withAlpha(label, 0.72f);
            String overallTotal = "Overall "
                + HudMetricPairFormatter.formatTotalLine(
                    snapshot.getOverallNet(), null, preferExact, false);
            String overallRate = snapshot.isOverallRateAvailable()
                ? HudMetricPairFormatter.formatRateLine(
                    snapshot.getOverallProfitPerHour(), true, preferExact, false)
                : com.gpmanager.diagnostics.RateAvailability.unavailableLabel();
            String peek = overallRate + "  ·  " + overallTotal + "  ·  paused";
            g.setColor(muted);
            g.drawString(truncate(peek, metrics, contentW), x, y + metrics.getAscent());
            y += line + LINE_GAP;
        }

        ProfitTargetPresentation target = snapshot.getProfitTarget();
        if (showTarget && target != null && target.isPresent())
        {
            String pct = target.percentLabel();
            int pctW = metrics.stringWidth(pct);
            int barW = Math.max(24, contentW - pctW - 8);
            int barY = y + Math.max(0, (metrics.getAscent() - TARGET_BAR_H) / 2);
            g.setColor(withAlpha(accent, 0.2f));
            g.fillRoundRect(x, barY, barW, TARGET_BAR_H, 2, 2);
            int fill = Math.max(0, Math.min(barW, Math.round(barW * target.getFillRatio())));
            if (fill > 0)
            {
                g.setColor(accent);
                g.fillRoundRect(x, barY, fill, TARGET_BAR_H, 2, 2);
            }
            g.setColor(label);
            g.drawString(pct, x + contentW - pctW, y + metrics.getAscent());
            y += Math.max(TARGET_BAR_H + 4, line);
        }
        return y;
    }

    /** Known-value tray sum (item rows / tests) — not used for the Total slot. */
    static long traySumDelta(TrackingDisplaySnapshot snapshot)
    {
        if (snapshot == null)
        {
            return 0L;
        }
        RewardObservation reward = snapshot.getReward();
        if (reward == null)
        {
            reward = snapshot.getCompleteReward();
        }
        return reward == null ? 0L : reward.getLootValueTotal();
    }

    private boolean updateNetChange(TrackingDisplaySnapshot snapshot, long now)
    {
        long net = snapshot.getNet();
        long elapsed = snapshot.getElapsedMillis();
        String sessionKey = (snapshot.isCustomSessionActive() ? "custom:" : "overall:")
            + snapshot.getSessionName();
        boolean changedSession = lastPaintedSessionKey != null
            && !lastPaintedSessionKey.equals(sessionKey);
        boolean elapsedReset = lastPaintedElapsed != Long.MIN_VALUE && elapsed < lastPaintedElapsed;
        boolean initialized = lastPaintedNet == Long.MIN_VALUE;
        boolean netChanged = !initialized && !changedSession && !elapsedReset && net != lastPaintedNet;

        if (changedSession || elapsedReset || initialized)
        {
            recentNetChange = 0L;
            netChangeStartedAtMs = Long.MIN_VALUE;
            netPunchStartMs = 0L;
        }
        else if (netChanged)
        {
            recentNetChange = subtractSaturated(net, lastPaintedNet);
            netChangeStartedAtMs = now;
        }

        lastPaintedNet = net;
        lastPaintedElapsed = elapsed;
        lastPaintedSessionKey = sessionKey;
        return netChanged;
    }

    private String visibleNetChangeChip(long now)
    {
        if (netChangeStartedAtMs == Long.MIN_VALUE)
        {
            return "";
        }
        long age = now - netChangeStartedAtMs;
        return age < 0L || age >= NET_CHANGE_CHIP_MS ? "" : netChangeChipText(recentNetChange);
    }

    private float netChangeOpacity(long now, boolean reducedMotion)
    {
        if (reducedMotion || netChangeStartedAtMs == Long.MIN_VALUE)
        {
            return 1f;
        }
        long age = Math.max(0L, now - netChangeStartedAtMs);
        long fadeStart = NET_CHANGE_CHIP_MS - 650L;
        return age <= fadeStart ? 1f : Math.max(0.25f,
            (NET_CHANGE_CHIP_MS - age) / 650f);
    }

    static String netChangeChipText(long delta)
    {
        if (delta == 0L)
        {
            return "";
        }
        String amount = QuantityFormatter.compactGp(delta);
        return delta > 0L ? "+" + amount : amount;
    }

    private static int netChangeChipWidth(FontMetrics metrics, String text)
    {
        return metrics.stringWidth(text) + 10;
    }

    private static Color netChangeColor(long delta, Color positive, Color negative)
    {
        return delta < 0L ? negative : positive;
    }

    private static void drawNetChangeChip(
        Graphics2D g,
        FontMetrics metrics,
        String text,
        int x,
        int baseline,
        Color tint,
        float opacity)
    {
        if (text == null || text.isEmpty())
        {
            return;
        }
        int height = Math.max(11, metrics.getHeight() - 4);
        int width = netChangeChipWidth(metrics, text);
        int top = baseline - metrics.getAscent() + (metrics.getHeight() - height) / 2;
        g.setColor(withAlpha(tint, 0.18f * opacity));
        g.fillRoundRect(x, top, width, height, height, height);
        g.setColor(withAlpha(tint, opacity));
        g.drawString(text, x + 5, baseline);
    }

    private static long subtractSaturated(long left, long right)
    {
        try
        {
            return Math.subtractExact(left, right);
        }
        catch (ArithmeticException ignored)
        {
            return left >= right ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private float netPunchScale(long now)
    {
        if (netPunchStartMs <= 0L)
        {
            return 1f;
        }
        long elapsed = now - netPunchStartMs;
        if (elapsed < 0L || elapsed >= NET_PUNCH_MS)
        {
            return 1f;
        }
        float t = elapsed / (float) NET_PUNCH_MS;
        return 1f + 0.16f * (1f - t) * (float) Math.sin(Math.PI * t);
    }

    static String formatElapsed(long millis, boolean compactTimer)
    {
        return compactTimer
            ? QuantityFormatter.compactDurationHud(millis)
            : QuantityFormatter.duration(millis);
    }

    /** Shared status-gem diameter for measure + paint (Auto Resize title budget). */
    static int gemDiameter(FontMetrics metrics)
    {
        if (metrics == null)
        {
            return 7;
        }
        return Math.max(7, metrics.getAscent() / 2 + 1);
    }

    private void paintCassette(
        Graphics2D g,
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        RewardObservation reward,
        int x,
        int y,
        int contentW,
        int line,
        int maxH,
        int rowCap,
        int iconSlot,
        Color label,
        Color value,
        Color positive,
        Color negative,
        Color accent,
        boolean keepTrayExpanded,
        boolean activityHold,
        boolean reducedMotion,
        long now,
        boolean showItemIcons,
        boolean showTrayTags)
    {
        float alpha = HudPlusTrayMotion.cassetteAlpha(
            now,
            snapshot == null ? 0L : snapshot.getRevealStartedAtEpochMillis(),
            snapshot == null ? 0L : snapshot.getRevealExpiresAtEpochMillis(),
            activityHold,
            reducedMotion);
        int slide = HudPlusTrayMotion.cassetteSlidePx(
            now,
            snapshot == null ? 0L : snapshot.getRevealStartedAtEpochMillis(),
            reducedMotion);
        y += slide;

        Composite previous = g.getComposite();
        if (alpha < 0.995f)
        {
            g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0.08f, alpha)));
        }

        if (reward == null)
        {
            y += SEP_BEFORE;
            g.setColor(SEPARATOR);
            g.drawLine(x, y, x + contentW, y);
            y += SEP_AFTER;
            String junk = DedicatedHudRenderer.junkAllFilteredBody(snapshot);
            if (junk != null)
            {
                g.setColor(label);
                g.drawString(truncate(junk, metrics, contentW), x, y + metrics.getAscent());
            }
            g.setComposite(previous);
            return;
        }

        int cassetteEnd = y + Math.max(0, maxH - slide);
        y += SEP_BEFORE;
        g.setColor(SEPARATOR);
        g.drawLine(x, y, x + contentW, y);
        y += SEP_AFTER;

        String right = trayRightLabel(snapshot, reward, activityHold, now);
        boolean paintTagRow = cassetteTagRowVisible(snapshot, reward, showTrayTags, activityHold, now);
        if (paintTagRow)
        {
            if (showTrayTags)
            {
                String tag = HudTrayState.tagLine(reward);
                g.setColor(accent);
                g.drawString(truncate(tag, metrics, contentW), x, y + metrics.getAscent());
            }
            if (!right.isEmpty())
            {
                g.setColor(label);
                g.drawString(right, x + contentW - metrics.stringWidth(right), y + metrics.getAscent());
            }
            y += line + TAG_AFTER;
        }

        List<RewardItem> ranked = reward.presentationItemsByValueDesc();
        int shown = Math.min(rowCap, ranked.size());
        int rowH = showItemIcons
            ? Math.max(iconSlot, line) + ROW_GAP
            : line + ROW_GAP;
        for (int i = 0; i < shown; i++)
        {
            if (y + rowH > cassetteEnd - CASSETTE_TAIL)
            {
                if (i == 0)
                {
                    // Height floor reserved one row — paint it rather than tag-only.
                    y = paintRow(g, metrics, ranked.get(0), x, y, contentW, rowH, iconSlot,
                        value, positive, negative, showItemIcons);
                    if (ranked.size() > 1)
                    {
                        g.setColor(label);
                        g.drawString("+" + (ranked.size() - 1) + " more", x, y + metrics.getAscent());
                    }
                    g.setComposite(previous);
                    return;
                }
                g.setColor(label);
                g.drawString("+" + (ranked.size() - i) + " more", x, y + metrics.getAscent());
                g.setComposite(previous);
                return;
            }
            RewardItem item = ranked.get(i);
            y = paintRow(g, metrics, item, x, y, contentW, rowH, iconSlot, value, positive, negative,
                showItemIcons);
        }
        if (ranked.size() > shown && y + line <= cassetteEnd - CASSETTE_TAIL)
        {
            g.setColor(label);
            g.drawString("+" + (ranked.size() - shown) + " more", x, y + metrics.getAscent());
            y += line;
        }
        String junk = DedicatedHudRenderer.junkHiddenFooter(snapshot, keepTrayExpanded);
        if (junk != null && y + line <= cassetteEnd)
        {
            g.setColor(label);
            g.drawString(truncate(junk, metrics, contentW), x, y + metrics.getAscent());
        }
        g.setComposite(previous);
    }

    /**
     * Blank while coalesce, batch-lock, or activity-hold freezes dwell (Busy gem
     * carries the live pulse); otherwise whole-second countdown, else item count.
     * Never returns "LIVE".
     */
    static String trayRightLabel(
        TrackingDisplaySnapshot snapshot,
        RewardObservation reward,
        boolean activityHold,
        long now)
    {
        if (activityHold || (snapshot != null && snapshot.isLootTrayBusy()))
        {
            return "";
        }
        String dwell = DedicatedHudRenderer.dwellCountdownLabel(snapshot, now);
        if (!dwell.isEmpty())
        {
            return dwell;
        }
        int count = presentationCount(reward);
        return count + (count == 1 ? " item" : " items");
    }

    static boolean cassetteTagRowVisible(
        @Nullable TrackingDisplaySnapshot snapshot,
        @Nullable RewardObservation reward,
        boolean showTrayTags,
        boolean activityHold,
        long now)
    {
        if (reward == null)
        {
            return false;
        }
        if (showTrayTags)
        {
            return true;
        }
        return !trayRightLabel(snapshot, reward, activityHold, now).isEmpty();
    }

    private void paintFolio(
        Graphics2D g,
        FontMetrics metrics,
        @Nullable SessionItemLedger.SessionLedgerSnapshot ledger,
        int x,
        int y,
        int contentW,
        int line,
        int rowCap,
        int iconSlot,
        Color label,
        Color value,
        Color positive,
        Color negative,
        Color accent,
        boolean showItemIcons)
    {
        g.setColor(accent);
        String folioTitle = ledger != null && ledger.getSessionName() != null
            ? ledger.getSessionName()
            : SessionOwnerLabels.DURABLE_OWNER_NAME;
        g.drawString(truncate(folioTitle, metrics, contentW), x, y + metrics.getAscent());
        y += line + SECTION_GAP;

        if (ledger == null || ledger.isEmpty())
        {
            g.setColor(label);
            g.drawString(
                truncate(SessionOwnerLabels.emptyFolioTip(folioTitle), metrics, contentW),
                x,
                y + metrics.getAscent());
            return;
        }

        int gainRows = Math.min(rowCap, Math.max(1, (rowCap + 1) / 2));
        int lossRows = Math.max(1, rowCap - gainRows);
        int rowH = showItemIcons
            ? Math.max(iconSlot, line) + ROW_GAP
            : line + ROW_GAP;

        g.setColor(positive);
        g.drawString("Gained", x, y + metrics.getAscent());
        y += line + LINE_GAP;
        List<RewardItem> gains = ledger.getGains();
        if (gains.isEmpty())
        {
            g.setColor(label);
            g.drawString("—", x, y + metrics.getAscent());
            y += line + LINE_GAP;
        }
        else
        {
            int n = Math.min(gainRows, gains.size());
            for (int i = 0; i < n; i++)
            {
                y = paintRow(g, metrics, gains.get(i), x, y, contentW, rowH, iconSlot,
                    value, positive, negative, showItemIcons);
            }
        }

        g.setColor(SEPARATOR);
        g.drawLine(x, y, x + contentW, y);
        y += SECTION_GAP;

        g.setColor(negative);
        g.drawString("Spent", x, y + metrics.getAscent());
        y += line + LINE_GAP;
        List<RewardItem> losses = ledger.getLosses();
        if (losses.isEmpty())
        {
            g.setColor(label);
            g.drawString("—", x, y + metrics.getAscent());
            y += line + LINE_GAP;
        }
        else
        {
            int n = Math.min(lossRows, losses.size());
            for (int i = 0; i < n; i++)
            {
                y = paintRow(g, metrics, losses.get(i), x, y, contentW, rowH, iconSlot,
                    value, positive, negative, showItemIcons);
            }
        }

        g.setColor(SEPARATOR);
        g.drawLine(x, y, x + contentW, y);
        y += SECTION_GAP;
        String net = (ledger.getNet() > 0L ? "+" : "")
            + QuantityFormatter.compactGp(ledger.getNet());
        g.setColor(amountColor(ledger.getNet(), positive, negative, value));
        g.drawString("Net " + net, x, y + metrics.getAscent());
        if (ledger.getHiddenCount() > 0)
        {
            String hid = ledger.getHiddenCount() + " hidden";
            g.setColor(label);
            g.drawString(hid, x + contentW - metrics.stringWidth(hid), y + metrics.getAscent());
        }
    }

    /**
     * Item row: left cluster (icon + name) flush left; GP flush right.
     * Name stays neutral; only the value uses profit/loss colour.
     */
    private int paintRow(
        Graphics2D g,
        FontMetrics metrics,
        RewardItem item,
        int x,
        int y,
        int contentW,
        int rowH,
        int iconSlot,
        Color value,
        Color positive,
        Color negative,
        boolean showItemIcons)
    {
        int textX = x;
        if (showItemIcons && iconSlot > 0)
        {
            int iconY = y + Math.max(0, (rowH - iconSlot) / 2);
            HudPlusLayout.drawIcon(g, sprite(item.getItemId()), x, iconY, iconSlot);
            textX = x + iconSlot + ICON_TEXT_GAP;
        }
        boolean loss = item.isLoss();
        String price = item.isValueKnown()
            ? ((item.getRecordedValue() > 0L ? "+" : "")
                + QuantityFormatter.compactGp(item.getRecordedValue()))
            : "—";
        int priceW = metrics.stringWidth(price);
        int valueX = x + contentW - priceW;
        int labelBudget = Math.max(12, valueX - textX - VALUE_GAP);
        int textY = y + (rowH + metrics.getAscent()) / 2 - 1;
        g.setColor(value);
        g.drawString(truncate(item.compactLabel(), metrics, labelBudget), textX, textY);
        g.setColor(loss ? negative : (item.isValueKnown() ? positive : LABEL));
        g.drawString(price, valueX, textY);
        return y + rowH;
    }

    private static void drawSprite(Graphics2D g, BufferedImage sprite, int x, int y, int slot)
    {
        HudPlusLayout.drawSprite(g, sprite, x, y, slot);
    }

    /** Content-hugging trip width (padding included); caller clamps to min/ceiling. */
    static int measureTripWidth(
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        @Nullable RewardObservation reward,
        boolean showCassette,
        boolean streak,
        boolean showTarget,
        boolean preferExact,
        long nowEpochMillis,
        int rowCap,
        boolean compactTimer)
    {
        return measureTripWidth(
            metrics, snapshot, reward, showCassette, streak, showTarget, preferExact,
            nowEpochMillis, rowCap, compactTimer, ROW_ICON);
    }

    static int measureTripWidth(
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        @Nullable RewardObservation reward,
        boolean showCassette,
        boolean streak,
        boolean showTarget,
        boolean preferExact,
        long nowEpochMillis,
        int rowCap,
        boolean compactTimer,
        int iconSlot)
    {
        return measureTripWidth(
            metrics, snapshot, reward, showCassette, streak, showTarget, preferExact,
            nowEpochMillis, rowCap, compactTimer, iconSlot, true, true, true, false);
    }

    static int measureTripWidth(
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        @Nullable RewardObservation reward,
        boolean showCassette,
        boolean streak,
        boolean showTarget,
        boolean preferExact,
        long nowEpochMillis,
        int rowCap,
        boolean compactTimer,
        int iconSlot,
        boolean showMetricLabels,
        boolean showActivityHeader)
    {
        return measureTripWidth(
            metrics, snapshot, reward, showCassette, streak, showTarget, preferExact,
            nowEpochMillis, rowCap, compactTimer, iconSlot, showMetricLabels, showActivityHeader,
            true, false);
    }

    static int measureTripWidth(
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        @Nullable RewardObservation reward,
        boolean showCassette,
        boolean streak,
        boolean showTarget,
        boolean preferExact,
        long nowEpochMillis,
        int rowCap,
        boolean compactTimer,
        int iconSlot,
        boolean showMetricLabels,
        boolean showActivityHeader,
        boolean showTrayTags,
        boolean showRiskRow)
    {
        int gem = gemDiameter(metrics);
        String activity = HudNames.compact(HudPlusHeaderLabel.resolveFull(snapshot, false));
        if (streak)
        {
            activity += " · Streak";
        }
        String time = formatElapsed(snapshot.getElapsedMillis(), compactTimer);
        int headerNeed = showActivityHeader
            ? gem + 6 + metrics.stringWidth(activity) + 8 + metrics.stringWidth(time)
            : 0;

        String total = HudMetricPairFormatter.formatTotalLine(
            snapshot.getNet(),
            showTarget ? snapshot.getProfitTarget() : null,
            preferExact,
            false);
        String rate = snapshot.isRateAvailable()
            ? HudMetricPairFormatter.formatRateLine(
                snapshot.getProfitPerHour(), true, preferExact, false)
            : com.gpmanager.diagnostics.RateAvailability.unavailableLabel();
        int heroNeed = metrics.stringWidth(rate) + METRIC_GAP + metrics.stringWidth(total);
        if (showMetricLabels)
        {
            heroNeed = Math.max(heroNeed,
                metrics.stringWidth("GP/hr:") + METRIC_GAP + metrics.stringWidth("Total:"));
        }
        if (showTarget && snapshot.getProfitTarget() != null && snapshot.getProfitTarget().isPresent())
        {
            heroNeed = Math.max(heroNeed,
                metrics.stringWidth(snapshot.getProfitTarget().percentLabel()) + 40);
        }
        if (snapshot.isOverallPeekPresent())
        {
            String overallTotal = "Overall "
                + HudMetricPairFormatter.formatTotalLine(
                    snapshot.getOverallNet(), null, preferExact, false);
            String overallRate = snapshot.isOverallRateAvailable()
                ? HudMetricPairFormatter.formatRateLine(
                    snapshot.getOverallProfitPerHour(), true, preferExact, false)
                : com.gpmanager.diagnostics.RateAvailability.unavailableLabel();
            heroNeed = Math.max(heroNeed,
                metrics.stringWidth(overallRate + "  ·  " + overallTotal + "  ·  paused"));
        }

        int cassetteNeed = 0;
        if (showCassette && reward != null)
        {
            String tag = showTrayTags ? HudTrayState.tagLine(reward) : "";
            String right = trayRightLabel(snapshot, reward, false, nowEpochMillis);
            if (showTrayTags || !right.isEmpty())
            {
                cassetteNeed = Math.max(cassetteNeed,
                    metrics.stringWidth(tag) + 8 + metrics.stringWidth(right));
                cassetteNeed = Math.max(cassetteNeed,
                    metrics.stringWidth(tag) + 8 + metrics.stringWidth("99s"));
            }
            List<RewardItem> ranked = reward.presentationItemsByValueDesc();
            int shown = Math.min(rowCap, ranked.size());
            int iconPad = iconSlot > 0 ? iconSlot + ICON_TEXT_GAP : 0;
            for (int i = 0; i < shown; i++)
            {
                RewardItem item = ranked.get(i);
                String price = item.isValueKnown()
                    ? ((item.getRecordedValue() > 0L ? "+" : "")
                        + QuantityFormatter.compactGp(item.getRecordedValue()))
                    : "—";
                cassetteNeed = Math.max(cassetteNeed,
                    iconPad + metrics.stringWidth(item.compactLabel())
                        + VALUE_GAP + metrics.stringWidth(price));
            }
        }

        int riskNeed = 0;
        if (showRiskRow)
        {
            String risk = snapshot.hasKnownWildernessRisk()
                ? QuantityFormatter.compactGp(snapshot.getWildernessRisk().getRiskValue())
                : "?";
            riskNeed = metrics.stringWidth("Risk:") + METRIC_GAP + metrics.stringWidth(risk);
        }

        int content = Math.max(headerNeed, Math.max(heroNeed, Math.max(cassetteNeed, riskNeed)));
        return content + PAD * 2;
    }

    private static void paintRiskRow(
        Graphics2D g,
        FontMetrics metrics,
        TrackingDisplaySnapshot snapshot,
        int x,
        int y,
        int contentW,
        Color label,
        Color value)
    {
        String amount = snapshot.hasKnownWildernessRisk()
            ? QuantityFormatter.compactGp(snapshot.getWildernessRisk().getRiskValue())
            : "?";
        g.setColor(label);
        g.drawString("Risk:", x, y + metrics.getAscent());
        g.setColor(value);
        int amountWidth = metrics.stringWidth(amount);
        g.drawString(amount, x + contentW - amountWidth, y + metrics.getAscent());
    }

    private static int measureHero(
        FontMetrics metrics,
        int line,
        TrackingDisplaySnapshot snapshot,
        boolean showTarget,
        boolean preferExact,
        boolean showMetricLabels,
        int contentW)
    {
        String sessionTotal = HudMetricPairFormatter.formatTotalLine(
            snapshot.getNet(),
            showTarget ? snapshot.getProfitTarget() : null,
            preferExact,
            false);
        String rate = snapshot.isRateAvailable()
            ? HudMetricPairFormatter.formatRateLine(
                snapshot.getProfitPerHour(), true, preferExact, false)
            : com.gpmanager.diagnostics.RateAvailability.unavailableLabel();
        boolean stacked = !HudMetricPairFormatter.metricsFitSideBySide(
            metrics, rate, sessionTotal, contentW);
        int h = 0;
        if (stacked)
        {
            h += showMetricLabels ? (line + LINE_GAP) * 2 : 0;
            h += (line + LINE_GAP) * 2;
        }
        else
        {
            h += showMetricLabels ? line + LINE_GAP : 0;
            h += line + LINE_GAP;
        }
        if (snapshot.isOverallPeekPresent())
        {
            h += line + LINE_GAP;
        }
        ProfitTargetPresentation target = snapshot.getProfitTarget();
        if (showTarget && target != null && target.isPresent())
        {
            h += Math.max(TARGET_BAR_H + 4, line);
        }
        return h;
    }

    private static int measureFolio(
        FontMetrics metrics,
        int line,
        @Nullable SessionItemLedger.SessionLedgerSnapshot ledger,
        int rowCap,
        int iconSlot,
        boolean showItemIcons)
    {
        int rowH = showItemIcons
            ? Math.max(iconSlot, line) + ROW_GAP
            : line + ROW_GAP;
        int h = PAD + line + SECTION_GAP;
        if (ledger == null || ledger.isEmpty())
        {
            return h + line + PAD;
        }
        int gainRows = Math.min(Math.max(1, (rowCap + 1) / 2), Math.max(1, ledger.getGains().size()));
        int lossRows = Math.min(Math.max(1, rowCap - gainRows), Math.max(1, ledger.getLosses().size()));
        if (ledger.getGains().isEmpty())
        {
            gainRows = 1;
        }
        if (ledger.getLosses().isEmpty())
        {
            lossRows = 1;
        }
        h += line + LINE_GAP + gainRows * rowH;
        h += SECTION_GAP + line + LINE_GAP + lossRows * rowH;
        h += SECTION_GAP + line + PAD;
        return h;
    }

    /** Content-hugging folio shell width (padding included). */
    static int measureFolioWidth(
        FontMetrics metrics,
        @Nullable SessionItemLedger.SessionLedgerSnapshot ledger,
        int rowCap,
        int iconSlot)
    {
        return measureFolioWidth(metrics, ledger, rowCap, iconSlot, true);
    }

    static int measureFolioWidth(
        FontMetrics metrics,
        @Nullable SessionItemLedger.SessionLedgerSnapshot ledger,
        int rowCap,
        int iconSlot,
        boolean showItemIcons)
    {
        String folioTitle = ledger != null && ledger.getSessionName() != null
            ? ledger.getSessionName()
            : SessionOwnerLabels.DURABLE_OWNER_NAME;
        int need = metrics.stringWidth(folioTitle);
        if (ledger == null || ledger.isEmpty())
        {
            need = Math.max(need, metrics.stringWidth(SessionOwnerLabels.emptyFolioTip(folioTitle)));
            return need + PAD * 2;
        }
        need = Math.max(need, metrics.stringWidth("Gained"));
        need = Math.max(need, metrics.stringWidth("Spent"));
        int gainRows = Math.min(rowCap, Math.max(1, (rowCap + 1) / 2));
        int lossRows = Math.max(1, rowCap - gainRows);
        List<RewardItem> gains = ledger.getGains();
        int nGain = Math.min(gainRows, Math.max(1, gains.size()));
        for (int i = 0; i < Math.min(nGain, gains.size()); i++)
        {
            need = Math.max(need, folioRowNeed(metrics, gains.get(i), iconSlot, showItemIcons));
        }
        List<RewardItem> losses = ledger.getLosses();
        int nLoss = Math.min(lossRows, Math.max(1, losses.size()));
        for (int i = 0; i < Math.min(nLoss, losses.size()); i++)
        {
            need = Math.max(need, folioRowNeed(metrics, losses.get(i), iconSlot, showItemIcons));
        }
        String net = "Net " + (ledger.getNet() > 0L ? "+" : "")
            + QuantityFormatter.compactGp(ledger.getNet());
        need = Math.max(need, metrics.stringWidth(net));
        if (ledger.getHiddenCount() > 0)
        {
            need = Math.max(need,
                metrics.stringWidth(net) + 8 + metrics.stringWidth(ledger.getHiddenCount() + " hidden"));
        }
        return need + PAD * 2;
    }

    private static int folioRowNeed(FontMetrics metrics, RewardItem item, int iconSlot, boolean showItemIcons)
    {
        if (item == null)
        {
            return showItemIcons ? iconSlot : 0;
        }
        String price = item.isValueKnown()
            ? ((item.getRecordedValue() > 0L ? "+" : "")
                + QuantityFormatter.compactGp(item.getRecordedValue()))
            : "—";
        int iconPad = showItemIcons && iconSlot > 0 ? iconSlot + ICON_TEXT_GAP : 0;
        return iconPad + metrics.stringWidth(item.compactLabel())
            + VALUE_GAP + metrics.stringWidth(price);
    }

    private static int countFolioRows(
        @Nullable SessionItemLedger.SessionLedgerSnapshot ledger,
        int rowCap)
    {
        if (ledger == null || ledger.isEmpty())
        {
            return 0;
        }
        return Math.min(rowCap, ledger.getGains().size() + ledger.getLosses().size());
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
        bindSprites(reward.presentationStacks(), itemManager);
    }

    private static int presentationCount(@Nullable RewardObservation reward)
    {
        if (reward == null)
        {
            return 0;
        }
        List<RewardItem> stacks = reward.presentationStacks();
        return stacks == null ? 0 : stacks.size();
    }

    private void bindSprites(List<RewardItem> items, @Nullable ItemManager itemManager)
    {
        if (items == null)
        {
            return;
        }
        for (RewardItem item : items)
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
            fallbackSprite = new BufferedImage(ROW_ICON, ROW_ICON, BufferedImage.TYPE_INT_ARGB);
        }
        return fallbackSprite;
    }

    private static Color amountColor(long amount, Color positive, Color negative, Color zero)
    {
        if (amount > 0L)
        {
            return positive;
        }
        if (amount < 0L)
        {
            return negative;
        }
        return zero;
    }

    private static Color applyOpacity(Color c, int percent)
    {
        int p = clamp(percent, 50, 100);
        int a = Math.round(255 * (p / 100f));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static Color opaque(Color c)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue());
    }

    private static Color withAlpha(Color c, float a)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * Math.max(0f, Math.min(1f, a))));
    }

    private static int clamp(int v, int min, int max)
    {
        return Math.max(min, Math.min(max, v));
    }

    private static String truncate(String text, FontMetrics metrics, int maxWidth)
    {
        if (text == null)
        {
            return "";
        }
        if (metrics.stringWidth(text) <= maxWidth)
        {
            return text;
        }
        String ellipsis = "…";
        int budget = maxWidth - metrics.stringWidth(ellipsis);
        if (budget <= 0)
        {
            return ellipsis;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++)
        {
            sb.append(text.charAt(i));
            if (metrics.stringWidth(sb.toString()) > budget)
            {
                sb.setLength(Math.max(0, sb.length() - 1));
                break;
            }
        }
        return sb.append(ellipsis).toString();
    }
}
