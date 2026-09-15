package com.gpmanager.persistence;

/**
 * Outcome of ordinary and reset persistence attempts.
 *
 * <p>Successful saves stay quiet. Persistent failures expose a compact warning
 * with details and a safe retry action in the panel.
 */
public final class SaveStatus
{
    public enum State
    {
        NEVER_SAVED,
        OK,
        FAILED,
        CONFLICT,
        UNBOUND,
        LOCKED_OUT
    }

    private final State state;
    private final long revision;
    private final long lastSuccessEpochMillis;
    private final long lastDurationMillis;
    private final String detail;
    private final boolean retryAvailable;
    private final long lastBytes;
    private final long maxDurationMillis;
    private final int pendingWrites;
    private final long lastFailureEpochMillis;
    private final String recoveredFrom;

    public SaveStatus(
        State state,
        long revision,
        long lastSuccessEpochMillis,
        long lastDurationMillis,
        String detail,
        boolean retryAvailable)
    {
        this(state, revision, lastSuccessEpochMillis, lastDurationMillis, detail, retryAvailable,
            0L, 0L, 0, 0L, "");
    }

    public SaveStatus(
        State state, long revision, long lastSuccessEpochMillis, long lastDurationMillis,
        String detail, boolean retryAvailable, long lastBytes, long maxDurationMillis,
        int pendingWrites, long lastFailureEpochMillis, String recoveredFrom)
    {
        this.state = state == null ? State.NEVER_SAVED : state;
        this.revision = revision;
        this.lastSuccessEpochMillis = lastSuccessEpochMillis;
        this.lastDurationMillis = lastDurationMillis;
        this.detail = detail == null ? "" : detail;
        this.retryAvailable = retryAvailable;
        this.lastBytes = Math.max(0L, lastBytes);
        this.maxDurationMillis = Math.max(0L, maxDurationMillis);
        this.pendingWrites = Math.max(0, pendingWrites);
        this.lastFailureEpochMillis = Math.max(0L, lastFailureEpochMillis);
        this.recoveredFrom = recoveredFrom == null ? "" : recoveredFrom;
    }

    public static SaveStatus neverSaved()
    {
        return new SaveStatus(State.NEVER_SAVED, 0L, 0L, 0L, "", false);
    }

    public State getState()
    {
        return state;
    }

    public long getRevision()
    {
        return revision;
    }

    public long getLastSuccessEpochMillis()
    {
        return lastSuccessEpochMillis;
    }

    public long getLastDurationMillis()
    {
        return lastDurationMillis;
    }

    public String getDetail()
    {
        return detail;
    }

    public boolean isRetryAvailable()
    {
        return retryAvailable;
    }

    public long getLastBytes() { return lastBytes; }
    public long getMaxDurationMillis() { return maxDurationMillis; }
    public int getPendingWrites() { return pendingWrites; }
    public long getLastFailureEpochMillis() { return lastFailureEpochMillis; }
    public String getRecoveredFrom() { return recoveredFrom; }

    public boolean isFailure()
    {
        return state == State.FAILED || state == State.CONFLICT || state == State.LOCKED_OUT;
    }

    public SaveStatus withDetail(String nextDetail)
    {
        return new SaveStatus(state, revision, lastSuccessEpochMillis, lastDurationMillis, nextDetail, retryAvailable,
            lastBytes, maxDurationMillis, pendingWrites, lastFailureEpochMillis, recoveredFrom);
    }
}
