package com.gpmanager.ui.bento;

import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.PkEncounter;
import com.gpmanager.model.PkEncounterType;
import com.gpmanager.model.PkMetrics;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionCategory;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.SessionSummary;
import com.gpmanager.model.TrackingGainedItemDetail;
import com.gpmanager.model.TrackingInsightsSnapshot;
import com.gpmanager.model.WealthLocationSnapshot;
import com.gpmanager.model.WealthLocationsSnapshot;
import com.gpmanager.ui.ledger.LedgerItemContribution;
import com.gpmanager.ui.ledger.LedgerItemGrouping;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Insights read model (SIDEBAR_BENTO.md §6). One capture per range covers all three
 * modes; the page decides what to paint. Averages honour {@code excludedFromAverages};
 * excluded sessions are still listed and flagged, never silently dropped.
 */
public final class InsightsSnapshot
{
    public enum Range
    {
        ALL("All", 0), D30("30d", 30), D7("7d", 7);

        public final String label;
        public final int days;

        Range(String label, int days)
        {
            this.label = label;
            this.days = days;
        }
    }

    /** A completed or active session inside the window. */
    public static final class SessionPoint
    {
        public final String id;
        public final String name;
        public final long startedAt;
        public final long net;
        public final long activeMillis;
        public final long gpPerHour;
        public final boolean rateAvailable;
        public final boolean excluded;
        public final boolean active;
        public final boolean party;

        SessionPoint(String id, String name, long startedAt, long net, long activeMillis, long gpPerHour, boolean rateAvailable,
            boolean excluded, boolean active, boolean party)
        {
            this.id = id;
            this.name = name;
            this.startedAt = startedAt;
            this.net = net;
            this.activeMillis = activeMillis;
            this.gpPerHour = gpPerHour;
            this.rateAvailable = rateAvailable;
            this.excluded = excluded;
            this.active = active;
            this.party = party;
        }
    }

    public static final class Activity
    {
        public final String name;
        public final int sessions;
        public final long net;

        Activity(String name, int sessions, long net)
        {
            this.name = name;
            this.sessions = sessions;
            this.net = net;
        }
    }

    public static final class Item
    {
        public final int itemId;
        public final String name;
        public final long quantity;
        public final long value;
        @Nullable
        public final String note;

        Item(int itemId, String name, long quantity, long value, @Nullable String note)
        {
            this.itemId = itemId;
            this.name = name;
            this.quantity = quantity;
            this.value = value;
            this.note = note;
        }
    }

    public static final class Milestone
    {
        public final int itemId;
        public final String itemName;
        public final long value;
        public final long at;
        public final int killCount;
        public final long sessionElapsed;
        public final long gpPerHourAt;

        Milestone(int itemId, String itemName, long value, long at, int killCount, long sessionElapsed, long gpPerHourAt)
        {
            this.itemId = itemId;
            this.itemName = itemName;
            this.value = value;
            this.at = at;
            this.killCount = killCount;
            this.sessionElapsed = sessionElapsed;
            this.gpPerHourAt = gpPerHourAt;
        }
    }

    public static final class Fight
    {
        public final String label;
        public final long value;
        public final long at;
        public final boolean kill;
        /** Where it happened, when the encounter recorded a place; empty otherwise. */
        public final String location;

        Fight(String label, long value, long at, boolean kill, @Nullable String location)
        {
            this.label = label;
            this.value = value;
            this.at = at;
            this.kill = kill;
            this.location = location == null ? "" : location;
        }
    }

    /** Deaths grouped by place: where you die and what it costs there. */
    public static final class Place
    {
        public final String label;
        public final int deaths;
        public final long loss;
        /** Kills booked in this place, and the counted net of every fight there. */
        public final int kills;
        public final long net;
        /** Active time in the place from the engine's location ledger; negative when unknown. */
        public final long activeMillis;

        Place(String label, int deaths, long loss, int kills, long net)
        {
            this(label, deaths, loss, kills, net, -1L);
        }

        Place(String label, int deaths, long loss, int kills, long net, long activeMillis)
        {
            this.label = label;
            this.deaths = deaths;
            this.loss = loss;
            this.kills = kills;
            this.net = net;
            this.activeMillis = activeMillis;
        }

    }

    /** One finished PvP session — a PK trip — inside the window. */
    public static final class Trip
    {
        public final String sessionId;
        public final String name;
        public final long startedAt;
        public final long activeMillis;
        public final int kills;
        public final int deaths;
        public final long net;
        /** The place most of its fights were booked in; empty when unknown. */
        public final String place;

        Trip(String sessionId, String name, long startedAt, long activeMillis, int kills, int deaths, long net, String place)
        {
            this.sessionId = sessionId;
            this.name = name;
            this.startedAt = startedAt;
            this.activeMillis = activeMillis;
            this.kills = kills;
            this.deaths = deaths;
            this.net = net;
            this.place = place == null ? "" : place;
        }
    }

    public static final class Pvp
    {
        public final int sessions;
        public final int kills;
        public final int deaths;
        public final long killNet;
        public final long deathLoss;
        public final long net;
        public final long medianKill;
        public final long medianDeath;
        public final long supplies;
        public final List<Fight> bestKills;
        public final List<Fight> worstDeaths;
        /** Places by net, kills and deaths together (the "Top PK trips" card); time in place is not known here. */
        public final List<Place> deathPlaces;
        /** Longest run of kills without a death in the window. */
        public final int streak;
        /** Cumulative counted net after each fight, oldest first — the hero sparkline. */
        public final double[] series;
        /** K/D over the previous window when the rollups vouch for it; null otherwise. */
        @Nullable
        public final Double previousKd;
        /** Net over the previous window when the rollups vouch for it; null otherwise. */
        @Nullable
        public final Long previousNet;
        /** Finished PvP sessions in the window, best net first — the Top PK trips card. */
        public final List<Trip> trips;

        Pvp(int sessions, int kills, int deaths, long killNet, long deathLoss, long net, long medianKill, long medianDeath,
            long supplies, List<Fight> bestKills, List<Fight> worstDeaths, List<Place> deathPlaces, int streak, double[] series,
            @Nullable Double previousKd)
        {
            this(sessions, kills, deaths, killNet, deathLoss, net, medianKill, medianDeath, supplies, bestKills, worstDeaths, deathPlaces,
                streak, series, previousKd, null);
        }

        Pvp(int sessions, int kills, int deaths, long killNet, long deathLoss, long net, long medianKill, long medianDeath,
            long supplies, List<Fight> bestKills, List<Fight> worstDeaths, List<Place> deathPlaces, int streak, double[] series,
            @Nullable Double previousKd, @Nullable Long previousNet)
        {
            this(sessions, kills, deaths, killNet, deathLoss, net, medianKill, medianDeath, supplies, bestKills, worstDeaths, deathPlaces,
                streak, series, previousKd, previousNet, Collections.emptyList());
        }

        Pvp(int sessions, int kills, int deaths, long killNet, long deathLoss, long net, long medianKill, long medianDeath,
            long supplies, List<Fight> bestKills, List<Fight> worstDeaths, List<Place> deathPlaces, int streak, double[] series,
            @Nullable Double previousKd, @Nullable Long previousNet, List<Trip> trips)
        {
            this.trips = trips == null ? Collections.emptyList() : trips;
            this.previousNet = previousNet;
            this.streak = streak;
            this.series = series == null ? new double[0] : series;
            this.previousKd = previousKd;
            this.deathPlaces = deathPlaces;
            this.sessions = sessions;
            this.kills = kills;
            this.deaths = deaths;
            this.killNet = killNet;
            this.deathLoss = deathLoss;
            this.net = net;
            this.medianKill = medianKill;
            this.medianDeath = medianDeath;
            this.supplies = supplies;
            this.bestKills = bestKills;
            this.worstDeaths = worstDeaths;
        }

        public int fights()
        {
            return kills + deaths;
        }

        public double kd()
        {
            return deaths == 0 ? kills : kills / (double) deaths;
        }

        public long perKill()
        {
            return kills == 0 ? 0L : killNet / kills;
        }

        public long perDeath()
        {
            return deaths == 0 ? 0L : deathLoss / deaths;
        }

        public long suppliesPerFight()
        {
            return fights() == 0 ? 0L : supplies / fights();
        }
    }

    public static final class WealthLocation
    {
        public final String id;
        public final String title;
        public final long value;
        public final boolean valueAvailable;
        public final String status;

        WealthLocation(String id, String title, long value, boolean valueAvailable, String status)
        {
            this.id = id;
            this.title = title;
            this.value = value;
            this.valueAvailable = valueAvailable;
            this.status = status;
        }
    }

    public static final class WealthChange
    {
        public final String label;
        public final boolean available;
        public final long earned;
        public final long market;
        public final long unexplained;

        WealthChange(String label, @Nullable com.gpmanager.model.WealthChangeBreakdown b)
        {
            this.label = label;
            this.available = b != null && b.isAvailable();
            this.earned = b == null ? 0L : b.getEarnedGp();
            this.market = b == null ? 0L : b.getMarketGp();
            this.unexplained = b == null ? 0L : b.getUnexplainedGp();
        }

        public long total()
        {
            return earned + market + unexplained;
        }
    }

    public static final class Mover
    {
        public final int itemId;
        public final String name;
        public final long change;
        public final String reason;

        Mover(int itemId, String name, long change, String reason)
        {
            this.itemId = itemId;
            this.name = name;
            this.change = change;
            this.reason = reason;
        }
    }

    public static final class WealthGroup
    {
        public final String label;
        public final long value;
        /** 0..1 share of the total; negative when the engine could not say. */
        public final double share;
        public final boolean remainder;

        WealthGroup(String label, long value, double share, boolean remainder)
        {
            this.label = label;
            this.value = value;
            this.share = share;
            this.remainder = remainder;
        }
    }

    public static final class TrendPoint
    {
        public final java.time.LocalDate date;
        public final boolean captured;
        public final long total;

        TrendPoint(java.time.LocalDate date, boolean captured, long total)
        {
            this.date = date;
            this.captured = captured;
            this.total = total;
        }
    }

    /** One coin store as Wealth shows it: fresh with a value, stale, unobserved, or declared unused. */
    public static final class CoinStoreRow
    {
        public final com.gpmanager.model.CoinStore store;
        public final String title;
        public final String state;
        @Nullable
        public final Long value;
        public final boolean unused;

        CoinStoreRow(com.gpmanager.model.CoinStore store, String title, String state, @Nullable Long value, boolean unused)
        {
            this.store = store;
            this.title = title;
            this.state = state;
            this.value = value;
            this.unused = unused;
        }
    }

    public static final class Wealth
    {
        /** Coin stores in engine order; set after construction. */
        public List<CoinStoreRow> coinStores = Collections.emptyList();
        public final boolean available;
        public final long total;
        public final long capturedAt;
        public final List<WealthLocation> locations;
        /** True when the total and locations come from a persisted bank-visit capture. */
        public final boolean fromHistory;
        /** Totals of retained captures inside the range, oldest first. */
        public final List<Double> timeline;
        public final List<WealthChange> changes;
        public final List<Mover> movers;
        /** Bank · Equipped · Grand Exchange · Other from the engine's breakdown (pass 8 step 32). */
        public final List<WealthGroup> groups;
        /** One point per local day inside the range; {@code captured} false is a gap, not a zero. */
        public final List<TrendPoint> trend;

        Wealth(boolean available, long total, long capturedAt, List<WealthLocation> locations, boolean fromHistory,
            List<Double> timeline, List<WealthChange> changes, List<Mover> movers, List<WealthGroup> groups, List<TrendPoint> trend)
        {
            this.groups = groups;
            this.trend = trend;
            this.available = available;
            this.total = total;
            this.capturedAt = capturedAt;
            this.locations = locations;
            this.fromHistory = fromHistory;
            this.timeline = timeline;
            this.changes = changes;
            this.movers = movers;
        }
    }

    public final Range range;
    public final long now;
    public final boolean partyOnly;
    /** Named sessions in the window; free play is part of {@link #net} but never a session. */
    public final int sessionCount;
    /** Free play's share of {@link #net} in the window. */
    public final long freePlayNet;
    public final int excludedCount;
    public final int partyCount;
    public final long net;
    public final long activeMillis;
    /** Time-weighted GP/h over sessions counted in averages. */
    public final long averageRate;
    /** Net over the same-length window before this one; null for All or when empty. */
    @Nullable
    public final Long previousNet;
    public final List<SessionPoint> trend;
    @Nullable
    public final SessionPoint bestSession;
    public final int deaths;
    public final long deathLoss;
    public final List<Activity> activities;
    public final List<Item> topItems;
    public final List<Item> topCosts;
    /** Monday-first weekday × six 4-hour buckets; counted net by receipt time. */
    public final long[][] heat;
    public final boolean heatAvailable;
    public final List<Milestone> milestones;
    public final String milestoneStatus;
    public final boolean receiptsComplete;
    /** Totals came from the profile's daily rollups (complete coverage) rather than retained sessions. */
    public final boolean rollupBacked;
    public final Pvp pvp;
    public final Wealth wealth;
    public final Highlights highlights;
    /** All-time records (Insights › General › All); independent of the range. */
    public final Records records;

    /** One personal best; {@code sessionId} opens it, {@code date} names a day or week start. Null values read as "—". */
    public static final class Record
    {
        public final String label;
        public final String detail;
        public final long value;
        @Nullable
        public final String sessionId;
        @Nullable
        public final java.time.LocalDate date;

        Record(String label, String detail, long value, @Nullable String sessionId, @Nullable java.time.LocalDate date)
        {
            this.label = label;
            this.detail = detail;
            this.value = value;
            this.sessionId = sessionId;
            this.date = date;
        }
    }

    /**
     * Personal bests from everything retained: sessions (never Free play, never excluded), the profile's
     * daily rollups (days and weeks, play streaks) and the wealth history. Until the engine's records
     * model lands (pass 10 step 40) the sidebar computes these itself; a missing record is null.
     */
    public static final class Records
    {
        public static final Records NONE = new Records(null, null, null, null, null, 0, 0, null, null, null, null);
        /** PvP bests (Insights › PvP › All); K/D is stored ×1,000,000 by the engine. */
        @Nullable
        public final Record bestKill;
        @Nullable
        public final Record longestKillStreak;
        @Nullable
        public final Record bestKd;
        @Nullable
        public final Record bestSession;
        @Nullable
        public final Record bestRate;
        @Nullable
        public final Record longestSession;
        @Nullable
        public final Record bestDay;
        @Nullable
        public final Record bestWeek;
        /** Consecutive days (ending today or yesterday) with at least a minute of tracked play. */
        public final int streakCurrent;
        public final int streakLongest;
        @Nullable
        public final Record wealthHigh;

        Records(@Nullable Record bestSession, @Nullable Record bestRate, @Nullable Record longestSession,
            @Nullable Record bestDay, @Nullable Record bestWeek, int streakCurrent, int streakLongest, @Nullable Record wealthHigh,
            @Nullable Record bestKill, @Nullable Record longestKillStreak, @Nullable Record bestKd)
        {
            this.bestKill = bestKill;
            this.longestKillStreak = longestKillStreak;
            this.bestKd = bestKd;
            this.bestSession = bestSession;
            this.bestRate = bestRate;
            this.longestSession = longestSession;
            this.bestDay = bestDay;
            this.bestWeek = bestWeek;
            this.streakCurrent = streakCurrent;
            this.streakLongest = streakLongest;
            this.wealthHigh = wealthHigh;
        }

        public boolean isEmpty()
        {
            return bestSession == null && bestRate == null && longestSession == null && bestDay == null
                && bestWeek == null && streakLongest == 0 && wealthHigh == null;
        }
    }

    /** The Performance deltas and the Highlights rows (§13.3 Insights › General). Nulls read as "—". */
    public static final class Highlights
    {
        @Nullable
        public final Long previousAverageRate;
        @Nullable
        public final Long previousBestNet;
        @Nullable
        public final Integer previousDeaths;
        @Nullable
        public final String longestName;
        public final long longestMillis;
        public final long longestAt;
        public final int biggestDropItemId;
        @Nullable
        public final String biggestDropName;
        public final long biggestDropValue;
        @Nullable
        public final String bestHourLabel;
        public final long bestHourNet;

        Highlights(@Nullable Long previousAverageRate, @Nullable Long previousBestNet, @Nullable Integer previousDeaths,
            @Nullable String longestName, long longestMillis, long longestAt, int biggestDropItemId, @Nullable String biggestDropName,
            long biggestDropValue, @Nullable String bestHourLabel, long bestHourNet)
        {
            this.previousAverageRate = previousAverageRate;
            this.previousBestNet = previousBestNet;
            this.previousDeaths = previousDeaths;
            this.longestName = longestName;
            this.longestMillis = longestMillis;
            this.longestAt = longestAt;
            this.biggestDropItemId = biggestDropItemId;
            this.biggestDropName = biggestDropName;
            this.biggestDropValue = biggestDropValue;
            this.bestHourLabel = bestHourLabel;
            this.bestHourNet = bestHourNet;
        }

        static final Highlights NONE = new Highlights(null, null, null, null, 0L, 0L, -1, null, 0L, null, 0L);
    }

    private InsightsSnapshot(Range range, long now, boolean partyOnly, int sessionCount, long freePlayNet, int excludedCount, int partyCount,
        long net, long activeMillis, long averageRate, @Nullable Long previousNet, List<SessionPoint> trend,
        @Nullable SessionPoint bestSession, int deaths, long deathLoss,
        List<Activity> activities, List<Item> topItems, List<Item> topCosts, long[][] heat, boolean heatAvailable,
        List<Milestone> milestones, String milestoneStatus, boolean receiptsComplete, boolean rollupBacked, Pvp pvp, Wealth wealth,
        Highlights highlights, Records records)
    {
        this.highlights = highlights == null ? Highlights.NONE : highlights;
        this.records = records == null ? Records.NONE : records;
        this.rollupBacked = rollupBacked;
        this.range = range;
        this.now = now;
        this.partyOnly = partyOnly;
        this.sessionCount = sessionCount;
        this.freePlayNet = freePlayNet;
        this.excludedCount = excludedCount;
        this.partyCount = partyCount;
        this.net = net;
        this.activeMillis = activeMillis;
        this.averageRate = averageRate;
        this.previousNet = previousNet;
        this.trend = trend;
        this.bestSession = bestSession;
        this.deaths = deaths;
        this.deathLoss = deathLoss;
        this.activities = activities;
        this.topItems = topItems;
        this.topCosts = topCosts;
        this.heat = heat;
        this.heatAvailable = heatAvailable;
        this.milestones = milestones;
        this.milestoneStatus = milestoneStatus;
        this.receiptsComplete = receiptsComplete;
        this.pvp = pvp;
        this.wealth = wealth;
    }

    /** Percent change against the previous window, or null when it cannot be said. */
    @Nullable
    public Double vsPrevious()
    {
        if (previousNet == null || previousNet == 0L)
        {
            return null;
        }
        return (net - previousNet) / (double) Math.abs(previousNet);
    }

    /** All-time personal bests from the engine's records model (pass 10 step 40); the session named by a record gives its label. */
    static Records records(GpManagerEngine engine, long now)
    {
        com.gpmanager.model.RecordsSnapshot r = engine.getRecords(now);
        if (r == null)
        {
            return Records.NONE;
        }
        Record bestSession = sessionRecord(engine, r.getBestSessionNet(), "Best session", now, false);
        Record bestRate = sessionRecord(engine, r.getBestSessionRate(), "Best GP/h", now, true);
        Record longest = sessionRecord(engine, r.getLongestSession(), "Longest session", now, false);
        Record bestDay = r.getBestDay().isAvailable() && r.getBestDay().getDate() != null
            ? new Record("Best day", Fmt.dayLabel(r.getBestDay().getDate(), now), r.getBestDay().getValue(), null, r.getBestDay().getDate()) : null;
        Record bestWeek = r.getBestWeek().isAvailable() && r.getBestWeek().getDate() != null
            ? new Record("Best week", "Week of " + Fmt.dayLabel(r.getBestWeek().getDate(), now), r.getBestWeek().getValue(), null, r.getBestWeek().getDate()) : null;
        Record wealthHigh = r.getWealthHigh().isAvailable()
            ? new Record("Wealth high", "", r.getWealthHigh().getValue(), null, null) : null;
        int streakCurrent = r.getCurrentDailyStreak().isAvailable() ? (int) Math.min(Integer.MAX_VALUE, r.getCurrentDailyStreak().getValue()) : 0;
        int streakLongest = r.getLongestDailyStreak().isAvailable() ? (int) Math.min(Integer.MAX_VALUE, r.getLongestDailyStreak().getValue()) : 0;
        Record bestKill = sessionRecord(engine, r.getBestPvpKill(), "Best kill", now, false);
        Record longestStreak = sessionRecord(engine, r.getLongestPvpStreak(), "Longest kill streak", now, false);
        Record bestKd = r.getBestPvpKd().isAvailable()
            ? sessionRecord(engine, r.getBestPvpKd(), "Best K/D", now, false) : null;
        return new Records(bestSession, bestRate, longest, bestDay, bestWeek, streakCurrent, streakLongest, wealthHigh,
            bestKill, longestStreak, bestKd);
    }

    @Nullable
    private static Record sessionRecord(GpManagerEngine engine, com.gpmanager.model.RecordsSnapshot.Record r, String label, long now, boolean duration)
    {
        if (r == null || !r.isAvailable() || r.getSessionId() == null || r.getSessionId().isEmpty())
        {
            return null;
        }
        ProfitSession session = engine.getHistorySession(r.getSessionId());
        String name = session == null ? "" : session.getName();
        String when = session == null ? "" : Fmt.dayLabel(Instant.ofEpochMilli(session.getStartedAtEpochMillis()).atZone(ZoneId.systemDefault()).toLocalDate(), now);
        String detail = duration && session != null
            ? name + " · " + Fmt.durationCompact(session.getElapsedMillis(now))
            : name.isEmpty() ? when : name + (when.isEmpty() ? "" : " · " + when);
        return new Record(label, detail, r.getValue(), r.getSessionId(), null);
    }

    public static InsightsSnapshot capture(GpManagerEngine engine, Range range, long now, boolean partyOnly,
        @Nullable WealthLocationsSnapshot wealthSnapshot)
    {
        long cutoff = range.days == 0 ? Long.MIN_VALUE : now - range.days * 86_400_000L;
        long previousCutoff = range.days == 0 ? Long.MIN_VALUE : cutoff - range.days * 86_400_000L;

        ProfitSession activeSession = engine.getActiveSession();
        List<ProfitSession> all = new ArrayList<>();
        for (ProfitSession s : engine.getHistory())
        {
            if (s != null && (activeSession == null || !s.getId().equals(activeSession.getId())))
            {
                all.add(s);
            }
        }
        if (activeSession != null)
        {
            all.add(activeSession);
        }

        List<SessionPoint> trend = new ArrayList<>();
        List<ProfitSession> inWindow = new ArrayList<>();
        Map<String, SessionMetrics> metricsById = new HashMap<>();
        Map<String, PkMetrics> pkById = new HashMap<>();
        long previousNet = 0L;
        boolean previousAny = false;
        int excluded = 0;
        int party = 0;
        long net = 0L;
        long freePlayNet = 0L;
        long activeMillis = 0L;
        long weightedNet = 0L;
        long weightedMillis = 0L;
        SessionPoint best = null;
        boolean receiptsComplete = true;

        for (ProfitSession s : all)
        {
            boolean isActive = activeSession != null && s.getId().equals(activeSession.getId());
            long end = s.isClosed() ? s.getEndedAtEpochMillis() : now;
            boolean partySession = s.getPartySummary() != null;
            if (partyOnly && !partySession)
            {
                continue;
            }
            SessionMetrics m;
            PkMetrics pk;
            if (isActive)
            {
                m = engine.getMetrics(now);
                pk = engine.getPkMetrics();
            }
            else
            {
                SessionSummary summary = engine.getHistorySummary(s.getId(), now);
                m = summary == null ? null : summary.getMetrics();
                pk = summary == null ? null : summary.getPkMetrics();
            }
            if (m == null)
            {
                continue;
            }
            boolean current = end >= cutoff;
            boolean previous = !current && end >= previousCutoff && range.days != 0;
            if (previous)
            {
                previousNet += m.getNet();
                previousAny = true;
                continue;
            }
            if (!current)
            {
                continue;
            }
            inWindow.add(s);
            metricsById.put(s.getId(), m);
            if (pk != null)
            {
                pkById.put(s.getId(), pk);
            }
            if (s.getCompactedTransactionCount() > 0L)
            {
                receiptsComplete = false;
            }
            net += m.getNet();
            activeMillis += m.getElapsedMillis();
            if (SessionsSnapshot.isFreePlay(s))
            {
                // Free play is the always-on background: it counts toward the total, not as a session.
                freePlayNet += m.getNet();
                continue;
            }
            boolean rate = com.gpmanager.diagnostics.RateAvailability.isEstablished(m.getElapsedMillis());
            SessionPoint point = new SessionPoint(s.getId(), s.getName(), s.getStartedAtEpochMillis(), m.getNet(),
                m.getElapsedMillis(), m.getProfitPerHour(), rate, s.isExcludedFromAverages(), isActive, partySession);
            trend.add(point);
            if (partySession)
            {
                party++;
            }
            if (s.isExcludedFromAverages())
            {
                excluded++;
            }
            else
            {
                weightedNet += m.getNet();
                weightedMillis += m.getElapsedMillis();
                if (rate && (best == null || m.getProfitPerHour() > best.gpPerHour))
                {
                    best = point;
                }
            }
        }
        trend.sort((a, b) -> Long.compare(a.startedAt, b.startedAt));

        // Receipts: deaths, costs, heatmap, PvP fight values.
        int deaths = 0;
        long deathLoss = 0L;
        long[][] heat = new long[7][6];
        // (rollup coverage may replace the receipt grid below)
        boolean heatAny = false;
        Map<Integer, long[]> costByItem = new LinkedHashMap<>();
        Map<Integer, String> costNames = new HashMap<>();
        Map<Integer, long[]> gainByItem = new LinkedHashMap<>();
        Map<Integer, String> gainNames = new HashMap<>();
        ZoneId zone = ZoneId.systemDefault();
        for (ProfitSession s : inWindow)
        {
            for (ProfitTransaction t : s.getTransactions())
            {
                if (t == null || !t.isCounted() || t.getTimestampEpochMillis() < cutoff)
                {
                    continue;
                }
                long value = t.getNet();
                ZonedDateTime when = Instant.ofEpochMilli(t.getTimestampEpochMillis()).atZone(zone);
                int day = when.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
                heat[day][Math.min(5, when.getHour() / 4)] += value;
                heatAny = true;
                if (isDeath(t))
                {
                    // Deaths are one cost row of their own, not per lost item.
                    deaths++;
                    deathLoss += Math.max(0L, -value);
                    continue;
                }
                for (LedgerItemContribution c : LedgerItemGrouping.extractContributions(Collections.singletonList(t), true))
                {
                    LedgerSnapshot.Section section = LedgerSnapshot.classify(t, c);
                    if (section == LedgerSnapshot.Section.GAINS && c.getValueDelta() > 0L)
                    {
                        gainByItem.computeIfAbsent(c.getItemId(), k -> new long[2]);
                        long[] g = gainByItem.get(c.getItemId());
                        g[0] += c.getQuantityDelta();
                        g[1] += c.getValueDelta();
                        gainNames.putIfAbsent(c.getItemId(), c.getItemName());
                    }
                    else if ((section == LedgerSnapshot.Section.SUPPLIES || section == LedgerSnapshot.Section.LOSSES)
                        && c.getValueDelta() < 0L)
                    {
                        costByItem.computeIfAbsent(c.getItemId(), k -> new long[2]);
                        long[] g = costByItem.get(c.getItemId());
                        g[0] += -c.getQuantityDelta();
                        g[1] += -c.getValueDelta();
                        costNames.putIfAbsent(c.getItemId(), c.getItemName());
                    }
                }
            }
        }

        // Profile-zone rollups (they survive compaction and midnight): when the engine vouches for a
        // dimension's coverage, its figure wins over what the retained sessions can still say.
        boolean rollupTotals = false;
        boolean rollupHeat = false;
        Long previousAverageRate = null;
        Long previousBestNet = null;
        Integer previousDeaths = null;
        Double previousKd = null;
        String longestName = null;
        long longestMillis = 0L;
        long longestAt = 0L;
        int biggestDropId = -1;
        String biggestDropName = null;
        long biggestDropValue = 0L;
        String bestHourLabel = null;
        long bestHourNet = 0L;
        // Longest session and biggest drop from what is retained; the rollup window overrides below when it vouches.
        for (SessionPoint pt : trend)
        {
            if (!pt.excluded && pt.activeMillis > longestMillis)
            {
                longestMillis = pt.activeMillis;
                longestName = pt.name;
                longestAt = pt.startedAt;
            }
        }
        if (range.days > 0)
        {
            com.gpmanager.model.InsightsWindowSnapshot window = engine.getInsightsWindow(range.days, now);
            com.gpmanager.model.InsightsWindowSnapshot.Window cur = window == null ? null : window.getCurrent();
            com.gpmanager.model.InsightsWindowSnapshot.Window prev = window == null ? null : window.getPrevious();
            if (cur != null)
            {
                com.gpmanager.model.InsightsWindowSnapshot.SessionHighlight longest = cur.getLongestSession();
                if (longest != null && longest.isAvailable() && longest.getActiveMillis() > longestMillis)
                {
                    longestMillis = longest.getActiveMillis();
                    longestName = longest.getName();
                    longestAt = longest.getStartedAtEpochMillis();
                }
                com.gpmanager.model.InsightsWindowSnapshot.BiggestDrop drop = cur.getBiggestDrop();
                if (drop != null && drop.isAvailable() && drop.isPresent() && drop.getValueGp() > biggestDropValue)
                {
                    biggestDropValue = drop.getValueGp();
                    biggestDropId = drop.getItemId();
                    biggestDropName = drop.getItemName();
                }
                com.gpmanager.model.InsightsWindowSnapshot.BestHour hour = cur.getBestHour();
                if (hour != null && hour.isAvailable() && hour.getDate() != null)
                {
                    bestHourNet = hour.getNetGp();
                    bestHourLabel = Fmt.dayLabel(hour.getDate(), now) + ", " + String.format(Locale.ROOT, "%02d:00 – %02d:00", hour.getHour(), (hour.getHour() + 1) % 24);
                }
            }
            if (prev != null && prev.getCoverage(com.gpmanager.model.DailyRollup.Dimension.ACCOUNTING) == com.gpmanager.model.DailyRollup.Coverage.COMPLETE)
            {
                if (prev.isAverageGpPerHourAvailable())
                {
                    previousAverageRate = prev.getAverageGpPerHour();
                }
                com.gpmanager.model.InsightsWindowSnapshot.SessionHighlight prevBest = prev.getBestSession();
                if (prevBest != null && prevBest.isAvailable())
                {
                    previousBestNet = prevBest.getNetGp();
                }
                previousDeaths = prev.getDeaths();
                if (prev.getPvpKills() + prev.getPvpDeaths() > 0)
                {
                    previousKd = prev.getPvpDeaths() == 0 ? (double) prev.getPvpKills() : prev.getPvpKills() / (double) prev.getPvpDeaths();
                }
            }
            if (cur != null && cur.getCoverage(com.gpmanager.model.DailyRollup.Dimension.ACCOUNTING) == com.gpmanager.model.DailyRollup.Coverage.COMPLETE)
            {
                net = cur.getNetGp();
                rollupTotals = true;
                if (cur.getCoverage(com.gpmanager.model.DailyRollup.Dimension.ACTIVE_TIME) == com.gpmanager.model.DailyRollup.Coverage.COMPLETE)
                {
                    activeMillis = cur.getActiveMillis();
                }
                if (prev != null && prev.getCoverage(com.gpmanager.model.DailyRollup.Dimension.ACCOUNTING) == com.gpmanager.model.DailyRollup.Coverage.COMPLETE)
                {
                    previousNet = prev.getNetGp();
                    previousAny = prev.getNetGp() != 0L || prev.getActiveMillis() > 0L;
                }
                // Item rows come from receipts while they exist (deaths stay one row); rollups take over
                // only once receipts have been compacted away.
                if (!receiptsComplete
                    && cur.getCoverage(com.gpmanager.model.DailyRollup.Dimension.GAINED_ITEMS) == com.gpmanager.model.DailyRollup.Coverage.COMPLETE
                    && !cur.getGainedItemTotals().isEmpty())
                {
                    gainByItem.clear();
                    for (com.gpmanager.model.DailyRollup.ItemTotal it : cur.getGainedItemTotals().values())
                    {
                        gainByItem.put(it.getItemId(), new long[] {it.getQuantity(), it.getValueGp()});
                        gainNames.put(it.getItemId(), it.getItemName());
                    }
                }
                if (!receiptsComplete
                    && cur.getCoverage(com.gpmanager.model.DailyRollup.Dimension.COST_ITEMS) == com.gpmanager.model.DailyRollup.Coverage.COMPLETE
                    && !cur.getCostItemTotals().isEmpty())
                {
                    costByItem.clear();
                    for (com.gpmanager.model.DailyRollup.ItemTotal it : cur.getCostItemTotals().values())
                    {
                        costByItem.put(it.getItemId(), new long[] {it.getQuantity(), it.getValueGp()});
                        costNames.put(it.getItemId(), it.getItemName());
                    }
                }
            }
            java.time.LocalDate to = java.time.LocalDate.now();
            java.time.LocalDate from = to.minusDays(range.days - 1L);
            long[][] rollupGrid = new long[7][6];
            boolean gridComplete = true;
            boolean gridAny = false;
            for (com.gpmanager.model.DailyRollup day : engine.getDailyRollups(from, to))
            {
                if (day.getCoverage(com.gpmanager.model.DailyRollup.Dimension.FOUR_HOUR_BUCKETS) != com.gpmanager.model.DailyRollup.Coverage.COMPLETE)
                {
                    gridComplete = false;
                    break;
                }
                long[] buckets = day.getFourHourNetGp();
                int weekday = day.getDate().getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
                for (int i = 0; i < Math.min(6, buckets.length); i++)
                {
                    rollupGrid[weekday][i] += buckets[i];
                    gridAny |= buckets[i] != 0L;
                }
            }
            if (gridComplete && gridAny)
            {
                heat = rollupGrid;
                heatAny = true;
                rollupHeat = true;
            }
        }

        // Activities: a custom session is its own activity; the durable owner splits by its activity hint.
        List<Activity> activities = new ArrayList<>();
        Map<String, long[]> byActivity = new LinkedHashMap<>();
        Map<String, String> activityLabels = new HashMap<>();
        for (ProfitSession s : inWindow)
        {
            SessionMetrics m = metricsById.get(s.getId());
            if (m == null)
            {
                continue;
            }
            String hint = m.getActivityHint() == null || "General".equalsIgnoreCase(m.getActivityHint().trim())
                ? "" : m.getActivityHint().trim();
            boolean owner = SessionsSnapshot.isFreePlay(s);
            String label = owner ? (hint.isEmpty() ? SessionsSnapshot.FREE_PLAY : hint) : s.getName();
            String key = label.toLowerCase(Locale.ROOT);
            long[] v = byActivity.computeIfAbsent(key, k -> new long[2]);
            // Free play is not a session, so it never carries a session count.
            v[0] += owner ? 0 : 1;
            v[1] += m.getNet();
            activityLabels.putIfAbsent(key, label);
        }
        for (Map.Entry<String, long[]> e : byActivity.entrySet())
        {
            activities.add(new Activity(activityLabels.get(e.getKey()), (int) e.getValue()[0], e.getValue()[1]));
        }
        activities.sort((a, b) -> Long.compare(b.net, a.net));

        // Top items lean on the engine's daily analytics (they survive compaction); receipts fill in
        // when the projection is unavailable. Either way one row per item.
        TrackingInsightsSnapshot tracking = engine.getTrackingInsights(range.days, now);
        Map<String, long[]> mergedItems = new LinkedHashMap<>();
        Map<String, Item> itemSeed = new HashMap<>();
        if (tracking.isProjectionAvailable() && !tracking.getGainedItemDetails().isEmpty())
        {
            for (TrackingGainedItemDetail d : tracking.getGainedItemDetails())
            {
                String key = d.getItemId() + "|" + d.getItemName().toLowerCase(Locale.ROOT);
                long[] v = mergedItems.computeIfAbsent(key, k -> new long[2]);
                v[0] += d.getQuantity();
                v[1] += d.getValue();
                itemSeed.putIfAbsent(key, new Item(d.getItemId(), d.getItemName(), 0L, 0L, null));
            }
        }
        else
        {
            for (Map.Entry<Integer, long[]> e : gainByItem.entrySet())
            {
                String key = e.getKey() + "|" + gainNames.get(e.getKey()).toLowerCase(Locale.ROOT);
                mergedItems.put(key, e.getValue());
                itemSeed.put(key, new Item(e.getKey(), gainNames.get(e.getKey()), 0L, 0L, null));
            }
        }
        List<Item> topItems = new ArrayList<>();
        for (Map.Entry<String, long[]> e : mergedItems.entrySet())
        {
            Item seed = itemSeed.get(e.getKey());
            topItems.add(new Item(seed.itemId, seed.name, e.getValue()[0], e.getValue()[1], null));
        }
        topItems.sort((a, b) -> Long.compare(b.value, a.value));

        List<Item> topCosts = new ArrayList<>();
        for (Map.Entry<Integer, long[]> e : costByItem.entrySet())
        {
            topCosts.add(new Item(e.getKey(), costNames.get(e.getKey()), e.getValue()[0], e.getValue()[1], null));
        }
        if (deaths > 0)
        {
            topCosts.add(new Item(-1, "Deaths", deaths, deathLoss, deaths == 1 ? "1 death" : deaths + " deaths"));
        }
        topCosts.sort((a, b) -> Long.compare(b.value, a.value));

        List<Milestone> milestones = new ArrayList<>();
        for (TrackingInsightsSnapshot.Milestone ms : tracking.getMilestones())
        {
            milestones.add(new Milestone(ms.getItemId(), ms.getItemName(), ms.getValueGp(), ms.getObservedAtEpochMillis(),
                ms.getEncounterKillCount(), ms.getSessionElapsedMillis(), ms.getGpPerHourAtObservation()));
        }
        milestones.sort((a, b) -> Long.compare(b.at, a.at));
        // Retained notable drops stand in for the biggest drop until the rollup window vouches for a larger one.
        for (Milestone ms : milestones)
        {
            if (ms.value > biggestDropValue)
            {
                biggestDropValue = ms.value;
                biggestDropId = ms.itemId;
                biggestDropName = ms.itemName;
            }
        }

        Pvp pvp = pvp(inWindow, metricsById, previousKd, pkById, cutoff);
        if (range.days > 0)
        {
            pvp = enginePvp(engine, range, now, pvp);
        }
        Wealth wealth = wealth(engine, range, now, wealthSnapshot);
        wealth.coinStores = coinStores(engine, now, wealthSnapshot);

        return new InsightsSnapshot(range, now, partyOnly, trend.size(), freePlayNet, excluded, party, net, activeMillis,
            weightedMillis <= 0L ? 0L : Math.round(weightedNet * 3_600_000d / weightedMillis),
            previousAny ? previousNet : null, Collections.unmodifiableList(trend), best, deaths, deathLoss,
            cap(activities, 5), cap(topItems, 5), cap(topCosts, 5), heat, heatAny,
            cap(milestones, 6), tracking.getMilestoneStatus(), receiptsComplete, rollupTotals || rollupHeat, pvp, wealth,
            new Highlights(previousAverageRate, previousBestNet, previousDeaths, longestName, longestMillis, longestAt,
                biggestDropId, biggestDropName, biggestDropValue, bestHourLabel, bestHourNet),
            records(engine, now));
    }

    static boolean isDeath(ProfitTransaction t)
    {
        return t.getType() == com.gpmanager.model.TransactionType.PK_DEATH_LOSS
            || t.getNote().toLowerCase(Locale.ROOT).startsWith("death");
    }

    private static Pvp pvp(List<ProfitSession> sessions, Map<String, SessionMetrics> metricsById, @Nullable Double previousKd,
        Map<String, PkMetrics> pkById, long cutoff)
    {
        int count = 0;
        int kills = 0;
        int deaths = 0;
        long killNet = 0L;
        long deathLoss = 0L;
        long net = 0L;
        long supplies = 0L;
        List<Long> killValues = new ArrayList<>();
        List<Long> deathValues = new ArrayList<>();
        List<Fight> fights = new ArrayList<>();
        List<Trip> trips = new ArrayList<>();
        for (ProfitSession s : sessions)
        {
            PkMetrics pk = pkById.get(s.getId());
            boolean pvpSession = s.getCategory() == SessionCategory.PKING
                || (pk != null && pk.getEncounterCount() > 0);
            if (!pvpSession)
            {
                continue;
            }
            count++;
            SessionMetrics m = metricsById.get(s.getId());
            if (s.isClosed() && !SessionsSnapshot.isFreePlay(s))
            {
                // A finished PvP session is a PK trip: its fights' commonest place names it.
                Map<String, Integer> byPlace = new LinkedHashMap<>();
                for (PkEncounter e : s.getPkEncounters())
                {
                    if (e != null && e.getLocationLabel() != null && !e.getLocationLabel().isEmpty())
                    {
                        byPlace.merge(e.getLocationLabel(), 1, Integer::sum);
                    }
                }
                String place = "";
                int best = 0;
                for (Map.Entry<String, Integer> e : byPlace.entrySet())
                {
                    if (e.getValue() > best)
                    {
                        best = e.getValue();
                        place = e.getKey();
                    }
                }
                trips.add(new Trip(s.getId(), SessionsSnapshot.displayName(s), s.getStartedAtEpochMillis(),
                    m == null ? 0L : m.getElapsedMillis(), pk == null ? 0 : pk.getKills(), pk == null ? 0 : pk.getDeaths(),
                    m == null ? (pk == null ? 0L : pk.getNet()) : m.getNet(), place));
            }
            if (pk != null)
            {
                kills += pk.getKills();
                deaths += pk.getDeaths();
                killNet += pk.getTotalKillNet();
                deathLoss += pk.getTotalDeathLoss();
                net += pk.getNet();
            }
            if (m != null)
            {
                supplies += CostSplit.of(pk, m.getCosts()).supplies;
            }
            Map<String, ProfitTransaction> byId = new HashMap<>();
            for (ProfitTransaction t : s.getTransactions())
            {
                if (t != null)
                {
                    byId.put(t.getId(), t);
                }
            }
            for (PkEncounter e : s.getPkEncounters())
            {
                if (e == null || e.getTimestampEpochMillis() < cutoff)
                {
                    continue;
                }
                long value = 0L;
                boolean any = false;
                for (String id : e.getTransactionIds())
                {
                    ProfitTransaction t = byId.get(id);
                    if (t != null && t.isCounted())
                    {
                        value += t.getNet();
                        any = true;
                    }
                }
                if (!any)
                {
                    continue;
                }
                boolean kill = e.getType() != PkEncounterType.DEATH;
                (kill ? killValues : deathValues).add(kill ? value : -value);
                fights.add(new Fight(e.getLabel(), value, e.getTimestampEpochMillis(), kill, e.getLocationLabel()));
            }
        }
        List<Fight> bestKills = new ArrayList<>();
        List<Fight> worstDeaths = new ArrayList<>();
        for (Fight f : fights)
        {
            (f.kill ? bestKills : worstDeaths).add(f);
        }
        bestKills.sort((a, b) -> Long.compare(b.value, a.value));
        worstDeaths.sort((a, b) -> Long.compare(a.value, b.value));
        // Oldest first: the kill streak and the cumulative series read in play order.
        List<Fight> ordered = new ArrayList<>(fights);
        ordered.sort((a, b) -> Long.compare(a.at, b.at));
        int streak = 0;
        int run = 0;
        double[] series = new double[ordered.size()];
        double running = 0d;
        for (int i = 0; i < ordered.size(); i++)
        {
            Fight f = ordered.get(i);
            run = f.kill ? run + 1 : 0;
            streak = Math.max(streak, run);
            running += f.value;
            series[i] = running;
        }
        Map<String, long[]> byPlace = new LinkedHashMap<>();
        for (Fight f : fights)
        {
            if (f.location.isEmpty())
            {
                continue;
            }
            long[] v = byPlace.computeIfAbsent(f.location, k -> new long[4]);
            if (f.kill)
            {
                v[2]++;
            }
            else
            {
                v[0]++;
                v[1] += -f.value;
            }
            v[3] += f.value;
        }
        List<Place> places = new ArrayList<>();
        for (Map.Entry<String, long[]> e : byPlace.entrySet())
        {
            places.add(new Place(e.getKey(), (int) e.getValue()[0], e.getValue()[1], (int) e.getValue()[2], e.getValue()[3]));
        }
        places.sort((a, b) -> Long.compare(b.net, a.net));
        trips.sort((a, b) -> Long.compare(b.net, a.net));
        return new Pvp(count, kills, deaths, killNet, deathLoss, net, median(killValues), median(deathValues), supplies,
            cap(bestKills, 4), cap(worstDeaths, 3), cap(places, 5), streak, series, previousKd, null, cap(trips, 5));
    }

    /**
     * Pass 8 step 31: the rollup-backed PvP window (kills, deaths, streak, medians, fight cost,
     * best kills) and the place summaries with time in place replace the receipt scan where the
     * engine says they are available; anything it cannot vouch for keeps the receipt figure.
     */
    private static Pvp enginePvp(GpManagerEngine engine, Range range, long now, Pvp base)
    {
        com.gpmanager.model.InsightsWindowSnapshot window = engine.getPkWindow(range.days, now);
        com.gpmanager.model.PkWindow cur = window == null || window.getCurrent() == null ? null : window.getCurrent().getPkWindow();
        com.gpmanager.model.PkWindow prev = window == null || window.getPrevious() == null ? null : window.getPrevious().getPkWindow();
        int kills = base.kills;
        int deaths = base.deaths;
        long killNet = base.killNet;
        long deathLoss = base.deathLoss;
        long net = base.net;
        long medianKill = base.medianKill;
        long medianDeath = base.medianDeath;
        long supplies = base.supplies;
        int streak = base.streak;
        Double previousKd = base.previousKd;
        List<Fight> bestKills = base.bestKills;
        if (cur != null && cur.isAggregateAvailable())
        {
            kills = cur.getKills();
            deaths = cur.getDeaths();
            killNet = cur.getKillNetGp();
            deathLoss = cur.getDeathLossGp();
            net = cur.getNetGp();
            streak = Math.max(streak, cur.getBestStreak());
            if (cur.isMedianKillNetAvailable())
            {
                medianKill = Math.round(cur.getMedianKillNetGp());
            }
            if (cur.isMedianDeathLossAvailable())
            {
                medianDeath = Math.round(cur.getMedianDeathLossGp());
            }
            if (cur.isAttachedSuppliesAvailable())
            {
                supplies = cur.getAttachedSuppliesGp();
            }
            if (cur.isDetailsAvailable() && !cur.getBestKills().isEmpty())
            {
                List<Fight> fights = new ArrayList<>();
                for (com.gpmanager.model.PkWindow.Event e : cur.getBestKills())
                {
                    fights.add(new Fight(e.getOpponentName(), e.getValueGp(), e.getTimestampEpochMillis(), true,
                        e.getLocationLabel() == null ? "" : e.getLocationLabel()));
                }
                bestKills = cap(fights, 4);
            }
        }
        Long previousNet = null;
        if (prev != null && prev.isAggregateAvailable() && prev.getKills() + prev.getDeaths() > 0)
        {
            previousKd = prev.getKillDeathRatio();
            previousNet = prev.getNetGp();
        }
        List<Place> places = base.deathPlaces;
        com.gpmanager.model.PkPlaceSummaries summaries = engine.getPkPlaceSummaries(range.days, now);
        if (summaries != null && summaries.isEncounterCountsAvailable() && !summaries.getPlaces().isEmpty())
        {
            List<Place> fromEngine = new ArrayList<>();
            for (com.gpmanager.model.PkPlaceSummary p : summaries.getPlaces())
            {
                fromEngine.add(new Place(p.getLocationLabel(), p.getDeaths(), Math.max(0L, -Math.min(0L, p.getNetGp())), p.getKills(),
                    p.isFinanceAvailable() ? p.getNetGp() : 0L, p.isActiveTimeAvailable() ? p.getActiveMillis() : -1L));
            }
            fromEngine.sort((a, b) -> Long.compare(b.net, a.net));
            places = cap(fromEngine, 5);
        }
        int sessions = Math.max(base.sessions, kills + deaths > 0 ? 1 : 0);
        return new Pvp(sessions, kills, deaths, killNet, deathLoss, net, medianKill, medianDeath, supplies,
            bestKills, base.worstDeaths, places, streak, base.series, previousKd, previousNet, base.trips);
    }

    static long median(List<Long> values)
    {
        if (values.isEmpty())
        {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2L;
    }

    /** The persisted bank-visit history when there is one; the plugin's live read otherwise. */
    static List<CoinStoreRow> coinStores(GpManagerEngine engine, long now, @Nullable WealthLocationsSnapshot live)
    {
        List<CoinStoreRow> rows = new ArrayList<>();
        com.gpmanager.model.DataHealthSnapshot health = engine.getDataHealth(now);
        com.gpmanager.model.LatestWealthSnapshot latest = engine.getLatestWealth(now);
        for (com.gpmanager.model.CoinStore store : com.gpmanager.model.CoinStore.values())
        {
            boolean unused = engine.isCoinStoreUnused(store);
            com.gpmanager.model.DataHealthSnapshot.CoinStoreFreshness fresh = health == null ? null : health.getCoinStoreFreshness(store);
            Long value = null;
            if (latest != null && latest.isAvailable())
            {
                com.gpmanager.model.WealthSnapshotHistory.Location l = latest.getSnapshot().getLocation(store.getLocationId());
                if (l != null && l.getStatus() == WealthLocationSnapshot.Status.AVAILABLE)
                {
                    value = l.getValueGp();
                }
            }
            String state = unused ? "unused" : fresh == com.gpmanager.model.DataHealthSnapshot.CoinStoreFreshness.FRESH ? "fresh"
                : fresh == com.gpmanager.model.DataHealthSnapshot.CoinStoreFreshness.STALE ? "stale" : "not read yet";
            rows.add(new CoinStoreRow(store, store.getTitle(), state, unused ? Long.valueOf(0L) : value, unused));
        }
        return rows;
    }

    private static Wealth wealth(GpManagerEngine engine, Range range, long now, @Nullable WealthLocationsSnapshot live)
    {
        com.gpmanager.model.LatestWealthSnapshot latest = engine.getLatestWealth(now);
        // Where it sits is the live picture: inventory, worn, GE and (once read) the repriced bank
        // move every tick. The durable bank-visit capture only stands in while there is no live read.
        boolean fromHistory = live == null && latest != null && latest.isAvailable();
        WealthLocationsSnapshot snapshot = live != null ? live
            : latest != null && latest.isAvailable() ? latest.getSnapshot().toWealthLocationsSnapshot() : null;
        List<WealthLocation> locations = new ArrayList<>();
        long total = 0L;
        boolean any = false;
        if (snapshot != null)
        {
            for (WealthLocationSnapshot l : snapshot.getLocations())
            {
                boolean available = l.getStatus() == WealthLocationSnapshot.Status.AVAILABLE;
                if (available)
                {
                    total += l.getValueGp();
                    any = true;
                }
                locations.add(new WealthLocation(l.getId(), l.getTitle(), l.getValueGp(), available,
                    l.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ')));
            }
        }
        List<Double> timeline = new ArrayList<>();
        for (com.gpmanager.model.WealthSnapshotHistory.Snapshot p : engine.getWealthTimeline(range.days == 0 ? 3650 : range.days, now))
        {
            long t = 0L;
            for (com.gpmanager.model.WealthSnapshotHistory.Location l : p.getLocations())
            {
                if (l.getStatus() == WealthLocationSnapshot.Status.AVAILABLE)
                {
                    t += l.getValueGp();
                }
            }
            timeline.add((double) t);
        }
        List<WealthChange> changes = new ArrayList<>();
        changes.add(new WealthChange("Since last bank visit", engine.getWealthChangeSince(com.gpmanager.model.WealthAnchor.LAST_BANK_VISIT, now)));
        changes.add(new WealthChange("Today", engine.getWealthChangeSince(com.gpmanager.model.WealthAnchor.TODAY, now)));
        changes.add(new WealthChange("7 days", engine.getWealthChangeSince(com.gpmanager.model.WealthAnchor.SEVEN_DAYS, now)));
        changes.add(new WealthChange("30 days", engine.getWealthChangeSince(com.gpmanager.model.WealthAnchor.THIRTY_DAYS, now)));
        List<WealthGroup> groups = new ArrayList<>();
        com.gpmanager.model.WealthBreakdown breakdown = engine.getWealthBreakdown(now);
        if (breakdown != null)
        {
            for (com.gpmanager.model.WealthBreakdown.Group g : com.gpmanager.model.WealthBreakdown.Group.values())
            {
                com.gpmanager.model.WealthBreakdown.GroupValue v = breakdown.getGroup(g);
                if (v == null || !v.isValueAvailable())
                {
                    continue;
                }
                String label = g == com.gpmanager.model.WealthBreakdown.Group.BANK ? "Bank"
                    : g == com.gpmanager.model.WealthBreakdown.Group.EQUIPPED ? "Equipped"
                    : g == com.gpmanager.model.WealthBreakdown.Group.GRAND_EXCHANGE ? "GE" : "Other";
                Double share = breakdown.getSharePercent(g);
                groups.add(new WealthGroup(label, v.getValueGp(), share == null ? -1d : share / 100d, v.hasRemainder()));
            }
        }
        List<TrendPoint> trend = new ArrayList<>();
        for (com.gpmanager.model.WealthTrendPoint p : engine.getWealthTrend(range.days == 0 ? 365 : range.days, now))
        {
            Long t = p.getTotalGp();
            trend.add(new TrendPoint(p.getDate(), p.isCaptured() && t != null, t == null ? 0L : t));
        }
        List<Mover> movers = new ArrayList<>();
        for (com.gpmanager.model.WealthTopMover m : engine.getWealthTopMovers(com.gpmanager.model.WealthAnchor.THIRTY_DAYS, 5, now))
        {
            movers.add(new Mover(m.getItemId(), m.getName(), m.getValueChangeGp(), m.getReason().name().toLowerCase(Locale.ROOT)));
        }
        return new Wealth(any, total, snapshot == null ? 0L : snapshot.getCapturedAtEpochMillis(),
            Collections.unmodifiableList(locations), fromHistory, Collections.unmodifiableList(timeline),
            Collections.unmodifiableList(changes), Collections.unmodifiableList(movers),
            Collections.unmodifiableList(groups), Collections.unmodifiableList(trend));
    }

    private static <T> List<T> cap(List<T> list, int limit)
    {
        return Collections.unmodifiableList(new ArrayList<>(list.subList(0, Math.min(limit, list.size()))));
    }
}
