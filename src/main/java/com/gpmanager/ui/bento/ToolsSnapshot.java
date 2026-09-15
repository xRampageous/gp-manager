package com.gpmanager.ui.bento;

import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.CorrectionRecord;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.PartyProfitSummary;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TileLayout;
import com.gpmanager.model.WealthLocationsSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Tools page read model (SIDEBAR_BENTO.md §7): pending decisions, applied corrections,
 * wealth locate, party, layout, data and storage figures, item rules and settings. Every
 * figure is read here; the page only paints and the panel only acts.
 */
public final class ToolsSnapshot
{
    /** A receipt waiting for the owner's decision. */
    public static final class Decision
    {
        public final String transactionId;
        public final int itemId;
        public final String name;
        public final long value;
        public final String why;
        public final long at;

        Decision(String transactionId, int itemId, String name, long value, String why, long at)
        {
            this.transactionId = transactionId;
            this.itemId = itemId;
            this.name = name;
            this.value = value;
            this.why = why;
            this.at = at;
        }
    }

    public static final class Applied
    {
        public final String transactionId;
        public final String change;
        public final long at;

        Applied(String transactionId, String change, long at)
        {
            this.transactionId = transactionId;
            this.change = change;
            this.at = at;
        }
    }

    public static final class Storage
    {
        public final int sessions;
        public final long receipts;
        public final long compacted;
        public final int corrections;
        /** Engine estimate of the saved profile (approximate JSON bytes). */
        public final long approximateBytes;
        public final int retentionDays;
        @Nullable
        public final Long nextCompactionAt;
        public final int pendingCompaction;

        Storage(int sessions, long receipts, long compacted, int corrections, long approximateBytes, int retentionDays,
            @Nullable Long nextCompactionAt, int pendingCompaction)
        {
            this.sessions = sessions;
            this.receipts = receipts;
            this.compacted = compacted;
            this.corrections = corrections;
            this.approximateBytes = approximateBytes;
            this.retentionDays = retentionDays;
            this.nextCompactionAt = nextCompactionAt;
            this.pendingCompaction = pendingCompaction;
        }
    }

    /** Tools › Wealth summary: change since an anchor, from the engine's wealth history. */
    public static final class WealthChange
    {
        public final String label;
        public final boolean available;
        public final long earned;
        public final long market;
        public final long unexplained;

        WealthChange(String label, com.gpmanager.model.WealthChangeBreakdown b)
        {
            this.label = label;
            this.available = b != null && b.isAvailable();
            this.earned = b == null ? 0L : b.getEarnedGp();
            this.market = b == null ? 0L : b.getMarketGp();
            this.unexplained = b == null ? 0L : b.getUnexplainedGp();
        }

        public long total()
        {
            return earned + market + unexplained;
        }
    }

    /** Live tiles the owner can hide (fixed tiles are not listed). */
    public static final String[] LIVE_TILES = {"goal", "party", "notices", "recent"};
    public static final String LIVE_PAGE = "live";

    public final List<Decision> decisions;
    public final List<Applied> applied;
    public final boolean canUndo;
    @Nullable
    public final Long wealthTotal;
    public final long wealthCapturedAt;
    public final List<com.gpmanager.ui.WealthLocateModel.Slot> locate;
    @Nullable
    public final PartyProfitSummary party;
    public final Set<String> hiddenLiveTiles;
    /** Optional Live tiles in the owner's order (Tools › Layout); empty means the default order. */
    public final List<String> liveTileOrder;
    public final Storage storage;
    public final Set<Integer> excludedItems;
    public final Map<Integer, Integer> priceOverrides;
    public final int historySessions;
    /** Latest wealth capture from history (null when none), and the three anchored changes. */
    @Nullable
    public final Long historyWealthTotal;
    public final long historyWealthAgeMillis;
    public final List<WealthChange> wealthChanges;

    private ToolsSnapshot(List<Decision> decisions, List<Applied> applied, boolean canUndo, @Nullable Long wealthTotal,
        long wealthCapturedAt, List<com.gpmanager.ui.WealthLocateModel.Slot> locate, @Nullable PartyProfitSummary party,
        Set<String> hiddenLiveTiles, List<String> liveTileOrder, Storage storage, Set<Integer> excludedItems, Map<Integer, Integer> priceOverrides,
        int historySessions, @Nullable Long historyWealthTotal, long historyWealthAgeMillis, List<WealthChange> wealthChanges)
    {
        this.historyWealthTotal = historyWealthTotal;
        this.historyWealthAgeMillis = historyWealthAgeMillis;
        this.wealthChanges = wealthChanges;
        this.decisions = decisions;
        this.applied = applied;
        this.canUndo = canUndo;
        this.wealthTotal = wealthTotal;
        this.wealthCapturedAt = wealthCapturedAt;
        this.locate = locate;
        this.party = party;
        this.hiddenLiveTiles = hiddenLiveTiles;
        this.liveTileOrder = liveTileOrder == null ? Collections.emptyList() : liveTileOrder;
        this.storage = storage;
        this.excludedItems = excludedItems;
        this.priceOverrides = priceOverrides;
        this.historySessions = historySessions;
    }

    /** Raw GE offer transitions, newest first (Tools > Diagnostics); empty until an offer moves. */
    public List<com.gpmanager.model.GeOfferObservation> geObservations = Collections.emptyList();
    /** Engine data health (pass 10 step 47) and which backup the load fell back to, if any. */
    @Nullable
    public com.gpmanager.model.DataHealthSnapshot dataHealth;
    public String recoveredFrom = "";

    public static ToolsSnapshot capture(GpManagerEngine engine, long now, @Nullable WealthLocationsSnapshot wealth,
        com.gpmanager.ui.WealthLocateModel locate, @Nullable PartyProfitSummary party, ItemRules rules)
    {
        ProfitSession active = engine.getActiveSession();
        List<Decision> decisions = new ArrayList<>();
        List<Applied> applied = new ArrayList<>();
        boolean canUndo = false;
        if (active != null)
        {
            List<ProfitTransaction> transactions = active.getTransactions();
            for (int i = transactions.size() - 1; i >= 0; i--)
            {
                ProfitTransaction t = transactions.get(i);
                if (t == null || !LiveSnapshot.needsReview(t))
                {
                    continue;
                }
                ItemFlow lead = null;
                long leadAbs = -1L;
                for (ItemFlow f : t.getFlows())
                {
                    if (f != null && Math.abs(f.getValueDelta()) > leadAbs)
                    {
                        leadAbs = Math.abs(f.getValueDelta());
                        lead = f;
                    }
                }
                String name = lead == null ? (t.getNote().isEmpty() ? "Unknown change" : t.getNote()) : lead.getItemName();
                if (lead != null && t.getFlows().size() > 1)
                {
                    name += " +" + (t.getFlows().size() - 1);
                }
                decisions.add(new Decision(t.getId(), lead == null ? -1 : lead.getItemId(), name, t.getNet(),
                    t.getExplanation() == null ? "" : t.getExplanation(), t.getTimestampEpochMillis()));
            }
            List<CorrectionRecord> history = active.getCorrectionHistory();
            canUndo = !history.isEmpty();
            for (int i = history.size() - 1; i >= 0 && applied.size() < 5; i--)
            {
                CorrectionRecord r = history.get(i);
                String change = r.getPreviousCorrection().name().toLowerCase() + " → " + r.getNewCorrection().name().toLowerCase();
                applied.add(new Applied(r.getTransactionId(), change, r.getTimestampEpochMillis()));
            }
        }

        Long wealthTotal = null;
        long capturedAt = 0L;
        if (wealth != null)
        {
            long total = 0L;
            boolean any = false;
            for (com.gpmanager.model.WealthLocationSnapshot l : wealth.getLocations())
            {
                if (l.getStatus() == com.gpmanager.model.WealthLocationSnapshot.Status.AVAILABLE)
                {
                    total += l.getValueGp();
                    any = true;
                }
            }
            wealthTotal = any ? total : null;
            capturedAt = wealth.getCapturedAtEpochMillis();
        }

        int sessions = 0;
        for (ProfitSession s : engine.getHistory())
        {
            if (s != null && !SessionsSnapshot.isFreePlay(s))
            {
                sessions++;
            }
        }
        if (active != null && engine.isCustomSessionActive())
        {
            sessions++;
        }
        long receipts = 0L;
        long compacted = 0L;
        int corrections = 0;
        for (ProfitSession s : engine.getHistory())
        {
            if (s == null)
            {
                continue;
            }
            receipts += s.getTransactions().size();
            compacted += s.getCompactedTransactionCount();
            corrections += s.getCorrectionLog().size();
        }
        if (active != null)
        {
            receipts += active.getTransactions().size();
            compacted += active.getCompactedTransactionCount();
            corrections += active.getCorrectionLog().size();
        }

        com.gpmanager.model.ProfileSizeEstimate size = engine.getProfileSizeEstimate();
        com.gpmanager.model.ReceiptRetentionStatus retention = engine.getRetentionStatus();
        com.gpmanager.model.LatestWealthSnapshot latest = engine.getLatestWealth(now);
        Long historyTotal = null;
        if (latest != null && latest.isAvailable())
        {
            long t = 0L;
            for (com.gpmanager.model.WealthSnapshotHistory.Location l : latest.getSnapshot().getLocations())
            {
                if (l.getStatus() == com.gpmanager.model.WealthLocationSnapshot.Status.AVAILABLE)
                {
                    t += l.getValueGp();
                }
            }
            historyTotal = t;
        }
        List<WealthChange> changes = new ArrayList<>();
        changes.add(new WealthChange("Since last bank visit", engine.getWealthChangeSince(com.gpmanager.model.WealthAnchor.LAST_BANK_VISIT, now)));
        changes.add(new WealthChange("Today", engine.getWealthChangeSince(com.gpmanager.model.WealthAnchor.TODAY, now)));
        changes.add(new WealthChange("30 days", engine.getWealthChangeSince(com.gpmanager.model.WealthAnchor.THIRTY_DAYS, now)));

        TileLayout layout = engine.getTileLayout();
        Set<String> hidden = layout == null ? Collections.emptySet() : layout.getHiddenTileIds(LIVE_PAGE);
        List<String> order = liveTileOrder(layout);

        ToolsSnapshot snapshot = new ToolsSnapshot(Collections.unmodifiableList(decisions), Collections.unmodifiableList(applied), canUndo,
            wealthTotal, capturedAt, locate == null ? Collections.emptyList() : locate.slots(), party,
            Collections.unmodifiableSet(new java.util.LinkedHashSet<>(hidden)), order,
            new Storage(sessions, receipts, compacted, corrections, size == null ? 0L : size.getApproximateJsonBytes(),
                retention == null ? 0 : retention.getWindowDays(), retention == null ? null : retention.getNextCompactionAtEpochMillis(),
                retention == null ? 0 : retention.getPendingCount()),
            rules == null ? Collections.emptySet() : rules.excluded(),
            rules == null ? Collections.emptyMap() : rules.overrides(),
            engine.getHistory().size(), historyTotal, latest == null || !latest.isAvailable() ? 0L : latest.getAgeMillis(),
            Collections.unmodifiableList(changes));
        snapshot.geObservations = engine.getRecentGeOfferObservations(5);
        snapshot.dataHealth = engine.getDataHealth(now);
        return snapshot;
    }

    /** The optional Live tiles in the saved order, unknown ids dropped and missing ones appended in default order. */
    public static List<String> liveTileOrder(@Nullable TileLayout layout)
    {
        List<String> order = new ArrayList<>();
        if (layout != null)
        {
            for (String id : layout.getOrderedTileIds(LIVE_PAGE))
            {
                for (String known : LIVE_TILES)
                {
                    if (known.equals(id) && !order.contains(id))
                    {
                        order.add(id);
                    }
                }
            }
        }
        for (String known : LIVE_TILES)
        {
            if (!order.contains(known))
            {
                order.add(known);
            }
        }
        return Collections.unmodifiableList(order);
    }

    /** Even split of the party's counted net: what each member owes or is owed against the mean. */
    public static List<long[]> evenSplit(PartyProfitSummary party)
    {
        List<long[]> rows = new ArrayList<>();
        if (party == null)
        {
            return rows;
        }
        List<PartyProfitSummary.Member> reporting = new ArrayList<>();
        long total = 0L;
        for (PartyProfitSummary.Member m : party.getMembers())
        {
            if (m.isReporting())
            {
                reporting.add(m);
                total += m.getNet();
            }
        }
        if (reporting.isEmpty())
        {
            return rows;
        }
        long share = total / reporting.size();
        for (int i = 0; i < reporting.size(); i++)
        {
            // [index, net, share, delta]  delta > 0 → owes the pool, < 0 → is owed
            rows.add(new long[] {i, reporting.get(i).getNet(), share, reporting.get(i).getNet() - share});
        }
        return rows;
    }
}
