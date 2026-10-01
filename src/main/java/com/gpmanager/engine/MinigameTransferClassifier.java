package com.gpmanager;
import static com.gpmanager.GameData.msg;
import java.util.Locale;
import lombok.AllArgsConstructor;
import static com.gpmanager.ModelText.has;
/**
* LMS invent wipe, raid private/shared bags, GIM shared storage — all ownership-neutral
* TRANSFER / baseline refresh. Never book full invent as Lost/LOOT on enter/exit.
*/
class MinigameTransferClassifier {
@AllArgsConstructor
enum Event {
LMS_ENTER(msg("dk")), LMS_EXIT(msg("dl")), RAID_BAG_STORE("Raid bag store"), RAID_BAG_WITHDRAW("Raid bag withdraw"),
RAID_BAG_CLEAR(msg("dm")), GIM_SHARED_DEPOSIT(msg("dn")), GIM_SHARED_WITHDRAW(msg("do"));
final String note;
}

static Event classify(String noteOrRegion) {
return classify(noteOrRegion, -1);
}

/** Classifies a menu/chat note; inside an LMS region ({@code regionId}) entering is implied. */
static Event classify(String noteOrRegion, int regionId) {
String lower = noteOrRegion == null ? "" : noteOrRegion.trim().toLowerCase(Locale.ROOT);
boolean leave = has(lower, "leave", "exit");
boolean take = has(lower, "withdraw", "take");
if (has(lower, "last man standing", "lms")) {
if (leave || lower.contains("restore")) return Event.LMS_EXIT;
if (has(lower, "enter", "join", "wipe", "loadout")) return Event.LMS_ENTER;
}
if (has(lower, "raid bag", "private storage", "cox storage", "toa storage")) {
return has(lower, "clear", "end", "wipe") ? Event.RAID_BAG_CLEAR
: take ? Event.RAID_BAG_WITHDRAW : Event.RAID_BAG_STORE;
}
if (has(lower, "group storage", "shared storage", "gim")) {
return take ? Event.GIM_SHARED_WITHDRAW : Event.GIM_SHARED_DEPOSIT;
}
if (regionId >= 0 && MinigameRegionHints.isLmsRegion(regionId)) {
return leave ? Event.LMS_EXIT : lower.isEmpty() || has(lower, "enter", "join", "wipe") ? Event.LMS_ENTER : null;
}
return null;
}

/** LMS invent wipe must stay ownership-neutral TRANSFER — never LOOT/Lost on enter. */
static boolean isOwnershipNeutral(Event event) {
return event != null;
}

static String transferNote(Event event) {
return event == null ? msg("ar") : event.note;
}
}
