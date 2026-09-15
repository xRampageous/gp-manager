package com.gpmanager.model;

public final class SessionSummary
{
    private final String sessionId;
    private final String name;
    private final String tags;
    private final String notes;
    private final boolean favorite;
    private final SessionCategory category;
    private final boolean recovered;
    private final boolean excludedFromAverages;
    private final SessionEndReason endReason;
    private final SessionOwnerKind ownerKind;
    private final boolean auto;
    private final long startedAtEpochMillis;
    private final long endedAtEpochMillis;
    private final SessionMetrics metrics;
    private final PkMetrics pkMetrics;
    private final SessionHighlight highlight;

    public SessionSummary(
        ProfitSession session,
        long now,
        long rollingWindowMillis)
    {
        this(session, now, rollingWindowMillis, null);
    }

    public SessionSummary(
        ProfitSession session,
        long now,
        long rollingWindowMillis,
        SessionMetrics metrics)
    {
        this(session, now, rollingWindowMillis, metrics, session.pkMetrics());
    }

    public SessionSummary(
        ProfitSession session,
        long now,
        long rollingWindowMillis,
        SessionMetrics metrics,
        PkMetrics pkMetrics)
    {
        this.sessionId = session.getId();
        this.name = session.getName();
        this.tags = session.getTagsDisplay();
        this.notes = session.getNotes();
        this.favorite = session.isFavorite();
        this.category = session.getCategory();
        this.recovered = session.isRecoveredFromCrash();
        this.excludedFromAverages = session.isExcludedFromAverages();
        this.endReason = session.getEndReason();
        this.ownerKind = session.getOwnerKind();
        this.auto = session.getMode() == SessionMode.AUTO;
        this.startedAtEpochMillis = session.getStartedAtEpochMillis();
        this.endedAtEpochMillis = session.getEndedAtEpochMillis();
        this.metrics = metrics == null ? session.metrics(now, rollingWindowMillis) : metrics;
        this.pkMetrics = pkMetrics == null ? session.pkMetrics() : pkMetrics;
        this.highlight = session.getSessionHighlight();
    }

    public String getSessionId() { return sessionId; }
    public String getName() { return name; }
    public String getTags() { return tags; }
    public String getNotes() { return notes; }
    public boolean isFavorite() { return favorite; }
    public SessionCategory getCategory() { return category; }
    public boolean isRecovered() { return recovered; }
    public boolean isExcludedFromAverages() { return excludedFromAverages; }
    /** Nullable for active and legacy sessions where no durable close reason exists. */
    public SessionEndReason getEndReason() { return endReason; }
    /** Durable owner provenance; UNKNOWN deliberately remains unavailable provenance. */
    public SessionOwnerKind getOwnerKind() { return ownerKind; }
    /** True only for an automatically-created session, never inferred from its name or tags. */
    public boolean isAuto() { return auto; }
    public long getStartedAtEpochMillis() { return startedAtEpochMillis; }
    public long getEndedAtEpochMillis() { return endedAtEpochMillis; }
    public SessionMetrics getMetrics() { return metrics; }
    public PkMetrics getPkMetrics() { return pkMetrics; }
    public SessionHighlight getHighlight() { return highlight; }
    public long getSuppliesCosts() { return metrics.getSuppliesCosts(); }
    public long getOtherCosts() { return metrics.getOtherCosts(); }
    public boolean isCostSplitAvailable() { return metrics.isCostSplitAvailable(); }
}
