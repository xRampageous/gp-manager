package com.gpmanager;
/**
* Plugin-side facts the Live read model cannot read from the engine: the current owner's
* idle-badge state, the presentation marks noted for that owner, the loot visibility
* preferences, the cached client-thread PvP sample and the activity context label.
*
* <p>Forward-port note: the historical context also carried the notable-drop threshold,
* the live encounter summary and the sidebar net-graph toggle. Those producers no longer
* exist in schema 102, so nothing is fabricated here; the fields that remain are the ones
* the current plugin can state truthfully.</p>
*/
class LiveContext {
static final LiveContext NONE = new LiveContext(false, null, 0L, PvpState.NONE, "", false, "", false);
final boolean idle;
/** Presentation-only item filter; null means every booked row stays visible. */
final RecentFilter filter;
/** Hide gained rows whose unit price is below this value; 0 shows all. Never hides costs. */
final long minimumDisplayedLootValue;
/** Cached client-thread PvP sample; presentation only. */
final PvpState pvp;
/** Activity context label for the status line; empty means "use the owner name". */
final String activity;
/** Freshest client-thread interaction target for the shared activity label; empty when none. */
final String target;
/** The client is at the login screen; only read when no Grind is active. */
final boolean offline;
/** A world hop is in flight: presentation says Reconnecting, never Logged out. */
final boolean hopping;
LiveContext(boolean idle, RecentFilter filter, long minimumDisplayedLootValue, PvpState pvp, String activity,
boolean offline, String target) {
 this(idle, filter, minimumDisplayedLootValue, pvp, activity, offline, target, false);
}

LiveContext(boolean idle, RecentFilter filter, long minimumDisplayedLootValue, PvpState pvp, String activity,
boolean offline, String target, boolean hopping) {
 this.idle = idle;
 this.filter = filter;
 this.minimumDisplayedLootValue = SafeMath.nonNeg(minimumDisplayedLootValue);
 this.pvp = pvp == null ? PvpState.NONE : pvp;
 this.activity = ModelText.orEmpty(activity);
 this.offline = offline;
 this.target = ModelText.orEmpty(target);
 this.hopping = hopping;
}

/** Whether one flow stays on a presentation row under the current visibility settings. */
boolean flowVisible(Flow flow) {
 if (flow == null) return false;
 // Costs, fees and deaths are never hidden by the display loot filter.
 if (flow.quantityDelta <= 0L) return true;
 // Unknown / unpriced gains remain discoverable for Review; the minimum is
 // compared against the unit price so quantity cannot defeat it.
 if (flow.unitPrice > 0 && flow.unitPrice < minimumDisplayedLootValue) return false;
 return filter == null || filter.isFlowIncluded(flow);
}
}
