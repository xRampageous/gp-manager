package com.gpmanager;
import static com.gpmanager.Ak.msg;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import com.gpmanager.SavedState.By;
import java.util.*;
import java.util.function.IntPredicate;
import lombok.*;
import net.runelite.api.*;
import static com.gpmanager.Ae.nonNeg;
import static com.gpmanager.Ag.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static com.gpmanager.Dv.*;
/**
* Key and chest claims. The account's unresolved claims are the only durable part: a claim opens
* when a key audit row is booked (the first key type keeps the row's id, more types derive
* {@code id#index}), shrinks as its chest contents settle or its keys are lost on death, and is
* removed at zero. Only a claim that reached zero writes its id onto the settling transaction as
* {@code sourceClaimId}, so a reload that sees both drops the stale claim without booking again,
* while a partly settled claim keeps its persisted remainder. Claims never expire on their own.
*
* <p>Everything else is short-lived evidence: an ordinary chest's object click (and a key loss seen
* while it is armed), the Wilderness loot-key chest container removals, and the local-death key
* snapshot. Chest contents never become durable provenance. Invoked only under the engine
* monitor.</p>
*/
class Da {
static final int OPEN_WINDOW_TICKS = 4;
static final int DEATH_WINDOW_TICKS = 8;
static final int CLAIM_EVIDENCE_TICKS = 4;
static final int[] KEY_ITEM_IDS = {
ItemID.WILDY_LOOT_KEY0, ItemID.WILDY_LOOT_KEY1, ItemID.WILDY_LOOT_KEY2, ItemID.WILDY_LOOT_KEY3,
ItemID.WILDY_LOOT_KEY4};
static final int[] KEY_CONTAINER_IDS = {
InventoryID.DEADMAN_LOOT_INV0, InventoryID.DEADMAN_LOOT_INV1, InventoryID.DEADMAN_LOOT_INV2,
InventoryID.DEADMAN_LOOT_INV3, InventoryID.DEADMAN_LOOT_INV4};
static final String LOOT_KEY_NOTE = msg("dj");
final List<By> claims = new ArrayList<>();
/** The chest object click, armed for a few ticks. */
Eg open;
/** A key loss seen while armed whose contents have not settled yet. */
Eg used;
Map<Integer, Long> localDeathKeys;
int localDeathTicks;
boolean lootChestVisible;
final Map<Integer, Map<Integer, Long>> observedKeyContainerContents = new HashMap<>();
/** Contents removed from each loot key's chest container, kept for a few ticks as claim evidence. */
final Map<Integer, Removed> removedByKey = new HashMap<>();
/** A chest opened with one key type: the key, how many were used and the chest's name. */
@AllArgsConstructor
static class Eg {
final int keyItemId;
final long quantity;
final String chestName;
@Getter(AccessLevel.NONE)
int ticks;
}
@AllArgsConstructor
static class Removed {
Map<Integer, Long> contents;
int ticks;
}
// -- Durable claims --
void clear() {
claims.clear();
}
/** Restores the persisted claims and drops any already settled by a retained transaction. */
void restore(SavedState state, List<Ad> sessions) {
claims.clear();
var settled = new HashSet<String>();
for (Ad session : sessions) {
for (Ac transaction : session == null ? Collections.<Ac>emptyList()
: session.getTransactions()) {
if (transaction != null && !transaction.uo().isEmpty()) {
settled.add(transaction.uo());
}
}
}
var seen = new HashSet<String>();
for (By claim : state.getPendingClaims()) {
if (claim != null && claim.isValid() && seen.add(claim.getClaimId())
&& !settled.contains(claim.getClaimId())) {
claims.add(claim);
}
}
sort();
}
void auc(Da staged) {
claims.clear();
claims.addAll(staged.claims);
}
void ayc(SavedState state) {
state.setPendingClaims(new ArrayList<>(claims));
}
List<By> all() {
return unmodifiableList(claims);
}
/** Opens a claim keyed by the audit row's id; a second open with the same id is ignored. */
void open(String claimId, int keyItemId, long quantity, long now) {
if (empty(claimId) || keyItemId <= 0 || quantity <= 0L || indexOf(claimId) >= 0) {
return;
}
claims.add(new By(claimId, keyItemId, quantity, now));
sort();
}
/**
* Applies a settlement or death loss of {@code quantity} keys to the oldest claims for the key.
* Returns the id of the last claim it touched when that claim closed; null otherwise.
*/
String consume(int keyItemId, long quantity) {
String closed = null;
long remaining = nonNeg(quantity);
for (int index = 0; index < claims.size() && remaining > 0L; index++) {
By claim = claims.get(index);
if (claim.itemOrKeyId != keyItemId) {
continue;
}
long applied = min(remaining, claim.getQuantity());
remaining -= applied;
closed = reduce(index, applied) ? claim.getClaimId() : null;
index -= closed == null ? 0 : 1;
}
return closed;
}
/** Settles one specific claim by {@code quantity}; returns its id when it closed. */
String settle(String claimId, long quantity) {
int index = indexOf(claimId);
boolean closed = index >= 0 && reduce(index, nonNeg(quantity));
sort();
return closed ? claimId : null;
}
/** Shrinks the claim at {@code index}; true when it reached zero and was removed. */
boolean reduce(int index, long quantity) {
By claim = claims.get(index);
long left = claim.getQuantity() - quantity;
if (left > 0L) {
claims.set(index, new By(claim.getClaimId(), claim.itemOrKeyId, left, claim.getCreatedAtEpochMillis()));
return false;
}
claims.remove(index);
return true;
}
int indexOf(String claimId) {
for (int index = 0; index < claims.size(); index++) {
if (claims.get(index).getClaimId().equals(claimId)) {
return index;
}
}
return -1;
}
/** Created-at then claim id: equal timestamps never reorder across save and reload. */
void sort() {
claims.sort(Comparator.comparingLong(By::getCreatedAtEpochMillis)
.thenComparing(By::getClaimId));
}
// -- Short-lived evidence --
/** All transient evidence goes; the durable claims stay. */
void pd() {
nk();
localDeathKeys = null;
localDeathTicks = 0;
lootChestVisible = false;
observedKeyContainerContents.clear();
removedByKey.clear();
}
void tick() {
open = open == null || --open.ticks <= 0 ? null : open;
used = used == null || --used.ticks <= 0 ? null : used;
if (localDeathTicks > 0 && --localDeathTicks == 0) {
localDeathKeys = null;
}
removedByKey.values().removeIf(removed -> --removed.ticks <= 0);
}
/** Arms (or disarms) ordinary key-chest provenance from an actual chest object click. */
boolean qo(String option, String target) {
KeyChestCatalogue.Entry entry = KeyChestCatalogue.rv(option, target);
used = null;
open = entry == null ? null : new Eg(entry.getKeyItemId(), 0L, entry.getChestName(), OPEN_WINDOW_TICKS);
return open != null;
}
void nk() {
open = null;
used = null;
}
/**
* Correlate a settled key loss with settled positive inventory contents. A click alone is never
* a claim, and ambiguous keys with multiple chest types require the observed object name.
*/
Eg aaj(Map<Integer, Long> keyLosses, Map<Integer, Long> positiveContents,
String sourceBackedActivity) {
boolean sourced = sourceBackedActivity != null && !sourceBackedActivity.trim().isEmpty();
Map<Integer, Long> losses = positiveEntries(keyLosses);
if (empty(positiveContents)) {
if (sourced) {
nk();
} else if (open != null && losses.size() == 1 && losses.containsKey(open.keyItemId)) {
// The key left before its contents: remember the use for a few ticks.
used = new Eg(open.keyItemId, losses.get(open.keyItemId), open.chestName, OPEN_WINDOW_TICKS);
open = null;
}
return null;
}
int keyId = -1;
long quantity = 0L;
String chestName = null;
if (!losses.isEmpty()) {
Eg armed = open != null ? open : used;
Map.Entry<Integer, Long> loss = losses.entrySet().iterator().next();
if (losses.size() != 1 || armed != null && armed.keyItemId != loss.getKey()) {
nk();
return null;
}
keyId = loss.getKey();
quantity = loss.getValue();
List<KeyChestCatalogue.Entry> entries = KeyChestCatalogue.rs(keyId);
chestName = open != null && open.keyItemId == keyId ? open.chestName
: entries.size() == 1 ? entries.get(0).getChestName() : null;
used = null;
} else if (used != null) {
keyId = used.keyItemId;
quantity = used.quantity;
chestName = used.chestName;
used = null;
}
if (keyId < 0 || quantity <= 0L || chestName == null) {
// Do not let an uncorroborated gain leave an object click armed to
// relabel a later, unrelated key loss and contents change.
open = null;
return null;
}
KeyChestCatalogue.Entry aqn = sourced ? KeyChestCatalogue.rp(sourceBackedActivity) : null;
if (sourced && (aqn == null || !chestName.equalsIgnoreCase(aqn.getChestName()))) {
nk();
return null;
}
if (open != null && open.keyItemId == keyId && open.chestName != null
&& !open.chestName.equalsIgnoreCase(chestName)) {
return null;
}
nk();
return new Eg(keyId, quantity, chestName, 0);
}
void ly(Map<Integer, Long> keyInventory) {
Map<Integer, Long> keys = keyQuantities(keyInventory);
localDeathKeys = keys.isEmpty() ? null : keys;
localDeathTicks = localDeathKeys == null ? 0 : DEATH_WINDOW_TICKS;
}
boolean vo() {
return localDeathKeys != null && localDeathTicks > 0;
}
void setLootChestVisible(boolean visible) {
if (lootChestVisible && !visible) {
// Reopening a key UI starts a fresh container-delta baseline. A
// short, already-observed claim delta may still settle afterward.
observedKeyContainerContents.clear();
}
lootChestVisible = visible;
}
/**
* Observe one loot key's server-owned contents while its interface is visible. Only removals
* from this container can correlate settled INV gains to a claim.
*/
void abq(int keyItemId, Map<Integer, Long> contents) {
int aoq = yc(keyItemId);
if (!lootChestVisible || aoq < 0 || contents == null) {
return;
}
Map<Integer, Long> next = positiveEntries(contents);
Map<Integer, Long> removed = minus(observedKeyContainerContents.put(aoq, next), next);
if (!removed.isEmpty()) {
Removed evidence = removedByKey.computeIfAbsent(aoq, key -> new Removed(new HashMap<>(), 0));
removed.forEach((itemId, quantity) -> evidence.contents.merge(itemId, quantity, Ae::safeAdd));
evidence.ticks = CLAIM_EVIDENCE_TICKS;
}
}
/**
* Settles the one single-key loot-key claim whose chest removal (seen within the evidence
* window) covers these settled gains. One matched manifest accounts for one physical key at
* most, so a stacked claim shrinks one unit at a time; exhausting the evidence supplies that
* unit even when the key-loss callback arrived separately. Returns the claim id when it
* closed. Descriptive: never alters the settling transaction's type, valuation or flows.
*/
String aiq(Map<Integer, Long> settledPositiveFlows, Map<Integer, Long> settledKeyLosses) {
if (empty(settledPositiveFlows)) {
return null;
}
By match = null;
int ami = -1;
for (Map.Entry<Integer, Removed> evidence : removedByKey.entrySet()) {
By claim = covers(evidence.getValue().contents, settledPositiveFlows)
? akp(yb(evidence.getKey())) : null;
if (claim != null && claim.getQuantity() > 0L) {
if (match != null) {
return null;
}
match = claim;
ami = evidence.getKey();
}
}
if (match == null) {
return null;
}
long anr = settledKeyLosses == null ? 0L
: settledKeyLosses.getOrDefault(yb(ami), 0L);
Removed evidence = removedByKey.get(ami);
evidence.contents = minus(evidence.contents, settledPositiveFlows);
evidence.ticks = CLAIM_EVIDENCE_TICKS;
if (evidence.contents.isEmpty()) {
removedByKey.remove(ami);
}
return settle(match.getClaimId(), evidence.contents.isEmpty() && anr <= 0L ? 1L : anr);
}
/** The one unresolved claim for this key item when exactly one exists; null otherwise. */
By akp(int keyItemId) {
By match = null;
for (By claim : claims) {
if (claim.itemOrKeyId != keyItemId) {
continue;
}
if (match != null) {
return null;
}
match = claim;
}
return match;
}
/**
* Closes pending loot-key claims only for key units that disappeared from INV in a local-death
* settlement, measured against the death snapshot.
*/
void aet(List<Ab> settledFlows, Map<Integer, Long> currentKeyInventory) {
if (!vo()) {
return;
}
Map<Integer, Long> flowLosses = lossQuantities(settledFlows, Da::xa);
Map<Integer, Long> gone = minus(localDeathKeys, keyQuantities(currentKeyInventory));
for (int keyItemId : KEY_ITEM_IDS) {
long pendingKeys = 0L;
for (By claim : claims) {
pendingKeys += claim.itemOrKeyId == keyItemId ? claim.getQuantity() : 0L;
}
long amj = min(pendingKeys,
min(gone.getOrDefault(keyItemId, 0L), flowLosses.getOrDefault(keyItemId, 0L)));
if (amj > 0L) {
consume(keyItemId, amj);
// Inventory/equipment changes can stabilize in separate batches after a death.
// Keep the bounded snapshot until each confirmed key loss is consumed or the
// game-tick window expires; an unrelated first batch must not make a later
// key-loss flow look like an ordinary cost.
take(localDeathKeys, keyItemId, amj);
}
}
if (localDeathKeys.isEmpty()) {
localDeathKeys = null;
localDeathTicks = 0;
}
}
// -- Key identity --
static boolean xa(int itemId) {
return yc(itemId) >= 0;
}
static int yc(int itemId) {
for (int index = 0; index < KEY_ITEM_IDS.length; index++) {
if (KEY_ITEM_IDS[index] == itemId) {
return index;
}
}
return -1;
}
static int yb(int index) {
return index < 0 || index >= KEY_ITEM_IDS.length ? -1 : KEY_ITEM_IDS[index];
}
static int ya(int index) {
return index < 0 || index >= KEY_CONTAINER_IDS.length ? -1 : KEY_CONTAINER_IDS[index];
}
/** Positive item quantities in a container, skipping items {@code skip} rejects (nested crates). */
static Map<Integer, Long> manifestFromContainer(ItemContainer container,
IntPredicate skip) {
// Refuse an incomplete evidence policy rather than accidentally valuing a
// nested crate as if its unopened contents were already manifest items.
var manifest = new LinkedHashMap<Integer, Long>();
if (container == null || container.getItems() == null || skip == null) {
return emptyMap();
}
for (Item item : container.getItems()) {
if (item != null && item.getId() >= 0 && item.getQuantity() > 0 && !skip.test(item.getId())) {
manifest.merge(item.getId(), (long) item.getQuantity(), Ae::safeAdd);
}
}
return unmodifiableMap(manifest);
}
static Map<Integer, Long> keyQuantities(ItemContainer inventory) {
return manifestFromContainer(inventory, itemId -> !xa(itemId));
}
static Map<Integer, Long> keyQuantities(Map<Integer, Long> quantities) {
Map<Integer, Long> keys = positiveEntries(quantities);
keys.keySet().removeIf(itemId -> !xa(itemId));
return keys;
}
}
