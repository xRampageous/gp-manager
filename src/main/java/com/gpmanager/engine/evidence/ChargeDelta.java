package com.gpmanager;
import java.util.*;
import lombok.*;
import net.runelite.api.gameval.ItemID;
import static java.lang.Math.*;
import static com.gpmanager.PriceSource.*;
/** Measured negative component-count differences; valuation is applied by the caller. */
@Getter
@AllArgsConstructor
class ChargeDelta {
final ChargeRead.Variant variant;
final List<ComponentDelta> componentDeltas;
/** One component's measured decrease (always negative). */
@Getter
@AllArgsConstructor
static class ComponentDelta {
 final int itemId;
 final long quantityDelta;
}

/**
* The GP cost of these priced component losses when they exactly match every measured
* decrease; 0 otherwise. Missing, partial, extra, unsupported-priced or overflowing flows fail
* closed as a whole, as does any component still represented by an unresolved same-variant
* charge-load Review row. Generic inventory losses can never authorize charge spend.
*/
long exactCost(List<Flow> pricedLosses, Set<Integer> pendingLoadComponentIds) {
 if (variant == null || !variant.isImplemented() || componentDeltas.isEmpty() || pricedLosses == null
 || pricedLosses.size() != componentDeltas.size()) {
  return 0L;
 }
 var expected = new HashMap<Integer, Long>();
 var actual = new HashMap<Integer, Long>();
 long cost = 0L;
 try {
  for (ComponentDelta component : componentDeltas) {
   if (pendingLoadComponentIds.contains(component.itemId) || component.itemId <= 0) return 0L;
   expected.merge(component.itemId, component.quantityDelta, Math::addExact);
  }
  for (Flow flow : pricedLosses) {
   boolean validPriceSource = flow != null && (flow.itemId == ItemID.COINS
   ? flow.unitPrice == 1 && flow.getPriceSource() == FACE_VALUE : flow.getPriceSource() == GRAND_EXCHANGE
   || flow.getPriceSource() == MANUAL_OVERRIDE);
   if (!validPriceSource || flow.quantityDelta >= 0L || flow.itemId <= 0 || flow.unitPrice <= 0 || flow.valueDelta >= 0L
   || multiplyExact(flow.quantityDelta, (long) flow.unitPrice) != flow.valueDelta) {
    return 0L;
   }
   actual.merge(flow.itemId, flow.quantityDelta, Math::addExact);
   cost = addExact(cost, negateExact(flow.valueDelta));
  }
 } catch (ArithmeticException ex) {
  return 0L;
 }
 return expected.equals(actual) ? cost : 0L;
}
}
