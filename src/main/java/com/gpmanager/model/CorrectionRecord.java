package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class CorrectionRecord
{
    private String recordId;
    private String transactionId;
    private long timestampEpochMillis;
    private TransactionCorrection previousCorrection;
    private TransactionCorrection newCorrection;
    private String reason;
    /** Optional pre-correction flows for split/undo restore (null on older records). */
    private List<ItemFlow> flowSnapshot;
    /** Optional pre-correction explanation for split/undo restore. */
    private String explanationSnapshot;
    /** Non-empty when an undo operation linked to this correction has been applied. */
    private String undoId;
    private long undoneAtEpochMillis;
    /** Additive batch payload. Null on correction records written before batch decisions. */
    private List<Change> changes;

    public CorrectionRecord()
    {
        // Gson
    }

    public CorrectionRecord(
        String transactionId,
        long timestampEpochMillis,
        TransactionCorrection previousCorrection,
        TransactionCorrection newCorrection)
    {
        this(transactionId, timestampEpochMillis, previousCorrection, newCorrection, "Manual correction");
    }

    public CorrectionRecord(
        String transactionId,
        long timestampEpochMillis,
        TransactionCorrection previousCorrection,
        TransactionCorrection newCorrection,
        String reason)
    {
        this(transactionId, timestampEpochMillis, previousCorrection, newCorrection, reason, null, null);
    }

    public CorrectionRecord(
        String transactionId,
        long timestampEpochMillis,
        TransactionCorrection previousCorrection,
        TransactionCorrection newCorrection,
        String reason,
        List<ItemFlow> flowSnapshot,
        String explanationSnapshot)
    {
        this.recordId = UUID.randomUUID().toString();
        this.transactionId = transactionId;
        this.timestampEpochMillis = timestampEpochMillis;
        this.previousCorrection = previousCorrection;
        this.newCorrection = newCorrection;
        this.reason = normalizeReason(reason);
        this.flowSnapshot = copyFlows(flowSnapshot);
        this.explanationSnapshot = explanationSnapshot;
    }

    private CorrectionRecord(long timestampEpochMillis, String reason, List<Change> changes)
    {
        List<Change> copy = new ArrayList<>();
        if (changes != null)
        {
            for (Change change : changes)
            {
                if (change != null && !change.getTransactionId().isEmpty())
                {
                    copy.add(change.copy());
                }
            }
        }
        Change first = copy.isEmpty() ? null : copy.get(0);
        this.recordId = UUID.randomUUID().toString();
        this.transactionId = first == null ? "" : first.getTransactionId();
        this.timestampEpochMillis = timestampEpochMillis;
        this.previousCorrection = first == null ? TransactionCorrection.AUTO : first.getPreviousCorrection();
        this.newCorrection = first == null ? TransactionCorrection.AUTO : first.getNewCorrection();
        this.reason = normalizeReason(reason);
        this.flowSnapshot = first == null || !first.hasFlowSnapshot()
            ? null : copyFlows(first.getFlowSnapshot());
        this.explanationSnapshot = first == null ? null : first.getExplanationSnapshot();
        this.changes = Collections.unmodifiableList(copy);
    }

    /** One persisted undo unit containing changes to several transactions. */
    public static CorrectionRecord batch(long timestampEpochMillis, String reason, List<Change> changes)
    {
        return new CorrectionRecord(timestampEpochMillis, reason, changes);
    }

    public String getRecordId() { return recordId == null ? "" : recordId; }
    public String getTransactionId() { return transactionId; }
    public long getTimestampEpochMillis() { return timestampEpochMillis; }
    public TransactionCorrection getPreviousCorrection()
    {
        return previousCorrection == null ? TransactionCorrection.AUTO : previousCorrection;
    }
    public TransactionCorrection getNewCorrection()
    {
        return newCorrection == null ? TransactionCorrection.AUTO : newCorrection;
    }

    public String getReason()
    {
        return normalizeReason(reason);
    }

    public List<ItemFlow> getFlowSnapshot()
    {
        return flowSnapshot == null ? Collections.emptyList() : Collections.unmodifiableList(flowSnapshot);
    }

    public boolean hasFlowSnapshot()
    {
        return flowSnapshot != null;
    }

    public String getExplanationSnapshot()
    {
        return explanationSnapshot;
    }

    public String getUndoId() { return undoId == null ? "" : undoId; }
    public long getUndoneAtEpochMillis() { return Math.max(0L, undoneAtEpochMillis); }
    public boolean isUndone() { return !getUndoId().isEmpty(); }

    /**
     * Returns detached correction changes. Legacy records without batch metadata
     * are presented as a one-change batch so undo code has one path.
     */
    public List<Change> getChanges()
    {
        if (changes != null && !changes.isEmpty())
        {
            List<Change> copy = new ArrayList<>(changes.size());
            for (Change change : changes)
            {
                if (change != null) copy.add(change.copy());
            }
            return Collections.unmodifiableList(copy);
        }
        if (transactionId == null || transactionId.isEmpty())
        {
            return Collections.emptyList();
        }
        return Collections.singletonList(new Change(transactionId, getPreviousCorrection(),
            getNewCorrection(), flowSnapshot, explanationSnapshot));
    }

    public int getBatchSize() { return getChanges().size(); }

    void markUndone(long now)
    {
        if (isUndone())
        {
            return;
        }
        if (getRecordId().isEmpty())
        {
            recordId = UUID.randomUUID().toString();
        }
        undoId = UUID.randomUUID().toString();
        undoneAtEpochMillis = Math.max(0L, now);
    }

    private static List<ItemFlow> copyFlows(List<ItemFlow> source)
    {
        if (source == null)
        {
            return null;
        }
        List<ItemFlow> copy = new ArrayList<>(source.size());
        for (ItemFlow flow : source)
        {
            if (flow == null)
            {
                continue;
            }
            copy.add(new ItemFlow(
                flow.getItemId(),
                flow.getItemName(),
                flow.getQuantityDelta(),
                flow.getUnitPrice(),
                flow.getValueDelta(),
                flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
        }
        return copy;
    }

    private static String normalizeReason(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return "Manual correction";
        }
        String trimmed = value.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    /** One transaction's before/after state inside an atomic correction batch. */
    public static final class Change
    {
        private String transactionId;
        private TransactionCorrection previousCorrection;
        private TransactionCorrection newCorrection;
        private List<ItemFlow> flowSnapshot;
        private String explanationSnapshot;

        public Change()
        {
            // Gson
        }

        public Change(String transactionId, TransactionCorrection previousCorrection,
            TransactionCorrection newCorrection, List<ItemFlow> flowSnapshot,
            String explanationSnapshot)
        {
            this.transactionId = transactionId;
            this.previousCorrection = previousCorrection;
            this.newCorrection = newCorrection;
            this.flowSnapshot = copyFlows(flowSnapshot);
            this.explanationSnapshot = explanationSnapshot;
        }

        public String getTransactionId() { return transactionId == null ? "" : transactionId; }
        public TransactionCorrection getPreviousCorrection()
        {
            return previousCorrection == null ? TransactionCorrection.AUTO : previousCorrection;
        }
        public TransactionCorrection getNewCorrection()
        {
            return newCorrection == null ? TransactionCorrection.AUTO : newCorrection;
        }
        public List<ItemFlow> getFlowSnapshot()
        {
            return flowSnapshot == null ? Collections.<ItemFlow>emptyList()
                : Collections.unmodifiableList(copyFlows(flowSnapshot));
        }
        public boolean hasFlowSnapshot() { return flowSnapshot != null; }
        public String getExplanationSnapshot() { return explanationSnapshot; }
        private Change copy()
        {
            return new Change(transactionId, getPreviousCorrection(), getNewCorrection(),
                flowSnapshot, explanationSnapshot);
        }
    }
}
