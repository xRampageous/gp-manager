package com.gpmanager;

import static com.gpmanager.GameData.msg;

import java.util.*;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.*;
import lombok.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;

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

/**
 * Special bags/sacks/barrels/kits/pouches. Store/retrieve is ownership-neutral ({@link Kind});
 * contents valuation follows snapshots — never invent gain/loss.
 */
class UtilityContainerCatalogue {
    enum Kind {
        STORE,
        WITHDRAW,
        EMPTY_TO_BANK,
        EMPTY_TO_INVENTORY,
        CALIBRATE_CHECK
    }

    /** Special container names, most specific first: the first one a name contains wins. */
    static final List<String> NAMES = GameData.names("utility_containers");

    static final Pattern ESSENCE_POUCH = GameData.pattern("essence-pouch");

    /**
     * The container family used in the custody note ({@code herb_sack}), or null for other items.
     */
    static String familyFor(String itemName) {
        String lower = Phrases.norm(itemName);
        if (lower != null && ESSENCE_POUCH.matcher(lower).find()) return "essence_pouch";
        for (String name : NAMES) {
            if (lower != null && lower.contains(name))
                return name.replace("'", "").replace(' ', '_').replace('-', '_');
        }
        return null;
    }

    /**
     * Classify a menu option on a known container. Deposit-box Empty → bank is {@link
     * Kind#EMPTY_TO_BANK} (ownership-neutral).
     */
    static Kind classify(String option, boolean depositBoxOpen) {
        String normalized = Phrases.lower(option);
        return "check".equals(normalized)
                ? Kind.CALIBRATE_CHECK
                : normalized.startsWith("empty")
                        ? depositBoxOpen ? Kind.EMPTY_TO_BANK : Kind.EMPTY_TO_INVENTORY
                        : normalized.startsWith("withdraw") || normalized.startsWith("remove")
                                ? Kind.WITHDRAW
                                : Kind.STORE;
    }

    /** True when evidence must stay ownership-neutral (never invent Net). */
    static boolean isNeutral(Kind kind) {
        return kind != null && kind != Kind.CALIBRATE_CHECK;
    }

    /**
     * True when the menu option is a container custody action this catalogue can classify.
     * Store/withdraw/empty/fill are reversible custody movement, never revenue or cost.
     */
    static boolean isCustodyOption(String option) {
        String normalized = Phrases.norm(option);
        return normalized != null
                && (normalized.matches(msg("fill-check"))
                        || normalized.startsWith("empty")
                        || normalized.startsWith("withdraw")
                        || normalized.startsWith("remove"));
    }
}

/**
 * Region-based ownership-neutral zones. Some instances store the whole inventory and equipment on
 * entry and restore it on exit without any menu click at the exit — The Gauntlet is the canonical
 * case (GP Per Hour issues #7/#8: all gear booked as a loss on entry and a gain on exit). While the
 * player is inside such a region every settled delta is an ownership-neutral transfer; leaving
 * opens one more window so the restore settles as a transfer too. Reward chests are claimed in the
 * lobby, outside the zone, so real loot still counts.
 *
 * <p>Pure state machine over region ids; the plugin feeds it one region per tick.
 */
@Getter
class NeutralZoneTracker {
    enum Signal {
        /** Not in a neutral zone and nothing to close. */
        NONE,
        /** First tick inside a neutral zone. */
        ENTERED,
        /** Inside a neutral zone: keep the transfer window open this tick. */
        INSIDE,
        /** Just left a neutral zone: open one closing window for the gear restore. */
        LEFT
    }

    boolean inside;

    /** Feed the current region id (0 or negative when unknown). */
    Signal onRegion(int regionId) {
        boolean now = MinigameRegionHints.isNeutralZoneRegion(regionId);
        if (now) {
            boolean entered = !inside;
            inside = true;
            return entered ? Signal.ENTERED : Signal.INSIDE;
        }
        if (inside && regionId > 0) {
            inside = false;
            return Signal.LEFT;
        }
        // Unknown region (loading screens) keeps the previous state without signalling.
        return Signal.NONE;
    }

    void reset() {
        inside = false;
    }
}

/**
 * Minigame-only items: ids that cannot legally exist outside their minigame (a loaned loadout,
 * table supplies), so their grant and exit strip are both non-economic. Persistent rewards stay
 * tracked. Coinflow MinigameQuarantine evidence, Muse review, owner 2026-10-12.
 */
class MinigameItems {
    static final Set<Integer> IDS = GameData.byId("minigame_items").keySet();

    /** True for an item id that only exists inside its minigame. */
    static boolean isMinigameOnly(int itemId) {
        return IDS.contains(itemId);
    }
}

/**
 * Conservative region hints for LMS and Gauntlet instances when menu text is sparse. Live producers
 * pass {@link net.runelite.api.coords.WorldPoint#getRegionID()}.
 */
class MinigameRegionHints {
    /**
     * Region id to kind: "lms" for Last Man Standing instances (not exhaustive; menu and chat stay
     * primary) and "neutral" for instances that store the player's gear on entry and restore it on
     * exit with no exit click (The Gauntlet and the Corrupted Gauntlet; see minigame-regions.tsv).
     */
    static final Map<Integer, String> KINDS = GameData.byId("minigame_regions");

    /** True inside an instance whose inventory changes are never profit or loss. */
    static boolean isNeutralZoneRegion(int regionId) {
        return "neutral".equals(KINDS.get(regionId));
    }

    /** The PvP tutorial arena: lent gear and supplies, taken back on the way out. */
    static boolean isPvpTutorial(int regionId) {
        return "arena".equals(KINDS.get(regionId));
    }

    static boolean isLmsRegion(int regionId) {
        return "lms".equals(KINDS.get(regionId));
    }
}

/**
 * Ownership-neutral moves, treated like a bank TRANSFER: world and coin storages (seed vault, tool
 * leprechaun, coffers, STASH units...) and minigame moves (LMS loadouts, raid bags, GIM shared
 * storage). Never invent profit or loss on deposit, withdraw, enter or exit, and never book a full
 * inventory as Lost or loot on an LMS enter or exit.
 *
 * <p>The rows live in neutral_transfers.tsv and are checked in file order, so the first match wins:
 * minigame rows have "m" keys, and the ".lms" rows apply only inside an LMS region.
 */
class NeutralTransfers {
    static final java.util.Map<String, Phrases.Clause[][]> RULES =
            GameData.rules("neutral_transfers");

    /** The transfer note of the first storage row matching this menu text, or null. */
    static String storage(String raw) {
        return match(Phrases.norm(raw), false, -1);
    }

    static String minigame(String raw) {
        return minigame(raw, -1);
    }

    /** The transfer note of the first minigame row matching this menu text, or null. */
    static String minigame(String raw, int regionId) {
        return match(Phrases.lower(raw), true, regionId);
    }

    private static String match(String name, boolean minigame, int regionId) {
        boolean lms = regionId >= 0 && MinigameRegionHints.isLmsRegion(regionId);
        for (String[] row : GameData.cachedRows("neutral_transfers")) {
            if (row[0].startsWith("m") != minigame || row[0].endsWith(".lms") && !lms) continue;
            if (Phrases.has(name, RULES.get(row[0]))) return GameData.text(row[3]);
        }
        return null;
    }
}
