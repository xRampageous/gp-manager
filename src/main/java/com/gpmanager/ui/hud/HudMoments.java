package com.gpmanager;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.Ad.hourly;
/**
* The HUD+ moments, each a fact shown a few seconds on the contextual line: Net or time target
* reached, new best Net or GP/h for the same Grind, a drop worth 10M or more, and "<Grind> ended".
* EDT only.
*/
class HudMoments {
static final long SHOW_MILLIS = 4_000L;
String text = "";
long until;
String sessionKey = "";
boolean reached;
boolean timeReached;
boolean bestNetSaid;
boolean bestRateSaid;
String bigDropSaid = "";
/** Last custom Grind seen, for the ended moment. */
String grindName = "";
long grindNet;
long grindMillis;
/** Set when a Grind just ended; {@link #avn} reads it once. */
String ended = "";
long endedNet;
long endedMillis;
/**
* @param bestNet best completed Net of this Grind, or null with no history (no best moment)
* @param bestRate best completed GP/h of this Grind, or null
* @param bigDrop the newest fresh big drop's item name, or empty
*/
String update(Ca s, Long bestNet, Long bestRate, String bigDrop, long now) {
boolean grind = s.hasSession && !s.freePlay;
String key = s.sessionId;
if (!key.equals(sessionKey)) {
text = "";
until = 0L;
bigDropSaid = "";
if (!grindName.isEmpty() && !grind) {
ended = grindName;
endedNet = grindNet;
endedMillis = grindMillis;
say(grindName + " ended · " + signed(grindNet) + " in " + ra(grindMillis), now);
}
sessionKey = key;
reached = s.goal != null && s.goal.reached;
timeReached = s.timeTargetMillis > 0L && s.elapsedMillis >= s.timeTargetMillis;
bestNetSaid = false;
bestRateSaid = false;
}
grindName = grind ? s.ownerLabel : "";
grindNet = s.net;
grindMillis = s.elapsedMillis;
if (s.goal != null && s.goal.reached && !reached) {
say("Target reached · " + signed(s.net), now);
}
reached = s.goal != null && s.goal.reached;
if (s.timeTargetMillis > 0L && s.elapsedMillis >= s.timeTargetMillis && !timeReached) {
timeReached = true;
say("Time target reached · " + ra(s.timeTargetMillis), now);
}
if (grind && bestNet != null && !bestNetSaid && s.net > bestNet && s.net > 0L) {
bestNetSaid = true;
say("New best Net for " + s.ownerLabel, now);
}
if (grind && bestRate != null && !bestRateSaid && s.elapsedMillis >= 60_000L
&& hourly(s.net, s.elapsedMillis) > bestRate && hourly(s.net, s.elapsedMillis) > 0L) {
// Owner 2026-10-01 (F04): a PB claim compares whole-run rates, never the rolling pace.
bestRateSaid = true;
say("New best GP/h · " + rate(hourly(s.net, s.elapsedMillis)) + "/h", now);
}
if (!bigDrop.isEmpty() && !bigDrop.equals(bigDropSaid)) {
say("Big drop · " + bigDrop, now);
}
bigDropSaid = bigDrop;
return now < until ? text : "";
}
/** The Grind that just ended (its Net and time in endedNet / endedMillis), once. */
String avn() {
String value = ended;
ended = "";
return value;
}
/** A factory reset starts a new profile: no moment, no ended Grind and no "already said" survives. */
void reset() {
text = "";
until = 0L;
sessionKey = "";
reached = false;
timeReached = false;
bestNetSaid = false;
bestRateSaid = false;
bigDropSaid = "";
grindName = "";
grindNet = 0L;
grindMillis = 0L;
ended = "";
endedNet = 0L;
endedMillis = 0L;
}
void say(String value, long now) {
text = value;
until = now + SHOW_MILLIS;
}
}
