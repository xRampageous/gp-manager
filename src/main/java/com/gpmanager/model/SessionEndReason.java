package com.gpmanager.model;

/** Why a named session was actually closed. A null value means it is still open or legacy. */
public enum SessionEndReason
{
    MANUAL,
    BOUNDARY,
    IDLE,
    SHUTDOWN
}
