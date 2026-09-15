package com.gpmanager.model;

/**
 * Snapshot-derived wealth facts for the seven- and thirty-day cutoffs. The
 * deltas are observed wealth changes and are deliberately separate from counted
 * Net and the market/unexplained attribution model.
 */
public final class WealthChangeFacts
{
    private final WindowChange sevenDays;
    private final WindowChange thirtyDays;
    private final Long latestBankValueGp;
    private final Long latestTotalValueGp;
    private final Double latestBankSharePercent;
    private final long latestCapturedAtEpochMillis;

    public WealthChangeFacts(WindowChange sevenDays, WindowChange thirtyDays,
        WealthBreakdown latest)
    {
        this.sevenDays = sevenDays == null ? WindowChange.unavailable() : sevenDays;
        this.thirtyDays = thirtyDays == null ? WindowChange.unavailable() : thirtyDays;
        if (latest != null)
        {
            WealthBreakdown.GroupValue bank = latest.getGroup(WealthBreakdown.Group.BANK);
            latestBankValueGp = bank == null ? null : bank.getValueGp();
            latestTotalValueGp = latest.getTotalGp();
            latestBankSharePercent = latest.getSharePercent(WealthBreakdown.Group.BANK);
            latestCapturedAtEpochMillis = latest.getCapturedAtEpochMillis();
        }
        else
        {
            latestBankValueGp = null;
            latestTotalValueGp = null;
            latestBankSharePercent = null;
            latestCapturedAtEpochMillis = 0L;
        }
    }

    public WindowChange getSevenDays() { return sevenDays; }
    public WindowChange getThirtyDays() { return thirtyDays; }
    public Long getLatestBankValueGp() { return latestBankValueGp; }
    public Long getLatestTotalValueGp() { return latestTotalValueGp; }
    public Double getLatestBankSharePercent() { return latestBankSharePercent; }
    public boolean isLatestBankShareAvailable() { return latestBankSharePercent != null; }
    public long getLatestCapturedAtEpochMillis() { return latestCapturedAtEpochMillis; }

    /** Absolute delta may be available when percent is not (for a zero baseline). */
    public static final class WindowChange
    {
        private final long baselineCapturedAtEpochMillis;
        private final long latestCapturedAtEpochMillis;
        private final Long baselineGp;
        private final Long latestGp;
        private final Long changeGp;
        private final Double changePercent;

        public WindowChange(long baselineCapturedAtEpochMillis, long latestCapturedAtEpochMillis,
            Long baselineGp, Long latestGp, Long changeGp, Double changePercent)
        {
            this.baselineCapturedAtEpochMillis = Math.max(0L, baselineCapturedAtEpochMillis);
            this.latestCapturedAtEpochMillis = Math.max(0L, latestCapturedAtEpochMillis);
            this.baselineGp = baselineGp;
            this.latestGp = latestGp;
            this.changeGp = changeGp;
            this.changePercent = changePercent;
        }

        public static WindowChange unavailable()
        {
            return new WindowChange(0L, 0L, null, null, null, null);
        }

        public long getBaselineCapturedAtEpochMillis() { return baselineCapturedAtEpochMillis; }
        public long getLatestCapturedAtEpochMillis() { return latestCapturedAtEpochMillis; }
        public Long getBaselineGp() { return baselineGp; }
        public Long getLatestGp() { return latestGp; }
        public Long getChangeGp() { return changeGp; }
        public Double getChangePercent() { return changePercent; }
        public boolean isChangeAvailable() { return changeGp != null; }
        public boolean isPercentAvailable() { return changePercent != null; }
    }
}
