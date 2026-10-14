package com.gpmanager;

import static com.gpmanager.Fmt.*;
import static com.gpmanager.Phrases.*;
import static com.gpmanager.SafeMath.*;

import static java.lang.Math.*;

import lombok.*;

import java.time.ZoneId;
import java.util.*;
import static com.gpmanager.GameData.msg;

/**
 * Grinds read model: the current canonical Grind, the reusable My Grinds definitions with their
 * derived Last/Best subset, the Recent Grinds history and one Grind detail. Targets and Pace are
 * derived from canonical facts; estimates never feed accounting, completion or PBs.
 */
@AllArgsConstructor
class GrindsData {
    /** A target field that did not parse: it never mutates and never means "clear". */
    static final long INVALID = -1L;

    /**
     * Friendly Net-target parsing: 5m, 5M, 5000k, 5,000,000. Blank is 0 (no target); malformed is
     * {@link #INVALID}.
     */
    static long parseNet(String raw) {
        if (blank(raw)) return 0L;
        // Strict whole-string form: digits with optional decimals and one k/m/b suffix. Anything
        // else is malformed and must never be treated as "clear".
        String text = Phrases.lower(raw).replace(",", "").replace("_", "").replace(" ", "");
        if (!text.matches("\\d+(?:\\.\\d+)?[kmb]?")) return INVALID;
        long value = parseGp(text);
        return value > 0L ? value : INVALID;
    }

    /**
     * Friendly Active-Time parsing: 90m, 3h, "3h 30m", plain minutes. Blank is 0 (no target);
     * malformed is {@link #INVALID}.
     */
    static long parseTime(String raw) {
        if (blank(raw)) return 0L;
        String text = Phrases.lower(raw).replace(",", "").replace("_", "").trim();
        if (text.matches("\\d+(?:\\.\\d+)?")) {
            long minutes = round(Double.parseDouble(text));
            return minutes > 0L ? minutes * 60_000L : INVALID;
        }
        // Strict whole-string form: optional "Nh" then optional "Mm" and nothing else.
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                                "^(?:(\\d+(?:\\.\\d+)?)\\s*h)?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*m)?$")
                        .matcher(text);
        if (!matcher.matches() || (matcher.group(1) == null && matcher.group(2) == null)) {
            return INVALID;
        }
        long total = 0L;
        if (matcher.group(1) != null)
            total += round(Double.parseDouble(matcher.group(1)) * 3_600_000d);
        if (matcher.group(2) != null)
            total += round(Double.parseDouble(matcher.group(2)) * 60_000d);
        return total > 0L ? total : INVALID;
    }

    /** Applies a Net input to an existing target: INVALID keeps the existing value untouched. */
    static Long applyNet(String raw, Long existing) {
        return apply(parseNet(raw), existing);
    }

    /** Applies an Active-Time input to an existing target: INVALID keeps the existing value. */
    static Long applyTime(String raw, Long existing) {
        return apply(parseTime(raw), existing);
    }

    static Long apply(long parsed, Long existing) {
        return parsed == INVALID ? existing : parsed == 0L ? null : (Long) parsed;
    }

    /** True when both fields are safe to apply (valid or deliberately blank). */
    static boolean inputsValid(String netRaw, String timeRaw) {
        return parseNet(netRaw) != INVALID && parseTime(timeRaw) != INVALID;
    }

    /** Derived pace over canonical facts; nothing here is booked or persisted. */
    @AllArgsConstructor
    static class Pace {
        /** True when a Pace question exists at all (at least one target). */
        boolean present;

        boolean available;
        String line;
        String second;

        /** No target at all: there is no Pace question to answer, so no row renders. */
        static Pace none() {
            return new Pace(false, false, "", null);
        }
    }

    /**
     * Mode A/B/C pace. A: time until the Net target. B: projected Net at the Active-Time target. C:
     * both plus the required average. Unavailable evidence reads "Calculating…" instead of a
     * fabricated precision; estimates stay visually muted.
     */
    static Pace pace(
            Long netTargetGp,
            Long timeTargetMillis,
            long net,
            boolean rateEstablished,
            long gpPerHour,
            long activeMillis) {
        if (netTargetGp == null && timeTargetMillis == null) return Pace.none();
        if (!rateEstablished) return new Pace(true, false, "Calculating\u2026", null);
        if (netTargetGp != null && timeTargetMillis == null) {
            if (net >= netTargetGp) return new Pace(true, true, "Target reached", null);
            if (gpPerHour <= 0L) {
                // The rate is established and genuinely non-positive: an ETA would be fabricated.
                return new Pace(true, true, "Not earning at this rate", null);
            }
            long minutes = nonNeg(round((netTargetGp - net) * 60d / gpPerHour));
            return new Pace(true, true, "~" + durationMinutes(minutes) + " remaining", null);
        }
        long remaining = nonNeg(timeTargetMillis - activeMillis);
        long projected = net + round(gpPerHour * (remaining / 3_600_000d));
        if (netTargetGp == null) {
            return remaining <= 0L
                    ? new Pace(true, true, "Time target reached", null)
                    : new Pace(true, true, "~" + signed(projected) + " projected Net", null);
        }
        if (remaining <= 0L) {
            return new Pace(
                    true,
                    true,
                    "Time target reached",
                    "Required average was "
                            + compact(requiredAverage(netTargetGp, net, activeMillis))
                            + "/h");
        }
        long required = requiredAverage(netTargetGp, net, timeTargetMillis);
        return new Pace(
                true,
                true,
                "~"
                        + signed(projected)
                        + " projected at "
                        + durationMinutes(timeTargetMillis / 60_000L),
                "Required average " + compact(required) + "/h");
    }

    static long requiredAverage(long target, long net, long byMillis) {
        if (byMillis <= 0L) return 0L;
        return round(nonNeg(target - net) * 3_600_000d / byMillis);
    }

    /**
     * A reached aim's short projection: where the next hour lands at the established rate; the
     * rate itself lives on the hero, so the line stays one short phrase (owner 2026-10-14).
     */
    static String nextHour(long net, long gpPerHour) {
        return "~" + signed(net + gpPerHour) + " in 1h";
    }

    static String durationMinutes(long minutes) {
        return minutes < 90L ? minutes + "m" : (minutes / 60L) + "h " + (minutes % 60L) + "m";
    }

    /** One reusable definition plus its derived Last/Best subset from linked canonical history. */
    @AllArgsConstructor
    static class MyGrind {
        SavedState.SavedGrind definition;
        int linkedCount;
        boolean hasLast;
        long lastNet;
        long lastStartedAt;
        boolean hasBest;
        long bestNet;
    }

    /** One recent canonical instance, rendered with Grind language. */
    @AllArgsConstructor
    static class Recent {
        String sessionId;
        String name;
        long startedAt;
        long net;
        long activeMillis;
    }

    /** One instance's detail: financial summary, targets and target outcome. */
    @AllArgsConstructor
    static class Detail {
        String sessionId;
        String name;
        long startedAt;
        long net;
        /** What the Net aim counted when this ran (the run's Net minus its set baseline). */
        long targetProgress;
        long revenue;
        long supplies;
        long losses;
        boolean splitAvailable;
        long activeMillis;
        /** The Active Time window the run's time target counts from (set moment to now/end). */
        long targetWindowMillis;
        boolean rateEstablished;
        long gpPerHour;
        Long netTargetGp;
        Long activeTimeTargetMillis;
        boolean closed;
        boolean compacted;
        String linkedGrindId;

        /** Factual target outcome wording; never "Failed". */
        String netOutcome() {
            return GrindHistory.netOutcome(netTargetGp, targetProgress, closed);
        }

        String timeOutcome() {
            return GrindHistory.timeOutcome(activeTimeTargetMillis, targetWindowMillis);
        }
    }

    boolean hasActive;
    String activeName;
    String activeSessionId;
    long activeNet;
    long activeGpPerHour;
    boolean activeRateEstablished;
    long activeMillis;
    Long activeNetTarget;
    Long activeTimeTarget;
    String activeGrindId;
    Pace activePace;
    List<MyGrind> myGrinds;
    List<Recent> recent;
    ZoneId profileZone;
    Detail detail;
    AllTime allTime;

    /** The All time card: every retained session (Free play included) from canonical metrics. */
    @AllArgsConstructor
    static class AllTime {
        long net;
        long activeMillis;
        Long gpPerHour;
        int grinds;
        boolean splitAvailable;
        long supplies;
        long losses;
        String bestNetName;
        long bestNet;
        String bestRateName;
        long bestRate;
    }

    static AllTime allTime(Engine engine, long now) {
        MoneySnapshot summary = engine.summary();
        long net = 0L, active = 0L, supplies = 0L, losses = 0L, bestNet = 0L, bestRate = 0L;
        var grinds = new HashSet<String>();
        boolean split = true;
        String bestNetName = null;
        String bestRateName = null;
        var seen = new HashSet<String>();
        var sessions = new ArrayList<Session>(engine.getHistory());
        sessions.add(engine.getActiveSession());
        sessions.add(engine.getGeneralSession());
        for (Session session : sessions) {
            if (session == null || !seen.add(session.getId())) continue;
            var run = summary.run(engine, session, now);
            Metrics metrics = run.metrics;
            net = safeAdd(net, metrics.net);
            active = safeAdd(active, metrics.elapsedMillis);
            split &= metrics.costSplitAvailable;
            supplies = safeAdd(supplies, metrics.suppliesCosts);
            losses = safeAdd(losses, metrics.otherCosts);
            if (session.grind) {
                // Runs of one saved Grind count once; an unsaved Grind is its own name.
                grinds.add(session.isLinked() ? session.getGrindId() : "name:" + session.getName());
            }
            if (!session.isClosed()) continue;
            if (bestNetName == null || run.net() > bestNet) {
                bestNetName = session.getName();
                bestNet = run.net();
            }
            if (run.gpPerHour != null && (bestRateName == null || run.gpPerHour > bestRate)) {
                bestRateName = session.getName();
                bestRate = run.gpPerHour;
            }
        }
        return new AllTime(
                net,
                active,
                RateReadiness.isEstablished(active) ? Session.hourly(net, active) : null,
                grinds.size(),
                split,
                supplies,
                losses,
                bestNetName,
                bestNet,
                bestRateName,
                bestRate);
    }

    static GrindsData capture(
            Engine engine, long now, boolean includeArchived, String detailSessionId) {
        MoneySnapshot summary = engine.summary();
        Session active = engine.getActiveSession();
        boolean custom = engine.isCustomActive();
        long net = 0L;
        long rate = 0L;
        boolean rateEstablished = false;
        long activeMillis = 0L;
        Long netTarget = null;
        Long timeTarget = null;
        String activeName = "";
        String activeSessionId = "";
        String activeGrindId = "";
        if (active != null) {
            Metrics metrics = summary.metrics(engine.getActiveSession(), now);
            net = metrics.net;
            rate = metrics.profitPerHour;
            rateEstablished = RateReadiness.isEstablished(metrics.elapsedMillis);
            activeMillis = metrics.elapsedMillis;
            netTarget = active.getProfitTargetGp();
            timeTarget = active.getActiveTimeTargetMillis();
            activeName = active.getName();
            activeSessionId = active.getId();
            activeGrindId = active.getGrindId();
        }
        // The Active Time target's own window counts from when it was set (owner live 2026-10-13);
        // the displayed Active Time above stays the run's total.
        long targetWindowMillis =
                active == null ? 0L : active.getActiveTimeTargetWindowMillis(activeMillis);
        Pace pace =
                pace(
                        netTarget,
                        timeTarget,
                        active == null ? net : active.profitTargetProgress(net),
                        rateEstablished,
                        rate,
                        targetWindowMillis);
        var myGrinds = new ArrayList<MyGrind>();
        for (SavedState.SavedGrind definition : engine.getSavedGrinds(includeArchived)) {
            MoneySnapshot.Rollup rollup = summary.rollups.get(definition.getGrindId());
            myGrinds.add(
                    new MyGrind(
                            definition,
                            rollup.count,
                            rollup.has,
                            rollup.last,
                            rollup.at,
                            rollup.has,
                            rollup.best));
        }
        myGrinds.sort(
                Comparator.comparing((MyGrind grind) -> !grind.definition.favorite)
                        .thenComparing(grind -> -grind.lastStartedAt)
                        .thenComparing(
                                grind -> grind.definition.getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(grind -> grind.definition.getGrindId()));
        var recent = new ArrayList<Recent>();
        for (Session session : engine.getHistory()) {
            if (session == null) continue;
            Metrics metrics = summary.run(engine, session, now).metrics;
            recent.add(
                    new Recent(
                            session.getId(),
                            session.getName(),
                            session.startedAtEpochMillis,
                            metrics.net,
                            metrics.elapsedMillis));
        }
        recent.sort(
                Comparator.comparingLong((Recent item) -> item.startedAt)
                        .reversed()
                        .thenComparing(item -> item.sessionId));
        Detail detail = null;
        if (detailSessionId != null && !detailSessionId.isEmpty()) {
            Session selected =
                    active != null && detailSessionId.equals(active.getId())
                            ? active
                            : engine.historySession(detailSessionId);
            if (selected != null) {
                boolean liveDetail = active != null && detailSessionId.equals(active.getId());
                Metrics metrics =
                        liveDetail
                                ? summary.metrics(engine.getActiveSession(), now)
                                : summary.run(engine, selected, now).metrics;
                String linkedId = selected.getGrindId();
                detail =
                        new Detail(
                                selected.getId(),
                                selected.getName(),
                                selected.startedAtEpochMillis,
                                metrics.net,
                                selected.profitTargetProgress(metrics.net),
                                metrics.revenue,
                                metrics.costSplitAvailable ? metrics.suppliesCosts : -1L,
                                metrics.costSplitAvailable ? metrics.otherCosts : -1L,
                                metrics.costSplitAvailable,
                                metrics.elapsedMillis,
                                selected.getActiveTimeTargetWindowMillis(metrics.elapsedMillis),
                                RateReadiness.isEstablished(metrics.elapsedMillis),
                                Session.hourly(metrics.net, metrics.elapsedMillis),
                                selected.getProfitTargetGp(),
                                selected.getActiveTimeTargetMillis(),
                                selected.isClosed(),
                                selected.compactedRevenue() != 0L
                                        || selected.compactedCosts() != 0L,
                                linkedId);
            }
        }
        ZoneId profileZone = engine.timeZone();
        return new GrindsData(
                active != null && custom,
                activeName,
                activeSessionId,
                net,
                rate,
                rateEstablished,
                activeMillis,
                netTarget,
                timeTarget,
                activeGrindId,
                pace,
                myGrinds,
                recent,
                profileZone,
                detail,
                allTime(engine, now));
    }
}

/**
 * Shared Grind facts: one session's run metrics and the factual target wording the Grinds pages
 * show. Nothing here books, prices, persists or feeds accounting.
 */
class GrindHistory {
    /**
     * One session's shared facts: the retained summary's metrics, else the session's own
     * correction-aware totals, plus the full-session rate once established. Grinds, Grind history
     * and the All time card all read sessions through this, so the pages cannot disagree.
     */
    static class Run {
        Session session;
        Metrics metrics;
        Long gpPerHour;

        /** Detached metrics supplied by the revision summary; fields keep their existing names. */
        Run(Session session, Metrics metrics) {
            this.session = session;
            this.metrics = metrics;
            gpPerHour =
                    RateReadiness.isEstablished(metrics.elapsedMillis)
                            ? Session.hourly(metrics.net, metrics.elapsedMillis)
                            : null;
        }

        long net() {
            return metrics.net;
        }
    }

    /** Factual target wording; never "Failed". Shared with the Grinds detail. */
    static String netOutcome(Long netTargetGp, long net, boolean closed) {
        if (netTargetGp == null) return "No Net target";
        if (net >= netTargetGp) return "Reached " + compact(netTargetGp) + " Net";
        return closed
                ? msg("ended-before") + signed(netTargetGp - net) + " remaining"
                : msg("reached") + signed(netTargetGp - net) + " remaining";
    }

    /** Factual Active-Time target wording; never "Failed". Shared with the Grinds detail. */
    static String timeOutcome(Long activeTimeTargetMillis, long activeMillis) {
        if (activeTimeTargetMillis == null) return msg("active-time-target-2");
        if (activeMillis >= activeTimeTargetMillis) return msg("time-target");
        return duration(activeTimeTargetMillis - activeMillis) + msg("active-time-remaining");
    }

    /** Full-session rate: Net over canonical Active Time; zero Active Time has no rate. */
}

/**
 * One presentation law for "is this GP/h trustworthy yet". A computed rate is only meaningful once
 * the Grind has real Active Time behind it; before that the canonical number is a data-availability
 * artifact, not a rate, and every surface must render the unavailable form ("—") instead of a
 * fabricated 0/h.
 *
 * <p>The minimum window is the historical accepted value (the retired {@code RateAvailability} used
 * the same 60 seconds); it is presentation-only and never affects accounting.
 */
class RateReadiness {
    /** Minimum Active Time before a computed rate is presentation-trustworthy. */
    static final long MIN_ACTIVE_MILLIS = 60_000L;

    /**
     * GP/h is Net over canonical Active Time; it is shown once that Active Time reaches the floor.
     *
     * @param activeMillis canonical Active Time of the Grind
     */
    static boolean isEstablished(long activeMillis) {
        return activeMillis >= MIN_ACTIVE_MILLIS;
    }
}
