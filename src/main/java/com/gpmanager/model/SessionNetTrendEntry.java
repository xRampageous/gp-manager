package com.gpmanager.model;

/** One session in the net trend; excluded sessions stay visible and are flagged. */
public final class SessionNetTrendEntry
{
    private final String sessionId;
    private final String sessionName;
    private final long startedAtEpochMillis;
    private final long netGp;
    private final long activeMillis;
    private final boolean excludedFromAverages;
    private final boolean netAvailable;

    public SessionNetTrendEntry(String sessionId, String sessionName, long startedAtEpochMillis,
        long netGp, long activeMillis, boolean excludedFromAverages, boolean netAvailable)
    {
        this.sessionId = sessionId == null ? "" : sessionId;
        this.sessionName = sessionName == null ? "" : sessionName;
        this.startedAtEpochMillis = Math.max(0L, startedAtEpochMillis);
        this.netGp = netGp;
        this.activeMillis = Math.max(0L, activeMillis);
        this.excludedFromAverages = excludedFromAverages;
        this.netAvailable = netAvailable;
    }

    public String getSessionId() { return sessionId; }
    public String getSessionName() { return sessionName; }
    public long getStartedAtEpochMillis() { return startedAtEpochMillis; }
    public long getNetGp() { return netGp; }
    public long getActiveMillis() { return activeMillis; }
    public boolean isExcludedFromAverages() { return excludedFromAverages; }
    public boolean isNetAvailable() { return netAvailable; }
}
