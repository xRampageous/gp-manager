package com.gpmanager;
import static com.gpmanager.Ak.msg;
import com.gpmanager.SavedState.*;
import java.util.*;
import net.runelite.api.gameval.ItemID;
import lombok.*;
import static java.util.Collections.*;
import static com.gpmanager.Dv.*;
/**
* PvM death → reclaim lifecycle. A separate held-stack snapshot is intersected with
* measured negative inventory flows to establish which items actually left the player.
* Those exact quantities remain ownership-neutral until reclaimed or the gravestone timer
* expires.
*
* <p>Fees are never inferred here; the engine can book only an observed carried-coin loss.
* A timer expiry records an informational audit event and never creates an item loss.
*
* <p>Pure tick-counted state; the engine owns the single instance.
*/
class DeathReclaimLifecycle {
/** Ticks a gravestone retains items. */
static final int AWAIT_TICKS = 1_500; // 15 minutes
/** Keep a grave interaction armed through the usual open/confirm/claim sequence. */
static final int MIN_RECLAIM_ARM_TICKS = 200; // 2 minutes
/** Bound delayed death-wipe reconciliation so later ordinary losses cannot match it. */
static final int DEATH_WIPE_WINDOW_TICKS = 100; // 1 minute
@Getter(AccessLevel.PACKAGE)
boolean awaitingReclaim;
int deathWipeTicks;
int ageTicks;
int reclaimTicks;
BossRetrievalCatalogue.Service reclaimService;
/** Stacks held at death not yet matched to a measured loss; null when no wipe is pending. */
Map<Integer, Long> carriedAtDeath;
/** Measured grave contents still outstanding, with the names used by the expiry note. */
Map<Integer, Long> deathItems = new HashMap<>();
Map<Integer, String> names = new HashMap<>();
/** Local unsafe PvM death: the snapshot is ownership evidence, not presentation evidence. */
void ace() {
 ace(null);
}

/** Local unsafe PvM death: the snapshot is ownership evidence, not presentation evidence. */
void ace(Map<Integer, Long> /* heldItems */a) {
 // A second death moves outstanding items to the new gravestone. Keep their
 // quantities and restart the single gravestone timer with the new grave.
 if (!awaitingReclaim) deathItems.clear();
 awaitingReclaim = true;
 // Missing canonical evidence is not permission to neutralize arbitrary losses.
 Map<Integer, Long> /* held */b = positiveEntries(a);
 carriedAtDeath = b.isEmpty() ? null : b;
 deathWipeTicks = carriedAtDeath == null ? 0 : DEATH_WIPE_WINDOW_TICKS;
 ageTicks = 0;
 reclaimTicks = 0;
 reclaimService = null;
 pn();
}

/** True while waiting for the measured death inventory/equipment removal. */
boolean wh() {
 return awaitingReclaim && carriedAtDeath != null;
}

/** Stop the short death-wipe whitelist when stronger later evidence owns the loss. */
void nl() {
 carriedAtDeath = null;
 deathWipeTicks = 0;
 pn();
}

/** Consume death-time candidates whose later loss is explicitly action-attributed. */
void ry(Map<Integer, Long> /* actionLosses */a) {
 if (carriedAtDeath == null || Ag.empty(a)) return;
 positiveEntries(a).forEach((/* itemId */b, /* lost */c) -> take(carriedAtDeath, b, c));
 rn();
}

/**
* Intersect a settled negative-flow batch with the stacks held at death. The returned
* quantities identify the exact negative flow portions that may be labelled as the
* ownership-neutral death transfer.
*/
Map<Integer, Long> onDeathItemsRemoved(List<Ab> /* flows */b) {
 if (!wh()) return emptyMap();
 var /* removed */c = new HashMap<Integer, Long>();
 for (Ab /* flow */d : b == null ? Collections.<Ab>emptyList() : b) {
  if (d == null || d.quantityDelta >= 0L || d.quantityDelta == Long.MIN_VALUE
  // A live retrieval interaction makes measured coin loss a possible
  // fee; do not consume it as a delayed death wipe.
  || d.itemId == ItemID.COINS && xk()) {
   continue;
  }
  long /* matched */e = take(carriedAtDeath, d.itemId, -d.quantityDelta);
  if (e <= 0L) continue;
  c.put(d.itemId, e);
  // Coins can be part of the measured death wipe transfer, but they are
  // never reclaim whitelist items: a later coin loss may be the observed fee.
  if (d.itemId != ItemID.COINS) {
   deathItems.merge(d.itemId, e, Ae::safeAdd);
   if (d.itemName != null && !d.itemName.trim().isEmpty()) names.put(d.itemId, d.itemName.trim());
  }
 }
 rn();
 return c.isEmpty() ? emptyMap() : unmodifiableMap(c);
}

/**
* Match a positive return against the still-outstanding measured death quantity.
* Returns zero when the item was not part of the actual settled death wipe.
*/
long aam(int /* itemId */b, long /* quantity */c) {
 long /* matched */d = take(deathItems, b, c);
 if (d > 0L && deathItems.isEmpty()) {
  // All measured grave contents have returned. Remaining stacks from the
  // death-time snapshot were kept and can no longer be a delayed wipe.
  nl();
 }
 return d;
}

long outstandingItemCount() {
 long /* count */a = 0L;
 for (long /* quantity */b : deathItems.values()) a = Ae.safeAdd(a, b);
 return a;
}

int ageTicks() {
 return ageTicks;
}

/**
* Player interacted with a retrieval service. A fresh Grave / Gravestone click can
* always re-arm while this local death remains active.
*
* @return true only when a local PvM death is awaiting reclaim
*/
boolean aca(BossRetrievalCatalogue.Service /* service */a, int /* ticks */b) {
 if (a == null || !awaitingReclaim) return false;
 reclaimService = a;
 reclaimTicks = Math.max(MIN_RECLAIM_ARM_TICKS, b);
 return true;
}

boolean xk() {
 return reclaimTicks > 0 && reclaimService != null;
}

/** One inventory/game tick; defers gravestone expiry while a snapshot is unsettled. */
String tick(boolean /* deferGravestoneExpiry */a) {
 if (awaitingReclaim && ageTicks < Integer.MAX_VALUE) ageTicks++;
 if (reclaimTicks > 0 && --reclaimTicks == 0) reclaimService = null;
 if (deathWipeTicks > 0 && --deathWipeTicks == 0) nl();
 if (a || !awaitingReclaim || ageTicks < AWAIT_TICKS) return null;
 String /* expired */b = deathItems.isEmpty() ? null : sk();
 reset();
 return b;
}

/** The expiry audit explanation: the outstanding items, sorted by name. */
String sk() {
 var /* labels */a = new ArrayList<String>();
 deathItems.forEach((/* itemId */b, /* quantity */c) -> {
  String /* name */e = names.getOrDefault(b, "item #" + b);
  a.add(c == 1L ? e : e + " ×" + String.format(Locale.ROOT, "%,d", c));
 });
 a.sort(String.CASE_INSENSITIVE_ORDER);
 return msg("a6") + outstandingItemCount() + msg("a5")
 + String.join(", ", a) + msg("aq");
}

void reset() {
 awaitingReclaim = false;
 deathWipeTicks = 0;
 ageTicks = 0;
 reclaimTicks = 0;
 reclaimService = null;
 carriedAtDeath = null;
 deathItems.clear();
 names.clear();
}

/**
* Persistable physical continuity for restart, or null when no death awaits reclaim.
* Item identity and quantity only; the live reclaim interaction and presentation labels
* are deliberately not persisted.
*/
Bw snapshot() {
 List<Bg> /* outstanding */a = items(deathItems);
 List<Bg> /* held */b = items(carriedAtDeath);
 return !awaitingReclaim || a.isEmpty() && b.isEmpty() ? null
 : new Bw(a, b, deathWipeTicks, ageTicks);
}

static List<Bg> items(Map<Integer, Long> /* quantities */a) {
 var /* items */b = new ArrayList<Bg>();
 positiveEntries(a).forEach((/* itemId */c, /* quantity */d) -> b.add(new Bg(c, d)));
 return b;
}

/**
* Restore persisted physical continuity after a restart. The reclaimed quantities are matched
* exactly as before; the delayed-wipe window resumes its remaining bounded ticks and the
* gravestone timer keeps its age. A live reclaim interaction is never restored.
*/
void restore(Bw /* pending */a) {
 reset();
 if (a == null || a.isEmpty()) return;
 for (Bg /* item */b : a.getOutstandingItems()) {
  if (b.getQuantity() > 0L) deathItems.merge(b.itemId, b.getQuantity(), Ae::safeAdd);
 }
 var /* held */c = new HashMap<Integer, Long>();
 for (Bg /* item */d : a.getHeldAtDeath()) {
  c.merge(d.itemId, d.getQuantity(), Ae::safeAdd);
 }
 deathWipeTicks = Math.min(DEATH_WIPE_WINDOW_TICKS, a.getWipeTicksRemaining());
 carriedAtDeath = deathWipeTicks > 0 && !c.isEmpty() ? c : null;
 deathWipeTicks = carriedAtDeath == null ? 0 : deathWipeTicks;
 ageTicks = a.getGravestoneAgeTicks();
 awaitingReclaim = carriedAtDeath != null || !deathItems.isEmpty();
}

/** Adopt the physical continuity of a staged engine during an owner install. */
void auc(DeathReclaimLifecycle /* other */a) {
 reset();
 if (a == null) return;
 awaitingReclaim = a.awaitingReclaim;
 deathWipeTicks = a.deathWipeTicks;
 ageTicks = a.ageTicks;
 reclaimTicks = a.reclaimTicks;
 reclaimService = a.reclaimService;
 carriedAtDeath = a.carriedAtDeath == null ? null : new HashMap<>(a.carriedAtDeath);
 deathItems.putAll(a.deathItems);
 names.putAll(a.names);
}

void rn() {
 if (carriedAtDeath != null && carriedAtDeath.isEmpty()) nl();
 pn();
}

void pn() {
 if (awaitingReclaim && carriedAtDeath == null && deathItems.isEmpty()) reset();
}
}
