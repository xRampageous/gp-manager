package com.gpmanager.engine;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

@Singleton
public class ContainerSnapshotFactory
{
    private final Client client;
    private final ItemManager itemManager;

    @Inject
    public ContainerSnapshotFactory(Client client, ItemManager itemManager)
    {
        this.client = client;
        this.itemManager = itemManager;
    }

    /** Rune pouch slot varbits (type, quantity) — live account state, unlike bag containers. */
    static final int[][] RUNE_POUCH_SLOTS = {
        {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_QUANTITY_1},
        {VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_QUANTITY_2},
        {VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_QUANTITY_3},
        {VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_QUANTITY_4},
        {VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_QUANTITY_5},
        {VarbitID.RUNE_POUCH_TYPE_6, VarbitID.RUNE_POUCH_QUANTITY_6},
    };

    public ContainerSnapshot capture(boolean includeEquipment)
    {
        return capture(includeEquipment, false);
    }

    /**
     * @param includeRunePouch merge runes held in the rune pouch. The pouch is account
     *                         state exposed through live varbits, so casting from it books
     *                         a cost and filling it nets to zero. Looting bag / seed box
     *                         containers are deliberately excluded: the client only syncs
     *                         them when the bag is opened, so they would drift.
     */
    public ContainerSnapshot capture(boolean includeEquipment, boolean includeRunePouch)
    {
        Map<Integer, Long> quantities = new HashMap<>();
        mergeContainer(quantities, client.getItemContainer(InventoryID.INV));

        if (includeEquipment)
        {
            mergeContainer(quantities, client.getItemContainer(InventoryID.WORN));
        }
        if (includeRunePouch)
        {
            mergeRunePouch(quantities);
        }

        return new ContainerSnapshot(quantities);
    }

    /**
     * Snapshot only the player's inventory and (when enabled for accounting) worn
     * equipment for local-death ownership reconciliation. Rune-pouch varbits and banked
     * containers are deliberately excluded because neither is an inventory/equipment
     * stack read from the death event.
     */
    public ContainerSnapshot captureDeathHeldItems(boolean includeEquipment)
    {
        Map<Integer, Long> quantities = new HashMap<>();
        mergeContainer(quantities, client.getItemContainer(InventoryID.INV));
        if (includeEquipment)
        {
            mergeContainer(quantities, client.getItemContainer(InventoryID.WORN));
        }
        return new ContainerSnapshot(quantities);
    }

    /** True when {@code varbitId} is one of the rune pouch slot varbits. */
    public static boolean isRunePouchVarbit(int varbitId)
    {
        for (int[] slot : RUNE_POUCH_SLOTS)
        {
            if (slot[0] == varbitId || slot[1] == varbitId)
            {
                return true;
            }
        }
        return false;
    }

    /** True when at least one live rune-pouch slot contains runes. */
    public boolean hasRunePouchContents()
    {
        for (int[] slot : RUNE_POUCH_SLOTS)
        {
            if (client.getVarbitValue(slot[0]) > 0 && client.getVarbitValue(slot[1]) > 0)
            {
                return true;
            }
        }
        return false;
    }

    /** Current rune-pouch stacks when the client exposes all readable pouch slots. */
    public Map<Integer, Long> runePouchContents()
    {
        Map<Integer, Long> quantities = new HashMap<>();
        mergeRunePouch(quantities);
        return java.util.Collections.unmodifiableMap(quantities);
    }

    /** Whether the rune-pouch item mapping is readable for this client state. */
    public boolean isRunePouchContentsReadable()
    {
        try
        {
            return client.getEnum(EnumID.RUNEPOUCH_RUNE) != null;
        }
        catch (RuntimeException ex)
        {
            return false;
        }
    }

    private void mergeRunePouch(Map<Integer, Long> quantities)
    {
        EnumComposition runes;
        try
        {
            runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
        }
        catch (RuntimeException ignored)
        {
            return;
        }
        if (runes == null)
        {
            return;
        }
        for (int[] slot : RUNE_POUCH_SLOTS)
        {
            int type = client.getVarbitValue(slot[0]);
            int quantity = client.getVarbitValue(slot[1]);
            if (type <= 0 || quantity <= 0)
            {
                continue;
            }
            int itemId = runes.getIntValue(type);
            if (itemId <= 0)
            {
                continue;
            }
            quantities.merge(canonical(itemId), (long) quantity, Long::sum);
        }
    }

    private void mergeContainer(Map<Integer, Long> quantities, ItemContainer container)
    {
        if (container == null)
        {
            return;
        }

        for (Item item : container.getItems())
        {
            if (item == null || item.getId() < 0 || item.getQuantity() <= 0)
            {
                continue;
            }

            int canonicalId = canonical(item.getId());
            quantities.merge(canonicalId, (long) item.getQuantity(), Long::sum);
        }
    }

    /** Un-noted / un-placeholdered id; identity when no ItemManager is wired (offline tests). */
    private int canonical(int itemId)
    {
        return itemManager == null ? itemId : itemManager.canonicalize(itemId);
    }
}
