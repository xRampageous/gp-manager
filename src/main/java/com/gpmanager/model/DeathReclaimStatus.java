package com.gpmanager;
import static com.gpmanager.Ae.nonNeg;
/**
* Read-only state for the local PvM death-reclaim indicator. The item count is the sum of
* measured outstanding stack quantities, and age is measured in active engine game ticks.
*/
class DeathReclaimStatus {
final boolean awaiting;
final boolean armed;
final long outstandingItemCount;
final long ageTicks;
DeathReclaimStatus(boolean awaiting, boolean armed, long outstandingItemCount, long ageTicks) {
 this.awaiting = awaiting;
 this.armed = awaiting && armed;
 this.outstandingItemCount = nonNeg(outstandingItemCount);
 this.ageTicks = nonNeg(ageTicks);
}
}
