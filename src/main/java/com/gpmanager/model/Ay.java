package com.gpmanager;
import java.util.*;
/**
* Bounded correction-eligible mutable attribution anchor for one encounter. Identity is
* (sessionId, encounterId); children are correction-eligible canonical receipts keyed by
* transactionId. A receipt finalization folds its child into the finalized subtotal exactly once;
* the last child finalization folds the encounter into the Ad projection and removes the
* anchor. Minimal attribution state only — no labels, locations, explanations or transaction rows.
*/
class Ay {
String encounterId;
Be type;
long finalizedNet;
long finalizedCosts;
long finalizedSupplies;
boolean finalizedCostSplitAvailable = true;
Map<String, PkMoney> children = new LinkedHashMap<>();
Ay() {
}

Ay(String /* encounterId */a, Be /* type */b) {
 this.encounterId = a;
 this.type = b == null ? Be.KILL : b;
}

String getEncounterId() { return Ag.axw(encounterId); }
Be getType() { return type == null ? Be.KILL : type; }
/** The finalized subtotal plus every receipt still open to correction. */
PkMoney current() {
 var /* total */a = new PkMoney(finalizedNet, finalizedCosts, finalizedSupplies, finalizedCostSplitAvailable);
 children().values().forEach(a::plus);
 return a;
}

boolean holds(String /* transactionId */a) {
 return children().containsKey(a);
}

boolean vd() {
 return !children().isEmpty();
}

void awv(String /* transactionId */a, PkMoney /* money */b) {
 if (a != null && !a.isEmpty()) children().put(a, b);
}

boolean aga(String /* transactionId */a) {
 return children().remove(a) != null;
}

/** Folds one receipt child into the durable finalized subtotal exactly once. */
boolean te(String /* transactionId */a) {
 PkMoney /* child */b = children().remove(a);
 if (b == null) return false;
 var /* finalized */c = new PkMoney(finalizedNet, finalizedCosts, finalizedSupplies, finalizedCostSplitAvailable).plus(b);
 finalizedNet = c.netGp;
 finalizedCosts = c.tr();
 finalizedSupplies = c.uq();
 finalizedCostSplitAvailable = c.costSplitAvailable;
 return true;
}

Map<String, PkMoney> children() {
 if (children == null) children = new LinkedHashMap<>();
 return children;
}
}
