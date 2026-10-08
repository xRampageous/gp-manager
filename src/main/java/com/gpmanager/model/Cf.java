package com.gpmanager;
import static com.gpmanager.Ae.nonNeg;
import static java.lang.Math.*;
/**
* Profile-level PvP retention state: the monotonic completion sequence and the detail floor.
* Never a booking authority; never grows with lifetime encounter count.
*/
class Cf {
long nextCompletionSequence;
/** Highest compacted completion sequence; monotone so removed detail can never backfill. */
long detailFloorSequence = -1L;
long km() {
 long /* allocated */a = nonNeg(nextCompletionSequence);
 nextCompletionSequence = a + 1L;
 return a;
}

void adv(long /* value */a) {
 detailFloorSequence = max(detailFloorSequence, nonNeg(a));
}
}
