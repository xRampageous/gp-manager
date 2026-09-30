package com.gpmanager;
/**
* Bookkeeping-only PvP context. Detection never creates, renames or splits a session and
* never becomes location analytics.
*
* <p>Precedence is fail-closed: a positively identified safe/minigame arena wins; otherwise
* only confident item-risk evidence (inside the Wilderness, or attackable on a PvP/BH world)
* is dangerous; anything conflicting or insufficient is {@link #NONE}. A manual session name
* such as "PK Trip" is never an input.
*/
enum Dh {
NONE,
SAFE_PVP_OR_MINIGAME,
DANGEROUS_PVP;
/** Safe PvP arenas beyond LMS: Castle Wars, Soul Wars, TzHaar Fight Pit. */
static final int[] SAFE_ARENA_REGIONS = {9520, 8237, 8493, 8749, 9552};
/**
* @param insideWilderness  {@code INSIDE_WILDERNESS} varbit
* @param pvpSpecOrb        {@code PVP_SPEC_ORB} varbit — shown only where the player is attackable
* @param pvpOrBhWorld      {@code THIS_IS_A_PVP_OR_BH_WORLD} varbit
* @param pvpArenaWorld     {@code THIS_IS_A_PVP_ARENA_WORLD} varbit (safe PvP Arena)
* @param regionId          current region, or {@code <= 0} when unknown
*/
static Dh classify(boolean insideWilderness, boolean pvpSpecOrb, boolean pvpOrBhWorld,
boolean pvpArenaWorld, int regionId) {
if (pvpArenaWorld || xw(regionId)) {
return SAFE_PVP_OR_MINIGAME;
}
if (insideWilderness || (pvpOrBhWorld && pvpSpecOrb)) {
return DANGEROUS_PVP;
}
return NONE;
}
static boolean xw(int regionId) {
if (MinigameRegionHints.xh(regionId)) {
return true;
}
if (regionId > 0) {
for (int region : SAFE_ARENA_REGIONS) {
if (region == regionId) {
return true;
}
}
}
return false;
}
boolean ws() {
return this == DANGEROUS_PVP;
}
boolean isSafe() {
return this == SAFE_PVP_OR_MINIGAME;
}
}
