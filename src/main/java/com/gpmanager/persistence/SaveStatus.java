package com.gpmanager;
/**
* Outcome of ordinary and reset persistence attempts.
*
* <p>Successful saves stay quiet. Persistent failures expose a compact warning
* with details and a safe retry action in the panel.
*/
class SaveStatus {
enum State {
 NEVER_SAVED, OK, FAILED, CONFLICT
}

final State state;
final String detail;
final boolean retryAvailable;
final String recoveredFrom;
SaveStatus(State state, String detail, boolean retryAvailable, String recoveredFrom) {
 this.state = state;
 this.detail = ModelText.orEmpty(detail);
 this.retryAvailable = retryAvailable;
 this.recoveredFrom = ModelText.orEmpty(recoveredFrom);
}

static SaveStatus neverSaved() {
 return new SaveStatus(State.NEVER_SAVED, "", false, "");
}

boolean isFailure() {
 return state == State.FAILED || state == State.CONFLICT;
}
}
