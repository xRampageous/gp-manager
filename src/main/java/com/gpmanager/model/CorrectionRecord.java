package com.gpmanager;

import static com.gpmanager.Phrases.*;

import static java.util.Collections.*;

import lombok.*;

import java.util.*;

/** One undoable owner decision: the before/after state of every row it changed. */
class CorrectionRecord {
    String reason;
    List<Change> changes;

    /** Set once the decision has been undone; an undone record is skipped by later undos. */
    boolean undone;

    CorrectionRecord() {
        // Gson
    }

    CorrectionRecord(String reason, List<Change> changes) {
        this.reason = Transaction.normalizeReason(reason);
        this.changes = new ArrayList<>(changes);
    }

    String getReason() {
        return Transaction.normalizeReason(reason);
    }

    boolean isUndone() {
        return undone;
    }

    /** The rows this decision changed. */
    List<Change> getChanges() {
        return changes == null ? emptyList() : unmodifiableList(changes);
    }

    /** True when any change in this record targets one of the given transaction ids. */
    boolean touchesAny(Set<String> transactionIds) {
        for (Change change : getChanges()) {
            if (transactionIds.contains(change.getTransactionId())) return true;
        }
        return false;
    }

    /**
     * One row's before/after state. The flow and explanation snapshots are set when the decision
     * changed the row's flows (split, own-drop recovery); flows are immutable, so they are shared.
     */
    @NoArgsConstructor
    @AllArgsConstructor
    static class Change {
        String transactionId;
        Correction previousCorrection;
        Correction newCorrection;
        List<Flow> flowSnapshot;
        String explanationSnapshot;

        String getTransactionId() {
            return orEmpty(transactionId);
        }

        Correction getPreviousCorrection() {
            return previousCorrection == null ? Correction.AUTO : previousCorrection;
        }

        List<Flow> getFlowSnapshot() {
            return flowSnapshot == null
                    ? Collections.<Flow>emptyList()
                    : unmodifiableList(flowSnapshot);
        }

        boolean hasFlowSnapshot() {
            return flowSnapshot != null;
        }
    }
}
