package com.gpmanager;
import java.util.Map;
/**
* Conservative region hints for LMS and Gauntlet instances when menu text is sparse.
* Live producers pass {@link net.runelite.api.coords.WorldPoint#getRegionID()}.
*/
class MinigameRegionHints {
/**
* Region id to kind: "lms" for Last Man Standing instances (not exhaustive; menu and chat stay
* primary) and "neutral" for instances that store the player's gear on entry and restore it on
* exit with no exit click (The Gauntlet and the Corrupted Gauntlet; see minigame-regions.tsv).
*/
static final Map<Integer, String> KINDS = GameData.byId("d13");
/** True inside an instance whose inventory changes are never profit or loss. */
static boolean isNeutralZoneRegion(int regionId) {
 return "neutral".equals(KINDS.get(regionId));
}

static boolean isLmsRegion(int regionId) {
 return "lms".equals(KINDS.get(regionId));
}
}
