package com.gpmanager.model;

import java.time.LocalDate;

/** One profile-local calendar-day slot in a wealth trend. Gaps never inherit values. */
public final class WealthTrendPoint
{
    private final LocalDate date;
    private final boolean captured;
    private final WealthBreakdown breakdown;

    public WealthTrendPoint(LocalDate date, WealthBreakdown breakdown)
    {
        if (date == null) throw new IllegalArgumentException("date is required");
        this.date = date;
        this.breakdown = breakdown;
        this.captured = breakdown != null;
    }

    public LocalDate getDate() { return date; }
    public boolean isCaptured() { return captured; }
    /** Null for an uncaptured date; class values may independently be unavailable. */
    public WealthBreakdown getBreakdown() { return breakdown; }
    public Long getTotalGp() { return breakdown == null ? null : breakdown.getTotalGp(); }
}
