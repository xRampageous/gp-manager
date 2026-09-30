package com.gpmanager;
import lombok.RequiredArgsConstructor;
import javax.inject.*;
import java.util.function.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
/**
* Client-thread interaction target for the active owner: the NPC being fought or interacted with,
* with a short release grace so a swing gap does not blank it. Scenery and opponent names never
* publish — the activity header titles NPCs only. The backend uses it for one fact — whether a
* death happened in positive PvM context — and publishes the name to presentation through
* a presentation callback.
*/
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class InteractionContextTracker {
static final long RELEASE_GRACE_MILLIS = 4_000L;
final Client client;
final Am engine;
final GpManagerConfig config;
String owner = "";
String name = "";
boolean combatNpc;
long releasedAt;
long lastTargetAt;
BooleanSupplier ingestion = () -> false;
BiConsumer<String, Boolean> presentation = (name, combat) -> {};
void mf(BooleanSupplier ready, BiConsumer<String, Boolean> changed) {
ingestion = ready == null ? () -> false : ready;
presentation = changed == null ? (name, combat) -> {} : changed;
}
void clear() {
owner = "";
lastTargetAt = 0L;
set("", false);
}
/** True while the active owner's target positively identifies PvM (a combat NPC). */
boolean vi() {
return ready() && combatNpc && (releasedAt == 0L || System.currentTimeMillis() - releasedAt < RELEASE_GRACE_MILLIS);
}
boolean ready() {
Ad session = engine.getActiveSession();
String aml = session == null ? "" : session.getId();
if (!aml.equals(owner)) {
clear();
owner = aml;
}
if (aml.isEmpty() || !config.autoActivityDetection() || !ingestion.getAsBoolean()) {
set("", false);
return false;
}
return true;
}
void onInteractingChanged(InteractingChanged event) {
if (!ready() || event == null || event.getSource() != client.getLocalPlayer()) {
return;
}
Player local = client.getLocalPlayer();
if (local == null || local.isDead()) {
set("", false);
return;
}
Actor target = event.getTarget();
if (target instanceof NPC) {
NPC npc = (NPC) target;
set(npc.getName(), npc.getCombatLevel() > 0);
} else if (target instanceof Player) {
// Opponent names are never display evidence; PvP reads as its activity.
set("", false);
} else {
releasedAt = System.currentTimeMillis();
}
}
/** Scenery is never an activity; any other intentional action cancels a non-combat target. */
void onMenuOptionClicked(MenuOptionClicked event) {
if (!ready() || event == null || event.isConsumed() || event.getMenuAction() == null) {
return;
}
String type = event.getMenuAction().name();
MenuEntry menuEntry = event.getMenuEntry();
String clicked = menuEntry == null || menuEntry.getTarget() == null ? ""
: menuEntry.getTarget().trim();
if (combatNpc && !clicked.isEmpty() && clicked.equalsIgnoreCase(name)) {
// Still attacking the same target: keep the activity label alive.
lastTargetAt = System.currentTimeMillis();
}
if (!(type.startsWith("GAME_OBJECT_") && type.endsWith("_OPTION")) && !combatNpc) {
set("", false);
}
}
/** Release grace expiry for non-combat targets; a combat target lives out the label timeout. */
void onGameTick() {
long now = System.currentTimeMillis();
if (releasedAt != 0L && now - releasedAt >= RELEASE_GRACE_MILLIS && !combatNpc) {
set("", false);
return;
}
if (combatNpc && name != null && !name.isEmpty() && lastTargetAt != 0L
&& now - lastTargetAt >= Math.max(1_000L, config.activityLabelSeconds() * 1_000L)) {
set("", false);
}
}
void set(String next, boolean combat) {
String n = next == null ? "" : next.trim();
releasedAt = 0L;
if (!n.isEmpty()) {
lastTargetAt = System.currentTimeMillis();
}
if (n.equals(name) && combat == combatNpc) {
return;
}
name = n;
combatNpc = combat;
presentation.accept(n, combat);
}
}
