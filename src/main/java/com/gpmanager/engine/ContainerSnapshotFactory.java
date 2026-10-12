package com.gpmanager;

import lombok.RequiredArgsConstructor;

import net.runelite.api.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;

import java.util.*;

import javax.inject.*;
import lombok.*;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ContainerSnapshotFactory {
    final Client client;
    final ItemLookup lookup;

    /** Rune pouch slot varbits (type, quantity) — live account state, unlike bag containers. */
    static final int[][] RUNE_POUCH_SLOTS = {
        {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_QUANTITY_1},
        {VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_QUANTITY_2},
        {VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_QUANTITY_3},
        {VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_QUANTITY_4},
        {VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_QUANTITY_5},
        {VarbitID.RUNE_POUCH_TYPE_6, VarbitID.RUNE_POUCH_QUANTITY_6},
    };

    /**
     * @param includeRunePouch merge runes held in the rune pouch. The pouch is account state
     *     exposed through live varbits, so casting from it books a cost and filling it nets to
     *     zero. Looting bag / seed box containers are deliberately excluded: the client only syncs
     *     them when the bag is opened, so they would drift.
     */
    ContainerSnapshot capture(boolean includeEquipment, boolean includeRunePouch) {
        var quantities = new HashMap<Integer, Long>();
        mergeContainer(quantities, client.getItemContainer(InventoryID.INV));
        if (includeEquipment) {
            mergeContainer(quantities, client.getItemContainer(InventoryID.WORN));
            // Ammo stored in Dizana's quiver is spent like the ammo slot's (research 1.2).
            mergeContainer(quantities, client.getItemContainer(InventoryID.DIZANAS_QUIVER_AMMO));
        }
        if (includeRunePouch) mergeRunePouch(quantities);
        // A carried ash sanctifier holds death runes as charges, ten per rune (research 1.2): each
        // ten ashes it scatters spend a rune, and charging it nets to zero.
        if (quantities.containsKey(ItemID.ASH_SANCTIFIER)) {
            quantities.merge(
                    ItemID.DEATHRUNE,
                    client.getVarbitValue(VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY) / 10L,
                    Long::sum);
        }
        return new ContainerSnapshot(quantities);
    }

    /**
     * Snapshot only the player's inventory and (when enabled for accounting) worn equipment for
     * local-death ownership reconciliation. Rune-pouch varbits and banked containers are
     * deliberately excluded because neither is an inventory/equipment stack read from the death
     * event.
     */
    ContainerSnapshot captureDeathHeld(boolean includeEquipment) {
        var quantities = new HashMap<Integer, Long>();
        mergeContainer(quantities, client.getItemContainer(InventoryID.INV));
        if (includeEquipment) mergeContainer(quantities, client.getItemContainer(InventoryID.WORN));
        return new ContainerSnapshot(quantities);
    }

    /** True when {@code varbitId} is one of the rune pouch slot varbits. */
    static boolean isPouchVarbit(int varbitId) {
        for (int[] slot : RUNE_POUCH_SLOTS) {
            if (slot[0] == varbitId || slot[1] == varbitId) return true;
        }
        return false;
    }

    void mergeRunePouch(Map<Integer, Long> quantities) {
        EnumComposition runes;
        try {
            runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
        } catch (RuntimeException ignored) {
            return;
        }
        if (runes == null) return;
        for (int[] slot : RUNE_POUCH_SLOTS) {
            int type = client.getVarbitValue(slot[0]);
            int quantity = client.getVarbitValue(slot[1]);
            if (type <= 0 || quantity <= 0) continue;
            int itemId = runes.getIntValue(type);
            if (itemId <= 0) continue;
            quantities.merge(canonical(itemId), (long) quantity, Long::sum);
        }
    }

    void mergeContainer(Map<Integer, Long> quantities, ItemContainer container) {
        if (container == null) return;
        for (Item item : container.getItems()) {
            if (item == null || item.getId() < 0 || item.getQuantity() <= 0) continue;
            int canonicalId = canonical(item.getId());
            quantities.merge(canonicalId, (long) item.getQuantity(), Long::sum);
        }
    }

    /** An open bag or lit lantern is the same item as the closed or unlit one (d21; owner 1.2). */
    static final Map<Integer, Integer> ONE_ITEM = new HashMap<>();

    /** The open bags among them: a pickup may land in one instead of the inventory. */
    static final Set<Integer> BAGS = new HashSet<>();

    static {
        for (String[] row : GameData.rows("item_states")) {
            ONE_ITEM.put(Integer.parseInt(row[0]), Integer.parseInt(row[1]));
            if (row[2].contains("_OPEN=")) BAGS.add(Integer.parseInt(row[0]));
        }
    }

    /** True while the inventory or equipment holds an open bag. */
    boolean bagOpen() {
        for (int container : new int[] {InventoryID.INV, InventoryID.WORN}) {
            ItemContainer items = client.getItemContainer(container);
            if (items == null) continue;
            for (Item item : items.getItems())
                if (item != null && BAGS.contains(item.getId())) return true;
        }
        return false;
    }

    /** Un-noted / un-placeholdered, closed and unlit id; no ItemManager in offline tests. */
    int canonical(int itemId) {
        int id = lookup == null ? itemId : lookup.canon(itemId);
        return ONE_ITEM.getOrDefault(id, id);
    }
}

@EqualsAndHashCode
class ContainerSnapshot {
    Map<Integer, Long> quantities;

    ContainerSnapshot(Map<Integer, Long> quantities) {
        this.quantities = Collections.unmodifiableMap(new HashMap<>(quantities));
    }

    static ContainerSnapshot empty() {
        return new ContainerSnapshot(Collections.emptyMap());
    }

    Map<Integer, Long> diff(ContainerSnapshot previous) {
        var result = new HashMap<Integer, Long>();
        var itemIds = new HashSet<Integer>(quantities.keySet());
        itemIds.addAll(previous.quantities.keySet());
        for (Integer itemId : itemIds) {
            long currentQuantity = quantities.getOrDefault(itemId, 0L);
            long previousQuantity = previous.quantities.getOrDefault(itemId, 0L);
            long delta = currentQuantity - previousQuantity;
            if (delta != 0L) result.put(itemId, delta);
        }
        return result;
    }

    /** Quantity of one item id in this snapshot (0 when absent). */
    long quantityOf(int itemId) {
        return quantities.getOrDefault(itemId, 0L);
    }
}
