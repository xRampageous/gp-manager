package com.gpmanager;
import javax.inject.Singleton;
/**
* Presentation-only character idle (Idle Notifier–shaped). Never pauses accounting
* or changes GP/hr — session AFK pause stays separate.
*
* <p>Busy when animating, interacting, or recent skilling XP (Make-X / RC gaps). Idle badge
* after hard-busy clears for {@link #delayMillis} (fixed at 5 s, SIDEBAR_SPEC.md §6b), or
* {@link #delayMillis} after the last skilling XP when XP was the only soft-busy
* (no double-wait).
*/
@Singleton
class CharacterIdleModel {
long delayMillis = 5_000L;
long idleCandidateSinceEpochMillis;
long lastSkillingXpEpochMillis;
boolean characterIdle;
/** Soft-busy for Make-X / process gaps — presentation only. */
synchronized void abn(long now) {
 lastSkillingXpEpochMillis = Ae.nonNeg(now);
 characterIdle = false;
 idleCandidateSinceEpochMillis = 0L;
}

/**
* @param animating true when local animation != -1
* @param interacting true when local player has an interact target
* @return true once when character idle newly becomes true
*/
synchronized boolean tick(boolean animating, boolean interacting, long now) {
 boolean atj = lastSkillingXpEpochMillis > 0L && now - lastSkillingXpEpochMillis < delayMillis;
 boolean aoj = animating || interacting;
 if (aoj || atj) {
  if (aoj) {
   // Animation / interact: full Idle delay after clear.
   idleCandidateSinceEpochMillis = 0L;
  } else {
   // XP soft-busy alone: Idle fires delayMillis after the XP drop —
   // do not stack a second full delay when the soft window ends.
   idleCandidateSinceEpochMillis = lastSkillingXpEpochMillis;
  }
  characterIdle = false;
  return false;
 }
 if (idleCandidateSinceEpochMillis <= 0L) idleCandidateSinceEpochMillis = now;
 if (now - idleCandidateSinceEpochMillis < delayMillis) {
  characterIdle = false;
  return false;
 }
 if (!characterIdle) {
  characterIdle = true;
  return true;
 }
 return false;
}

/** Logout / hop — reset like Idle Notifier timers. */
synchronized void clear() {
 idleCandidateSinceEpochMillis = 0L;
 lastSkillingXpEpochMillis = 0L;
 characterIdle = false;
}
}
