package com.gpmanager.model;

/** Persisted ownership provenance for profile sessions. */
public enum SessionOwnerKind
{
    /** The durable automatic/General owner; it is not a named session. */
    FREE_PLAY,
    /** A user-created session with an explicit session name. */
    NAMED_SESSION,
    /** Legacy or otherwise unprovable ownership; never infer from mutable labels. */
    UNKNOWN
}
