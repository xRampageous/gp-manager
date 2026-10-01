package com.gpmanager;
import static com.gpmanager.GameData.msg;
import java.util.*;
import lombok.*;
/**
* Bounded evidence that a component loss may be loading an observed charged weapon.
* Item-on-item intent alone has no quantity and therefore cannot partition a loss.
* Later same-target measured Check differences reconcile the deferred component
* flows and separately own charge-use costs.
*/
class ChargeLoadTransferEvidence {
static final String AMBIGUOUS_QUANTITY_NOTE = msg("b");
ChargeRead.Variant variant;
int selectedItemId = -1;
String targetIdentity;
int ticksRemaining;
/** Arm only for a supported item-on-item target and a measured charge component, with the
* stable target identity used by the corresponding Check menu action. */
synchronized boolean arm(ChargeRead.Variant variant, int selectedItemId, String selectedItemName, String targetIdentity,
int ticks) {
 if (variant == null || ticks <= 0 || !ChargeRead.isSupportedLoadComponent(variant, selectedItemId, selectedItemName)) {
  return false;
 }
 this.variant = variant;
 this.selectedItemId = selectedItemId;
 this.targetIdentity = ModelText.nonBlank(targetIdentity, null);
 this.ticksRemaining = ticks;
 return true;
}

/**
* Partition recipe component losses after the selected component is observed.
* An item-on-item menu action has no quantity, so candidate losses are held
* separately for later measured Check reconciliation.
*/
synchronized Partition partition(List<Flow> flows) {
 var supportedLosses = new ArrayList<Flow>();
 var remaining = new ArrayList<Flow>();
 boolean selectedComponentLost = false;
 for (Flow flow : variant == null || ticksRemaining <= 0 || flows == null ? Collections.<Flow>emptyList() : flows) {
  if (flow != null && flow.isCost() && ChargeRead.isSupportedLoadComponent(variant, flow.itemId, flow.itemName)) {
   supportedLosses.add(flow);
   selectedComponentLost |= flow.itemId == selectedItemId;
  } else if (flow != null) {
   remaining.add(flow);
  }
 }
 // Hold every supported component lost with the selected component.
 // The next same-target Check may measure a recipe-level load; for a
 // blowpipe it can confirm scales only and leaves darts in Review.
 return selectedComponentLost ? new Partition(variant, targetIdentity, remaining, supportedLosses)
 : new Partition(null, null, flows == null ? Collections.emptyList() : flows, Collections.emptyList());
}

synchronized void tick() {
 if (ticksRemaining > 0 && --ticksRemaining <= 0) clear();
}

synchronized void clear() {
 variant = null;
 selectedItemId = -1;
 targetIdentity = null;
 ticksRemaining = 0;
}

@AllArgsConstructor
static class Partition {
 final ChargeRead.Variant variant;
 final String targetIdentity;
 final List<Flow> remaining;
 final List<Flow> ambiguousCandidates;
}
}
