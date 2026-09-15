package com.gpmanager.persistence;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.PkEncounter;
import com.gpmanager.model.PkEncounterType;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.model.TransactionCorrection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.BiPredicate;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CsvExporterTest
{
    @Test
    public void protectsFormulaTextAndKeepsRepeatedExportsSeparate() throws Exception
    {
        Path directory = Files.createTempDirectory("gp-export-text");
        ProfitSession session = new ProfitSession("=1+1", 1L);
        session.setNotes("@SUM(A1:A2)");
        CsvExporter exporter = new CsvExporter();
        CsvExportResult first = exporter.exportSession(session, directory);
        CsvExportResult second = exporter.exportSession(session, directory);
        assertTrue(!first.getDetailPath().equals(second.getDetailPath()));
        String diagnostics = Files.readString(first.getDiagnosticsPath(), StandardCharsets.UTF_8);
        assertTrue(diagnostics.contains("'=1+1"));
        assertTrue(diagnostics.contains("'@SUM(A1:A2)"));
    }
    @Test
    public void exportsDetailedAndTransactionSummaryCsvFiles() throws Exception
    {
        Path directory = Files.createTempDirectory("profit-manager-test");
        ProfitSession session = new ProfitSession("Test Session", 1L);
        ProfitTransaction transaction = new ProfitTransaction(
            2L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Boss, phase 2",
            true,
            Collections.singletonList(
                new ItemFlow(1, "Item, special", 2, 50, 100)));
        session.addTransaction(transaction, 100);
        session.correctTransaction(transaction.getId(), com.gpmanager.model.TransactionCorrection.REVENUE, 3L, "Verified loot split");
        ProfitTransaction undone = new ProfitTransaction(
            4L, TransactionType.CONSUMPTION, TrackingContext.GENERIC, "Undo me", true,
            Collections.singletonList(new ItemFlow(2, "Supply", -1, 25, -25)));
        session.addTransaction(undone, 100);
        session.undoLastTransaction(5L);
        session.restoreLastUndo(6L);

        session.recordAction("Boss");
        CsvExportResult output = new CsvExporter().exportSession(session, directory, 15);
        String details = Files.readString(output.getDetailPath(), StandardCharsets.UTF_8);
        String summary = Files.readString(output.getSummaryPath(), StandardCharsets.UTF_8);
        String diagnostics = Files.readString(output.getDiagnosticsPath(), StandardCharsets.UTF_8);
        String activities = Files.readString(output.getActivitiesPath(), StandardCharsets.UTF_8);
        String pkEncounters = Files.readString(output.getPkEncountersPath(), StandardCharsets.UTF_8);
        String audit = Files.readString(output.getAuditPath(), StandardCharsets.UTF_8);

        assertTrue(details.contains("transaction_id"));
        assertTrue(details.contains("row_kind"));
        assertTrue(details.contains(transaction.getId()));
        assertTrue(details.contains("\"Boss, phase 2\""));
        assertTrue(details.contains("\"Item, special\""));
        assertTrue(details.contains("price_source"));
        assertTrue(details.contains("pricing_summary"));
        assertTrue(details.contains("correction_reason"));
        assertTrue(details.contains("Verified loot split"));

        assertTrue(summary.contains("flow_count"));
        assertTrue(summary.contains("confidence"));
        assertTrue(summary.contains("correction"));
        assertTrue(summary.contains("explanation"));
        assertTrue(summary.contains("correction_reason"));
        assertTrue(summary.contains("activity_name"));
        assertTrue(summary.contains(transaction.getId()));
        assertTrue(summary.contains(",100,0,100"));
        assertTrue(diagnostics.contains("rate_gp_per_hour"));
        assertTrue(diagnostics.contains("profit_per_action"));
        assertTrue(diagnostics.contains("pk_profit_per_kill"));
        assertTrue(diagnostics.contains("pk_loss_per_death"));
        assertTrue(diagnostics.contains("pk_net_per_encounter"));
        assertTrue(diagnostics.contains("undo_count"));
        assertTrue(activities.contains("activity_name"));
        assertTrue(activities.contains("Boss"));
        assertTrue(pkEncounters.contains("encounter_id"));
        assertTrue(audit.contains("event_kind"));
        assertTrue(audit.contains("CORRECTION"));
        assertTrue(audit.contains("UNDO"));
        assertTrue(audit.contains("Verified loot split"));
        assertTrue(audit.contains("true"));
        assertTrue(audit.contains("RAW_AUDIT"));
    }

    @Test
    public void rawAuditExportsEveryMemberOfAnUndoneReviewBatch() throws Exception
    {
        Path directory = Files.createTempDirectory("gp-review-batch-audit");
        ProfitSession session = new ProfitSession("Review", 1L);
        ProfitTransaction first = uncertainReceipt(10L, "First item");
        ProfitTransaction second = uncertainReceipt(20L, "Second item");
        session.addTransaction(first, 20);
        session.addTransaction(second, 20);
        assertEquals(2, session.correctPendingTransactions(
            Arrays.asList(first.getId(), second.getId()), TransactionCorrection.REVENUE,
            30L, "Bulk review decision: GAIN"));
        assertTrue(session.undoLastCorrection(40L));

        CsvExportResult output = new CsvExporter().exportSession(session, directory);
        String audit = Files.readString(output.getAuditPath(), StandardCharsets.UTF_8);
        assertTrue(audit.contains(first.getId()));
        assertTrue(audit.contains(second.getId()));
        assertEquals(2, audit.split("CORRECTION", -1).length - 1);
        assertTrue(audit.contains("Bulk review decision: GAIN"));
        assertTrue(audit.contains(",true,"));
    }
    @Test
    public void exportsSessionComparisonWithNotesAndFavorites() throws Exception
    {
        Path directory = Files.createTempDirectory("profit-manager-comparison");
        ProfitSession left = new ProfitSession("Left", 1L);
        left.setNotes("First session");
        left.setFavorite(true);
        left.addTransaction(new ProfitTransaction(
            2L, TransactionType.LOOT, TrackingContext.LOOT, "Loot", true,
            Collections.singletonList(new ItemFlow(1, "Item", 1, 100, 100))), 100);
        left.close(3_600_001L);

        ProfitSession right = new ProfitSession("Right", 1L);
        right.addTransaction(new ProfitTransaction(
            2L, TransactionType.CONSUMPTION, TrackingContext.GENERIC, "Cost", true,
            Collections.singletonList(new ItemFlow(2, "Supply", -1, 25, -25))), 100);
        right.close(3_600_001L);

        Path output = new CsvExporter().exportComparison(left, right, directory, 15);
        String comparison = Files.readString(output, StandardCharsets.UTF_8);
        assertTrue(comparison.contains("delta_right_minus_left"));
        assertTrue(comparison.contains("First session"));
        assertTrue(comparison.contains("favorite"));
        assertTrue(comparison.contains("-125"));
    }

    @Test
    public void filteredComparisonMarksUnavailableActionCounts() throws Exception
    {
        Path directory = Files.createTempDirectory("gp-filtered-comparison");
        ProfitSession left = new ProfitSession("Left", 1L);
        left.addTransaction(new ProfitTransaction(2L, TransactionType.LOOT,
            TrackingContext.LOOT, "Visible", true,
            Collections.singletonList(new ItemFlow(1, "Visible", 1L, 100, 100L))), 100);
        left.recordAction("Left action");
        left.close(3_600_001L);

        ProfitSession right = new ProfitSession("Right", 1L);
        right.addTransaction(new ProfitTransaction(2L, TransactionType.LOOT,
            TrackingContext.LOOT, "Visible", true,
            Collections.singletonList(new ItemFlow(1, "Visible", 1L, 200, 200L))), 100);
        right.recordAction("Right action");
        right.close(3_600_001L);

        BiPredicate<ProfitTransaction, ItemFlow> eligible = (transaction, flow) -> flow.getItemId() == 1;
        Path output = new CsvExporter().exportComparison(left, right, directory, 15, eligible);
        String comparison = Files.readString(output, StandardCharsets.UTF_8);

        assertTrue(comparison.contains("left_action_count_status"));
        assertTrue(comparison.contains("UNAVAILABLE_ITEM_ACTION_ATTRIBUTION"));
        assertFalse(comparison.contains("\nactions,"));
        assertTrue(comparison.contains("left_transaction_count_status"));
        assertTrue(comparison.contains("\nnet,"));
    }

    private static ProfitTransaction uncertainReceipt(long at, String itemName)
    {
        int itemId = (int) at;
        return new ProfitTransaction(at, null, TransactionType.UNCERTAIN,
            TrackingContext.GENERIC, "Uncertain", "Test", false,
            Collections.singletonList(new ItemFlow(itemId, itemName, 1L, 100, 100L)),
            ClassificationConfidence.UNCERTAIN, "Needs owner decision", null);
    }

    @Test
    public void filteredCsvUsesOneEligibleProjectionAcrossLedgerActivitiesAndPk() throws Exception
    {
        Path directory = Files.createTempDirectory("gp-filtered-export");
        ProfitSession session = new ProfitSession("Filtered", 0L);
        ProfitTransaction transaction = new ProfitTransaction(1L, 1L, TransactionType.LOOT,
            TrackingContext.LOOT, "Mixed", "Goblin", true, Arrays.asList(
                new ItemFlow(1, "Shown gain", 1L, 100, 100L),
                new ItemFlow(2, "Hidden gain", 1L, 300, 300L),
                new ItemFlow(1, "Shown cost", -1L, 20, -20L),
                new ItemFlow(2, "Hidden cost", -1L, 70, -70L)));
        session.addTransaction(transaction, 100);
        session.correctTransaction(transaction.getId(), TransactionCorrection.REVENUE, 2L);
        PkEncounter encounter = session.addPkEncounter(PkEncounterType.KILL, 3L,
            "Victim", ClassificationConfidence.CONFIRMED, "Kill evidence");
        session.attachTransactionToEncounter(transaction.getId(), encounter.getId(), false);
        BiPredicate<ProfitTransaction, ItemFlow> eligible = (tx, flow) -> flow.getItemId() == 1;

        CsvExportResult output = new CsvExporter().exportSession(session, directory, 15, eligible);
        String details = Files.readString(output.getDetailPath(), StandardCharsets.UTF_8);
        String summary = Files.readString(output.getSummaryPath(), StandardCharsets.UTF_8);
        String diagnostics = Files.readString(output.getDiagnosticsPath(), StandardCharsets.UTF_8);
        String activities = Files.readString(output.getActivitiesPath(), StandardCharsets.UTF_8);
        String pk = Files.readString(output.getPkEncountersPath(), StandardCharsets.UTF_8);

        assertTrue(details.contains("FILTERED_ACCOUNTING"));
        assertTrue(details.contains("AVAILABLE_FILTERED"));
        assertTrue(details.contains("Shown gain"));
        assertFalse(details.contains("Hidden gain"));
        assertFalse(details.contains("Hidden cost"));
        assertTrue(summary.contains("120,0,120"));
        String[] detailLines = details.trim().split("\\r?\\n");
        String[] detailHeaders = detailLines[0].split(",");
        int rowKindColumn = Arrays.asList(detailHeaders).indexOf("record_kind");
        int rowRevenueColumn = Arrays.asList(detailHeaders).indexOf("projected_row_revenue");
        int rowCostsColumn = Arrays.asList(detailHeaders).indexOf("projected_row_costs");
        int rowNetColumn = Arrays.asList(detailHeaders).indexOf("projected_row_net");
        long detailRevenue = 0L;
        long detailCosts = 0L;
        long detailNet = 0L;
        for (int index = 1; index < detailLines.length; index++)
        {
            String[] values = detailLines[index].split(",", -1);
            if (values[rowKindColumn].equals("ITEM_FLOW"))
            {
                detailRevenue += Long.parseLong(values[rowRevenueColumn]);
                detailCosts += Long.parseLong(values[rowCostsColumn]);
                detailNet += Long.parseLong(values[rowNetColumn]);
            }
        }
        assertEquals(120L, detailRevenue);
        assertEquals(0L, detailCosts);
        assertEquals(120L, detailNet);
        assertFalse(summary.contains("Hidden gain"));
        assertTrue(diagnostics.contains("AVAILABLE_FILTERED"));
        String[] diagnosticLines = diagnostics.trim().split("\\r?\\n");
        String[] diagnosticHeaders = diagnosticLines[0].split(",");
        String[] diagnosticValues = diagnosticLines[1].split(",", -1);
        assertEquals("", diagnosticValues[Arrays.asList(diagnosticHeaders).indexOf("actions")]);
        assertEquals("UNAVAILABLE_ITEM_ACTION_ATTRIBUTION",
            diagnosticValues[Arrays.asList(diagnosticHeaders).indexOf("action_count_status")]);
        assertTrue(activities.contains("Goblin"));
        assertTrue(activities.contains("120"));
        assertTrue(pk.contains("Victim"));
        assertTrue(pk.contains("120"));
    }

    @Test
    public void legacyCompactedDataIsMarkedUnavailableInsteadOfUsingRawTotals() throws Exception
    {
        Path directory = Files.createTempDirectory("gp-legacy-filtered-export");
        ProfitSession compacted = new ProfitSession("Legacy", 0L);
        compacted.addTransaction(new ProfitTransaction(1L, TransactionType.LOOT,
            TrackingContext.LOOT, "Old", true,
            Collections.singletonList(new ItemFlow(2, "Hidden old item", 1L, 500, 500L))), 1);
        compacted.addTransaction(new ProfitTransaction(2L, TransactionType.LOOT,
            TrackingContext.LOOT, "Recent", true,
            Collections.singletonList(new ItemFlow(1, "Shown recent item", 1L, 100, 100L))), 1);

        Gson gson = new Gson();
        JsonObject legacyJson = new JsonParser().parse(gson.toJson(compacted)).getAsJsonObject();
        legacyJson.remove("retainedItemContributionsVersion");
        legacyJson.remove("retainedItemContributionsComplete");
        ProfitSession restored = gson.fromJson(legacyJson, ProfitSession.class);
        BiPredicate<ProfitTransaction, ItemFlow> eligible = (tx, flow) -> flow.getItemId() == 1;
        assertFalse(restored.metrics(60_000L, 60_000L, eligible).isAccountingProjectionAvailable());
        assertEquals("UNAVAILABLE_LEGACY_COMPACTION",
            restored.metrics(60_000L, 60_000L, eligible).getAccountingProjectionStatus());

        CsvExportResult output = new CsvExporter().exportSession(restored, directory, 15, eligible);
        String details = Files.readString(output.getDetailPath(), StandardCharsets.UTF_8);
        String diagnostics = Files.readString(output.getDiagnosticsPath(), StandardCharsets.UTF_8);
        String activities = Files.readString(output.getActivitiesPath(), StandardCharsets.UTF_8);
        String pk = Files.readString(output.getPkEncountersPath(), StandardCharsets.UTF_8);
        assertTrue(details.contains("UNAVAILABLE_LEGACY_COMPACTION"));
        assertTrue(diagnostics.contains("UNAVAILABLE_LEGACY_COMPACTION"));
        assertTrue(activities.contains("UNAVAILABLE_LEGACY_COMPACTION"));
        assertTrue(pk.contains("UNAVAILABLE_LEGACY_COMPACTION"));

        String[] headers = diagnostics.substring(0, diagnostics.indexOf('\n')).split(",");
        String[] values = diagnostics.substring(diagnostics.indexOf('\n') + 1).trim().split(",", -1);
        int revenueColumn = Arrays.asList(headers).indexOf("revenue");
        assertEquals("Filtered revenue is blank when legacy detail prevents recalculation", "", values[revenueColumn]);
    }

}
