package com.gpmanager;

import static com.gpmanager.Phrases.*;
import static com.gpmanager.SafeMath.nonNeg;

import java.util.*;

/** Detached, read-only summary of one transaction awaiting an owner decision. */
class ReviewRow {
    String transactionId;
    String sessionId;
    long timestampEpochMillis;
    long ageMillis;
    List<Item> items;
    long value;
    String why;
    Set<Decision> validDecisions;

    ReviewRow(
            String transactionId,
            String sessionId,
            long timestampEpochMillis,
            long ageMillis,
            List<Item> items,
            long value,
            String why,
            Set<Decision> validDecisions) {
        this.transactionId = orEmpty(transactionId);
        this.sessionId = orEmpty(sessionId);
        this.timestampEpochMillis = nonNeg(timestampEpochMillis);
        this.ageMillis = nonNeg(ageMillis);
        this.items =
                Collections.unmodifiableList(
                        items == null ? new ArrayList<Item>() : new ArrayList<>(items));
        this.value = value;
        this.why = orEmpty(why);
        EnumSet<Decision> copy =
                validDecisions == null || validDecisions.isEmpty()
                        ? EnumSet.noneOf(Decision.class)
                        : EnumSet.copyOf(validDecisions);
        this.validDecisions = Collections.unmodifiableSet(copy);
    }

    /** Detached item summary; value is the signed flow value for this item. */
    static class Item {
        int itemId;
        String name;
        long quantityDelta;
        long valueDelta;

        Item(int itemId, String name, long quantityDelta, long valueDelta) {
            this.itemId = itemId;
            this.name = orEmpty(name);
            this.quantityDelta = quantityDelta;
            this.valueDelta = valueDelta;
        }
    }
}
