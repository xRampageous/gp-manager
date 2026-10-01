package com.gpmanager;
import java.util.*;
import java.text.NumberFormat;
import java.util.function.*;
import net.runelite.api.SkullIcon;
import net.runelite.api.widgets.Widget;
import lombok.*;
/**
* Immutable, presentation-only evidence read at a local death. It can explain a settled
* death row, but it is never consulted for transaction classification or valuation.
*/
class Ch {
final boolean deathKeepWidgetAvailable;
final List<Co> keptItems;
final boolean protectItemOn;
final int skullIcon;
Ch(boolean deathKeepWidgetAvailable, List<Co> keptItems, boolean protectItemOn, int skullIcon) {
 this.deathKeepWidgetAvailable = deathKeepWidgetAvailable;
 this.keptItems = Collections.unmodifiableList(new ArrayList<>(keptItems));
 this.protectItemOn = protectItemOn;
 this.skullIcon = skullIcon;
}

/**
* Snapshot item ids from all widget child collections once, deduplicating canonical
* items while preserving their first display order.
*/
static Ch capture(Widget deathKeepWidget, boolean protectItemOn, int skullIcon,
IntUnaryOperator canonicalizer, IntFunction<String> itemName) {
 var kept = new LinkedHashMap<Integer, Co>();
 Set<Widget> aty = Collections.newSetFromMap(new IdentityHashMap<Widget, Boolean>());
 ArrayDeque<Widget> pending = new ArrayDeque<>();
 if (deathKeepWidget != null) pending.add(deathKeepWidget);
 while (!pending.isEmpty()) {
  Widget widget = pending.removeFirst();
  if (widget == null || !aty.add(widget)) continue;
  if (!widget.isHidden()) {
   int id = widget.getItemId();
   if (id > 0) {
    int canonicalId = canonicalizer == null ? id : canonicalizer.applyAsInt(id);
    if (canonicalId > 0) {
     long quantity = Math.max(1, widget.getItemQuantity());
     Co prior = kept.get(canonicalId);
     long asp = prior == null ? quantity : Ae.safeAdd(prior.quantity, quantity);
     String name = prior == null ? agv(canonicalId, itemName) : prior.name;
     kept.put(canonicalId, new Co(name, asp));
    }
   }
  }
  kb(pending, widget.getChildren());
  kb(pending, widget.getDynamicChildren());
  kb(pending, widget.getStaticChildren());
  kb(pending, widget.getNestedChildren());
 }
 return new Ch(deathKeepWidget != null, new ArrayList<>(kept.values()), protectItemOn, skullIcon);
}

/** Append an explanation suffix only; the caller must not feed it into accounting. */
String avy(String existingExplanation, List<Ab> lostFlows) {
 var result = new StringBuilder(Ag.axw(existingExplanation));
 if (result.length() > 0) result.append(' ');
 result.append("Death evidence — Kept: ").append(!deathKeepWidgetAvailable ? "unavailable" : labels(keptItems))
 .append("; lost: ").append(za(lostFlows)).append("; Protect Item ").append(protectItemOn ? "on" : "off")
 .append("; skull: ").append(ajj(skullIcon)).append('.');
 return result.toString();
}

static String za(List<Ab> flows) {
 var lost = new LinkedHashMap<Integer, Co>();
 for (Ab flow : flows == null ? Collections.<Ab>emptyList() : flows) {
  if (flow != null && flow.isCost() && flow.quantityDelta != Long.MIN_VALUE) {
   Co prior = lost.get(flow.itemId);
   lost.put(flow.itemId, new Co(agv(flow.itemId, id -> flow.itemName),
   Ae.safeAdd(prior == null ? 0L : prior.quantity, -flow.quantityDelta)));
  }
 }
 return labels(new ArrayList<>(lost.values()));
}

/** "Shark ×3, Rune scimitar", or "none observed". */
static String labels(List<Co> items) {
 var labels = new ArrayList<String>();
 for (Co item : items) {
  labels.add(item.quantity > 1
  ? item.name + " ×" + NumberFormat.getIntegerInstance(Locale.US).format(item.quantity) : item.name);
 }
 return labels.isEmpty() ? "none observed" : String.join(", ", labels);
}

static String ajj(int icon) {
 if (icon == SkullIcon.NONE) return "none";
 if (icon == SkullIcon.SKULL) return "skulled";
 if (icon == SkullIcon.SKULL_FIGHT_PIT) return "Fight Pits";
 if (icon == SkullIcon.SKULL_HIGH_RISK) return "high-risk";
 if (icon == SkullIcon.FORINTHRY_SURGE) return "Forinthry surge";
 if (icon == SkullIcon.SKULL_DEADMAN) return "Deadman";
 int aoo = icon == SkullIcon.LOOT_KEYS_ONE || icon == SkullIcon.FORINTHRY_SURGE_KEYS_ONE ? 1
 : icon == SkullIcon.LOOT_KEYS_TWO || icon == SkullIcon.FORINTHRY_SURGE_KEYS_TWO ? 2
 : icon == SkullIcon.LOOT_KEYS_THREE || icon == SkullIcon.FORINTHRY_SURGE_KEYS_THREE ? 3
 : icon == SkullIcon.LOOT_KEYS_FOUR || icon == SkullIcon.FORINTHRY_SURGE_KEYS_FOUR ? 4
 : icon == SkullIcon.LOOT_KEYS_FIVE || icon == SkullIcon.FORINTHRY_SURGE_KEYS_FIVE ? 5 : 0;
 if (aoo > 0) return "loot keys ×" + aoo;
 return "icon " + icon;
}

static String agv(int itemId, IntFunction<String> itemName) {
 String name = itemName == null ? null : itemName.apply(itemId);
 return Ag.awq(name, "item #" + itemId);
}

static void kb(ArrayDeque<Widget> pending, Widget[] children) {
 if (children == null) return;
 for (Widget child : children) {
  if (child != null) pending.addLast(child);
 }
}

@AllArgsConstructor
static class Co {
 final String name;
 final long quantity;
}
}
