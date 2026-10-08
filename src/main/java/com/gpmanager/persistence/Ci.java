package com.gpmanager;
/**
* Outcome of ordinary and reset persistence attempts.
*
* <p>Successful saves stay quiet. Persistent failures expose a compact warning
* with details and a safe retry action in the panel.
*/
class Ci {
enum State {
 NEVER_SAVED, OK, FAILED, CONFLICT
}

State state;
String detail;
boolean retryAvailable;
String recoveredFrom;
Ci(State /* state */a, String /* detail */b, boolean /* retryAvailable */c, String /* recoveredFrom */d) {
 this.state = a;
 this.detail = Ag.axw(b);
 this.retryAvailable = c;
 this.recoveredFrom = Ag.axw(d);
}

static Ci abd() {
 return new Ci(State.NEVER_SAVED, "", false, "");
}

boolean auq() {
 return state == State.FAILED || state == State.CONFLICT;
}
}
