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
BiConsumer<String, Boolean> presentation = (/* name */b, /* combat */c) -> {};
void mf(BooleanSupplier /* ready */a, BiConsumer<String, Boolean> /* changed */b) {
 ingestion = a == null ? () -> false : a;
 presentation = b == null ? (/* name */c, /* combat */d) -> {} : b;
}

void clear() {
 owner = "";
 lastTargetAt = 0L;
 set("", false);
}

/** True while the active owner's target positively identifies PvM (a combat NPC). */
boolean vi() {
 return ready() && combatNpc && (releasedAt == 0L || Ae.now() - releasedAt < RELEASE_GRACE_MILLIS);
}

boolean ready() {
 Ad /* session */a = engine.getActiveSession();
 String /* aml */b = a == null ? "" : a.getId();
 if (!b.equals(owner)) {
  clear();
  owner = b;
 }
 if (b.isEmpty() || !config.autoActivityDetection() || !ingestion.getAsBoolean()) {
  set("", false);
  return false;
 }
 return true;
}

void onInteractingChanged(InteractingChanged /* event */a) {
 if (!ready() || a == null || a.getSource() != client.getLocalPlayer()) return;
 Player /* local */b = client.getLocalPlayer();
 if (b == null || b.isDead()) {
  set("", false);
  return;
 }
 Actor /* target */c = a.getTarget();
 if (c instanceof NPC) {
  NPC /* npc */d = (NPC) c;
  set(d.getName(), d.getCombatLevel() > 0);
 } else if (c instanceof Player) {
  // Opponent names are never display evidence; PvP reads as its activity.
  set("", false);
 } else {
  releasedAt = Ae.now();
 }
}

/** Scenery is never an activity; any other intentional action cancels a non-combat target. */
void onMenuOptionClicked(MenuOptionClicked /* event */a) {
 if (!ready() || a == null || a.isConsumed() || a.getMenuAction() == null) return;
 String /* type */b = a.getMenuAction().name();
 MenuEntry /* menuEntry */c = a.getMenuEntry();
 String /* clicked */d = c == null || c.getTarget() == null ? "" : c.getTarget().trim();
 if (combatNpc && !d.isEmpty() && d.equalsIgnoreCase(name)) {
  // Still attacking the same target: keep the activity label alive.
  lastTargetAt = Ae.now();
 }
 if (!(b.startsWith("GAME_OBJECT_") && b.endsWith("_OPTION")) && !combatNpc) set("", false);
}

/** Release grace expiry for non-combat targets; a combat target lives out the label timeout. */
void onGameTick() {
 long /* now */a = Ae.now();
 if (releasedAt != 0L && a - releasedAt >= RELEASE_GRACE_MILLIS && !combatNpc) {
  set("", false);
  return;
 }
 if (combatNpc && name != null && !name.isEmpty() && lastTargetAt != 0L
 && a - lastTargetAt >= Math.max(1_000L, config.activityLabelSeconds() * 1_000L)) {
  set("", false);
 }
}

void set(String /* next */a, boolean /* combat */b) {
 String n = a == null ? "" : a.trim();
 releasedAt = 0L;
 if (!n.isEmpty()) lastTargetAt = Ae.now();
 if (n.equals(name) && b == combatNpc) return;
 name = n;
 combatNpc = b;
 presentation.accept(n, b);
}
}
