package com.gpmanager;
import java.util.*;
class ItemRuleParser {
static Map<Integer, Integer> parsePriceOverrides(String value) {
 if (ModelText.blank(value)) return Collections.emptyMap();
 var result = new HashMap<Integer, Integer>();
 for (String token : value.split(",")) {
  String[] parts = token.trim().split("=", 2);
  if (parts.length != 2) continue;
  try {
   int itemId = Integer.parseInt(parts[0].trim());
   int price = Integer.parseInt(parts[1].trim());
   if (itemId >= 0 && price >= 0) result.put(itemId, price);
  } catch (NumberFormatException ignored) {
   // Invalid entries are ignored so a typo cannot break tracking.
  }
 }
 return result;
}
}
