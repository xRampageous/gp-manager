package com.gpmanager.model;

/** Stable coin-only stores that contribute to Wealth's Other group. */
public enum CoinStore
{
    NMZ_COFFER("nmz_coffer", "NMZ coffer"),
    BLAST_FURNACE_COFFER("blast_furnace_coffer", "Blast Furnace coffer"),
    SERVANT_MONEYBAG("servant_moneybag", "Servant's moneybag");

    private final String locationId;
    private final String title;
    CoinStore(String locationId, String title) { this.locationId = locationId; this.title = title; }
    public String getLocationId() { return locationId; }
    /** The name the sidebar shows; never the enum constant. */
    public String getTitle() { return title; }

    /** The store behind a wealth location id, or null. */
    public static CoinStore forLocationId(String id)
    {
        for (CoinStore store : values()) if (store.locationId.equalsIgnoreCase(id == null ? "" : id.trim())) return store;
        return null;
    }
}
