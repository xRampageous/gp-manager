package com.gpmanager;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InventoryID;
import lombok.RequiredArgsConstructor;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.client.game.ItemManager;
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ContainerSnapshotFactory {
final Client client;
final ItemManager itemManager;
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
Cc capture(boolean includeEquipment, boolean includeRunePouch) {
 var quantities = new HashMap<Integer, Long>();
 aah(quantities, client.getItemContainer(InventoryID.INV));
 if (includeEquipment) aah(quantities, client.getItemContainer(InventoryID.WORN));
 if (includeRunePouch) aao(quantities);
 return new Cc(quantities);
}

/**
* Snapshot only the player's inventory and (when enabled for accounting) worn
* equipment for local-death ownership reconciliation. Rune-pouch varbits and banked
* containers are deliberately excluded because neither is an inventory/equipment
* stack read from the death event.
*/
Cc nr(boolean includeEquipment) {
 var quantities = new HashMap<Integer, Long>();
 aah(quantities, client.getItemContainer(InventoryID.INV));
 if (includeEquipment) aah(quantities, client.getItemContainer(InventoryID.WORN));
 return new Cc(quantities);
}

/** True when {@code varbitId} is one of the rune pouch slot varbits. */
static boolean xv(int varbitId) {
 for (int[] slot : RUNE_POUCH_SLOTS) {
  if (slot[0] == varbitId || slot[1] == varbitId) return true;
 }
 return false;
}

void aao(Map<Integer, Long> quantities) {
 EnumComposition runes;
 try {
  runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
 } catch (RuntimeException ignored) {
  return;
 }
 if (runes == null) return;
 for (int[] slot : RUNE_POUCH_SLOTS) {
  int type = client.getVarbitValue(slot[0]);
  int quantity = client.getVarbitValue(slot[1]);
  if (type <= 0 || quantity <= 0) continue;
  int itemId = runes.getIntValue(type);
  if (itemId <= 0) continue;
  quantities.merge(canonical(itemId), (long) quantity, Long::sum);
 }
}

void aah(Map<Integer, Long> quantities, ItemContainer container) {
 if (container == null) return;
 for (Item item : container.getItems()) {
  if (item == null || item.getId() < 0 || item.getQuantity() <= 0) continue;
  int canonicalId = canonical(item.getId());
  quantities.merge(canonicalId, (long) item.getQuantity(), Long::sum);
 }
}

/** Un-noted / un-placeholdered id; identity when no ItemManager is wired (offline tests). */
int canonical(int itemId) {
 return itemManager == null ? itemId : itemManager.canonicalize(itemId);
}
}
