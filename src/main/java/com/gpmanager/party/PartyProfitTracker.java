package com.gpmanager.party;

import com.gpmanager.model.PartyProfitSummary;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.SessionMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;

/** Coordinates the ephemeral party-wide profit snapshots. */
@Singleton
public final class PartyProfitTracker
{
    private static final long SNAPSHOT_INTERVAL_MILLIS = 5_000L;
    private static final long SNAPSHOT_EXPIRY_MILLIS = 15_000L;

    private final PartyService partyService;
    private final WSClient wsClient;
    private final Map<Long, RemoteSnapshot> remoteSnapshots = new HashMap<>();
    private final String generation = UUID.randomUUID().toString();

    private long partyId;
    private long localRevision;
    private long lastBroadcastMillis;
    private LocalSnapshot localSnapshot;

    @Inject
    public PartyProfitTracker(PartyService partyService, WSClient wsClient)
    {
        this.partyService = partyService;
        this.wsClient = wsClient;
    }

    public void registerMessage()
    {
        wsClient.registerMessage(PartyProfitMessage.class);
    }

    public void unregisterMessage()
    {
        synchronized (this)
        {
            if (localSnapshot != null && partyService.isInParty())
            {
                partyService.send(new PartyProfitMessage(
                    generation,
                    ++localRevision,
                    System.currentTimeMillis(),
                    "",
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0));
            }
            clearState();
        }
        wsClient.unregisterMessage(PartyProfitMessage.class);
    }

    public synchronized void updateLocal(
        ProfitSession activeSession,
        SessionMetrics metrics,
        long now,
        boolean enabled)
    {
        if (!enabled)
        {
            clearState();
            return;
        }
        if (!partyService.isInParty())
        {
            clearIfOutsideParty();
            return;
        }

        ensurePartyState();
        String sessionId = activeSession == null ? "" : activeSession.getId();
        SessionMetrics safeMetrics = metrics == null
            ? new SessionMetrics("No session", "General", false, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0, 0)
            : metrics;
        LocalSnapshot next = new LocalSnapshot(
            sessionId,
            safeMetrics.getRevenue(),
            safeMetrics.getCosts(),
            safeMetrics.getNet(),
            safeMetrics.getProfitPerHour(),
            safeMetrics.getRollingProfitPerHour(),
            safeMetrics.getTransactionCount());

        boolean changed = localSnapshot == null || !localSnapshot.sameValues(next);
        localSnapshot = next;
        if (changed || now - lastBroadcastMillis >= SNAPSHOT_INTERVAL_MILLIS)
        {
            broadcast(now);
        }
    }

    public synchronized void onPartyChanged(PartyChanged event)
    {
        partyId = event == null || event.getPartyId() == null ? 0L : event.getPartyId();
        remoteSnapshots.clear();
        lastBroadcastMillis = 0L;
        localSnapshot = null;
    }

    public synchronized void onUserJoin(UserJoin event)
    {
        if (event == null || !partyService.isInParty() || event.getPartyId() != partyService.getPartyId())
        {
            return;
        }
        ensurePartyState();
        if (localSnapshot != null)
        {
            broadcast(System.currentTimeMillis());
        }
    }

    public synchronized void onUserPart(UserPart event)
    {
        if (event != null)
        {
            remoteSnapshots.remove(event.getMemberId());
        }
    }

    public synchronized void onMessage(PartyProfitMessage message, boolean enabled)
    {
        if (!enabled || message == null || !partyService.isInParty())
        {
            return;
        }

        long memberId = message.getMemberId();
        PartyMember member = partyService.getMemberById(memberId);
        if (memberId == 0L || member == null || isLocalMember(memberId))
        {
            return;
        }

        String sessionId = message.getSessionId();
        if (sessionId.isEmpty())
        {
            remoteSnapshots.remove(memberId);
            return;
        }

        RemoteSnapshot previous = remoteSnapshots.get(memberId);
        if (previous != null
            && message.getGeneration().equals(previous.generation)
            && message.getRevision() <= previous.revision)
        {
            return;
        }

        remoteSnapshots.put(memberId, new RemoteSnapshot(
            memberId,
            member.getDisplayName(),
            message.getGeneration(),
            message.getRevision(),
            message.getTimestampEpochMillis(),
            message.getRevenue(),
            message.getCosts(),
            message.getNet(),
            message.getProfitPerHour(),
            message.getRollingProfitPerHour(),
            message.getTransactionCount()));
    }

    public synchronized PartyProfitSummary getSummary(boolean enabled)
    {
        if (partyService == null || !enabled || !partyService.isInParty())
        {
            return PartyProfitSummary.empty();
        }

        ensurePartyState();
        long now = System.currentTimeMillis();
        List<PartyProfitSummary.Member> members = new ArrayList<>();
        Set<Long> currentMemberIds = new HashSet<>();
        for (PartyMember member : partyService.getMembers())
        {
            if (member != null)
            {
                currentMemberIds.add(member.getMemberId());
            }
        }

        long localMemberId = partyService.getLocalMember() == null
            ? 0L
            : partyService.getLocalMember().getMemberId();
        for (PartyMember member : partyService.getMembers())
        {
            if (member == null) continue;
            if (member.getMemberId() == localMemberId)
            {
                String displayName = member.getDisplayName();
                members.add(localSnapshot == null
                    ? PartyProfitSummary.Member.notReporting(displayName == null ? "You" : displayName, true)
                    : localSnapshot.toMember(displayName == null ? "You" : displayName, true, now, member.getAvatar()));
            }
            else
            {
                RemoteSnapshot snapshot = remoteSnapshots.get(member.getMemberId());
                members.add(snapshot == null
                    ? PartyProfitSummary.Member.notReporting(member.getDisplayName(), false)
                    : snapshot.toMember(now, member.getAvatar()));
            }
        }
        members.sort((left, right) -> left.isLocal() == right.isLocal()
            ? left.getDisplayName().compareToIgnoreCase(right.getDisplayName()) : left.isLocal() ? -1 : 1);

        long revenue = 0L;
        long costs = 0L;
        long net = 0L;
        long profitPerHour = 0L;
        long rollingProfitPerHour = 0L;
        for (PartyProfitSummary.Member member : members)
        {
            if (!member.isFreshReport()) continue;
            revenue = safeAdd(revenue, member.getRevenue());
            costs = safeAdd(costs, member.getCosts());
            net = safeAdd(net, member.getNet());
            profitPerHour = safeAdd(profitPerHour, member.getProfitPerHour());
            rollingProfitPerHour = safeAdd(rollingProfitPerHour, member.getRollingProfitPerHour());
        }

        return new PartyProfitSummary(
            true,
            partyService.getMembers().size(),
            revenue,
            costs,
            net,
            profitPerHour,
            rollingProfitPerHour,
            members);
    }

    private void broadcast(long now)
    {
        if (localSnapshot == null || !partyService.isInParty())
        {
            return;
        }
        partyService.send(new PartyProfitMessage(
            generation,
            ++localRevision,
            now,
            localSnapshot.sessionId,
            localSnapshot.revenue,
            localSnapshot.costs,
            localSnapshot.net,
            localSnapshot.profitPerHour,
            localSnapshot.rollingProfitPerHour,
            localSnapshot.transactionCount));
        lastBroadcastMillis = now;
    }

    private void ensurePartyState()
    {
        long currentPartyId = partyService.getPartyId();
        if (partyId != currentPartyId)
        {
            partyId = currentPartyId;
            remoteSnapshots.clear();
            lastBroadcastMillis = 0L;
            localSnapshot = null;
        }
    }

    private void clearIfOutsideParty()
    {
        if (partyService.isInParty())
        {
            return;
        }
        clearState();
    }

    private void clearState()
    {
        partyId = 0L;
        lastBroadcastMillis = 0L;
        localSnapshot = null;
        remoteSnapshots.clear();
    }


    private boolean isLocalMember(long memberId)
    {
        PartyMember local = partyService.getLocalMember();
        return local != null && local.getMemberId() == memberId;
    }

    private static long safeAdd(long left, long right)
    {
        try
        {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException ex)
        {
            return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private static final class LocalSnapshot
    {
        private final String sessionId;
        private final long revenue;
        private final long costs;
        private final long net;
        private final long profitPerHour;
        private final long rollingProfitPerHour;
        private final int transactionCount;

        private LocalSnapshot(
            String sessionId,
            long revenue,
            long costs,
            long net,
            long profitPerHour,
            long rollingProfitPerHour,
            int transactionCount)
        {
            this.sessionId = sessionId == null ? "" : sessionId;
            this.revenue = revenue;
            this.costs = costs;
            this.net = net;
            this.profitPerHour = profitPerHour;
            this.rollingProfitPerHour = rollingProfitPerHour;
            this.transactionCount = Math.max(0, transactionCount);
        }

        private boolean sameValues(LocalSnapshot other)
        {
            return other != null
                && sessionId.equals(other.sessionId)
                && revenue == other.revenue
                && costs == other.costs
                && net == other.net
                && profitPerHour == other.profitPerHour
                && rollingProfitPerHour == other.rollingProfitPerHour
                && transactionCount == other.transactionCount;
        }

        private PartyProfitSummary.Member toMember(String displayName, boolean local, long now, java.awt.image.BufferedImage avatar)
        {
            return new PartyProfitSummary.Member(
                displayName,
                revenue,
                costs,
                net,
                profitPerHour,
                rollingProfitPerHour,
                transactionCount,
                local,
                true,
                true,
                transactionCount > 0,
                now,
                avatar);
        }
    }

    private static final class RemoteSnapshot
    {
        private final long memberId;
        private final String displayName;
        private final String generation;
        private final long revision;
        private final long timestampEpochMillis;
        private final long revenue;
        private final long costs;
        private final long net;
        private final long profitPerHour;
        private final long rollingProfitPerHour;
        private final int transactionCount;

        private RemoteSnapshot(
            long memberId,
            String displayName,
            String generation,
            long revision,
            long timestampEpochMillis,
            long revenue,
            long costs,
            long net,
            long profitPerHour,
            long rollingProfitPerHour,
            int transactionCount)
        {
            this.memberId = memberId;
            this.displayName = displayName;
            this.generation = generation == null ? "" : generation;
            this.revision = revision;
            this.timestampEpochMillis = timestampEpochMillis;
            this.revenue = revenue;
            this.costs = costs;
            this.net = net;
            this.profitPerHour = profitPerHour;
            this.rollingProfitPerHour = rollingProfitPerHour;
            this.transactionCount = Math.max(0, transactionCount);
        }

        private PartyProfitSummary.Member toMember(long now, java.awt.image.BufferedImage avatar)
        {
            return new PartyProfitSummary.Member(
                displayName,
                revenue,
                costs,
                net,
                profitPerHour,
                rollingProfitPerHour,
                transactionCount,
                false,
                true,
                timestampEpochMillis > 0L && now - timestampEpochMillis <= SNAPSHOT_EXPIRY_MILLIS,
                transactionCount > 0,
                timestampEpochMillis,
                avatar);
        }
    }
}
