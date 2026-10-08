package com.gpmanager;
import java.util.*;
class ItemRuleParser {
static Map<Integer, Integer> parsePriceOverrides(String /* value */b) {
 if (Ag.blank(b)) return Collections.emptyMap();
 var /* result */c = new HashMap<Integer, Integer>();
 for (String /* token */d : b.split(",")) {
  String[] /* parts */e = d.trim().split("=", 2);
  if (e.length != 2) continue;
  try {
   int /* itemId */f = Integer.parseInt(e[0].trim());
   int /* price */g = Integer.parseInt(e[1].trim());
   if (f >= 0 && g >= 0) c.put(f, g);
  } catch (NumberFormatException /* ignored */h) {
   // Invalid entries are ignored so a typo cannot break tracking.
  }
 }
 return c;
}
}
