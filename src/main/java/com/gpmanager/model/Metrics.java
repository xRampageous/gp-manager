package com.gpmanager;

import static com.gpmanager.SafeMath.safeAdd;

/** Only the canonical money, active clock and rate facts consumed by presentation and export. */
class Metrics {
    String activityHint;
    boolean paused;
    long elapsedMillis;
    long revenue;
    long costs;
    long net;
    long profitPerHour;
    long suppliesCosts;
    long otherCosts;
    boolean costSplitAvailable;

    static Metrics none() {
        return new Metrics("General", false, 0L, 0L, 0L, 0L, 0L);
    }

    Metrics(
            String activityHint,
            boolean paused,
            long elapsedMillis,
            long revenue,
            long costs,
            long net,
            long profitPerHour) {
        this(
                activityHint,
                paused,
                elapsedMillis,
                revenue,
                costs,
                net,
                profitPerHour,
                0L,
                0L,
                false);
    }

    Metrics(
            String activityHint,
            boolean paused,
            long elapsedMillis,
            long revenue,
            long costs,
            long net,
            long profitPerHour,
            long suppliesCosts,
            long otherCosts,
            boolean costSplitAvailable) {
        this.activityHint = activityHint;
        this.paused = paused;
        this.elapsedMillis = elapsedMillis;
        this.revenue = revenue;
        this.costs = costs;
        this.net = net;
        this.profitPerHour = profitPerHour;
        this.suppliesCosts = suppliesCosts;
        this.otherCosts = otherCosts;
        this.costSplitAvailable = costSplitAvailable && safeAdd(suppliesCosts, otherCosts) == costs;
    }
}
