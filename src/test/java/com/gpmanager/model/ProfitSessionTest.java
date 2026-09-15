package com.gpmanager.model;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProfitSessionTest
{
    @Test
    public void categoryOverrideWinsAndPkingImpliesPkMode()
    {
        ProfitSession session = new ProfitSession("Manual category", 1L, SessionMode.AUTO);
        assertTrue(session.setCategoryOverride(SessionCategory.BOSSING));
        assertEquals(SessionCategory.BOSSING, session.getCategory());
        assertEquals(SessionMode.AUTO, session.getMode());

        assertTrue(session.setCategoryOverride(SessionCategory.PKING));
        assertEquals(SessionCategory.PKING, session.getCategory());
        assertEquals(SessionMode.PK, session.getMode());
        assertFalse(session.setCategoryOverride(SessionCategory.SKILLING));
        assertEquals(SessionCategory.PKING, session.getCategory());

        session.setMode(SessionMode.AUTO);
        assertEquals(SessionMode.AUTO, session.getMode());
        assertNull("leaving PK mode clears the implied PKing override",
            session.getCategoryOverride());
    }

    @Test
    public void malformedPkOverrideStillResolvesToPking()
    {
        ProfitSession malformed = new ProfitSession("Malformed", 1L, SessionMode.PK);
        com.google.gson.JsonObject json = new Gson().toJsonTree(malformed).getAsJsonObject();
        json.addProperty("categoryOverride", SessionCategory.SKILLING.name());
        ProfitSession restored = new Gson().fromJson(json, ProfitSession.class);

        assertEquals(SessionCategory.PKING, restored.getCategory());
        assertFalse(restored.setCategoryOverride(SessionCategory.SKILLING));
    }

    @Test
    public void categoryTagMigrationIsIdempotentAndFailsClosedOnConflictingTags()
    {
        ProfitSession session = new ProfitSession("Tagged", 1L, SessionMode.AUTO);
        session.setTags("bossing, favourite");

        assertTrue(session.migrateCategoryOverrideFromTags());
        assertEquals(SessionCategory.BOSSING, session.getCategory());
        assertFalse(session.migrateCategoryOverrideFromTags());
        assertEquals("bossing, favourite", session.getTagsDisplay());

        ProfitSession conflict = new ProfitSession("Conflict", 1L, SessionMode.AUTO);
        conflict.setTags("bossing, skilling");
        assertFalse(conflict.migrateCategoryOverrideFromTags());
        assertNull(conflict.getCategoryOverride());
    }

    @Test
    public void calculatesRevenueCostsNetAndRate()
    {
        long start = 1_000_000L;
        ProfitSession session = new ProfitSession("Bossing", start);

        session.addTransaction(
            new ProfitTransaction(
                start + 1_000L,
                TransactionType.LOOT,
                TrackingContext.LOOT,
                "Loot",
                true,
                Collections.singletonList(new ItemFlow(1, "Drop", 1, 1_000, 1_000))),
            100);

        ProfitTransaction food = new ProfitTransaction(
            start + 2_000L,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "Supplies",
            true,
            Collections.singletonList(new ItemFlow(2, "Food", -2, 100, -200)));
        food.setActionKind(com.gpmanager.model.ActionKind.EAT);
        session.addTransaction(food, 100);

        SessionMetrics metrics = session.metrics(start + 3_600_000L, 15L * 60_000L);

        assertEquals(1_000L, metrics.getRevenue());
        assertEquals(200L, metrics.getCosts());
        assertEquals(800L, metrics.getNet());
        assertEquals(200L, metrics.getSuppliesCosts());
        assertEquals(0L, metrics.getOtherCosts());
        assertTrue(metrics.isCostSplitAvailable());
        assertEquals(metrics.getCosts(), metrics.getSuppliesCosts() + metrics.getOtherCosts());
        assertEquals(800L, metrics.getProfitPerHour());
        assertEquals(2, metrics.getTransactionCount());
    }

    @Test
    public void filteredMetricsExcludeMatchingGainAndCostButKeepIncludedMixedFlows()
    {
        ProfitSession session = new ProfitSession("Filtered", 0L);
        session.addTransaction(new ProfitTransaction(1L, TransactionType.LOOT, TrackingContext.LOOT,
            "Mixed", true, Arrays.asList(
                new ItemFlow(1, "Hidden", 1L, 100, 100L),
                new ItemFlow(2, "Shown", 1L, 50, 50L),
                new ItemFlow(1, "Hidden", -1L, 100, -100L))), 100);

        SessionMetrics metrics = session.metrics(60_000L, 60_000L,
            (transaction, flow) -> flow.getItemId() != 1);

        assertEquals(50L, metrics.getRevenue());
        assertEquals(0L, metrics.getCosts());
        assertEquals(0L, metrics.getSuppliesCosts() + metrics.getOtherCosts());
        assertTrue(metrics.isCostSplitAvailable());
        assertEquals(50L, metrics.getNet());
        assertEquals(1, metrics.getTransactionCount());
        assertEquals(0, metrics.getActionCount());
        assertTrue(!metrics.isActionCountAvailable());
    }

    @Test
    public void manualRevenueAndCostCorrectionsOnlyUseEligibleFlows()
    {
        java.util.function.BiPredicate<ProfitTransaction, ItemFlow> eligible =
            (transaction, flow) -> flow.getItemId() == 1;
        ProfitSession revenueSession = new ProfitSession("Filtered correction", 0L);
        ProfitTransaction revenue = new ProfitTransaction(1L, TransactionType.LOOT,
            TrackingContext.LOOT, "Mixed", true, Arrays.asList(
                new ItemFlow(1, "Shown gain", 1L, 100, 100L),
                new ItemFlow(2, "Hidden gain", 1L, 300, 300L),
                new ItemFlow(1, "Shown cost", -1L, 20, -20L),
                new ItemFlow(2, "Hidden cost", -1L, 70, -70L)));
        revenueSession.addTransaction(revenue, 100);
        revenueSession.correctTransaction(revenue.getId(), TransactionCorrection.REVENUE, 2L);
        SessionMetrics revenueMetrics = revenueSession.metrics(60_000L, 60_000L, eligible);
        assertEquals(120L, revenueMetrics.getRevenue());
        assertEquals(0L, revenueMetrics.getCosts());
        assertTrue(revenueMetrics.isCostSplitAvailable());
        assertEquals(revenueMetrics.getCosts(),
            revenueMetrics.getSuppliesCosts() + revenueMetrics.getOtherCosts());

        ProfitSession costSession = new ProfitSession("Filtered correction", 0L);
        ProfitTransaction cost = new ProfitTransaction(1L, TransactionType.LOOT,
            TrackingContext.LOOT, "Mixed", true, Arrays.asList(
                new ItemFlow(1, "Shown gain", 1L, 100, 100L),
                new ItemFlow(2, "Hidden gain", 1L, 300, 300L),
                new ItemFlow(1, "Shown cost", -1L, 20, -20L),
                new ItemFlow(2, "Hidden cost", -1L, 70, -70L)));
        costSession.addTransaction(cost, 100);
        costSession.correctTransaction(cost.getId(), TransactionCorrection.COST, 2L);
        SessionMetrics costMetrics = costSession.metrics(60_000L, 60_000L, eligible);
        assertEquals(0L, costMetrics.getRevenue());
        assertEquals(120L, costMetrics.getCosts());
        assertTrue(costMetrics.isCostSplitAvailable());
        assertEquals(0L, costMetrics.getSuppliesCosts());
        assertEquals(120L, costMetrics.getOtherCosts());
        assertEquals(costMetrics.getCosts(),
            costMetrics.getSuppliesCosts() + costMetrics.getOtherCosts());
    }

    @Test
    public void pausedTimeIsExcluded()
    {
        ProfitSession session = new ProfitSession("Test", 1_000L);
        session.pause(2_000L);
        session.resume(12_000L);

        assertEquals(2_000L, session.getElapsedMillis(13_000L));
    }

    @Test
    public void boundsTransactionHistory()
    {
        ProfitSession session = new ProfitSession("Test", 0L);
        for (int index = 0; index < 5; index++)
        {
            session.addTransaction(
                new ProfitTransaction(
                    index,
                    TransactionType.GAIN,
                    TrackingContext.GENERIC,
                    "",
                    true,
                    Arrays.asList(new ItemFlow(index, "Item", 1, 1, 1))),
                3);
        }

        assertEquals(3, session.getTransactions().size());
        assertTrue(session.getTransactions().get(0).getTimestampEpochMillis() >= 2L);
        assertEquals(5L, session.metrics(5_000L, 60_000L).getNet());
        assertEquals(5, session.metrics(5_000L, 60_000L).getTransactionCount());
        assertEquals(2L, session.getCompactedTransactionCount());
    }

    @Test
    public void compactionPreservesCorrectedTotalsAcrossReloadAndUndo()
    {
        ProfitSession session = new ProfitSession("Long session", 0L);
        ProfitTransaction corrected = new ProfitTransaction(1L, TransactionType.UNCERTAIN,
            TrackingContext.GENERIC, "Review", false,
            Collections.singletonList(new ItemFlow(1, "Loot", 1, 100, 100)));
        session.addTransaction(corrected, 1);
        session.correctTransaction(corrected.getId(), TransactionCorrection.REVENUE, 2L);
        session.addTransaction(new ProfitTransaction(3L, TransactionType.CONSUMPTION,
            TrackingContext.GENERIC, "Cost", true,
            Collections.singletonList(new ItemFlow(2, "Food", -1, 25, -25))), 1);
        session.addTransaction(new ProfitTransaction(4L, TransactionType.TRANSFER,
            TrackingContext.TRANSFER, "Bank", false,
            Collections.singletonList(new ItemFlow(3, "Coins", 1000, 1, 1000))), 1);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        ProfitSession restored = gson.fromJson(gson.toJson(session), ProfitSession.class);
        assertEquals(75L, restored.metrics(5L, 60_000L).getNet());
        assertEquals(100L, restored.getCompactedRevenue());
        assertEquals(25L, restored.getCompactedCosts());
        restored.undoLastTransaction(6L);
        assertEquals(75L, restored.metrics(7L, 60_000L).getNet());
        restored.restoreLastUndo(8L);
        assertEquals(75L, restored.metrics(9L, 60_000L).getNet());
        assertEquals(1, restored.metrics(9L, 60_000L).getTransferCount());
    }

    @Test
    public void correctedCostCompactionKeepsEffectiveSessionAndRunAmounts()
    {
        ProfitSession session = new ProfitSession("Corrected compaction", 0L);
        ProfitTransaction correctedGain = new ProfitTransaction(1L, TransactionType.GAIN,
            TrackingContext.GENERIC, "Manual cost", true,
            Collections.singletonList(new ItemFlow(1, "Item", 1, 50, 50L)));
        session.addTransaction(correctedGain, 1);
        assertTrue(session.correctTransaction(correctedGain.getId(), TransactionCorrection.COST,
            2L, "Owner correction"));
        session.addTransaction(new ProfitTransaction(3L, TransactionType.LOOT,
            TrackingContext.LOOT, "Later gain", true,
            Collections.singletonList(new ItemFlow(2, "Later item", 1, 1, 1L))), 1);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        session = gson.fromJson(gson.toJson(session), ProfitSession.class);
        SessionMetrics metrics = session.metrics(5L, 60_000L);
        assertEquals(1L, metrics.getRevenue());
        assertEquals(50L, metrics.getCosts());
        assertEquals(0L, metrics.getSuppliesCosts());
        assertEquals(50L, metrics.getOtherCosts());
        assertTrue(metrics.isCostSplitAvailable());

        String runId = session.getCurrentRunId(5L);
        RunStatementSnapshot statement = findRun(session.runHistorySnapshot(5L), runId);
        assertEquals(1L, statement.getLootGp());
        assertEquals(50L, statement.getSuppliesGp());
        assertEquals(0L, statement.getConsumableGp());
        assertEquals(50L, statement.getLossGp());
        assertTrue(statement.isCostSplitAvailable());
    }

    @Test
    public void suppliesLossSplitSurvivesSessionAndRunCompaction()
    {
        ProfitSession session = new ProfitSession("Costs", 0L);
        session.addTransaction(cost(TransactionType.CONSUMPTION, "Food", 1, -100L), 1);
        session.addTransaction(cost(TransactionType.TRADE, "GE buy", 2, -200L), 1);
        session.addTransaction(cost(TransactionType.PK_DEATH_LOSS, "Death", 3, -300L), 1);
        session.addTransaction(cost(TransactionType.CONSUMPTION, "Death reclaim", 4, -25L), 1);
        String firstRun = session.getCurrentRunId(9_000L);
        Run second = session.startRun("Run 2", 10_000L);
        session.addTransaction(cost(TransactionType.CONSUMPTION, "Food", 5, -50L), 1);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        session = gson.fromJson(gson.toJson(session), ProfitSession.class);

        SessionMetrics metrics = session.metrics(20_000L, 60_000L);
        assertEquals(675L, metrics.getCosts());
        assertEquals(150L, metrics.getSuppliesCosts());
        assertEquals(525L, metrics.getOtherCosts());
        assertTrue(metrics.isCostSplitAvailable());
        assertEquals(metrics.getCosts(), metrics.getSuppliesCosts() + metrics.getOtherCosts());

        RunHistorySnapshot history = session.runHistorySnapshot(20_000L);
        RunStatementSnapshot firstStatement = findRun(history, firstRun);
        RunStatementSnapshot secondStatement = findRun(history, second.getId());
        assertEquals(625L, firstStatement.getSuppliesGp());
        assertEquals(100L, firstStatement.getConsumableGp());
        assertEquals(525L, firstStatement.getLossGp());
        assertTrue(firstStatement.isCostSplitAvailable());
        assertEquals(50L, secondStatement.getSuppliesGp());
        assertEquals(50L, secondStatement.getConsumableGp());
        assertEquals(0L, secondStatement.getLossGp());
        assertEquals(675L, firstStatement.getSuppliesGp() + secondStatement.getSuppliesGp());

        RunComparisonSnapshot comparison = session.compareRuns(firstRun, second.getId(), 20_000L);
        assertTrue(comparison.isCostSplitAvailable());
        assertEquals(Long.valueOf(-50L), comparison.getConsumableDeltaGp());
        assertEquals(Long.valueOf(-525L), comparison.getLossDeltaGp());
    }

    @Test
    public void legacyCompactedSessionDoesNotInventCostCategories()
    {
        ProfitSession session = new ProfitSession("Legacy", 0L);
        session.addTransaction(cost(TransactionType.CONSUMPTION, "Food", 1, -100L), 1);
        session.addTransaction(cost(TransactionType.LOOT, "Loot", 2, 200L), 1);
        com.google.gson.JsonObject json = new com.google.gson.Gson().toJsonTree(session).getAsJsonObject();
        json.remove("retainedCostSplitVersion");
        json.remove("retainedCostSplitComplete");
        ProfitSession legacy = new com.google.gson.Gson().fromJson(json, ProfitSession.class);

        SessionMetrics metrics = legacy.metrics(5_000L, 60_000L);
        assertEquals(100L, metrics.getCosts());
        assertTrue("legacy net remains valid", metrics.getNet() == 100L);
        assertFalse(metrics.isCostSplitAvailable());
    }

    private static ProfitTransaction cost(TransactionType type, String activity, int itemId, long value)
    {
        ProfitTransaction transaction = new ProfitTransaction(itemId, null, type, TrackingContext.GENERIC,
            activity, activity, true,
            Collections.singletonList(new ItemFlow(itemId, activity, -1L,
                (int) Math.abs(value), value)));
        if (type == TransactionType.CONSUMPTION && "Food".equals(activity)) transaction.setActionKind(com.gpmanager.model.ActionKind.EAT);
        return transaction;
    }

    private static RunStatementSnapshot findRun(RunHistorySnapshot history, String runId)
    {
        for (RunStatementSnapshot statement : history.getStatements())
        {
            if (runId.equals(statement.getRunId())) return statement;
        }
        throw new AssertionError("Missing run " + runId);
    }
    @Test
    public void pausedRatesRemainFrozenAndResumeOnActiveTime()
    {
        ProfitSession session = new ProfitSession("Test", 0L);
        session.addTransaction(
            new ProfitTransaction(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                TrackingContext.GENERIC,
                "",
                true,
                Arrays.asList(new ItemFlow(1, "Item", 1, 1_000, 1_000))),
            10);

        session.pause(2_000L);
        SessionMetrics atPause = session.metrics(2_000L, 60_000L);
        SessionMetrics muchLater = session.metrics(62_000L, 60_000L);

        assertEquals(atPause.getElapsedMillis(), muchLater.getElapsedMillis());
        assertEquals(atPause.getProfitPerHour(), muchLater.getProfitPerHour());
        assertEquals(atPause.getRollingProfitPerHour(), muchLater.getRollingProfitPerHour());

        session.resume(62_000L);
        SessionMetrics afterOneActiveSecond = session.metrics(63_000L, 60_000L);
        assertEquals(3_000L, afterOneActiveSecond.getElapsedMillis());
        assertEquals(1_800_000L, afterOneActiveSecond.getRollingProfitPerHour());
    }

    @Test
    public void buildsPerActivityProfitAndCostInsights()
    {
        ProfitSession session = new ProfitSession("Test", 0L);
        session.recordAction("Goblin");
        session.addTransaction(
            new ProfitTransaction(
                1_000L,
                1_000L,
                TransactionType.LOOT,
                TrackingContext.LOOT,
                "Loot from Goblin",
                "Goblin",
                true,
                Collections.singletonList(new ItemFlow(526, "Bones", 1, 31, 31))),
            10);

        assertEquals(1, session.activityBreakdown().size());
        ActivityMetrics activity = session.activityBreakdown().get(0);
        assertEquals("Goblin", activity.getActivityName());
        assertEquals(1, activity.getActionCount());
        assertEquals(31L, activity.getNet());
        assertEquals(31L, activity.getProfitPerAction());
    }


    @Test
    public void manualCorrectionsAreReversibleAndAudited()
    {
        ProfitSession session = new ProfitSession("Test", 0L);
        ProfitTransaction transaction = new ProfitTransaction(
            1_000L,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            true,
            Collections.singletonList(new ItemFlow(1, "Item", -1, 500, -500)));
        session.addTransaction(transaction, 10);

        session.correctTransaction(transaction.getId(), TransactionCorrection.REVENUE, 2_000L);
        assertEquals(500L, session.metrics(3_000L, 60_000L).getNet());
        assertEquals(1, session.getCorrectionHistory().size());
        assertEquals("Manual correction", transaction.getCorrectionReason());
        assertEquals("Manual correction", session.getCorrectionHistory().get(0).getReason());

        session.correctTransaction(transaction.getId(), TransactionCorrection.AUTO, 4_000L, "Rechecked against loot timeline");
        assertEquals(-500L, session.metrics(5_000L, 60_000L).getNet());
        assertEquals(2, session.getCorrectionHistory().size());
        assertEquals("Rechecked against loot timeline", transaction.getCorrectionReason());
        assertEquals("Rechecked against loot timeline", session.getCorrectionHistory().get(1).getReason());
    }

    @Test
    public void undoCorrectionPreservesAppliedRecordAndUndoLinkAcrossPersistence()
    {
        ProfitSession session = new ProfitSession("Test", 0L);
        ProfitTransaction transaction = new ProfitTransaction(
            1_000L,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            true,
            Collections.singletonList(new ItemFlow(1, "Item", -1, 500, -500)));
        session.addTransaction(transaction, 10);
        session.correctTransaction(transaction.getId(), TransactionCorrection.REVENUE, 2_000L, "Audit fix");

        assertTrue(session.undoLastCorrection(3_000L));
        assertEquals(-500L, session.metrics(4_000L, 60_000L).getNet());
        assertEquals(0, session.getCorrectionHistory().size());
        assertEquals(1, session.getCorrectionLog().size());
        CorrectionRecord record = session.getCorrectionLog().get(0);
        assertTrue(record.isUndone());
        assertTrue(record.getUndoneAtEpochMillis() == 3_000L);
        assertTrue(!record.getRecordId().isEmpty());
        assertTrue(!record.getUndoId().isEmpty());

        ProfitSession restored = new Gson().fromJson(new Gson().toJson(session), ProfitSession.class);
        assertEquals(1, restored.getCorrectionLog().size());
        assertTrue(restored.getCorrectionLog().get(0).isUndone());
        assertEquals(record.getRecordId(), restored.getCorrectionLog().get(0).getRecordId());
        assertEquals(record.getUndoId(), restored.getCorrectionLog().get(0).getUndoId());
    }

    @Test
    public void undoWalksBackToPreviousActiveCorrection()
    {
        ProfitSession session = new ProfitSession("Test", 0L);
        ProfitTransaction transaction = new ProfitTransaction(
            1_000L,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            true,
            Collections.singletonList(new ItemFlow(1, "Item", -1, 500, -500)));
        session.addTransaction(transaction, 10);
        session.correctTransaction(transaction.getId(), TransactionCorrection.REVENUE, 2_000L, "First");
        session.correctTransaction(transaction.getId(), TransactionCorrection.IGNORE, 3_000L, "Second");

        assertTrue(session.undoLastCorrection(4_000L));
        assertEquals(TransactionCorrection.REVENUE, transaction.getCorrection());
        assertTrue(session.undoLastCorrection(5_000L));
        assertEquals(TransactionCorrection.AUTO, transaction.getCorrection());
        assertEquals(0, session.getCorrectionHistory().size());
        assertEquals(2, session.getCorrectionLog().size());
    }

    @Test
    public void partialAndFullOwnDropRecoveryAreUndoable()
    {
        ProfitSession session = new ProfitSession("Test", 0L);
        ProfitTransaction transaction = new ProfitTransaction(
            1_000L,
            TransactionType.PK_DEATH_LOSS,
            TrackingContext.PK_DEATH,
            "Death loss",
            true,
            Collections.singletonList(new ItemFlow(1, "Item", -4, 250, -1_000)));
        session.addTransaction(transaction, 10);

        assertTrue(session.recoverOwnDropCosts(transaction.getId(), 1, 2L, 2_000L, "Partial reclaim"));
        assertEquals(-500L, session.metrics(3_000L, 60_000L).getNet());
        assertTrue(session.undoLastCorrection(4_000L));
        assertEquals(-1_000L, session.metrics(5_000L, 60_000L).getNet());

        assertTrue(session.recoverOwnDropCosts(transaction.getId(), 1, 4L, 6_000L, "Full reclaim"));
        assertEquals(0L, session.metrics(7_000L, 60_000L).getNet());
        assertTrue(session.undoLastCorrection(8_000L));
        assertEquals(-1_000L, session.metrics(9_000L, 60_000L).getNet());
    }

    @Test
    public void undoActionsAreAuditedWithTransactionMetadata()
    {
        ProfitSession session = new ProfitSession("Test", 0L);
        ProfitTransaction transaction = new ProfitTransaction(
            1_000L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Loot",
            true,
            Collections.singletonList(new ItemFlow(1, "Item", 1, 250, 250)));
        session.addTransaction(transaction, 10);

        ProfitTransaction removed = session.undoLastTransaction(2_000L);
        assertEquals(transaction.getId(), removed.getId());
        assertEquals(1, session.getUndoHistory().size());
        assertEquals("General", session.getUndoHistory().get(0).getActivityName());
        assertEquals(250L, session.getUndoHistory().get(0).getNet());
        assertEquals(2_000L, session.getUndoHistory().get(0).getTimestampEpochMillis());
    }

    @Test
    public void latestUndoCanBeRestoredWithoutLosingAuditRecord()
    {
        ProfitSession session = new ProfitSession("Test", 0L);
        ProfitTransaction transaction = new ProfitTransaction(
            1_000L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Loot",
            true,
            Collections.singletonList(new ItemFlow(1, "Item", 1, 250, 250)));
        session.addTransaction(transaction, 10);
        session.undoLastTransaction(2_000L);

        ProfitTransaction restored = session.restoreLastUndo(3_000L);
        assertEquals(transaction.getId(), restored.getId());
        assertEquals(1, session.getTransactions().size());
        assertTrue(session.getUndoHistory().get(0).isRestored());
        assertEquals(3_000L, session.getUndoHistory().get(0).getRestoredAtEpochMillis());
        assertEquals(null, session.restoreLastUndo(4_000L));
    }

    @Test
    public void undoHistoryIsBoundedAndRestoreRespectsTransactionCapacity()
    {
        ProfitSession session = new ProfitSession("Test", 0L);
        for (int index = 0; index < 40; index++)
        {
            session.addTransaction(new ProfitTransaction(
                index,
                TransactionType.LOOT,
                TrackingContext.LOOT,
                "Loot",
                true,
                Collections.singletonList(new ItemFlow(index, "Item", 1, 1, 1))), 100);
            session.undoLastTransaction(index + 1_000L);
        }
        assertEquals(32, session.getUndoHistory().size());

        ProfitTransaction first = new ProfitTransaction(
            50_000L, TransactionType.LOOT, TrackingContext.LOOT, "First", true,
            Collections.singletonList(new ItemFlow(50, "First", 1, 10, 10)));
        ProfitTransaction second = new ProfitTransaction(
            51_000L, TransactionType.LOOT, TrackingContext.LOOT, "Second", true,
            Collections.singletonList(new ItemFlow(51, "Second", 1, 20, 20)));
        ProfitSession bounded = new ProfitSession("Bounded", 0L);
        bounded.addTransaction(first, 1);
        bounded.undoLastTransaction(52_000L);
        bounded.addTransaction(second, 1);
        assertEquals(first.getId(), bounded.restoreLastUndo(53_000L).getId());
        assertEquals(1, bounded.getTransactions().size());
        assertEquals(first.getId(), bounded.getTransactions().get(0).getId());
        // Restoring into a full ledger displaces the newer retained row. It
        // must remain in full-session totals through compaction.
        assertEquals(30L, bounded.metrics(54_000L, 60_000L).getNet());
        assertEquals(2, bounded.metrics(54_000L, 60_000L).getTransactionCount());
        assertEquals(1L, bounded.getCompactedTransactionCount());
    }

    @Test
    public void pkEncounterGroupsLootAndRecentSupplyCosts()
    {
        ProfitSession session = new ProfitSession("PK", 0L, SessionMode.PK);
        ProfitTransaction supply = new ProfitTransaction(
            1_000L,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            true,
            Collections.singletonList(new ItemFlow(1, "Food", -1, 100, -100)));
        session.addTransaction(supply, 10);

        PkEncounter encounter = session.addPkEncounter(
            PkEncounterType.KILL,
            2_000L,
            "Player kill",
            ClassificationConfidence.CONFIRMED,
            "RuneLite player loot");
        session.attachRecentCostsToEncounter(encounter.getId(), 2_000L, 10_000L);

        ProfitTransaction loot = new ProfitTransaction(
            2_500L,
            2_500L,
            TransactionType.PK_LOOT,
            TrackingContext.PK_LOOT,
            "PK loot",
            "PKing",
            true,
            Collections.singletonList(new ItemFlow(2, "Loot", 1, 1_000, 1_000)));
        session.addTransaction(loot, 10);
        session.attachTransactionToEncounter(loot.getId(), encounter.getId(), false);

        PkMetrics metrics = session.pkMetrics();
        assertEquals(1, metrics.getKills());
        assertEquals(0, metrics.getDeaths());
        assertEquals(900L, metrics.getNet());
        assertEquals(1, metrics.getEncounterCount());
        assertEquals(900L, metrics.getProfitPerKill());
        assertEquals(900L, metrics.getNetPerEncounter());
        assertTrue(metrics.isCostSplitAvailable());
        assertEquals(100L, metrics.getSuppliesCosts());
        assertEquals(100L, metrics.getSuppliesPerFight());
        assertEquals(0L, metrics.getOtherCosts());
        assertEquals(1, session.getActionCount());
        assertEquals("PKing", session.getActivityHint());
        assertEquals(TransactionType.PK_SUPPLY_COST, supply.getAutomaticType());
    }

    @Test
    public void pkFinancialSummaryTracksCorrectionUndoAndReceiptCompaction()
    {
        ProfitSession session = new ProfitSession("PK", 0L, SessionMode.PK);
        ProfitTransaction food = new ProfitTransaction(1_000L, TransactionType.CONSUMPTION,
            TrackingContext.GENERIC, "Food", true,
            Collections.singletonList(new ItemFlow(1, "Food", -1L, 100, -100L)));
        ProfitTransaction loot = new ProfitTransaction(2_000L, TransactionType.PK_LOOT,
            TrackingContext.PK_LOOT, "PK loot", true,
            Collections.singletonList(new ItemFlow(2, "Loot", 1L, 1_000, 1_000L)));
        session.addTransaction(food, 2);
        session.addTransaction(loot, 2);
        PkEncounter kill = session.addPkEncounter(PkEncounterType.KILL, 2_100L,
            "Player kill", ClassificationConfidence.CONFIRMED, "test");
        session.attachTransactionToEncounter(food.getId(), kill.getId(), true);
        session.attachTransactionToEncounter(loot.getId(), kill.getId(), false);

        assertEquals(900L, session.pkMetrics().getNet());
        assertTrue(session.correctTransaction(loot.getId(), TransactionCorrection.IGNORE,
            3_000L, "Correction regression"));
        assertEquals(-100L, session.pkMetrics().getNet());
        assertTrue(session.undoLastCorrection(4_000L));
        assertEquals(900L, session.pkMetrics().getNet());

        ProfitTransaction fillerOne = new ProfitTransaction(5_000L, TransactionType.ADJUSTMENT,
            TrackingContext.GENERIC, "", false, Collections.emptyList());
        ProfitTransaction fillerTwo = new ProfitTransaction(6_000L, TransactionType.ADJUSTMENT,
            TrackingContext.GENERIC, "", false, Collections.emptyList());
        session.addTransaction(fillerOne, 2);
        session.addTransaction(fillerTwo, 2);

        PkMetrics compacted = session.pkMetrics();
        assertTrue(compacted.isProjectionAvailable());
        assertEquals(900L, compacted.getNet());
        assertEquals(100L, compacted.getSuppliesCosts());
        assertEquals(0L, compacted.getOtherCosts());
        assertTrue(compacted.isCostSplitAvailable());

        Gson gson = new Gson();
        ProfitSession restored = gson.fromJson(gson.toJson(session), ProfitSession.class);
        assertTrue(restored.metrics(7_000L, 60_000L).isCostSplitAvailable());
    }

    @Test
    public void pkStreakIsSignedAndResetsAtEncounterTypeChanges()
    {
        ProfitSession session = new ProfitSession("PK streak", 1L, SessionMode.PK);
        session.addPkEncounter(PkEncounterType.KILL, 10L, "Kill: One",
            ClassificationConfidence.CONFIRMED, "test");
        session.addPkEncounter(PkEncounterType.KILL, 20L, "Kill: Two",
            ClassificationConfidence.CONFIRMED, "test");
        session.addPkEncounter(PkEncounterType.DEATH, 30L, "Player death",
            ClassificationConfidence.CONFIRMED, "test");
        session.addPkEncounter(PkEncounterType.DEATH, 40L, "Player death",
            ClassificationConfidence.CONFIRMED, "test");
        session.addPkEncounter(PkEncounterType.DEATH, 50L, "Player death",
            ClassificationConfidence.CONFIRMED, "test");
        assertEquals(-3, session.pkMetrics().getCurrentStreak());

        session.addPkEncounter(PkEncounterType.KILL, 60L, "Kill: Three",
            ClassificationConfidence.CONFIRMED, "test");
        assertEquals(1, session.pkMetrics().getCurrentStreak());
    }

    @Test
    public void pkEncounterActiveTimeUsesEncounterClockInsteadOfWallClock()
    {
        ProfitSession session = new ProfitSession("PK clock", 1L, SessionMode.PK);
        session.addPkEncounter(PkEncounterType.KILL, 10L, "Kill: One",
            ClassificationConfidence.CONFIRMED, "test");
        session.addPkEncounter(PkEncounterType.KILL, 20L, "Kill: Two",
            ClassificationConfidence.CONFIRMED, "test");
        session.close(31L);

        long activeMillis = session.getAnalyticsDays().stream()
            .mapToLong(TrackingDaySummary::getActiveMillis).sum();
        assertEquals(30L, activeMillis);
        long cursor = new Gson().toJsonTree(session).getAsJsonObject()
            .get("analyticsLastActiveAtEpochMillis").getAsLong();
        session.pkMetrics();
        session.metrics(1_000_000_000_000L, 60_000L);
        session.advanceAnalyticsActiveTime(1_000_000_000_000L);
        assertEquals(cursor, new Gson().toJsonTree(session).getAsJsonObject()
            .get("analyticsLastActiveAtEpochMillis").getAsLong());
        assertEquals(30L, session.getAnalyticsDays().stream()
            .mapToLong(TrackingDaySummary::getActiveMillis).sum());
    }

    @Test
    public void closingPausedSessionDoesNotResumeOrAccrueThePausedInterval()
    {
        ProfitSession session = new ProfitSession("Paused close", 1L, SessionMode.GENERAL);
        session.pause(10L);
        session.close(1_000L);

        assertTrue(session.isPaused());
        assertEquals(9L, session.getAnalyticsDays().stream()
            .mapToLong(TrackingDaySummary::getActiveMillis).sum());
    }

    @Test
    public void delayedActiveTimeEventCannotRewindTheSessionCursor()
    {
        ProfitSession session = new ProfitSession("Clock order", 1L, SessionMode.GENERAL);
        session.advanceAnalyticsActiveTime(100L);
        session.advanceAnalyticsActiveTime(50L);
        session.advanceAnalyticsActiveTime(200L);

        assertEquals(199L, session.getAnalyticsDays().stream()
            .mapToLong(TrackingDaySummary::getActiveMillis).sum());
        assertEquals(200L, new Gson().toJsonTree(session).getAsJsonObject()
            .get("analyticsLastActiveAtEpochMillis").getAsLong());
    }

    @Test
    public void delayedResumeCannotRewindTheSessionCursor()
    {
        ProfitSession session = new ProfitSession("Resume clock order", 1L, SessionMode.GENERAL);
        session.advanceAnalyticsActiveTime(200L);
        session.pause(200L);
        session.resume(150L);
        session.advanceAnalyticsActiveTime(300L);

        assertEquals(299L, session.getAnalyticsDays().stream()
            .mapToLong(TrackingDaySummary::getActiveMillis).sum());
        assertEquals(300L, new Gson().toJsonTree(session).getAsJsonObject()
            .get("analyticsLastActiveAtEpochMillis").getAsLong());
    }

    @Test
    public void pkMediansAverageEvenMiddleValuesWithoutLosingHalfGp()
    {
        ProfitSession session = new ProfitSession("PK median", 1L, SessionMode.PK);
        addPkValue(session, PkEncounterType.KILL, 10L, 100L);
        addPkValue(session, PkEncounterType.KILL, 20L, 101L);
        addPkValue(session, PkEncounterType.DEATH, 30L, -100L);
        addPkValue(session, PkEncounterType.DEATH, 40L, -101L);

        PkMetrics metrics = session.pkMetrics();
        assertTrue(metrics.isMedianKillNetAvailable());
        assertTrue(metrics.isMedianDeathLossAvailable());
        assertEquals(100.5d, metrics.getMedianKillNetGp(), 0.0d);
        assertEquals(100.5d, metrics.getMedianDeathLossGp(), 0.0d);
    }

    private static void addPkValue(ProfitSession session, PkEncounterType type,
        long at, long gp)
    {
        PkEncounter encounter = session.addPkEncounter(type, at,
            type == PkEncounterType.KILL ? "Kill: Rival" : "Player death",
            ClassificationConfidence.CONFIRMED, "test");
        TransactionType transactionType = type == PkEncounterType.KILL
            ? TransactionType.PK_LOOT : TransactionType.PK_DEATH_LOSS;
        ProfitTransaction transaction = new ProfitTransaction(at, transactionType,
            type == PkEncounterType.KILL ? TrackingContext.PK_LOOT : TrackingContext.PK_DEATH,
            "", true, Collections.singletonList(new ItemFlow(1, "PK item",
                gp >= 0L ? 1L : -1L, (int) Math.abs(gp), gp)));
        session.addTransaction(transaction, 20);
        session.attachTransactionToEncounter(transaction.getId(), encounter.getId(), false);
    }

}
