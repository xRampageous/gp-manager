package com.gpmanager.model;

/**
 * A passive historical segment inside a session. Segments are bookkeeping only;
 * they never influence or automate gameplay.
 */
public class ActivitySegment
{
    private String activity;
    private long startedAtEpochMillis;
    private long endedAtEpochMillis;

    public ActivitySegment()
    {
        // Gson
    }

    public ActivitySegment(String activity, long startedAtEpochMillis)
    {
        this.activity = normalize(activity);
        this.startedAtEpochMillis = Math.max(0L, startedAtEpochMillis);
    }

    public void close(long now)
    {
        if (endedAtEpochMillis == 0L)
        {
            endedAtEpochMillis = Math.max(startedAtEpochMillis, now);
        }
    }

    private static String normalize(String value)
    {
        return value == null || value.trim().isEmpty() ? "General" : value.trim();
    }

    public String getActivity()
    {
        return normalize(activity);
    }

    public long getStartedAtEpochMillis()
    {
        return startedAtEpochMillis;
    }

    public long getEndedAtEpochMillis()
    {
        return endedAtEpochMillis;
    }
}
