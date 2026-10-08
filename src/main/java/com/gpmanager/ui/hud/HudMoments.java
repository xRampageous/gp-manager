package com.gpmanager;
import static com.gpmanager.Fmt.*;
/**
* The HUD+ moments, each a fact shown a few seconds on the contextual line: Net or time target
* reached, a drop worth 10M or more, and "<Grind> ended".
* EDT only.
*/
class HudMoments {
static final long SHOW_MILLIS = 4_000L;
String text = "";
long until;
String sessionKey = "";
boolean reached;
boolean timeReached;
String bigDropSaid = "";
/** Last custom Grind seen, for the ended moment. */
String grindName = "";
long grindNet;
long grindMillis;
/** @param bigDrop the newest fresh big drop's item name, or empty */
String update(Ca s, String /* bigDrop */a, long /* now */b) {
 boolean /* grind */c = s.hasSession && !s.freePlay;
 String /* key */d = s.sessionId;
 if (!d.equals(sessionKey)) {
  text = "";
  until = 0L;
  bigDropSaid = "";
  if (!grindName.isEmpty() && !c) {
   say(grindName + " ended · " + signed(grindNet) + " in " + ra(grindMillis), b);
  }
  sessionKey = d;
  reached = s.goal != null && s.goal.reached;
  timeReached = s.timeTargetMillis > 0L && s.elapsedMillis >= s.timeTargetMillis;
 }
 grindName = c ? s.ownerLabel : "";
 grindNet = s.net;
 grindMillis = s.elapsedMillis;
 if (s.goal != null && s.goal.reached && !reached) say("Target reached · " + signed(s.net), b);
 reached = s.goal != null && s.goal.reached;
 if (s.timeTargetMillis > 0L && s.elapsedMillis >= s.timeTargetMillis && !timeReached) {
  timeReached = true;
  say("Time target reached · " + ra(s.timeTargetMillis), b);
 }
 if (!a.isEmpty() && !a.equals(bigDropSaid)) say("Big drop · " + a, b);
 bigDropSaid = a;
 return b < until ? text : "";
}

/** A factory reset starts a new profile: no moment, no ended Grind and no "already said" survives. */
void reset() {
 text = "";
 until = 0L;
 sessionKey = "";
 reached = false;
 timeReached = false;
 bigDropSaid = "";
 grindName = "";
 grindNet = 0L;
 grindMillis = 0L;
}

void say(String /* value */a, long /* now */b) {
 text = a;
 until = b + SHOW_MILLIS;
}
}
