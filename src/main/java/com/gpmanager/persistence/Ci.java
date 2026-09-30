package com.gpmanager;
/**
* Outcome of ordinary and reset persistence attempts.
*
* <p>Successful saves stay quiet. Persistent failures expose a compact warning
* with details and a safe retry action in the panel.
*/
class Ci {
enum State {
NEVER_SAVED,
OK,
FAILED,
CONFLICT
}
final State state;
final String detail;
final boolean retryAvailable;
final String recoveredFrom;
Ci(State state, String detail, boolean retryAvailable, String recoveredFrom) {
this.state = state;
this.detail = Ag.axw(detail);
this.retryAvailable = retryAvailable;
this.recoveredFrom = Ag.axw(recoveredFrom);
}
static Ci abd() {
return new Ci(State.NEVER_SAVED, "", false, "");
}
boolean auq() {
return state == State.FAILED || state == State.CONFLICT;
}
}
