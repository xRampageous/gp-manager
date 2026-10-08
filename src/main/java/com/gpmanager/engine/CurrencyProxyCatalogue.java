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
static final Set<Integer> CURRENCIES = Ak.byId("d1").keySet();
/** Proxy rows: item id to {market item id, units per proxy item}. */
static final Map<Integer, int[]> PROXIES = new HashMap<>();
static {
 for (String[] /* row */b : Ak.rows("d1")) {
  if (b.length > 2) {
   PROXIES.put(Integer.parseInt(b[0]), new int[]{Integer.parseInt(b[1]), Integer.parseInt(b[2])});
  }
 }
}

static boolean awk(int /* itemId */a) {
 return CURRENCIES.contains(a);
}

/** The proxy price source for this item, or null. */
static int[] axy(int /* itemId */a) {
 return PROXIES.get(a);
}
}
