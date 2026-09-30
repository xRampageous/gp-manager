package com.gpmanager;
import static com.gpmanager.Ak.msg;
import java.util.*;
import lombok.*;
/**
* Bounded evidence that a component loss may be loading an observed charged weapon.
* Item-on-item intent alone has no quantity and therefore cannot partition a loss.
* Later same-target measured Check differences reconcile the deferred component
* flows and separately own charge-use costs.
*/
class ChargeLoadTransferEvidence {
static final String AMBIGUOUS_QUANTITY_NOTE =
msg("b");
Ar.V variant;
int selectedItemId = -1;
String targetIdentity;
int ticksRemaining;
/** Arm only for a supported item-on-item target and a measured charge component, with the
* stable target identity used by the corresponding Check menu action. */
synchronized boolean arm(
Ar.V variant,
int selectedItemId,
String selectedItemName,
String targetIdentity,
int ticks) {
if (variant == null || ticks <= 0
|| !Ar.xq(variant, selectedItemId, selectedItemName)) {
return false;
}
this.variant = variant;
this.selectedItemId = selectedItemId;
this.targetIdentity = Ag.awq(targetIdentity, null);
this.ticksRemaining = ticks;
return true;
}
/**
* Partition recipe component losses after the selected component is observed.
* An item-on-item menu action has no quantity, so candidate losses are held
* separately for later measured Check reconciliation.
*/
synchronized Partition partition(List<Ab> flows) {
var aqu = new ArrayList<Ab>();
var remaining = new ArrayList<Ab>();
boolean aqh = false;
for (Ab flow : variant == null || ticksRemaining <= 0 || flows == null
? Collections.<Ab>emptyList() : flows) {
if (flow != null && flow.isCost()
&& Ar.xq(variant, flow.itemId, flow.itemName)) {
aqu.add(flow);
aqh |= flow.itemId == selectedItemId;
} else if (flow != null) {
remaining.add(flow);
}
}
// Hold every supported component lost with the selected component.
// The next same-target Check may measure a recipe-level load; for a
// blowpipe it can confirm scales only and leaves darts in Review.
return aqh
? new Partition(variant, targetIdentity, remaining, aqu)
: new Partition(null, null, flows == null ? Collections.emptyList() : flows, Collections.emptyList());
}
synchronized void tick() {
if (ticksRemaining > 0 && --ticksRemaining <= 0) {
clear();
}
}
synchronized void clear() {
variant = null;
selectedItemId = -1;
targetIdentity = null;
ticksRemaining = 0;
}
@AllArgsConstructor
static class Partition {
final Ar.V variant;
final String targetIdentity;
final List<Ab> remaining;
final List<Ab> ambiguousCandidates;
}
}
