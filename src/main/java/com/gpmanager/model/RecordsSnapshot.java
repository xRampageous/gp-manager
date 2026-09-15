package com.gpmanager.model;

import java.time.LocalDate;

/**
 * Evidence-backed all-time (retained-history) records.  A missing value is
 * represented by {@link Record#isAvailable()}, never by a zero value.
 */
public final class RecordsSnapshot
{
    public static final class Record
    {
        private final Long value;
        private final String sessionId;
        private final LocalDate date;
        private final DailyRollup.Coverage coverage;
        private final String status;

        public Record(Long value, String sessionId, LocalDate date,
            DailyRollup.Coverage coverage, String status)
        {
            this.value = value;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.date = date;
            this.coverage = coverage == null ? DailyRollup.Coverage.UNAVAILABLE : coverage;
            this.status = status == null || status.trim().isEmpty() ? "UNAVAILABLE" : status;
        }
        public static Record unavailable(String status)
        {
            return new Record(null, "", null, DailyRollup.Coverage.UNAVAILABLE, status);
        }
        public Long getValue() { return value; }
        public String getSessionId() { return sessionId; }
        public LocalDate getDate() { return date; }
        public DailyRollup.Coverage getCoverage() { return coverage; }
        public boolean isAvailable() { return value != null && coverage != DailyRollup.Coverage.UNAVAILABLE; }
        public boolean isComplete() { return isAvailable() && coverage == DailyRollup.Coverage.COMPLETE; }
        public String getStatus() { return status; }
    }

    private final Record bestSessionNet;
    private final Record bestSessionRate;
    private final Record longestSession;
    private final Record bestDay;
    private final Record bestWeek;
    private final Record wealthHigh;
    private final Record currentDailyStreak;
    private final Record longestDailyStreak;
    private final Record bestPvpKill;
    private final Record longestPvpStreak;
    private final Record bestPvpKd;

    public RecordsSnapshot(Record bestSessionNet, Record bestSessionRate, Record longestSession,
        Record bestDay, Record bestWeek, Record wealthHigh, Record currentDailyStreak,
        Record longestDailyStreak)
    {
        this(bestSessionNet, bestSessionRate, longestSession, bestDay, bestWeek, wealthHigh,
            currentDailyStreak, longestDailyStreak, null, null, null);
    }

    public RecordsSnapshot(Record bestSessionNet, Record bestSessionRate, Record longestSession,
        Record bestDay, Record bestWeek, Record wealthHigh, Record currentDailyStreak,
        Record longestDailyStreak, Record bestPvpKill, Record longestPvpStreak, Record bestPvpKd)
    {
        this.bestSessionNet = safe(bestSessionNet); this.bestSessionRate = safe(bestSessionRate);
        this.longestSession = safe(longestSession); this.bestDay = safe(bestDay);
        this.bestWeek = safe(bestWeek); this.wealthHigh = safe(wealthHigh);
        this.currentDailyStreak = safe(currentDailyStreak); this.longestDailyStreak = safe(longestDailyStreak);
        this.bestPvpKill = safe(bestPvpKill); this.longestPvpStreak = safe(longestPvpStreak); this.bestPvpKd = safe(bestPvpKd);
    }
    public Record getBestSessionNet() { return bestSessionNet; }
    public Record getBestSessionRate() { return bestSessionRate; }
    public Record getLongestSession() { return longestSession; }
    public Record getBestDay() { return bestDay; }
    public Record getBestWeek() { return bestWeek; }
    public Record getWealthHigh() { return wealthHigh; }
    public Record getCurrentDailyStreak() { return currentDailyStreak; }
    public Record getLongestDailyStreak() { return longestDailyStreak; }
    public Record getBestPvpKill() { return bestPvpKill; }
    public Record getLongestPvpStreak() { return longestPvpStreak; }
    public Record getBestPvpKd() { return bestPvpKd; }
    private static Record safe(Record value) { return value == null ? Record.unavailable("UNAVAILABLE") : value; }
}
