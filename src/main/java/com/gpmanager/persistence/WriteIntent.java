package com.gpmanager.persistence;

/**
 * Immutable destination and fencing for one persistence attempt.
 *
 * <p>Queued and retry writes must carry this intent so a later account switch
 * cannot redirect the payload. {@code expectedBaseRevision} is the on-disk
 * revision observed when the snapshot was taken; under the write lock the
 * repository refuses the save unless disk still matches.
 */
public final class WriteIntent
{
    private final TrackingIdentity identity;
    private final long scopeGeneration;
    private final long expectedBaseRevision;
    private final SavedState state;

    public WriteIntent(
        TrackingIdentity identity,
        long scopeGeneration,
        long expectedBaseRevision,
        SavedState state)
    {
        if (state == null)
        {
            throw new IllegalArgumentException("state");
        }
        this.identity = identity;
        this.scopeGeneration = scopeGeneration;
        this.expectedBaseRevision = Math.max(0L, expectedBaseRevision);
        this.state = state;
    }

    /** Single-scope / test saves without account identity. */
    public static WriteIntent unbound(long scopeGeneration, long expectedBaseRevision, SavedState state)
    {
        return new WriteIntent(null, scopeGeneration, expectedBaseRevision, state);
    }

    public TrackingIdentity getIdentity()
    {
        return identity;
    }

    public long getScopeGeneration()
    {
        return scopeGeneration;
    }

    public long getExpectedBaseRevision()
    {
        return expectedBaseRevision;
    }

    public SavedState getState()
    {
        return state;
    }

    public WriteIntent withExpectedBaseRevision(long nextBase)
    {
        return new WriteIntent(identity, scopeGeneration, nextBase, state);
    }

    public String ownerKey()
    {
        return identity == null ? state.getOwnerKey() : identity.getRsProfileKey();
    }
}
