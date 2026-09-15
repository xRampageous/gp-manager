package com.gpmanager.model;

/** Persisted ownership of a paused session. NONE means the session is running. */
public enum PauseReason
{
    NONE,
    MANUAL,
    IDLE,
    LIFECYCLE,
    STOPPED,
    RECOVERY,
    CUSTOM_SESSION
}
