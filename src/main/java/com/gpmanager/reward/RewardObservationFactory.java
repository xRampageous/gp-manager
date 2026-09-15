package com.gpmanager.reward;

import com.gpmanager.model.ItemPriceSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.api.ItemComposition;

/** Builds priced reward items from RuneLite item stacks without touching the ledger. */
public final class RewardObservationFactory
{
    private RewardObservationFactory()
    {
    }

    public static List<RewardItem> fromItemStacks(
        @Nullable ItemManager itemManager,
        Collection<ItemStack> stacks)
    {
        List<RewardItem> items = new ArrayList<>();
        if (stacks == null)
        {
            return items;
        }
        for (ItemStack stack : stacks)
        {
            if (stack == null || stack.getId() < 0 || stack.getQuantity() <= 0)
            {
                continue;
            }
            int itemId = stack.getId();
            if (itemManager != null)
            {
                try
                {
                    itemId = itemManager.canonicalize(stack.getId());
                }
                catch (RuntimeException ignored)
                {
                    itemId = stack.getId();
                }
            }
            String name = "Item " + itemId;
            int unitPrice = 0;
            boolean known = false;
            ItemPriceSource source = ItemPriceSource.UNKNOWN;
            if (itemManager != null)
            {
                try
                {
                    ItemComposition composition = itemManager.getItemComposition(itemId);
                    if (composition != null && composition.getName() != null)
                    {
                        name = composition.getName();
                    }
                    unitPrice = itemManager.getItemPrice(itemId);
                    if (unitPrice > 0)
                    {
                        known = true;
                        source = ItemPriceSource.GRAND_EXCHANGE;
                    }
                }
                catch (RuntimeException ignored)
                {
                    // Keep unknown pricing explicit.
                }
            }
            long qty = stack.getQuantity();
            items.add(new RewardItem(
                itemId,
                name,
                qty,
                known ? unitPrice * qty : 0L,
                known,
                source));
        }
        return RewardObservation.mergeStacks(items);
    }
}
