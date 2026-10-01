package com.gpmanager;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
/**
* Untradeable non-GP currencies. They are unpriced unless a manual override supplies a price or
* a proxy row prices them from a market item and its fixed exchange rate: RuneLite's price helper
* would otherwise value some of them through an item mapping.
*/
class CurrencyProxyCatalogue {
static final Set<Integer> CURRENCIES = GameData.byId("d1").keySet();
/** Proxy rows: item id to {market item id, units per proxy item}. */
static final Map<Integer, int[]> PROXIES = new HashMap<>();
static {
 for (String[] row : GameData.rows("d1")) {
  if (row.length > 2) {
   PROXIES.put(Integer.parseInt(row[0]), new int[]{Integer.parseInt(row[1]), Integer.parseInt(row[2])});
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
}
