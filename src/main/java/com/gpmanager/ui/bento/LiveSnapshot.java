package com.gpmanager.ui.bento;

import com.gpmanager.diagnostics.RateAvailability;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.TransactionType;
import com.gpmanager.reward.ActionPresentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Everything the Live page paints, captured in one read from the engine so the page never
 * holds engine references while laying out. Pure data; built by {@link #capture}.
 */
public final class LiveSnapshot
{
    public static final int RECENT_ROWS = 5;

    /** A drop worth a notice (≥ the notable-drop threshold), the most recent one in the session. */
    /** One session goal as the Live card shows it. */
    public static final class Goal
    {
        public final com.gpmanager.model.GoalDefinition.Kind kind;
        public final long target;
        public final long current;
        public final boolean available;
        public final boolean reached;
        /** "200k", "1.5M/h", "15 kills". */
        public final String label;
        /** "62%" or "12 / 15". */
        public final String progress;
        /** "~18m" for net goals with a rate; "reached"; null otherwise. */
        @Nullable
        public final String eta;
        public final double fraction;
        @Nullable
        public final String unavailableReason;

        Goal(com.gpmanager.model.GoalDefinition.Kind kind, long target, long current, boolean available, boolean reached,
            String label, String progress, @Nullable String eta, double fraction, @Nullable String unavailableReason)
        {
            this.kind = kind;
            this.target = target;
            this.current = current;
            this.available = available;
            this.reached = reached;
            this.label = label;
            this.progress = progress;
            this.eta = eta;
            this.fraction = fraction;
            this.unavailableReason = unavailableReason;
        }

        public static String kindLabel(com.gpmanager.model.GoalDefinition.Kind kind)
        {
            switch (kind)
            {
                case GP_PER_HOUR:
                    return "GP/h";
                case KILLS:
                    return "Kills";
                case ITEM_COUNT:
                    return "Items";
                default:
                    return "Net";
            }
        }

        /** "200k" · "1.5M/h" · "15 kills" for a definition. */
        public static String targetLabel(com.gpmanager.model.GoalDefinition.Kind kind, long target)
        {
            switch (kind)
            {
                case GP_PER_HOUR:
                    return Fmt.compact(target) + "/h";
                case KILLS:
                    return target + (target == 1L ? " kill" : " kills");
                case ITEM_COUNT:
                    return Fmt.compact(target) + " items";
                default:
                    return Fmt.compact(target);
            }
        }
    }

    public static final class NotableDrop
    {
        public final int itemId;
        public final String name;
        public final long value;
        public final long at;

        NotableDrop(int itemId, String name, long value, long at)
        {
            this.itemId = itemId;
            this.name = name;
            this.value = value;
            this.at = at;
        }
    }

    public static final class Encounter
    {
        public final String name;
        public final int count;
        public final int streak;
        public final long perKill;

        Encounter(String name, int count, int streak, long perKill)
        {
            this.name = name;
            this.count = count;
            this.streak = streak;
            this.perKill = perKill;
        }
    }

    /** A kill or death on the live session, newest first. */
    public static final class PvpEvent
    {
        public final String opponent;
        public final boolean kill;
        public final long value;
        public final long at;
        public final String location;

        PvpEvent(String opponent, boolean kill, long value, long at, String location)
        {
            this.opponent = opponent;
            this.kill = kill;
            this.value = value;
            this.at = at;
            this.location = location;
        }
    }

    public static final class Recent
    {
        public final int itemId;
        public final String name;
        @Nullable
        public final String qty;
        public final String verb;
        public final long value;
        public final boolean neutral;
        @Nullable
        public final String tag;

        Recent(int itemId, String name, @Nullable String qty, String verb, long value, boolean neutral, @Nullable String tag)
        {
            this.itemId = itemId;
            this.name = name;
            this.qty = qty;
            this.verb = verb;
            this.value = value;
            this.neutral = neutral;
            this.tag = tag;
        }
    }

    public final boolean hasSession;
    public final String ownerLabel;
    public final boolean custom;
    public final long elapsedMillis;
    public final boolean paused;
    public final boolean idle;
    public final boolean stopped;
    public final boolean accountHold;
    public final long net;
    public final long gains;
    /** Non-consumable costs: deaths, tax, fees, drops. */
    public final long loss;
    /** Consumables: food, potions, runes, ammo, charges. */
    public final long supplies;
    public final boolean rateEstablished;
    public final long gpPerHour;
    /** Legacy net target; {@link #goal} is the read to use. */
    @Nullable
    public final Long goalGp;
    /** Counted net since the last bank visit in this session; null when it has not banked. */
    @Nullable
    public final Long sinceBank;
    /** The session goal (§13.5): kind, target and the engine's progress; null when none is set. */
    @Nullable
    public final Goal goal;
    /** Free play (the engine's durable owner) rather than a named session. */
    public final boolean freePlay;
    /** Net booked after the session's last bank visit (a bank transfer receipt or a bank boundary). */
    @Nullable
    static Long sinceBank(@Nullable ProfitSession active, @Nullable BoundaryLog boundaries)
    {
        if (active == null)
        {
            return null;
        }
        long lastBank = 0L;
        for (ProfitTransaction t : active.getTransactions())
        {
            if (t != null && t.getType() == TransactionType.TRANSFER && t.getNote().toLowerCase(Locale.ROOT).contains("bank"))
            {
                lastBank = Math.max(lastBank, t.getTimestampEpochMillis());
            }
        }
        if (boundaries != null)
        {
            for (BoundaryLog.Entry e : boundaries.entries(active.getId()))
            {
                if (e.kind == Ribbon.MarkKind.BANK)
                {
                    lastBank = Math.max(lastBank, e.at);
                }
            }
        }
        if (lastBank == 0L)
        {
            return null;
        }
        long net = 0L;
        for (ProfitTransaction t : active.getTransactions())
        {
            if (t != null && t.isCounted() && t.getTimestampEpochMillis() > lastBank)
            {
                net += t.getNet();
            }
        }
        return net;
    }

    /** The profile's session-scope goal definition, or null. */
    @Nullable
    public static com.gpmanager.model.GoalDefinition sessionGoalDefinition(GpManagerEngine engine)
    {
        for (com.gpmanager.model.GoalDefinition d : engine.getGoalDefinitions())
        {
            if (d != null && d.getScope() == com.gpmanager.model.GoalDefinition.Scope.SESSION && d.isConfigured())
            {
                return d;
            }
        }
        return null;
    }

    /**
     * The goal card's read: the profile's session-scope definition through the engine's
     * {@code getGoalProgress}; a legacy per-session net target reads as a net goal.
     */
    @Nullable
    static Goal goal(GpManagerEngine engine, @Nullable ProfitSession active, SessionMetrics metrics, long elapsed, long now)
    {
        if (active == null)
        {
            return null;
        }
        com.gpmanager.model.GoalDefinition def = sessionGoalDefinition(engine);
        if (def == null)
        {
            Long legacy = active.getProfitTargetGp();
            if (legacy == null || legacy <= 0L)
            {
                return null;
            }
            def = new com.gpmanager.model.GoalDefinition(com.gpmanager.model.GoalDefinition.Kind.NET,
                com.gpmanager.model.GoalDefinition.Scope.SESSION, legacy, Collections.emptyList());
        }
        com.gpmanager.model.GoalProgress p = engine.getGoalProgress(def, now);
        long target = def.getTargetValue();
        boolean available = p != null && p.isAvailable();
        long current = available ? p.getCurrentValue() : 0L;
        double fraction = target <= 0L ? 0d : Math.max(0d, current / (double) target);
        boolean reached = available && p.isReached();
        String progress;
        String eta = null;
        switch (def.getKind())
        {
            case KILLS:
            case ITEM_COUNT:
                progress = available ? current + " / " + target : "—";
                break;
            case GP_PER_HOUR:
                progress = available ? Fmt.rate(current) + "/h" : "—";
                break;
            default:
                progress = available ? Fmt.percent(fraction) : "—";
                if (available && RateAvailability.isEstablished(elapsed) && metrics.getProfitPerHour() > 0L && current < target)
                {
                    long minutes = Math.round((target - current) * 60d / metrics.getProfitPerHour());
                    eta = minutes < 1L ? "<1m" : minutes < 90L ? "~" + minutes + "m" : "~" + (minutes / 60) + "h " + (minutes % 60) + "m";
                }
                break;
        }
        if (reached)
        {
            eta = "reached";
        }
        return new Goal(def.getKind(), target, current, available, reached, Goal.targetLabel(def.getKind(), target), progress, eta,
            fraction, available ? null : p == null ? "unavailable" : p.getUnavailableReason());
    }
    /** Started by an automatic boundary. */
    public final boolean auto;
    /** Session start, for the ribbon's time axis. */
    public final long startedAt;
    public final List<Ribbon.Mark> ribbonMarks;
    /** Overall today: free play plus every session started today, including what is live. */
    public final long overallToday;
    /** Named sessions started today (free play is never a session). */
    public final int sessionsToday;
    public final boolean neutralZone;
    public final boolean reclaimPending;
    /** Reclaim window armed (a grave / retrieval interaction was seen). */
    public final boolean reclaimArmed;
    public final long reclaimOutstanding;
    public final long reclaimAgeMillis;
    public final int keysHeld;
    public final int reviewCount;
    public final List<Recent> recent;
    /** True for PK-mode sessions or any session that has booked a fight. */
    public final boolean pvpSession;
    public final int kills;
    public final int deaths;
    public final long killNet;
    public final long deathLoss;
    public final long bestKill;
    public final int streak;
    /** Cumulative counted net after each receipt, for the optional sparkline. */
    public final double[] netSeries;
    @Nullable
    public final NotableDrop notableDrop;
    @Nullable
    public final Encounter encounter;
    /** GP/h against your same-activity average when ≥ 3 sessions exist; null otherwise. */
    @Nullable
    public final Double rateVsAverage;
    public final List<PvpEvent> pvpEvents;
    /** Previous PvP session's net for the hero delta; null when there is none. */
    @Nullable
    public final Long pvpPreviousNet;
    /** Averages per finished PvP session, for the loot / loss deltas; null when none. */
    @Nullable
    public final Long pvpAverageKillNet;
    @Nullable
    public final Long pvpAverageDeathLoss;

    private LiveSnapshot(
        boolean hasSession, String ownerLabel, boolean custom, long elapsedMillis, boolean paused, boolean idle,
        boolean stopped, boolean accountHold, long net, long gains, long loss, long supplies, boolean rateEstablished,
        long gpPerHour, @Nullable Long goalGp, boolean freePlay, boolean auto, long startedAt,
        List<Ribbon.Mark> ribbonMarks, long overallToday, int sessionsToday, boolean neutralZone, boolean reclaimPending, boolean reclaimArmed, long reclaimOutstanding, long reclaimAgeMillis, int keysHeld, int reviewCount,
        @Nullable Goal goal, @Nullable Long sinceBank,
        List<Recent> recent, boolean pvpSession, int kills, int deaths, long killNet, long deathLoss, long bestKill,
        int streak, double[] netSeries, @Nullable NotableDrop notableDrop,
        @Nullable Encounter encounter, @Nullable Double rateVsAverage, List<PvpEvent> pvpEvents,
        @Nullable Long pvpPreviousNet, @Nullable Long pvpAverageKillNet, @Nullable Long pvpAverageDeathLoss)
    {
        this.notableDrop = notableDrop;
        this.encounter = encounter;
        this.rateVsAverage = rateVsAverage;
        this.pvpEvents = pvpEvents;
        this.pvpPreviousNet = pvpPreviousNet;
        this.pvpAverageKillNet = pvpAverageKillNet;
        this.pvpAverageDeathLoss = pvpAverageDeathLoss;
        this.hasSession = hasSession;
        this.ownerLabel = ownerLabel;
        this.custom = custom;
        this.elapsedMillis = elapsedMillis;
        this.paused = paused;
        this.idle = idle;
        this.stopped = stopped;
        this.accountHold = accountHold;
        this.net = net;
        this.gains = gains;
        this.loss = loss;
        this.supplies = supplies;
        this.rateEstablished = rateEstablished;
        this.gpPerHour = gpPerHour;
        this.goalGp = goalGp;
        this.goal = goal;
        this.sinceBank = sinceBank;
        this.freePlay = freePlay;
        this.auto = auto;
        this.startedAt = startedAt;
        this.ribbonMarks = ribbonMarks;
        this.overallToday = overallToday;
        this.sessionsToday = sessionsToday;
        this.neutralZone = neutralZone;
        this.reclaimPending = reclaimPending;
        this.reclaimArmed = reclaimArmed;
        this.reclaimOutstanding = reclaimOutstanding;
        this.reclaimAgeMillis = reclaimAgeMillis;
        this.keysHeld = keysHeld;
        this.reviewCount = reviewCount;
        this.recent = recent;
        this.pvpSession = pvpSession;
        this.kills = kills;
        this.deaths = deaths;
        this.killNet = killNet;
        this.deathLoss = deathLoss;
        this.bestKill = bestKill;
        this.streak = streak;
        this.netSeries = netSeries;
    }

    public double kd()
    {
        return deaths == 0 ? kills : kills / (double) deaths;
    }

    /**
     * @param neutralZoneActive from the plugin's neutral-zone tracker (the engine does not own it)
     * @param accountHold identity switch held by persistence
     */
    public static LiveSnapshot capture(GpManagerEngine engine, long now, boolean neutralZoneActive, boolean accountHold)
    {
        return capture(engine, now, neutralZoneActive, accountHold, (BoundaryLog) null);
    }

    /**
     * @param boundaries marks the plugin noted for the live owner (may be null)
     */
    public static LiveSnapshot capture(GpManagerEngine engine, long now, boolean neutralZoneActive, boolean accountHold,
        @Nullable BoundaryLog boundaries)
    {
        return capture(engine, now, neutralZoneActive, accountHold,
            new LiveContext(LiveContext.NONE.notableDropGp, null, boundaries));
    }

    public static LiveSnapshot capture(GpManagerEngine engine, long now, boolean neutralZoneActive, boolean accountHold,
        LiveContext ctx)
    {
        BoundaryLog boundaries = ctx == null ? null : ctx.boundaries;
        LiveContext context = ctx == null ? LiveContext.NONE : ctx;
        SessionMetrics metrics = engine.getMetrics(now);
        ProfitSession active = engine.getActiveSession();
        boolean custom = engine.isCustomSessionActive();
        boolean freePlay = active == null || !custom;
        String owner = active == null ? SessionsSnapshot.FREE_PLAY : SessionsSnapshot.displayName(active);
        long elapsed = metrics.getElapsedMillis();

        // Overall today = sessions that started today + what is live (history nets are cached by the panel).
        long overallToday = active == null ? 0L : metrics.getNet();
        int sessionsToday = active == null || freePlay ? 0 : 1;
        java.time.LocalDate today = java.time.LocalDate.now();
        for (ProfitSession s : engine.getHistory())
        {
            if (s == null || (active != null && s.getId().equals(active.getId())))
            {
                continue;
            }
            java.time.LocalDate day = java.time.Instant.ofEpochMilli(s.getStartedAtEpochMillis())
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            if (day.equals(today))
            {
                overallToday += SessionNetCache.net(engine, s, now);
                if (!SessionsSnapshot.isFreePlay(s))
                {
                    sessionsToday++;
                }
            }
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

        List<Ribbon.Mark> marks = new ArrayList<>();
        List<Recent> recent = new ArrayList<>();
        int keysHeld = 0;
        int review = 0;
        if (active != null)
        {
            List<ProfitTransaction> transactions = active.getTransactions();
            for (ProfitTransaction t : transactions)
            {
                if (t == null)
                {
                    continue;
                }
                Long activeElapsed = t.getActiveElapsedMillis();
                if (elapsed > 0L && activeElapsed != null)
                {
                    double at = (double) activeElapsed / elapsed;
                    String note = t.getNote().toLowerCase(Locale.ROOT);
                    if (t.getType() == TransactionType.PK_DEATH_LOSS || note.startsWith("death"))
                    {
                        marks.add(new Ribbon.Mark(at, Ribbon.MarkKind.DEATH));
                    }
                    else if (t.getType() == TransactionType.TRANSFER && note.contains("bank"))
                    {
                        marks.add(new Ribbon.Mark(at, Ribbon.MarkKind.BANK));
                    }
                }
                if (t.hasPendingDeferredClaim())
                {
                    keysHeld++;
                }
                if (needsReview(t))
                {
                    review++;
                }
            }
            for (int i = transactions.size() - 1; i >= 0 && recent.size() < RECENT_ROWS; i--)
            {
                Recent row = toRecent(transactions.get(i));
                if (row != null)
                {
                    recent.add(row);
                }
            }
        }

        if (active != null && boundaries != null && elapsed > 0L)
        {
            for (BoundaryLog.Entry e : boundaries.entries(active.getId()))
            {
                double at = (double) active.getElapsedMillis(e.at) / elapsed;
                marks.add(new Ribbon.Mark(at, e.kind, e.label));
            }
        }
        CostSplit costSplit = CostSplit.of(metrics);
        com.gpmanager.model.DeathReclaimStatus reclaim = engine.getDeathReclaimStatus();
        com.gpmanager.model.PkMetrics pk = active == null ? null : engine.getPkMetrics();
        boolean pvpSession = active != null
            && (active.getCategory() == com.gpmanager.model.SessionCategory.PKING || (pk != null && pk.getEncounterCount() > 0));
        double[] series = new double[0];
        if (active != null)
        {
            List<Double> points = new ArrayList<>();
            double running = 0d;
            for (ProfitTransaction t : active.getTransactions())
            {
                if (t != null && t.isCounted())
                {
                    running += t.getNet();
                    points.add(running);
                }
            }
            int from = Math.max(0, points.size() - 60);
            series = new double[points.size() - from];
            for (int i = from; i < points.size(); i++)
            {
                series[i - from] = points.get(i);
            }
        }
        // §13.3 extras: notable drop, encounter, rate vs average, PvP events and baselines.
        NotableDrop notable = null;
        Double rateVsAverage = null;
        List<PvpEvent> pvpEvents = new ArrayList<>();
        Long pvpPreviousNet = null;
        Long pvpAvgKill = null;
        Long pvpAvgDeath = null;
        if (active != null)
        {
            List<ProfitTransaction> transactions = active.getTransactions();
            for (int i = transactions.size() - 1; i >= 0; i--)
            {
                ProfitTransaction t = transactions.get(i);
                if (t == null || !t.isCounted())
                {
                    continue;
                }
                for (ItemFlow f : t.getFlows())
                {
                    if (f == null)
                    {
                        continue;
                    }
                    if (notable == null && f.getQuantityDelta() > 0L && f.getValueDelta() >= context.notableDropGp
                        && now - t.getTimestampEpochMillis() <= 30 * 60_000L)
                    {
                        notable = new NotableDrop(f.getItemId(), f.getItemName(), f.getValueDelta(), t.getTimestampEpochMillis());
                    }
                }
            }
            if (custom && RateAvailability.isEstablished(elapsed))
            {
                com.gpmanager.model.ActivityAverageSnapshot avg = engine.getActivityAverage(active.getName(), active.getId());
                if (avg != null && avg.isRateAvailable() && avg.getSessionsCounted() >= 3 && avg.getTimeWeightedGpPerHour() != 0L)
                {
                    rateVsAverage = (metrics.getProfitPerHour() - avg.getTimeWeightedGpPerHour()) / (double) Math.abs(avg.getTimeWeightedGpPerHour());
                }
            }
            java.util.Map<String, ProfitTransaction> byId = new java.util.HashMap<>();
            for (ProfitTransaction t : transactions)
            {
                if (t != null)
                {
                    byId.put(t.getId(), t);
                }
            }
            List<com.gpmanager.model.PkEncounter> encounters = new ArrayList<>(active.getPkEncounters());
            for (int i = encounters.size() - 1; i >= 0 && pvpEvents.size() < RECENT_ROWS; i--)
            {
                com.gpmanager.model.PkEncounter e = encounters.get(i);
                if (e == null)
                {
                    continue;
                }
                long value = 0L;
                for (String id : e.getTransactionIds())
                {
                    ProfitTransaction t = byId.get(id);
                    if (t != null && t.isCounted())
                    {
                        value += t.getNet();
                    }
                }
                boolean kill = e.getType() != com.gpmanager.model.PkEncounterType.DEATH;
                pvpEvents.add(new PvpEvent(opponent(e.getLabel()), kill, value, e.getTimestampEpochMillis(),
                    e.getLocationLabel() == null ? "" : e.getLocationLabel()));
            }
            if (pvpSession)
            {
                com.gpmanager.model.SessionSummary previous = engine.getPreviousPkSessionSummary(active.getId());
                if (previous != null)
                {
                    pvpPreviousNet = previous.getMetrics().getNet();
                }
                long kills = 0L, deaths = 0L, killNetSum = 0L, deathLossSum = 0L;
                int counted = 0;
                for (ProfitSession h : engine.getHistory())
                {
                    if (h == null || h.getId().equals(active.getId()) || h.getCategory() != com.gpmanager.model.SessionCategory.PKING)
                    {
                        continue;
                    }
                    com.gpmanager.model.SessionSummary summary = engine.getHistorySummary(h.getId(), now);
                    if (summary == null || summary.getPkMetrics() == null)
                    {
                        continue;
                    }
                    if (pvpPreviousNet == null)
                    {
                        pvpPreviousNet = summary.getMetrics().getNet();
                    }
                    counted++;
                    killNetSum += summary.getPkMetrics().getTotalKillNet();
                    deathLossSum += summary.getPkMetrics().getTotalDeathLoss();
                }
                if (counted > 0)
                {
                    pvpAvgKill = killNetSum / counted;
                    pvpAvgDeath = deathLossSum / counted;
                }
            }
        }
        Encounter encounter = null;
        if (context.encounter != null && context.encounter.encounters > 0)
        {
            encounter = new Encounter(context.encounter.sourceName, context.encounter.encounters, context.encounter.streak,
                context.encounter.lootValue / Math.max(1, context.encounter.encounters));
        }
        return new LiveSnapshot(
            active != null,
            owner,
            custom,
            elapsed,
            metrics.isPaused(),
            engine.isIdlePaused(),
            engine.isStopped(),
            accountHold,
            metrics.getNet(),
            metrics.getRevenue(),
            costSplit.loss,
            costSplit.supplies,
            RateAvailability.isEstablished(elapsed),
            metrics.getProfitPerHour(),
            active == null ? null : active.getProfitTargetGp(),
            freePlay,
            active != null && SessionsSnapshot.isAuto(active),
            active == null ? 0L : active.getStartedAtEpochMillis(),
            Collections.unmodifiableList(marks),
            overallToday,
            sessionsToday,
            neutralZoneActive,
            reclaim.isAwaiting(),
            reclaim.isArmed(),
            reclaim.getOutstandingItemCount(),
            reclaim.getAgeTicks() * 600L,
            keysHeld,
            review,
            goal(engine, active, metrics, elapsed, now),
            sinceBank(active, boundaries),
            Collections.unmodifiableList(recent),
            pvpSession,
            pk == null ? 0 : pk.getKills(),
            pk == null ? 0 : pk.getDeaths(),
            pk == null ? 0L : pk.getTotalKillNet(),
            pk == null ? 0L : pk.getTotalDeathLoss(),
            pk == null ? 0L : pk.getBestKill(),
            pk == null ? 0 : pk.getCurrentStreak(),
            series,
            notable,
            encounter,
            rateVsAverage,
            Collections.unmodifiableList(pvpEvents),
            pvpPreviousNet,
            pvpAvgKill,
            pvpAvgDeath);
    }

    /** "Kill: Rival Name" / "Death: Rival" → the name; unknown labels stay as they are. */
    static String opponent(String label)
    {
        if (label == null)
        {
            return "";
        }
        String l = label.trim();
        int colon = l.indexOf(':');
        return colon >= 0 && colon < l.length() - 1 ? l.substring(colon + 1).trim() : l;
    }

    /** "Super antifire(4)" → "Super antifire". */
    public static String baseName(String name)
    {
        return name == null ? "" : name.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
    }

    static boolean needsReview(ProfitTransaction t)
    {
        return t.getCorrection() == com.gpmanager.model.TransactionCorrection.AUTO
            && t.getConfidence() == com.gpmanager.model.ClassificationConfidence.UNCERTAIN;
    }

    @Nullable
    public static Recent toRecent(ProfitTransaction t)
    {
        if (t == null || !ActionPresentation.showsOn(t, ActionPresentation.Surface.LIVE_TIMELINE))
        {
            return null;
        }
        List<ItemFlow> flows = t.getFlows();
        ItemFlow lead = null;
        long leadAbs = -1L;
        for (ItemFlow f : flows)
        {
            long abs = Math.abs(f.getValueDelta());
            if (abs > leadAbs)
            {
                leadAbs = abs;
                lead = f;
            }
        }
        boolean neutral = t.getType() == TransactionType.TRANSFER;
        ActionKind kind = t.getActionKind();
        String verb;
        if (kind != null)
        {
            verb = kind.completedVerb().toLowerCase(Locale.ROOT);
        }
        else if (neutral)
        {
            verb = "neutral";
        }
        else if (t.getNet() >= 0L)
        {
            verb = "received";
        }
        else
        {
            verb = "used";
        }
        String name;
        int itemId;
        String qty = null;
        if (lead != null)
        {
            name = lead.getItemName();
            itemId = lead.getItemId();
            long q = Math.abs(lead.getQuantityDelta());
            if (flows.size() > 1)
            {
                qty = flows.size() + " items";
            }
            else if (q > 1L)
            {
                qty = Fmt.times(q);
            }
        }
        else
        {
            if (t.getNet() == 0L && !needsReview(t))
            {
                // Nothing moved and nothing to decide: not a row.
                return null;
            }
            name = t.getNote().isEmpty() ? t.getActivityName() : t.getNote();
            itemId = -1;
        }
        String tag = null;
        if (needsReview(t))
        {
            tag = "review";
        }
        else if (kind != null && kind.isRoutineRepetitive())
        {
            tag = "quiet";
        }
        return new Recent(itemId, name, qty, verb, t.getNet(), neutral, tag);
    }
}
