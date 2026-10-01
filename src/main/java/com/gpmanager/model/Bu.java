package com.gpmanager;
import static com.gpmanager.Ae.safeAdd;
/** Only the canonical money, active clock and rate facts consumed by presentation and export. */
class Bu {
final String activityHint;
final boolean paused;
final long elapsedMillis;
final long revenue;
final long costs;
final long net;
final long profitPerHour;
final long suppliesCosts;
final long otherCosts;
final boolean costSplitAvailable;
static Bu none() {
 return new Bu("General", false, 0L, 0L, 0L, 0L, 0L);
}

Bu(String activityHint, boolean paused, long elapsedMillis, long revenue, long costs,
long net, long profitPerHour) {
 this(activityHint, paused, elapsedMillis, revenue, costs, net, profitPerHour, 0L, 0L, false);
}

Bu(String activityHint, boolean paused, long elapsedMillis, long revenue, long costs,
long net, long profitPerHour, long suppliesCosts,
long otherCosts, boolean costSplitAvailable) {
 this.activityHint = activityHint;
 this.paused = paused;
 this.elapsedMillis = elapsedMillis;
 this.revenue = revenue;
 this.costs = costs;
 this.net = net;
 this.profitPerHour = profitPerHour;
 this.suppliesCosts = suppliesCosts;
 this.otherCosts = otherCosts;
 this.costSplitAvailable = costSplitAvailable && safeAdd(suppliesCosts, otherCosts) == costs;
}
}
