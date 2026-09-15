package com.gpmanager.model;

/**
 * Compact, evidence-backed session key statistic.
 *
 * <p>Selection precedence is fixed: PK sessions use PK kill count; otherwise a
 * retained observed encounter source with at least one encounter wins; otherwise
 * the most-gathered counted gain with quantity at least 20 wins; otherwise the
 * counted gained-receipt total is used. Any missing coverage needed to prove an
 * earlier rule does not fall through to a lower-priority statistic.</p>
 */
public final class SessionHighlight
{
    public enum Kind
    {
        PK_KILLS,
        KILLS,
        GATHERED,
        DROPS,
        UNAVAILABLE
    }

    private final Kind kind;
    private final boolean available;
    private final long count;
    private final String sourceName;
    private final int itemId;
    private final String itemName;
    private final long quantity;
    private final String unavailableReason;

    private SessionHighlight(Kind kind, boolean available, long count, String sourceName,
        int itemId, String itemName, long quantity, String unavailableReason)
    {
        this.kind = kind == null ? Kind.UNAVAILABLE : kind;
        this.available = available;
        this.count = Math.max(0L, count);
        this.sourceName = sourceName == null ? "" : sourceName;
        this.itemId = Math.max(0, itemId);
        this.itemName = itemName == null ? "" : itemName;
        this.quantity = Math.max(0L, quantity);
        this.unavailableReason = unavailableReason == null ? "" : unavailableReason;
    }

    public static SessionHighlight of(Kind kind, long count, String sourceName)
    {
        return new SessionHighlight(kind, true, count, sourceName, 0, "", 0L, "");
    }

    public static SessionHighlight gathered(int itemId, String itemName, long quantity)
    {
        return new SessionHighlight(Kind.GATHERED, true, quantity, "", itemId,
            itemName, quantity, "");
    }

    public static SessionHighlight unavailable(String reason)
    {
        return new SessionHighlight(Kind.UNAVAILABLE, false, 0L, "", 0, "", 0L, reason);
    }

    /** Adds the independent raw facts used to format a session card. */
    public SessionHighlight withRawParts(long kills, boolean killsAvailable,
        String topSourceName, long topSourceKills, boolean topSourceAvailable,
        int topItemId, String topItemName, long topItemQuantity, boolean topItemAvailable,
        long drops, boolean dropsAvailable)
    {
        SessionHighlight copy = new SessionHighlight(kind, available, count, sourceName,
            itemId, itemName, quantity, unavailableReason);
        copy.rawParts = new RawParts(Math.max(0L, kills), killsAvailable,
            topSourceName, Math.max(0L, topSourceKills), topSourceAvailable,
            Math.max(0, topItemId), topItemName, Math.max(0L, topItemQuantity), topItemAvailable,
            Math.max(0L, drops), dropsAvailable);
        return copy;
    }

    private RawParts rawParts = RawParts.EMPTY;

    private static final class RawParts
    {
        private static final RawParts EMPTY = new RawParts(0L, false, "", 0L, false,
            0, "", 0L, false, 0L, false);
        private final long kills;
        private final boolean killsAvailable;
        private final String sourceName;
        private final long sourceKills;
        private final boolean sourceAvailable;
        private final int itemId;
        private final String itemName;
        private final long itemQuantity;
        private final boolean itemAvailable;
        private final long drops;
        private final boolean dropsAvailable;

        private RawParts(long kills, boolean killsAvailable, String sourceName, long sourceKills,
            boolean sourceAvailable, int itemId, String itemName, long itemQuantity,
            boolean itemAvailable, long drops, boolean dropsAvailable)
        {
            this.kills = kills;
            this.killsAvailable = killsAvailable;
            this.sourceName = sourceName == null ? "" : sourceName;
            this.sourceKills = sourceKills;
            this.sourceAvailable = sourceAvailable;
            this.itemId = itemId;
            this.itemName = itemName == null ? "" : itemName;
            this.itemQuantity = itemQuantity;
            this.itemAvailable = itemAvailable;
            this.drops = drops;
            this.dropsAvailable = dropsAvailable;
        }
    }

    public Kind getKind() { return kind; }
    public boolean isAvailable() { return available; }
    /** Kill or drop count, or gathered quantity for {@link Kind#GATHERED}. */
    public long getCount() { return count; }
    public String getSourceName() { return sourceName; }
    public int getItemId() { return itemId; }
    public String getItemName() { return itemName; }
    public long getQuantity() { return quantity; }
    public String getUnavailableReason() { return unavailableReason; }
    public long getKills() { return rawParts.kills; }
    public boolean areKillsAvailable() { return rawParts.killsAvailable; }
    public String getTopSourceName() { return rawParts.sourceName; }
    public long getTopSourceKills() { return rawParts.sourceKills; }
    public boolean isTopSourceAvailable() { return rawParts.sourceAvailable; }
    public int getTopItemId() { return rawParts.itemId; }
    public String getTopItemName() { return rawParts.itemName; }
    public long getTopItemQuantity() { return rawParts.itemQuantity; }
    public boolean isTopItemAvailable() { return rawParts.itemAvailable; }
    public long getDrops() { return rawParts.drops; }
    public boolean areDropsAvailable() { return rawParts.dropsAvailable; }
}
