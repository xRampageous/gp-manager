package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detached snapshot of the active session's unresolved owner decisions. */
public final class ReviewInbox
{
    private final String sessionId;
    private final List<ReviewRow> rows;
    private final Map<String, Integer> countsByReason;
    private final long oldestAgeMillis;

    public ReviewInbox(String sessionId, List<ReviewRow> rows,
        Map<String, Integer> countsByReason, long oldestAgeMillis)
    {
        this.sessionId = sessionId == null ? "" : sessionId;
        this.rows = Collections.unmodifiableList(rows == null
            ? new ArrayList<ReviewRow>() : new ArrayList<>(rows));
        this.countsByReason = Collections.unmodifiableMap(countsByReason == null
            ? new LinkedHashMap<String, Integer>() : new LinkedHashMap<>(countsByReason));
        this.oldestAgeMillis = Math.max(0L, oldestAgeMillis);
    }

    public static ReviewInbox empty()
    {
        return new ReviewInbox("", Collections.<ReviewRow>emptyList(),
            Collections.<String, Integer>emptyMap(), 0L);
    }

    public String getSessionId() { return sessionId; }
    public List<ReviewRow> getRows() { return rows; }
    public int getPendingCount() { return rows.size(); }
    public Map<String, Integer> getCountsByReason() { return countsByReason; }
    public long getOldestAgeMillis() { return oldestAgeMillis; }
}
