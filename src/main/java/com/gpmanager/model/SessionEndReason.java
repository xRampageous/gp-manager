package com.gpmanager;
/** Why a named session was actually closed. A null value means it is still open or legacy. */
enum SessionEndReason {
MANUAL,
BOUNDARY,
IDLE,
SHUTDOWN
}
