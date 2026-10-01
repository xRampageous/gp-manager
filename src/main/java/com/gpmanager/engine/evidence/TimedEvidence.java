package com.gpmanager;
import static java.lang.Math.*;
/**
* Evidence from one observed source with its own tick lifetime.
* Independent lifetimes keep bank transfers, consumption and own-drop evidence
* from suppressing unrelated inventory changes. Expired evidence has no bearing
* on classification; refreshes can extend its remaining lifetime.
*/
abstract class TimedEvidence {
int ticksRemaining;
protected TimedEvidence(int ticks) {
this.ticksRemaining = max(1, ticks);
}

/**
* Extends this evidence's remaining lifetime. Never shortens it — a
* fresh, weaker refresh must not cut short evidence armed with a
* longer window by an earlier, stronger signal.
*/
final void refresh(int ticks) {
ticksRemaining = max(ticksRemaining, max(1, ticks));
}

/**
* Advances this evidence by one game tick.
*
* @return true once this call has exhausted the remaining lifetime
*         (the caller should discard/null out its reference now).
*/
final boolean tick() {
return --ticksRemaining <= 0;
}

/** True once {@link #tick()} has exhausted the lifetime. */
final boolean isExpired() {
return ticksRemaining <= 0;
}

final int ticksRemaining() {
return ticksRemaining;
}
}
