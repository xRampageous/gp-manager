package com.gpmanager;
/** Persisted ownership of a paused session. NONE means the session is running. */
enum PauseReason {
NONE, MANUAL, IDLE, LIFECYCLE, RECOVERY, CUSTOM_SESSION
}
