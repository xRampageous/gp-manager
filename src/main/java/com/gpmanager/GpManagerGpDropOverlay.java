package com.gpmanager;

import com.gpmanager.ui.HudPlusLayout;
import com.gpmanager.grounditems.LootPresentationFilterService;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.ProfitTargetParser;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.util.QuantityFormatter;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import net.runelite.client.config.FontType;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TextComponent;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ColorUtil;

/**
 * Passive transaction-driven GP drops.
 *
 * <p>The default presentation mirrors Profit Tracker's classic GP drop: a
 * coin sprite and a signed compact amount. Custom presets retain optional
 * item sprites. Values always come from committed profit-tracker
 * transactions; XP is never used as the accounting source.</p>
 */
public class GpManagerGpDropOverlay extends Overlay
{
    private static final int COINS_ITEM_ID = 995;
    private static final NumberFormat INTEGER_FORMAT = NumberFormat.getIntegerInstance(Locale.US);

    private final GpManagerConfig config;
    private final ItemManager itemManager;
    @Nullable
    private final LootPresentationFilterService lootFilter;
    private final Deque<GpDrop> drops = new ArrayDeque<>();

    @Inject
    public GpManagerGpDropOverlay(
        GpManagerPlugin plugin,
        GpManagerConfig config,
        ItemManager itemManager,
        @Nullable LootPresentationFilterService lootFilter)
    {
        super(plugin);
        this.config = config;
        this.itemManager = itemManager;
        this.lootFilter = lootFilter;
        setPosition(OverlayPosition.TOP_RIGHT);
        // Match Customizable XP Drops: under widgets so inventory/chat stay readable.
        setLayer(OverlayLayer.UNDER_WIDGETS);
        setPriority((float) config.gpDropOverlayPriority());
    }

    /** Test/compat constructor. */
    public GpManagerGpDropOverlay(GpManagerConfig config, ItemManager itemManager)
    {
        this(null, config, itemManager, null);
    }

    /** Test seam for exercising Ground Items presentation and accounting filters. */
    GpManagerGpDropOverlay(
        GpManagerConfig config,
        ItemManager itemManager,
        @Nullable LootPresentationFilterService lootFilter)
    {
        this(null, config, itemManager, lootFilter);
    }

    public synchronized void enqueue(ProfitTransaction transaction)
    {
        if (!ChangeFeedbackPolicy.showFloatingDrops(config) || transaction == null)
        {
            return;
        }

        boolean uncertain = transaction.getConfidence() == ClassificationConfidence.UNCERTAIN;
        if (uncertain && !config.showUncertainGpDrops())
        {
            return;
        }

        long transactionAmount = transaction.isCounted()
            ? transaction.getNet()
            : uncertain ? transaction.getAutomaticNet() : 0L;
        if (transactionAmount == 0L)
        {
            return;
        }

        List<DropCandidate> candidates = createCandidates(transaction, transactionAmount, uncertain);
        long now = System.currentTimeMillis();
        int stagger = Math.max(0, config.gpDropStaggerMillis());
        int index = 0;
        for (DropCandidate candidate : candidates)
        {
            enqueueCandidate(candidate, now, index * stagger);
            index++;
        }
        trimToConfiguredMaximum();
    }

    private List<DropCandidate> createCandidates(
        ProfitTransaction transaction,
        long transactionAmount,
        boolean uncertain)
    {
        List<ItemFlow> eligibleFlows = eligibleFlows(transaction.getFlows(), transaction.isCounted());
        LootPresentationFilter filterMode = config.lootPresentationFilter();
        boolean filtering = (filterMode != null && filterMode != LootPresentationFilter.ALL_ITEMS)
            || ProfitTargetParser.parseOrZero(config.minimumDisplayedLootValue()) > 0L
            || (transaction.isCounted()
                && config.accountingItemFilter() != LootPresentationFilter.ALL_ITEMS);
        if (filtering)
        {
            long visibleNet = sumFlows(eligibleFlows);
            // All gain rows filtered away — suppress floating feedback for this event.
            if (eligibleFlows.isEmpty() && (transactionAmount > 0L
                || allValuedFlowsExcludedByAccounting(transaction)))
            {
                return Collections.emptyList();
            }
            if (!eligibleFlows.isEmpty())
            {
                transactionAmount = visibleNet;
            }
        }

        GpDropContentMode mode = effectiveContentMode();

        // A manual correction can reinterpret the whole transaction. Preserve
        // that user decision by displaying its corrected total instead of the
        // original automatic per-flow signs.
        if (transaction.getCorrection() != TransactionCorrection.AUTO)
        {
            return singletonTotal(transactionAmount, uncertain, eligibleFlows);
        }

        switch (mode)
        {
            case TRANSACTION_TOTAL:
                return singletonTotal(transactionAmount, uncertain, eligibleFlows);
            case DIRECTION_TOTALS:
                return directionTotals(eligibleFlows, transactionAmount, uncertain);
            case EACH_ITEM:
                return itemRows(eligibleFlows, transactionAmount, uncertain);
            case SMART:
            default:
                int maximumRows = clamp(config.gpDropMaxItemRows(), 1, 8);
                if (!eligibleFlows.isEmpty() && eligibleFlows.size() <= maximumRows)
                {
                    return itemRows(eligibleFlows, transactionAmount, uncertain);
                }
                return directionTotals(eligibleFlows, transactionAmount, uncertain);
        }
    }

    private static long sumFlows(List<ItemFlow> flows)
    {
        long sum = 0L;
        for (ItemFlow flow : flows)
        {
            if (flow != null)
            {
                sum = safeAdd(sum, flow.getValueDelta());
            }
        }
        return sum;
    }

    private List<ItemFlow> eligibleFlows(List<ItemFlow> flows, boolean counted)
    {
        List<ItemFlow> eligible = new ArrayList<>();
        int minimum = Math.max(0, config.gpDropMinimumValue());
        LootPresentationFilter filterMode = config.lootPresentationFilter();
        boolean reuseColors = config.reuseGroundItemsHighlightColors();
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getItemId() <= 0 || flow.getValueDelta() == 0L)
            {
                continue;
            }
            if (flow.getValueDelta() < 0L && !config.showGpDropCosts())
            {
                continue;
            }
            if (safeAbs(flow.getValueDelta()) < minimum)
            {
                continue;
            }
            if (counted && lootFilter != null
                && !lootFilter.isFlowIncluded(flow, config.accountingItemFilter()))
            {
                continue;
            }
            if (lootFilter != null
                && !lootFilter.isFlowVisible(flow, filterMode, reuseColors,
                    ProfitTargetParser.parseOrZero(config.minimumDisplayedLootValue())))
            {
                continue;
            }
            eligible.add(flow);
        }
        eligible.sort(Comparator.comparingLong((ItemFlow flow) -> safeAbs(flow.getValueDelta())).reversed());
        return eligible;
    }

    private boolean allValuedFlowsExcludedByAccounting(ProfitTransaction transaction)
    {
        LootPresentationFilter accountingMode = config.accountingItemFilter();
        if (transaction == null || !transaction.isCounted()
            || lootFilter == null
            || accountingMode == null
            || accountingMode == LootPresentationFilter.ALL_ITEMS
            || transaction.getFlows() == null)
        {
            return false;
        }

        boolean foundValuedFlow = false;
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null || flow.getItemId() <= 0 || flow.getValueDelta() == 0L)
            {
                continue;
            }
            foundValuedFlow = true;
            if (lootFilter.isFlowIncluded(flow, accountingMode))
            {
                return false;
            }
        }
        return foundValuedFlow;
    }

    synchronized int queuedDropCountForTests()
    {
        return drops.size();
    }

    private List<DropCandidate> singletonTotal(
        long amount,
        boolean uncertain,
        List<ItemFlow> flows)
    {
        if (!isVisibleAmount(amount))
        {
            return Collections.emptyList();
        }
        return Collections.singletonList(DropCandidate.summary(
            amount,
            uncertain,
            iconTokensForDirection(flows, Long.signum(amount)),
            ""));
    }

    private List<DropCandidate> directionTotals(
        List<ItemFlow> flows,
        long fallbackAmount,
        boolean uncertain)
    {
        if (flows.isEmpty())
        {
            return singletonTotal(fallbackAmount, uncertain, flows);
        }

        long gains = 0L;
        long costs = 0L;
        List<ItemFlow> gainFlows = new ArrayList<>();
        List<ItemFlow> costFlows = new ArrayList<>();
        for (ItemFlow flow : flows)
        {
            if (flow.getValueDelta() > 0L)
            {
                gains = safeAdd(gains, flow.getValueDelta());
                gainFlows.add(flow);
            }
            else if (flow.getValueDelta() < 0L)
            {
                costs = safeAdd(costs, flow.getValueDelta());
                costFlows.add(flow);
            }
        }

        List<DropCandidate> result = new ArrayList<>(2);
        if (isVisibleAmount(gains))
        {
            result.add(DropCandidate.summary(
                gains,
                uncertain,
                iconTokensForDirection(gainFlows, 1L),
                ""));
        }
        if (isVisibleAmount(costs) && config.showGpDropCosts())
        {
            result.add(DropCandidate.summary(
                costs,
                uncertain,
                iconTokensForDirection(costFlows, -1L),
                ""));
        }

        return result.isEmpty()
            ? singletonTotal(fallbackAmount, uncertain, flows)
            : result;
    }

    private List<DropCandidate> itemRows(
        List<ItemFlow> flows,
        long fallbackAmount,
        boolean uncertain)
    {
        if (flows.isEmpty())
        {
            return singletonTotal(fallbackAmount, uncertain, flows);
        }

        int maximumRows = clamp(config.gpDropMaxItemRows(), 1, 8);
        if (maximumRows == 1 && flows.size() > 1)
        {
            return singletonTotal(fallbackAmount, uncertain, flows);
        }

        List<DropCandidate> result = new ArrayList<>();
        int directRows = Math.min(flows.size(), maximumRows);
        if (flows.size() > maximumRows)
        {
            directRows = maximumRows - 1;
        }

        for (int index = 0; index < directRows; index++)
        {
            ItemFlow flow = flows.get(index);
            result.add(DropCandidate.item(
                flow.getValueDelta(),
                uncertain,
                flow.getItemId(),
                safeAbs(flow.getQuantityDelta()),
                flow.getItemName()));
        }

        if (flows.size() > maximumRows)
        {
            long remainder = 0L;
            List<ItemFlow> remainderFlows = new ArrayList<>();
            for (int index = directRows; index < flows.size(); index++)
            {
                ItemFlow flow = flows.get(index);
                remainder = safeAdd(remainder, flow.getValueDelta());
                remainderFlows.add(flow);
            }
            if (isVisibleAmount(remainder))
            {
                result.add(DropCandidate.summary(
                    remainder,
                    uncertain,
                    iconTokensForDirection(remainderFlows, Long.signum(remainder)),
                    "Other"));
            }
        }

        return result.isEmpty()
            ? singletonTotal(fallbackAmount, uncertain, flows)
            : result;
    }

    private void enqueueCandidate(DropCandidate candidate, long now, long staggerDelayMillis)
    {
        if (candidate == null || !isVisibleAmount(candidate.amount))
        {
            return;
        }

        List<IconToken> icons = resolveIcons(candidate);
        String combineKey = resolveCombineKey(candidate);
        int combineWindow = Math.max(0, config.gpDropCombineMillis());
        GpDrop mergeTarget = findMergeTarget(combineKey, candidate.uncertain, now, combineWindow);
        if (mergeTarget != null)
        {
            mergeTarget.amount = safeAdd(mergeTarget.amount, candidate.amount);
            mergeTarget.quantity = safeAdd(mergeTarget.quantity, candidate.quantity);
            mergeTarget.icons = mergeIcons(mergeTarget.icons, icons);
            mergeTarget.updatedAt = now;
            if (config.gpDropReanimateOnCombine())
            {
                mergeTarget.animationStartedAt = now;
                mergeTarget.visibleAt = now;
            }
            return;
        }

        long visibleAt = now + Math.max(0L, staggerDelayMillis);
        drops.addLast(new GpDrop(
            candidate.amount,
            candidate.uncertain,
            candidate.itemSpecific,
            candidate.primaryItemId,
            candidate.quantity,
            candidate.itemName,
            candidate.summaryLabel,
            icons,
            combineKey,
            now,
            visibleAt));
    }

    /** Prefer the newest compatible in-flight row, not only the deque tail. */
    private GpDrop findMergeTarget(String combineKey, boolean uncertain, long now, int combineWindow)
    {
        if (combineKey == null || drops.isEmpty())
        {
            return null;
        }
        GpDrop[] snapshot = drops.toArray(new GpDrop[0]);
        for (int index = snapshot.length - 1; index >= 0; index--)
        {
            GpDrop drop = snapshot[index];
            if (combineKey.equals(drop.combineKey)
                && drop.uncertain == uncertain
                && now - drop.updatedAt <= combineWindow)
            {
                return drop;
            }
        }
        return null;
    }

    private boolean isVisibleAmount(long amount)
    {
        if (amount == 0L || (amount < 0L && !config.showGpDropCosts()))
        {
            return false;
        }
        return safeAbs(amount) >= Math.max(0, config.gpDropMinimumValue());
    }

    private String resolveCombineKey(DropCandidate candidate)
    {
        GpDropCombineMode mode = config.gpDropCombineMode();
        if (mode == null)
        {
            mode = GpDropCombineMode.SMART;
        }
        long direction = Long.signum(candidate.amount);
        switch (mode)
        {
            case OFF:
                return null;
            case SAME_ITEM:
                return candidate.primaryItemId > 0
                    ? "item:" + candidate.primaryItemId + ':' + direction
                    : null;
            case SAME_DIRECTION:
                return "direction:" + direction;
            case SMART:
            default:
                return candidate.itemSpecific && candidate.primaryItemId > 0
                    ? "item:" + candidate.primaryItemId + ':' + direction
                    : "summary:" + direction;
        }
    }

    private List<IconToken> resolveIcons(DropCandidate candidate)
    {
        GpDropIconMode mode = effectiveIconMode();
        if (mode == GpDropIconMode.NONE)
        {
            return Collections.emptyList();
        }
        if (mode == GpDropIconMode.COINS)
        {
            return Collections.singletonList(new IconToken(
                COINS_ITEM_ID,
                safeAbs(candidate.amount),
                safeAbs(candidate.amount)));
        }

        IconToken primary = candidate.icons.isEmpty() ? null : candidate.icons.get(0);
        switch (mode)
        {
            case ITEM:
                return primary == null ? Collections.emptyList() : Collections.singletonList(primary);
            case ITEM_OR_COINS:
                return primary == null
                    ? Collections.singletonList(new IconToken(COINS_ITEM_ID, 0L, safeAbs(candidate.amount)))
                    : Collections.singletonList(primary);
            case ITEM_STRIP:
                return truncateIcons(candidate.icons);
            case SMART:
                if (candidate.itemSpecific && primary != null)
                {
                    return Collections.singletonList(primary);
                }
                List<IconToken> strip = truncateIcons(candidate.icons);
                return strip.isEmpty()
                    ? Collections.singletonList(new IconToken(COINS_ITEM_ID, 0L, safeAbs(candidate.amount)))
                    : strip;
            case NONE:
            default:
                return Collections.emptyList();
        }
    }

    private GpDropPresentationPreset effectivePreset()
    {
        GpDropPresentationPreset preset = config.gpDropPresentationPreset();
        return preset == null ? GpDropPresentationPreset.ICON_VALUE_ONLY : preset;
    }

    private GpDropContentMode effectiveContentMode()
    {
        GpDropPresentationPreset preset = effectivePreset();
        if (!preset.isCustom())
        {
            return preset.getContentMode();
        }
        GpDropContentMode mode = config.gpDropContentMode();
        return mode == null ? GpDropContentMode.SMART : mode;
    }

    private GpDropItemTextMode effectiveItemTextMode()
    {
        GpDropPresentationPreset preset = effectivePreset();
        if (!preset.isCustom())
        {
            return preset.getItemTextMode();
        }
        GpDropItemTextMode mode = config.gpDropItemTextMode();
        return mode == null ? GpDropItemTextMode.QUANTITY : mode;
    }

    private GpDropIconMode effectiveIconMode()
    {
        GpDropPresentationPreset preset = effectivePreset();
        if (!preset.isCustom())
        {
            return preset.getIconMode();
        }
        GpDropIconMode mode = config.gpDropIconMode();
        return mode == null ? GpDropIconMode.SMART : mode;
    }

    private boolean effectiveQuantityOnIcon()
    {
        GpDropPresentationPreset preset = effectivePreset();
        return preset.isCustom() ? config.gpDropQuantityOnIcon() : preset.isQuantityOnIcon();
    }

    private List<IconToken> truncateIcons(List<IconToken> icons)
    {
        int maximum = clamp(config.gpDropMaxIcons(), 1, 6);
        if (icons.size() <= maximum)
        {
            return new ArrayList<>(icons);
        }
        return new ArrayList<>(icons.subList(0, maximum));
    }

    private List<IconToken> iconTokensForDirection(List<ItemFlow> flows, long direction)
    {
        Map<Integer, IconToken> byItem = new LinkedHashMap<>();
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getItemId() <= 0 || Long.signum(flow.getValueDelta()) != direction)
            {
                continue;
            }
            IconToken existing = byItem.get(flow.getItemId());
            if (existing == null)
            {
                byItem.put(flow.getItemId(), new IconToken(
                    flow.getItemId(),
                    safeAbs(flow.getQuantityDelta()),
                    safeAbs(flow.getValueDelta())));
            }
            else
            {
                existing.quantity = safeAdd(existing.quantity, safeAbs(flow.getQuantityDelta()));
                existing.weight = safeAdd(existing.weight, safeAbs(flow.getValueDelta()));
            }
        }
        List<IconToken> result = new ArrayList<>(byItem.values());
        result.sort(Comparator.comparingLong((IconToken token) -> token.weight).reversed());
        return result;
    }

    private List<IconToken> mergeIcons(List<IconToken> left, List<IconToken> right)
    {
        Map<Integer, IconToken> merged = new LinkedHashMap<>();
        mergeIconList(merged, left);
        mergeIconList(merged, right);
        List<IconToken> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparingLong((IconToken token) -> token.weight).reversed());
        int maximum = clamp(config.gpDropMaxIcons(), 1, 6);
        return result.size() <= maximum ? result : new ArrayList<>(result.subList(0, maximum));
    }

    private static void mergeIconList(Map<Integer, IconToken> merged, List<IconToken> icons)
    {
        for (IconToken token : icons)
        {
            IconToken existing = merged.get(token.itemId);
            if (existing == null)
            {
                merged.put(token.itemId, new IconToken(token.itemId, token.quantity, token.weight));
            }
            else
            {
                existing.quantity = safeAdd(existing.quantity, token.quantity);
                existing.weight = safeAdd(existing.weight, token.weight);
            }
        }
    }

    @Override
    public synchronized Dimension render(Graphics2D graphics)
    {
        if (!ChangeFeedbackPolicy.showFloatingDrops(config))
        {
            drops.clear();
            return null;
        }

        setPriority((float) config.gpDropOverlayPriority());

        long now = System.currentTimeMillis();
        long duration = Math.max(500L, config.gpDropDurationMillis());
        while (!drops.isEmpty() && now - drops.peekFirst().updatedAt > duration)
        {
            drops.removeFirst();
        }
        trimToConfiguredMaximum();
        if (drops.isEmpty())
        {
            return null;
        }

        FontType fontType = config.gpDropFontType();
        if (fontType == null)
        {
            fontType = GpDropFontHandler.fromLegacyStyle(config.gpDropFontStyle());
        }
        GpDropFontHandler.apply(graphics, fontType);
        Font font = GpDropFontHandler.resolve(fontType);
        FontMetrics metrics = graphics.getFontMetrics(font);
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        int spacing = clamp(config.gpDropLineSpacing(), 0, 18);
        int iconSize = Math.max(18, clamp(config.gpDropIconSize(), 10, 48));
        int iconGap = clamp(config.gpDropIconGap(), 0, 16);
        int iconOverlap = clamp(
            config.gpDropIconOverlap(),
            0,
            Math.max(0, iconSize - 2));

        List<GpDrop> visible = new ArrayList<>();
        for (GpDrop drop : drops)
        {
            if (now >= drop.visibleAt)
            {
                visible.add(drop);
            }
        }
        if (visible.isEmpty())
        {
            // Still reserve space while staggered rows wait.
            return new Dimension(1, 1);
        }
        if (config.gpDropStackDirection() == GpDropStackDirection.NEWEST_TOP)
        {
            Collections.reverse(visible);
        }

        // Constant-ish width against a format pattern so the snapped overlay does not jitter.
        int patternWidth = metrics.stringWidth("+###,###,###");
        int contentWidth = patternWidth;
        int contentHeight = 0;
        List<RenderedDrop> renderedDrops = new ArrayList<>(visible.size());
        for (GpDrop drop : visible)
        {
            String text = formatText(drop);
            int iconBlockWidth = iconBlockWidth(drop.icons.size(), iconSize, iconOverlap);
            int rowHeight = Math.max(metrics.getHeight(), iconBlockWidth > 0 ? iconSize : 0);
            int rowWidth = metrics.stringWidth(text)
                + (iconBlockWidth > 0 ? iconBlockWidth + iconGap : 0);
            renderedDrops.add(new RenderedDrop(drop, text, iconBlockWidth, rowWidth, rowHeight));
            contentWidth = Math.max(contentWidth, rowWidth);
            contentHeight += rowHeight;
        }
        contentHeight += Math.max(0, visible.size() - 1) * spacing;

        MotionInsets motionInsets = resolveMotionInsets(contentWidth, contentHeight, duration);
        int width = contentWidth + motionInsets.left + motionInsets.right;
        int height = contentHeight + motionInsets.top + motionInsets.bottom;

        int rowTop = motionInsets.top;
        for (RenderedDrop rendered : renderedDrops)
        {
            GpDrop drop = rendered.drop;
            AnimationFrame frame = resolveAnimationFrame(drop, now, duration);
            int alpha = multiplyAlpha(resolveExitAlpha(drop, now, duration), frame.opacity);
            int rowX = motionInsets.left + alignedRowX(contentWidth, rendered.width);
            int textWidth = metrics.stringWidth(rendered.text);
            int textX = rowX;
            int iconX = rowX;
            boolean iconsBeforeText = config.gpDropIconSide() != GpDropIconSide.RIGHT;

            if (rendered.iconBlockWidth > 0)
            {
                if (iconsBeforeText)
                {
                    textX += rendered.iconBlockWidth + iconGap;
                }
                else
                {
                    iconX += textWidth + iconGap;
                }
            }

            int baseline = rowTop + ((rendered.height - metrics.getHeight()) / 2) + metrics.getAscent();
            int centerX = rowX + (rendered.width / 2);
            int centerY = rowTop + (rendered.height / 2);

            if (rendered.iconBlockWidth > 0)
            {
                int iconY = rowTop + ((rendered.height - iconSize) / 2);
                if (config.gpDropAnimateIcons())
                {
                    Graphics2D iconGraphics = createAnimatedGraphics(graphics, centerX, centerY, frame);
                    drawIconStrip(iconGraphics, drop.icons, iconX, iconY, iconSize, iconOverlap, alpha);
                    iconGraphics.dispose();
                }
                else
                {
                    drawIconStrip(graphics, drop.icons, iconX, iconY, iconSize, iconOverlap, alpha);
                }
            }

            // Text stays integer-snapped without fractional scale so glyphs stay crisp;
            // icons may still use the full animated transform when enabled.
            Graphics2D textGraphics = createCrispTextGraphics(graphics, frame);
            GpDropFontHandler.apply(textGraphics, fontType);
            drawDropText(textGraphics, rendered.text, font, resolveBaseColor(drop), textX, baseline, alpha);
            textGraphics.dispose();

            rowTop += rendered.height + spacing;
        }

        return new Dimension(width, height);
    }

    private Graphics2D createAnimatedGraphics(
        Graphics2D graphics,
        int centerX,
        int centerY,
        AnimationFrame frame)
    {
        Graphics2D animated = (Graphics2D) graphics.create();
        animated.translate(frame.offsetX, frame.offsetY);
        if (Math.abs(frame.scale - 1.0d) > 0.0001d)
        {
            animated.translate(centerX, centerY);
            animated.scale(frame.scale, frame.scale);
            animated.translate(-centerX, -centerY);
        }
        return animated;
    }

    /** Integer device alignment for drop text — no fractional scale on bitmap glyphs. */
    private Graphics2D createCrispTextGraphics(Graphics2D graphics, AnimationFrame frame)
    {
        Graphics2D crisp = (Graphics2D) graphics.create();
        crisp.translate(Math.round(frame.offsetX), Math.round(frame.offsetY));
        return crisp;
    }

    private void drawDropText(
        Graphics2D graphics,
        String text,
        Font font,
        Color color,
        int x,
        int baseline,
        int alpha)
    {
        if (text == null || text.isEmpty())
        {
            return;
        }
        int fadeAlpha = clamp(alpha, 0, 255);
        int colorAlpha = color == null ? 255 : color.getAlpha();
        int effectiveAlpha = Math.min(fadeAlpha, colorAlpha);
        Color fill = ColorUtil.colorWithAlpha(color == null ? Color.WHITE : color, effectiveAlpha);
        Color shadow = ColorUtil.colorWithAlpha(Color.BLACK, effectiveAlpha);

        graphics.setFont(font);
        GpDropTextBackground background = config.gpDropTextBackground();
        if (background == null)
        {
            background = config.gpDropOutline() ? GpDropTextBackground.OUTLINE : GpDropTextBackground.SHADOW;
        }
        switch (background)
        {
            case OUTLINE:
                graphics.setColor(shadow);
                graphics.drawString(text, x, baseline + 1);
                graphics.drawString(text, x, baseline - 1);
                graphics.drawString(text, x + 1, baseline);
                graphics.drawString(text, x - 1, baseline);
                break;
            case SHADOW:
                graphics.setColor(shadow);
                graphics.drawString(text, x + 1, baseline + 1);
                break;
            case NONE:
            default:
                break;
        }
        graphics.setColor(fill);
        graphics.drawString(text, x, baseline);
    }

    private AnimationFrame resolveAnimationFrame(GpDrop drop, long now, long displayDuration)
    {
        double driftX = 0.0d;
        double driftY = 0.0d;
        if (!config.reducedMotion())
        {
            long age = Math.max(0L, now - drop.visibleAt);
            double seconds = age / 1000.0d;
            GpDropMotionDirection direction = config.gpDropMotionDirection() == null
                ? GpDropMotionDirection.UP
                : config.gpDropMotionDirection();
            int ySpeed = Math.max(0, config.gpDropPixelsPerSecondY());
            int xSpeed = Math.max(0, config.gpDropPixelsPerSecondX());
            // Continuous float: Y follows motion direction; X speed drifts left (CXPD default).
            switch (direction)
            {
                case DOWN:
                    driftY = ySpeed * seconds;
                    break;
                case LEFT:
                    driftX = -Math.max(xSpeed, ySpeed) * seconds;
                    break;
                case RIGHT:
                    driftX = Math.max(xSpeed, ySpeed) * seconds;
                    break;
                case UP:
                default:
                    driftY = -ySpeed * seconds;
                    driftX = -xSpeed * seconds;
                    break;
            }
        }

        if (config.reducedMotion())
        {
            return new AnimationFrame(driftX, driftY, 1.0d, 1.0d);
        }
        GpDropAnimationStyle style = config.gpDropAnimationStyle();
        if (style == null)
        {
            style = GpDropAnimationStyle.STATIC;
        }

        long age = Math.max(0L, now - drop.animationStartedAt);
        long entryDuration = Math.min(180L, Math.max(100L, config.gpDropAnimationMillis()));
        double entryProgress = GpDropAnimationMath.progress(age, entryDuration);
        GpDropEasing easing = config.gpDropEasing() == null
            ? GpDropEasing.EASE_OUT
            : config.gpDropEasing();
        double easedEntry = GpDropAnimationMath.ease(entryProgress, easing);
        int distance = effectiveMotionDistance();
        double offsetX = driftX;
        double offsetY = driftY;
        double scale = 1.0d;
        double opacity = config.gpDropFadeIn()
            ? GpDropAnimationMath.fadeIn(entryProgress)
            : 1.0d;

        GpDropMotionDirection direction = config.gpDropMotionDirection() == null
            ? GpDropMotionDirection.UP
            : config.gpDropMotionDirection();
        double remain = 1.0d - easedEntry;
        switch (style)
        {
            case STATIC:
            case FADE:
                break;
            case CLASSIC_RISE:
            case RISE_AND_FADE:
            case SLIDE_IN:
                switch (direction)
                {
                    case DOWN:
                        offsetY += distance * remain;
                        break;
                    case LEFT:
                        offsetX += -distance * remain;
                        break;
                    case RIGHT:
                        offsetX += distance * remain;
                        break;
                    case UP:
                    default:
                        offsetY += -distance * remain;
                        break;
                }
                break;
            case POP_IN:
                scale = GpDropAnimationMath.popScale(easedEntry);
                break;
            case GENTLE_FLOAT:
                offsetY += -Math.min(distance, 8) * remain;
                break;
            default:
                break;
        }
        return new AnimationFrame(offsetX, offsetY, scale, opacity);
    }

    private MotionInsets resolveMotionInsets(int contentWidth, int contentHeight, long displayDuration)
    {
        int yTravel = (int) Math.ceil(Math.max(0, config.gpDropPixelsPerSecondY()) * (displayDuration / 1000.0d));
        int xTravel = (int) Math.ceil(Math.max(0, config.gpDropPixelsPerSecondX()) * (displayDuration / 1000.0d));
        int entry = config.reducedMotion() ? 0 : effectiveMotionDistance();
        int top = Math.max(yTravel, entry);
        int bottom = Math.max(yTravel / 4, entry / 4);
        int left = Math.max(xTravel, entry / 2);
        int right = Math.max(xTravel, entry / 2);

        GpDropAnimationStyle style = config.gpDropAnimationStyle();
        if (style == GpDropAnimationStyle.POP_IN)
        {
            int horizontal = Math.max(2, (int) Math.ceil(contentWidth * 0.05d));
            int vertical = Math.max(2, (int) Math.ceil(contentHeight * 0.05d));
            left = Math.max(left, horizontal);
            right = Math.max(right, horizontal);
            top = Math.max(top, vertical);
            bottom = Math.max(bottom, vertical);
        }
        if (top == 0 && bottom == 0 && left == 0 && right == 0)
        {
            return MotionInsets.NONE;
        }
        return new MotionInsets(left, right, top, bottom);
    }

    private double directionX(double distance)
    {
        GpDropMotionDirection direction = effectiveDirection();
        if (direction == GpDropMotionDirection.LEFT)
        {
            return -distance;
        }
        if (direction == GpDropMotionDirection.RIGHT)
        {
            return distance;
        }
        return 0.0d;
    }

    private double directionY(double distance)
    {
        GpDropMotionDirection direction = effectiveDirection();
        if (direction == GpDropMotionDirection.UP)
        {
            return -distance;
        }
        if (direction == GpDropMotionDirection.DOWN)
        {
            return distance;
        }
        return 0.0d;
    }

    private GpDropMotionDirection effectiveDirection()
    {
        GpDropMotionDirection direction = config.gpDropMotionDirection();
        return direction == null ? GpDropMotionDirection.UP : direction;
    }

    private static int multiplyAlpha(int alpha, double multiplier)
    {
        return clamp((int) Math.round(alpha * Math.max(0.0d, Math.min(1.0d, multiplier))), 0, 255);
    }

    private int alignedRowX(int availableWidth, int rowWidth)
    {
        GpDropAlignment alignment = config.gpDropAlignment();
        if (alignment == GpDropAlignment.LEFT)
        {
            return 0;
        }
        if (alignment == GpDropAlignment.CENTER)
        {
            return Math.max(0, (availableWidth - rowWidth) / 2);
        }
        return Math.max(0, availableWidth - rowWidth);
    }

    private String formatText(GpDrop drop)
    {
        StringBuilder text = new StringBuilder();
        if (drop.itemSpecific)
        {
            GpDropItemTextMode mode = effectiveItemTextMode();
            switch (mode)
            {
                case ITEM_NAME:
                    appendName(text, drop.itemName);
                    break;
                case NAME_AND_QUANTITY:
                    appendName(text, drop.itemName);
                    appendQuantity(text, drop.quantity);
                    break;
                case QUANTITY:
                    appendQuantity(text, drop.quantity);
                    break;
                case VALUE_ONLY:
                default:
                    break;
            }
        }
        else if (drop.summaryLabel != null && !drop.summaryLabel.isEmpty())
        {
            text.append(drop.summaryLabel).append(' ');
        }

        text.append(formatAmount(drop.amount));
        return text.toString();
    }

    private void appendName(StringBuilder text, String itemName)
    {
        String safeName = itemName == null || itemName.trim().isEmpty() ? "Item" : itemName.trim();
        int maximum = clamp(config.gpDropMaxNameLength(), 8, 28);
        if (safeName.length() > maximum)
        {
            safeName = safeName.substring(0, Math.max(1, maximum - 3)) + "...";
        }
        text.append(safeName).append(' ');
    }

    private static void appendQuantity(StringBuilder text, long quantity)
    {
        text.append('\u00d7').append(formatQuantity(quantity)).append(' ');
    }

    private int resolveExitAlpha(GpDrop drop, long now, long duration)
    {
        if (!config.gpDropFade())
        {
            return 255;
        }
        long age = Math.max(0L, now - drop.visibleAt);
        // Customizable XP Drops: fade over the last ~34% of lifetime.
        long fadeStart = (long) (duration * 0.66d);
        if (age <= fadeStart)
        {
            return 255;
        }
        long fadeSpan = Math.max(1L, duration - fadeStart);
        double fade = Math.max(0.0d, Math.min(1.0d, (age - fadeStart) / (double) fadeSpan));
        return (int) Math.round(255.0d * (1.0d - fade));
    }

    private int effectiveMotionDistance()
    {
        return clamp(config.gpDropMotionDistance(), 0, 160);
    }

    private String formatAmount(long amount)
    {
        if (effectivePreset() == GpDropPresentationPreset.COIN_TOTAL)
        {
            return formatClassicProfitTrackerAmount(amount);
        }
        String value = config.gpDropCompactNumbers()
            ? QuantityFormatter.compactGp(safeAbs(amount))
            : INTEGER_FORMAT.format(safeAbs(amount));
        return (amount > 0L ? "+" : "-")
            + value
            + (config.gpDropShowSuffix() ? " gp" : "");
    }

    static String formatClassicProfitTrackerAmount(long amount)
    {
        long absolute = safeAbs(amount);
        if (absolute < 10_000L)
        {
            return Long.toString(amount);
        }
        if (absolute < 1_000_000L)
        {
            return (amount / 1_000L) + "K";
        }
        if (absolute < 1_000_000_000L)
        {
            return formatClassicMajorAmount(amount, 1_000_000L, "M");
        }
        return formatClassicMajorAmount(amount, 1_000_000_000L, "B");
    }

    private static String formatClassicMajorAmount(long amount, long divisor, String suffix)
    {
        long absolute = safeAbs(amount);
        long whole = absolute / divisor;
        long tenths = (absolute % divisor) / (divisor / 10L);
        String sign = amount < 0L ? "-" : "";
        return whole >= 100L || tenths == 0L
            ? sign + whole + suffix
            : sign + whole + "." + tenths + suffix;
    }

    private void drawIconStrip(
        Graphics2D graphics,
        List<IconToken> icons,
        int x,
        int y,
        int iconSize,
        int overlap,
        int alpha)
    {
        int advance = Math.max(2, iconSize - overlap);
        int iconX = x;
        for (IconToken token : icons)
        {
            drawIcon(graphics, token, iconX, y, iconSize, alpha);
            iconX += advance;
        }
    }

    private void drawIcon(
        Graphics2D graphics,
        IconToken token,
        int x,
        int y,
        int iconSize,
        int alpha)
    {
        AsyncBufferedImage image = token.itemId == COINS_ITEM_ID
            ? itemManager.getImage(COINS_ITEM_ID, 10_000, false)
            : itemManager.getImage(token.itemId);
        if (image == null)
        {
            return;
        }

        Composite original = graphics.getComposite();
        if (alpha < 255)
        {
            graphics.setComposite(AlphaComposite.getInstance(
                AlphaComposite.SRC_OVER,
                Math.max(0.0f, Math.min(1.0f, alpha / 255.0f))));
        }
        HudPlusLayout.drawSprite(graphics, image, x, y, iconSize);

        if (effectiveQuantityOnIcon() && token.quantity > 1L && token.itemId != COINS_ITEM_ID)
        {
            drawIconQuantity(graphics, token.quantity, x, y, iconSize, alpha);
        }
        graphics.setComposite(original);
    }

    private void drawIconQuantity(
        Graphics2D graphics,
        long quantity,
        int x,
        int y,
        int iconSize,
        int alpha)
    {
        Font font = FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, (float) clamp(iconSize - 7, 9, 12));
        FontMetrics metrics = graphics.getFontMetrics(font);
        String value = formatQuantity(quantity);
        int baseline = y + iconSize - 1;
        int quantityX = x + iconSize - metrics.stringWidth(value);

        TextComponent quantityText = new TextComponent();
        quantityText.setText(value);
        quantityText.setFont(font);
        quantityText.setOutline(true);
        quantityText.setColor(Color.WHITE);
        quantityText.setPosition(quantityX, baseline);
        quantityText.render(graphics);
    }

    private static int iconBlockWidth(int count, int iconSize, int overlap)
    {
        if (count <= 0)
        {
            return 0;
        }
        return iconSize + (count - 1) * Math.max(2, iconSize - overlap);
    }

    private Color resolveBaseColor(GpDrop drop)
    {
        Color base = drop.uncertain
            ? config.gpDropUncertainColor()
            : drop.amount > 0L ? config.gpDropProfitColor() : config.gpDropLossColor();
        return base == null ? Color.WHITE : base;
    }

    private void trimToConfiguredMaximum()
    {
        int maximum = clamp(config.gpDropMaxVisible(), 1, 10);
        while (drops.size() > maximum)
        {
            drops.removeFirst();
        }
    }

    private static String formatQuantity(long quantity)
    {
        long safeQuantity = safeAbs(quantity);
        return safeQuantity >= 10_000L
            ? QuantityFormatter.compactGp(safeQuantity)
            : INTEGER_FORMAT.format(safeQuantity);
    }

    private static Color withAlpha(Color color, int alpha)
    {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clamp(alpha, 0, 255));
    }

    private static int clamp(int value, int minimum, int maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long safeAdd(long left, long right)
    {
        try
        {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException ex)
        {
            return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private static long safeAbs(long value)
    {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private static final class AnimationFrame
    {
        private final double offsetX;
        private final double offsetY;
        private final double scale;
        private final double opacity;

        private AnimationFrame(double offsetX, double offsetY, double scale, double opacity)
        {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.scale = scale;
            this.opacity = opacity;
        }
    }

    private static final class MotionInsets
    {
        private static final MotionInsets NONE = new MotionInsets(0, 0, 0, 0);

        private final int left;
        private final int right;
        private final int top;
        private final int bottom;

        private MotionInsets(int left, int right, int top, int bottom)
        {
            this.left = Math.max(0, left);
            this.right = Math.max(0, right);
            this.top = Math.max(0, top);
            this.bottom = Math.max(0, bottom);
        }
    }

    private static final class DropCandidate
    {
        private final long amount;
        private final boolean uncertain;
        private final boolean itemSpecific;
        private final int primaryItemId;
        private final long quantity;
        private final String itemName;
        private final String summaryLabel;
        private final List<IconToken> icons;

        private DropCandidate(
            long amount,
            boolean uncertain,
            boolean itemSpecific,
            int primaryItemId,
            long quantity,
            String itemName,
            String summaryLabel,
            List<IconToken> icons)
        {
            this.amount = amount;
            this.uncertain = uncertain;
            this.itemSpecific = itemSpecific;
            this.primaryItemId = primaryItemId;
            this.quantity = quantity;
            this.itemName = itemName == null ? "" : itemName;
            this.summaryLabel = summaryLabel == null ? "" : summaryLabel;
            this.icons = icons == null ? Collections.emptyList() : new ArrayList<>(icons);
        }

        private static DropCandidate item(
            long amount,
            boolean uncertain,
            int itemId,
            long quantity,
            String itemName)
        {
            List<IconToken> icons = Collections.singletonList(
                new IconToken(itemId, quantity, safeAbs(amount)));
            return new DropCandidate(
                amount,
                uncertain,
                true,
                itemId,
                quantity,
                itemName,
                "",
                icons);
        }

        private static DropCandidate summary(
            long amount,
            boolean uncertain,
            List<IconToken> icons,
            String summaryLabel)
        {
            int primaryItemId = icons == null || icons.isEmpty() ? -1 : icons.get(0).itemId;
            return new DropCandidate(
                amount,
                uncertain,
                false,
                primaryItemId,
                0L,
                "",
                summaryLabel,
                icons);
        }
    }

    private static final class IconToken
    {
        private final int itemId;
        private long quantity;
        private long weight;

        private IconToken(int itemId, long quantity, long weight)
        {
            this.itemId = itemId;
            this.quantity = quantity;
            this.weight = weight;
        }
    }

    private static final class RenderedDrop
    {
        private final GpDrop drop;
        private final String text;
        private final int iconBlockWidth;
        private final int width;
        private final int height;

        private RenderedDrop(GpDrop drop, String text, int iconBlockWidth, int width, int height)
        {
            this.drop = drop;
            this.text = text;
            this.iconBlockWidth = iconBlockWidth;
            this.width = width;
            this.height = height;
        }
    }

    private static final class GpDrop
    {
        private long amount;
        private final boolean uncertain;
        private final boolean itemSpecific;
        private final int primaryItemId;
        private long quantity;
        private final String itemName;
        private final String summaryLabel;
        private List<IconToken> icons;
        private final String combineKey;
        private long updatedAt;
        private long animationStartedAt;
        private long visibleAt;

        private GpDrop(
            long amount,
            boolean uncertain,
            boolean itemSpecific,
            int primaryItemId,
            long quantity,
            String itemName,
            String summaryLabel,
            List<IconToken> icons,
            String combineKey,
            long updatedAt,
            long visibleAt)
        {
            this.amount = amount;
            this.uncertain = uncertain;
            this.itemSpecific = itemSpecific;
            this.primaryItemId = primaryItemId;
            this.quantity = quantity;
            this.itemName = itemName == null ? "" : itemName;
            this.summaryLabel = summaryLabel == null ? "" : summaryLabel;
            this.icons = icons == null ? new ArrayList<>() : new ArrayList<>(icons);
            this.combineKey = combineKey;
            this.updatedAt = updatedAt;
            this.animationStartedAt = updatedAt;
            this.visibleAt = visibleAt;
        }
    }
}
