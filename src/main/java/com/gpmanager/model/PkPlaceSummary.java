package com.gpmanager.model;

/** Financial and active-time facts attributed to one explicit PK location label. */
public final class PkPlaceSummary
{
    private final String locationLabel;
    private final int kills;
    private final int deaths;
    private final long netGp;
    private final long attachedSuppliesGp;
    private final long activeMillis;
    private final long firstAtEpochMillis;
    private final long lastAtEpochMillis;
    private final boolean encounterCountsAvailable;
    private final boolean financeAvailable;
    private final boolean activeTimeAvailable;

    public PkPlaceSummary(String locationLabel, int kills, int deaths, long netGp,
        long attachedSuppliesGp,
        long activeMillis, long firstAtEpochMillis, long lastAtEpochMillis,
        boolean encounterCountsAvailable, boolean financeAvailable, boolean activeTimeAvailable)
    {
        this.locationLabel = locationLabel == null ? "" : locationLabel;
        this.kills = Math.max(0, kills);
        this.deaths = Math.max(0, deaths);
        this.netGp = netGp;
        this.attachedSuppliesGp = Math.max(0L, attachedSuppliesGp);
        this.activeMillis = Math.max(0L, activeMillis);
        this.firstAtEpochMillis = Math.max(0L, firstAtEpochMillis);
        this.lastAtEpochMillis = Math.max(0L, lastAtEpochMillis);
        this.encounterCountsAvailable = encounterCountsAvailable;
        this.financeAvailable = financeAvailable;
        this.activeTimeAvailable = activeTimeAvailable;
    }

    public String getLocationLabel() { return locationLabel; }
    public int getKills() { return kills; }
    public int getDeaths() { return deaths; }
    public long getNetGp() { return netGp; }
    /** Informational split only; included already in the correction-aware encounter net. */
    public long getAttachedSuppliesGp() { return attachedSuppliesGp; }
    public long getActiveMillis() { return activeMillis; }
    public long getFirstAtEpochMillis() { return firstAtEpochMillis; }
    public long getLastAtEpochMillis() { return lastAtEpochMillis; }
    public boolean isEncounterCountsAvailable() { return encounterCountsAvailable; }
    public boolean isFinanceAvailable() { return financeAvailable; }
    public boolean isActiveTimeAvailable() { return activeTimeAvailable; }
}
