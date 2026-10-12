package com.gpmanager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Untradeable non-GP currencies. They are unpriced unless a manual override supplies a price or a
 * proxy row prices them from a market item and its fixed exchange rate: RuneLite's price helper
 * would otherwise value some of them through an item mapping. A proxy's units cell may read
 * base/with-gloves when Karamja gloves improve that exchange.
 */
class CurrencyProxyCatalogue {
    static final Set<Integer> CURRENCIES = GameData.byId("currencies").keySet();

    /** Proxy rows: item id to {market item id, units per proxy item}. */
    static final Map<Integer, int[]> PROXIES = new HashMap<>();

    /** Proxy rows whose exchange improves with Karamja gloves worn: item id to gloves units. */
    static final Map<Integer, Integer> GLOVES_UNITS = new HashMap<>();

    static {
        for (String[] row : GameData.rows("currencies")) {
            if (row.length > 2) {
                String[] units = row[2].split("/");
                PROXIES.put(
                        Integer.parseInt(row[0]),
                        new int[] {Integer.parseInt(row[1]), Integer.parseInt(units[0])});
                if (units.length > 1) {
                    GLOVES_UNITS.put(Integer.parseInt(row[0]), Integer.parseInt(units[1]));
                }
            }
        }
    }

    static boolean isMapped(int itemId) {
        return CURRENCIES.contains(itemId);
    }

    /** The proxy price source for this item, or null. */
    static int[] proxyOf(int itemId) {
        return PROXIES.get(itemId);
    }

    /** The gloves-worn units for this proxy row, or 0 when its rate does not change. */
    static int glovesUnitsOf(int itemId) {
        return GLOVES_UNITS.getOrDefault(itemId, 0);
    }
}
