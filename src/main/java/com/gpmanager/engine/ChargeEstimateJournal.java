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
final String key;
final String transactionId;
final int itemId;
long remainingQuantity;
Receipt(String key, String transactionId, int itemId, long quantity) {
this.key = key;
this.transactionId = transactionId;
this.itemId = itemId;
this.remainingQuantity = quantity;
}
}
final LinkedHashMap<String, List<Receipt>> entries = new LinkedHashMap<>();
/** Quantities evicted by the bound while their measured window is still open, per key+item. */
final LinkedHashMap<String, LinkedHashMap<Integer, Long>> evicted = new LinkedHashMap<>();
int receiptCount;
synchronized void clear() {
entries.clear();
evicted.clear();
receiptCount = 0;
}
synchronized void record(String sessionId, String targetIdentity, String family,
String transactionId, int itemId, long quantity) {
String session = clean(sessionId);
String target = clean(targetIdentity);
String aqd = clean(family);
if (session.isEmpty() || target.isEmpty() || aqd.isEmpty()
|| transactionId == null || transactionId.trim().isEmpty() || itemId <= 0 || quantity <= 0L) {
return;
}
String key = session + "\n" + target + "\n" + aqd;
entries.computeIfAbsent(key, ignored -> new ArrayList<>())
.add(new Receipt(key, transactionId, itemId, quantity));
receiptCount++;
while (receiptCount > MAX_RECEIPTS) {
Iterator<Map.Entry<String, List<Receipt>>> keys = entries.entrySet().iterator();
if (!keys.hasNext()) break;
Map.Entry<String, List<Receipt>> first = keys.next();
List<Receipt> alx = first.getValue();
if (!alx.isEmpty()) {
Receipt gone = alx.remove(0);
receiptCount--;
if (gone.remainingQuantity > 0L) {
// The window is still open: remember the evicted quantity so the next measured
// delta is not booked on top of an estimate that is no longer tracked here.
evicted.computeIfAbsent(gone.key, ignored -> new LinkedHashMap<>())
.merge(gone.itemId, gone.remainingQuantity, Long::sum);
}
}
if (alx.isEmpty()) keys.remove();
}
}
synchronized long adp(String sessionId, String targetIdentity, String family, int itemId) {
List<Receipt> receipts = entries.get(clean(sessionId) + "\n" + clean(targetIdentity)
+ "\n" + clean(family));
long total = 0L;
if (receipts != null) {
for (Receipt receipt : receipts) {
if (receipt.itemId == itemId) {
total = Ae.safeAdd(total, Math.max(0L, receipt.remainingQuantity));
}
}
}
return total;
}
/** Evicted quantity still inside this target's open window for one component. */
synchronized long evictedOverlap(String sessionId, String targetIdentity, int itemId) {
String prefix = clean(sessionId) + "\n" + clean(targetIdentity) + "\n";
long total = 0L;
for (Map.Entry<String, LinkedHashMap<Integer, Long>> entry : evicted.entrySet()) {
if (!entry.getKey().startsWith(prefix)) {
continue;
}
Long quantity = entry.getValue().get(itemId);
if (quantity != null) {
total = Ae.safeAdd(total, quantity);
}
}
return total;
}
/** Pending receipts for one component across families; a stable target resolves to one family. */
synchronized List<Receipt> receipts(String sessionId, String targetIdentity, int itemId) {
String prefix = clean(sessionId) + "\n" + clean(targetIdentity) + "\n";
List<Receipt> result = new ArrayList<>();
for (List<Receipt> values : entries.values()) {
for (Receipt receipt : values) {
if (receipt.itemId == itemId && receipt.key.startsWith(prefix)) {
result.add(receipt);
}
}
}
return result;
}
/** Every pending receipt for one target, whichever component or family it belongs to. */
synchronized List<Receipt> receiptsFor(String sessionId, String targetIdentity) {
String prefix = clean(sessionId) + "\n" + clean(targetIdentity) + "\n";
List<Receipt> result = new ArrayList<>();
for (Map.Entry<String, List<Receipt>> entry : entries.entrySet()) {
if (entry.getKey().startsWith(prefix)) {
result.addAll(entry.getValue());
}
}
return result;
}
synchronized void consume(Receipt receipt, long quantity) {
if (receipt == null || quantity <= 0L) {
return;
}
List<Receipt> values = entries.get(receipt.key);
if (values == null || !values.contains(receipt)) {
return;
}
receipt.remainingQuantity = Math.max(0L, receipt.remainingQuantity - quantity);
if (receipt.remainingQuantity == 0L) {
values.remove(receipt);
receiptCount--;
}
if (values.isEmpty()) {
entries.remove(receipt.key);
}
}
/** A measured Check supersedes a family's pending estimates for one session. */
synchronized void clearFamily(String sessionId, String family) {
String prefix = clean(sessionId) + "\n";
String name = clean(family);
List<List<Receipt>> removed = new ArrayList<>();
entries.entrySet().removeIf(entry -> {
boolean match = entry.getKey().startsWith(prefix) && entry.getKey().endsWith("\n" + name);
if (match) {
removed.add(entry.getValue());
}
return match;
});
for (List<Receipt> values : removed) {
receiptCount -= values.size();
}
evicted.entrySet().removeIf(entry -> {
String key = entry.getKey();
return key.startsWith(prefix) && key.endsWith("\n" + name);
});
if (receiptCount < 0) {
receiptCount = 0;
}
}
/** Forget every receipt, and all evicted overlap, for one target (its window closed). */
synchronized void clearTarget(String sessionId, String targetIdentity) {
String prefix = clean(sessionId) + "\n" + clean(targetIdentity) + "\n";
List<List<Receipt>> removed = new ArrayList<>();
entries.entrySet().removeIf(entry -> {
boolean match = entry.getKey().startsWith(prefix);
if (match) {
removed.add(entry.getValue());
}
return match;
});
for (List<Receipt> values : removed) {
receiptCount -= values.size();
}
evicted.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
if (receiptCount < 0) {
receiptCount = 0;
}
}
static String clean(String value) {
return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
}
}
