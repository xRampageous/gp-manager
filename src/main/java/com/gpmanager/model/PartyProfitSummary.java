package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.awt.image.BufferedImage;

/** Immutable party-wide view of the current member session snapshots. */
public final class PartyProfitSummary
{
    private static final PartyProfitSummary EMPTY = new PartyProfitSummary(
        false,
        0,
        0L,
        0L,
        0L,
        0L,
        0L,
        Collections.emptyList());

    private final boolean inParty;
    private final int memberCount;
    private final long revenue;
    private final long costs;
    private final long net;
    private final long profitPerHour;
    private final long rollingProfitPerHour;
    private final List<Member> members;

    public PartyProfitSummary(
        boolean inParty,
        int memberCount,
        long revenue,
        long costs,
        long net,
        long profitPerHour,
        long rollingProfitPerHour,
        List<Member> members)
    {
        this.inParty = inParty;
        this.memberCount = Math.max(0, memberCount);
        this.revenue = revenue;
        this.costs = costs;
        this.net = net;
        this.profitPerHour = profitPerHour;
        this.rollingProfitPerHour = rollingProfitPerHour;
        this.members = members == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(members));
    }

    public static PartyProfitSummary empty()
    {
        return EMPTY;
    }

    public boolean isInParty()
    {
        return inParty;
    }

    public int getMemberCount()
    {
        return memberCount;
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

    public List<Member> getMembers()
    {
        return members;
    }

    public int getReportingCount()
    {
        int count = 0;
        for (Member member : members) if (member.isFreshReport()) count++;
        return count;
    }

    public boolean hasPartialRate()
    {
        for (Member member : members)
        {
            if (member.isFreshReport() && !member.hasReportedRate()) return true;
        }
        return false;
    }

    public static final class Member
    {
        private final String displayName;
        private final long revenue;
        private final long costs;
        private final long net;
        private final long profitPerHour;
        private final long rollingProfitPerHour;
        private final int transactionCount;
        private final boolean local;
        private final boolean reporting;
        private final boolean fresh;
        private final boolean reportedRate;
        private final long updatedAtEpochMillis;
        private final BufferedImage avatar;

        public Member(
            String displayName,
            long revenue,
            long costs,
            long net,
            long profitPerHour,
            long rollingProfitPerHour,
            int transactionCount,
            boolean local)
        {
            this(displayName, revenue, costs, net, profitPerHour, rollingProfitPerHour,
                transactionCount, local, true, true, transactionCount > 0, 0L);
        }

        public Member(
            String displayName,
            long revenue,
            long costs,
            long net,
            long profitPerHour,
            long rollingProfitPerHour,
            int transactionCount,
            boolean local,
            boolean reporting,
            boolean fresh,
            boolean reportedRate,
            long updatedAtEpochMillis)
        {
            this(displayName, revenue, costs, net, profitPerHour, rollingProfitPerHour, transactionCount,
                local, reporting, fresh, reportedRate, updatedAtEpochMillis, null);
        }

        public Member(
            String displayName, long revenue, long costs, long net, long profitPerHour, long rollingProfitPerHour,
            int transactionCount, boolean local, boolean reporting, boolean fresh, boolean reportedRate,
            long updatedAtEpochMillis, BufferedImage avatar)
        {
            this.displayName = displayName == null || displayName.trim().isEmpty()
                ? "Party member"
                : displayName.trim();
            this.revenue = revenue;
            this.costs = costs;
            this.net = net;
            this.profitPerHour = profitPerHour;
            this.rollingProfitPerHour = rollingProfitPerHour;
            this.transactionCount = Math.max(0, transactionCount);
            this.local = local;
            this.reporting = reporting;
            this.fresh = fresh;
            this.reportedRate = reportedRate;
            this.updatedAtEpochMillis = Math.max(0L, updatedAtEpochMillis);
            this.avatar = avatar;
        }

        public static Member notReporting(String displayName, boolean local)
        {
            return new Member(displayName, 0L, 0L, 0L, 0L, 0L, 0,
                local, false, false, false, 0L);
        }

        public String getDisplayName()
        {
            return displayName;
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

        public boolean isLocal()
        {
            return local;
        }

        public boolean isReporting() { return reporting; }
        public boolean isFreshReport() { return reporting && fresh; }
        public boolean hasReportedRate() { return reportedRate; }
        public long getUpdatedAtEpochMillis() { return updatedAtEpochMillis; }
        public BufferedImage getAvatar() { return avatar; }
    }
}
