package com.gpmanager;
import java.util.*;
import lombok.*;
import net.runelite.api.gameval.ItemID;
import static java.lang.Math.*;
import static com.gpmanager.Av.*;
/** Measured negative component-count differences; valuation is applied by the caller. */
@Getter
@AllArgsConstructor
class Cm {
Ar.V variant;
List<ComponentDelta> componentDeltas;
/** Explicit Check outcome; this enum is transient accounting evidence, never save data. */
enum K { SEED, SAME, SPEND, LOAD, MISMATCH }
K k;
/** A caller supplying measured losses explicitly constructs SPEND, as before. */
Cm(Ar.V v, List<ComponentDelta> d) { this(v, d, K.SPEND); }
/** An outcome without a measured decrease carries no bookable component quantity. */
Cm(Ar.V v, K k) { this(v, Collections.emptyList(), k); }
/** One component's measured decrease (always negative). */
@Getter
@AllArgsConstructor
static class ComponentDelta {
 int itemId;
 long quantityDelta;
}

/**
* The GP cost of these priced component losses when they exactly match every measured
* decrease; 0 otherwise. Missing, partial, extra, unsupported-priced or overflowing flows fail
* closed as a whole, as does any component still represented by an unresolved same-variant
* charge-load Review row. Generic inventory losses can never authorize charge spend.
*/
long aui(List<Ab> /* pricedLosses */a, Set<Integer> /* pendingLoadComponentIds */b) {
 if (k != K.SPEND || variant == null || !variant.implemented || componentDeltas.isEmpty() || a == null
 || a.size() != componentDeltas.size()) {
  return 0L;
 }
 var /* expected */c = new HashMap<Integer, Long>();
 var /* actual */d = new HashMap<Integer, Long>();
 long /* cost */e = 0L;
 try {
  for (ComponentDelta /* component */f : componentDeltas) {
   if (b.contains(f.itemId) || f.itemId <= 0) return 0L;
   c.merge(f.itemId, f.quantityDelta, Math::addExact);
  }
  for (Ab /* flow */g : a) {
   boolean /* atw */h = g != null && (g.itemId == ItemID.COINS
   ? g.unitPrice == 1 && g.getPriceSource() == FACE_VALUE : g.getPriceSource() == GRAND_EXCHANGE
   || g.getPriceSource() == MANUAL_OVERRIDE);
   if (!h || g.quantityDelta >= 0L || g.itemId <= 0 || g.unitPrice <= 0 || g.valueDelta >= 0L
   || multiplyExact(g.quantityDelta, (long) g.unitPrice) != g.valueDelta) {
    return 0L;
   }
   d.merge(g.itemId, g.quantityDelta, Math::addExact);
   e = addExact(e, negateExact(g.valueDelta));
  }
 } catch (ArithmeticException /* ex */i) {
  return 0L;
 }
 return c.equals(d) ? e : 0L;
}
}
