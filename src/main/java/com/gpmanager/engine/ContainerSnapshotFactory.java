package com.gpmanager;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import lombok.RequiredArgsConstructor;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ContainerSnapshotFactory {
final Client client;
final Ix ix;
/** Rune pouch slot varbits (type, quantity) — live account state, unlike bag containers. */
static final int[][] RUNE_POUCH_SLOTS = {
 {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_QUANTITY_1},
 {VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_QUANTITY_2},
 {VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_QUANTITY_3},
 {VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_QUANTITY_4},
 {VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_QUANTITY_5},
 {VarbitID.RUNE_POUCH_TYPE_6, VarbitID.RUNE_POUCH_QUANTITY_6}, };
/**
* @param includeRunePouch merge runes held in the rune pouch. The pouch is account
*                         state exposed through live varbits, so casting from it books
*                         a cost and filling it nets to zero. Looting bag / seed box
*                         containers are deliberately excluded: the client only syncs
*                         them when the bag is opened, so they would drift.
*/
Cc capture(boolean /* includeEquipment */b, boolean /* includeRunePouch */c) {
 var /* quantities */d = new HashMap<Integer, Long>();
 aah(d, client.getItemContainer(InventoryID.INV));
 if (b) {
  aah(d, client.getItemContainer(InventoryID.WORN));
  // Ammo stored in Dizana's quiver is spent like the ammo slot's (research 1.2).
  aah(d, client.getItemContainer(InventoryID.DIZANAS_QUIVER_AMMO));
 }
 if (c) aao(d);
 // A carried ash sanctifier holds death runes as charges, ten per rune (research 1.2): each
 // ten ashes it scatters spend a rune, and charging it nets to zero.
 if (d.containsKey(ItemID.ASH_SANCTIFIER)) {
  d.merge(ItemID.DEATHRUNE, client.getVarbitValue(VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY) / 10L, Long::sum);
 }
 return new Cc(d);
}

/**
* Snapshot only the player's inventory and (when enabled for accounting) worn
* equipment for local-death ownership reconciliation. Rune-pouch varbits and banked
* containers are deliberately excluded because neither is an inventory/equipment
* stack read from the death event.
*/
Cc nr(boolean /* includeEquipment */a) {
 var /* quantities */b = new HashMap<Integer, Long>();
 aah(b, client.getItemContainer(InventoryID.INV));
 if (a) aah(b, client.getItemContainer(InventoryID.WORN));
 return new Cc(b);
}

/** True when {@code varbitId} is one of the rune pouch slot varbits. */
static boolean xv(int /* varbitId */a) {
 for (int[] /* slot */b : RUNE_POUCH_SLOTS) {
  if (b[0] == a || b[1] == a) return true;
 }
 return false;
}

void aao(Map<Integer, Long> /* quantities */a) {
 EnumComposition /* runes */b;
 try {
  b = client.getEnum(EnumID.RUNEPOUCH_RUNE);
 } catch (RuntimeException /* ignored */c) {
  return;
 }
 if (b == null) return;
 for (int[] /* slot */d : RUNE_POUCH_SLOTS) {
  int /* type */e = client.getVarbitValue(d[0]);
  int /* quantity */f = client.getVarbitValue(d[1]);
  if (e <= 0 || f <= 0) continue;
  int /* itemId */g = b.getIntValue(e);
  if (g <= 0) continue;
  a.merge(canonical(g), (long) f, Long::sum);
 }
}

void aah(Map<Integer, Long> /* quantities */a, ItemContainer /* container */b) {
 if (b == null) return;
 for (Item /* item */c : b.getItems()) {
  if (c == null || c.getId() < 0 || c.getQuantity() <= 0) continue;
  int /* canonicalId */d = canonical(c.getId());
  a.merge(d, (long) c.getQuantity(), Long::sum);
 }
}

/** An open bag or lit lantern is the same item as the closed or unlit one (d21; owner 1.2). */
static final Map<Integer, Integer> ONE_ITEM = new HashMap<>();
/** The open bags among them: a pickup may land in one instead of the inventory. */
static final Set<Integer> BAGS = new HashSet<>();
static {
 for (String[] /* row */b : Ak.rows("d21")) {
  ONE_ITEM.put(Integer.parseInt(b[0]), Integer.parseInt(b[1]));
  if (b[2].contains("_OPEN=")) BAGS.add(Integer.parseInt(b[0]));
 }
}

/** True while the inventory or equipment holds an open bag. */
boolean bagOpen() {
 for (int /* container */a : new int[]{InventoryID.INV, InventoryID.WORN}) {
  ItemContainer /* items */b = client.getItemContainer(a);
  if (b == null) continue;
  for (Item /* item */c : b.getItems()) if (c != null && BAGS.contains(c.getId())) return true;
 }
 return false;
}

/** Un-noted / un-placeholdered, closed and unlit id; no ItemManager in offline tests. */
int canonical(int /* itemId */a) {
 int /* id */b = ix == null ? a : ix.canon(a);
 return ONE_ITEM.getOrDefault(b, b);
}
}
