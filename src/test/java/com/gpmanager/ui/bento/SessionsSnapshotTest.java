package com.gpmanager.ui.bento;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.ContainerSnapshot;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.SessionSummary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Sessions page read model: what is live (free play or a named session), Overall today,
 * history grouped by day then collapsed spans, same-name folding, favourites, the auto tag,
 * and the same-activity average.
 */
public class SessionsSnapshotTest
{
    private static final int LOGS = 1519;
    private static final long DAY = 24L * 3_600_000L;

    @Test
    public void emptyEngineHasNoOwnerAndNoGroups()
    {
        SessionsSnapshot s = SessionsSnapshot.capture(engine(), 1_000L, false);
        assertNull(s.active);
        assertTrue(s.groups.isEmpty());
        assertEquals(0L, s.overallToday);
    }

    @Test
    public void freePlayOwnerHistoryGroupsAndOverallToday()
    {
        GpManagerEngine engine = engine();
        long now = noon();

        // Finished sessions: two today back to back (fold), yesterday, ~40 days ago (collapsed group).
        history(engine, "Vorkath", now - 3 * 3_600_000L, 40 * 60_000L, 30L);
        history(engine, "Vorkath", now - 2 * 3_600_000L, 40 * 60_000L, 20L);
        history(engine, "Zulrah", now - DAY - 3_600_000L, 40 * 60_000L, 30L);
        history(engine, "Vorkath", now - 40 * DAY, 40 * 60_000L, 10L);

        // Free play is live.
        long start = now - 20 * 60_000L;
        engine.restartGeneral(start, false, false);
        engine.togglePause(start);
        engine.setBaseline(snapshot(Collections.emptyMap()));
        settle(engine, snapshot(map(LOGS, 27L)), start + 60_000L);

        SessionsSnapshot s = SessionsSnapshot.capture(engine, now, false);
        assertNotNull(s.active);
        assertTrue(s.active.freePlay);
        assertEquals(SessionsSnapshot.FREE_PLAY, s.active.name);
        assertEquals(engine.getMetrics(now).getNet(), s.active.net);
        assertEquals(s.active.net, s.active.asStatement.net);
        assertEquals("live", s.active.asStatement.when);

        long todayNet = s.active.net;
        for (ProfitSession h : engine.getHistory())
        {
            if (h.getStartedAtEpochMillis() >= now - 4 * 3_600_000L)
            {
                todayNet += engine.getHistorySummary(h.getId(), now).getMetrics().getNet();
            }
        }
        assertEquals(todayNet, s.overallToday);
        assertEquals(2, s.sessionsToday);   // free play is not a session

        assertEquals(3, s.groups.size());
        SessionsSnapshot.Group today = s.groups.get(0);
        assertEquals("Today", today.label);
        assertTrue(today.recent);
        assertEquals(1, today.folds.size());
        assertEquals(2, today.folds.get(0).sessions.size());
        assertEquals("Vorkath", today.folds.get(0).name);
        assertEquals(2, today.sessionCount);

        SessionsSnapshot.Group yesterday = s.groups.get(1);
        assertEquals("Yesterday", yesterday.label);
        SessionsSnapshot.SessionRow row = yesterday.folds.get(0).first();
        assertEquals("Zulrah", row.name);
        SessionSummary summary = engine.getHistorySummary(row.id, now);
        assertEquals(summary.getMetrics().getNet(), row.net);
        assertEquals(row.net, yesterday.net);
        assertFalse(row.auto);

        assertFalse(s.groups.get(2).recent);
    }

    @Test
    public void archivedFreePlayCountsTowardOverallButIsNeverListed()
    {
        GpManagerEngine engine = engine();
        long now = noon();
        history(engine, "Vorkath", now - 3 * 3_600_000L, 30 * 60_000L, 10L);
        // Free play archived today via Archive free play (restartGeneral archives the owner).
        engine.togglePause(now - 2 * 3_600_000L);
        engine.setBaseline(snapshot(Collections.emptyMap()));
        settle(engine, snapshot(map(LOGS, 100L)), now - 2 * 3_600_000L + 60_000L);
        long freePlayNet = engine.getMetrics(now).getNet();
        engine.restartGeneral(now - 60 * 60_000L, true, false);
        engine.togglePause(now - 60 * 60_000L);

        SessionsSnapshot s = SessionsSnapshot.capture(engine, now, false);
        assertEquals(1, s.groups.size());
        assertEquals(1, s.groups.get(0).sessionCount);
        assertEquals("Vorkath", s.groups.get(0).folds.get(0).name);
        assertEquals(1, s.sessionsToday);
        long vorkath = s.groups.get(0).folds.get(0).net;
        assertEquals(vorkath + freePlayNet + s.active.net, s.overallToday);
        assertTrue(s.active.freePlay);
    }

    @Test
    public void foldingJoinsOnlyConsecutiveSameNames()
    {
        GpManagerEngine engine = engine();
        long now = noon();
        history(engine, "Vorkath", now - 5 * 3_600_000L, 30 * 60_000L, 10L);
        history(engine, "Zulrah", now - 4 * 3_600_000L, 30 * 60_000L, 10L);
        history(engine, "vorkath", now - 3 * 3_600_000L, 30 * 60_000L, 10L);
        history(engine, "Vorkath", now - 2 * 3_600_000L, 30 * 60_000L, 10L);
        SessionsSnapshot s = SessionsSnapshot.capture(engine, now, false);
        List<SessionsSnapshot.Fold> folds = s.groups.get(0).folds;
        assertEquals(3, folds.size());
        assertEquals(2, folds.get(0).sessions.size());   // newest two Vorkath (case-insensitive)
        assertEquals("Zulrah", folds.get(1).name);
        assertEquals(1, folds.get(2).sessions.size());
    }

    @Test
    public void autoTagAndFavoritesFilter()
    {
        GpManagerEngine engine = engine();
        long now = noon();
        String vorkath = history(engine, "Vorkath", now - DAY, 30 * 60_000L, 30L);
        String zulrah = history(engine, "Zulrah", now - DAY + 3_600_000L, 30 * 60_000L, 20L);
        engine.setHistorySessionFavorite(zulrah, true);
        engine.setHistorySessionTags(vorkath, "boss, " + SessionsSnapshot.AUTO_TAG);

        SessionsSnapshot all = SessionsSnapshot.capture(engine, now, false);
        assertEquals(2, all.groups.get(0).sessionCount);
        boolean autoSeen = false;
        for (SessionsSnapshot.Fold f : all.groups.get(0).folds)
        {
            for (SessionsSnapshot.SessionRow r : f.sessions)
            {
                autoSeen |= r.auto && r.id.equals(vorkath);
            }
        }
        assertTrue(autoSeen);
        SessionsSnapshot only = SessionsSnapshot.capture(engine, now, true);
        assertEquals(1, only.groups.get(0).sessionCount);
        assertEquals("Zulrah", only.groups.get(0).folds.get(0).name);
        assertTrue(only.groups.get(0).folds.get(0).first().favorite);
    }

    @Test
    public void sameActivityAverageIsTimeWeightedAndSkipsExcluded()
    {
        GpManagerEngine engine = engine();
        long now = noon();
        history(engine, "Vorkath", now - DAY, 30 * 60_000L, 30L);
        history(engine, "Vorkath", now - 2 * DAY, 60 * 60_000L, 30L);
        history(engine, "Zulrah", now - 3 * DAY, 30 * 60_000L, 300L);

        long net = 0L;
        long millis = 0L;
        String excludeId = null;
        for (ProfitSession s : engine.getHistory())
        {
            if (!"Vorkath".equals(s.getName()))
            {
                continue;
            }
            SessionSummary summary = engine.getHistorySummary(s.getId(), now);
            net += summary.getMetrics().getNet();
            millis += summary.getMetrics().getElapsedMillis();
            excludeId = s.getId();
        }
        assertEquals(2, SessionsSnapshot.sameActivityCount(engine, "vorkath", null));
        assertEquals(Math.round(net * 3_600_000d / millis), SessionsSnapshot.sameActivityAverage(engine, "Vorkath", now, null));
        assertEquals(1, SessionsSnapshot.sameActivityCount(engine, "Vorkath", excludeId));

        ComparePage.Average avg = SessionsSnapshot.activityAverage(engine, "Vorkath", now, null);
        assertEquals(2, avg.sessions);
        assertTrue(avg.gpPerHour != 0L);

        engine.setHistorySessionExcluded(excludeId, true);
        assertEquals(1, SessionsSnapshot.sameActivityCount(engine, "Vorkath", null));
        assertEquals(1, SessionsSnapshot.activityAverage(engine, "Vorkath", now, null).sessions);
        assertEquals(0, SessionsSnapshot.sameActivityCount(engine, "Nothing", null));
        assertEquals(0L, SessionsSnapshot.sameActivityAverage(engine, "Nothing", now, null));
    }

    @Test
    public void kindKeyStatAndOverallActiveTime()
    {
        GpManagerEngine engine = engine();
        long now = noon();
        String vorkath = history(engine, "Vorkath", now - DAY, 30 * 60_000L, 30L);
        String few = history(engine, "Zulrah", now - DAY + 3_600_000L, 20 * 60_000L, 3L);
        engine.setHistorySessionCategory(vorkath, SessionKind.BOSSING.category);

        SessionsSnapshot s = SessionsSnapshot.capture(engine, now, false);
        SessionsSnapshot.SessionRow v = null;
        SessionsSnapshot.SessionRow z = null;
        for (SessionsSnapshot.Fold f : s.groups.get(0).folds)
        {
            for (SessionsSnapshot.SessionRow r : f.sessions)
            {
                if (r.id.equals(vorkath)) v = r;
                if (r.id.equals(few)) z = r;
            }
        }
        assertNotNull(v);
        assertNotNull(z);
        assertEquals("Bossing", v.kind);
        assertEquals("~30 logs", v.keyStat);
        assertEquals("", z.kind);
        // A pre-pass-8 tag still reads back as the category.
        engine.setHistorySessionTags(few, "slayer");
        assertEquals("Slayer", SessionKind.labelOf(engine.getHistorySession(few)));
        assertEquals("1 drop", z.keyStat);
        // Overall active time is everything: at least the two sessions (50 minutes) and never more
        // than the day since free play began — how much of the idle day free play accrues is the
        // engine's rule (pass 9 step 34), not the sidebar's.
        assertTrue("active " + s.overallActiveMillis, s.overallActiveMillis >= 50 * 60_000L - 2_000L);
        assertTrue("active " + s.overallActiveMillis, s.overallActiveMillis <= DAY + 2_000L);
        // Retagging through the picker swaps only the category tag.
        assertEquals("slayer, boss", SessionKind.retag("bossing, boss", SessionKind.SLAYER));
        assertEquals("boss", SessionKind.retag("bossing, boss", SessionKind.OTHER));
    }

    @Test
    public void kindSuggestionAndWhenFormatting()
    {
        assertEquals(SessionKind.PVP, SessionKind.suggest("Vorkath", "PVM", true));
        assertEquals(SessionKind.SLAYER, SessionKind.suggest("Slayer: Hellhounds", "PVM", false));
        assertEquals(SessionKind.RAIDS, SessionKind.suggest("Chambers of Xeric", "", false));
        assertEquals(SessionKind.SKILLING, SessionKind.suggest("Wilderness Agility Course", "", false));
        assertEquals(SessionKind.BOSSING, SessionKind.suggest("Vorkath", "PVM", false));
        assertEquals(SessionKind.OTHER, SessionKind.suggest("", "", false));
        assertEquals(SessionMode.PK, SessionKind.PVP.mode);

        long now = noon();
        assertTrue(Fmt.when(now - 60_000L, now).startsWith("Today "));
        assertTrue(Fmt.when(now - DAY, now).startsWith("Yesterday "));
        String old = Fmt.when(now - 40 * DAY, now);
        assertFalse(old.startsWith("Today") || old.startsWith("Yesterday"));
        assertTrue(old.contains(":"));
    }

    /** Midday today, so "three hours ago" never crosses midnight while the suite runs. */
    private static long noon()
    {
        return java.time.LocalDate.now().atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String history(GpManagerEngine engine, String name, long startedAt, long length, long logs)
    {
        engine.startCustomSession(name, SessionMode.AUTO, startedAt);
        String id = engine.getActiveSession().getId();
        engine.setBaseline(snapshot(Collections.emptyMap()));
        settle(engine, snapshot(map(LOGS, logs)), startedAt + 60_000L);
        assertTrue(engine.finishCustomSession(startedAt + length));
        return id;
    }

    private static GpManagerEngine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public int minimumTransactionValue() { return 1; }
        };
        return new GpManagerEngine(deltas ->
        {
            List<ItemFlow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                flows.add(new ItemFlow(delta.getKey(), "Willow logs", delta.getValue(), 48, delta.getValue() * 48,
                    ItemPriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static void settle(GpManagerEngine engine, ContainerSnapshot snapshot, long now)
    {
        engine.markInventoryDirty();
        engine.processIfDirty(snapshot, now);
        engine.processIfDirty(snapshot, now + 600L);
    }

    private static ContainerSnapshot snapshot(Map<Integer, Long> items)
    {
        return new ContainerSnapshot(items);
    }

    private static Map<Integer, Long> map(Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((Integer) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }
}
