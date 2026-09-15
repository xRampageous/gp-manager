package com.gpmanager.grounditems;

import com.gpmanager.LootPresentationFilter;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardObservation;
import com.gpmanager.reward.RewardSourceKind;
import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

/**
 * Resolves item-visibility decisions from Ground Items rules for deliberately
 * distinct presentation and accounting callers:
 * <ul>
 *   <li>Presentation ({@link #filterReward}, {@link #isRewardItemIncluded},
 *       {@link #isFlowVisible}, {@link #highlightColorFor}) — what item rows
 *       show. The display filter hides gains only and never changes accounting.</li>
 *   <li>Accounting eligibility ({@link #isFlowIncluded}) — whether a flow
 *       counts toward revenue/costs/net under the advanced
 *       {@code accountingItemFilter} setting. See {@link ContributionEligibility}
 *       for the typed wrapper engine call sites use for this half of the
 *       contract, and {@code docs/SETTINGS_MAPPING.md} for the full
 *       accounting-vs-presentation settings mapping.</li>
 * </ul>
 * Stored observations and transactions remain untouched either way — only
 * derived presentation/metrics projections are filtered — for recovery/audit.
 */
@Singleton
public class LootPresentationFilterService
{
    private final GroundItemsConfigReader configReader;
    @Nullable
    private final ItemManager itemManager;

    private volatile GroundItemsConfigSnapshot cachedSnapshot;
    private volatile String cachedFingerprint = "";
    private volatile GroundItemsRuleEngine cachedEngine;

    @Inject
    public LootPresentationFilterService(
        GroundItemsConfigReader configReader,
        @Nullable ItemManager itemManager)
    {
        this.configReader = configReader;
        this.itemManager = itemManager;
    }

    /** Test helper with a fixed snapshot (no live ConfigManager). */
    public LootPresentationFilterService(GroundItemsConfigSnapshot snapshot)
    {
        this.configReader = null;
        this.itemManager = null;
        this.cachedSnapshot = snapshot == null ? GroundItemsConfigSnapshot.disabled() : snapshot;
        this.cachedFingerprint = this.cachedSnapshot.fingerprint();
        this.cachedEngine = new GroundItemsRuleEngine(this.cachedSnapshot);
    }

    /**
     * Reload Ground Items settings. Safe to call on ConfigChanged / ProfileChanged.
     * Does not touch reward observations or accounting.
     */
    public synchronized void refresh()
    {
        if (configReader == null)
        {
            return;
        }
        GroundItemsConfigSnapshot next = configReader.read();
        cachedSnapshot = next;
        cachedFingerprint = next.fingerprint();
        IntFunction<Color> colors = itemId -> configReader.readItemColor(itemId);
        cachedEngine = new GroundItemsRuleEngine(next, colors);
    }

    public GroundItemsConfigSnapshot currentSnapshot()
    {
        ensureEngine();
        return cachedSnapshot;
    }

    public FilteredRewardView filterReward(
        @Nullable RewardObservation observation,
        LootPresentationFilter mode,
        boolean reuseHighlightColors)
    {
        return filterReward(observation, mode, reuseHighlightColors, 0L);
    }

    public FilteredRewardView filterReward(
        @Nullable RewardObservation observation,
        LootPresentationFilter mode,
        boolean reuseHighlightColors,
        long minimumValue)
    {
        LootPresentationFilter effective = mode == null ? LootPresentationFilter.ALL_ITEMS : mode;
        if (observation == null)
        {
            return FilteredRewardView.empty(null, effective);
        }
        List<RewardItem> candidates = observation.presentationStacks();
        // Color reuse is independent of visibility filtering. Keep the fast
        // path only when neither filtering nor Ground Items colors are needed.
        if (effective == LootPresentationFilter.ALL_ITEMS && !reuseHighlightColors && minimumValue <= 0L)
        {
            return FilteredRewardView.of(
                observation, candidates, candidates, java.util.Collections.emptyMap(), effective);
        }

        GroundItemsRuleEngine engine = ensureEngine();
        GroundItemsConfigSnapshot snapshot = currentSnapshot();
        if (!snapshot.isPluginEnabled() && minimumValue <= 0L)
        {
            // Ground Items disabled → pass through; presentation stays complete.
            return FilteredRewardView.of(
                observation, candidates, candidates, java.util.Collections.emptyMap(), effective);
        }

        List<RewardItem> visible = new ArrayList<>();
        Map<Integer, Color> colors = new LinkedHashMap<>();
        for (RewardItem item : candidates)
        {
            if (item == null)
            {
                continue;
            }
            // Minimum display value hides cheap *unpicked* ground gains by unit price.
            // Intentional pickups (Received) always stay visible — Net already counted them.
            if (minimumValue > 0L && item.isValueKnown() && !item.isLoss()
                && !isIntentionalPickup(observation, item))
            {
                long qty = Math.max(1L, Math.abs(item.getQuantity()));
                long unit = Math.abs(item.getRecordedValue()) / qty;
                if (unit > 0L && unit < minimumValue)
                {
                    continue;
                }
            }
            // Display loot filters never hide costs. Explicit accounting
            // exclusions are a separate decision applied by the caller.
            if (item.isLoss())
            {
                visible.add(item);
                continue;
            }
            GroundItemsItemDecision decision = decide(engine, item);
            boolean keep;
            if (!snapshot.isPluginEnabled() || effective == LootPresentationFilter.ALL_ITEMS)
            {
                keep = true;
            }
            else if (isIntentionalPickup(observation, item))
            {
                // Player chose to pick it up — show as Received even if Ground Items
                // would hide the stack while it was still on the ground.
                keep = true;
            }
            else if (effective == LootPresentationFilter.HIGHLIGHTED_LIST_ONLY)
            {
                keep = decision.isOnHighlightedList();
            }
            else
            {
                keep = decision.isVisibleUnderFollow();
            }
            if (!keep)
            {
                continue;
            }
            visible.add(item);
            if (snapshot.isPluginEnabled() && reuseHighlightColors && decision.getHighlightColor() != null)
            {
                colors.put(item.getItemId(), decision.getHighlightColor());
            }
        }
        return FilteredRewardView.of(observation, candidates, visible, colors, effective);
    }

    /**
     * Whether an item contribution is included under the active accounting
     * filter. Gains and costs use the same rule decision.
     */
    public boolean isFlowIncluded(ItemFlow flow, LootPresentationFilter mode)
    {
        LootPresentationFilter effective = mode == null ? LootPresentationFilter.ALL_ITEMS : mode;
        if (effective == LootPresentationFilter.ALL_ITEMS || flow == null)
        {
            return true;
        }
        GroundItemsConfigSnapshot snapshot = currentSnapshot();
        if (!snapshot.isPluginEnabled())
        {
            return true;
        }
        GroundItemsItemDecision decision = decideFlow(ensureEngine(), flow);
        return effective == LootPresentationFilter.HIGHLIGHTED_LIST_ONLY
            ? decision.isOnHighlightedList()
            : decision.isVisibleUnderFollow();
    }

    /**
     * Presentation visibility for a capsule/tray {@link RewardItem} under the
     * active Ground Items mode. Gain rows follow the hide list; cost rows stay
     * visible because the display loot filter is gain-side only.
     */
    public boolean isRewardItemIncluded(RewardItem item, LootPresentationFilter mode)
    {
        LootPresentationFilter effective = mode == null ? LootPresentationFilter.ALL_ITEMS : mode;
        if (item == null || item.isLoss() || effective == LootPresentationFilter.ALL_ITEMS)
        {
            return true;
        }
        GroundItemsConfigSnapshot snapshot = currentSnapshot();
        if (!snapshot.isPluginEnabled())
        {
            return true;
        }
        GroundItemsItemDecision decision = decide(ensureEngine(), item);
        return effective == LootPresentationFilter.HIGHLIGHTED_LIST_ONLY
            ? decision.isOnHighlightedList()
            : decision.isVisibleUnderFollow();
    }

    /**
     * Visibility fallback for name-only historical aggregates. Unconditional
     * Ground Items list entries are applied; Follow mode keeps rows whose
     * prices or quantities were not retained and therefore cannot be judged.
     */
    public boolean isItemNameIncluded(String itemName, LootPresentationFilter mode)
    {
        LootPresentationFilter effective = mode == null ? LootPresentationFilter.ALL_ITEMS : mode;
        if (effective == LootPresentationFilter.ALL_ITEMS || !currentSnapshot().isPluginEnabled())
        {
            return true;
        }
        return ensureEngine().includesNameWhenPricesUnknown(itemName, effective);
    }

    /**
     * Loot visibility does not suppress costs or alter accounting eligibility.
     */
    public boolean isFlowVisible(
        ItemFlow flow,
        LootPresentationFilter mode,
        boolean reuseHighlightColorsIgnored)
    {
        return isFlowVisible(flow, mode, reuseHighlightColorsIgnored, 0L);
    }

    public boolean isFlowVisible(ItemFlow flow, LootPresentationFilter mode,
        boolean reuseHighlightColorsIgnored, long minimumValue)
    {
        if (flow == null) return false;
        // The display loot filter only hides gains. Costs stay discoverable;
        // callers may additionally apply isFlowIncluded when explicit
        // accountingItemFilter exclusions should hide a contribution.
        if (flow.getQuantityDelta() <= 0L) return true;
        // Unknown / zero unit prices remain discoverable for review.
        // Compare unit price, not stack total, so quantity cannot defeat the minimum.
        long min = Math.max(0L, minimumValue);
        return (flow.getUnitPrice() <= 0 || flow.getUnitPrice() >= min)
            && isFlowIncluded(flow, mode);
    }

    @Nullable
    public Color highlightColorFor(ItemFlow flow, boolean reuseHighlightColors)
    {
        if (!reuseHighlightColors || flow == null || flow.getQuantityDelta() <= 0L)
        {
            return null;
        }
        if (!currentSnapshot().isPluginEnabled())
        {
            return null;
        }
        return decideFlow(ensureEngine(), flow).getHighlightColor();
    }

    private GroundItemsItemDecision decide(GroundItemsRuleEngine engine, RewardItem item)
    {
        int qty = clampQty(item.getQuantity());
        int ge = resolveGeStack(item.getItemId(), qty, item);
        int ha = resolveHaStack(item.getItemId(), qty);
        boolean tradeable = resolveTradeable(item.getItemId());
        return engine.evaluate(item.getItemName(), qty, item.getItemId(), ge, ha, tradeable);
    }

    private GroundItemsItemDecision decideFlow(GroundItemsRuleEngine engine, ItemFlow flow)
    {
        long absQty = Math.abs(flow.getQuantityDelta());
        int qty = clampQty(absQty);
        int ge = safeMul(Math.max(0, flow.getUnitPrice()), qty);
        if (ge <= 0 && itemManager != null)
        {
            ge = resolveGeStack(flow.getItemId(), qty, null);
        }
        int ha = resolveHaStack(flow.getItemId(), qty);
        boolean tradeable = resolveTradeable(flow.getItemId());
        return engine.evaluate(flow.getItemName(), qty, flow.getItemId(), ge, ha, tradeable);
    }

    private synchronized GroundItemsRuleEngine ensureEngine()
    {
        if (cachedEngine == null)
        {
            if (configReader != null)
            {
                refresh();
            }
            else if (cachedSnapshot == null)
            {
                cachedSnapshot = GroundItemsConfigSnapshot.disabled();
                cachedFingerprint = cachedSnapshot.fingerprint();
                cachedEngine = new GroundItemsRuleEngine(cachedSnapshot);
            }
        }
        return cachedEngine;
    }

    private int resolveGeStack(int itemId, int qty, @Nullable RewardItem item)
    {
        // Ground Items visibility uses live GE prices, never recorded accounting values.
        if (itemManager == null || itemId <= 0)
        {
            return 0;
        }
        try
        {
            int unit = itemManager.getItemPrice(unnote(itemId));
            return safeMul(Math.max(0, unit), qty);
        }
        catch (RuntimeException ex)
        {
            return 0;
        }
    }

    private int resolveHaStack(int itemId, int qty)
    {
        if (itemManager == null || itemId <= 0)
        {
            return 0;
        }
        try
        {
            ItemComposition composition = itemManager.getItemComposition(unnote(itemId));
            if (composition == null)
            {
                return 0;
            }
            return safeMul(Math.max(0, composition.getHaPrice()), qty);
        }
        catch (RuntimeException ex)
        {
            return 0;
        }
    }

    private boolean resolveTradeable(int itemId)
    {
        if (itemManager == null || itemId <= 0)
        {
            // Unknown composition: allow hide-under-value (tradeable=true is safer
            // for "canBeHidden" when dontHideUntradeables is on only for true untradeables).
            return true;
        }
        try
        {
            ItemComposition composition = itemManager.getItemComposition(unnote(itemId));
            return composition == null || composition.isTradeable();
        }
        catch (RuntimeException ex)
        {
            return true;
        }
    }

    private int unnote(int itemId)
    {
        if (itemManager == null)
        {
            return itemId;
        }
        try
        {
            ItemComposition composition = itemManager.getItemComposition(itemId);
            if (composition != null && composition.getNote() != -1)
            {
                return composition.getLinkedNoteId();
            }
        }
        catch (RuntimeException ignored)
        {
            // fall through
        }
        return itemId;
    }

    private static int clampQty(long quantity)
    {
        if (quantity <= 0L)
        {
            return 0;
        }
        if (quantity > Integer.MAX_VALUE)
        {
            return Integer.MAX_VALUE;
        }
        return (int) quantity;
    }

    private static int safeMul(int unit, int qty)
    {
        if (unit <= 0 || qty <= 0)
        {
            return 0;
        }
        long product = (long) unit * (long) qty;
        return product > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) product;
    }

    /**
     * Inventory receipts and confirmed collect quantities are intentional pickups.
     * Ground Items hide rules apply to unconfirmed ground stacks only.
     */
    static boolean isIntentionalPickup(@Nullable RewardObservation observation, @Nullable RewardItem item)
    {
        if (observation == null || item == null)
        {
            return false;
        }
        RewardSourceKind kind = observation.getSourceKind();
        if (kind == RewardSourceKind.RECENT_PICKUPS || kind == RewardSourceKind.INVENTORY_CONFIRMED)
        {
            return true;
        }
        return observation.confirmedQuantity(item.getItemId()) > 0L;
    }

    /** Test seam: replace live snapshot without ConfigManager. */
    public synchronized void replaceSnapshotForTests(GroundItemsConfigSnapshot snapshot)
    {
        cachedSnapshot = snapshot == null ? GroundItemsConfigSnapshot.disabled() : snapshot;
        cachedFingerprint = cachedSnapshot.fingerprint();
        cachedEngine = new GroundItemsRuleEngine(cachedSnapshot);
    }

    public String configFingerprint()
    {
        ensureEngine();
        return cachedFingerprint;
    }
}
