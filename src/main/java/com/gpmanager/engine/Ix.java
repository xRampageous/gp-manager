package com.gpmanager;
import java.util.*;
import javax.inject.*;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;
/**
* The item lookups accounting needs, in one place: the canonical id, the composition, the active
* market quote and a name search. Production answers from RuneLite's ItemManager; the rework's
* replay answers from a recording (docs/REWORK_PLAN.md), so a session books the same both ways.
*/
@Singleton
class Ix {
ItemManager m;
@Inject
Ix(ItemManager m) {
 this.m = m;
}

int canon(int /* id */a) {
 return m == null ? a : m.canonicalize(a);
}

ItemComposition comp(int /* id */a) {
 return m == null ? null : m.getItemComposition(a);
}

long price(int /* id */a) {
 return m == null ? 0L : m.getItemPriceWithSource(a, true);
}

List<ItemPrice> find(String /* name */a) {
 return m == null ? Collections.emptyList() : m.search(a);
}
}
