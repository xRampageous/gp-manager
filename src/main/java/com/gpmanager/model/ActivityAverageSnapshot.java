package com.gpmanager.model;

/**
 * Durable-summary-only average for one named activity or category. Nullable
 * monetary/rate values mean that retained coverage is insufficient to make
 * that claim. {@code category} is populated only for category averages.
 */
public final class ActivityAverageSnapshot
{
    private final String activityName;
    private final SessionCategory category;
    private final int sessionsCounted;
    private final Long totalNetGp;
    private final Double medianSessionNetGp;
    private final long knownActiveMillis;
    private final Long timeWeightedGpPerHour;
    private final boolean coverageComplete;
    private final String status;

    public ActivityAverageSnapshot(String activityName, int sessionsCounted,
        Long totalNetGp, Double medianSessionNetGp, long knownActiveMillis,
        Long timeWeightedGpPerHour, boolean coverageComplete, String status)
    {
        this(null, activityName, sessionsCounted, totalNetGp, medianSessionNetGp,
            knownActiveMillis, timeWeightedGpPerHour, coverageComplete, status);
    }

    public static ActivityAverageSnapshot forCategory(SessionCategory category, int sessionsCounted,
        Long totalNetGp, Double medianSessionNetGp, long knownActiveMillis,
        Long timeWeightedGpPerHour, boolean coverageComplete, String status)
    {
        return new ActivityAverageSnapshot(category, "", sessionsCounted, totalNetGp, medianSessionNetGp,
            knownActiveMillis, timeWeightedGpPerHour, coverageComplete, status);
    }

    private ActivityAverageSnapshot(SessionCategory category, String activityName,
        int sessionsCounted, Long totalNetGp, Double medianSessionNetGp,
        long knownActiveMillis, Long timeWeightedGpPerHour,
        boolean coverageComplete, String status)
    {
        this.category = category;
        this.activityName = activityName == null ? "" : activityName;
        this.sessionsCounted = Math.max(0, sessionsCounted);
        this.totalNetGp = totalNetGp;
        this.medianSessionNetGp = medianSessionNetGp;
        this.knownActiveMillis = Math.max(0L, knownActiveMillis);
        this.timeWeightedGpPerHour = timeWeightedGpPerHour;
        this.coverageComplete = coverageComplete;
        this.status = status == null || status.trim().isEmpty() ? "UNAVAILABLE" : status.trim();
    }

    public String getActivityName() { return activityName; }
    public SessionCategory getCategory() { return category; }
    public int getSessionsCounted() { return sessionsCounted; }
    public Long getTotalNetGp() { return totalNetGp; }
    public Double getMedianSessionNetGp() { return medianSessionNetGp; }
    public long getKnownActiveMillis() { return knownActiveMillis; }
    public Long getTimeWeightedGpPerHour() { return timeWeightedGpPerHour; }
    public boolean isNetAvailable() { return totalNetGp != null && medianSessionNetGp != null; }
    public boolean isRateAvailable() { return timeWeightedGpPerHour != null; }
    public boolean isCoverageComplete() { return coverageComplete; }
    public String getStatus() { return status; }
}
