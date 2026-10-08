package com.gpmanager;
import java.util.*;
/**
* Reward-interface / chest adapters → Pending Rewards → Claimed once.
* Name matching for Loot Tracker EVENT + live reward UIs.
*/
class RewardChestCatalogue {
static final List<String[]> SOURCES = Ak.x("d3");
/** True when this LT/event name is a reward chest / raid / clue pool — not floor loot. */


/** Reward fragments share the same once-compiled matcher as other evidence tables. */
static final Map<String, Ag.P[][]> R = Ak.r("d3");
static boolean a1(String a) {
 if (Ag.blank(a)) return false;
 if (KeyChestCatalogue.rp(a) != null) return true;
 String b = Ag.lower(a);
 return R.values().stream().anyMatch(c -> Ag.has(b, c));
}
}
