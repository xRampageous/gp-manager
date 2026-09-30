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
* expires. The explanatory {@link Ch} is intentionally not used here.
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
final Map<Integer, Long> deathItems = new HashMap<>();
final Map<Integer, String> names = new HashMap<>();
/** Local unsafe PvM death: the snapshot is ownership evidence, not presentation evidence. */
void ace() {
ace(null);
}
/** Local unsafe PvM death: the snapshot is ownership evidence, not presentation evidence. */
void ace(Map<Integer, Long> heldItems) {
// A second death moves outstanding items to the new gravestone. Keep their
// quantities and restart the single gravestone timer with the new grave.
if (!awaitingReclaim) {
deathItems.clear();
}
awaitingReclaim = true;
// Missing canonical evidence is not permission to neutralize arbitrary losses.
Map<Integer, Long> held = positiveEntries(heldItems);
carriedAtDeath = held.isEmpty() ? null : held;
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
void ry(Map<Integer, Long> actionLosses) {
if (carriedAtDeath == null || Ag.empty(actionLosses)) {
return;
}
positiveEntries(actionLosses).forEach((itemId, lost) -> take(carriedAtDeath, itemId, lost));
rn();
}
/**
* Intersect a settled negative-flow batch with the stacks held at death. The returned
* quantities identify the exact negative flow portions that may be labelled as the
* ownership-neutral death transfer.
*/
Map<Integer, Long> onDeathItemsRemoved(List<Ab> flows) {
if (!wh()) {
return emptyMap();
}
var removed = new HashMap<Integer, Long>();
for (Ab flow : flows == null ? Collections.<Ab>emptyList() : flows) {
if (flow == null || flow.quantityDelta >= 0L || flow.quantityDelta == Long.MIN_VALUE
// A live retrieval interaction makes measured coin loss a possible
// fee; do not consume it as a delayed death wipe.
|| flow.itemId == ItemID.COINS && xk()) {
continue;
}
long matched = take(carriedAtDeath, flow.itemId, -flow.quantityDelta);
if (matched <= 0L) {
continue;
}
removed.put(flow.itemId, matched);
// Coins can be part of the measured death wipe transfer, but they are
// never reclaim whitelist items: a later coin loss may be the observed fee.
if (flow.itemId != ItemID.COINS) {
deathItems.merge(flow.itemId, matched, Ae::safeAdd);
if (flow.itemName != null && !flow.itemName.trim().isEmpty()) {
names.put(flow.itemId, flow.itemName.trim());
}
}
}
rn();
return removed.isEmpty() ? emptyMap() : unmodifiableMap(removed);
}
/**
* Match a positive return against the still-outstanding measured death quantity.
* Returns zero when the item was not part of the actual settled death wipe.
*/
long aam(int itemId, long quantity) {
long matched = take(deathItems, itemId, quantity);
if (matched > 0L && deathItems.isEmpty()) {
// All measured grave contents have returned. Remaining stacks from the
// death-time snapshot were kept and can no longer be a delayed wipe.
nl();
}
return matched;
}
long outstandingItemCount() {
long count = 0L;
for (long quantity : deathItems.values()) {
count = Ae.safeAdd(count, quantity);
}
return count;
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
boolean aca(BossRetrievalCatalogue.Service service, int ticks) {
if (service == null || !awaitingReclaim) {
return false;
}
reclaimService = service;
reclaimTicks = Math.max(MIN_RECLAIM_ARM_TICKS, ticks);
return true;
}
boolean xk() {
return reclaimTicks > 0 && reclaimService != null;
}
/** One inventory/game tick; defers gravestone expiry while a snapshot is unsettled. */
String tick(boolean deferGravestoneExpiry) {
if (awaitingReclaim && ageTicks < Integer.MAX_VALUE) {
ageTicks++;
}
if (reclaimTicks > 0 && --reclaimTicks == 0) {
reclaimService = null;
}
if (deathWipeTicks > 0 && --deathWipeTicks == 0) {
nl();
}
if (deferGravestoneExpiry || !awaitingReclaim || ageTicks < AWAIT_TICKS) {
return null;
}
String expired = deathItems.isEmpty() ? null : sk();
reset();
return expired;
}
/** The expiry audit explanation: the outstanding items, sorted by name. */
String sk() {
var labels = new ArrayList<String>();
deathItems.forEach((itemId, quantity) -> {
String name = names.getOrDefault(itemId, "item #" + itemId);
labels.add(quantity == 1L ? name : name + " ×" + String.format(Locale.ROOT, "%,d", quantity));
});
labels.sort(String.CASE_INSENSITIVE_ORDER);
return "The gravestone timer expired with " + outstandingItemCount() + " item(s) outstanding: "
+ String.join(", ", labels) + msg("aq");
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
List<Bg> outstanding = items(deathItems);
List<Bg> held = items(carriedAtDeath);
return !awaitingReclaim || outstanding.isEmpty() && held.isEmpty() ? null
: new Bw(outstanding, held, deathWipeTicks, ageTicks);
}
static List<Bg> items(Map<Integer, Long> quantities) {
var items = new ArrayList<Bg>();
positiveEntries(quantities).forEach((itemId, quantity) ->
items.add(new Bg(itemId, quantity)));
return items;
}
/**
* Restore persisted physical continuity after a restart. The reclaimed quantities are matched
* exactly as before; the delayed-wipe window resumes its remaining bounded ticks and the
* gravestone timer keeps its age. A live reclaim interaction is never restored.
*/
void restore(Bw pending) {
reset();
if (pending == null || pending.isEmpty()) {
return;
}
for (Bg item : pending.getOutstandingItems()) {
if (item.getQuantity() > 0L) {
deathItems.merge(item.itemId, item.getQuantity(), Ae::safeAdd);
}
}
var held = new HashMap<Integer, Long>();
for (Bg item : pending.getHeldAtDeath()) {
held.merge(item.itemId, item.getQuantity(), Ae::safeAdd);
}
deathWipeTicks = Math.min(DEATH_WIPE_WINDOW_TICKS, pending.getWipeTicksRemaining());
carriedAtDeath = deathWipeTicks > 0 && !held.isEmpty() ? held : null;
deathWipeTicks = carriedAtDeath == null ? 0 : deathWipeTicks;
ageTicks = pending.getGravestoneAgeTicks();
awaitingReclaim = carriedAtDeath != null || !deathItems.isEmpty();
}
/** Adopt the physical continuity of a staged engine during an owner install. */
void auc(DeathReclaimLifecycle other) {
reset();
if (other == null) {
return;
}
awaitingReclaim = other.awaitingReclaim;
deathWipeTicks = other.deathWipeTicks;
ageTicks = other.ageTicks;
reclaimTicks = other.reclaimTicks;
reclaimService = other.reclaimService;
carriedAtDeath = other.carriedAtDeath == null ? null : new HashMap<>(other.carriedAtDeath);
deathItems.putAll(other.deathItems);
names.putAll(other.names);
}
void rn() {
if (carriedAtDeath != null && carriedAtDeath.isEmpty()) {
nl();
}
pn();
}
void pn() {
if (awaitingReclaim && carriedAtDeath == null && deathItems.isEmpty()) {
reset();
}
}
}
