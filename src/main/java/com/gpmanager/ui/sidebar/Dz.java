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
class Dz {
static final Dz NONE = new Dz(false, null, 0L, Bo.NONE, "", false, "", false);
boolean idle;
/** Presentation-only item filter; null means every booked row stays visible. */
RecentFilter filter;
/** Hide gained rows whose unit price is below this value; 0 shows all. Never hides costs. */
long minimumDisplayedLootValue;
/** Cached client-thread PvP sample; presentation only. */
Bo pvp;
/** Activity context label for the status line; empty means "use the owner name". */
String activity;
/** Freshest client-thread interaction target for the shared activity label; empty when none. */
String target;
/** The client is at the login screen; only read when no Grind is active. */
boolean offline;
/** A world hop is in flight: presentation says Reconnecting, never Logged out. */
boolean hopping;
Dz(boolean /* idle */a, RecentFilter /* filter */b, long /* minimumDisplayedLootValue */c, Bo /* pvp */d, String /* activity */e,
boolean /* offline */f, String /* target */g) {
 this(a, b, c, d, e, f, g, false);
}

Dz(boolean /* idle */a, RecentFilter /* filter */b, long /* minimumDisplayedLootValue */c, Bo /* pvp */d, String /* activity */e,
boolean /* offline */f, String /* target */g, boolean /* hopping */h) {
 this.idle = a;
 this.filter = b;
 this.minimumDisplayedLootValue = Ae.nonNeg(c);
 this.pvp = d == null ? Bo.NONE : d;
 this.activity = Ag.axw(e);
 this.offline = f;
 this.target = Ag.axw(g);
 this.hopping = h;
}

/** Whether one flow stays on a presentation row under the current visibility settings. */
boolean td(Ab /* flow */a) {
 if (a == null) return false;
 // Costs, fees and deaths are never hidden by the display loot filter.
 if (a.quantityDelta <= 0L) return true;
 // Unknown / unpriced gains remain discoverable for Review; the minimum is
 // compared against the unit price so quantity cannot defeat it.
 if (a.unitPrice > 0 && a.unitPrice < minimumDisplayedLootValue) return false;
 return filter == null || filter.isFlowIncluded(a);
}
}
