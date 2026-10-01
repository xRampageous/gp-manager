package com.gpmanager;
import lombok.RequiredArgsConstructor;
import java.util.*;
import static java.util.Locale.*;
import static java.lang.Math.*;
import static com.gpmanager.TransactionType.*;
/**
* The HUD+ item tray: what the current streak received, claimed, lost or traded. A streak is one
* run of the same work: fighting one NPC, or one skill. Attacking another NPC, another kind of
* work, or a quiet gap starts the next streak, so one NPC's loot never lands on another's tray. Fed on the
* client thread by kills, bookings and reward sightings, read on the EDT by {@link HudBuilder}.
* Presentation only; nothing here books or values money, it only mirrors the bookings.
*/
class HudTray {
/** A single drop worth this much lights its row gold and raises a moment. */
static final long BIG_DROP = 10_000_000L;
/** Bounded presentation mirror of canonical receipts eligible for own-drop recovery. */
static final int MAX_DROP_RECEIPTS = 64;
enum State {
RECEIVED(""), PENDING("pending"), CLAIMED("claimed"), LOST("lost"), RECOVERED("recovered"), TRADED("traded");
final String tag;
State(String tag) {
this.tag = tag;
}
}

/** One tray entry; quantities of the same item and state add up. */
@RequiredArgsConstructor
static class Entry {
final int itemId;
final String name;
final State state;
final long unitPrice;
long quantity;
long value;
boolean gold;
long born;
}

static class DropReceipt {
final Transaction transaction;
final int itemId;
long lastQuantity;
long lastValue;
DropReceipt(Transaction transaction, int itemId) {
this.transaction = transaction;
this.itemId = itemId;
}
}

final Map<String, Entry> entries = new LinkedHashMap<>();
/** Receipt references are presentation-only and live no longer than the tray streak/session. */
final List<DropReceipt> dropReceipts = new ArrayList<>();
/** The NPC this streak is killing ("" before its first kill), and its kills. */
String source = "";
int kills;
long firstKillAt;
long lastKillAt;
long lastEventAt;
/** When an item last arrived: the tray opens on items only, never on a kill or an attack. */
long shownAt;
/** Changes with every new streak (and on clear); HUD+ rebases the streak's Net and width on it. */
int tripId;
String label = "Looted";
String bigDrop = "";
/** What the newest booking added (counted rows only): the chip beside Net. */
long lastDelta;
long lastDeltaAt;
/** The session's most valuable single received drop. */
String bestDrop = "";
long bestDropValue;
long bigDropAt;
/** A booking settled; mirror its visible item flows onto the tray. */
synchronized void booked(Transaction transaction, long now, boolean keepSession) {
if (transaction == null) return;
if (!transaction.isCounted() || transaction.getType() == TransactionType.TRANSFER
|| transaction.getCorrection() == Correction.TRANSFER || transaction.getType() == UNCERTAIN
// Owner 2026-09-28: eating and drinking stay off HUD+ (a sip's "(3)" is no drop).
|| transaction.getActionKind() == ActionKind.EAT || transaction.getActionKind() == ActionKind.DRINK
|| ActionEvidence.isDoseOrPartialConsumeDelta(transaction.getFlows())) {
return;
}
boolean market = transaction.getContext() == Context.MARKET || transaction.getType() == TRADE;
boolean claimed = transaction.getActionKind() == ActionKind.DEFERRED_CLAIM || !transaction.getSourceClaimId().isEmpty();
if (!source.isEmpty() && transaction.getNote().startsWith("Loot from ")
&& !transaction.getActivityName().equalsIgnoreCase(source)) {
// Another NPC's leftover drop, picked up after this streak began: it counts in Net, not here.
return;
}
boolean reclaim = (transaction.getActivityName() + " " + transaction.getNote()).toLowerCase(ROOT).contains("reclaim");
// Only deaths, drops and destroys ride the tray as lost; other item use is routine.
String dropped = ModelText.has(transaction.getNote(), "Dropped", "Destroyed") ? transaction.getNote() : "";
boolean lost = transaction.getAutomaticType() == PK_DEATH_LOSS
|| transaction.getContext() == Context.PK_DEATH || !dropped.isEmpty();
boolean any = false;
long delta = 0L;
for (Flow flow : transaction.getFlows()) {
if (flow == null || flow.quantityDelta == 0L) continue;
boolean gain = flow.quantityDelta > 0L;
// Routine rune/ammo/charge use never rides the tray, but a pickup is a drop
// like any other (owner 2026-09-29).
if (!gain && routine(flow.itemName)) continue;
if (!gain && (market || !lost)) continue;
if (!any) {
// A drop joins the running trip; on an empty tray it reads "Dropped" (owner 2026-09-28).
event(now, keepSession, claimed ? "Claimed" : !dropped.isEmpty() ? null
: labelFor(transaction.getActivityName()), null);
if (!dropped.isEmpty() && entries.isEmpty()) label = dropped;
shownAt = now;
any = true;
}
int id = flow.itemId;
long quantity = abs(flow.quantityDelta);
State state = !gain ? State.LOST : market ? State.TRADED : reclaim ? State.RECOVERED
: claimed ? State.CLAIMED : State.RECEIVED;
long born = 0L;
if (state == State.RECEIVED || state == State.CLAIMED) {
// A claim turns its pending row into Claimed in place, keeping its first-seen identity.
Entry pending = entries.get(key(State.PENDING, id));
if (pending != null && take(State.PENDING, id, quantity) > 0L) {
state = State.CLAIMED;
born = pending.born;
}
}
Entry entry = add(id, flow.itemName, state, flow.unitPrice, quantity, flow.valueDelta, now, born);
delta += state == State.TRADED ? 0L : flow.valueDelta;
if (gain && state != State.TRADED && flow.valueDelta > bestDropValue) {
bestDropValue = flow.valueDelta;
bestDrop = flow.itemName;
}
if (gain && state != State.TRADED && flow.valueDelta >= BIG_DROP) {
entry.gold = true;
bigDrop = flow.itemName;
bigDropAt = now;
}
}
if (any && delta != 0L) {
lastDelta = delta;
lastDeltaAt = now;
}
rememberDropReceipt(transaction);
}

/**
* A reward window reported loot it is holding; nothing is counted until it settles. Ground
* loot never comes here: the tray lists what was received, so a pickup appears once, in place.
*/
synchronized void observed(int itemId, String name, long quantity, long unitPrice, long now, boolean keepSession) {
if (quantity <= 0L) return;
event(now, keepSession, null, null);
shownAt = now;
add(itemId, name, State.PENDING, unitPrice, quantity, unitPrice * quantity, now, 0L);
}

/** When the tray folds after its last item. */
synchronized long foldAt(long stayMillis) {
return shownAt + stayMillis;
}

/** Attacking another NPC starts its streak at once; the tray stays folded until loot arrives. */
synchronized void engage(String npc, boolean keepSession) {
// Switching targets keeps the previous streak's loot on the tray; the new target's
// first kill refreshes it to the new streak (owner 2026-10-01).
}

/** One NPC loot event is one kill; a kill of another NPC starts that NPC's streak. */
synchronized void kill(String npc, long now, boolean keepSession) {
String name = npc == null ? "" : npc.trim();
if (name.isEmpty() || "NPC loot".equals(name)) return;
if (!name.equalsIgnoreCase(source)) {
if (kills > 0) {
// The new target's first kill refreshes the tray to its own streak; loot already
// booked before any kill stays (owner 2026-10-01).
restart(keepSession);
}
source = name;
label = "Looted";
}
event(now, keepSession, "Looted", name);
if (kills++ == 0) firstKillAt = now;
lastKillAt = now;
}

/** A new session: the best drop starts again; the streak ends on its own. */
synchronized void resetSession() {
bestDrop = "";
bestDropValue = 0L;
}

/** "Draconic visage +10.4M", or empty before any drop. */
synchronized String bestDrop() {
return bestDrop.isEmpty() ? "" : bestDrop + " " + Fmt.signed(bestDropValue);
}

/** The live kill streak ("Guard", 5), or null before a kill or once the streak went quiet. */
synchronized Map.Entry<String, Integer> streak(long now) {
return kills == 0 || now - lastEventAt >= quietMillis() ? null : new AbstractMap.SimpleImmutableEntry<>(source, kills);
}

/** Owner or Grind changed: start clean. */
synchronized void clear() {
entries.clear();
dropReceipts.clear();
resetSession();
tripId++;
source = "";
kills = 0;
lastEventAt = 0L;
shownAt = 0L;
lastDelta = 0L;
lastDeltaAt = 0L;
bigDrop = "";
bigDropAt = 0L;
label = "Looted";
}

synchronized boolean open(long now, long stayMillis, boolean alwaysOpen) {
reconcileDropReceipts();
return !entries.isEmpty() && (alwaysOpen || now - shownAt < stayMillis);
}

/** Newest arrivals first; a row keeps its place while its quantity grows. */
synchronized List<Entry> entries() {
reconcileDropReceipts();
var list = new ArrayList<Entry>(entries.values());
list.sort((a, b) -> Long.compare(b.born, a.born));
return list;
}

/** The newest booking's counted change, e.g. +23 for one more item; 0 before any. */
synchronized long lastDelta() {
return lastDelta;
}

/** When the newest booking's chip was set; 0 before any. */
synchronized long lastDeltaAt() {
return lastDeltaAt;
}

/** Something booked rides the tray; a reward window's held (pending) loot is not booked yet. */
synchronized boolean hasBooked() {
reconcileDropReceipts();
return entries.values().stream().anyMatch(entry -> entry.state != State.PENDING);
}

synchronized int tripId() {
return tripId;
}

/** The tray heading: what the streak did ("Looted", "Stole", "Claimed", "Mined" ...). */
synchronized String label() {
return label;
}

/** The newest big drop's name while it is fresh (moments show it a few seconds). */
synchronized String bigDrop(long now, long forMillis) {
return now - bigDropAt < forMillis ? bigDrop : "";
}

/**
* Every tray event belongs to a streak. A quiet gap, another kind of work ("Mined" after
* "Looted") or a kill of another NPC ends it; the next streak starts with an empty tray unless
* rows keep for the whole session. Folding only hides the tray; it never ends a streak.
*/
void event(long now, boolean keepSession, String kind, String npc) {
if (lastEventAt > 0L && now - lastEventAt >= quietMillis() || kind != null && !kind.equals(label)
|| npc != null && !source.isEmpty() && !npc.equalsIgnoreCase(source)) {
restart(keepSession);
}
if (kind != null) label = kind;
if (npc != null) source = npc;
lastEventAt = now;
}

void restart(boolean keepSession) {
if (!keepSession) {
entries.clear();
dropReceipts.clear();
}
tripId++;
source = "";
kills = 0;
lastEventAt = 0L;
}

/** Identity of the streak the per-kill line measures: a new streak, or its first kill. */
synchronized String streakKey() {
return tripId + ":" + source;
}

/** A streak goes quiet after three of its own kill intervals: at least a minute, at most five. */
volatile long keepMillis = 60_000L;
/** Owner-set streak window: the tray ends a streak this long after its last kill. */
synchronized void keepMillis(long millis) {
keepMillis = max(10_000L, min(3_600_000L, millis));
}

long quietMillis() {
return keepMillis;
}

long take(State from, int itemId, long quantity) {
Entry source = entries.get(key(from, itemId));
if (source == null) return 0L;
long moved = min(quantity, source.quantity);
source.quantity -= moved;
source.value = source.unitPrice * source.quantity;
if (source.quantity <= 0L) entries.remove(key(from, itemId));
return moved;
}

Entry add(int itemId, String name, State state, long unitPrice, long quantity, long value, long now, long born) {
Entry entry = entries.computeIfAbsent(key(state, itemId),
ignored -> new Entry(itemId, name == null ? "Unknown item" : name, state, unitPrice));
if (entry.born == 0L) entry.born = born != 0L ? born : now;
entry.quantity += quantity;
entry.value += value;
return entry;
}

void rememberDropReceipt(Transaction transaction) {
if (!"Dropped".equals(transaction.getNote()) || !transaction.isCounted() || !transaction.isOwnDropRecoveryEligible()
|| transaction.getCorrection() != Correction.AUTO) {
return;
}
for (Flow flow : transaction.getFlows()) {
if (flow == null || !flow.isCost() || flow.itemId <= 0) continue;
boolean known = dropReceipts.stream().anyMatch(receipt -> receipt.transaction == transaction
&& receipt.itemId == flow.itemId);
if (known) continue;
DropReceipt receipt = new DropReceipt(transaction, flow.itemId);
long[] costs = canonicalCosts(receipt);
receipt.lastQuantity = costs[0];
receipt.lastValue = costs[1];
dropReceipts.add(receipt);
}
while (dropReceipts.size() > MAX_DROP_RECEIPTS) dropReceipts.remove(0);
}

synchronized void reconcileDropReceipts() {
for (DropReceipt receipt : dropReceipts) {
long[] costs = canonicalCosts(receipt);
long recoveredQuantity = receipt.lastQuantity - costs[0];
long recoveredValue = receipt.lastValue - costs[1];
if (recoveredQuantity > 0L && recoveredValue >= 0L) {
reduceLost(receipt.itemId, recoveredQuantity, recoveredValue);
}
receipt.lastQuantity = costs[0];
receipt.lastValue = costs[1];
}
}

long[] canonicalCosts(DropReceipt receipt) {
long quantity = 0L;
long value = 0L;
for (Flow flow : receipt.transaction.getFlows()) {
if (flow == null || flow.itemId != receipt.itemId || !flow.isCost()) continue;
AccountingProjection.TransactionAmounts amounts = AccountingProjection.flow(receipt.transaction, flow);
if (amounts.costs <= 0L) continue;
quantity = SafeMath.safeAdd(quantity, SafeMath.abs(flow.quantityDelta));
value = SafeMath.safeAdd(value, amounts.costs);
}
return new long[] {quantity, value};
}

boolean reduceLost(int itemId, long quantity, long value) {
Entry entry = entries.get(key(State.LOST, itemId));
if (entry == null || quantity <= 0L || entry.quantity < quantity) return false;
entry.quantity -= quantity;
entry.value = min(0L, SafeMath.safeAdd(entry.value, value));
if (entry.quantity <= 0L) entries.remove(key(State.LOST, itemId));
return true;
}

static String key(State state, int itemId) {
return state.name() + ":" + itemId;
}

/** Runes, ammunition and charges are routine use: they count in Net but never ride the tray. */
static boolean routine(String name) {
String lower = name == null ? "" : name.toLowerCase(ROOT);
return lower.endsWith(" rune") || lower.endsWith(" runes") || lower.contains("arrow")
|| lower.endsWith(" bolts") || lower.contains(" bolts (") || lower.endsWith(" dart")
|| ModelText.has(lower, "javelin", "throwing", "zulrah's scales");
}

static final List<String[]> VERBS = GameData.rows("d17");
static final Set<String> MINIONS = new HashSet<>();
static {
GameData.rows("d16").forEach(row -> MINIONS.add(row[0]));
}

/** A boss's spawn (Greater Nechryael's death spawns): no header, streak, kill or activity of its own. */
static boolean minion(String npc) {
return npc != null && MINIONS.contains(npc.trim().toLowerCase(ROOT));
}

static String labelFor(String activity) {
String lower = activity == null ? "" : activity.toLowerCase(ROOT);
for (String[] row : VERBS) {
if (lower.contains(row[0])) return row[1];
}
return "Looted";
}
}
