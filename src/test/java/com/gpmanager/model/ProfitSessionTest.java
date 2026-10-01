package com.gpmanager;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProfitSessionTest
{
    @Test
    public void undoRestoresPriorCorrectionReasonAcrossSplitAndReload()
    {
        Session session = new Session("Test", 1_000L);
        Transaction transaction = Tx.of(1_100L, TransactionType.LOOT, Context.LOOT,
            "Loot", true, Collections.singletonList(new Flow(526, "Bones", 4L, 100, 400L)));
        session.addTransaction(transaction, 100);
        assertTrue(session.correctTransaction(transaction.getId(), Correction.REVENUE, 1_200L, "First reason"));
        assertTrue(session.correctTransaction(transaction.getId(), Correction.COST, 1_300L, "Second reason"));
        session = new Gson().fromJson(new Gson().toJson(session), Session.class);
        assertTrue(session.undoLastCorrection(1_400L));
        transaction = session.findTransaction(transaction.getId());
        assertEquals("First reason", transaction.getCorrectionReason());
        assertTrue(session.applyItemSplit(transaction.getId(), 526, 2L, 1_500L, "Sharing"));
        assertTrue(session.undoLastCorrection(1_600L));
        assertEquals("First reason", transaction.getCorrectionReason());
        assertEquals(400L, session.metrics(1_600L).net);
        assertTrue(session.undoLastCorrection(1_700L));
        assertEquals("", transaction.getCorrectionReason());
        assertEquals(Correction.AUTO, transaction.getCorrection());
    }

    @Test
    public void undoSkipsPartialRecoveryThatDidNotChangeTheReason()
    {
        Session session = new Session("Test", 1_000L);
        Transaction transaction = Tx.of(1_100L, TransactionType.CONSUMPTION, Context.GENERIC,
            "Own drop", true, Collections.singletonList(new Flow(526, "Bones", -4L, 100, -400L)));
        session.addTransaction(transaction, 100);
        assertTrue(session.correctTransaction(transaction.getId(), Correction.COST, 1_200L, "Original decision"));
        assertTrue(session.recoverOwnDropCosts(transaction.getId(), 526, 1L, 1_300L, "Partial recovery"));
        assertTrue(session.correctTransaction(transaction.getId(), Correction.REVENUE, 1_400L, "Later decision"));
        session = new Gson().fromJson(new Gson().toJson(session), Session.class);
        assertTrue(session.undoLastCorrection(1_500L));
        transaction = session.findTransaction(transaction.getId());
        assertEquals("Original decision", transaction.getCorrectionReason());
        assertEquals(-300L, session.metrics(1_500L).net);
        assertTrue(session.undoLastCorrection(1_600L));
        assertEquals("Original decision", transaction.getCorrectionReason());
        assertEquals(-400L, session.metrics(1_600L).net);
    }

    @Test
    public void undoRestoresTheLatestReasonWhenTheCorrectionEnumWasUnchanged()
    {
        Session session = new Session("Test", 1_000L);
        Transaction transaction = Tx.of(1_100L, TransactionType.LOOT, Context.LOOT,
            "Loot", true, Collections.singletonList(new Flow(526, "Bones", 1L, 100, 100L)));
        session.addTransaction(transaction, 100);
        assertTrue(session.correctTransaction(transaction.getId(), Correction.REVENUE, 1_200L, "A"));
        assertTrue(session.correctTransaction(transaction.getId(), Correction.REVENUE, 1_300L, "B"));
        assertTrue(session.correctTransaction(transaction.getId(), Correction.COST, 1_400L, "C"));
        session = new Gson().fromJson(new Gson().toJson(session), Session.class);
        assertTrue(session.undoLastCorrection(1_500L));
        transaction = session.findTransaction(transaction.getId());
        assertEquals("B", transaction.getCorrectionReason());
        assertTrue(session.undoLastCorrection(1_600L));
        assertEquals("A", transaction.getCorrectionReason());
        assertEquals(100L, session.metrics(1_600L).net);
    }

    @Test
    public void profitTargetDoesNotAlterSessionAccounting()
    {
        Session session = new Session("General", 0L, SessionMode.AUTO);
        session.addTransaction(Tx.of(1L, TransactionType.LOOT, Context.LOOT, "loot", true,
            Collections.singletonList(new Flow(995, "Coins", 100, 1, 100))), 10);
        long net = session.metrics(2L).net;
        assertTrue(session.setProfitTargetGp(1_000L));
        assertEquals(net, session.metrics(2L).net);
        session.setProfitTargetGp(null);
        assertEquals(net, session.metrics(2L).net);
    }

    @Test
    public void calculatesRevenueCostsNetAndRate()
    {
        long start = 1_000_000L;
        Session session = new Session("Bossing", start);

        session.addTransaction(
            Tx.of(
                start + 1_000L,
                TransactionType.LOOT,
                Context.LOOT,
                "Loot",
                true,
                Collections.singletonList(new Flow(1, "Drop", 1, 1_000, 1_000))),
            100);

        Transaction food = Tx.of(
            start + 2_000L,
            TransactionType.CONSUMPTION,
            Context.GENERIC,
            "Supplies",
            true,
            Collections.singletonList(new Flow(2, "Food", -2, 100, -200)));
        food.setActionKind(ActionKind.EAT);
        session.addTransaction(food, 100);

        SessionMetrics metrics = session.metrics(start + 3_600_000L);

        assertEquals(1_000L, metrics.revenue);
        assertEquals(200L, metrics.costs);
        assertEquals(800L, metrics.net);
        assertEquals(200L, metrics.suppliesCosts);
        assertEquals(0L, metrics.otherCosts);
        assertTrue(metrics.costSplitAvailable);
        assertEquals(metrics.costs, metrics.suppliesCosts + metrics.otherCosts);
        assertEquals(800L, metrics.profitPerHour);
        assertEquals(2, SessionCounts.counted(session));
    }

    @Test
    public void pausedTimeIsExcluded()
    {
        Session session = new Session("Test", 1_000L);
        session.pause(2_000L, PauseReason.MANUAL);
        session.resume(12_000L);

        assertEquals(2_000L, session.getElapsedMillis(13_000L));
    }

    @Test
    public void boundsTransactionHistory()
    {
        Session session = new Session("Test", 0L);
        for (int index = 0; index < 5; index++)
        {
            session.addTransaction(
                Tx.of(
                    index,
                    TransactionType.GAIN,
                    Context.GENERIC,
                    "",
                    true,
                    Arrays.asList(new Flow(index, "Item", 1, 1, 1))),
                3);
        }

        assertEquals(3, session.getTransactions().size());
        assertTrue(session.getTransactions().get(0).timestampEpochMillis >= 2L);
        assertEquals(5L, session.metrics(5_000L).net);
        assertEquals(5, SessionCounts.counted(session));
        assertEquals(2L, session.compactedTransactionCount);
    }

    @Test
    public void compactionPreservesCorrectedTotalsAcrossReloadAndUndo()
    {
        Session session = new Session("Long session", 0L);
        Transaction corrected = Tx.of(1L, TransactionType.UNCERTAIN,
            Context.GENERIC, "Review", false,
            Collections.singletonList(new Flow(1, "Loot", 1, 100, 100)));
        session.addTransaction(corrected, 1);
        session.correctTransaction(corrected.getId(), Correction.REVENUE, 2L, "Manual correction");
        session.addTransaction(Tx.of(3L, TransactionType.CONSUMPTION,
            Context.GENERIC, "Cost", true,
            Collections.singletonList(new Flow(2, "Food", -1, 25, -25))), 1);
        session.addTransaction(Tx.of(4L, TransactionType.TRANSFER,
            Context.TRANSFER, "Bank", false,
            Collections.singletonList(new Flow(3, "Coins", 1000, 1, 1000))), 1);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        Session restored = gson.fromJson(gson.toJson(session), Session.class);
        assertEquals(75L, restored.metrics(5L).net);
        assertEquals(100L, restored.getCompactedRevenue());
        assertEquals(25L, restored.getCompactedCosts());
        restored.undoLastTransaction(6L);
        assertEquals(75L, restored.metrics(7L).net);
        restored.restoreLastUndo(8L);
        assertEquals(75L, restored.metrics(9L).net);
        assertEquals(1, SessionCounts.transfers(restored));
    }

    @Test
    public void correctedCostCompactionKeepsEffectiveSessionAmounts()
    {
        Session session = new Session("Corrected compaction", 0L);
        Transaction correctedGain = Tx.of(1L, TransactionType.GAIN,
            Context.GENERIC, "Manual cost", true,
            Collections.singletonList(new Flow(1, "Item", 1, 50, 50L)));
        session.addTransaction(correctedGain, 1);
        assertTrue(session.correctTransaction(correctedGain.getId(), Correction.COST,
            2L, "Owner correction"));
        session.addTransaction(Tx.of(3L, TransactionType.LOOT,
            Context.LOOT, "Later gain", true,
            Collections.singletonList(new Flow(2, "Later item", 1, 1, 1L))), 1);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        session = gson.fromJson(gson.toJson(session), Session.class);
        SessionMetrics metrics = session.metrics(5L);
        assertEquals(1L, metrics.revenue);
        assertEquals(50L, metrics.costs);
        assertEquals(0L, metrics.suppliesCosts);
        assertEquals(50L, metrics.otherCosts);
        assertTrue(metrics.costSplitAvailable);

        // The limit of one already folded the corrected row by its effective (corrected) contribution;
        // compacting the retained gain keeps the archive exact.
        assertEquals(1, session.compactTransactionsBefore(10L, transaction -> false));
        assertTrue(session.getTransactions().isEmpty());
        SessionMetrics compacted = session.metrics(5L);
        assertEquals(1L, compacted.revenue);
        assertEquals(50L, compacted.costs);
        assertEquals(0L, compacted.suppliesCosts);
        assertEquals(50L, compacted.otherCosts);
        assertTrue(compacted.costSplitAvailable);
    }

    @Test
    public void suppliesLossSplitSurvivesSerializationAndCompaction()
    {
        Session session = new Session("Costs", 0L);
        session.addTransaction(cost(TransactionType.CONSUMPTION, "Food", 1, -100L), 1);
        session.addTransaction(cost(TransactionType.TRADE, "GE buy", 2, -200L), 1);
        session.addTransaction(cost(TransactionType.PK_DEATH_LOSS, "Death", 3, -300L), 1);
        session.addTransaction(cost(TransactionType.CONSUMPTION, "Death reclaim", 4, -25L), 1);
        session.addTransaction(cost(TransactionType.CONSUMPTION, "Food", 5, -50L), 1);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        session = gson.fromJson(gson.toJson(session), Session.class);

        SessionMetrics metrics = session.metrics(20_000L);
        assertEquals(675L, metrics.costs);
        assertEquals(150L, metrics.suppliesCosts);
        assertEquals(525L, metrics.otherCosts);
        assertTrue(metrics.costSplitAvailable);
        assertEquals(metrics.costs, metrics.suppliesCosts + metrics.otherCosts);

        // The limit of one already archived four rows; folding the retained one keeps the split exact.
        assertEquals(1, session.getTransactions().size());
        assertEquals(1, session.compactTransactionsBefore(6L, transaction -> false));
        assertEquals(0, session.getTransactions().size());
        SessionMetrics mixed = gson.fromJson(gson.toJson(session), Session.class).metrics(20_000L);
        assertEquals(675L, mixed.costs);
        assertEquals(150L, mixed.suppliesCosts);
        assertEquals(525L, mixed.otherCosts);
        assertTrue(mixed.costSplitAvailable);
    }

    private static Transaction cost(TransactionType type, String activity, int itemId, long value)
    {
        Transaction transaction = Tx.of(itemId, null, type, Context.GENERIC,
            activity, activity, true,
            Collections.singletonList(new Flow(itemId, activity, -1L,
                (int) Math.abs(value), value)));
        if (type == TransactionType.CONSUMPTION && "Food".equals(activity)) transaction.setActionKind(ActionKind.EAT);
        return transaction;
    }

    @Test
    public void pausedRatesRemainFrozenAndResumeOnActiveTime()
    {
        Session session = new Session("Test", 0L);
        session.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                Context.GENERIC,
                "",
                true,
                Arrays.asList(new Flow(1, "Item", 1, 1_000, 1_000))),
            10);

        session.pause(2_000L, PauseReason.MANUAL);
        SessionMetrics atPause = session.metrics(2_000L);
        SessionMetrics muchLater = session.metrics(62_000L);

        assertEquals(atPause.elapsedMillis, muchLater.elapsedMillis);
        assertEquals(atPause.profitPerHour, muchLater.profitPerHour);

        session.resume(62_000L);
        SessionMetrics afterOneActiveSecond = session.metrics(63_000L);
        assertEquals(3_000L, afterOneActiveSecond.elapsedMillis);
        assertEquals(1_200_000L, afterOneActiveSecond.profitPerHour);
    }

    @Test
    public void manualCorrectionsAreReversibleAndAudited()
    {
        Session session = new Session("Test", 0L);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.CONSUMPTION,
            Context.GENERIC,
            "",
            true,
            Collections.singletonList(new Flow(1, "Item", -1, 500, -500)));
        session.addTransaction(transaction, 10);

        session.correctTransaction(transaction.getId(), Correction.REVENUE, 2_000L, "Manual correction");
        assertEquals(500L, session.metrics(3_000L).net);
        assertEquals(1, ModelProbe.activeCorrections(session).size());
        assertEquals("Manual correction", transaction.getCorrectionReason());
        assertEquals("Manual correction", ModelProbe.activeCorrections(session).get(0).getReason());

        session.correctTransaction(transaction.getId(), Correction.AUTO, 4_000L, "Rechecked against loot timeline");
        assertEquals(-500L, session.metrics(5_000L).net);
        assertEquals(2, ModelProbe.activeCorrections(session).size());
        assertEquals("Rechecked against loot timeline", transaction.getCorrectionReason());
        assertEquals("Rechecked against loot timeline", ModelProbe.activeCorrections(session).get(1).getReason());
    }

    @Test
    public void undoCorrectionPreservesAppliedRecordAndUndoLinkAcrossPersistence()
    {
        Session session = new Session("Test", 0L);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.CONSUMPTION,
            Context.GENERIC,
            "",
            true,
            Collections.singletonList(new Flow(1, "Item", -1, 500, -500)));
        session.addTransaction(transaction, 10);
        session.correctTransaction(transaction.getId(), Correction.REVENUE, 2_000L, "Audit fix");

        assertTrue(session.undoLastCorrection(3_000L));
        assertEquals(-500L, session.metrics(4_000L).net);
        assertEquals(0, ModelProbe.activeCorrections(session).size());
        assertEquals(1, session.correctionHistory.size());
        CorrectionRecord record = session.correctionHistory.get(0);
        assertTrue(record.isUndone());
        assertTrue(record.undoneAtEpochMillis == 3_000L);
        assertTrue(!record.getRecordId().isEmpty());
        assertTrue(!record.getUndoId().isEmpty());

        Session restored = new Gson().fromJson(new Gson().toJson(session), Session.class);
        assertEquals(1, restored.correctionHistory.size());
        assertTrue(restored.correctionHistory.get(0).isUndone());
        assertEquals(record.getRecordId(), restored.correctionHistory.get(0).getRecordId());
        assertEquals(record.getUndoId(), restored.correctionHistory.get(0).getUndoId());
    }

    /** A one-change record written before batch records (top-level fields, no changes) still loads and undoes. */
    @Test
    public void legacyOneChangeSplitRecordStillUndoes()
    {
        Session session = new Session("Test", 0L);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.GAIN,
            Context.GENERIC,
            "",
            true,
            Collections.singletonList(new Flow(1, "Item", 10, 100, 1_000)));
        session.addTransaction(transaction, 10);
        assertTrue(session.applyItemSplit(transaction.getId(), 1, 4, 2_000L, null));
        assertEquals(400L, session.metrics(3_000L).net);

        Gson gson = new Gson();
        com.google.gson.JsonObject json = gson.toJsonTree(session).getAsJsonObject();
        com.google.gson.JsonObject record = json.getAsJsonArray("correctionHistory").get(0).getAsJsonObject();
        com.google.gson.JsonObject change = record.remove("changes").getAsJsonArray().get(0).getAsJsonObject();
        for (String field : change.keySet())
        {
            record.add(field, change.get(field));
        }

        Session restored = gson.fromJson(json, Session.class);
        assertTrue(restored.undoLastCorrection(4_000L));
        assertEquals("the legacy flow snapshot restores the full gain", 1_000L,
            restored.metrics(5_000L).net);
        assertEquals(10L, restored.findTransaction(transaction.getId()).quantity(1, true));
    }

    @Test
    public void undoWalksBackToPreviousActiveCorrection()
    {
        Session session = new Session("Test", 0L);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.CONSUMPTION,
            Context.GENERIC,
            "",
            true,
            Collections.singletonList(new Flow(1, "Item", -1, 500, -500)));
        session.addTransaction(transaction, 10);
        session.correctTransaction(transaction.getId(), Correction.REVENUE, 2_000L, "First");
        session.correctTransaction(transaction.getId(), Correction.IGNORE, 3_000L, "Second");

        assertTrue(session.undoLastCorrection(4_000L));
        assertEquals(Correction.REVENUE, transaction.getCorrection());
        assertTrue(session.undoLastCorrection(5_000L));
        assertEquals(Correction.AUTO, transaction.getCorrection());
        assertEquals(0, ModelProbe.activeCorrections(session).size());
        assertEquals(2, session.correctionHistory.size());
    }

    @Test
    public void partialAndFullOwnDropRecoveryAreUndoable()
    {
        Session session = new Session("Test", 0L);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.PK_DEATH_LOSS,
            Context.PK_DEATH,
            "Death loss",
            true,
            Collections.singletonList(new Flow(1, "Item", -4, 250, -1_000)));
        session.addTransaction(transaction, 10);

        assertTrue(session.recoverOwnDropCosts(transaction.getId(), 1, 2L, 2_000L, "Partial reclaim"));
        assertEquals(-500L, session.metrics(3_000L).net);
        assertTrue(session.undoLastCorrection(4_000L));
        assertEquals(-1_000L, session.metrics(5_000L).net);

        assertTrue(session.recoverOwnDropCosts(transaction.getId(), 1, 4L, 6_000L, "Full reclaim"));
        assertEquals(0L, session.metrics(7_000L).net);
        assertTrue(session.undoLastCorrection(8_000L));
        assertEquals(-1_000L, session.metrics(9_000L).net);
    }

    @Test
    public void undoActionsAreAuditedWithTransactionMetadata()
    {
        Session session = new Session("Test", 0L);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.LOOT,
            Context.LOOT,
            "Loot",
            true,
            Collections.singletonList(new Flow(1, "Item", 1, 250, 250)));
        session.addTransaction(transaction, 10);

        Transaction removed = session.undoLastTransaction(2_000L);
        assertEquals(transaction.getId(), removed.getId());
        assertEquals(1, session.undoHistory.size());
        assertEquals("General", session.undoHistory.get(0).getActivityName());
        assertEquals(250L, session.undoHistory.get(0).net);
        assertEquals(2_000L, session.undoHistory.get(0).timestampEpochMillis);
    }

    @Test
    public void latestUndoCanBeRestoredWithoutLosingAuditRecord()
    {
        Session session = new Session("Test", 0L);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.LOOT,
            Context.LOOT,
            "Loot",
            true,
            Collections.singletonList(new Flow(1, "Item", 1, 250, 250)));
        session.addTransaction(transaction, 10);
        session.undoLastTransaction(2_000L);

        Transaction restored = session.restoreLastUndo(3_000L);
        assertEquals(transaction.getId(), restored.getId());
        assertEquals(1, session.getTransactions().size());
        assertTrue(session.undoHistory.get(0).restored);
        assertEquals(3_000L, session.undoHistory.get(0).restoredAtEpochMillis);
        assertEquals(null, session.restoreLastUndo(4_000L));
    }

    @Test
    public void undoHistoryIsBoundedAndRestoreRespectsTransactionCapacity()
    {
        Session session = new Session("Test", 0L);
        for (int index = 0; index < 40; index++)
        {
            session.addTransaction(Tx.of(
                index,
                TransactionType.LOOT,
                Context.LOOT,
                "Loot",
                true,
                Collections.singletonList(new Flow(index, "Item", 1, 1, 1))), 100);
            session.undoLastTransaction(index + 1_000L);
        }
        assertEquals(32, session.undoHistory.size());

        Transaction first = Tx.of(
            50_000L, TransactionType.LOOT, Context.LOOT, "First", true,
            Collections.singletonList(new Flow(50, "First", 1, 10, 10)));
        Transaction second = Tx.of(
            51_000L, TransactionType.LOOT, Context.LOOT, "Second", true,
            Collections.singletonList(new Flow(51, "Second", 1, 20, 20)));
        Session bounded = new Session("Bounded", 0L);
        bounded.addTransaction(first, 1);
        bounded.undoLastTransaction(52_000L);
        bounded.addTransaction(second, 1);
        assertEquals(first.getId(), bounded.restoreLastUndo(53_000L).getId());
        assertEquals(1, bounded.getTransactions().size());
        assertEquals(first.getId(), bounded.getTransactions().get(0).getId());
        // Restoring into a full ledger displaces the newer retained row. It
        // must remain in full-session totals through compaction.
        assertEquals(30L, bounded.metrics(54_000L).net);
        assertEquals(2, SessionCounts.counted(bounded));
        assertEquals(1L, bounded.compactedTransactionCount);
    }

    @Test
    public void pkEncounterGroupsLootAndRecentSupplyCosts()
    {
        Session session = new Session("PK", 0L, SessionMode.PK);
        Transaction supply = Tx.of(
            1_000L,
            TransactionType.CONSUMPTION,
            Context.GENERIC,
            "",
            true,
            Collections.singletonList(new Flow(1, "Food", -1, 100, -100)));
        session.addTransaction(supply, 10);

        PkEncounter encounter = session.addPkEncounter(
            EncounterType.KILL,
            2_000L,
            "Player kill",
            ClassificationConfidence.CONFIRMED,
            "RuneLite player loot");
        session.attachRecentCostsToEncounter(encounter.getId(), 2_000L, 10_000L);

        Transaction loot = Tx.of(
            2_500L,
            2_500L,
            TransactionType.PK_LOOT,
            Context.PK_LOOT,
            "PK loot",
            "PKing",
            true,
            Collections.singletonList(new Flow(2, "Loot", 1, 1_000, 1_000)));
        session.addTransaction(loot, 10);
        session.attachTransactionToEncounter(loot.getId(), encounter.getId(), false);

        PkMetrics metrics = session.pkMetrics();
        assertEquals(1, metrics.kills);
        assertEquals(0, metrics.deaths);
        assertEquals(900L, metrics.net);
        assertEquals(1, metrics.getEncounterCount());
        assertEquals(900L, metrics.totalKillNet / metrics.kills);
        assertEquals(900L, metrics.net / metrics.getEncounterCount());
        assertTrue(metrics.costSplitAvailable);
        assertEquals(100L, metrics.suppliesCosts);
        assertEquals(100L, metrics.suppliesCosts / metrics.getEncounterCount());
        assertEquals(0L, metrics.otherCosts);
        assertEquals(1, session.actionCount);
        assertEquals("PKing", session.getActivityHint());
        assertEquals(TransactionType.PK_SUPPLY_COST, supply.getAutomaticType());
    }

    @Test
    public void pkFinancialSummaryTracksCorrectionUndoAndReceiptCompaction()
    {
        Session session = new Session("PK", 0L, SessionMode.PK);
        Transaction food = Tx.of(1_000L, TransactionType.CONSUMPTION,
            Context.GENERIC, "Food", true,
            Collections.singletonList(new Flow(1, "Food", -1L, 100, -100L)));
        Transaction loot = Tx.of(2_000L, TransactionType.PK_LOOT,
            Context.PK_LOOT, "PK loot", true,
            Collections.singletonList(new Flow(2, "Loot", 1L, 1_000, 1_000L)));
        session.addTransaction(food, 2);
        session.addTransaction(loot, 2);
        PkEncounter kill = session.addPkEncounter(EncounterType.KILL, 2_100L,
            "Player kill", ClassificationConfidence.CONFIRMED, "test");
        session.attachTransactionToEncounter(food.getId(), kill.getId(), true);
        session.attachTransactionToEncounter(loot.getId(), kill.getId(), false);

        assertEquals(900L, session.pkMetrics().net);
        assertTrue(session.correctTransaction(loot.getId(), Correction.IGNORE,
            3_000L, "Correction regression"));
        assertEquals(-100L, session.pkMetrics().net);
        assertTrue(session.undoLastCorrection(4_000L));
        assertEquals(900L, session.pkMetrics().net);

        Transaction fillerOne = Tx.of(5_000L, TransactionType.ADJUSTMENT,
            Context.GENERIC, "", false, Collections.emptyList());
        Transaction fillerTwo = Tx.of(6_000L, TransactionType.ADJUSTMENT,
            Context.GENERIC, "", false, Collections.emptyList());
        session.addTransaction(fillerOne, 2);
        session.addTransaction(fillerTwo, 2);

        PkMetrics compacted = session.pkMetrics();
        assertEquals(900L, compacted.net);
        assertEquals(100L, compacted.suppliesCosts);
        assertEquals(0L, compacted.otherCosts);
        assertTrue(compacted.costSplitAvailable);

        Gson gson = new Gson();
        Session restored = gson.fromJson(gson.toJson(session), Session.class);
        assertTrue(restored.metrics(7_000L).costSplitAvailable);
    }

    @Test
    public void pkStreakIsSignedAndResetsAtEncounterTypeChanges()
    {
        Session session = new Session("PK streak", 1L, SessionMode.PK);
        session.addPkEncounter(EncounterType.KILL, 10L, "Kill: One",
            ClassificationConfidence.CONFIRMED, "test");
        session.addPkEncounter(EncounterType.KILL, 20L, "Kill: Two",
            ClassificationConfidence.CONFIRMED, "test");
        session.addPkEncounter(EncounterType.DEATH, 30L, "Player death",
            ClassificationConfidence.CONFIRMED, "test");
        session.addPkEncounter(EncounterType.DEATH, 40L, "Player death",
            ClassificationConfidence.CONFIRMED, "test");
        session.addPkEncounter(EncounterType.DEATH, 50L, "Player death",
            ClassificationConfidence.CONFIRMED, "test");
        assertEquals(-3, session.pkMetrics().currentStreak);

        session.addPkEncounter(EncounterType.KILL, 60L, "Kill: Three",
            ClassificationConfidence.CONFIRMED, "test");
        assertEquals(1, session.pkMetrics().currentStreak);
    }

    @Test
    public void pkMediansAverageEvenMiddleValuesWithoutLosingHalfGp()
    {
        Session session = new Session("PK median", 1L, SessionMode.PK);
        addPkValue(session, EncounterType.KILL, 10L, 100L);
        addPkValue(session, EncounterType.KILL, 20L, 101L);
        addPkValue(session, EncounterType.DEATH, 30L, -100L);
        addPkValue(session, EncounterType.DEATH, 40L, -101L);

        PkMetrics metrics = session.pkMetrics();
        assertTrue(metrics.medianKillNetGp != null);
        assertTrue(metrics.medianDeathLossGp != null);
        assertEquals(100.5d, metrics.medianKillNetGp, 0.0d);
        assertEquals(100.5d, metrics.medianDeathLossGp, 0.0d);
    }

    private static void addPkValue(Session session, EncounterType type,
        long at, long gp)
    {
        PkEncounter encounter = session.addPkEncounter(type, at,
            type == EncounterType.KILL ? "Kill: Rival" : "Player death",
            ClassificationConfidence.CONFIRMED, "test");
        TransactionType transactionType = type == EncounterType.KILL
            ? TransactionType.PK_LOOT : TransactionType.PK_DEATH_LOSS;
        Transaction transaction = Tx.of(at, transactionType,
            type == EncounterType.KILL ? Context.PK_LOOT : Context.PK_DEATH,
            "", true, Collections.singletonList(new Flow(1, "PK item",
                gp >= 0L ? 1L : -1L, (int) Math.abs(gp), gp)));
        session.addTransaction(transaction, 20);
        session.attachTransactionToEncounter(transaction.getId(), encounter.getId(), false);
    }

    @Test
    public void closingPausedSessionDoesNotResumeOrAccrueThePausedInterval()
    {
        Session session = new Session("Paused close", 1L, SessionMode.GENERAL);
        session.pause(10L, PauseReason.MANUAL);
        session.close(1_000L);

        assertTrue(session.paused);
        assertEquals(9L, session.getElapsedMillis(1_000L));
    }
}
