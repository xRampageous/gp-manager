package com.gpmanager;
import java.util.*;
/**
* Transient ownership of automatic charge estimates. The canonical receipts remain in the owning
* {@link Ad}; this index links a stable target/component to those receipt ids until a
* compatible measured Check closes the window. Never persisted.
*/
class ChargeEstimateJournal {
static final int MAX_RECEIPTS = 512;
static final class Receipt {
 String key;
 String transactionId;
 int itemId;
 long remainingQuantity;
 Receipt(String /* key */b, String /* transactionId */c, int /* itemId */d, long /* quantity */e) {
  this.key = b;
  this.transactionId = c;
  this.itemId = d;
  this.remainingQuantity = e;
 }
}

LinkedHashMap<String, List<Receipt>> entries = new LinkedHashMap<>();
/** Quantities evicted by the bound while their measured window is still open, per key+item. */
LinkedHashMap<String, LinkedHashMap<Integer, Long>> evicted = new LinkedHashMap<>();
int receiptCount;
synchronized void clear() {
 entries.clear();
 evicted.clear();
 receiptCount = 0;
}

synchronized void record(String /* sessionId */a, String /* targetIdentity */b, String /* family */c,
String /* transactionId */d, int /* itemId */e, long /* quantity */f) {
 String /* session */g = Ag.lower(a);
 String /* target */h = Ag.lower(b);
 String /* aqd */i = Ag.lower(c);
 if (g.isEmpty() || h.isEmpty() || i.isEmpty()
 || d == null || d.trim().isEmpty() || e <= 0 || f <= 0L) {
  return;
 }
 String /* key */j = g + "\n" + h + "\n" + i;
 entries.computeIfAbsent(j, /* ignored */k -> new ArrayList<>()).add(new Receipt(j, d, e, f));
 receiptCount++;
 while (receiptCount > MAX_RECEIPTS) {
  Iterator<Map.Entry<String, List<Receipt>>> /* keys */l = entries.entrySet().iterator();
  if (!l.hasNext()) break;
  Map.Entry<String, List<Receipt>> /* first */m = l.next();
  List<Receipt> /* alx */o = m.getValue();
  if (!o.isEmpty()) {
   Receipt /* gone */p = o.remove(0);
   receiptCount--;
   if (p.remainingQuantity > 0L) {
    // The window is still open: remember the evicted quantity so the next measured
    // delta is not booked on top of an estimate that is no longer tracked here.
    evicted.computeIfAbsent(p.key, /* ignored */q -> new LinkedHashMap<>())
    .merge(p.itemId, p.remainingQuantity, Long::sum);
   }
  }
  if (o.isEmpty()) l.remove();
 }
}

/** Evicted quantity still inside this target's open window for one component. */
synchronized long evictedOverlap(String /* sessionId */a, String /* targetIdentity */b, int /* itemId */c) {
 String /* prefix */d = Ag.lower(a) + "\n" + Ag.lower(b) + "\n";
 long /* total */e = 0L;
 for (Map.Entry<String, LinkedHashMap<Integer, Long>> /* entry */f : evicted.entrySet()) {
  if (!f.getKey().startsWith(d)) continue;
  Long /* quantity */g = f.getValue().get(c);
  if (g != null) e = Ae.safeAdd(e, g);
 }
 return e;
}

/** Pending receipts for one component across families; a stable target resolves to one family. */
synchronized List<Receipt> receipts(String /* sessionId */a, String /* targetIdentity */b, int /* itemId */c) {
 String /* prefix */d = Ag.lower(a) + "\n" + Ag.lower(b) + "\n";
 List<Receipt> /* result */e = new ArrayList<>();
 for (List<Receipt> /* values */f : entries.values()) {
  for (Receipt /* receipt */g : f) {
   if (g.itemId == c && g.key.startsWith(d)) e.add(g);
  }
 }
 return e;
}

/** Every pending receipt for one target, whichever component or family it belongs to. */
synchronized List<Receipt> receiptsFor(String /* sessionId */a, String /* targetIdentity */b) {
 String /* prefix */c = Ag.lower(a) + "\n" + Ag.lower(b) + "\n";
 List<Receipt> /* result */d = new ArrayList<>();
 for (Map.Entry<String, List<Receipt>> /* entry */e : entries.entrySet()) {
  if (e.getKey().startsWith(c)) d.addAll(e.getValue());
 }
 return d;
}

synchronized void consume(Receipt /* receipt */a, long /* quantity */b) {
 if (a == null || b <= 0L) return;
 List<Receipt> /* values */c = entries.get(a.key);
 if (c == null || !c.contains(a)) return;
 a.remainingQuantity = Math.max(0L, a.remainingQuantity - b);
 if (a.remainingQuantity == 0L) {
  c.remove(a);
  receiptCount--;
 }
 if (c.isEmpty()) entries.remove(a.key);
}

/** Forget every receipt, and all evicted overlap, for one target (its window closed). */
synchronized void clearTarget(String /* sessionId */a, String /* targetIdentity */b) {
 String /* prefix */c = Ag.lower(a) + "\n" + Ag.lower(b) + "\n";
 List<List<Receipt>> /* removed */d = new ArrayList<>();
 entries.entrySet().removeIf(/* entry */e -> {
  boolean /* match */f = e.getKey().startsWith(c);
  if (f) d.add(e.getValue());
  return f;
 });
 for (List<Receipt> /* values */g : d) receiptCount -= g.size();
 evicted.entrySet().removeIf(/* entry */h -> h.getKey().startsWith(c));
 if (receiptCount < 0) receiptCount = 0;
}
}
