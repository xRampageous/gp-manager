package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.gameval.ItemID;

/**
 * Wiki-sourced key/chest pairs used for deferred-claim valuation and provenance.
 * Chest names and locations follow the OSRS Wiki pages for the
 * <a href="https://oldschool.runescape.wiki/w/Crystal_chest">Crystal chest</a>,
 * <a href="https://oldschool.runescape.wiki/w/Elf_crystal_chest">Elven Crystal Chest</a>,
 * <a href="https://oldschool.runescape.wiki/w/Larran%27s_small_chest">Larran's small chest</a>,
 * <a href="https://oldschool.runescape.wiki/w/Larran%27s_big_chest">Larran's big chest</a>,
 * <a href="https://oldschool.runescape.wiki/w/Ogre_Coffin">Ogre coffin</a>,
 * <a href="https://oldschool.runescape.wiki/w/Muddy_chest">Muddy chest</a>,
 * <a href="https://oldschool.runescape.wiki/w/Sinister_chest">Sinister chest</a>,
 * <a href="https://oldschool.runescape.wiki/w/Grubby_chest">Grubby chest</a>,
 * <a href="https://oldschool.runescape.wiki/w/Brimstone_chest">Brimstone chest</a>,
 * <a href="https://oldschool.runescape.wiki/w/Moons_rewards">Moon chest</a>, and
 * <a href="https://oldschool.runescape.wiki/w/Category%3AShades_of_Mort%27ton_%28minigame%29">Shades
 * of Mort'ton chest/key variants</a> (for example,
 * <a href="https://oldschool.runescape.wiki/w/Steel_Chest_%28brown%29">Steel Chest (brown)</a>).
 * Key names/tradeability follow the item pages for
 * <a href="https://oldschool.runescape.wiki/w/Crystal_key">Crystal key</a>,
 * <a href="https://oldschool.runescape.wiki/w/Enhanced_crystal_key">Enhanced crystal key</a>,
 * <a href="https://oldschool.runescape.wiki/w/Larran%27s_key">Larran's key</a>,
 * <a href="https://oldschool.runescape.wiki/w/Ogre_coffin_key">Ogre coffin key</a>,
 * <a href="https://oldschool.runescape.wiki/w/Muddy_key">Muddy key</a>,
 * <a href="https://oldschool.runescape.wiki/w/Sinister_key">Sinister key</a>,
 * <a href="https://oldschool.runescape.wiki/w/Grubby_key">Grubby key</a>,
 * <a href="https://oldschool.runescape.wiki/w/Brimstone_key">Brimstone key</a>,
 * <a href="https://oldschool.runescape.wiki/w/Moon_key">Moon key</a>, and
 * <a href="https://oldschool.runescape.wiki/w/Shade_key">Shade key variants</a> and the
 * <a href="https://oldschool.runescape.wiki/w/Bronze_key_crimson">Bronze key crimson</a>,
 * <a href="https://oldschool.runescape.wiki/w/Steel_key_brown">Steel key brown</a>,
 * <a href="https://oldschool.runescape.wiki/w/Black_key_black">Black key black</a>,
 * <a href="https://oldschool.runescape.wiki/w/Silver_key_red">Silver key red</a>, and
 * <a href="https://oldschool.runescape.wiki/w/Gold_key_purple">Gold key purple</a> pages.
 * Tradeable keys keep their market opportunity cost. Untradeable keys represent an
 * unclaimed reward and are deliberately held at zero until contents enter inventory.
 * The Wiki explicitly documents consumption for Crystal, Muddy, Sinister and Grubby
 * keys (<a href="https://oldschool.runescape.wiki/w/Crystal_key">Crystal key</a>,
 * <a href="https://oldschool.runescape.wiki/w/Muddy_chest">Muddy chest</a>,
 * <a href="https://oldschool.runescape.wiki/w/Money_making_guide/Opening_sinister_chests">Sinister chest</a>,
 * <a href="https://oldschool.runescape.wiki/w/Money_making_guide/Opening_grubby_chests">Grubby chest</a>).
 * The Enhanced crystal, Larran, Ogre coffin, Brimstone, Moon and Shade pages describe
 * using their keys but do not explicitly state that chest use consumes them. Every
 * claim match therefore still requires a measured inventory key loss; a click only
 * disambiguates a chest, and live acceptance must confirm consumption for those families.
 */
public final class KeyChestCatalogue
{
    private static final Map<Integer, List<Entry>> BY_KEY_ID;
    private static final Map<String, Entry> BY_CHEST_NAME;
    private static final List<Entry> ENTRIES;

    static
    {
        Map<Integer, List<Entry>> byKey = new LinkedHashMap<>();
        Map<String, Entry> byChest = new LinkedHashMap<>();
        List<Entry> entries = new ArrayList<>();

        add(byKey, byChest, entries, ItemID.CRYSTAL_KEY, "Crystal key", "Crystal chest", true);
        add(byKey, byChest, entries, ItemID.PRIF_CRYSTAL_KEY, "Enhanced crystal key",
            "Elven Crystal Chest", false);
        add(byKey, byChest, entries, ItemID.SLAYER_WILDERNESS_KEY, "Larran's key",
            "Larran's small chest", true);
        add(byKey, byChest, entries, ItemID.SLAYER_WILDERNESS_KEY, "Larran's key",
            "Larran's big chest", true);
        add(byKey, byChest, entries, ItemID.ZOGRE_COFFINKEY, "Ogre coffin key", "Ogre coffin", true);
        add(byKey, byChest, entries, ItemID.MUDDY_KEY, "Muddy key", "Muddy chest", true);
        add(byKey, byChest, entries, ItemID.SINISTER_KEY, "Sinister key", "Sinister chest", true);
        add(byKey, byChest, entries, ItemID.HOSDUN_GRUBBY_KEY, "Grubby key", "Grubby chest", true);
        add(byKey, byChest, entries, ItemID.KONAR_KEY, "Brimstone key", "Brimstone chest", false);
        add(byKey, byChest, entries, ItemID.VARLAMORE_NICE_KEY, "Moon key", "Moon chest", false);

        addShadeKeys(byKey, byChest, entries);

        Map<Integer, List<Entry>> frozenByKey = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Entry>> entry : byKey.entrySet())
        {
            frozenByKey.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        BY_KEY_ID = Collections.unmodifiableMap(frozenByKey);
        BY_CHEST_NAME = Collections.unmodifiableMap(byChest);
        ENTRIES = Collections.unmodifiableList(entries);
    }

    private KeyChestCatalogue()
    {
    }

    private static void addShadeKeys(Map<Integer, List<Entry>> byKey,
        Map<String, Entry> byChest, List<Entry> entries)
    {
        addShadeTier(byKey, byChest, entries, "Bronze",
            ItemID.SHADEKEY_BRONZE_BLOODRED, ItemID.SHADEKEY_BRONZE_BROWN,
            ItemID.SHADEKEY_BRONZE_CRIMSON, ItemID.SHADEKEY_BRONZE_BLACK,
            ItemID.SHADEKEY_BRONZE_PURPLE);
        addShadeTier(byKey, byChest, entries, "Steel",
            ItemID.SHADEKEY_STEEL_BLOODRED, ItemID.SHADEKEY_STEEL_BROWN,
            ItemID.SHADEKEY_STEEL_CRIMSON, ItemID.SHADEKEY_STEEL_BLACK,
            ItemID.SHADEKEY_STEEL_PURPLE);
        addShadeTier(byKey, byChest, entries, "Black",
            ItemID.SHADEKEY_BLACK_BLOODRED, ItemID.SHADEKEY_BLACK_BROWN,
            ItemID.SHADEKEY_BLACK_CRIMSON, ItemID.SHADEKEY_BLACK_BLACK,
            ItemID.SHADEKEY_BLACK_PURPLE);
        addShadeTier(byKey, byChest, entries, "Silver",
            ItemID.SHADEKEY_SILVER_BLOODRED, ItemID.SHADEKEY_SILVER_BROWN,
            ItemID.SHADEKEY_SILVER_CRIMSON, ItemID.SHADEKEY_SILVER_BLACK,
            ItemID.SHADEKEY_SILVER_PURPLE);
        addShadeTier(byKey, byChest, entries, "Gold",
            ItemID.SHADEKEY_GOLD_BLOODRED, ItemID.SHADEKEY_GOLD_BROWN,
            ItemID.SHADEKEY_GOLD_CRIMSON, ItemID.SHADEKEY_GOLD_BLACK,
            ItemID.SHADEKEY_GOLD_PURPLE);
    }

    private static void addShadeTier(Map<Integer, List<Entry>> byKey,
        Map<String, Entry> byChest, List<Entry> entries, String metal,
        int red, int brown, int crimson, int black, int purple)
    {
        add(byKey, byChest, entries, red, metal + " key red", metal + " Chest (red)", false);
        add(byKey, byChest, entries, brown, metal + " key brown", metal + " Chest (brown)", false);
        add(byKey, byChest, entries, crimson, metal + " key crimson", metal + " Chest (crimson)", false);
        add(byKey, byChest, entries, black, metal + " key black", metal + " Chest (black)", false);
        add(byKey, byChest, entries, purple, metal + " key purple", metal + " Chest (purple)", false);
    }

    private static void add(Map<Integer, List<Entry>> byKey, Map<String, Entry> byChest,
        List<Entry> entries, int keyItemId, String keyName, String chestName, boolean tradeable)
    {
        Entry entry = new Entry(keyItemId, keyName, chestName, tradeable);
        byKey.computeIfAbsent(keyItemId, ignored -> new ArrayList<>()).add(entry);
        byChest.put(normalize(chestName), entry);
        entries.add(entry);
    }

    /** Catalogue entries for a key item; Larran's key has both chest variants. */
    public static List<Entry> entriesForKey(int keyItemId)
    {
        return BY_KEY_ID.getOrDefault(keyItemId, Collections.emptyList());
    }

    /** First known chest association for an item, or {@code null} when not catalogued. */
    @Nullable
    public static Entry entryForKey(int keyItemId)
    {
        List<Entry> entries = entriesForKey(keyItemId);
        return entries.isEmpty() ? null : entries.get(0);
    }

    /** Exact chest-name lookup, case-insensitive and whitespace-trimmed. */
    @Nullable
    public static Entry entryForChest(@Nullable String chestName)
    {
        return chestName == null ? null : BY_CHEST_NAME.get(normalize(chestName));
    }

    /** Match an observed object target that contains one exact catalogue chest label. */
    @Nullable
    public static Entry entryForChestMention(@Nullable String target)
    {
        if (target == null || target.trim().isEmpty())
        {
            return null;
        }
        String normalizedTarget = normalize(target);
        Entry best = null;
        for (Entry entry : ENTRIES)
        {
            String name = normalize(entry.getChestName());
            if (normalizedTarget.contains(name)
                && (best == null || name.length() > normalize(best.getChestName()).length()))
            {
                best = entry;
            }
        }
        return best;
    }

    /** Arm a short-lived chest label for measured key-loss disambiguation only. */
    @Nullable
    public static Entry entryForMenuOption(@Nullable String option, @Nullable String target)
    {
        String action = option == null ? "" : normalize(option);
        if (!(action.equals("open") || action.startsWith("open ")
            || action.equals("unlock") || action.startsWith("unlock ")
            || action.equals("search") || action.startsWith("search ")))
        {
            return null;
        }
        return entryForChestMention(target);
    }

    public static boolean isCataloguedKey(int keyItemId)
    {
        return BY_KEY_ID.containsKey(keyItemId);
    }

    /** Deferred claims have no held value and are excluded from high-alchemy fallback. */
    public static boolean isDeferredClaimKey(int keyItemId)
    {
        Entry entry = entryForKey(keyItemId);
        return entry != null && !entry.isTradeable();
    }

    /** True when every mapped chest for this item uses the existing GE opportunity cost. */
    public static boolean isTradeableKey(int keyItemId)
    {
        List<Entry> entries = entriesForKey(keyItemId);
        return !entries.isEmpty() && entries.get(0).isTradeable();
    }

    public static List<Entry> entries()
    {
        return ENTRIES;
    }

    private static String normalize(String value)
    {
        return value.trim().replace('\u2019', '\'').toLowerCase(Locale.ROOT);
    }

    /** Immutable key-to-chest information for accounting and presentation. */
    public static final class Entry
    {
        private final int keyItemId;
        private final String keyItemName;
        private final String chestName;
        private final boolean tradeable;

        private Entry(int keyItemId, String keyItemName, String chestName, boolean tradeable)
        {
            this.keyItemId = keyItemId;
            this.keyItemName = keyItemName;
            this.chestName = chestName;
            this.tradeable = tradeable;
        }

        public int getKeyItemId() { return keyItemId; }
        public String getKeyItemName() { return keyItemName; }
        public String getChestName() { return chestName; }
        public String getActivityLabel() { return chestName; }
        public boolean isTradeable() { return tradeable; }
        public boolean isDeferredClaim() { return !tradeable; }
    }
}
