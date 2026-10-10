package com.gpmanager;

import java.util.*;

/**
 * Transient ownership of automatic charge estimates. The canonical receipts remain in the owning
 * {@link Session}; this index links one weapon (session and target identity) to those receipt ids
 * until a compatible measured Check closes the window. Never persisted.
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

    /** Open receipts per weapon, oldest weapon first. */
    final LinkedHashMap<String, List<Receipt>> entries = new LinkedHashMap<>();

    /** Quantities evicted by the bound while their weapon's window is still open, per item. */
    final Map<String, Map<Integer, Long>> evicted = new HashMap<>();

    int receiptCount;

    static String key(String sessionId, String targetIdentity) {
        return Phrases.lower(sessionId) + "\n" + Phrases.lower(targetIdentity);
    }

    synchronized void clear() {
        entries.clear();
        evicted.clear();
        receiptCount = 0;
    }

    synchronized void record(
            String sessionId,
            String targetIdentity,
            String transactionId,
            int itemId,
            long quantity) {
        if (Phrases.blank(sessionId)
                || Phrases.blank(targetIdentity)
                || Phrases.blank(transactionId)
                || itemId <= 0
                || quantity <= 0L) {
            return;
        }
        String key = key(sessionId, targetIdentity);
        entries.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new Receipt(key, transactionId, itemId, quantity));
        receiptCount++;
        while (receiptCount > MAX_RECEIPTS) {
            Map.Entry<String, List<Receipt>> oldest = entries.entrySet().iterator().next();
            Receipt gone = oldest.getValue().remove(0);
            receiptCount--;
            // The window is still open: remember the evicted quantity so the next measured
            // delta is not booked on top of an estimate that is no longer tracked here.
            evicted.computeIfAbsent(gone.key, ignored -> new HashMap<>())
                    .merge(gone.itemId, gone.remainingQuantity, Long::sum);
            if (oldest.getValue().isEmpty()) entries.remove(oldest.getKey());
        }
    }

    /** Evicted quantity still inside this weapon's open window for one component. */
    synchronized long evictedOverlap(String sessionId, String targetIdentity, int itemId) {
        return evicted.getOrDefault(key(sessionId, targetIdentity), Map.of())
                .getOrDefault(itemId, 0L);
    }

    /** Pending receipts for one component of one weapon. */
    synchronized List<Receipt> receipts(String sessionId, String targetIdentity, int itemId) {
        List<Receipt> result = new ArrayList<>();
        for (Receipt receipt : receiptsFor(sessionId, targetIdentity))
            if (receipt.itemId == itemId) result.add(receipt);
        return result;
    }

    /** Every pending receipt for one weapon, whichever component it belongs to. */
    synchronized List<Receipt> receiptsFor(String sessionId, String targetIdentity) {
        return new ArrayList<>(entries.getOrDefault(key(sessionId, targetIdentity), List.of()));
    }

    synchronized void consume(Receipt receipt, long quantity) {
        List<Receipt> values = receipt == null ? null : entries.get(receipt.key);
        if (values == null || quantity <= 0L || !values.contains(receipt)) return;
        receipt.remainingQuantity = Math.max(0L, receipt.remainingQuantity - quantity);
        if (receipt.remainingQuantity == 0L) {
            values.remove(receipt);
            receiptCount--;
        }
        if (values.isEmpty()) entries.remove(receipt.key);
    }

    /** Forget every receipt, and all evicted overlap, for one weapon (its window closed). */
    synchronized void clearTarget(String sessionId, String targetIdentity) {
        String key = key(sessionId, targetIdentity);
        List<Receipt> removed = entries.remove(key);
        if (removed != null) receiptCount -= removed.size();
        evicted.remove(key);
    }
}
