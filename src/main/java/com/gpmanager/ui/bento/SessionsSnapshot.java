package com.gpmanager.ui.bento;

import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.PkMetrics;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.SessionSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Sessions page read model (SIDEBAR_BENTO.md §5). Vocabulary: <b>Overall</b> is the derived
 * total of everything; <b>Free play</b> is play outside any session (the engine's durable
 * owner); a <b>session</b> is a start-to-end stretch the owner names (the engine's custom
 * session), or one an automatic boundary started for them. There is no level inside a session.
 * Consecutive same-name sessions in a day are folded for reading, never for accounting.
 */
public final class SessionsSnapshot
{
    public static final String FREE_PLAY = "Free play";
    /** Tag the plugin sets on sessions an automatic boundary started. */
    public static final String AUTO_TAG = "auto";

    /** A comparable statement for one session. */
    public static final class Statement
    {
        public final String key;
        public final String sessionId;
        public final String title;
        /** Activity hint when it differs from the title; empty otherwise. */
        public final String subtitle;
        /** Day label for column headers. */
        public final String when;
        public final long net;
        public final long loot;
        /** Consumables: food, potions, runes, ammo, charges. */
        public final long supplies;
        /** Everything else that left for good: deaths, tax, fees, drops. */
        public final long loss;
        public final long durationMillis;
        public final long gpPerHour;
        public final boolean rateAvailable;
        public final int kills;
        public final int deaths;
        /** Value the engine saw dropped and left behind; informational, never in net. */
        @Nullable
        public final Long leftOnGround;
        public final boolean accountingAvailable;

        Statement(String key, String sessionId, String title, String subtitle, String when, long net, long loot,
            long supplies, long loss, long durationMillis, long gpPerHour, boolean rateAvailable, int kills, int deaths,
            @Nullable Long leftOnGround, boolean accountingAvailable)
        {
            this.key = key;
            this.sessionId = sessionId;
            this.title = title;
            this.subtitle = subtitle;
            this.when = when;
            this.net = net;
            this.loot = loot;
            this.supplies = supplies;
            this.loss = loss;
            this.durationMillis = durationMillis;
            this.gpPerHour = gpPerHour;
            this.rateAvailable = rateAvailable;
            this.kills = kills;
            this.deaths = deaths;
            this.leftOnGround = leftOnGround;
            this.accountingAvailable = accountingAvailable;
        }
    }

    public static final class SessionRow
    {
        public final String id;
        public final String name;
        public final String activity;
        public final long startedAt;
        public final long durationMillis;
        public final long net;
        public final long gpPerHour;
        public final boolean rateAvailable;
        public final boolean favorite;
        public final boolean excludedFromAverages;
        public final boolean compacted;
        public final boolean pvp;
        public final boolean auto;
        public final boolean freePlay;
        public final int kills;
        public final int deaths;
        /** Category label (Bossing, Slayer, PvP…); empty when unknown. */
        public final String kind;
        /** One figure that describes the session: "41 kills", "~7.4k logs", "3 drops". Empty when unknown. */
        public final String keyStat;
        /** Why the session closed (pass 9 step 37); null for a live session or one that predates the field. */
        @Nullable
        public final com.gpmanager.model.SessionEndReason endReason;
        public final Statement asStatement;

        SessionRow(String id, String name, String activity, long startedAt, long durationMillis, long net, long gpPerHour,
            boolean rateAvailable, boolean favorite, boolean excludedFromAverages, boolean compacted, boolean pvp,
            boolean auto, boolean freePlay, int kills, int deaths, String kind, String keyStat, Statement asStatement)
        {
            this(id, name, activity, startedAt, durationMillis, net, gpPerHour, rateAvailable, favorite, excludedFromAverages,
                compacted, pvp, auto, freePlay, kills, deaths, kind, keyStat, null, asStatement);
        }

        SessionRow(String id, String name, String activity, long startedAt, long durationMillis, long net, long gpPerHour,
            boolean rateAvailable, boolean favorite, boolean excludedFromAverages, boolean compacted, boolean pvp,
            boolean auto, boolean freePlay, int kills, int deaths, String kind, String keyStat,
            @Nullable com.gpmanager.model.SessionEndReason endReason, Statement asStatement)
        {
            this.endReason = endReason;
            this.kind = kind == null ? "" : kind;
            this.keyStat = keyStat == null ? "" : keyStat;
            this.id = id;
            this.name = name;
            this.activity = activity;
            this.startedAt = startedAt;
            this.durationMillis = durationMillis;
            this.net = net;
            this.gpPerHour = gpPerHour;
            this.rateAvailable = rateAvailable;
            this.favorite = favorite;
            this.excludedFromAverages = excludedFromAverages;
            this.compacted = compacted;
            this.pvp = pvp;
            this.auto = auto;
            this.freePlay = freePlay;
            this.kills = kills;
            this.deaths = deaths;
            this.asStatement = asStatement;
        }
    }

    /** Consecutive same-name sessions inside a group, read as one row with a count. */
    public static final class Fold
    {
        public final String name;
        public final List<SessionRow> sessions;
        public final long net;
        public final long durationMillis;

        Fold(String name, List<SessionRow> sessions)
        {
            this.name = name;
            this.sessions = sessions;
            long n = 0L;
            long d = 0L;
            for (SessionRow s : sessions)
            {
                n += s.net;
                d += s.durationMillis;
            }
            this.net = n;
            this.durationMillis = d;
        }

        public SessionRow first()
        {
            return sessions.get(0);
        }
    }

    /** Recent day, or a collapsed older span. */
    public static final class Group
    {
        public final String label;
        public final boolean recent;
        public final List<Fold> folds;
        public final int sessionCount;
        public final long net;

        Group(String label, boolean recent, List<Fold> folds)
        {
            this.label = label;
            this.recent = recent;
            this.folds = folds;
            long n = 0L;
            int c = 0;
            for (Fold f : folds)
            {
                n += f.net;
                c += f.sessions.size();
            }
            this.net = n;
            this.sessionCount = c;
        }
    }

    /** What is live now: a named session, or free play. */
    @Nullable
    public final SessionRow active;
    public final List<Group> groups;
    /** Overall today: free play plus every session started today, including what is live. */
    public final long overallToday;
    /** Overall all-time active play, for the current card's Total time. */
    public final long overallActiveMillis;
    /** Named sessions started today (free play is never a session). */
    public final int sessionsToday;

    private SessionsSnapshot(@Nullable SessionRow active, List<Group> groups, long overallToday, int sessionsToday,
        long overallActiveMillis)
    {
        this.overallActiveMillis = overallActiveMillis;
        this.active = active;
        this.groups = groups;
        this.overallToday = overallToday;
        this.sessionsToday = sessionsToday;
    }

    public static SessionsSnapshot capture(GpManagerEngine engine, long now, boolean favoritesOnly)
    {
        ProfitSession activeSession = engine.getActiveSession();
        SessionRow active = activeSession == null ? null : row(engine, activeSession, now, true);

        LocalDate today = LocalDate.now();
        Map<String, List<SessionRow>> byLabel = new LinkedHashMap<>();
        Map<String, Boolean> recentByLabel = new LinkedHashMap<>();
        List<ProfitSession> history = new ArrayList<>(engine.getHistory());
        history.sort((a, b) -> Long.compare(b.getStartedAtEpochMillis(), a.getStartedAtEpochMillis()));
        long overallToday = active == null ? 0L : active.net;
        long overallActive = active == null ? 0L : active.durationMillis;
        if (active != null && !active.freePlay)
        {
            // Free play is paused while a session runs; its time still belongs to Overall.
            ProfitSession general = engine.getGeneralSession();
            if (general != null && !general.getId().equals(active.id))
            {
                overallActive += general.getElapsedMillis(now);
            }
        }
        int sessionsToday = active == null || active.freePlay ? 0 : 1;
        for (ProfitSession s : history)
        {
            if (s == null || (activeSession != null && s.getId().equals(activeSession.getId())))
            {
                continue;
            }
            LocalDate day = Instant.ofEpochMilli(s.getStartedAtEpochMillis()).atZone(ZoneId.systemDefault()).toLocalDate();
            long daysAgo = ChronoUnit.DAYS.between(day, today);
            overallActive += SessionNetCache.activeMillis(engine, s, now);
            if (isFreePlay(s))
            {
                // Archived free play stays in Overall (and the Ledger's Today), never in the list.
                if (daysAgo == 0L)
                {
                    overallToday += SessionNetCache.net(engine, s, now);
                }
                continue;
            }
            SessionRow row = row(engine, s, now, false);
            if (daysAgo == 0L)
            {
                overallToday += row.net;
                sessionsToday++;
            }
            if (favoritesOnly && !s.isFavorite())
            {
                continue;
            }
            boolean recent = daysAgo < 7L;
            String label = recent ? dayLabel(s.getStartedAtEpochMillis(), today)
                : daysAgo < 31L ? "Last month"
                : day.getYear() == today.getYear() ? day.format(java.time.format.DateTimeFormatter.ofPattern("MMMM"))
                : Integer.toString(day.getYear());
            byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(row);
            recentByLabel.putIfAbsent(label, recent);
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<SessionRow>> e : byLabel.entrySet())
        {
            groups.add(new Group(e.getKey(), recentByLabel.get(e.getKey()), fold(e.getValue())));
        }
        // Pass 8 step 33: the engine's Overall totals (rollups first, never receipts) win where they vouch.
        com.gpmanager.model.OverallTotalsSnapshot all = engine.getOverallTotals(now);
        if (all != null && all.isActiveTimeAvailable())
        {
            overallActive = all.getActiveMillis();
        }
        com.gpmanager.model.OverallTotalsSnapshot todayTotals = engine.getOverallToday(now);
        if (todayTotals != null && todayTotals.isNetComplete())
        {
            overallToday = todayTotals.getNetGp();
        }
        if (todayTotals != null && todayTotals.areNamedSessionStartsComplete())
        {
            sessionsToday = todayTotals.getNamedSessionsStarted();
        }
        return new SessionsSnapshot(active, Collections.unmodifiableList(groups), overallToday, sessionsToday, overallActive);
    }

    /** Consecutive rows with the same name (case-insensitive) become one fold. */
    static List<Fold> fold(List<SessionRow> rows)
    {
        List<Fold> folds = new ArrayList<>();
        List<SessionRow> run = new ArrayList<>();
        String current = null;
        for (SessionRow r : rows)
        {
            if (current != null && !current.equalsIgnoreCase(r.name))
            {
                folds.add(new Fold(current, Collections.unmodifiableList(run)));
                run = new ArrayList<>();
            }
            current = r.name;
            run.add(r);
        }
        if (current != null)
        {
            folds.add(new Fold(current, Collections.unmodifiableList(run)));
        }
        return Collections.unmodifiableList(folds);
    }

    /** Today · Yesterday · "Sat 12 Sept" · "12 Jul" · "12 Jul 2025". */
    static String dayLabel(long epochMillis, LocalDate today)
    {
        LocalDate day = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate();
        long daysAgo = ChronoUnit.DAYS.between(day, today);
        if (daysAgo == 0L)
        {
            return "Today";
        }
        if (daysAgo == 1L)
        {
            return "Yesterday";
        }
        if (daysAgo < 7L)
        {
            return day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"));
        }
        if (day.getYear() == today.getYear())
        {
            return day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"));
        }
        return day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"));
    }

    /** The engine's durable owner is "Free play" to the owner. */
    public static boolean isFreePlay(ProfitSession s)
    {
        return com.gpmanager.ui.SessionOwnerLabels.DURABLE_OWNER_NAME.equalsIgnoreCase(s.getName())
            || com.gpmanager.ui.SessionOwnerLabels.LEGACY_DURABLE_OWNER_NAME.equalsIgnoreCase(s.getName());
    }

    public static String displayName(ProfitSession s)
    {
        return isFreePlay(s) ? FREE_PLAY : s.getName();
    }

    static boolean isAuto(ProfitSession s)
    {
        String tags = s.getTagsDisplay();
        if (tags == null || tags.isEmpty())
        {
            return false;
        }
        for (String t : tags.split(","))
        {
            if (AUTO_TAG.equalsIgnoreCase(t.trim()))
            {
                return true;
            }
        }
        return false;
    }

    static SessionRow row(GpManagerEngine engine, ProfitSession s, long now, boolean active)
    {
        SessionMetrics m;
        PkMetrics pk;
        if (active)
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
            m = engine.getMetrics(now);
        }
        List<ProfitTransaction> transactions = s.getTransactions();
        Long leftOnGround = null;
        com.gpmanager.model.RunHistorySnapshot rh = active ? engine.getRunHistorySnapshot(now) : s.runHistorySnapshot(now);
        if (rh != null)
        {
            for (com.gpmanager.model.RunStatementSnapshot st : rh.getStatements())
            {
                if (st.isLeftOnGroundAvailable() && st.getLeftOnGroundGp() != null)
                {
                    leftOnGround = (leftOnGround == null ? 0L : leftOnGround) + st.getLeftOnGroundGp();
                }
            }
        }
        boolean pvp = s.getCategory() == com.gpmanager.model.SessionCategory.PKING;
        boolean freePlay = isFreePlay(s);
        String name = displayName(s);
        // "General" is the engine's no-detection default, not an activity worth a subtitle.
        String hint = m.getActivityHint() == null || "General".equalsIgnoreCase(m.getActivityHint().trim())
            ? "" : m.getActivityHint().trim();
        String activity = hint.equalsIgnoreCase(name) ? "" : hint;
        CostSplit split = CostSplit.of(m);
        boolean rate = com.gpmanager.diagnostics.RateAvailability.isEstablished(m.getElapsedMillis());
        Statement whole = new Statement(
            s.getId(), s.getId(), name, activity, active ? "live" : dayLabel(s.getStartedAtEpochMillis(), LocalDate.now()),
            m.getNet(), m.getRevenue(), split.supplies, split.loss, m.getElapsedMillis(), m.getProfitPerHour(), rate,
            pk == null ? 0 : pk.getKills(), pk == null ? 0 : pk.getDeaths(), leftOnGround, true);
        String kind = freePlay ? "" : SessionKind.labelOf(s);
        String keyStat = keyStat(s.getSessionHighlight());
        if (keyStat.isEmpty())
        {
            keyStat = keyStat(transactions, pk, pvp);
        }
        return new SessionRow(
            s.getId(), name, activity, s.getStartedAtEpochMillis(), m.getElapsedMillis(), m.getNet(),
            m.getProfitPerHour(), rate, s.isFavorite(), s.isExcludedFromAverages(), s.getCompactedTransactionCount() > 0,
            pvp, isAuto(s), freePlay, pk == null ? 0 : pk.getKills(), pk == null ? 0 : pk.getDeaths(), kind,
            keyStat, active ? null : s.getEndReason(), whole);
    }

    /** The engine's persisted highlight (pass 8 step 29) as one figure; empty when unavailable. */
    static String keyStat(@Nullable com.gpmanager.model.SessionHighlight h)
    {
        if (h == null || !h.isAvailable() || h.getKind() == null)
        {
            return "";
        }
        switch (h.getKind())
        {
            case PK_KILLS:
            case KILLS:
                return h.getCount() + (h.getCount() == 1L ? " kill" : " kills");
            case GATHERED:
                return "~" + Fmt.compact(h.getQuantity()) + " " + shortItemName(h.getItemName());
            case DROPS:
                return h.getCount() + (h.getCount() == 1L ? " drop" : " drops");
            default:
                return "";
        }
    }

    /** "willow logs" reads as "logs" on a one-line card; the full name is one hover away. */
    static String shortItemName(@Nullable String name)
    {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s*\\(\\d+\\)$", "");
        int space = n.lastIndexOf(' ');
        return space > 0 && n.length() > 10 ? n.substring(space + 1) : n;
    }

    /** Receipts fallback for sessions from before the highlight existed. */
    static String keyStat(List<ProfitTransaction> transactions, @Nullable PkMetrics pk, boolean pvp)
    {
        if (pvp && pk != null && pk.getEncounterCount() > 0)
        {
            return pk.getKills() + (pk.getKills() == 1 ? " kill" : " kills");
        }
        Map<Integer, long[]> byItem = new LinkedHashMap<>();
        Map<Integer, String> names = new java.util.HashMap<>();
        int gains = 0;
        for (ProfitTransaction t : transactions)
        {
            if (t == null || !t.isCounted() || t.getNet() <= 0L)
            {
                continue;
            }
            gains++;
            for (com.gpmanager.model.ItemFlow f : t.getFlows())
            {
                if (f == null || f.getQuantityDelta() <= 0L)
                {
                    continue;
                }
                long[] v = byItem.computeIfAbsent(f.getItemId(), k -> new long[2]);
                v[0] += f.getQuantityDelta();
                v[1] += f.getValueDelta();
                names.putIfAbsent(f.getItemId(), f.getItemName());
            }
        }
        if (byItem.isEmpty())
        {
            return "";
        }
        int bestId = -1;
        long bestQty = 0L;
        for (Map.Entry<Integer, long[]> e : byItem.entrySet())
        {
            if (e.getValue()[0] > bestQty)
            {
                bestQty = e.getValue()[0];
                bestId = e.getKey();
            }
        }
        if (bestQty >= 20L)
        {
            String n = names.get(bestId);
            n = n == null ? "" : n.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s*\\(\\d+\\)$", "");
            // "willow logs" reads as "logs" on a one-line card; the full name is one hover away.
            int space = n.lastIndexOf(' ');
            if (space > 0 && n.length() > 10)
            {
                n = n.substring(space + 1);
            }
            return "~" + Fmt.compact(bestQty) + " " + n;
        }
        return gains + (gains == 1 ? " drop" : " drops");
    }

    /** The engine's coverage-aware average; falls back to the receipts when the model says unavailable. */
    public static ComparePage.Average activityAverage(GpManagerEngine engine, String sessionName, long now, @Nullable String excludeId)
    {
        com.gpmanager.model.ActivityAverageSnapshot a = engine.getActivityAverage(sessionName, excludeId);
        if (a != null && a.isRateAvailable())
        {
            return new ComparePage.Average(sessionName, a.getSessionsCounted(), a.getTimeWeightedGpPerHour(),
                a.isNetAvailable() ? Math.round(a.getMedianSessionNetGp()) : null, a.isCoverageComplete());
        }
        return new ComparePage.Average(sessionName, sameActivityCount(engine, sessionName, excludeId),
            sameActivityAverage(engine, sessionName, now, excludeId), null, false);
    }

    /** Average GP/h across other sessions with the same name (the "your average" line). */
    public static long sameActivityAverage(GpManagerEngine engine, String sessionName, long now, @Nullable String excludeId)
    {
        long weightedNet = 0L;
        long millis = 0L;
        for (ProfitSession s : engine.getHistory())
        {
            if (s == null || s.isExcludedFromAverages() || !s.getName().equalsIgnoreCase(sessionName)
                || (excludeId != null && excludeId.equals(s.getId())))
            {
                continue;
            }
            SessionSummary summary = engine.getHistorySummary(s.getId(), now);
            if (summary == null || summary.getMetrics() == null)
            {
                continue;
            }
            weightedNet += summary.getMetrics().getNet();
            millis += summary.getMetrics().getElapsedMillis();
        }
        return millis <= 0L ? 0L : Math.round(weightedNet * 3_600_000d / millis);
    }

    public static int sameActivityCount(GpManagerEngine engine, String sessionName, @Nullable String excludeId)
    {
        int n = 0;
        for (ProfitSession s : engine.getHistory())
        {
            if (s != null && !s.isExcludedFromAverages() && s.getName().equalsIgnoreCase(sessionName)
                && (excludeId == null || !excludeId.equals(s.getId())))
            {
                n++;
            }
        }
        return n;
    }
}
