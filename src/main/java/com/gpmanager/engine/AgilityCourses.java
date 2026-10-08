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
static String qp(int /* regionId */a) {
 return BY_REGION.get(a);
}

/** The Wilderness course's reward dispenser and the Brimhaven ticket dispenser. */


/** Name-only dispenser evidence, independent of the region, as before. */
static final java.util.regex.Pattern D = Ak.pattern("dispenser");
static boolean a1(String a) { return a != null && D.matcher(Ag.lower(a)).matches(); }
}
