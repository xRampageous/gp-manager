package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Detached, read-only summary of one transaction awaiting an owner decision. */
public final class ReviewRow
{
    private final String transactionId;
    private final String sessionId;
    private final long timestampEpochMillis;
    private final long ageMillis;
    private final List<Item> items;
    private final long value;
    private final String why;
    private final Set<ReviewDecision> validDecisions;

    public ReviewRow(String transactionId, String sessionId, long timestampEpochMillis,
        long ageMillis, List<Item> items, long value, String why,
        Set<ReviewDecision> validDecisions)
    {
        this.transactionId = transactionId == null ? "" : transactionId;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.timestampEpochMillis = Math.max(0L, timestampEpochMillis);
        this.ageMillis = Math.max(0L, ageMillis);
        this.items = Collections.unmodifiableList(items == null
            ? new ArrayList<Item>() : new ArrayList<>(items));
        this.value = value;
        this.why = why == null ? "" : why;
        EnumSet<ReviewDecision> copy = validDecisions == null || validDecisions.isEmpty()
            ? EnumSet.noneOf(ReviewDecision.class) : EnumSet.copyOf(validDecisions);
        this.validDecisions = Collections.unmodifiableSet(copy);
    }

    public String getTransactionId() { return transactionId; }
    public String getSessionId() { return sessionId; }
    public long getTimestampEpochMillis() { return timestampEpochMillis; }
    public long getAgeMillis() { return ageMillis; }
    public List<Item> getItems() { return items; }
    public long getValue() { return value; }
    public String getWhy() { return why; }
    public Set<ReviewDecision> getValidDecisions() { return validDecisions; }

    /** Detached item summary; value is the signed flow value for this item. */
    public static final class Item
    {
        private final int itemId;
        private final String name;
        private final long quantityDelta;
        private final long valueDelta;

        public Item(int itemId, String name, long quantityDelta, long valueDelta)
        {
            this.itemId = itemId;
            this.name = name == null ? "" : name;
            this.quantityDelta = quantityDelta;
            this.valueDelta = valueDelta;
        }

        public int getItemId() { return itemId; }
        public String getName() { return name; }
        public long getQuantityDelta() { return quantityDelta; }
        public long getValueDelta() { return valueDelta; }
    }
}
