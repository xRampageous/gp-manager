package com.gpmanager;
import java.util.*;
/**
* Reward-interface / chest adapters → Pending Rewards → Claimed once.
* Name matching for Loot Tracker EVENT + live reward UIs.
*/
class RewardChestCatalogue {
static final List<String[]> SOURCES = Ak.rows("d3");
/** True when this LT/event name is a reward chest / raid / clue pool — not floor loot. */
static boolean xn(String name) {
if (Ag.blank(name)) {
return false;
}
if (KeyChestCatalogue.rp(name) != null) {
return true;
}
String lower = name.trim().toLowerCase(Locale.ROOT);
for (String[] source : SOURCES) {
if (lower.contains(source[0])) {
return true;
}
}
return false;
}
}
