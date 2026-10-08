package com.gpmanager;
import java.util.*;
import static com.gpmanager.Ae.nonNeg;
import static com.gpmanager.Ag.*;
/** Detached, read-only summary of one transaction awaiting an owner decision. */
class Cu {
String transactionId;
String sessionId;
long timestampEpochMillis;
long ageMillis;
List<Item> items;
long value;
String why;
Set<Cl> validDecisions;
Cu(String /* transactionId */a, String /* sessionId */b, long /* timestampEpochMillis */c,
long /* ageMillis */d, List<Item> /* items */e, long /* value */f, String /* why */g, Set<Cl> /* validDecisions */h) {
 this.transactionId = axw(a);
 this.sessionId = axw(b);
 this.timestampEpochMillis = nonNeg(c);
 this.ageMillis = nonNeg(d);
 this.items = Collections.unmodifiableList(e == null ? new ArrayList<Item>() : new ArrayList<>(e));
 this.value = f;
 this.why = axw(g);
 EnumSet<Cl> /* copy */i = h == null || h.isEmpty()
 ? EnumSet.noneOf(Cl.class) : EnumSet.copyOf(h);
 this.validDecisions = Collections.unmodifiableSet(i);
}

/** Detached item summary; value is the signed flow value for this item. */
static class Item {
 int itemId;
 String name;
 long quantityDelta;
 long valueDelta;
 Item(int /* itemId */a, String /* name */b, long /* quantityDelta */c, long /* valueDelta */d) {
  this.itemId = a;
  this.name = axw(b);
  this.quantityDelta = c;
  this.valueDelta = d;
 }
}
}
