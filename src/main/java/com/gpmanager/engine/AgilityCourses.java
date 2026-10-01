package com.gpmanager;
import java.util.*;
/**
* Agility courses by map region, so Agility XP earns a named activity ("Wilderness Agility
* Course") instead of the bare skill. Presentation / activity naming only — accounting never
* reads this. Region ids follow the wiki course maps (and RuneLite's agility plugin).
*/
class AgilityCourses {
static final Map<Integer, String> BY_REGION = Ak.byId("d14");
/** Course name for a region, or null when the region is not a known course. */
static String qp(int regionId) {
 return BY_REGION.get(regionId);
}

/** The Wilderness course's reward dispenser and the Brimhaven ticket dispenser. */
static boolean xd(String target) {
 if (target == null) return false;
 String lower = target.trim().toLowerCase(Locale.ROOT);
 return Ag.has(lower, "agility dispenser", "ticket dispenser");
}
}
