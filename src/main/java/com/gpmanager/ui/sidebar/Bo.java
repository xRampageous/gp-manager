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
final boolean pvpPossible;
final boolean skulled;
final boolean protectItem;
/** GP a death here would lose; 0 where no death can be lost. */
final long risk;

/**
* Every held unit but the most valuable 3 (none when skulled), one more with Protect Item, at
* today's prices; an unpriced unit counts as 0. A stack keeps units, not the whole stack.
*/
static long risk(List<Ab> held, boolean skulled, boolean protectItem) {
 var stacks = new ArrayList<Ab>();
 for (Ab flow : held) {
  if (flow != null && flow.quantityDelta > 0L) stacks.add(flow);
 }
 stacks.sort((a, b) -> Long.compare(b.unitPrice, a.unitPrice));
 long keep = (skulled ? 0 : 3) + (protectItem ? 1 : 0);
 long risk = 0L;
 for (Ab stack : stacks) {
  long kept = Math.min(keep, stack.quantityDelta);
  keep -= kept;
  risk = Ae.safeAdd(risk, Math.max(0L, stack.unitPrice) * (stack.quantityDelta - kept));
 }
 return risk;
}
}
