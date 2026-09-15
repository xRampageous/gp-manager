package com.gpmanager.ui.bento;

import com.gpmanager.engine.GeSellTaxBooking;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.Run;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import com.gpmanager.ui.ledger.LedgerItemContribution;
import com.gpmanager.ui.ledger.LedgerItemGrouping;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The Ledger page's read model (SIDEBAR_BENTO.md §4): receipts in scope, classified into
 * sections by how the money moved, grouped by item. Pure data built by {@link #capture}.
 */
public final class LedgerSnapshot
{
    public enum Scope
    {
        SESSION("This session"), TODAY("Today");

        public final String label;

        Scope(String label)
        {
            this.label = label;
        }
    }

    /** Section order is display order. */
    public enum Section
    {
        GAINS("Gains"), MARKET("Market"), SUPPLIES("Supplies"), LOSSES("Losses"), NEUTRAL("Neutral"), CLAIMS("Claims");

        public final String label;

        Section(String label)
        {
            this.label = label;
        }
    }

    /** One receipt line inside an item group. */
    public static final class Receipt
    {
        public final String transactionId;
        public final String sessionId;
        public final long timestamp;
        public final long quantity;
        public final long value;
        public final boolean counted;
        public final String priceSource;
        public final long priceCapturedAt;
        public final String verb;
        public final String why;
        public final String confidence;
        public final TransactionCorrection correction;
        public final boolean review;

        Receipt(String transactionId, String sessionId, long timestamp, long quantity, long value, boolean counted,
            String priceSource, long priceCapturedAt, String verb, String why, String confidence,
            TransactionCorrection correction, boolean review)
        {
            this.transactionId = transactionId;
            this.sessionId = sessionId;
            this.timestamp = timestamp;
            this.quantity = quantity;
            this.value = value;
            this.counted = counted;
            this.priceSource = priceSource;
            this.priceCapturedAt = priceCapturedAt;
            this.verb = verb;
            this.why = why;
            this.confidence = confidence;
            this.correction = correction;
            this.review = review;
        }
    }

    /** One row: an item within a section. */
    public static final class Row
    {
        public final Section section;
        public final int itemId;
        public final String name;
        public final long quantity;
        public final long value;
        public final boolean counted;
        public final String verb;
        @Nullable
        public final String tag;
        public final List<Receipt> receipts;

        Row(Section section, int itemId, String name, long quantity, long value, boolean counted, String verb,
            @Nullable String tag, List<Receipt> receipts)
        {
            this.section = section;
            this.itemId = itemId;
            this.name = name;
            this.quantity = quantity;
            this.value = value;
            this.counted = counted;
            this.verb = verb;
            this.tag = tag;
            this.receipts = receipts;
        }
    }

    public final Scope scope;
    public final Map<Section, List<Row>> rows;
    public final Map<Section, Long> subtotals;
    public final int receiptCount;
    public final int itemCount;
    public final long gains;
    public final long loss;
    public final int reviewCount;

    private LedgerSnapshot(Scope scope, Map<Section, List<Row>> rows, Map<Section, Long> subtotals, int receiptCount,
        int itemCount, long gains, long loss, int reviewCount)
    {
        this.scope = scope;
        this.rows = rows;
        this.subtotals = subtotals;
        this.receiptCount = receiptCount;
        this.itemCount = itemCount;
        this.gains = gains;
        this.loss = loss;
        this.reviewCount = reviewCount;
    }

    public static LedgerSnapshot capture(GpManagerEngine engine, Scope scope, long now, @Nullable String search)
    {
        List<ProfitTransaction> transactions = new ArrayList<>();
        ProfitSession active = engine.getActiveSession();
        switch (scope)
        {
            case TODAY:
                LocalDate today = LocalDate.now();
                for (ProfitSession s : engine.getHistory())
                {
                    if (s != null && sameDay(s.getStartedAtEpochMillis(), today))
                    {
                        transactions.addAll(s.getTransactions());
                    }
                }
                if (active != null && !containsSession(engine.getHistory(), active))
                {
                    transactions.addAll(active.getTransactions());
                }
                break;
            default:
                if (active != null)
                {
                    transactions.addAll(active.getTransactions());
                }
        }
        return build(scope, transactions, search);
    }

    /** A retained (history) session by id; scope reads as SESSION. */
    public static LedgerSnapshot captureSession(GpManagerEngine engine, String sessionId, @Nullable String search)
    {
        ProfitSession session = engine.getHistorySession(sessionId);
        List<ProfitTransaction> transactions = session == null ? Collections.emptyList() : new ArrayList<>(session.getTransactions());
        return build(Scope.SESSION, transactions, search);
    }

    static LedgerSnapshot build(Scope scope, List<ProfitTransaction> transactions, @Nullable String search)
    {
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<LedgerItemContribution> contributions = LedgerItemGrouping.extractContributions(transactions, true);
        Map<String, ProfitTransaction> byId = new LinkedHashMap<>();
        for (ProfitTransaction t : transactions)
        {
            if (t != null)
            {
                byId.put(t.getId(), t);
            }
        }

        Map<Section, Map<String, List<LedgerItemContribution>>> grouped = new EnumMap<>(Section.class);
        Map<Section, Map<String, ProfitTransaction>> leadTx = new EnumMap<>(Section.class);
        int receiptCount = 0;
        int review = 0;
        for (LedgerItemContribution c : contributions)
        {
            ProfitTransaction t = byId.get(c.getTransactionId());
            if (t == null)
            {
                continue;
            }
            if (!needle.isEmpty() && !c.getItemName().toLowerCase(Locale.ROOT).contains(needle))
            {
                continue;
            }
            Section section = classify(t, c);
            grouped.computeIfAbsent(section, k -> new LinkedHashMap<>())
                .computeIfAbsent(c.groupKey(), k -> new ArrayList<>()).add(c);
            leadTx.computeIfAbsent(section, k -> new LinkedHashMap<>()).putIfAbsent(c.groupKey(), t);
            receiptCount++;
            if (c.isNeedsReview())
            {
                review++;
            }
        }

        Map<Section, List<Row>> rows = new EnumMap<>(Section.class);
        Map<Section, Long> subtotals = new EnumMap<>(Section.class);
        int items = 0;
        long gains = 0L;
        long loss = 0L;
        for (Section section : Section.values())
        {
            Map<String, List<LedgerItemContribution>> groups = grouped.get(section);
            if (groups == null)
            {
                continue;
            }
            List<Row> list = new ArrayList<>();
            long subtotal = 0L;
            for (Map.Entry<String, List<LedgerItemContribution>> e : groups.entrySet())
            {
                List<LedgerItemContribution> cs = e.getValue();
                LedgerItemContribution first = cs.get(0);
                long qty = 0L;
                long value = 0L;
                boolean counted = false;
                String tag = null;
                List<Receipt> receipts = new ArrayList<>();
                for (LedgerItemContribution c : cs)
                {
                    ProfitTransaction t = byId.get(c.getTransactionId());
                    qty += c.getQuantityDelta();
                    if (c.isCounted())
                    {
                        value += c.getValueDelta();
                        counted = true;
                    }
                    if (c.isNeedsReview())
                    {
                        tag = "review";
                    }
                    ActionKind kind = t == null ? null : t.getActionKind();
                    if (tag == null && kind != null && kind.isRoutineRepetitive())
                    {
                        tag = "quiet";
                    }
                    if (tag == null && t != null && t.getCorrection() == TransactionCorrection.IGNORE)
                    {
                        tag = "ignored";
                    }
                    receipts.add(new Receipt(
                        c.getTransactionId(),
                        t == null ? "" : sessionIdOf(t),
                        c.getTimestampEpochMillis(),
                        c.getQuantityDelta(),
                        c.getValueDelta(),
                        c.isCounted(),
                        c.getPriceSource() == null ? "" : c.getPriceSource().toString(),
                        t == null ? 0L : priceCapturedAt(t, c.getItemId()),
                        verbFor(t, c),
                        c.whyCountedSummary(),
                        t == null || t.getConfidence() == null ? "" : t.getConfidence().name().toLowerCase(Locale.ROOT),
                        c.getCorrection(),
                        c.isNeedsReview()));
                }
                if (section == Section.CLAIMS)
                {
                    tag = "claim";
                }
                receipts.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                String verb = receipts.isEmpty() ? "" : receipts.get(0).verb;
                list.add(new Row(section, first.getItemId(), first.getItemName(), qty, value, counted, verb, tag, receipts));
                subtotal += value;
                items++;
            }
            list.sort((a, b) -> Long.compare(Math.abs(b.value), Math.abs(a.value)));
            rows.put(section, Collections.unmodifiableList(list));
            subtotals.put(section, subtotal);
            if (section == Section.GAINS || section == Section.MARKET)
            {
                if (subtotal > 0L)
                {
                    gains += subtotal;
                }
                else
                {
                    loss += -subtotal;
                }
            }
            else if (section == Section.SUPPLIES || section == Section.LOSSES)
            {
                loss += -subtotal;
            }
        }
        return new LedgerSnapshot(scope, rows, subtotals, receiptCount, items, gains, loss, review);
    }

    static Section classify(ProfitTransaction t, LedgerItemContribution c)
    {
        if (t.hasPendingDeferredClaim() || t.getActionKind() == ActionKind.DEFERRED_CLAIM)
        {
            return Section.CLAIMS;
        }
        TransactionType type = t.getType();
        if (t.getCorrection() == TransactionCorrection.IGNORE)
        {
            return Section.NEUTRAL;
        }
        // A counted death fee or tax is a loss even when it was booked on a transfer-shaped row.
        boolean deathRow = type == TransactionType.PK_DEATH_LOSS || t.getNote().toLowerCase(Locale.ROOT).startsWith("death");
        if (t.isCounted() && (deathRow || LedgerItemGrouping.matchesTaxFilter(c) || c.getItemId() == GeSellTaxBooking.TAX_ITEM_ID))
        {
            return Section.LOSSES;
        }
        if (type == TransactionType.TRANSFER)
        {
            return Section.NEUTRAL;
        }
        if (deathRow)
        {
            return Section.LOSSES;
        }
        if (type == TransactionType.TRADE)
        {
            return Section.MARKET;
        }
        if (c.getValueDelta() >= 0L && c.getQuantityDelta() >= 0L)
        {
            return Section.GAINS;
        }
        ActionKind kind = t.getActionKind();
        // Same rule as CostKind: only evidenced consumption is a supply; an unexplained decrease is a loss.
        if (type == TransactionType.PK_SUPPLY_COST || type == TransactionType.PROCESSING || kind != null)
        {
            return Section.SUPPLIES;
        }
        return Section.LOSSES;
    }

    static String verbFor(@Nullable ProfitTransaction t, LedgerItemContribution c)
    {
        if (t != null && t.getActionKind() != null)
        {
            return t.getActionKind().completedVerb().toLowerCase(Locale.ROOT);
        }
        if (t != null && t.getType() == TransactionType.TRANSFER)
        {
            return "transfer";
        }
        if (t != null && t.getType() == TransactionType.TRADE)
        {
            return c.getQuantityDelta() < 0L ? "sold" : "bought";
        }
        return c.getQuantityDelta() >= 0L ? "received" : "used";
    }

    private static long priceCapturedAt(ProfitTransaction t, int itemId)
    {
        for (com.gpmanager.model.ItemFlow f : t.getFlows())
        {
            if (f != null && f.getItemId() == itemId)
            {
                return f.getPriceCapturedAtEpochMillis();
            }
        }
        return 0L;
    }

    private static String sessionIdOf(ProfitTransaction t)
    {
        return "";
    }

    private static boolean sameDay(long epochMillis, LocalDate day)
    {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().equals(day);
    }

    private static boolean containsSession(List<ProfitSession> sessions, ProfitSession session)
    {
        for (ProfitSession s : sessions)
        {
            if (s == session || (s != null && s.getId().equals(session.getId())))
            {
                return true;
            }
        }
        return false;
    }

    public List<Row> rows(Section section)
    {
        List<Row> list = rows.get(section);
        return list == null ? Collections.emptyList() : list;
    }

    public long subtotal(Section section)
    {
        Long v = subtotals.get(section);
        return v == null ? 0L : v;
    }
}
