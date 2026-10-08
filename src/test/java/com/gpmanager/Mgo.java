package com.gpmanager;
import static com.gpmanager.Ak.msg;
import lombok.AllArgsConstructor;
import static com.gpmanager.Ag.has;
/**
* LMS invent wipe, raid private/shared bags, GIM shared storage — all ownership-neutral
* TRANSFER / baseline refresh. Never book full invent as Lost/LOOT on enter/exit.
*/
class Mgo {
@AllArgsConstructor
enum Event {
 LMS_ENTER(msg("dk")), LMS_EXIT(msg("dl")), RAID_BAG_STORE("Raid bag store"), RAID_BAG_WITHDRAW("Raid bag withdraw"),
 RAID_BAG_CLEAR(msg("dm")), GIM_SHARED_DEPOSIT(msg("dn")), GIM_SHARED_WITHDRAW(msg("do"));
 String note;
}

static Event classify(String /* noteOrRegion */a) {
 return classify(a, -1);
}

/** Classifies a menu/chat note; inside an LMS region ({@code regionId}) entering is implied. */
static Event classify(String /* noteOrRegion */a, int /* regionId */b) {
 String /* lower */c = Ag.lower(a);
 boolean /* leave */d = has(c, "leave", "exit");
 boolean /* take */e = has(c, "withdraw", "take");
 if (has(c, "last man standing", "lms")) {
  if (d || c.contains("restore")) return Event.LMS_EXIT;
  if (has(c, "enter", "join", "wipe", "loadout")) return Event.LMS_ENTER;
 }
 if (has(c, "raid bag", "private storage", "cox storage", "toa storage")) {
  return has(c, "clear", "end", "wipe") ? Event.RAID_BAG_CLEAR
  : e ? Event.RAID_BAG_WITHDRAW : Event.RAID_BAG_STORE;
 }
 if (has(c, "group storage", "shared storage", "gim")) {
  return e ? Event.GIM_SHARED_WITHDRAW : Event.GIM_SHARED_DEPOSIT;
 }
 if (b >= 0 && MinigameRegionHints.xh(b)) {
  return d ? Event.LMS_EXIT : c.isEmpty() || has(c, "enter", "join", "wipe") ? Event.LMS_ENTER : null;
 }
 return null;
}

/** LMS invent wipe must stay ownership-neutral TRANSFER — never LOOT/Lost on enter. */
static boolean xg(Event /* event */a) {
 return a != null;
}

static String ajw(Event /* event */a) {
 return a == null ? msg("ar") : a.note;
}
}
