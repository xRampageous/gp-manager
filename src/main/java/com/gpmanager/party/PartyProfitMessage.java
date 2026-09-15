package com.gpmanager.party;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A replaceable snapshot of one member's current GP Manager session.
 *
 * Party messages are intentionally snapshots rather than individual
 * transactions. This keeps the party total correct when a member joins late,
 * reconnects, or has a transaction corrected locally.
 */
public final class PartyProfitMessage extends PartyMemberMessage
{
    private String generation;
    private long revision;
    private long timestampEpochMillis;
    private String sessionId;
    private long revenue;
    private long costs;
    private long net;
    private long profitPerHour;
    private long rollingProfitPerHour;
    private int transactionCount;
    private String activityName;
    private String notableDrop;

    public PartyProfitMessage()
    {
        // Gson
    }

    public PartyProfitMessage(
        String generation,
        long revision,
        long timestampEpochMillis,
        String sessionId,
        long revenue,
        long costs,
        long net,
        long profitPerHour,
        long rollingProfitPerHour,
        int transactionCount)
    {
        this(generation, revision, timestampEpochMillis, sessionId, revenue, costs, net,
            profitPerHour, rollingProfitPerHour, transactionCount, "", null);
    }

    public PartyProfitMessage(
        String generation,
        long revision,
        long timestampEpochMillis,
        String sessionId,
        long revenue,
        long costs,
        long net,
        long profitPerHour,
        long rollingProfitPerHour,
        int transactionCount,
        String activityName,
        String notableDrop)
    {
        this.generation = generation;
        this.revision = revision;
        this.timestampEpochMillis = timestampEpochMillis;
        this.sessionId = sessionId;
        this.revenue = revenue;
        this.costs = costs;
        this.net = net;
        this.profitPerHour = profitPerHour;
        this.rollingProfitPerHour = rollingProfitPerHour;
        this.transactionCount = transactionCount;
        this.activityName = normalizeOptional(activityName);
        this.notableDrop = normalizeOptional(notableDrop);
    }

    public String getGeneration()
    {
        return generation == null ? "" : generation;
    }

    public long getRevision()
    {
        return revision;
    }

    public long getTimestampEpochMillis()
    {
        return timestampEpochMillis;
    }

    public String getSessionId()
    {
        return sessionId == null ? "" : sessionId;
    }

    public long getRevenue()
    {
        return revenue;
    }

    public long getCosts()
    {
        return costs;
    }

    public long getNet()
    {
        return net;
    }

    public long getProfitPerHour()
    {
        return profitPerHour;
    }

    public long getRollingProfitPerHour()
    {
        return rollingProfitPerHour;
    }

    public int getTransactionCount()
    {
        return transactionCount;
    }

    public String getActivityName()
    {
        return activityName == null ? "" : activityName.trim();
    }

    public String getNotableDrop()
    {
        return normalizeOptional(notableDrop);
    }

    private static String normalizeOptional(String value)
    {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
