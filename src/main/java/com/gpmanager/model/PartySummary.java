package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistence-safe final summary of a party at the end of a party session.
 * Sharing flags record which member figures were actually shared at capture time.
 */
public final class PartySummary
{
    private long combinedNetGp;
    private long elapsedMillis;
    private boolean combinedNetComplete;
    private List<Member> members;

    public PartySummary()
    {
        // Gson
    }

    public PartySummary(long combinedNetGp, long elapsedMillis, List<Member> members)
    {
        this(combinedNetGp, elapsedMillis, members, true);
    }

    public PartySummary(
        long combinedNetGp,
        long elapsedMillis,
        List<Member> members,
        boolean combinedNetComplete)
    {
        this.combinedNetGp = combinedNetGp;
        this.elapsedMillis = Math.max(0L, elapsedMillis);
        this.combinedNetComplete = combinedNetComplete;
        this.members = copyMembers(members);
    }

    public long getCombinedNetGp() { return combinedNetGp; }
    public long getElapsedMillis() { return Math.max(0L, elapsedMillis); }
    public boolean isCombinedNetComplete() { return combinedNetComplete; }

    public List<Member> getMembers()
    {
        return Collections.unmodifiableList(copyMembers(members));
    }

    public PartySummary copy()
    {
        return new PartySummary(combinedNetGp, elapsedMillis, members, combinedNetComplete);
    }

    private static List<Member> copyMembers(List<Member> input)
    {
        List<Member> copy = new ArrayList<>();
        if (input != null)
        {
            for (Member member : input)
            {
                if (member != null)
                {
                    copy.add(member.copy());
                }
            }
        }
        return copy;
    }

    /** Plain member values only; live avatars/freshness snapshots are never persisted here. */
    public static final class Member
    {
        private String displayName;
        private long finalNetGp;
        private long finalGpPerHour;
        private boolean netShared;
        private boolean rateShared;
        private boolean activityShared;
        private boolean notableDropShared;

        public Member()
        {
            // Gson
        }

        public Member(
            String displayName,
            long finalNetGp,
            long finalGpPerHour,
            boolean netShared,
            boolean rateShared,
            boolean activityShared,
            boolean notableDropShared)
        {
            this.displayName = normalizeName(displayName);
            this.finalNetGp = netShared ? finalNetGp : 0L;
            this.finalGpPerHour = rateShared ? finalGpPerHour : 0L;
            this.netShared = netShared;
            this.rateShared = rateShared;
            this.activityShared = activityShared;
            this.notableDropShared = notableDropShared;
        }

        public String getDisplayName() { return normalizeName(displayName); }
        public long getFinalNetGp() { return netShared ? finalNetGp : 0L; }
        public long getFinalGpPerHour() { return rateShared ? finalGpPerHour : 0L; }
        public boolean isNetShared() { return netShared; }
        public boolean isRateShared() { return rateShared; }
        public boolean isActivityShared() { return activityShared; }
        public boolean isNotableDropShared() { return notableDropShared; }

        private Member copy()
        {
            return new Member(displayName, finalNetGp, finalGpPerHour,
                netShared, rateShared, activityShared, notableDropShared);
        }

        private static String normalizeName(String value)
        {
            return value == null || value.trim().isEmpty() ? "Party member" : value.trim();
        }
    }
}
