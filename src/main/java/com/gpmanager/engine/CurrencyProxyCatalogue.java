package com.gpmanager.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Curated untradeable currency → traded counterpart valuation.
 * Manual price overrides always win over this catalogue.
 */
public final class CurrencyProxyCatalogue
{
    /** itemId → (counterpartItemId, units of currency per 1 counterpart GE gp… actually rate: gp per unit). */
    public static final class Proxy
    {
        public final int counterpartItemId;
        /** How many currency units equal one counterpart item (e.g. tokkul per onyx). */
        public final int currencyPerCounterpart;
        public final String note;

        public Proxy(int counterpartItemId, int currencyPerCounterpart, String note)
        {
            this.counterpartItemId = counterpartItemId;
            this.currencyPerCounterpart = Math.max(1, currencyPerCounterpart);
            this.note = note == null ? "" : note;
        }
    }

    // OSRS item ids (tradeable counterparts where GE price exists).
    private static final int TOKKUL = 6529;
    private static final int ONYX = 6573;
    private static final int CRYSTAL_SHARD = 23866;
    private static final int CRYSTAL_DUST = 23964;
    private static final int ENHANCED_CRYSTAL_WEAPON_SEED = 25859;
    private static final int MERMAIDS_TEAR = 21649;
    private static final int STARDUST = 25527;
    private static final int UNIDENTIFIED_MINERALS = 21341;
    private static final int GOLDEN_NUGGET = 12012;
    private static final int ABYSSAL_PEARL = 27285;
    private static final int HALLOWED_MARK = 24714;
    private static final int BOUNTY_HUNTER_EMBLEM_T1 = 12746;
    private static final int GUARDIAN_FRAGMENTS = 26879;
    private static final int COINS = 995;

    private static final Map<Integer, Proxy> BY_ID;

    static
    {
        Map<Integer, Proxy> map = new LinkedHashMap<>();
        // Approximate TzHaar shop rates — counterpart GE ÷ rate ≈ unit proxy GP.
        map.put(TOKKUL, new Proxy(ONYX, 300_000, "Tokkul × onyx proxy"));
        // Enhanced weapon seed exchanges for 1,500 shards (the teleport seed
        // exchanges for 150). One shard grinds into ten dust.
        map.put(CRYSTAL_SHARD, new Proxy(ENHANCED_CRYSTAL_WEAPON_SEED, 1_500, "Crystal shard × enhanced weapon seed / 1,500 proxy"));
        map.put(CRYSTAL_DUST, new Proxy(ENHANCED_CRYSTAL_WEAPON_SEED, 15_000, "Crystal dust × enhanced weapon seed / 15,000 proxy"));
        // Tears / dust / minerals / nuggets / pearls / marks: soft coin-anchored proxies
        // until a better traded sink is preferred — still beaten by manual overrides.
        // Coin-anchored rows return unit GP 0 (honest unpriced) unless manually overridden.
        map.put(MERMAIDS_TEAR, new Proxy(COINS, 1, "Mermaid's tear × coin proxy (manual override required)"));
        map.put(STARDUST, new Proxy(COINS, 1, "Stardust × coin proxy (manual override required)"));
        map.put(UNIDENTIFIED_MINERALS, new Proxy(COINS, 1, "Unidentified minerals × coin proxy (manual override required)"));
        map.put(GOLDEN_NUGGET, new Proxy(COINS, 1, "Golden nugget × coin proxy (manual override required)"));
        map.put(ABYSSAL_PEARL, new Proxy(COINS, 1, "Abyssal pearl × coin proxy (manual override required)"));
        map.put(HALLOWED_MARK, new Proxy(COINS, 1, "Hallowed mark × coin proxy (manual override required)"));
        map.put(BOUNTY_HUNTER_EMBLEM_T1, new Proxy(COINS, 1, "BH emblem × coin proxy (manual override required)"));
        map.put(GUARDIAN_FRAGMENTS, new Proxy(COINS, 1, "Guardian fragments × coin proxy (manual override required)"));
        BY_ID = Collections.unmodifiableMap(map);
    }

    private CurrencyProxyCatalogue()
    {
    }

    public static boolean isMapped(int itemId)
    {
        return BY_ID.containsKey(itemId);
    }

    @Nullable
    public static Proxy proxyFor(int itemId)
    {
        return BY_ID.get(itemId);
    }

    /**
     * Unit GP for one currency item given the counterpart's unit GE price.
     * Returns 0 when counterpart is coins and we lack a fixed rate table —
     * callers should treat coin-anchored entries as unpriced unless overridden.
     */
    public static int unitPriceFromCounterpart(Proxy proxy, int counterpartUnitPrice)
    {
        if (proxy == null || counterpartUnitPrice <= 0)
        {
            return 0;
        }
        if (proxy.counterpartItemId == COINS)
        {
            // Coin-anchored rows are placeholders — require manual override for real GP.
            return 0;
        }
        return Math.max(0, counterpartUnitPrice / proxy.currencyPerCounterpart);
    }

    /** True when the catalogue row is coin-anchored (honest unpriced without override). */
    public static boolean isCoinAnchored(int itemId)
    {
        Proxy proxy = BY_ID.get(itemId);
        return proxy != null && proxy.counterpartItemId == COINS;
    }

    /** Ledger why-counted label for a currency item, or empty when unmapped. */
    public static String whyCountedNote(int itemId)
    {
        Proxy proxy = BY_ID.get(itemId);
        return proxy == null ? "" : proxy.note;
    }

    public static Map<Integer, Proxy> all()
    {
        return BY_ID;
    }
}
