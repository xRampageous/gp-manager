package com.gpmanager.model;

/** Latest durable wealth capture with its age at the time of the read. */
public final class LatestWealthSnapshot
{
    private final WealthSnapshotHistory.Snapshot snapshot;
    private final long ageMillis;

    public LatestWealthSnapshot(WealthSnapshotHistory.Snapshot snapshot, long nowEpochMillis)
    {
        this.snapshot = snapshot;
        this.ageMillis = snapshot == null ? 0L
            : Math.max(0L, nowEpochMillis - snapshot.getCapturedAtEpochMillis());
    }

    public WealthSnapshotHistory.Snapshot getSnapshot() { return snapshot; }
    public long getAgeMillis() { return ageMillis; }
    public long getCapturedAtEpochMillis()
    {
        return snapshot == null ? 0L : snapshot.getCapturedAtEpochMillis();
    }
    public boolean isAvailable() { return snapshot != null; }
}
