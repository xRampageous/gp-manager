package com.gpmanager;
import java.util.*;
import lombok.*;
import static com.gpmanager.Ag.*;
import static java.util.Collections.*;
/** One undoable owner decision: the before/after state of every row it changed. */
class Dx {
String recordId;
long timestampEpochMillis;
String reason;
/** Non-empty when an undo operation linked to this correction has been applied. */
String undoId;
long undoneAtEpochMillis;
List<Change> changes;
// Records written before batch decisions keep their one change here; read only on load.
String transactionId;
Ah previousCorrection;
Ah newCorrection;
List<Ab> flowSnapshot;
String explanationSnapshot;
Dx() {
 // Gson
}

Dx(long timestampEpochMillis, String reason, List<Change> changes) {
 this.recordId = UUID.randomUUID().toString();
 this.timestampEpochMillis = timestampEpochMillis;
 this.reason = abo(reason);
 this.changes = new ArrayList<>(changes);
}

String getRecordId() { return axw(recordId); }
String getReason() {
 return abo(reason);
}

String getUndoId() { return axw(undoId); }
boolean isUndone() { return !getUndoId().isEmpty(); }
/** The rows this decision changed; an older one-row record reads as a batch of one. */
List<Change> getChanges() {
 if (changes != null && !changes.isEmpty()) return unmodifiableList(changes);
 return empty(transactionId) ? emptyList() : singletonList(new Change(transactionId, previousCorrection, newCorrection,
 flowSnapshot, explanationSnapshot));
}

/** True when any change in this record targets one of the given transaction ids. */
boolean ale(Set<String> transactionIds) {
 for (Change change : getChanges()) {
  if (transactionIds.contains(change.getTransactionId())) return true;
 }
 return false;
}

void zr(long now) {
 if (isUndone()) return;
 if (getRecordId().isEmpty()) recordId = UUID.randomUUID().toString();
 undoId = UUID.randomUUID().toString();
 undoneAtEpochMillis = Ae.nonNeg(now);
}

static String abo(String value) {
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
 Ah previousCorrection;
 Ah newCorrection;
 List<Ab> flowSnapshot;
 String explanationSnapshot;
 String getTransactionId() { return axw(transactionId); }
 Ah getPreviousCorrection() {
  return previousCorrection == null ? Ah.AUTO : previousCorrection;
 }
 List<Ab> getFlowSnapshot() {
  return flowSnapshot == null ? Collections.<Ab>emptyList() : unmodifiableList(flowSnapshot);
 }
 boolean hasFlowSnapshot() { return flowSnapshot != null; }
}
}
