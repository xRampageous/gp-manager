package com.gpmanager;

import static com.gpmanager.Fmt.*;
import static com.gpmanager.GameData.msg;

import static java.lang.Math.*;

import lombok.*;

import java.util.*;

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
