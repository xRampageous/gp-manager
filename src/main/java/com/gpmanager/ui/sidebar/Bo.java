package com.gpmanager;
import java.util.*;
import lombok.AllArgsConstructor;
/**
* Client-side PvP facts the plugin observes on its tick (Wilderness / PvP world, skull,
* Protect Item, what a death would lose). Presentation only; nothing here touches accounting.
*/
@AllArgsConstructor
class Bo {
static final Bo NONE = new Bo(false, false, false, 0L);
boolean pvpPossible;
boolean skulled;
boolean protectItem;
/** GP a death here would lose; 0 where no death can be lost. */
long risk;

/**
* Every held unit but the most valuable 3 (none when skulled), one more with Protect Item, at
* today's prices; an unpriced unit counts as 0. A stack keeps units, not the whole stack.
*/
static long risk(List<Ab> /* held */c, boolean /* skulled */d, boolean /* protectItem */e) {
 var /* stacks */f = new ArrayList<Ab>();
 for (Ab /* flow */g : c) {
  if (g != null && g.quantityDelta > 0L) f.add(g);
 }
 f.sort((a, b) -> Long.compare(b.unitPrice, a.unitPrice));
 long /* keep */h = (d ? 0 : 3) + (e ? 1 : 0);
 long /* risk */i = 0L;
 for (Ab /* stack */j : f) {
  long /* kept */k = Math.min(h, j.quantityDelta);
  h -= k;
  i = Ae.safeAdd(i, Math.max(0L, j.unitPrice) * (j.quantityDelta - k));
 }
 return i;
}
}
