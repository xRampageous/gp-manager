package com.gpmanager;
import com.gpmanager.AccountingProjection.TransactionAmounts;
import java.io.*;
import java.nio.file.StandardOpenOption;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.runelite.client.util.Filepath;
import static com.gpmanager.GameData.msg;
/**
* Canonical CSV exports: correction-aware booked totals for every item, never a presentation
* filter. Compacted detail is one explicit {@code COMPACTED} row, never fabricated rows.
*/
class CsvExporter {
static final DateTimeFormatter FILE_TIME =
DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
/**
* One Grind's CSV (owner 2026-09-28: one file per export): one row per item flow, correction-
* aware, plus one COMPACTED row when retention folded older detail.
*/
Filepath exportSession(Session session, Filepath exportDirectory) throws IOException {
 return exportSession(session, session == null ? null : session.getTransactions(), exportDirectory);
}

/**
* One Grind's CSV over a detached transaction list: the UI takes that list under the engine
* lock, so concurrent bookings cannot mutate it while the file is written (owner 2026-10-01).
*/
Filepath exportSession(Session session, List<Transaction> transactions, Filepath exportDirectory) throws IOException {
 if (session == null) throw new IllegalArgumentException(msg("bx"));
 exportDirectory.createDirectories();
 String raw = session.getName();
 String safe = raw == null ? "session" : raw.replaceAll("[^A-Za-z0-9._-]+", "-");
 safe = safe.replaceAll("^-+|-+$", "");
 Filepath output = exportDirectory.joinSegment(FILE_TIME.format(Instant.now()) + "-"
 + (safe.isEmpty() ? "session" : safe) + "-" + UUID.randomUUID().toString().substring(0, 8) + ".csv");
 try (BufferedWriter details = open(output)) {
  details.write(msg("fu"));
  details.newLine();
  writeCompactedRow(session, details);
  for (Transaction transaction : transactions) {
   if (transaction.getFlows().isEmpty()) {
    writeDetailRow(details, session, transaction, null, AccountingProjection.transaction(transaction));
   }
   for (Flow flow : transaction.getFlows()) {
    writeDetailRow(details, session, transaction, flow, AccountingProjection.flow(transaction, flow));
   }
  }
 }
 return output;
}

/**
* Account history CSV: one row per retained canonical Grind/Session summary. Reporting only
* - never a restore format. Values are the correction-aware booked totals, historical prices
* stay booked, compacted detail is labelled instead of fabricated, and no presentation
* filter or Party peer state takes part.
*/
Filepath exportHistory(List<Session> history, Filepath exportDirectory, long now) throws IOException {
 if (history == null) throw new IllegalArgumentException(msg("br"));
 exportDirectory.createDirectories();
 long exportedAt = now <= 0L ? System.currentTimeMillis() : now;
 Filepath output = exportDirectory.joinSegment(FILE_TIME.format(Instant.ofEpochMilli(exportedAt))
 + "-account-history-" + UUID.randomUUID().toString().substring(0, 8) + ".csv");
 try (BufferedWriter writer = open(output)) {
  writer.write(msg("fv"));
  writer.newLine();
  for (Session session : history) {
   if (session == null) continue;
   SessionMetrics metrics = session.metrics(exportedAt);
   boolean compacted = session.getCompactedRevenue() != 0L || session.getCompactedCosts() != 0L;
   writeValues(writer, new String[] {
    session.getName(), session.getGrindId(), session.getId(), Instant.ofEpochMilli(session.startedAtEpochMillis).toString(),
    session.endedAtEpochMillis > 0L ? Instant.ofEpochMilli(session.endedAtEpochMillis).toString() : "",
    session.endReason == null ? "" : session.endReason.name(), Long.toString(metrics.elapsedMillis),
    Long.toString(metrics.net), Long.toString(metrics.revenue), Long.toString(metrics.costs),
    metrics.costSplitAvailable ? Long.toString(metrics.suppliesCosts) : "",
    metrics.costSplitAvailable ? Long.toString(metrics.otherCosts) : "",
    metrics.costSplitAvailable ? "AVAILABLE" : "UNAVAILABLE", compacted ? msg("es") : "DETAIL"
   });
  }
 }
 return output;
}

void writeDetailRow(BufferedWriter writer, Session session, Transaction transaction, Flow flow,
TransactionAmounts amounts) throws IOException {
 writeValues(writer, new String[] {
  flow == null ? "TRANSACTION" : "ITEM_FLOW", session.getId(), session.getName(), session.getMode().name(),
  transaction.getId(), Instant.ofEpochMilli(transaction.timestampEpochMillis).toString(),
  Objects.toString(transaction.activeElapsedMillis, ""), transaction.getType().name(),
  transaction.getAutomaticType().name(), transaction.getContext().name(),
  transaction.getActivityName(), transaction.getConfidence().name(),
  transaction.getCorrection().name(), transaction.getCorrectionReason(),
  Boolean.toString(transaction.isCounted()), transaction.getNote(), transaction.getExplanation(),
  transaction.getEncounterId()
 }, transaction.getPricingSummary(), flow == null ? "" : Integer.toString(flow.itemId),
 flow == null ? "" : flow.itemName, flow == null ? "" : Long.toString(flow.quantityDelta),
 flow == null ? "" : Integer.toString(flow.unitPrice), flow == null ? "" : flow.getPriceSource().name(),
 flow == null ? "" : Long.toString(flow.valueDelta), Long.toString(amounts.revenue), Long.toString(amounts.costs),
 Long.toString(amounts.getNet()), Long.toString(amounts.automaticRevenue), Long.toString(amounts.automaticCosts),
 Long.toString(amounts.getAutomaticNet()));
}

/**
* One explicit {@code COMPACTED} row when retention folded detail out of the session (charter T):
* retained rows plus this row reconcile exactly to the session totals; no old rows are fabricated.
*/
void writeCompactedRow(Session session, BufferedWriter details) throws IOException {
 Session.CompactedContribution compacted = session.compactedContribution();
 if (compacted.rows <= 0L) return;
 writeValues(details, new String[] {
  "COMPACTED", session.getId(), session.getName(), session.getMode().name(), "", "", "", "COMPACTED",
  "", "", "", "", "", "", "",
  compacted.rows + " older rows compacted by retention; totals exact, rows no longer individually correctable",
  "COMPACTED", ""
 }, "", "", "", Long.toString(compacted.rows), "", "", "", Long.toString(compacted.revenue),
 Long.toString(compacted.costs), Long.toString(compacted.getNet()), "", "", "");
}

static BufferedWriter open(Filepath output) throws IOException {
 return output.openBufferedWriter(
 StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
}

void writeValues(BufferedWriter writer, String[] values, String... more) throws IOException {
 var row = new StringJoiner(",");
 for (String value : values) row.add(escape(value));
 for (String value : more) row.add(escape(value));
 writer.write(row.toString());
 writer.newLine();
}

String escape(String value) {
 String safe = ModelText.orEmpty(value);
 // Keep numeric CSV cells numeric, but prevent names/notes becoming
 // executable spreadsheet formulas when the export is opened.
 String trimmed = safe.stripLeading();
 if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0 && !trimmed.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")) {
  safe = "'" + safe;
 }
 if (ModelText.has(safe, ",", "\"", "\n", "\r")) return "\"" + safe.replace("\"", "\"\"") + "\"";
 return safe;
}
}
