package com.gpmanager;
import static com.gpmanager.Ae.nonNeg;
/**
* Read-only state for the local PvM death-reclaim indicator. The item count is the sum of
* measured outstanding stack quantities, and age is measured in active engine game ticks.
*/
class DeathReclaimStatus {
boolean awaiting;
boolean armed;
long outstandingItemCount;
long ageTicks;
DeathReclaimStatus(boolean /* awaiting */a, boolean /* armed */b, long /* outstandingItemCount */c, long /* ageTicks */d) {
 this.awaiting = a;
 this.armed = a && b;
 this.outstandingItemCount = nonNeg(c);
 this.ageTicks = nonNeg(d);
}
}
