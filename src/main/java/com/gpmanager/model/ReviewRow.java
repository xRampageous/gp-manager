package com.gpmanager;
import java.util.*;
import static com.gpmanager.SafeMath.nonNeg;
import static com.gpmanager.ModelText.*;
/** Detached, read-only summary of one transaction awaiting an owner decision. */
class ReviewRow {
final String transactionId;
final String sessionId;
final long timestampEpochMillis;
final long ageMillis;
final List<Item> items;
final long value;
final String why;
final Set<ReviewDecision> validDecisions;
ReviewRow(String transactionId, String sessionId, long timestampEpochMillis,
long ageMillis, List<Item> items, long value, String why, Set<ReviewDecision> validDecisions) {
 this.transactionId = orEmpty(transactionId);
 this.sessionId = orEmpty(sessionId);
 this.timestampEpochMillis = nonNeg(timestampEpochMillis);
 this.ageMillis = nonNeg(ageMillis);
 this.items = Collections.unmodifiableList(items == null ? new ArrayList<Item>() : new ArrayList<>(items));
 this.value = value;
 this.why = orEmpty(why);
 EnumSet<ReviewDecision> copy = validDecisions == null || validDecisions.isEmpty()
 ? EnumSet.noneOf(ReviewDecision.class) : EnumSet.copyOf(validDecisions);
 this.validDecisions = Collections.unmodifiableSet(copy);
}

/** Detached item summary; value is the signed flow value for this item. */
static class Item {
 final int itemId;
 final String name;
 final long quantityDelta;
 final long valueDelta;
 Item(int itemId, String name, long quantityDelta, long valueDelta) {
  this.itemId = itemId;
  this.name = orEmpty(name);
  this.quantityDelta = quantityDelta;
  this.valueDelta = valueDelta;
 }
}
}
