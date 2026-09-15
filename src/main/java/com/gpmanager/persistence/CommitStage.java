package com.gpmanager.persistence;

/**
 * Stages for the recoverable reset/replace commit protocol.
 *
 * <p>Replacement becomes <b>committed</b> when the primary {@code sessions.json}
 * has been replaced with a validated next payload. The backup update is recovery
 * hygiene that must still complete, but a crash after primary replacement must
 * not resurrect cleared data from an older backup.
 */
public enum CommitStage
{
    NONE,
    /** Validated next payload written; primary not yet replaced. */
    NEXT_READY,
    /** Primary replaced with the new state; backup may still be stale. */
    PRIMARY_COMMITTED,
    /** Primary and backup both match the committed reset/save. */
    COMPLETE
}
