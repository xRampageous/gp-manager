package com.gpmanager.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.NumberFormat;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import net.runelite.api.SkullIcon;
import net.runelite.api.widgets.Widget;
import com.gpmanager.model.ItemFlow;

/**
 * Immutable, presentation-only evidence read at a local death. It can explain a settled
 * death row, but it is never consulted for transaction classification or valuation.
 */
public final class LocalDeathEvidence
{
    private final boolean deathKeepWidgetAvailable;
    private final List<KeptItem> keptItems;
    private final boolean protectItemOn;
    private final int skullIcon;

    private LocalDeathEvidence(
        boolean deathKeepWidgetAvailable,
        List<KeptItem> keptItems,
        boolean protectItemOn,
        int skullIcon)
    {
        this.deathKeepWidgetAvailable = deathKeepWidgetAvailable;
        this.keptItems = Collections.unmodifiableList(new ArrayList<>(keptItems));
        this.protectItemOn = protectItemOn;
        this.skullIcon = skullIcon;
    }

    /**
     * Snapshot item ids from all widget child collections once, deduplicating canonical
     * items while preserving their first display order.
     */
    public static LocalDeathEvidence capture(
        Widget deathKeepWidget,
        boolean protectItemOn,
        int skullIcon,
        IntUnaryOperator canonicalizer,
        IntFunction<String> itemName)
    {
        Map<Integer, KeptItem> kept = new LinkedHashMap<>();
        Set<Widget> visited = Collections.newSetFromMap(new IdentityHashMap<Widget, Boolean>());
        ArrayDeque<Widget> pending = new ArrayDeque<>();
        if (deathKeepWidget != null)
        {
            pending.add(deathKeepWidget);
        }

        while (!pending.isEmpty())
        {
            Widget widget = pending.removeFirst();
            if (widget == null || !visited.add(widget))
            {
                continue;
            }
            if (!widget.isHidden())
            {
                int id = widget.getItemId();
                if (id > 0)
                {
                    int canonicalId = canonicalizer == null ? id : canonicalizer.applyAsInt(id);
                    if (canonicalId > 0)
                    {
                        long quantity = Math.max(1, widget.getItemQuantity());
                        KeptItem prior = kept.get(canonicalId);
                        long mergedQuantity = prior == null ? quantity : saturatedAdd(prior.quantity, quantity);
                        String name = prior == null ? resolveName(canonicalId, itemName) : prior.name;
                        kept.put(canonicalId, new KeptItem(name, mergedQuantity));
                    }
                }
            }
            addChildren(pending, widget.getChildren());
            addChildren(pending, widget.getDynamicChildren());
            addChildren(pending, widget.getStaticChildren());
            addChildren(pending, widget.getNestedChildren());
        }

        return new LocalDeathEvidence(deathKeepWidget != null,
            new ArrayList<>(kept.values()), protectItemOn, skullIcon);
    }

    /** Append an explanation suffix only; the caller must not feed it into accounting. */
    public String appendTo(String existingExplanation, List<ItemFlow> lostFlows)
    {
        StringBuilder result = new StringBuilder(existingExplanation == null ? "" : existingExplanation);
        if (result.length() > 0)
        {
            result.append(' ');
        }
        result.append("Death evidence — Kept: ")
            .append(keptItemsText())
            .append("; lost: ")
            .append(lostItemsText(lostFlows))
            .append("; Protect Item ")
            .append(protectItemOn ? "on" : "off")
            .append("; skull: ")
            .append(skullLabel(skullIcon))
            .append('.');
        return result.toString();
    }

    private String keptItemsText()
    {
        if (!deathKeepWidgetAvailable)
        {
            return "unavailable";
        }
        if (keptItems.isEmpty())
        {
            return "none observed";
        }
        List<String> labels = new ArrayList<>();
        for (KeptItem item : keptItems)
        {
            labels.add(item.quantity > 1 ? item.name + " ×" + formatQuantity(item.quantity) : item.name);
        }
        return join(labels);
    }

    private static String lostItemsText(List<ItemFlow> flows)
    {
        Map<Integer, KeptItem> lost = new LinkedHashMap<>();
        if (flows != null)
        {
            for (ItemFlow flow : flows)
            {
                if (flow == null || flow.getQuantityDelta() >= 0 || flow.getQuantityDelta() == Long.MIN_VALUE)
                {
                    continue;
                }
                long quantity = -flow.getQuantityDelta();
                KeptItem prior = lost.get(flow.getItemId());
                long total = prior == null ? quantity : saturatedAdd(prior.quantity, quantity);
                String name = flow.getItemName() == null || flow.getItemName().trim().isEmpty()
                    ? "item #" + flow.getItemId()
                    : flow.getItemName().trim();
                lost.put(flow.getItemId(), new KeptItem(name, total));
            }
        }
        if (lost.isEmpty())
        {
            return "none observed";
        }
        List<String> labels = new ArrayList<>();
        for (KeptItem item : lost.values())
        {
            labels.add(item.quantity > 1 ? item.name + " ×" + formatQuantity(item.quantity) : item.name);
        }
        return join(labels);
    }

    private static String skullLabel(int icon)
    {
        if (icon == SkullIcon.NONE)
        {
            return "none";
        }
        if (icon == SkullIcon.SKULL)
        {
            return "skulled";
        }
        if (icon == SkullIcon.SKULL_FIGHT_PIT)
        {
            return "Fight Pits";
        }
        if (icon == SkullIcon.SKULL_HIGH_RISK)
        {
            return "high-risk";
        }
        if (icon == SkullIcon.FORINTHRY_SURGE)
        {
            return "Forinthry surge";
        }
        if (icon == SkullIcon.SKULL_DEADMAN)
        {
            return "Deadman";
        }
        int keyCount = lootKeyCount(icon);
        if (keyCount > 0)
        {
            return "loot keys ×" + keyCount;
        }
        return "icon " + icon;
    }

    private static int lootKeyCount(int icon)
    {
        if (icon == SkullIcon.LOOT_KEYS_ONE || icon == SkullIcon.FORINTHRY_SURGE_KEYS_ONE) return 1;
        if (icon == SkullIcon.LOOT_KEYS_TWO || icon == SkullIcon.FORINTHRY_SURGE_KEYS_TWO) return 2;
        if (icon == SkullIcon.LOOT_KEYS_THREE || icon == SkullIcon.FORINTHRY_SURGE_KEYS_THREE) return 3;
        if (icon == SkullIcon.LOOT_KEYS_FOUR || icon == SkullIcon.FORINTHRY_SURGE_KEYS_FOUR) return 4;
        if (icon == SkullIcon.LOOT_KEYS_FIVE || icon == SkullIcon.FORINTHRY_SURGE_KEYS_FIVE) return 5;
        return 0;
    }

    private static String resolveName(int itemId, IntFunction<String> itemName)
    {
        String name = itemName == null ? null : itemName.apply(itemId);
        return name == null || name.trim().isEmpty() ? "item #" + itemId : name.trim();
    }

    private static void addChildren(ArrayDeque<Widget> pending, Widget[] children)
    {
        if (children == null)
        {
            return;
        }
        for (Widget child : children)
        {
            if (child != null)
            {
                pending.addLast(child);
            }
        }
    }

    private static long saturatedAdd(long left, long right)
    {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static String formatQuantity(long quantity)
    {
        return NumberFormat.getIntegerInstance(Locale.US).format(quantity);
    }

    private static String join(List<String> values)
    {
        StringBuilder result = new StringBuilder();
        for (String value : values)
        {
            if (result.length() > 0)
            {
                result.append(", ");
            }
            result.append(value);
        }
        return result.toString();
    }

    private static final class KeptItem
    {
        private final String name;
        private final long quantity;

        private KeptItem(String name, long quantity)
        {
            this.name = name;
            this.quantity = quantity;
        }
    }
}
