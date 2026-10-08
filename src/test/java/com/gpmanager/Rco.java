package com.gpmanager;
import java.util.*;
/**
* Reward-interface / chest adapters → Pending Rewards → Claimed once.
* Name matching for Loot Tracker EVENT + live reward UIs.
*/
class Rco {
static final List<String[]> SOURCES = Ak.rows("d3");
/** True when this LT/event name is a reward chest / raid / clue pool — not floor loot. */
static boolean xn(String /* name */a) {
 if (Ag.blank(a)) return false;
 if (KeyChestCatalogue.rp(a) != null) return true;
 String /* lower */b = Ag.lower(a);
 for (String[] /* source */c : SOURCES) {
  if (b.contains(c[0])) return true;
 }
 return false;
}
}
