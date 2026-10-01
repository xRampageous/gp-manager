package com.gpmanager;
import java.util.*;
import lombok.*;
import static com.gpmanager.ModelText.*;
import static java.util.Collections.*;
/** One undoable owner decision: the before/after state of every row it changed. */
class CorrectionRecord {
String recordId;
long timestampEpochMillis;
String reason;
/** Non-empty when an undo operation linked to this correction has been applied. */
String undoId;
long undoneAtEpochMillis;
List<Change> changes;
// Records written before batch decisions keep their one change here; read only on load.
String transactionId;
Correction previousCorrection;
Correction newCorrection;
List<Flow> flowSnapshot;
String explanationSnapshot;
CorrectionRecord() {
 // Gson
}

CorrectionRecord(long timestampEpochMillis, String reason, List<Change> changes) {
 this.recordId = UUID.randomUUID().toString();
 this.timestampEpochMillis = timestampEpochMillis;
 this.reason = normalizeReason(reason);
 this.changes = new ArrayList<>(changes);
}

String getRecordId() { return orEmpty(recordId); }
String getReason() {
 return normalizeReason(reason);
}

String getUndoId() { return orEmpty(undoId); }
boolean isUndone() { return !getUndoId().isEmpty(); }
/** The rows this decision changed; an older one-row record reads as a batch of one. */
List<Change> getChanges() {
 if (changes != null && !changes.isEmpty()) return unmodifiableList(changes);
 return empty(transactionId) ? emptyList() : singletonList(new Change(transactionId, previousCorrection, newCorrection,
 flowSnapshot, explanationSnapshot));
}

/** True when any change in this record targets one of the given transaction ids. */
boolean touchesAny(Set<String> transactionIds) {
 for (Change change : getChanges()) {
  if (transactionIds.contains(change.getTransactionId())) return true;
 }
 return false;
}

void markUndone(long now) {
 if (isUndone()) return;
 if (getRecordId().isEmpty()) recordId = UUID.randomUUID().toString();
 undoId = UUID.randomUUID().toString();
 undoneAtEpochMillis = SafeMath.nonNeg(now);
}

static String normalizeReason(String value) {
 if (blank(value)) return "Manual correction";
 String trimmed = value.trim();
 return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
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
 String getTransactionId() { return orEmpty(transactionId); }
 Correction getPreviousCorrection() {
  return previousCorrection == null ? Correction.AUTO : previousCorrection;
 }
 List<Flow> getFlowSnapshot() {
  return flowSnapshot == null ? Collections.<Flow>emptyList() : unmodifiableList(flowSnapshot);
 }
 boolean hasFlowSnapshot() { return flowSnapshot != null; }
}
}
