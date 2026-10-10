package com.gpmanager;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
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
        assertTrue(session.undoCorrection(1_400L));
        transaction = session.findTransaction(transaction.getId());
        assertEquals("First reason", transaction.correctionReason());
        assertTrue(session.splitItem(transaction.getId(), 526, 2L, 1_500L, "Sharing"));
        assertTrue(session.undoCorrection(1_600L));
        assertEquals("First reason", transaction.correctionReason());
        assertEquals(400L, session.metrics(1_600L).net);
        assertTrue(session.undoCorrection(1_700L));
        assertEquals("", transaction.correctionReason());
        assertEquals(Correction.AUTO, transaction.getCorrection());
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
        assertTrue(session.undoCorrection(1_500L));
        transaction = session.findTransaction(transaction.getId());
        assertEquals("B", transaction.correctionReason());
        assertTrue(session.undoCorrection(1_600L));
        assertEquals("A", transaction.correctionReason());
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

        Metrics metrics = session.metrics(start + 3_600_000L);

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
        assertEquals("three rows held, two compacted", 3, SessionCounts.counted(session));
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
        assertEquals(100L, restored.compactedRevenue());
        assertEquals(25L, restored.compactedCosts());
        restored.undoTransaction();
        assertEquals(75L, restored.metrics(7L).net);
        restored.restoreUndo();
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
        Metrics metrics = session.metrics(5L);
        assertEquals(1L, metrics.revenue);
        assertEquals(50L, metrics.costs);
        assertEquals(0L, metrics.suppliesCosts);
        assertEquals(50L, metrics.otherCosts);
        assertTrue(metrics.costSplitAvailable);

        // The limit of one already folded the corrected row by its effective (corrected) contribution;
        // compacting the retained gain keeps the archive exact.
        assertEquals(1, session.compactBefore(10L, transaction -> false));
        assertTrue(session.getTransactions().isEmpty());
        Metrics compacted = session.metrics(5L);
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

        Metrics metrics = session.metrics(20_000L);
        assertEquals(675L, metrics.costs);
        assertEquals(150L, metrics.suppliesCosts);
        assertEquals(525L, metrics.otherCosts);
        assertTrue(metrics.costSplitAvailable);
        assertEquals(metrics.costs, metrics.suppliesCosts + metrics.otherCosts);

        // The limit of one already archived four rows; folding the retained one keeps the split exact.
        assertEquals(1, session.getTransactions().size());
        assertEquals(1, session.compactBefore(6L, transaction -> false));
        assertEquals(0, session.getTransactions().size());
        Metrics mixed = gson.fromJson(gson.toJson(session), Session.class).metrics(20_000L);
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
        Metrics atPause = session.metrics(2_000L);
        Metrics muchLater = session.metrics(62_000L);

        assertEquals(atPause.elapsedMillis, muchLater.elapsedMillis);
        assertEquals(atPause.profitPerHour, muchLater.profitPerHour);

        session.resume(62_000L);
        Metrics afterOneActiveSecond = session.metrics(63_000L);
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
        assertEquals("Manual correction", transaction.correctionReason());
        assertEquals("Manual correction", ModelProbe.activeCorrections(session).get(0).getReason());

        session.correctTransaction(transaction.getId(), Correction.AUTO, 4_000L, "Rechecked against loot timeline");
        assertEquals(-500L, session.metrics(5_000L).net);
        assertEquals(2, ModelProbe.activeCorrections(session).size());
        assertEquals("Rechecked against loot timeline", transaction.correctionReason());
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

        assertTrue(session.undoCorrection(3_000L));
        assertEquals(-500L, session.metrics(4_000L).net);
        assertEquals(0, ModelProbe.activeCorrections(session).size());
        assertEquals(1, session.correctionHistory.size());
        CorrectionRecord record = session.correctionHistory.get(0);
        assertTrue(record.isUndone());

        Session restored = new Gson().fromJson(new Gson().toJson(session), Session.class);
        assertEquals(1, restored.correctionHistory.size());
        assertTrue(restored.correctionHistory.get(0).isUndone());
        assertFalse("an undone decision is never undone twice", restored.undoCorrection(5_000L));
    }

    /** A saved split decision reloads and undoes back to the full gain. */
    @Test
    public void savedSplitRecordStillUndoes()
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
        assertTrue(session.splitItem(transaction.getId(), 1, 4, 2_000L, null));
        assertEquals(400L, session.metrics(3_000L).net);

        Gson gson = new Gson();
        Session restored = gson.fromJson(gson.toJson(session), Session.class);
        assertTrue(restored.undoCorrection(4_000L));
        assertEquals("the saved flow snapshot restores the full gain", 1_000L,
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

        assertTrue(session.undoCorrection(4_000L));
        assertEquals(Correction.REVENUE, transaction.getCorrection());
        assertTrue(session.undoCorrection(5_000L));
        assertEquals(Correction.AUTO, transaction.getCorrection());
        assertEquals(0, ModelProbe.activeCorrections(session).size());
        assertEquals(2, session.correctionHistory.size());
    }


    @Test
    public void undoKeepsTheRemovedTransaction()
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

        Transaction removed = session.undoTransaction();
        assertEquals(transaction.getId(), removed.getId());
        assertEquals(1, session.undoHistory.size());
        assertSame("the removed row is kept whole for a restore", transaction,
            session.undoHistory.get(0).transaction);
        assertEquals(0L, session.metrics(3_000L).net);
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
        session.undoTransaction();

        Transaction restored = session.restoreUndo();
        assertEquals(transaction.getId(), restored.getId());
        assertEquals(1, session.getTransactions().size());
        assertTrue(session.undoHistory.get(0).restored);
        assertEquals(null, session.restoreUndo());
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
            session.undoTransaction();
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
        bounded.undoTransaction();
        bounded.addTransaction(second, 1);
        assertEquals(first.getId(), bounded.restoreUndo().getId());
        assertEquals(1, bounded.getTransactions().size());
        assertEquals(first.getId(), bounded.getTransactions().get(0).getId());
        // Restoring into a full ledger displaces the newer retained row. It
        // must remain in full-session totals through compaction.
        assertEquals(30L, bounded.metrics(54_000L).net);
        assertEquals("one row held, one compacted", 1, SessionCounts.counted(bounded));
        assertEquals(1L, bounded.compactedTransactionCount);
    }

    @Test
    public void pkEncounterGroupsLootAndRecentSupplyCosts()
    {
        Session session = new Session("PK", 0L, SessionMode.AUTO);
        Transaction supply = Tx.of(
            1_000L,
            TransactionType.CONSUMPTION,
            Context.GENERIC,
            "",
            true,
            Collections.singletonList(new Flow(1, "Food", -1, 100, -100)));
        session.addTransaction(supply, 10);

        String encounter = session.addEncounter(true, 2_000L);
        session.attachRecentCosts(encounter, 2_000L, 10_000L);

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
        session.attachToEncounter(loot.getId(), encounter, false);

        assertEquals(1, session.pk().kills);
        assertEquals(0, session.pk().deaths);
        assertEquals("loot less the attached food", 900L, session.bestKill());
        assertEquals("PKing", session.getActivityHint());
        assertEquals(TransactionType.PK_SUPPLY_COST, supply.autoType());
    }

    @Test
    public void pkFinancialSummaryTracksCorrectionUndoAndReceiptCompaction()
    {
        Session session = new Session("PK", 0L, SessionMode.AUTO);
        Transaction food = Tx.of(1_000L, TransactionType.CONSUMPTION,
            Context.GENERIC, "Food", true,
            Collections.singletonList(new Flow(1, "Food", -1L, 100, -100L)));
        Transaction loot = Tx.of(2_000L, TransactionType.PK_LOOT,
            Context.PK_LOOT, "PK loot", true,
            Collections.singletonList(new Flow(2, "Loot", 1L, 1_000, 1_000L)));
        session.addTransaction(food, 2);
        session.addTransaction(loot, 2);
        String kill = session.addEncounter(true, 2_100L);
        session.attachToEncounter(food.getId(), kill, true);
        session.attachToEncounter(loot.getId(), kill, false);

        assertEquals(900L, session.bestKill());
        assertTrue(session.correctTransaction(loot.getId(), Correction.IGNORE,
            3_000L, "Correction regression"));
        assertEquals("a losing kill is never the best", 0L, session.bestKill());
        assertTrue(session.undoCorrection(4_000L));
        assertEquals(900L, session.bestKill());

        Transaction fillerOne = Tx.of(5_000L, TransactionType.ADJUSTMENT,
            Context.GENERIC, "", false, Collections.emptyList());
        Transaction fillerTwo = Tx.of(6_000L, TransactionType.ADJUSTMENT,
            Context.GENERIC, "", false, Collections.emptyList());
        session.addTransaction(fillerOne, 2);
        session.addTransaction(fillerTwo, 2);

        assertEquals("compaction keeps the kill", 900L, session.bestKill());
        assertTrue(session.pk().compactedKillNet.isEmpty());

        Gson gson = new Gson();
        Session restored = gson.fromJson(gson.toJson(session), Session.class);
        assertTrue(restored.metrics(7_000L).costSplitAvailable);
        assertEquals(900L, restored.bestKill());
        assertEquals(1, restored.pk().kills);
    }

    @Test
    public void pkStreakIsSignedAndResetsAtEncounterTypeChanges()
    {
        Session session = new Session("PK streak", 1L, SessionMode.AUTO);
        session.addEncounter(true, 10L);
        session.addEncounter(true, 20L);
        session.addEncounter(false, 30L);
        session.addEncounter(false, 40L);
        session.addEncounter(false, 50L);
        assertEquals(-3, session.pk().streak);

        session.addEncounter(true, 60L);
        assertEquals(1, session.pk().streak);
        assertEquals(2, session.pk().bestStreak);
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
