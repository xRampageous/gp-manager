package com.gpmanager.persistence;

import com.gpmanager.model.ActivityMetrics;
import com.gpmanager.model.AccountingProjection;
import com.gpmanager.model.CorrectionRecord;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.PkEncounter;
import com.gpmanager.model.PkMetrics;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import com.gpmanager.model.UndoRecord;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.BiPredicate;

public class CsvExporter
{
    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    public Path export(ProfitSession session, Path exportDirectory) throws IOException
    {
        return exportSession(session, exportDirectory).getDetailPath();
    }

    public CsvExportResult exportSession(ProfitSession session, Path exportDirectory) throws IOException
    {
        return exportSession(session, exportDirectory, 15);
    }

    public CsvExportResult exportSession(
        ProfitSession session,
        Path exportDirectory,
        int rollingRateMinutes) throws IOException
    {
        return exportSession(session, exportDirectory, rollingRateMinutes, null);
    }

    /** Optional eligibility keeps exported accounting totals aligned with the live projection. */
    public CsvExportResult exportSession(
        ProfitSession session,
        Path exportDirectory,
        int rollingRateMinutes,
        BiPredicate<ProfitTransaction, ItemFlow> contributionEligibility) throws IOException
    {
        if (session == null)
        {
            throw new IllegalArgumentException("No active session to export");
        }

        Files.createDirectories(exportDirectory);
        long exportedAt = System.currentTimeMillis();
        String safeName = safeFileName(session.getName());
        String baseName = FILE_TIME.format(Instant.ofEpochMilli(exportedAt)) + "-" + safeName
            + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Path detailPath = exportDirectory.resolve(baseName + "-details.csv");
        Path summaryPath = exportDirectory.resolve(baseName + "-transactions.csv");
        Path diagnosticsPath = exportDirectory.resolve(baseName + "-diagnostics.csv");
        Path activitiesPath = exportDirectory.resolve(baseName + "-activities.csv");
        Path pkEncountersPath = exportDirectory.resolve(baseName + "-pk-encounters.csv");
        Path auditPath = exportDirectory.resolve(baseName + "-audit.csv");

        writeDetails(session, detailPath, contributionEligibility);
        writeTransactionSummary(session, summaryPath, contributionEligibility);
        writeDiagnostics(session, diagnosticsPath, exportedAt, rollingRateMinutes, contributionEligibility);
        writeActivities(session, activitiesPath, contributionEligibility);
        writePkEncounters(session, pkEncountersPath, contributionEligibility);
        writeAudit(session, auditPath);
        return new CsvExportResult(
            detailPath,
            summaryPath,
            diagnosticsPath,
            activitiesPath,
            pkEncountersPath,
            auditPath);
    }

    public Path exportComparison(
        ProfitSession left,
        ProfitSession right,
        Path exportDirectory,
        int rollingRateMinutes) throws IOException
    {
        return exportComparison(left, right, exportDirectory, rollingRateMinutes, null);
    }

    public Path exportComparison(
        ProfitSession left,
        ProfitSession right,
        Path exportDirectory,
        int rollingRateMinutes,
        BiPredicate<ProfitTransaction, ItemFlow> contributionEligibility) throws IOException
    {
        if (left == null || right == null)
        {
            throw new IllegalArgumentException("Two completed sessions are required for comparison export");
        }

        Files.createDirectories(exportDirectory);
        long exportedAt = System.currentTimeMillis();
        String leftName = safeFileName(left.getName());
        String rightName = safeFileName(right.getName());
        Path output = exportDirectory.resolve(
            FILE_TIME.format(Instant.ofEpochMilli(exportedAt))
                + "-compare-" + leftName + "-vs-" + rightName
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 8) + ".csv");
        long windowMillis = Math.max(1, rollingRateMinutes) * 60_000L;
        SessionMetrics leftMetrics = left.metrics(exportedAt, windowMillis, contributionEligibility);
        SessionMetrics rightMetrics = right.metrics(exportedAt, windowMillis, contributionEligibility);
        PkMetrics leftPk = left.pkMetrics(contributionEligibility);
        PkMetrics rightPk = right.pkMetrics(contributionEligibility);

        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
        {
            writer.write("metric,left_session,right_session,left_value,right_value,delta_right_minus_left");
            writer.newLine();
            writeComparisonRow(writer, "category", left, right, left.getCategory().name(), right.getCategory().name(), "");
            writeComparisonRow(writer, "started_at", left, right,
                Instant.ofEpochMilli(left.getStartedAtEpochMillis()).toString(),
                Instant.ofEpochMilli(right.getStartedAtEpochMillis()).toString(), "");
            writeComparisonRow(writer, "duration_millis", left, right,
                Long.toString(leftMetrics.getElapsedMillis()),
                Long.toString(rightMetrics.getElapsedMillis()),
                Long.toString(rightMetrics.getElapsedMillis() - leftMetrics.getElapsedMillis()));
            boolean needsProjectionStatus = contributionEligibility != null
                || left.getCompactedTransactionCount() > 0L || right.getCompactedTransactionCount() > 0L;
            if (needsProjectionStatus)
            {
                writeComparisonRow(writer, "left_accounting_projection_status", left, right,
                    leftMetrics.getAccountingProjectionStatus(), "", "");
                writeComparisonRow(writer, "right_accounting_projection_status", left, right,
                    "", rightMetrics.getAccountingProjectionStatus(), "");
                writeComparisonRow(writer, "left_action_count_status", left, right,
                    leftMetrics.isActionCountAvailable() ? "AVAILABLE" : "UNAVAILABLE_ITEM_ACTION_ATTRIBUTION", "", "");
                writeComparisonRow(writer, "right_action_count_status", left, right,
                    "", rightMetrics.isActionCountAvailable() ? "AVAILABLE" : "UNAVAILABLE_ITEM_ACTION_ATTRIBUTION", "");
                writeComparisonRow(writer, "left_transaction_count_status", left, right,
                    leftMetrics.isTransactionCountAvailable() ? "AVAILABLE" : "UNAVAILABLE_COMPACTED_TRANSACTION_COUNT", "", "");
                writeComparisonRow(writer, "right_transaction_count_status", left, right,
                    "", rightMetrics.isTransactionCountAvailable() ? "AVAILABLE" : "UNAVAILABLE_COMPACTED_TRANSACTION_COUNT", "");
            }
            if (leftMetrics.isAccountingProjectionAvailable() && rightMetrics.isAccountingProjectionAvailable())
            {
                writeComparisonNumber(writer, "revenue", left, right, leftMetrics.getRevenue(), rightMetrics.getRevenue());
                writeComparisonNumber(writer, "costs", left, right, leftMetrics.getCosts(), rightMetrics.getCosts());
                writeComparisonNumber(writer, "net", left, right, leftMetrics.getNet(), rightMetrics.getNet());
                writeComparisonNumber(writer, "gp_per_hour", left, right, leftMetrics.getProfitPerHour(), rightMetrics.getProfitPerHour());
            }
            if (leftMetrics.isActionCountAvailable() && rightMetrics.isActionCountAvailable())
            {
                writeComparisonNumber(writer, "actions", left, right,
                    leftMetrics.getActionCount(), rightMetrics.getActionCount());
            }
            if (leftMetrics.isTransactionCountAvailable() && rightMetrics.isTransactionCountAvailable())
            {
                writeComparisonNumber(writer, "transactions", left, right, leftMetrics.getTransactionCount(), rightMetrics.getTransactionCount());
            }
            writeComparisonRow(writer, "pk_projection_status", left, right,
                leftPk.isProjectionAvailable() ? "AVAILABLE" : "UNAVAILABLE_COMPACTED_PK_DETAIL",
                rightPk.isProjectionAvailable() ? "AVAILABLE" : "UNAVAILABLE_COMPACTED_PK_DETAIL", "");
            if (leftPk.isProjectionAvailable() && rightPk.isProjectionAvailable())
            {
                writeComparisonNumber(writer, "pk_kills", left, right, leftPk.getKills(), rightPk.getKills());
                writeComparisonNumber(writer, "pk_deaths", left, right, leftPk.getDeaths(), rightPk.getDeaths());
                writeComparisonNumber(writer, "pk_profit_per_kill", left, right, leftPk.getProfitPerKill(), rightPk.getProfitPerKill());
                writeComparisonNumber(writer, "pk_loss_per_death", left, right, leftPk.getLossPerDeath(), rightPk.getLossPerDeath());
            }
            writeComparisonRow(writer, "tags", left, right, left.getTagsDisplay(), right.getTagsDisplay(), "");
            writeComparisonRow(writer, "notes", left, right, left.getNotes(), right.getNotes(), "");
            writeComparisonRow(writer, "favorite", left, right,
                Boolean.toString(left.isFavorite()), Boolean.toString(right.isFavorite()), "");
        }
        return output;
    }

    private void writeDetails(
        ProfitSession session,
        Path output,
        BiPredicate<ProfitTransaction, ItemFlow> eligibility) throws IOException
    {
        String projectionStatus = session.detailProjectionStatus(eligibility);
        String scope = eligibility == null ? "ALL_ITEMS_ACCOUNTING" : "FILTERED_ACCOUNTING";
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
        {
            String header = "projection_scope,projection_status,record_kind,session_id,session_name,session_mode,transaction_id,row_kind,timestamp,active_elapsed_millis,type,automatic_type,context,activity_name,confidence,correction,correction_reason,counted,note,explanation,pricing_summary,encounter_id,item_id,item_name,quantity_delta,unit_price,price_source,value_delta,projected_row_revenue,projected_row_costs,projected_row_net,automatic_row_revenue,automatic_row_costs,automatic_row_net";
            writer.write(header);
            writer.newLine();
            if (eligibility != null || session.getCompactedTransactionCount() > 0L)
            {
                writeProjectionStatusRow(writer, session, scope, projectionStatus, header.split(",").length);
            }

            for (ProfitTransaction transaction : session.getTransactions())
            {
                AccountingProjection.TransactionAmounts amounts =
                    AccountingProjection.transaction(transaction, eligibility);
                if (eligibility != null && (!amounts.isAvailable() || !amounts.isIncluded()))
                {
                    continue;
                }
                if (transaction.getFlows().isEmpty())
                {
                    if (eligibility == null)
                    {
                        writeDetailRow(writer, session, transaction, null, amounts, eligibility, projectionStatus);
                    }
                    continue;
                }
                for (ItemFlow flow : transaction.getFlows())
                {
                    if (eligibility != null && !eligibility.test(transaction, flow))
                    {
                        continue;
                    }
                    AccountingProjection.TransactionAmounts flowAmounts =
                        AccountingProjection.flow(transaction, flow, eligibility);
                    writeDetailRow(writer, session, transaction, flow, flowAmounts, eligibility, projectionStatus);
                }
            }
        }
    }

    private void writeTransactionSummary(
        ProfitSession session,
        Path output,
        BiPredicate<ProfitTransaction, ItemFlow> eligibility) throws IOException
    {
        String projectionStatus = session.detailProjectionStatus(eligibility);
        String scope = eligibility == null ? "ALL_ITEMS_ACCOUNTING" : "FILTERED_ACCOUNTING";
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
        {
            String header = "projection_scope,projection_status,record_kind,session_id,session_name,session_mode,transaction_id,timestamp,active_elapsed_millis,type,automatic_type,context,activity_name,confidence,correction,correction_reason,counted,note,explanation,encounter_id,flow_count,transaction_revenue,transaction_costs,transaction_net,automatic_revenue,automatic_costs,automatic_net";
            writer.write(header);
            writer.newLine();
            if (eligibility != null || session.getCompactedTransactionCount() > 0L)
            {
                writeProjectionStatusRow(writer, session, scope, projectionStatus, header.split(",").length);
            }

            for (ProfitTransaction transaction : session.getTransactions())
            {
                AccountingProjection.TransactionAmounts amounts =
                    AccountingProjection.transaction(transaction, eligibility);
                if (eligibility != null && (!amounts.isAvailable() || !amounts.isIncluded()))
                {
                    continue;
                }
                int flowCount = 0;
                for (ItemFlow flow : transaction.getFlows())
                {
                    if (flow != null && (eligibility == null || eligibility.test(transaction, flow)))
                    {
                        flowCount++;
                    }
                }
                if (eligibility != null && flowCount == 0)
                {
                    continue;
                }
                writeValues(writer, new String[]
                {
                    scope, projectionStatus, "TRANSACTION",
                    session.getId(), session.getName(), session.getMode().name(), transaction.getId(),
                    Instant.ofEpochMilli(transaction.getTimestampEpochMillis()).toString(),
                    nullableLong(transaction.getActiveElapsedMillis()),
                    transaction.getType().name(), transaction.getAutomaticType().name(),
                    transaction.getContext().name(), transaction.getActivityName(),
                    transaction.getConfidence().name(), transaction.getCorrection().name(),
                    transaction.getCorrectionReason(), Boolean.toString(transaction.isCounted()), transaction.getNote(),
                    transaction.getExplanation(), transaction.getEncounterId(),
                    Integer.toString(flowCount),
                    Long.toString(eligibility == null ? transaction.getRevenue() : amounts.getRevenue()),
                    Long.toString(eligibility == null ? transaction.getCosts() : amounts.getCosts()),
                    Long.toString(eligibility == null ? transaction.getNet() : amounts.getNet()),
                    Long.toString(eligibility == null ? transaction.getAutomaticRevenue() : amounts.getAutomaticRevenue()),
                    Long.toString(eligibility == null ? transaction.getAutomaticCosts() : amounts.getAutomaticCosts()),
                    Long.toString(eligibility == null ? transaction.getAutomaticNet() : amounts.getAutomaticNet())
                });
            }
        }
    }

    private void writeDiagnostics(
        ProfitSession session,
        Path output,
        long exportedAt,
        int rollingRateMinutes,
        BiPredicate<ProfitTransaction, ItemFlow> contributionEligibility) throws IOException
    {
        SessionMetrics metrics = session.metrics(
            exportedAt,
            Math.max(1, rollingRateMinutes) * 60_000L,
            contributionEligibility);
        PkMetrics pk = session.pkMetrics(contributionEligibility);
        boolean projectionAvailable = metrics.isAccountingProjectionAvailable();
        boolean activityProjectionAvailable = session.isActivityProjectionAvailable(contributionEligibility);
        int uncertain = 0;
        int corrected = 0;
        for (ProfitTransaction transaction : session.getTransactions())
        {
            if (transaction.getAutomaticType() == TransactionType.UNCERTAIN)
            {
                uncertain++;
            }
            if (transaction.getCorrection() != TransactionCorrection.AUTO)
            {
                corrected++;
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
        {
            writer.write("session_id,session_name,session_mode,session_category,session_tags,session_notes,session_favorite,recovered_from_crash,excluded_from_averages,exported_at,activity_hint,paused,elapsed_millis,revenue,costs,net,session_gp_per_hour,rate_gp_per_hour,rate_window_minutes,actions,transactions,raw_transfers,raw_uncertain_transactions,raw_corrected_transactions,raw_undo_count,profit_per_action,cost_per_action,pk_kills,pk_deaths,pk_encounters,pk_streak,pk_revenue,pk_costs,pk_net,pk_best_kill,pk_largest_death_loss,pk_profit_per_kill,pk_loss_per_death,pk_net_per_encounter,raw_compacted_rows,raw_compacted_revenue,raw_compacted_costs,accounting_projection_status,transaction_count_status,rolling_rate_status,action_count_status,activity_projection_status,pk_projection_status");
            writer.newLine();
            writeValues(writer, new String[]
            {
                session.getId(), session.getName(), session.getMode().name(),
                session.getCategory().name(), session.getTagsDisplay(),
                session.getNotes(), Boolean.toString(session.isFavorite()),
                Boolean.toString(session.isRecoveredFromCrash()),
                Boolean.toString(session.isExcludedFromAverages()),
                Instant.ofEpochMilli(exportedAt).toString(),
                metrics.getActivityHint(), Boolean.toString(metrics.isPaused()),
                Long.toString(metrics.getElapsedMillis()), projectionAvailable ? Long.toString(metrics.getRevenue()) : "",
                projectionAvailable ? Long.toString(metrics.getCosts()) : "",
                projectionAvailable ? Long.toString(metrics.getNet()) : "",
                projectionAvailable ? Long.toString(metrics.getProfitPerHour()) : "",
                projectionAvailable && metrics.isRollingRateAvailable()
                    ? Long.toString(metrics.getRollingProfitPerHour()) : "",
                Integer.toString(Math.max(1, rollingRateMinutes)),
                metrics.isActionCountAvailable() ? Integer.toString(metrics.getActionCount()) : "",
                metrics.isTransactionCountAvailable() ? Integer.toString(metrics.getTransactionCount()) : "",
                Integer.toString(metrics.getTransferCount()),
                Integer.toString(uncertain), Integer.toString(corrected),
                Integer.toString(session.getUndoHistory().size()),
                projectionAvailable ? Long.toString(metrics.getProfitPerAction()) : "",
                projectionAvailable ? Long.toString(metrics.getCostPerAction()) : "",
                pk.isProjectionAvailable() ? Integer.toString(pk.getKills()) : "",
                pk.isProjectionAvailable() ? Integer.toString(pk.getDeaths()) : "",
                pk.isProjectionAvailable() ? Integer.toString(pk.getEncounterCount()) : "",
                pk.isProjectionAvailable() ? Integer.toString(pk.getCurrentStreak()) : "",
                pk.isProjectionAvailable() ? Long.toString(pk.getRevenue()) : "",
                pk.isProjectionAvailable() ? Long.toString(pk.getCosts()) : "",
                pk.isProjectionAvailable() ? Long.toString(pk.getNet()) : "",
                pk.isProjectionAvailable() ? Long.toString(pk.getBestKill()) : "",
                pk.isProjectionAvailable() ? Long.toString(pk.getLargestDeathLoss()) : "",
                pk.isProjectionAvailable() ? Long.toString(pk.getProfitPerKill()) : "",
                pk.isProjectionAvailable() ? Long.toString(pk.getLossPerDeath()) : "",
                pk.isProjectionAvailable() ? Long.toString(pk.getNetPerEncounter()) : "",
                Long.toString(session.getCompactedTransactionCount()),
                Long.toString(session.getCompactedRevenue()),
                Long.toString(session.getCompactedCosts()),
                metrics.getAccountingProjectionStatus(),
                metrics.isTransactionCountAvailable() ? "AVAILABLE" : "UNAVAILABLE_COMPACTED_TRANSACTION_COUNT",
                metrics.isRollingRateAvailable() ? "AVAILABLE" : "UNAVAILABLE_COMPACTED_ROLLING_DETAIL",
                metrics.isActionCountAvailable() ? "AVAILABLE" : "UNAVAILABLE_ITEM_ACTION_ATTRIBUTION",
                activityProjectionAvailable ? "AVAILABLE" : "UNAVAILABLE_COMPACTED_ACTIVITY_DETAIL",
                pk.isProjectionAvailable() ? "AVAILABLE" : "UNAVAILABLE_COMPACTED_PK_DETAIL"
            });
        }
    }

    private void writeActivities(
        ProfitSession session,
        Path output,
        BiPredicate<ProfitTransaction, ItemFlow> eligibility) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
        {
            writer.write("session_id,session_name,record_kind,projection_status,activity_name,actions,transactions,revenue,costs,net,profit_per_action,cost_per_action");
            writer.newLine();
            boolean available = session.isActivityProjectionAvailable(eligibility);
            String status = session.activityProjectionStatus(eligibility);
            if (eligibility != null || session.getCompactedTransactionCount() > 0L)
            {
                writeValues(writer, new String[]
                {
                    session.getId(), session.getName(), "PROJECTION_STATUS", status,
                    "", "", "", "", "", "", "", ""
                });
            }
            if (!available)
            {
                return;
            }
            for (ActivityMetrics activity : session.activityBreakdown(eligibility))
            {
                writeValues(writer, new String[]
                {
                    session.getId(), session.getName(), "ACTIVITY", status, activity.getActivityName(),
                    activity.isActionCountAvailable() ? Integer.toString(activity.getActionCount()) : "",
                    Integer.toString(activity.getTransactionCount()),
                    Long.toString(activity.getRevenue()), Long.toString(activity.getCosts()),
                    Long.toString(activity.getNet()),
                    activity.isActionCountAvailable() ? Long.toString(activity.getProfitPerAction()) : "",
                    activity.isActionCountAvailable() ? Long.toString(activity.getCostPerAction()) : ""
                });
            }
        }
    }

    private void writePkEncounters(
        ProfitSession session,
        Path output,
        BiPredicate<ProfitTransaction, ItemFlow> eligibility) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
        {
            writer.write("session_id,session_name,record_kind,projection_status,encounter_id,timestamp,type,label,confidence,explanation,transaction_count,revenue,costs,net");
            writer.newLine();
            PkMetrics pk = session.pkMetrics(eligibility);
            String status = session.pkProjectionStatus(eligibility);
            if (eligibility != null || session.getCompactedTransactionCount() > 0L)
            {
                writeValues(writer, new String[]
                {
                    session.getId(), session.getName(), "PROJECTION_STATUS", status,
                    "", "", "", "", "", "", "", "", "", ""
                });
            }
            if (!pk.isProjectionAvailable())
            {
                return;
            }
            for (PkEncounter encounter : session.getPkEncounters())
            {
                long revenue = 0L;
                long costs = 0L;
                int transactionCount = 0;
                for (String transactionId : encounter.getTransactionIds())
                {
                    ProfitTransaction transaction = session.findTransaction(transactionId);
                    if (transaction == null)
                    {
                        continue;
                    }
                    AccountingProjection.TransactionAmounts amounts =
                        AccountingProjection.transaction(transaction, eligibility);
                    if (!amounts.isAvailable() || !amounts.isIncluded()) continue;
                    transactionCount++;
                    revenue += amounts.getRevenue();
                    costs += amounts.getCosts();
                }
                writeValues(writer, new String[]
                {
                    session.getId(), session.getName(), "PK_ENCOUNTER", status, encounter.getId(),
                    Instant.ofEpochMilli(encounter.getTimestampEpochMillis()).toString(),
                    encounter.getType().name(), encounter.getLabel(),
                    encounter.getConfidence().name(), encounter.getExplanation(),
                    Integer.toString(transactionCount), Long.toString(revenue),
                    Long.toString(costs), Long.toString(revenue - costs)
                });
            }
        }
    }

    private void writeAudit(ProfitSession session, Path output) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
        {
            writer.write("export_scope,session_id,session_name,event_kind,event_timestamp,transaction_id,activity_name,previous_correction,new_correction,reason,transaction_type,net,restored,restored_at");
            writer.newLine();
            writeValues(writer, new String[]
            {
                "RAW_AUDIT", session.getId(), session.getName(), "EXPORT_SCOPE",
                "", "", "", "", "", "Corrections and undo audit is raw; no accounting filter applied.",
                "", "", "", ""
            });
            for (CorrectionRecord record : session.getCorrectionLog())
            {
                if (record == null)
                {
                    continue;
                }
                for (CorrectionRecord.Change change : record.getChanges())
                {
                    if (change == null) continue;
                    String restoredAt = record.isUndone()
                        ? Instant.ofEpochMilli(record.getUndoneAtEpochMillis()).toString()
                        : "";
                    writeValues(writer, new String[]
                    {
                        "RAW_AUDIT", session.getId(), session.getName(), "CORRECTION",
                        Instant.ofEpochMilli(record.getTimestampEpochMillis()).toString(),
                        change.getTransactionId(), "", change.getPreviousCorrection().name(),
                        change.getNewCorrection().name(), record.getReason(), "", "",
                        Boolean.toString(record.isUndone()), restoredAt
                    });
                }
            }
            for (UndoRecord record : session.getUndoHistory())
            {
                if (record == null)
                {
                    continue;
                }
                String restoredAt = record.isRestored()
                    ? Instant.ofEpochMilli(record.getRestoredAtEpochMillis()).toString()
                    : "";
                writeValues(writer, new String[]
                {
                    "RAW_AUDIT", session.getId(), session.getName(), "UNDO",
                    Instant.ofEpochMilli(record.getTimestampEpochMillis()).toString(),
                    record.getTransactionId(), record.getActivityName(), "", "",
                    record.isRestored() ? "Restored by user" : "Removed by user",
                    record.getType().name(), Long.toString(record.getNet()),
                    Boolean.toString(record.isRestored()), restoredAt
                });
            }
        }
    }

    private void writeDetailRow(
        BufferedWriter writer,
        ProfitSession session,
        ProfitTransaction transaction,
        ItemFlow flow,
        AccountingProjection.TransactionAmounts amounts,
        BiPredicate<ProfitTransaction, ItemFlow> eligibility,
        String projectionStatus) throws IOException
    {
        boolean filtered = eligibility != null;
        writeValues(writer, new String[]
        {
            filtered ? "FILTERED_ACCOUNTING" : "ALL_ITEMS_ACCOUNTING",
            projectionStatus,
            flow == null ? "TRANSACTION" : "ITEM_FLOW",
            session.getId(), session.getName(), session.getMode().name(), transaction.getId(),
            flow == null ? "TRANSACTION" : "ITEM_FLOW",
            Instant.ofEpochMilli(transaction.getTimestampEpochMillis()).toString(),
            nullableLong(transaction.getActiveElapsedMillis()), transaction.getType().name(),
            transaction.getAutomaticType().name(), transaction.getContext().name(),
            transaction.getActivityName(), transaction.getConfidence().name(),
            transaction.getCorrection().name(), transaction.getCorrectionReason(), Boolean.toString(transaction.isCounted()),
            transaction.getNote(), transaction.getExplanation(), transaction.getPricingSummary(), transaction.getEncounterId(),
            flow == null ? "" : Integer.toString(flow.getItemId()),
            flow == null ? "" : flow.getItemName(),
            flow == null ? "" : Long.toString(flow.getQuantityDelta()),
            flow == null ? "" : Integer.toString(flow.getUnitPrice()),
            flow == null ? "" : flow.getPriceSource().name(),
            flow == null ? "" : Long.toString(flow.getValueDelta()),
            Long.toString(amounts.getRevenue()),
            Long.toString(amounts.getCosts()),
            Long.toString(amounts.getNet()),
            Long.toString(amounts.getAutomaticRevenue()),
            Long.toString(amounts.getAutomaticCosts()),
            Long.toString(amounts.getAutomaticNet())
        });
    }

    private void writeProjectionStatusRow(
        BufferedWriter writer,
        ProfitSession session,
        String scope,
        String status,
        int columnCount) throws IOException
    {
        String[] row = new String[columnCount];
        java.util.Arrays.fill(row, "");
        if (columnCount >= 5)
        {
            row[0] = scope;
            row[1] = status;
            row[2] = "PROJECTION_STATUS";
            row[3] = session.getId();
            row[4] = session.getName();
        }
        writeValues(writer, row);
    }

    private String nullableLong(Long value)
    {
        return value == null ? "" : Long.toString(value);
    }

    private String safeFileName(String value)
    {
        String safe = value == null ? "session" : value.replaceAll("[^A-Za-z0-9._-]+", "-");
        safe = safe.replaceAll("^-+|-+$", "");
        return safe.isEmpty() ? "session" : safe;
    }

    private void writeComparisonNumber(
        BufferedWriter writer,
        String metric,
        ProfitSession left,
        ProfitSession right,
        long leftValue,
        long rightValue) throws IOException
    {
        writeComparisonRow(
            writer,
            metric,
            left,
            right,
            Long.toString(leftValue),
            Long.toString(rightValue),
            Long.toString(rightValue - leftValue));
    }

    private void writeComparisonRow(
        BufferedWriter writer,
        String metric,
        ProfitSession left,
        ProfitSession right,
        String leftValue,
        String rightValue,
        String delta) throws IOException
    {
        writeValues(writer, new String[]
        {
            metric, left.getName(), right.getName(), leftValue, rightValue, delta
        });
    }

    private void writeValues(BufferedWriter writer, String[] values) throws IOException
    {
        for (int index = 0; index < values.length; index++)
        {
            if (index > 0)
            {
                writer.write(',');
            }
            writer.write(escape(values[index]));
        }
        writer.newLine();
    }

    private String escape(String value)
    {
        String safe = value == null ? "" : value;
        // Keep numeric CSV cells numeric, but prevent names/notes becoming
        // executable spreadsheet formulas when the export is opened.
        String trimmed = safe.stripLeading();
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0
            && !trimmed.matches("[+-]?[0-9]+(?:\\.[0-9]+)?"))
        {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r"))
        {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
