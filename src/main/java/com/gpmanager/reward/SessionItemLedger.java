package com.gpmanager.reward;

import com.gpmanager.LootPresentationFilter;
import com.gpmanager.grounditems.LootPresentationFilterService;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TransactionType;
import com.gpmanager.ui.SessionOwnerLabels;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Singleton;

/**
 * Presentation-only Session Capsule for the HUD+ satellite folio (Gained / Spent).
 * Bound to the active {@link ProfitSession} — never mutates Net or ledger accounting.
 */
@Singleton
public final class SessionItemLedger
{
    private final Map<Integer, RewardItem> gains = new LinkedHashMap<>();
    private final Map<Integer, RewardItem> losses = new LinkedHashMap<>();
    @Nullable
    private String boundSessionId;
    @Nullable
    private String boundSessionName;

    public synchronized void clear()
    {
        gains.clear();
        losses.clear();
        boundSessionId = null;
        boundSessionName = null;
    }

    /**
     * Bind the capsule to {@code session}: clear stacks and rebuild from that
     * session's counted transactions. Null clears the capsule.
     */
    public synchronized void bindToSession(@Nullable ProfitSession session)
    {
        gains.clear();
        losses.clear();
        if (session == null)
        {
            boundSessionId = null;
            boundSessionName = null;
            return;
        }
        boundSessionId = session.getId();
        boundSessionName = session.getName() == null || session.getName().trim().isEmpty()
            ? SessionOwnerLabels.DURABLE_OWNER_NAME
            : session.getName().trim();
        if (SessionOwnerLabels.isDurableOwnerName(boundSessionName))
        {
            boundSessionName = SessionOwnerLabels.DURABLE_OWNER_NAME;
        }
        List<ProfitTransaction> txs = session.getTransactions();
        if (txs == null)
        {
            return;
        }
        for (ProfitTransaction tx : txs)
        {
            if (tx != null && tx.isCounted())
            {
                record(tx);
            }
        }
    }

    /** Updates the folio title after rename without rebuilding stacks. */
    public synchronized void renameBoundSession(@Nullable String name)
    {
        if (boundSessionId == null)
        {
            return;
        }
        boundSessionName = name == null || name.trim().isEmpty()
            ? SessionOwnerLabels.DURABLE_OWNER_NAME
            : name.trim();
        if (SessionOwnerLabels.isDurableOwnerName(boundSessionName))
        {
            boundSessionName = SessionOwnerLabels.DURABLE_OWNER_NAME;
        }
    }

    @Nullable
    public synchronized String getBoundSessionId()
    {
        return boundSessionId;
    }

    @Nullable
    public synchronized String getBoundSessionName()
    {
        return boundSessionName;
    }

    /**
     * Records counted acquisition flows from a settled transaction.
     * Skips TRANSFER / TRADE ownership moves and corrections.
     */
    public synchronized void record(ProfitTransaction transaction)
    {
        if (transaction == null || transaction.getFlows() == null)
        {
            return;
        }
        if (!transaction.isCounted())
        {
            return;
        }
        TransactionType type = transaction.getType();
        if (type == TransactionType.TRANSFER || type == TransactionType.TRADE)
        {
            return;
        }
        if (transaction.getCorrection() != null
            && transaction.getCorrection() != com.gpmanager.model.TransactionCorrection.AUTO)
        {
            return;
        }
        // B10 surface policy: routine rune/ammo events are Ledger detail only — the HUD+
        // capsule never lists them. Net and aggregate metrics still include them.
        if (!ActionPresentation.showsOn(transaction, ActionPresentation.Surface.HUD_PLUS_CAPSULE))
        {
            return;
        }
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null)
            {
                continue;
            }
            long qty = flow.getQuantityDelta();
            long value = flow.getValueDelta();
            if (qty == 0L && value == 0L)
            {
                continue;
            }
            boolean known = !(flow.getPriceSource() == ItemPriceSource.UNKNOWN && value == 0L)
                && flow.getPriceSource() != ItemPriceSource.UNPRICED;
            if (qty > 0L || (qty == 0L && value > 0L))
            {
                mergeInto(gains, new RewardItem(
                    flow.getItemId(),
                    flow.getItemName(),
                    qty == 0L ? 1L : qty,
                    known ? Math.abs(value) : 0L,
                    known,
                    flow.getPriceSource()));
            }
            else if (qty < 0L || value < 0L)
            {
                long shownQty = qty < 0L ? qty : -1L;
                long shownValue = known ? (value <= 0L ? value : -Math.abs(value)) : 0L;
                mergeInto(losses, new RewardItem(
                    flow.getItemId(),
                    flow.getItemName(),
                    shownQty,
                    shownValue,
                    known,
                    flow.getPriceSource()));
            }
        }
    }

    public synchronized SessionLedgerSnapshot snapshot(
        long minimumDisplayedLootValue,
        int maxGainRows,
        int maxLossRows)
    {
        return snapshot(minimumDisplayedLootValue, maxGainRows, maxLossRows, null, LootPresentationFilter.ALL_ITEMS);
    }

    /**
     * Folio view. Display rules and the minimum affect gain rows only; explicit
     * accounting exclusions can also remove costs. Presentation-only hiding
     * never changes the folio's accounting totals.
     */
    public synchronized SessionLedgerSnapshot snapshot(
        long minimumDisplayedLootValue,
        int maxGainRows,
        int maxLossRows,
        @Nullable LootPresentationFilterService filterService,
        @Nullable LootPresentationFilter mode)
    {
        return snapshot(minimumDisplayedLootValue, maxGainRows, maxLossRows,
            filterService, mode, LootPresentationFilter.ALL_ITEMS);
    }

    public synchronized SessionLedgerSnapshot snapshot(
        long minimumDisplayedLootValue,
        int maxGainRows,
        int maxLossRows,
        @Nullable LootPresentationFilterService filterService,
        @Nullable LootPresentationFilter displayMode,
        @Nullable LootPresentationFilter accountingMode)
    {
        LootPresentationFilter effective = displayMode == null
            ? LootPresentationFilter.ALL_ITEMS : displayMode;
        LootPresentationFilter accounting = accountingMode == null
            ? LootPresentationFilter.ALL_ITEMS : accountingMode;
        List<RewardItem> allGains = sortedByAbsValue(gains);
        List<RewardItem> allLosses = sortedByAbsValue(losses);
        List<RewardItem> visibleGains = new ArrayList<>();
        List<RewardItem> visibleLosses = new ArrayList<>();
        int hidden = 0;
        for (RewardItem item : allGains)
        {
            if (item == null)
            {
                continue;
            }
            if (filterService != null && !isAccountingIncluded(filterService, item, accounting))
            {
                hidden++;
                continue;
            }
            if (minimumDisplayedLootValue > 0L
                && item.isValueKnown()
                && !item.isLoss())
            {
                long qty = Math.max(1L, Math.abs(item.getQuantity()));
                long unit = Math.abs(item.getRecordedValue()) / qty;
                if (unit > 0L && unit < minimumDisplayedLootValue)
                {
                    hidden++;
                    continue;
                }
            }
            if (filterService != null && !filterService.isRewardItemIncluded(item, effective))
            {
                hidden++;
                continue;
            }
            if (visibleGains.size() < Math.max(0, maxGainRows))
            {
                visibleGains.add(item);
            }
            else
            {
                hidden++;
            }
        }
        for (RewardItem item : allLosses)
        {
            if (item == null)
            {
                continue;
            }
            // Costs bypass display filtering and the minimum, but can be
            // hidden by the user's explicit accounting exclusions.
            if (filterService != null && !isAccountingIncluded(filterService, item, accounting))
            {
                hidden++;
                continue;
            }
            if (visibleLosses.size() < Math.max(0, maxLossRows))
            {
                visibleLosses.add(item);
            }
            else
            {
                hidden++;
            }
        }
        long gainTotal = 0L;
        long lossTotal = 0L;
        boolean anyGain = false;
        boolean anyLoss = false;
        for (RewardItem item : allGains)
        {
            if (item != null && item.isValueKnown()
                && (filterService == null || isAccountingIncluded(filterService, item, accounting)))
            {
                gainTotal = safeAdd(gainTotal, Math.abs(item.getRecordedValue()));
                anyGain = true;
            }
        }
        for (RewardItem item : allLosses)
        {
            if (item != null && item.isValueKnown()
                && (filterService == null || isAccountingIncluded(filterService, item, accounting)))
            {
                lossTotal = safeAdd(lossTotal, Math.abs(item.getRecordedValue()));
                anyLoss = true;
            }
        }
        long net = anyGain || anyLoss ? safeAdd(gainTotal, -lossTotal) : 0L;
        String title = SessionOwnerLabels.folioTitle(
            boundSessionName,
            !SessionOwnerLabels.isDurableOwnerName(boundSessionName));
        return new SessionLedgerSnapshot(
            title,
            visibleGains,
            visibleLosses,
            hidden,
            gainTotal,
            lossTotal,
            net,
            allGains.isEmpty() && allLosses.isEmpty());
    }

    private static boolean isAccountingIncluded(
        LootPresentationFilterService filterService,
        RewardItem item,
        LootPresentationFilter accountingMode)
    {
        return filterService.isFlowIncluded(toFlow(item), accountingMode);
    }

    private static ItemFlow toFlow(RewardItem item)
    {
        long quantity = item.getQuantity();
        long absQuantity = quantity == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(quantity);
        long value = item.getRecordedValue();
        long absValue = value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
        long unitValue = absValue / Math.max(1L, absQuantity);
        int unitPrice = unitValue > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) unitValue;
        return new ItemFlow(item.getItemId(), item.getItemName(), quantity, unitPrice, value,
            item.getPriceSource());
    }

    private static void mergeInto(Map<Integer, RewardItem> map, RewardItem incoming)
    {
        if (incoming == null)
        {
            return;
        }
        RewardItem existing = map.get(incoming.getItemId());
        map.put(incoming.getItemId(), existing == null ? incoming : existing.merge(incoming));
    }

    private static List<RewardItem> sortedByAbsValue(Map<Integer, RewardItem> map)
    {
        List<RewardItem> list = new ArrayList<>(map.values());
        list.sort(Comparator
            .comparingLong((RewardItem i) -> Math.abs(i.getRecordedValue()))
            .reversed()
            .thenComparingInt(RewardItem::getItemId));
        return list;
    }

    private static long safeAdd(long a, long b)
    {
        try
        {
            return Math.addExact(a, b);
        }
        catch (ArithmeticException ex)
        {
            return a > 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** Immutable Session Capsule folio view. */
    public static final class SessionLedgerSnapshot
    {
        private final String sessionName;
        private final List<RewardItem> gains;
        private final List<RewardItem> losses;
        private final int hiddenCount;
        private final long gainTotal;
        private final long lossTotal;
        private final long net;
        private final boolean empty;

        SessionLedgerSnapshot(
            String sessionName,
            List<RewardItem> gains,
            List<RewardItem> losses,
            int hiddenCount,
            long gainTotal,
            long lossTotal,
            long net,
            boolean empty)
        {
            this.sessionName = sessionName == null || sessionName.isEmpty()
                ? SessionOwnerLabels.DURABLE_OWNER_NAME
                : sessionName;
            this.gains = gains;
            this.losses = losses;
            this.hiddenCount = Math.max(0, hiddenCount);
            this.gainTotal = gainTotal;
            this.lossTotal = lossTotal;
            this.net = net;
            this.empty = empty;
        }

        public String getSessionName()
        {
            return sessionName;
        }

        public List<RewardItem> getGains()
        {
            return gains;
        }

        public List<RewardItem> getLosses()
        {
            return losses;
        }

        public int getHiddenCount()
        {
            return hiddenCount;
        }

        public long getGainTotal()
        {
            return gainTotal;
        }

        public long getLossTotal()
        {
            return lossTotal;
        }

        public long getNet()
        {
            return net;
        }

        public boolean isEmpty()
        {
            return empty;
        }
    }
}
