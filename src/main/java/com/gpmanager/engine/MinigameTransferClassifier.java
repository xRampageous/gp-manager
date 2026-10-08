package com.gpmanager;
import static com.gpmanager.Ak.msg;
import static com.gpmanager.Ag.has;
/**
* LMS invent wipe, raid private/shared bags, GIM shared storage — all ownership-neutral
* TRANSFER / baseline refresh. Never book full invent as Lost/LOOT on enter/exit.
*/
class MinigameTransferClassifier {
enum Event {
 LMS_ENTER, LMS_EXIT, RAID_BAG_STORE, RAID_BAG_WITHDRAW, RAID_BAG_CLEAR, GIM_SHARED_DEPOSIT, GIM_SHARED_WITHDRAW;
 String note;
 Event() { note = Ak.t(Ak.v("d26", name())[3]); }
}



/** Classifies a menu/chat note; inside an LMS region ({@code regionId}) entering is implied. */


/** LMS invent wipe must stay ownership-neutral TRANSFER — never LOOT/Lost on enter. */
static boolean xg(Event /* event */a) {
 return a != null;
}

static String ajw(Event /* event */a) {
 return a == null ? msg("ar") : a.note;
}
/** Ordered outcomes and shared compiled predicates; region-only fallbacks come last. */
static final java.util.Map<String, Ag.P[][]> P = Ak.r("d26");
static final java.util.Map<String, Event> R = new java.util.LinkedHashMap<>();
static {
 for (String[] a : Ak.x("d26")) if (a[0].startsWith("m")) R.put(a[0], Event.valueOf(a[2]));
}
static Event a1(String a) { return a1(a, -1); }
static Event a1(String a, int b) {
 String c = Ag.lower(a);
 for (var d : R.entrySet()) {
  if ((!d.getKey().endsWith(".lms") || b >= 0 && MinigameRegionHints.xh(b))
  && Ag.has(c, P.get(d.getKey()))) return d.getValue();
 }
 return null;
}

}
