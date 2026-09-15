package com.gpmanager.model;

import java.time.LocalDate;

/**
 * Facts-only retained-history totals for the account profile or one local day.
 * Coverage is explicit so a missing legacy dimension is never shown as zero.
 */
public final class OverallTotalsSnapshot
{
    private final String zoneId;
    private final LocalDate requestedDate;
    private final long netGp;
    private final long activeMillis;
    private final int namedSessionsStarted;
    private final int daysTracked;
    private final LocalDate firstTrackedDate;
    private final String firstTrackedZoneId;
    private final DailyRollup.Coverage netCoverage;
    private final DailyRollup.Coverage activeTimeCoverage;
    private final DailyRollup.Coverage namedSessionStartsCoverage;
    private final DailyRollup.Coverage daysTrackedCoverage;

    public OverallTotalsSnapshot(String zoneId, LocalDate requestedDate, long netGp, long activeMillis,
        int namedSessionsStarted, int daysTracked, LocalDate firstTrackedDate,
        String firstTrackedZoneId, DailyRollup.Coverage netCoverage,
        DailyRollup.Coverage activeTimeCoverage,
        DailyRollup.Coverage namedSessionStartsCoverage,
        DailyRollup.Coverage daysTrackedCoverage)
    {
        this.zoneId = zoneId == null ? "UTC" : zoneId;
        this.requestedDate = requestedDate;
        this.netGp = netGp;
        this.activeMillis = Math.max(0L, activeMillis);
        this.namedSessionsStarted = Math.max(0, namedSessionsStarted);
        this.daysTracked = Math.max(0, daysTracked);
        this.firstTrackedDate = firstTrackedDate;
        this.firstTrackedZoneId = firstTrackedZoneId == null ? "" : firstTrackedZoneId;
        this.netCoverage = safeCoverage(netCoverage);
        this.activeTimeCoverage = safeCoverage(activeTimeCoverage);
        this.namedSessionStartsCoverage = safeCoverage(namedSessionStartsCoverage);
        this.daysTrackedCoverage = safeCoverage(daysTrackedCoverage);
    }

    public String getZoneId() { return zoneId; }
    /** Non-null only for a single-day snapshot. */
    public LocalDate getRequestedDate() { return requestedDate; }
    public long getNetGp() { return netGp; }
    public long getActiveMillis() { return activeMillis; }
    public int getNamedSessionsStarted() { return namedSessionsStarted; }
    /** Count of distinct retained (local date, zone) keys with tracking evidence. */
    public int getDaysTracked() { return daysTracked; }
    public LocalDate getFirstTrackedDate() { return firstTrackedDate; }
    public String getFirstTrackedZoneId() { return firstTrackedZoneId; }
    public DailyRollup.Coverage getNetCoverage() { return netCoverage; }
    public DailyRollup.Coverage getActiveTimeCoverage() { return activeTimeCoverage; }
    public DailyRollup.Coverage getNamedSessionStartsCoverage() { return namedSessionStartsCoverage; }
    public DailyRollup.Coverage getDaysTrackedCoverage() { return daysTrackedCoverage; }
    public boolean isNetAvailable() { return netCoverage != DailyRollup.Coverage.UNAVAILABLE; }
    public boolean isActiveTimeAvailable() { return activeTimeCoverage != DailyRollup.Coverage.UNAVAILABLE; }
    public boolean areNamedSessionStartsAvailable()
    {
        return namedSessionStartsCoverage != DailyRollup.Coverage.UNAVAILABLE;
    }
    public boolean areDaysTrackedAvailable() { return daysTrackedCoverage != DailyRollup.Coverage.UNAVAILABLE; }
    public boolean isNetComplete() { return netCoverage == DailyRollup.Coverage.COMPLETE; }
    public boolean isActiveTimeComplete() { return activeTimeCoverage == DailyRollup.Coverage.COMPLETE; }
    public boolean areNamedSessionStartsComplete()
    {
        return namedSessionStartsCoverage == DailyRollup.Coverage.COMPLETE;
    }
    public boolean areDaysTrackedComplete() { return daysTrackedCoverage == DailyRollup.Coverage.COMPLETE; }
    public boolean isFirstTrackedDateAvailable() { return areDaysTrackedComplete(); }

    private static DailyRollup.Coverage safeCoverage(DailyRollup.Coverage value)
    {
        return value == null ? DailyRollup.Coverage.UNAVAILABLE : value;
    }
}
