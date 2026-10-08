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
 ItemID.WILDY_LOOT_KEY0, ItemID.WILDY_LOOT_KEY1, ItemID.WILDY_LOOT_KEY2, ItemID.WILDY_LOOT_KEY3, ItemID.WILDY_LOOT_KEY4};
static final int[] KEY_CONTAINER_IDS = {
 InventoryID.DEADMAN_LOOT_INV0, InventoryID.DEADMAN_LOOT_INV1, InventoryID.DEADMAN_LOOT_INV2,
 InventoryID.DEADMAN_LOOT_INV3, InventoryID.DEADMAN_LOOT_INV4};
static final String LOOT_KEY_NOTE = msg("dj");
List<By> claims = new ArrayList<>();
/** The chest object click, armed for a few ticks. */
Eg open;
/** A key loss seen while armed whose contents have not settled yet. */
Eg used;
Map<Integer, Long> localDeathKeys;
int localDeathTicks;
boolean lootChestVisible;
Map<Integer, Map<Integer, Long>> observedKeyContainerContents = new HashMap<>();
/** Contents removed from each loot key's chest container, kept for a few ticks as claim evidence. */
Map<Integer, Removed> removedByKey = new HashMap<>();
/** A chest opened with one key type: the key, how many were used and the chest's name. */
@AllArgsConstructor
static class Eg {
 int keyItemId;
 long quantity;
 String chestName;
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
void restore(SavedState /* state */a, List<Ad> /* sessions */b) {
 claims.clear();
 var /* settled */c = new HashSet<String>();
 for (Ad /* session */d : b) {
  for (Ac /* transaction */e : d == null ? Collections.<Ac>emptyList() : d.getTransactions()) {
   if (e != null && !e.uo().isEmpty()) {
    c.add(e.uo());
   }
  }
 }
 var /* seen */f = new HashSet<String>();
 for (By /* claim */g : a.getPendingClaims()) {
  if (g != null && g.isValid() && f.add(g.getClaimId()) && !c.contains(g.getClaimId())) {
   claims.add(g);
  }
 }
 sort();
}

void auc(Da /* staged */a) {
 claims.clear();
 claims.addAll(a.claims);
}

void ayc(SavedState /* state */a) {
 a.setPendingClaims(new ArrayList<>(claims));
}

List<By> all() {
 return unmodifiableList(claims);
}

/** Opens a claim keyed by the audit row's id; a second open with the same id is ignored. */
void open(String /* claimId */a, int /* keyItemId */b, long /* quantity */c, long /* now */d) {
 if (empty(a) || b <= 0 || c <= 0L || indexOf(a) >= 0) return;
 claims.add(new By(a, b, c, d));
 sort();
}

/**
* Applies a settlement or death loss of {@code quantity} keys to the oldest claims for the key.
* Returns the id of the last claim it touched when that claim closed; null otherwise.
*/
String consume(int /* keyItemId */a, long /* quantity */b) {
 String /* closed */c = null;
 long /* remaining */d = nonNeg(b);
 for (int /* index */e = 0; e < claims.size() && d > 0L; e++) {
  By /* claim */f = claims.get(e);
  if (f.itemOrKeyId != a) continue;
  long /* applied */g = min(d, f.getQuantity());
  d -= g;
  c = reduce(e, g) ? f.getClaimId() : null;
  e -= c == null ? 0 : 1;
 }
 return c;
}

/** Settles one specific claim by {@code quantity}; returns its id when it closed. */
String settle(String /* claimId */a, long /* quantity */b) {
 int /* index */c = indexOf(a);
 boolean /* closed */d = c >= 0 && reduce(c, nonNeg(b));
 sort();
 return d ? a : null;
}

/** Shrinks the claim at {@code index}; true when it reached zero and was removed. */
boolean reduce(int /* index */a, long /* quantity */b) {
 By /* claim */c = claims.get(a);
 long /* left */d = c.getQuantity() - b;
 if (d > 0L) {
  claims.set(a, new By(c.getClaimId(), c.itemOrKeyId, d, c.getCreatedAtEpochMillis()));
  return false;
 }
 claims.remove(a);
 return true;
}

int indexOf(String /* claimId */a) {
 for (int /* index */b = 0; b < claims.size(); b++) {
  if (claims.get(b).getClaimId().equals(a)) return b;
 }
 return -1;
}

/** Created-at then claim id: equal timestamps never reorder across save and reload. */
void sort() {
 claims.sort(Comparator.comparingLong(By::getCreatedAtEpochMillis).thenComparing(By::getClaimId));
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
 if (localDeathTicks > 0 && --localDeathTicks == 0) localDeathKeys = null;
 removedByKey.values().removeIf(/* removed */a -> --a.ticks <= 0);
}

/** Arms (or disarms) ordinary key-chest provenance from an actual chest object click. */
boolean qo(String /* option */a, String /* target */b) {
 KeyChestCatalogue.Entry /* entry */c = KeyChestCatalogue.rv(a, b);
 used = null;
 open = c == null ? null : new Eg(c.keyItemId, 0L, c.chestName, OPEN_WINDOW_TICKS);
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
Eg aaj(Map<Integer, Long> /* keyLosses */b, Map<Integer, Long> /* positiveContents */c, String /* sourceBackedActivity */d) {
 boolean /* sourced */e = d != null && !d.trim().isEmpty();
 Map<Integer, Long> /* losses */f = positiveEntries(b);
 if (empty(c)) {
  if (e) {
   nk();
  } else if (open != null && f.size() == 1 && f.containsKey(open.keyItemId)) {
   // The key left before its contents: remember the use for a few ticks.
   used = new Eg(open.keyItemId, f.get(open.keyItemId), open.chestName, OPEN_WINDOW_TICKS);
   open = null;
  }
  return null;
 }
 int /* keyId */g = -1;
 long /* quantity */h = 0L;
 String /* chestName */i = null;
 if (!f.isEmpty()) {
  Eg /* armed */j = open != null ? open : used;
  Map.Entry<Integer, Long> /* loss */k = f.entrySet().iterator().next();
  if (f.size() != 1 || j != null && j.keyItemId != k.getKey()) {
   nk();
   return null;
  }
  g = k.getKey();
  h = k.getValue();
  List<KeyChestCatalogue.Entry> /* entries */l = KeyChestCatalogue.rs(g);
  i = open != null && open.keyItemId == g ? open.chestName
  : l.size() == 1 ? l.get(0).chestName : null;
  used = null;
 } else if (used != null) {
  g = used.keyItemId;
  h = used.quantity;
  i = used.chestName;
  used = null;
 }
 if (g < 0 || h <= 0L || i == null) {
  // Do not let an uncorroborated gain leave an object click armed to
  // relabel a later, unrelated key loss and contents change.
  open = null;
  return null;
 }
 KeyChestCatalogue.Entry /* aqn */m = e ? KeyChestCatalogue.rp(d) : null;
 if (e && (m == null || !i.equalsIgnoreCase(m.chestName))) {
  nk();
  return null;
 }
 if (open != null && open.keyItemId == g && open.chestName != null && !open.chestName.equalsIgnoreCase(i)) {
  return null;
 }
 nk();
 return new Eg(g, h, i, 0);
}

void ly(Map<Integer, Long> /* keyInventory */a) {
 Map<Integer, Long> /* keys */b = keyQuantities(a);
 localDeathKeys = b.isEmpty() ? null : b;
 localDeathTicks = localDeathKeys == null ? 0 : DEATH_WINDOW_TICKS;
}

boolean vo() {
 return localDeathKeys != null && localDeathTicks > 0;
}

void setLootChestVisible(boolean /* visible */b) {
 if (lootChestVisible && !b) {
  // Reopening a key UI starts a fresh container-delta baseline. A
  // short, already-observed claim delta may still settle afterward.
  observedKeyContainerContents.clear();
 }
 lootChestVisible = b;
}

/**
* Observe one loot key's server-owned contents while its interface is visible. Only removals
* from this container can correlate settled INV gains to a claim.
*/
void abq(int /* keyItemId */a, Map<Integer, Long> /* contents */b) {
 int /* aoq */c = yc(a);
 if (!lootChestVisible || c < 0 || b == null) return;
 Map<Integer, Long> /* next */d = positiveEntries(b);
 Map<Integer, Long> /* removed */e = minus(observedKeyContainerContents.put(c, d), d);
 if (!e.isEmpty()) {
  Removed /* evidence */f = removedByKey.computeIfAbsent(c, /* key */g -> new Removed(new HashMap<>(), 0));
  e.forEach((/* itemId */h, /* quantity */i) -> f.contents.merge(h, i, Ae::safeAdd));
  f.ticks = CLAIM_EVIDENCE_TICKS;
 }
}

/**
* Settles the one single-key loot-key claim whose chest removal (seen within the evidence
* window) covers these settled gains. One matched manifest accounts for one physical key at
* most, so a stacked claim shrinks one unit at a time; exhausting the evidence supplies that
* unit even when the key-loss callback arrived separately. Returns the claim id when it
* closed. Descriptive: never alters the settling transaction's type, valuation or flows.
*/
String aiq(Map<Integer, Long> /* settledPositiveFlows */a, Map<Integer, Long> /* settledKeyLosses */b) {
 if (empty(a)) return null;
 By /* match */c = null;
 int /* ami */d = -1;
 for (Map.Entry<Integer, Removed> /* evidence */e : removedByKey.entrySet()) {
  By /* claim */f = covers(e.getValue().contents, a)
  ? akp(yb(e.getKey())) : null;
  if (f != null && f.getQuantity() > 0L) {
   if (c != null) return null;
   c = f;
   d = e.getKey();
  }
 }
 if (c == null) return null;
 long /* anr */g = b == null ? 0L : b.getOrDefault(yb(d), 0L);
 Removed /* evidence */h = removedByKey.get(d);
 h.contents = minus(h.contents, a);
 h.ticks = CLAIM_EVIDENCE_TICKS;
 if (h.contents.isEmpty()) removedByKey.remove(d);
 return settle(c.getClaimId(), h.contents.isEmpty() && g <= 0L ? 1L : g);
}

/** The one unresolved claim for this key item when exactly one exists; null otherwise. */
By akp(int /* keyItemId */a) {
 By /* match */b = null;
 for (By /* claim */c : claims) {
  if (c.itemOrKeyId != a) continue;
  if (b != null) return null;
  b = c;
 }
 return b;
}

/**
* Closes pending loot-key claims only for key units that disappeared from INV in a local-death
* settlement, measured against the death snapshot.
*/
void aet(List<Ab> /* settledFlows */b, Map<Integer, Long> /* currentKeyInventory */c) {
 if (!vo()) return;
 Map<Integer, Long> /* flowLosses */d = lossQuantities(b, Da::xa);
 Map<Integer, Long> /* gone */e = minus(localDeathKeys, keyQuantities(c));
 for (int /* keyItemId */f : KEY_ITEM_IDS) {
  long /* pendingKeys */g = 0L;
  for (By /* claim */h : claims) g += h.itemOrKeyId == f ? h.getQuantity() : 0L;
  long /* amj */i = min(g, min(e.getOrDefault(f, 0L), d.getOrDefault(f, 0L)));
  if (i > 0L) {
   consume(f, i);
   // Inventory/equipment changes can stabilize in separate batches after a death.
   // Keep the bounded snapshot until each confirmed key loss is consumed or the
   // game-tick window expires; an unrelated first batch must not make a later
   // key-loss flow look like an ordinary cost.
   take(localDeathKeys, f, i);
  }
 }
 if (localDeathKeys.isEmpty()) {
  localDeathKeys = null;
  localDeathTicks = 0;
 }
}

// -- Key identity --
static boolean xa(int /* itemId */a) {
 return yc(a) >= 0;
}

static int yc(int /* itemId */a) {
 for (int /* index */b = 0; b < KEY_ITEM_IDS.length; b++) {
  if (KEY_ITEM_IDS[b] == a) return b;
 }
 return -1;
}

static int yb(int /* index */a) {
 return a < 0 || a >= KEY_ITEM_IDS.length ? -1 : KEY_ITEM_IDS[a];
}

static int ya(int /* index */a) {
 return a < 0 || a >= KEY_CONTAINER_IDS.length ? -1 : KEY_CONTAINER_IDS[a];
}

/** Positive item quantities in a container, skipping items {@code skip} rejects (nested crates). */
static Map<Integer, Long> manifestFromContainer(ItemContainer /* container */b, IntPredicate /* skip */c) {
 // Refuse an incomplete evidence policy rather than accidentally valuing a
 // nested crate as if its unopened contents were already manifest items.
 var /* manifest */d = new LinkedHashMap<Integer, Long>();
 if (b == null || b.getItems() == null || c == null) return emptyMap();
 for (Item /* item */e : b.getItems()) {
  if (e != null && e.getId() >= 0 && e.getQuantity() > 0 && !c.test(e.getId())) {
   d.merge(e.getId(), (long) e.getQuantity(), Ae::safeAdd);
  }
 }
 return unmodifiableMap(d);
}

static Map<Integer, Long> keyQuantities(ItemContainer /* inventory */a) {
 return manifestFromContainer(a, /* itemId */b -> !xa(b));
}

static Map<Integer, Long> keyQuantities(Map<Integer, Long> /* quantities */a) {
 Map<Integer, Long> /* keys */b = positiveEntries(a);
 b.keySet().removeIf(/* itemId */c -> !xa(c));
 return b;
}
}
