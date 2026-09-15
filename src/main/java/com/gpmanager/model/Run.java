package com.gpmanager.model;

import java.util.UUID;

/** Boundary metadata for a run owned by one {@link ProfitSession}. */
public final class Run
{
    private String id;
    private String name;
    private long startedActiveElapsedMillis;
    /** Wall-clock start used for profile-local daily run counts; zero on legacy runs. */
    private long startedAtEpochMillis;
    private long endedActiveElapsedMillis;
    private boolean closed;
    private boolean legacyUnsplit;
    private String startReason;
    private String endReason;

    public Run()
    {
        // Gson
    }

    public Run(String name, long startedActiveElapsedMillis)
    {
        this(name, startedActiveElapsedMillis, false, "EXPLICIT_START", 0L);
    }

    public Run(String name, long startedActiveElapsedMillis, boolean legacyUnsplit, String startReason)
    {
        this(name, startedActiveElapsedMillis, legacyUnsplit, startReason, 0L);
    }

    public Run(String name, long startedActiveElapsedMillis, boolean legacyUnsplit,
        String startReason, long startedAtEpochMillis)
    {
        this.id = UUID.randomUUID().toString();
        this.name = normalizeName(name);
        this.startedActiveElapsedMillis = Math.max(0L, startedActiveElapsedMillis);
        this.startedAtEpochMillis = Math.max(0L, startedAtEpochMillis);
        this.legacyUnsplit = legacyUnsplit;
        this.startReason = normalizeReason(startReason, legacyUnsplit ? "LEGACY_MIGRATION" : "EXPLICIT_START");
        this.endReason = "";
    }

    void close(long activeElapsedMillis, String reason)
    {
        if (closed)
        {
            return;
        }
        endedActiveElapsedMillis = Math.max(startedActiveElapsedMillis, activeElapsedMillis);
        closed = true;
        endReason = normalizeReason(reason, "EXPLICIT_STOP");
    }

    /** Reopens an empty stop/resume placeholder without creating a new run identity. */
    void reopen(long activeElapsedMillis, long startedAtEpochMillis)
    {
        if (!closed)
        {
            return;
        }
        startedActiveElapsedMillis = Math.max(0L, activeElapsedMillis);
        this.startedAtEpochMillis = Math.max(0L, startedAtEpochMillis);
        endedActiveElapsedMillis = 0L;
        closed = false;
        startReason = "RESUME_EMPTY";
        endReason = "";
    }

    public long durationAt(long activeElapsedMillis)
    {
        long end = closed ? endedActiveElapsedMillis : Math.max(startedActiveElapsedMillis, activeElapsedMillis);
        return Math.max(0L, end - startedActiveElapsedMillis);
    }

    public String getId()
    {
        if (id == null || id.isEmpty())
        {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public String getName() { return normalizeName(name); }
    public long getStartedActiveElapsedMillis() { return Math.max(0L, startedActiveElapsedMillis); }
    public long getStartedAtEpochMillis() { return Math.max(0L, startedAtEpochMillis); }
    public long getEndedActiveElapsedMillis() { return Math.max(0L, endedActiveElapsedMillis); }
    public boolean isClosed() { return closed; }
    public boolean isLegacyUnsplit() { return legacyUnsplit; }
    public String getStartReason() { return normalizeReason(startReason, "EXPLICIT_START"); }
    public String getEndReason() { return endReason == null ? "" : endReason; }

    private static String normalizeName(String value)
    {
        return value == null || value.trim().isEmpty() ? "Run" : value.trim();
    }

    private static String normalizeReason(String value, String fallback)
    {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
