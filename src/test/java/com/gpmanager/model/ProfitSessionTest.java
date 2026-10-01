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
        Ad session = new Ad("Test", 1_000L);
        Ac transaction = Tx.of(1_100L, Ai.LOOT, Aj.LOOT,
            "Loot", true, Collections.singletonList(new Ab(526, "Bones", 4L, 100, 400L)));
        session.kf(transaction, 100);
        assertTrue(session.qi(transaction.getId(), Ah.REVENUE, 1_200L, "First reason"));
        assertTrue(session.qi(transaction.getId(), Ah.COST, 1_300L, "Second reason"));
        session = new Gson().fromJson(new Gson().toJson(session), Ad.class);
        assertTrue(session.akb(1_400L));
        transaction = session.sw(transaction.getId());
        assertEquals("First reason", transaction.tq());
        assertTrue(session.kr(transaction.getId(), 526, 2L, 1_500L, "Sharing"));
        assertTrue(session.akb(1_600L));
        assertEquals("First reason", transaction.tq());
        assertEquals(400L, session.metrics(1_600L).net);
        assertTrue(session.akb(1_700L));
        assertEquals("", transaction.tq());
        assertEquals(Ah.AUTO, transaction.getCorrection());
    }

    @Test
    public void undoSkipsPartialRecoveryThatDidNotChangeTheReason()
    {
        Ad session = new Ad("Test", 1_000L);
        Ac transaction = Tx.of(1_100L, Ai.CONSUMPTION, Aj.GENERIC,
            "Own drop", true, Collections.singletonList(new Ab(526, "Bones", -4L, 100, -400L)));
        session.kf(transaction, 100);
        assertTrue(session.qi(transaction.getId(), Ah.COST, 1_200L, "Original decision"));
        assertTrue(session.afc(transaction.getId(), 526, 1L, 1_300L, "Partial recovery"));
        assertTrue(session.qi(transaction.getId(), Ah.REVENUE, 1_400L, "Later decision"));
        session = new Gson().fromJson(new Gson().toJson(session), Ad.class);
        assertTrue(session.akb(1_500L));
        transaction = session.sw(transaction.getId());
        assertEquals("Original decision", transaction.tq());
        assertEquals(-300L, session.metrics(1_500L).net);
        assertTrue(session.akb(1_600L));
        assertEquals("Original decision", transaction.tq());
        assertEquals(-400L, session.metrics(1_600L).net);
    }

    @Test
    public void undoRestoresTheLatestReasonWhenTheCorrectionEnumWasUnchanged()
    {
        Ad session = new Ad("Test", 1_000L);
        Ac transaction = Tx.of(1_100L, Ai.LOOT, Aj.LOOT,
            "Loot", true, Collections.singletonList(new Ab(526, "Bones", 1L, 100, 100L)));
        session.kf(transaction, 100);
        assertTrue(session.qi(transaction.getId(), Ah.REVENUE, 1_200L, "A"));
        assertTrue(session.qi(transaction.getId(), Ah.REVENUE, 1_300L, "B"));
        assertTrue(session.qi(transaction.getId(), Ah.COST, 1_400L, "C"));
        session = new Gson().fromJson(new Gson().toJson(session), Ad.class);
        assertTrue(session.akb(1_500L));
        transaction = session.sw(transaction.getId());
        assertEquals("B", transaction.tq());
        assertTrue(session.akb(1_600L));
        assertEquals("A", transaction.tq());
        assertEquals(100L, session.metrics(1_600L).net);
    }

    @Test
    public void profitTargetDoesNotAlterSessionAccounting()
    {
        Ad session = new Ad("General", 0L, Cx.AUTO);
        session.kf(Tx.of(1L, Ai.LOOT, Aj.LOOT, "loot", true,
            Collections.singletonList(new Ab(995, "Coins", 100, 1, 100))), 10);
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
        Ad session = new Ad("Bossing", start);

        session.kf(
            Tx.of(
                start + 1_000L,
                Ai.LOOT,
                Aj.LOOT,
                "Loot",
                true,
                Collections.singletonList(new Ab(1, "Drop", 1, 1_000, 1_000))),
            100);

        Ac food = Tx.of(
            start + 2_000L,
            Ai.CONSUMPTION,
            Aj.GENERIC,
            "Supplies",
            true,
            Collections.singletonList(new Ab(2, "Food", -2, 100, -200)));
        food.setActionKind(Au.EAT);
        session.kf(food, 100);

        Bu metrics = session.metrics(start + 3_600_000L);

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
        Ad session = new Ad("Test", 1_000L);
        session.pause(2_000L, Ed.MANUAL);
        session.resume(12_000L);

        assertEquals(2_000L, session.getElapsedMillis(13_000L));
    }

    @Test
    public void boundsTransactionHistory()
    {
        Ad session = new Ad("Test", 0L);
        for (int index = 0; index < 5; index++)
        {
            session.kf(
                Tx.of(
                    index,
                    Ai.GAIN,
                    Aj.GENERIC,
                    "",
                    true,
                    Arrays.asList(new Ab(index, "Item", 1, 1, 1))),
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
        Ad session = new Ad("Long session", 0L);
        Ac corrected = Tx.of(1L, Ai.UNCERTAIN,
            Aj.GENERIC, "Review", false,
            Collections.singletonList(new Ab(1, "Loot", 1, 100, 100)));
        session.kf(corrected, 1);
        session.qi(corrected.getId(), Ah.REVENUE, 2L, "Manual correction");
        session.kf(Tx.of(3L, Ai.CONSUMPTION,
            Aj.GENERIC, "Cost", true,
            Collections.singletonList(new Ab(2, "Food", -1, 25, -25))), 1);
        session.kf(Tx.of(4L, Ai.TRANSFER,
            Aj.TRANSFER, "Bank", false,
            Collections.singletonList(new Ab(3, "Coins", 1000, 1, 1000))), 1);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        Ad restored = gson.fromJson(gson.toJson(session), Ad.class);
        assertEquals(75L, restored.metrics(5L).net);
        assertEquals(100L, restored.tp());
        assertEquals(25L, restored.tn());
        restored.akc(6L);
        assertEquals(75L, restored.metrics(7L).net);
        restored.agn(8L);
        assertEquals(75L, restored.metrics(9L).net);
        assertEquals(1, SessionCounts.transfers(restored));
    }

    @Test
    public void correctedCostCompactionKeepsEffectiveSessionAmounts()
    {
        Ad session = new Ad("Corrected compaction", 0L);
        Ac correctedGain = Tx.of(1L, Ai.GAIN,
            Aj.GENERIC, "Manual cost", true,
            Collections.singletonList(new Ab(1, "Item", 1, 50, 50L)));
        session.kf(correctedGain, 1);
        assertTrue(session.qi(correctedGain.getId(), Ah.COST,
            2L, "Owner correction"));
        session.kf(Tx.of(3L, Ai.LOOT,
            Aj.LOOT, "Later gain", true,
            Collections.singletonList(new Ab(2, "Later item", 1, 1, 1L))), 1);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        session = gson.fromJson(gson.toJson(session), Ad.class);
        Bu metrics = session.metrics(5L);
        assertEquals(1L, metrics.revenue);
        assertEquals(50L, metrics.costs);
        assertEquals(0L, metrics.suppliesCosts);
        assertEquals(50L, metrics.otherCosts);
        assertTrue(metrics.costSplitAvailable);

        // The limit of one already folded the corrected row by its effective (corrected) contribution;
        // compacting the retained gain keeps the archive exact.
        assertEquals(1, session.pj(10L, transaction -> false));
        assertTrue(session.getTransactions().isEmpty());
        Bu compacted = session.metrics(5L);
        assertEquals(1L, compacted.revenue);
        assertEquals(50L, compacted.costs);
        assertEquals(0L, compacted.suppliesCosts);
        assertEquals(50L, compacted.otherCosts);
        assertTrue(compacted.costSplitAvailable);
    }

    @Test
    public void suppliesLossSplitSurvivesSerializationAndCompaction()
    {
        Ad session = new Ad("Costs", 0L);
        session.kf(cost(Ai.CONSUMPTION, "Food", 1, -100L), 1);
        session.kf(cost(Ai.TRADE, "GE buy", 2, -200L), 1);
        session.kf(cost(Ai.PK_DEATH_LOSS, "Death", 3, -300L), 1);
        session.kf(cost(Ai.CONSUMPTION, "Death reclaim", 4, -25L), 1);
        session.kf(cost(Ai.CONSUMPTION, "Food", 5, -50L), 1);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        session = gson.fromJson(gson.toJson(session), Ad.class);

        Bu metrics = session.metrics(20_000L);
        assertEquals(675L, metrics.costs);
        assertEquals(150L, metrics.suppliesCosts);
        assertEquals(525L, metrics.otherCosts);
        assertTrue(metrics.costSplitAvailable);
        assertEquals(metrics.costs, metrics.suppliesCosts + metrics.otherCosts);

        // The limit of one already archived four rows; folding the retained one keeps the split exact.
        assertEquals(1, session.getTransactions().size());
        assertEquals(1, session.pj(6L, transaction -> false));
        assertEquals(0, session.getTransactions().size());
        Bu mixed = gson.fromJson(gson.toJson(session), Ad.class).metrics(20_000L);
        assertEquals(675L, mixed.costs);
        assertEquals(150L, mixed.suppliesCosts);
        assertEquals(525L, mixed.otherCosts);
        assertTrue(mixed.costSplitAvailable);
    }

    private static Ac cost(Ai type, String activity, int itemId, long value)
    {
        Ac transaction = Tx.of(itemId, null, type, Aj.GENERIC,
            activity, activity, true,
            Collections.singletonList(new Ab(itemId, activity, -1L,
                (int) Math.abs(value), value)));
        if (type == Ai.CONSUMPTION && "Food".equals(activity)) transaction.setActionKind(Au.EAT);
        return transaction;
    }

    @Test
    public void pausedRatesRemainFrozenAndResumeOnActiveTime()
    {
        Ad session = new Ad("Test", 0L);
        session.kf(
            Tx.of(
                1_000L,
                1_000L,
                Ai.GAIN,
                Aj.GENERIC,
                "",
                true,
                Arrays.asList(new Ab(1, "Item", 1, 1_000, 1_000))),
            10);

        session.pause(2_000L, Ed.MANUAL);
        Bu atPause = session.metrics(2_000L);
        Bu muchLater = session.metrics(62_000L);

        assertEquals(atPause.elapsedMillis, muchLater.elapsedMillis);
        assertEquals(atPause.profitPerHour, muchLater.profitPerHour);

        session.resume(62_000L);
        Bu afterOneActiveSecond = session.metrics(63_000L);
        assertEquals(3_000L, afterOneActiveSecond.elapsedMillis);
        assertEquals(1_200_000L, afterOneActiveSecond.profitPerHour);
    }

    @Test
    public void manualCorrectionsAreReversibleAndAudited()
    {
        Ad session = new Ad("Test", 0L);
        Ac transaction = Tx.of(
            1_000L,
            Ai.CONSUMPTION,
            Aj.GENERIC,
            "",
            true,
            Collections.singletonList(new Ab(1, "Item", -1, 500, -500)));
        session.kf(transaction, 10);

        session.qi(transaction.getId(), Ah.REVENUE, 2_000L, "Manual correction");
        assertEquals(500L, session.metrics(3_000L).net);
        assertEquals(1, ModelProbe.activeCorrections(session).size());
        assertEquals("Manual correction", transaction.tq());
        assertEquals("Manual correction", ModelProbe.activeCorrections(session).get(0).getReason());

        session.qi(transaction.getId(), Ah.AUTO, 4_000L, "Rechecked against loot timeline");
        assertEquals(-500L, session.metrics(5_000L).net);
        assertEquals(2, ModelProbe.activeCorrections(session).size());
        assertEquals("Rechecked against loot timeline", transaction.tq());
        assertEquals("Rechecked against loot timeline", ModelProbe.activeCorrections(session).get(1).getReason());
    }

    @Test
    public void undoCorrectionPreservesAppliedRecordAndUndoLinkAcrossPersistence()
    {
        Ad session = new Ad("Test", 0L);
        Ac transaction = Tx.of(
            1_000L,
            Ai.CONSUMPTION,
            Aj.GENERIC,
            "",
            true,
            Collections.singletonList(new Ab(1, "Item", -1, 500, -500)));
        session.kf(transaction, 10);
        session.qi(transaction.getId(), Ah.REVENUE, 2_000L, "Audit fix");

        assertTrue(session.akb(3_000L));
        assertEquals(-500L, session.metrics(4_000L).net);
        assertEquals(0, ModelProbe.activeCorrections(session).size());
        assertEquals(1, session.correctionHistory.size());
        Dx record = session.correctionHistory.get(0);
        assertTrue(record.isUndone());
        assertTrue(record.undoneAtEpochMillis == 3_000L);
        assertTrue(!record.getRecordId().isEmpty());
        assertTrue(!record.getUndoId().isEmpty());

        Ad restored = new Gson().fromJson(new Gson().toJson(session), Ad.class);
        assertEquals(1, restored.correctionHistory.size());
        assertTrue(restored.correctionHistory.get(0).isUndone());
        assertEquals(record.getRecordId(), restored.correctionHistory.get(0).getRecordId());
        assertEquals(record.getUndoId(), restored.correctionHistory.get(0).getUndoId());
    }

    /** A one-change record written before batch records (top-level fields, no changes) still loads and undoes. */
    @Test
    public void legacyOneChangeSplitRecordStillUndoes()
    {
        Ad session = new Ad("Test", 0L);
        Ac transaction = Tx.of(
            1_000L,
            Ai.GAIN,
            Aj.GENERIC,
            "",
            true,
            Collections.singletonList(new Ab(1, "Item", 10, 100, 1_000)));
        session.kf(transaction, 10);
        assertTrue(session.kr(transaction.getId(), 1, 4, 2_000L, null));
        assertEquals(400L, session.metrics(3_000L).net);

        Gson gson = new Gson();
        com.google.gson.JsonObject json = gson.toJsonTree(session).getAsJsonObject();
        com.google.gson.JsonObject record = json.getAsJsonArray("correctionHistory").get(0).getAsJsonObject();
        com.google.gson.JsonObject change = record.remove("changes").getAsJsonArray().get(0).getAsJsonObject();
        for (String field : change.keySet())
        {
            record.add(field, change.get(field));
        }

        Ad restored = gson.fromJson(json, Ad.class);
        assertTrue(restored.akb(4_000L));
        assertEquals("the legacy flow snapshot restores the full gain", 1_000L,
            restored.metrics(5_000L).net);
        assertEquals(10L, restored.sw(transaction.getId()).quantity(1, true));
    }

    @Test
    public void undoWalksBackToPreviousActiveCorrection()
    {
        Ad session = new Ad("Test", 0L);
        Ac transaction = Tx.of(
            1_000L,
            Ai.CONSUMPTION,
            Aj.GENERIC,
            "",
            true,
            Collections.singletonList(new Ab(1, "Item", -1, 500, -500)));
        session.kf(transaction, 10);
        session.qi(transaction.getId(), Ah.REVENUE, 2_000L, "First");
        session.qi(transaction.getId(), Ah.IGNORE, 3_000L, "Second");

        assertTrue(session.akb(4_000L));
        assertEquals(Ah.REVENUE, transaction.getCorrection());
        assertTrue(session.akb(5_000L));
        assertEquals(Ah.AUTO, transaction.getCorrection());
        assertEquals(0, ModelProbe.activeCorrections(session).size());
        assertEquals(2, session.correctionHistory.size());
    }

    @Test
    public void partialAndFullOwnDropRecoveryAreUndoable()
    {
        Ad session = new Ad("Test", 0L);
        Ac transaction = Tx.of(
            1_000L,
            Ai.PK_DEATH_LOSS,
            Aj.PK_DEATH,
            "Death loss",
            true,
            Collections.singletonList(new Ab(1, "Item", -4, 250, -1_000)));
        session.kf(transaction, 10);

        assertTrue(session.afc(transaction.getId(), 1, 2L, 2_000L, "Partial reclaim"));
        assertEquals(-500L, session.metrics(3_000L).net);
        assertTrue(session.akb(4_000L));
        assertEquals(-1_000L, session.metrics(5_000L).net);

        assertTrue(session.afc(transaction.getId(), 1, 4L, 6_000L, "Full reclaim"));
        assertEquals(0L, session.metrics(7_000L).net);
        assertTrue(session.akb(8_000L));
        assertEquals(-1_000L, session.metrics(9_000L).net);
    }

    @Test
    public void undoActionsAreAuditedWithTransactionMetadata()
    {
        Ad session = new Ad("Test", 0L);
        Ac transaction = Tx.of(
            1_000L,
            Ai.LOOT,
            Aj.LOOT,
            "Loot",
            true,
            Collections.singletonList(new Ab(1, "Item", 1, 250, 250)));
        session.kf(transaction, 10);

        Ac removed = session.akc(2_000L);
        assertEquals(transaction.getId(), removed.getId());
        assertEquals(1, session.undoHistory.size());
        assertEquals("General", session.undoHistory.get(0).getActivityName());
        assertEquals(250L, session.undoHistory.get(0).net);
        assertEquals(2_000L, session.undoHistory.get(0).timestampEpochMillis);
    }

    @Test
    public void latestUndoCanBeRestoredWithoutLosingAuditRecord()
    {
        Ad session = new Ad("Test", 0L);
        Ac transaction = Tx.of(
            1_000L,
            Ai.LOOT,
            Aj.LOOT,
            "Loot",
            true,
            Collections.singletonList(new Ab(1, "Item", 1, 250, 250)));
        session.kf(transaction, 10);
        session.akc(2_000L);

        Ac restored = session.agn(3_000L);
        assertEquals(transaction.getId(), restored.getId());
        assertEquals(1, session.getTransactions().size());
        assertTrue(session.undoHistory.get(0).restored);
        assertEquals(3_000L, session.undoHistory.get(0).restoredAtEpochMillis);
        assertEquals(null, session.agn(4_000L));
    }

    @Test
    public void undoHistoryIsBoundedAndRestoreRespectsTransactionCapacity()
    {
        Ad session = new Ad("Test", 0L);
        for (int index = 0; index < 40; index++)
        {
            session.kf(Tx.of(
                index,
                Ai.LOOT,
                Aj.LOOT,
                "Loot",
                true,
                Collections.singletonList(new Ab(index, "Item", 1, 1, 1))), 100);
            session.akc(index + 1_000L);
        }
        assertEquals(32, session.undoHistory.size());

        Ac first = Tx.of(
            50_000L, Ai.LOOT, Aj.LOOT, "First", true,
            Collections.singletonList(new Ab(50, "First", 1, 10, 10)));
        Ac second = Tx.of(
            51_000L, Ai.LOOT, Aj.LOOT, "Second", true,
            Collections.singletonList(new Ab(51, "Second", 1, 20, 20)));
        Ad bounded = new Ad("Bounded", 0L);
        bounded.kf(first, 1);
        bounded.akc(52_000L);
        bounded.kf(second, 1);
        assertEquals(first.getId(), bounded.agn(53_000L).getId());
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
        Ad session = new Ad("PK", 0L, Cx.PK);
        Ac supply = Tx.of(
            1_000L,
            Ai.CONSUMPTION,
            Aj.GENERIC,
            "",
            true,
            Collections.singletonList(new Ab(1, "Food", -1, 100, -100)));
        session.kf(supply, 10);

        Bx encounter = session.ke(
            Be.KILL,
            2_000L,
            "Player kill",
            Bd.CONFIRMED,
            "RuneLite player loot");
        session.lj(encounter.getId(), 2_000L, 10_000L);

        Ac loot = Tx.of(
            2_500L,
            2_500L,
            Ai.PK_LOOT,
            Aj.PK_LOOT,
            "PK loot",
            "PKing",
            true,
            Collections.singletonList(new Ab(2, "Loot", 1, 1_000, 1_000)));
        session.kf(loot, 10);
        session.ll(loot.getId(), encounter.getId(), false);

        Dt metrics = session.ava();
        assertEquals(1, metrics.kills);
        assertEquals(0, metrics.deaths);
        assertEquals(900L, metrics.net);
        assertEquals(1, metrics.ue());
        assertEquals(900L, metrics.totalKillNet / metrics.kills);
        assertEquals(900L, metrics.net / metrics.ue());
        assertTrue(metrics.costSplitAvailable);
        assertEquals(100L, metrics.suppliesCosts);
        assertEquals(100L, metrics.suppliesCosts / metrics.ue());
        assertEquals(0L, metrics.otherCosts);
        assertEquals(1, session.actionCount);
        assertEquals("PKing", session.getActivityHint());
        assertEquals(Ai.PK_SUPPLY_COST, supply.tm());
    }

    @Test
    public void pkFinancialSummaryTracksCorrectionUndoAndReceiptCompaction()
    {
        Ad session = new Ad("PK", 0L, Cx.PK);
        Ac food = Tx.of(1_000L, Ai.CONSUMPTION,
            Aj.GENERIC, "Food", true,
            Collections.singletonList(new Ab(1, "Food", -1L, 100, -100L)));
        Ac loot = Tx.of(2_000L, Ai.PK_LOOT,
            Aj.PK_LOOT, "PK loot", true,
            Collections.singletonList(new Ab(2, "Loot", 1L, 1_000, 1_000L)));
        session.kf(food, 2);
        session.kf(loot, 2);
        Bx kill = session.ke(Be.KILL, 2_100L,
            "Player kill", Bd.CONFIRMED, "test");
        session.ll(food.getId(), kill.getId(), true);
        session.ll(loot.getId(), kill.getId(), false);

        assertEquals(900L, session.ava().net);
        assertTrue(session.qi(loot.getId(), Ah.IGNORE,
            3_000L, "Correction regression"));
        assertEquals(-100L, session.ava().net);
        assertTrue(session.akb(4_000L));
        assertEquals(900L, session.ava().net);

        Ac fillerOne = Tx.of(5_000L, Ai.ADJUSTMENT,
            Aj.GENERIC, "", false, Collections.emptyList());
        Ac fillerTwo = Tx.of(6_000L, Ai.ADJUSTMENT,
            Aj.GENERIC, "", false, Collections.emptyList());
        session.kf(fillerOne, 2);
        session.kf(fillerTwo, 2);

        Dt compacted = session.ava();
        assertEquals(900L, compacted.net);
        assertEquals(100L, compacted.suppliesCosts);
        assertEquals(0L, compacted.otherCosts);
        assertTrue(compacted.costSplitAvailable);

        Gson gson = new Gson();
        Ad restored = gson.fromJson(gson.toJson(session), Ad.class);
        assertTrue(restored.metrics(7_000L).costSplitAvailable);
    }

    @Test
    public void pkStreakIsSignedAndResetsAtEncounterTypeChanges()
    {
        Ad session = new Ad("PK streak", 1L, Cx.PK);
        session.ke(Be.KILL, 10L, "Kill: One",
            Bd.CONFIRMED, "test");
        session.ke(Be.KILL, 20L, "Kill: Two",
            Bd.CONFIRMED, "test");
        session.ke(Be.DEATH, 30L, "Player death",
            Bd.CONFIRMED, "test");
        session.ke(Be.DEATH, 40L, "Player death",
            Bd.CONFIRMED, "test");
        session.ke(Be.DEATH, 50L, "Player death",
            Bd.CONFIRMED, "test");
        assertEquals(-3, session.ava().currentStreak);

        session.ke(Be.KILL, 60L, "Kill: Three",
            Bd.CONFIRMED, "test");
        assertEquals(1, session.ava().currentStreak);
    }

    @Test
    public void pkMediansAverageEvenMiddleValuesWithoutLosingHalfGp()
    {
        Ad session = new Ad("PK median", 1L, Cx.PK);
        addPkValue(session, Be.KILL, 10L, 100L);
        addPkValue(session, Be.KILL, 20L, 101L);
        addPkValue(session, Be.DEATH, 30L, -100L);
        addPkValue(session, Be.DEATH, 40L, -101L);

        Dt metrics = session.ava();
        assertTrue(metrics.medianKillNetGp != null);
        assertTrue(metrics.medianDeathLossGp != null);
        assertEquals(100.5d, metrics.medianKillNetGp, 0.0d);
        assertEquals(100.5d, metrics.medianDeathLossGp, 0.0d);
    }

    private static void addPkValue(Ad session, Be type,
        long at, long gp)
    {
        Bx encounter = session.ke(type, at,
            type == Be.KILL ? "Kill: Rival" : "Player death",
            Bd.CONFIRMED, "test");
        Ai transactionType = type == Be.KILL
            ? Ai.PK_LOOT : Ai.PK_DEATH_LOSS;
        Ac transaction = Tx.of(at, transactionType,
            type == Be.KILL ? Aj.PK_LOOT : Aj.PK_DEATH,
            "", true, Collections.singletonList(new Ab(1, "PK item",
                gp >= 0L ? 1L : -1L, (int) Math.abs(gp), gp)));
        session.kf(transaction, 20);
        session.ll(transaction.getId(), encounter.getId(), false);
    }

    @Test
    public void closingPausedSessionDoesNotResumeOrAccrueThePausedInterval()
    {
        Ad session = new Ad("Paused close", 1L, Cx.GENERAL);
        session.pause(10L, Ed.MANUAL);
        session.close(1_000L);

        assertTrue(session.paused);
        assertEquals(9L, session.getElapsedMillis(1_000L));
    }
}
