package com.gpmanager.ui.bento;

import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.SessionSummary;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Net of a finished session, computed once. A closed session's figures never change (prices
 * are captured at booking), so a year of history costs one summary each, not one per refresh.
 */
final class SessionNetCache
{
    private static final Map<String, Long> CLOSED = new ConcurrentHashMap<>();

    private SessionNetCache()
    {
    }

    static long net(GpManagerEngine engine, ProfitSession s, long now)
    {
        if (!s.isClosed())
        {
            SessionSummary summary = engine.getHistorySummary(s.getId(), now);
            return summary == null ? 0L : summary.getMetrics().getNet();
        }
        Long cached = CLOSED.get(s.getId());
        if (cached != null)
        {
            return cached;
        }
        SessionSummary summary = engine.getHistorySummary(s.getId(), now);
        long net = summary == null ? 0L : summary.getMetrics().getNet();
        CLOSED.put(s.getId(), net);
        return net;
    }

    private static final Map<String, Long> ACTIVE = new ConcurrentHashMap<>();

    /** Active play time of a finished session, computed once. */
    static long activeMillis(GpManagerEngine engine, ProfitSession s, long now)
    {
        if (!s.isClosed())
        {
            SessionSummary summary = engine.getHistorySummary(s.getId(), now);
            return summary == null ? 0L : summary.getMetrics().getElapsedMillis();
        }
        Long cached = ACTIVE.get(s.getId());
        if (cached != null)
        {
            return cached;
        }
        SessionSummary summary = engine.getHistorySummary(s.getId(), now);
        long millis = summary == null ? 0L : summary.getMetrics().getElapsedMillis();
        ACTIVE.put(s.getId(), millis);
        return millis;
    }

    /** Corrections and deletions change history; forget what we knew. */
    static void invalidate()
    {
        CLOSED.clear();
        ACTIVE.clear();
    }
}
