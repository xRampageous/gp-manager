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
        Session session = new Session("Test", 1_000L, SessionMode.GENERAL);
        Transaction transaction = Tx.of(1_100L, TransactionType.LOOT, Context.LOOT,
            "Loot", true, Collections.singletonList(new Flow(526, "Bones", 4L, 100, 400L)));
        session.addTransaction(transaction, 100, false);
        assertTrue(session.correctTransaction(transaction.id(), Correction.REVENUE, "First reason"));
        assertTrue(session.correctTransaction(transaction.id(), Correction.COST, "Second reason"));
        session = new Gson().fromJson(new Gson().toJson(session), Session.class);
        assertTrue(session.undoCorrection());
        transaction = session.findTransaction(transaction.id());
        assertEquals("First reason", transaction.correctionReason());
        assertTrue(session.splitItem(transaction.id(), 526, 2L, "Sharing"));
        assertTrue(session.undoCorrection());
        assertEquals("First reason", transaction.correctionReason());
        assertEquals(400L, session.metrics(1_600L).net);
        assertTrue(session.undoCorrection());
        assertEquals("", transaction.correctionReason());
        assertEquals(Correction.AUTO, transaction.correction());
    }


    @Test
    public void undoRestoresTheLatestReasonWhenTheCorrectionEnumWasUnchanged()
    {
        Session session = new Session("Test", 1_000L, SessionMode.GENERAL);
        Transaction transaction = Tx.of(1_100L, TransactionType.LOOT, Context.LOOT,
            "Loot", true, Collections.singletonList(new Flow(526, "Bones", 1L, 100, 100L)));
        session.addTransaction(transaction, 100, false);
        assertTrue(session.correctTransaction(transaction.id(), Correction.REVENUE, "A"));
        assertTrue(session.correctTransaction(transaction.id(), Correction.REVENUE, "B"));
        assertTrue(session.correctTransaction(transaction.id(), Correction.COST, "C"));
        session = new Gson().fromJson(new Gson().toJson(session), Session.class);
        assertTrue(session.undoCorrection());
        transaction = session.findTransaction(transaction.id());
        assertEquals("B", transaction.correctionReason());
        assertTrue(session.undoCorrection());
        assertEquals("A", transaction.correctionReason());
        assertEquals(100L, session.metrics(1_600L).net);
    }

    @Test
    public void profitTargetDoesNotAlterSessionAccounting()
    {
        Session session = new Session("General", 0L, SessionMode.AUTO);
        session.addTransaction(Tx.of(1L, TransactionType.LOOT, Context.LOOT, "loot", true,
            Collections.singletonList(new Flow(995, "Coins", 100, 1, 100))), 10, false);
        long net = session.metrics(2L).net;
        assertTrue(session.setNetTarget(1_000L, 0, 0));
        assertEquals(net, session.metrics(2L).net);
        session.setNetTarget(null, 0, 0);
        assertEquals(net, session.metrics(2L).net);
    }

    @Test
    public void calculatesRevenueCostsNetAndRate()
    {
        long start = 1_000_000L;
        Session session = new Session("Bossing", start, SessionMode.GENERAL);

        session.addTransaction(
            Tx.of(
                start + 1_000L,
                TransactionType.LOOT,
                Context.LOOT,
                "Loot",
                true,
                Collections.singletonList(new Flow(1, "Drop", 1, 1_000, 1_000))),
            100, false);

        Transaction food = Tx.of(
            start + 2_000L,
            TransactionType.CONSUMPTION,
            Context.GENERIC,
            "Supplies",
            true,
            Collections.singletonList(new Flow(2, "Food", -2, 100, -200)));
        food.setAction(ActionKind.EAT);
        session.addTransaction(food, 100, false);

        Metrics metrics = session.metrics(start + 3_600_000L);

        assertEquals(1_000L, metrics.income);
        assertEquals(200L, metrics.cost);
        assertEquals(800L, metrics.net);
        assertEquals(200L, metrics.supplies);
        assertEquals(0L, metrics.other);
        assertTrue(metrics.split);
        assertEquals(metrics.cost, metrics.supplies + metrics.other);
        assertEquals(800L, metrics.rate);
        assertEquals(2, SessionCounts.counted(session));
    }

    @Test
    public void pausedTimeIsExcluded()
    {
        Session session = new Session("Test", 1_000L, SessionMode.GENERAL);
        session.pause(2_000L, PauseReason.MANUAL);
        session.resume(12_000L);

        assertEquals(2_000L, session.elapsed(13_000L));
    }

    @Test
    public void boundsTransactionHistory()
    {
        Session session = new Session("Test", 0L, SessionMode.GENERAL);
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
                3, false);
        }

        assertEquals(3, session.transactions().size());
        assertTrue(session.transactions().get(0).timestamp >= 2L);
        assertEquals(5L, session.metrics(5_000L).net);
        assertEquals("three rows held, two compacted", 3, SessionCounts.counted(session));
        assertEquals(2L, session.compactedTransactionCount);
    }

    @Test
    public void compactionPreservesCorrectedTotalsAcrossReloadAndUndo()
    {
        Session session = new Session("Long session", 0L, SessionMode.GENERAL);
        Transaction corrected = Tx.of(1L, TransactionType.UNCERTAIN,
            Context.GENERIC, "Review", false,
            Collections.singletonList(new Flow(1, "Loot", 1, 100, 100)));
        session.addTransaction(corrected, 1, false);
        session.correctTransaction(corrected.id(), Correction.REVENUE, "Manual correction");
        session.addTransaction(Tx.of(3L, TransactionType.CONSUMPTION,
            Context.GENERIC, "Cost", true,
            Collections.singletonList(new Flow(2, "Food", -1, 25, -25))), 1, false);
        session.addTransaction(Tx.of(4L, TransactionType.TRANSFER,
            Context.TRANSFER, "Bank", false,
            Collections.singletonList(new Flow(3, "Coins", 1000, 1, 1000))), 1, false);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        Session restored = gson.fromJson(gson.toJson(session), Session.class);
        assertEquals(75L, restored.metrics(5L).net);
        assertEquals(100L, restored.retainedRevenue);
        assertEquals(25L, restored.retainedCosts);
        restored.undoTransaction();
        assertEquals(75L, restored.metrics(7L).net);
        restored.restoreUndo();
        assertEquals(75L, restored.metrics(9L).net);
        assertEquals(1, SessionCounts.transfers(restored));
    }

    @Test
    public void correctedCostCompactionKeepsEffectiveSessionAmounts()
    {
        Session session = new Session("Corrected compaction", 0L, SessionMode.GENERAL);
        Transaction correctedGain = Tx.of(1L, TransactionType.GAIN,
            Context.GENERIC, "Manual cost", true,
            Collections.singletonList(new Flow(1, "Item", 1, 50, 50L)));
        session.addTransaction(correctedGain, 1, false);
        assertTrue(session.correctTransaction(correctedGain.id(), Correction.COST, "Owner correction"));
        session.addTransaction(Tx.of(3L, TransactionType.LOOT,
            Context.LOOT, "Later gain", true,
            Collections.singletonList(new Flow(2, "Later item", 1, 1, 1L))), 1, false);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        session = gson.fromJson(gson.toJson(session), Session.class);
        Metrics metrics = session.metrics(5L);
        assertEquals(1L, metrics.income);
        assertEquals(50L, metrics.cost);
        assertEquals(0L, metrics.supplies);
        assertEquals(50L, metrics.other);
        assertTrue(metrics.split);

        // The limit of one already folded the corrected row by its effective (corrected) contribution;
        // compacting the retained gain keeps the archive exact.
        assertEquals(1, session.compactBefore(10L, transaction -> false));
        assertTrue(session.transactions().isEmpty());
        Metrics compacted = session.metrics(5L);
        assertEquals(1L, compacted.income);
        assertEquals(50L, compacted.cost);
        assertEquals(0L, compacted.supplies);
        assertEquals(50L, compacted.other);
        assertTrue(compacted.split);
    }

    @Test
    public void suppliesLossSplitSurvivesSerializationAndCompaction()
    {
        Session session = new Session("Costs", 0L, SessionMode.GENERAL);
        session.addTransaction(cost(TransactionType.CONSUMPTION, "Food", 1, -100L), 1, false);
        session.addTransaction(cost(TransactionType.TRADE, "GE buy", 2, -200L), 1, false);
        session.addTransaction(cost(TransactionType.PK_DEATH_LOSS, "Death", 3, -300L), 1, false);
        session.addTransaction(cost(TransactionType.CONSUMPTION, "Death reclaim", 4, -25L), 1, false);
        session.addTransaction(cost(TransactionType.CONSUMPTION, "Food", 5, -50L), 1, false);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        session = gson.fromJson(gson.toJson(session), Session.class);

        Metrics metrics = session.metrics(20_000L);
        assertEquals(675L, metrics.cost);
        assertEquals(150L, metrics.supplies);
        assertEquals(525L, metrics.other);
        assertTrue(metrics.split);
        assertEquals(metrics.cost, metrics.supplies + metrics.other);

        // The limit of one already archived four rows; folding the retained one keeps the split exact.
        assertEquals(1, session.transactions().size());
        assertEquals(1, session.compactBefore(6L, transaction -> false));
        assertEquals(0, session.transactions().size());
        Metrics mixed = gson.fromJson(gson.toJson(session), Session.class).metrics(20_000L);
        assertEquals(675L, mixed.cost);
        assertEquals(150L, mixed.supplies);
        assertEquals(525L, mixed.other);
        assertTrue(mixed.split);
    }

    private static Transaction cost(TransactionType type, String activity, int itemId, long value)
    {
        Transaction transaction = Tx.of(itemId, null, type, Context.GENERIC,
            activity, activity, true,
            Collections.singletonList(new Flow(itemId, activity, -1L,
                (int) Math.abs(value), value)));
        if (type == TransactionType.CONSUMPTION && "Food".equals(activity)) transaction.setAction(ActionKind.EAT);
        return transaction;
    }

    @Test
    public void pausedRatesRemainFrozenAndResumeOnActiveTime()
    {
        Session session = new Session("Test", 0L, SessionMode.GENERAL);
        session.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                Context.GENERIC,
                "",
                true,
                Arrays.asList(new Flow(1, "Item", 1, 1_000, 1_000))),
            10, false);

        session.pause(2_000L, PauseReason.MANUAL);
        Metrics atPause = session.metrics(2_000L);
        Metrics muchLater = session.metrics(62_000L);

        assertEquals(atPause.elapsed, muchLater.elapsed);
        assertEquals(atPause.rate, muchLater.rate);

        session.resume(62_000L);
        Metrics afterOneActiveSecond = session.metrics(63_000L);
        assertEquals(3_000L, afterOneActiveSecond.elapsed);
        assertEquals(1_200_000L, afterOneActiveSecond.rate);
    }

    @Test
    public void manualCorrectionsAreReversibleAndAudited()
    {
        Session session = new Session("Test", 0L, SessionMode.GENERAL);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.CONSUMPTION,
            Context.GENERIC,
            "",
            true,
            Collections.singletonList(new Flow(1, "Item", -1, 500, -500)));
        session.addTransaction(transaction, 10, false);

        session.correctTransaction(transaction.id(), Correction.REVENUE, "Manual correction");
        assertEquals(500L, session.metrics(3_000L).net);
        assertEquals(1, ModelProbe.activeCorrections(session).size());
        assertEquals("Manual correction", transaction.correctionReason());
        assertEquals("Manual correction", ModelProbe.activeCorrections(session).get(0).reason());

        session.correctTransaction(transaction.id(), Correction.AUTO, "Rechecked against loot timeline");
        assertEquals(-500L, session.metrics(5_000L).net);
        assertEquals(2, ModelProbe.activeCorrections(session).size());
        assertEquals("Rechecked against loot timeline", transaction.correctionReason());
        assertEquals("Rechecked against loot timeline", ModelProbe.activeCorrections(session).get(1).reason());
    }

    @Test
    public void undoCorrectionPreservesAppliedRecordAndUndoLinkAcrossPersistence()
    {
        Session session = new Session("Test", 0L, SessionMode.GENERAL);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.CONSUMPTION,
            Context.GENERIC,
            "",
            true,
            Collections.singletonList(new Flow(1, "Item", -1, 500, -500)));
        session.addTransaction(transaction, 10, false);
        session.correctTransaction(transaction.id(), Correction.REVENUE, "Audit fix");

        assertTrue(session.undoCorrection());
        assertEquals(-500L, session.metrics(4_000L).net);
        assertEquals(0, ModelProbe.activeCorrections(session).size());
        assertEquals(1, session.correctionHistory.size());
        CorrectionRecord record = session.correctionHistory.get(0);
        assertTrue(record.undone);

        Session restored = new Gson().fromJson(new Gson().toJson(session), Session.class);
        assertEquals(1, restored.correctionHistory.size());
        assertTrue(restored.correctionHistory.get(0).undone);
        assertFalse("an undone decision is never undone twice", restored.undoCorrection());
    }

    /** A saved split decision reloads and undoes back to the full gain. */
    @Test
    public void savedSplitRecordStillUndoes()
    {
        Session session = new Session("Test", 0L, SessionMode.GENERAL);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.GAIN,
            Context.GENERIC,
            "",
            true,
            Collections.singletonList(new Flow(1, "Item", 10, 100, 1_000)));
        session.addTransaction(transaction, 10, false);
        assertTrue(session.splitItem(transaction.id(), 1, 4, null));
        assertEquals(400L, session.metrics(3_000L).net);

        Gson gson = new Gson();
        Session restored = gson.fromJson(gson.toJson(session), Session.class);
        assertTrue(restored.undoCorrection());
        assertEquals("the saved flow snapshot restores the full gain", 1_000L,
            restored.metrics(5_000L).net);
        assertEquals(10L, restored.findTransaction(transaction.id()).quantity(1, true));
    }

    @Test
    public void undoWalksBackToPreviousActiveCorrection()
    {
        Session session = new Session("Test", 0L, SessionMode.GENERAL);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.CONSUMPTION,
            Context.GENERIC,
            "",
            true,
            Collections.singletonList(new Flow(1, "Item", -1, 500, -500)));
        session.addTransaction(transaction, 10, false);
        session.correctTransaction(transaction.id(), Correction.REVENUE, "First");
        session.correctTransaction(transaction.id(), Correction.IGNORE, "Second");

        assertTrue(session.undoCorrection());
        assertEquals(Correction.REVENUE, transaction.correction());
        assertTrue(session.undoCorrection());
        assertEquals(Correction.AUTO, transaction.correction());
        assertEquals(0, ModelProbe.activeCorrections(session).size());
        assertEquals(2, session.correctionHistory.size());
    }


    @Test
    public void undoKeepsTheRemovedTransaction()
    {
        Session session = new Session("Test", 0L, SessionMode.GENERAL);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.LOOT,
            Context.LOOT,
            "Loot",
            true,
            Collections.singletonList(new Flow(1, "Item", 1, 250, 250)));
        session.addTransaction(transaction, 10, false);

        Transaction removed = session.undoTransaction();
        assertEquals(transaction.id(), removed.id());
        assertEquals(1, session.undoHistory.size());
        assertSame("the removed row is kept whole for a restore", transaction,
            session.undoHistory.get(0).transaction);
        assertEquals(0L, session.metrics(3_000L).net);
    }

    @Test
    public void latestUndoCanBeRestoredWithoutLosingAuditRecord()
    {
        Session session = new Session("Test", 0L, SessionMode.GENERAL);
        Transaction transaction = Tx.of(
            1_000L,
            TransactionType.LOOT,
            Context.LOOT,
            "Loot",
            true,
            Collections.singletonList(new Flow(1, "Item", 1, 250, 250)));
        session.addTransaction(transaction, 10, false);
        session.undoTransaction();

        Transaction restored = session.restoreUndo();
        assertEquals(transaction.id(), restored.id());
        assertEquals(1, session.transactions().size());
        assertTrue(session.undoHistory.get(0).restored);
        assertEquals(null, session.restoreUndo());
    }

    @Test
    public void undoHistoryIsBoundedAndRestoreRespectsTransactionCapacity()
    {
        Session session = new Session("Test", 0L, SessionMode.GENERAL);
        for (int index = 0; index < 40; index++)
        {
            session.addTransaction(Tx.of(
                index,
                TransactionType.LOOT,
                Context.LOOT,
                "Loot",
                true,
                Collections.singletonList(new Flow(index, "Item", 1, 1, 1))), 100, false);
            session.undoTransaction();
        }
        assertEquals(32, session.undoHistory.size());

        Transaction first = Tx.of(
            50_000L, TransactionType.LOOT, Context.LOOT, "First", true,
            Collections.singletonList(new Flow(50, "First", 1, 10, 10)));
        Transaction second = Tx.of(
            51_000L, TransactionType.LOOT, Context.LOOT, "Second", true,
            Collections.singletonList(new Flow(51, "Second", 1, 20, 20)));
        Session bounded = new Session("Bounded", 0L, SessionMode.GENERAL);
        bounded.addTransaction(first, 1, false);
        bounded.undoTransaction();
        bounded.addTransaction(second, 1, false);
        assertEquals(first.id(), bounded.restoreUndo().id());
        assertEquals(1, bounded.transactions().size());
        assertEquals(first.id(), bounded.transactions().get(0).id());
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
        session.addTransaction(supply, 10, false);

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
        session.addTransaction(loot, 10, false);
        session.attachToEncounter(loot.id(), encounter, false);

        assertEquals(1, session.pk().kills);
        assertEquals(0, session.pk().deaths);
        assertEquals("loot less the attached food", 900L, session.bestKill());
        assertEquals("PKing", session.activityHint());
        assertEquals(TransactionType.PK_SUPPLY_COST, supply.original());
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
        session.addTransaction(food, 2, false);
        session.addTransaction(loot, 2, false);
        String kill = session.addEncounter(true, 2_100L);
        session.attachToEncounter(food.id(), kill, true);
        session.attachToEncounter(loot.id(), kill, false);

        assertEquals(900L, session.bestKill());
        assertTrue(session.correctTransaction(loot.id(), Correction.IGNORE, "Correction regression"));
        assertEquals("a losing kill is never the best", 0L, session.bestKill());
        assertTrue(session.undoCorrection());
        assertEquals(900L, session.bestKill());

        Transaction fillerOne = Tx.of(5_000L, TransactionType.ADJUSTMENT,
            Context.GENERIC, "", false, Collections.emptyList());
        Transaction fillerTwo = Tx.of(6_000L, TransactionType.ADJUSTMENT,
            Context.GENERIC, "", false, Collections.emptyList());
        session.addTransaction(fillerOne, 2, false);
        session.addTransaction(fillerTwo, 2, false);

        assertEquals("compaction keeps the kill", 900L, session.bestKill());
        assertTrue(session.pk().compactedKillNet.isEmpty());

        Gson gson = new Gson();
        Session restored = gson.fromJson(gson.toJson(session), Session.class);
        assertTrue(restored.metrics(7_000L).split);
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
        assertEquals(9L, session.elapsed(1_000L));
    }
}
