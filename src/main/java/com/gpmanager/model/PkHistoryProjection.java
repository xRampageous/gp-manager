package com.gpmanager;
import lombok.Getter;
import static com.gpmanager.SafeMath.nonNeg;
/**
* Per-Session PvP projection: exact counts and the current streak on top of the finalized money
* facts, plus detail-compaction metadata. Mutable correction-eligible attribution lives only in
* {@link PkMutableAttribution} anchors; queries compose the two. Never a booking authority.
*/
class PkHistoryProjection extends PkProfileBase {
@Getter
int streak;
long compactedDetailCount;
/** Records one encounter's exact non-financial facts; money enters only at finalization. */
void recordEncounter(EncounterType type) {
 encounters++;
 if (type == EncounterType.KILL) {
  kills++;
  streak = streak < 0 ? 1 : streak + 1;
 } else {
  deaths++;
  streak = streak > 0 ? -1 : streak - 1;
 }
}

/** Marks one detailed encounter row as compacted away; exact facts are already retained. */
void markDetailCompacted() {
 compactedDetailCount++;
}

long getCompactedDetailCount() { return nonNeg(compactedDetailCount); }
long getRetainedDetailCount() { return nonNeg(getEncounters() - getCompactedDetailCount()); }
PkDetailScope getDetailScope() {
 return getCompactedDetailCount() == 0L ? PkDetailScope.COMPLETE_HISTORY : PkDetailScope.RETAINED_WINDOW;
}
}
