package com.gpmanager.grounditems;

import com.gpmanager.LootPresentationFilter;
import java.awt.Color;
import java.util.function.IntFunction;
import javax.annotation.Nullable;

/**
 * Reimplements Ground Items highlight/hide precedence for loot presentation.
 *
 * <p>Precedence (exact name &gt; exact hide &gt; wildcard highlight &gt; wildcard hide),
 * quantity thresholds, hide-under-value, {@code dontHideUntradeables}, value-tier
 * colours, and {@code showHighlightedOnly} match public Ground Items behaviour.
 * See {@code docs/GROUND_ITEMS_FILTER.md} for contextual rules that cannot be
 * reproduced without a live ground-item instance.</p>
 */
public final class GroundItemsRuleEngine
{
    private static final int NONE = 0;
    private static final int HIGHLIGHTED = 1;
    private static final int HIDDEN = 2;

    private final GroundItemsConfigSnapshot config;
    private final GroundItemsItemList highlighted;
    private final GroundItemsItemList hidden;
    @Nullable
    private final IntFunction<Color> itemColorLookup;

    public GroundItemsRuleEngine(GroundItemsConfigSnapshot config)
    {
        this(config, null);
    }

    public GroundItemsRuleEngine(
        GroundItemsConfigSnapshot config,
        @Nullable IntFunction<Color> itemColorLookup)
    {
        this.config = config == null ? GroundItemsConfigSnapshot.disabled() : config;
        this.highlighted = new GroundItemsItemList(this.config.getHighlightedCsv());
        this.hidden = new GroundItemsItemList(this.config.getHiddenCsv());
        this.itemColorLookup = itemColorLookup;
    }

    public GroundItemsConfigSnapshot getConfig()
    {
        return config;
    }

    public GroundItemsItemDecision evaluate(
        String itemName,
        int quantity,
        int itemId,
        int geStackPrice,
        int haStackPrice,
        boolean tradeable)
    {
        int listState = isHiddenOrHighlighted(itemName, quantity);
        boolean onHighlightedList = listState == HIGHLIGHTED;
        boolean explicitlyHidden = listState == HIDDEN;

        Color itemColor = itemId > 0 && itemColorLookup != null
            ? itemColorLookup.apply(itemId)
            : null;

        Color highlightedColor = resolveHighlightedColor(
            listState, itemColor, geStackPrice, haStackPrice);
        boolean valueTierHighlighted = listState == NONE
            && itemColor == null
            && highlightedColor != null;

        boolean hiddenByRules = isHidden(listState, geStackPrice, haStackPrice, tradeable);

        // Overlay visibility when hotkey is not pressed:
        // hidden → skip; showHighlightedOnly → require highlighted.
        boolean visibleUnderFollow = !hiddenByRules
            && (!config.isShowHighlightedOnly() || highlightedColor != null);

        return new GroundItemsItemDecision(
            visibleUnderFollow,
            onHighlightedList,
            hiddenByRules || explicitlyHidden,
            valueTierHighlighted,
            onHighlightedList
                ? GroundItemsItemDecision.ListMatch.HIGHLIGHTED
                : explicitlyHidden
                    ? GroundItemsItemDecision.ListMatch.HIDDEN
                    : GroundItemsItemDecision.ListMatch.NONE,
            highlightedColor);
    }

    /**
     * Conservative fallback for legacy analytics that retained only item
     * names. It honors unconditional list entries; unknown values stay visible
     * in Follow mode because price-tier and quantity rules cannot be evaluated.
     */
    public boolean includesNameWhenPricesUnknown(String itemName, LootPresentationFilter mode)
    {
        LootPresentationFilter effective = mode == null ? LootPresentationFilter.ALL_ITEMS : mode;
        if (effective == LootPresentationFilter.ALL_ITEMS || !config.isPluginEnabled())
        {
            return true;
        }

        int listState = explicitUnconditionalNameMatch(itemName);
        if (listState == HIDDEN)
        {
            return false;
        }
        if (listState == HIGHLIGHTED)
        {
            return true;
        }
        // Legacy name-only rows cannot be assigned a current price or stack
        // quantity. Preserve them in Follow mode, but a list-only view needs
        // an explicit highlighted-list match.
        return effective != LootPresentationFilter.HIGHLIGHTED_LIST_ONLY;
    }

    private int explicitUnconditionalNameMatch(String itemName)
    {
        int hl = highlighted.matchesUnconditionally(itemName);
        if (hl == GroundItemsItemList.EXACT) return HIGHLIGHTED;
        int hi = hidden.matchesUnconditionally(itemName);
        if (hi == GroundItemsItemList.EXACT) return HIDDEN;
        if (hl == GroundItemsItemList.WILDCARD) return HIGHLIGHTED;
        if (hi == GroundItemsItemList.WILDCARD) return HIDDEN;
        return NONE;
    }

    private int isHiddenOrHighlighted(String itemName, int quantity)
    {
        int hl = highlighted.matches(itemName, quantity);
        if (hl == GroundItemsItemList.EXACT)
        {
            return HIGHLIGHTED;
        }

        int hi = hidden.matches(itemName, quantity);
        if (hi == GroundItemsItemList.EXACT)
        {
            return HIDDEN;
        }

        if (hl == GroundItemsItemList.WILDCARD)
        {
            return HIGHLIGHTED;
        }
        if (hi == GroundItemsItemList.WILDCARD)
        {
            return HIDDEN;
        }
        return NONE;
    }

    private boolean isHidden(int listState, int geStackPrice, int haStackPrice, boolean tradeable)
    {
        boolean canBeHidden = geStackPrice > 0 || tradeable || !config.isDontHideUntradeables();
        boolean underGe = geStackPrice < config.getHideUnderValue();
        boolean underHa = haStackPrice < config.getHideUnderValue();
        // Explicit highlight takes priority over implicit hide.
        return listState == HIDDEN
            || (listState != HIGHLIGHTED && canBeHidden && underGe && underHa);
    }

    @Nullable
    private Color resolveHighlightedColor(
        int listState,
        @Nullable Color itemColor,
        int geStackPrice,
        int haStackPrice)
    {
        if (listState == HIGHLIGHTED)
        {
            return itemColor != null ? itemColor : config.getHighlightedColor();
        }
        // Explicit hide takes priority over implicit highlight.
        if (listState == HIDDEN)
        {
            return null;
        }
        if (itemColor != null)
        {
            return itemColor;
        }
        int price = valueByMode(geStackPrice, haStackPrice);
        for (GroundItemsConfigSnapshot.PriceTier tier : config.getPriceTiers())
        {
            if (price > tier.getPrice())
            {
                return tier.getColor();
            }
        }
        return null;
    }

    private int valueByMode(int geStackPrice, int haStackPrice)
    {
        switch (config.getValueMode())
        {
            case GE:
                return geStackPrice;
            case HA:
                return haStackPrice;
            case HIGHEST:
            default:
                return Math.max(geStackPrice, haStackPrice);
        }
    }
}
