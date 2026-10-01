package com.gpmanager;
import static com.gpmanager.SafeMath.nonNeg;
import static java.lang.Math.*;
/**
* Profile-level PvP retention state: the monotonic completion sequence and the detail floor.
* Never a booking authority; never grows with lifetime encounter count.
*/
class PkHistoryState {
long nextCompletionSequence;
/** Highest compacted completion sequence; monotone so removed detail can never backfill. */
long detailFloorSequence = -1L;
long allocateCompletionSequence() {
long allocated = nonNeg(nextCompletionSequence);
nextCompletionSequence = allocated + 1L;
return allocated;
}

void raiseDetailFloorSequence(long value) {
detailFloorSequence = max(detailFloorSequence, nonNeg(value));
}
}
