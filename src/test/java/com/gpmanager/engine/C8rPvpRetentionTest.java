package com.gpmanager;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * C8R Family C contract: bounded detailed PvP retention, correction-safe extrema, exact retained
 * projections, honest coverage, projection-sourced rollups, and trim/delete/reset isolation.
 * Offline deterministic engine tests; no timing sleeps.
 */
public class C8rPvpRetentionTest
{
    private static final long DAY = 86_400_000L;
    private static final long NOW = Instant.parse("2027-06-01T12:00:00Z").toEpochMilli();

    static
    {
        JsonCodec.bind(new Gson());
    }

    private static GpManagerConfig config(ReceiptRetentionPeriod period, int maxHistory)
    {
        return new GpManagerConfig()
        {
            @Override
            public int maxHistorySessions() { return maxHistory; }
            @Override
            public int maxTransactionsPerSession() { return 100_000; }
            @Override
            public ReceiptRetentionPeriod receiptRetentionDays() { return period; }
            @Override
            public boolean autoStartSession() { return false; }
        };
    }

    private static Engine engine(ReceiptRetentionPeriod period)
    {
        return engine(period, 5_000);
    }

    private static Engine engine(ReceiptRetentionPeriod period, int maxHistory)
    {
        return new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            config(period, maxHistory));
    }

    private static Session pkSession(String name, long startedAt)
    {
        return new Session(name, startedAt, SessionMode.PK);
    }

    private static PkEncounter kill(Engine engine, Session session, long at)
    {
        PkEncounter encounter = session.addPkEncounter(EncounterType.KILL, at, "Player kill",
            ClassificationConfidence.CONFIRMED, "Kill");
        engine.pkHistory.registerEncounter(encounter);
        return encounter;
    }

    private static PkEncounter death(Engine engine, Session session, long at)
    {
        PkEncounter encounter = session.addPkEncounter(EncounterType.DEATH, at, "Player death",
            ClassificationConfidence.CONFIRMED, "Death");
        engine.pkHistory.registerEncounter(encounter);
        return encounter;
    }

    private static Transaction receipt(Session session, long at, long netGp)
    {
        Transaction transaction = new Transaction(at, null,
            netGp >= 0 ? TransactionType.PK_LOOT : TransactionType.PK_DEATH_LOSS,
            Context.PK_LOOT, "pk", "PKing", true,
            Collections.singletonList(new Flow(netGp >= 0 ? 995 : 385,
                netGp >= 0 ? "Coins" : "Shark", netGp >= 0 ? netGp : -1L,
                (int) Math.max(1L, Math.abs(netGp)), netGp)),
            ClassificationConfidence.CONFIRMED, "test", null);
        session.addTransaction(transaction, 100_000);
        return transaction;
    }

    private static Transaction supplyCost(Session session, long at, long costsGp)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "pk supply", "PKing", true,
            Collections.singletonList(new Flow(385, "Shark", -1L, (int) Math.max(1L, costsGp), -costsGp)),
            ClassificationConfidence.CONFIRMED, "test", null);
        session.addTransaction(transaction, 100_000);
        return transaction;
    }

    // ── retention boundaries ──────────────────────────────────────────────────

    @Test
    public void detailBoundaryRetainsExactlyTheLimit()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_365);
        Session session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        for (int index = 0; index < PkHistoryArchive.MAX_DETAILED_ENCOUNTERS - 1; index++)
        {
            kill(engine, session, NOW - 1_000L + index);
        }
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS - 1, session.getPkEncounters().size());
        kill(engine, session, NOW);
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS, session.getPkEncounters().size());
        kill(engine, session, NOW + 1_000L);
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS, session.getPkEncounters().size());
        assertEquals("counts stay exact after detail eviction",
            PkHistoryArchive.MAX_DETAILED_ENCOUNTERS + 1, session.pkProjection.getKills());
    }

    @Test
    public void detailBoundIsProfileWideAcrossSessions()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_365);
        Session older = pkSession("older", NOW - DAY);
        Session newer = pkSession("newer", NOW - 1_000L);
        engine.history.add(older);
        engine.history.add(newer);
        for (int index = 0; index < 1_000; index++) kill(engine, older, NOW - 1_000_000L + index);
        for (int index = 0; index < 1_001; index++) kill(engine, newer, NOW - 500_000L + index);
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS,
            older.getPkEncounters().size() + newer.getPkEncounters().size());
        assertEquals("the oldest profile-wide position was compacted", 999, older.getPkEncounters().size());
        assertEquals(1_001, newer.getPkEncounters().size());
        assertEquals(1_000, older.pkProjection.getKills());
        assertEquals(1_001, newer.pkProjection.getKills());
    }

    @Test
    public void ageCutoffRetainsTheExactBoundaryAndEvictsOlderDetail()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_30);
        Session session = pkSession("PK", NOW - 100L * DAY);
        engine.history.add(session);
        long cutoff = NOW - 30L * DAY;
        kill(engine, session, cutoff - 1L);
        kill(engine, session, cutoff);
        kill(engine, session, cutoff + 1L);
        engine.pkHistory.enforceDetailRetention(engine.uniqueProfileSessions(), 30, NOW);
        assertEquals(2, session.getPkEncounters().size());
        assertEquals(PkDetailScope.RETAINED_WINDOW, session.pkProjection.getDetailScope());
        assertEquals(3, session.pkProjection.getKills());
    }

    @Test
    public void completionSequencePersistsAndNeverRenumbers()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_365);
        Session session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        kill(engine, session, NOW - 1_000L);
        long first = session.getPkEncounters().get(0).completionSequence;
        SavedState state = engine.createSavedState();
        Engine reloaded = engine(ReceiptRetentionPeriod.DAYS_365);
        reloaded.restore(state, NOW);
        Session restored = reloaded.getHistory().get(0);
        assertEquals(first, restored.getPkEncounters().get(0).completionSequence);
        PkEncounter next = kill(reloaded, restored, NOW);
        assertTrue("new detail never reuses a completion position", next.completionSequence > first);
        assertEquals(2, restored.pkProjection.getKills());
    }

    @Test
    public void compactedDetailNeverBackfillsAfterNewerRowsDisappear()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_365);
        Session session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        for (int index = 0; index <= PkHistoryArchive.MAX_DETAILED_ENCOUNTERS; index++)
        {
            kill(engine, session, NOW - 2_000_000L + index);
        }
        long floor = engine.pkHistory.state.detailFloorSequence;
        assertTrue("the oldest position was compacted", floor >= 0L);
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS, session.getPkEncounters().size());
        // Removing a newer retained row must not resurrect or lower the floor.
        session.removePkEncounter(session.getPkEncounters().get(session.getPkEncounters().size() - 1));
        engine.pkHistory.enforceDetailRetention(engine.uniqueProfileSessions(), 365, NOW);
        assertEquals(floor, engine.pkHistory.state.detailFloorSequence);
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS - 1, session.getPkEncounters().size());
    }

    @Test
    public void serializedStateStaysBoundedAtTheCap()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_365);
        Session session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        for (int index = 0; index < 2_500; index++) kill(engine, session, NOW - 3_000_000L + index);
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS, session.getPkEncounters().size());
        SavedState state = engine.createSavedState();
        int bytes = JsonCodec.gson().toJson(state).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue("bounded serialized profile: " + bytes, bytes < 1_500_000);
        assertEquals(2_500, state.getHistory().get(0).pkProjection.getKills());
    }

    // ── anchors / corrections ─────────────────────────────────────────────────

    @Test
    public void receiptFinalizationFoldsExactlyOnceAndRemovesTheAnchor()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_30);
        Session session = pkSession("PK", NOW - 100L * DAY);
        engine.history.add(session);
        PkEncounter encounter = kill(engine, session, NOW - 100L * DAY);
        Transaction loot = receipt(session, NOW - 100L * DAY + 1_000L, 5_000L);
        session.attachTransactionToEncounter(loot.getId(), encounter.getId(), false);
        assertEquals(5_000L, session.pkMetrics().net);
        assertEquals(1, session.getPkAttributions().size());

        assertEquals(1, session.compactTransactionsBefore(NOW - 30L * DAY, null));
        assertEquals("anchors disappear once every receipt has finalized",
            0, session.getPkAttributions().size());
        assertEquals(5_000L, session.pkMetrics().net);
        assertEquals(5_000L, session.pkProjection.getNet());
        assertEquals(0, session.compactTransactionsBefore(NOW, null));
        assertEquals(5_000L, session.pkMetrics().net);
    }

    @Test
    public void correctionAfterDetailRemovalStillRevisesTheProjection()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_365);
        Session session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        PkEncounter encounter = kill(engine, session, NOW - DAY);
        Transaction loot = receipt(session, NOW - DAY + 1_000L, 5_000L);
        session.attachTransactionToEncounter(loot.getId(), encounter.getId(), false);
        assertTrue(session.removePkEncounter(encounter));
        assertEquals(0, session.getPkEncounters().size());
        assertEquals(1, session.getPkAttributions().size());
        assertEquals(5_000L, session.pkMetrics().net);

        assertTrue(session.correctTransaction(loot.getId(), Correction.IGNORE, NOW, "not mine"));
        assertEquals("correction updates the retained projection after detail loss",
            0L, session.pkMetrics().net);
        assertEquals("a zeroed correction stays correction-eligible", 1, session.getPkAttributions().size());
        assertEquals(0L, session.getPkAttributions().get(0).current().netGp);
        assertTrue(session.correctTransaction(loot.getId(), Correction.AUTO, NOW, "restored"));
        assertEquals(5_000L, session.pkMetrics().net);
        assertEquals(1, session.pkProjection.getKills());
    }

    @Test
    public void undoRemovesTheAnchorChildWithoutLosingCounts()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_365);
        Session session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        PkEncounter encounter = kill(engine, session, NOW - DAY);
        Transaction loot = receipt(session, NOW - DAY + 1_000L, 5_000L);
        session.attachTransactionToEncounter(loot.getId(), encounter.getId(), false);
        assertNotNull(session.undoLastTransaction(NOW));
        assertEquals(0L, session.pkMetrics().net);
        assertEquals(0, session.getPkAttributions().size());
        assertEquals(1, session.pkMetrics().kills);
    }

    @Test
    public void findingF3UndoRedoRestoresTheMutableAnchorAndFinalizesOnce()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_365);
        Session session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        PkEncounter encounter = kill(engine, session, NOW - DAY);
        Transaction loot = receipt(session, NOW - DAY + 1_000L, 5_000L);
        session.attachTransactionToEncounter(loot.getId(), encounter.getId(), false);

        assertNotNull(session.undoLastTransaction(NOW));
        assertNotNull(session.restoreLastUndo(NOW));
        assertEquals(5_000L, session.pkMetrics().net);
        assertEquals(0L, session.pkProjection.getNet());
        assertEquals(1, session.getPkAttributions().size());
        assertTrue(session.getPkAttributions().get(0).holds(loot.getId()));

        assertEquals(1, session.compactTransactionsBefore(NOW, null));
        assertEquals(0, session.getPkAttributions().size());
        assertEquals(5_000L, session.pkProjection.getNet());
        assertEquals(5_000L, session.pkMetrics().net);

        SavedState state = engine.createSavedState();
        Engine reloaded = engine(ReceiptRetentionPeriod.DAYS_365);
        reloaded.restore(state, NOW);
        assertEquals(5_000L, reloaded.getHistory().get(0).pkProjection.getNet());
    }

    @Test
    public void findingF3UndoRedoMixedFinalizedAndLiveChildDoesNotDoubleCount()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_365);
        Session session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        PkEncounter encounter = kill(engine, session, NOW - DAY);
        Transaction finalized = receipt(session, NOW - DAY + 1_000L, 2_000L);
        session.attachTransactionToEncounter(finalized.getId(), encounter.getId(), false);
        Transaction live = receipt(session, NOW - DAY + 2_000L, 3_000L);
        session.attachTransactionToEncounter(live.getId(), encounter.getId(), false);
        assertEquals(1, session.compactTransactionsBefore(NOW - DAY + 1_500L, null));
        assertEquals(2_000L, session.getPkAttributions().get(0).finalizedNet);

        assertNotNull(session.undoLastTransaction(NOW));
        assertEquals(2_000L, session.pkProjection.getNet());
        assertNotNull(session.restoreLastUndo(NOW));
        assertEquals(5_000L, session.pkMetrics().net);
        assertEquals(2_000L, session.pkProjection.getNet());
        assertEquals(1, session.getPkAttributions().size());

        assertEquals(1, session.compactTransactionsBefore(NOW, null));
        assertEquals(5_000L, session.pkProjection.getNet());
        assertEquals(0, session.getPkAttributions().size());
        assertEquals(5_000L,
            EngineProbe.profileFacts(engine.pkHistory, engine.uniqueProfileSessions()).getNet());

        SavedState state = engine.createSavedState();
        Engine reloaded = engine(ReceiptRetentionPeriod.DAYS_365);
        reloaded.restore(state, NOW);
        assertEquals(5_000L,
            EngineProbe.profileFacts(reloaded.pkHistory, reloaded.uniqueProfileSessions()).getNet());
    }

    // ── extrema ───────────────────────────────────────────────────────────────

    @Test
    public void mutableWinnerOverFinalizedExtremumAndCorrectionRevealsTheNext()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_30);
        Session session = pkSession("PK", NOW - 100L * DAY);
        engine.history.add(session);
        PkEncounter finalized = kill(engine, session, NOW - 100L * DAY);
        Transaction first = receipt(session, NOW - 100L * DAY + 1_000L, 8_000_000L);
        session.attachTransactionToEncounter(first.getId(), finalized.getId(), false);
        session.compactTransactionsBefore(NOW - 30L * DAY, null);

        PkEncounter mutable = kill(engine, session, NOW - 1L * DAY);
        Transaction second = receipt(session, NOW - 1L * DAY + 1_000L, 10_000_000L);
        session.attachTransactionToEncounter(second.getId(), mutable.getId(), false);
        assertEquals(10_000_000L, session.pkMetrics().bestKill);
        assertEquals(18_000_000L, session.pkMetrics().net);

        assertTrue(session.correctTransaction(second.getId(), Correction.IGNORE, NOW, "wrong"));
        assertEquals("correcting the mutable winner reveals the finalized extremum",
            8_000_000L, session.pkMetrics().bestKill);
        assertEquals(8_000_000L, session.pkMetrics().net);
    }

    @Test
    public void equalMutableAndFinalizedMaximaStayIndependent()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_30);
        Session session = pkSession("PK", NOW - 100L * DAY);
        engine.history.add(session);
        PkEncounter finalized = kill(engine, session, NOW - 100L * DAY);
        Transaction first = receipt(session, NOW - 100L * DAY + 1_000L, 10_000_000L);
        session.attachTransactionToEncounter(first.getId(), finalized.getId(), false);
        session.compactTransactionsBefore(NOW - 30L * DAY, null);

        PkEncounter mutableA = kill(engine, session, NOW - 2L * DAY);
        Transaction second = receipt(session, NOW - 2L * DAY + 1_000L, 10_000_000L);
        session.attachTransactionToEncounter(second.getId(), mutableA.getId(), false);
        PkEncounter mutableB = kill(engine, session, NOW - 1L * DAY);
        Transaction third = receipt(session, NOW - 1L * DAY + 1_000L, 10_000_000L);
        session.attachTransactionToEncounter(third.getId(), mutableB.getId(), false);
        assertEquals(10_000_000L, session.pkMetrics().bestKill);

        assertTrue(session.correctTransaction(second.getId(), Correction.IGNORE, NOW, "wrong"));
        assertEquals("the remaining equal mutable maximum keeps the extremum",
            10_000_000L, session.pkMetrics().bestKill);
        assertTrue(session.correctTransaction(third.getId(), Correction.IGNORE, NOW, "wrong"));
        assertEquals("the finalized equal maximum still holds", 10_000_000L, session.pkMetrics().bestKill);
    }

    @Test
    public void negativeKillNetAndPositiveDeathNetFollowTheMetricFloors()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_365);
        Session session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        PkEncounter kill = kill(engine, session, NOW - DAY);
        Transaction loss = receipt(session, NOW - DAY + 1_000L, -50L);
        session.attachTransactionToEncounter(loss.getId(), kill.getId(), false);
        PkEncounter death = death(engine, session, NOW - DAY + 2_000L);
        Transaction gain = receipt(session, NOW - DAY + 3_000L, 20L);
        session.attachTransactionToEncounter(gain.getId(), death.getId(), false);
        PkMetrics metrics = session.pkMetrics();
        assertEquals("best kill is floored at zero", 0L, metrics.bestKill);
        assertEquals(-50L, metrics.totalKillNet);
        assertEquals("death loss is floored at zero", 0L, metrics.largestDeathLoss);
        assertEquals(-30L, metrics.net);
    }

    // ── coverage / medians ────────────────────────────────────────────────────

    @Test
    public void zeroEncountersExposeAdditiveZeroButNoExtremaOrMedians()
    {
        Session session = pkSession("PK", NOW - DAY);
        PkMetrics metrics = session.pkMetrics();
        assertEquals(0, metrics.kills);
        assertEquals(0L, metrics.net);
        assertEquals(0L, metrics.bestKill);
        assertFalse(metrics.medianKillNetGp != null);
        assertFalse(metrics.medianDeathLossGp != null);
    }

    @Test
    public void mediansCoverOnlyTheRetainedDetailWindow()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_30);
        Session session = pkSession("PK", NOW - 100L * DAY);
        engine.history.add(session);
        // Three retained kills: 100, 300, 200 -> median 200.
        long[] values = {100L, 300L, 200L};
        for (int index = 0; index < values.length; index++)
        {
            PkEncounter encounter = kill(engine, session, NOW - 10L * DAY + index);
            Transaction loot = receipt(session, NOW - 10L * DAY + index + 1L, values[index]);
            session.attachTransactionToEncounter(loot.getId(), encounter.getId(), false);
        }
        assertEquals(200.0d, session.pkMetrics().medianKillNetGp, 0.0d);
        assertFalse((session.pkMetrics().detailScope == PkDetailScope.RETAINED_WINDOW));

        // An older kill leaves the window: its net must not influence the retained median.
        PkEncounter old = kill(engine, session, NOW - 100L * DAY);
        Transaction oldLoot = receipt(session, NOW - 100L * DAY + 1L, 1_000L);
        session.attachTransactionToEncounter(oldLoot.getId(), old.getId(), false);
        engine.pkHistory.enforceDetailRetention(engine.uniqueProfileSessions(), 30, NOW);
        assertEquals(3, session.getPkEncounters().size());
        assertEquals("the retained-window median ignores compacted detail",
            200.0d, session.pkMetrics().medianKillNetGp, 0.0d);
        assertTrue((session.pkMetrics().detailScope == PkDetailScope.RETAINED_WINDOW));
    }

    @Test
    public void sessionMediansNeverUseAnotherSessionsRows()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_365);
        Session left = pkSession("left", NOW - DAY);
        Session right = pkSession("right", NOW - DAY);
        engine.history.add(left);
        engine.history.add(right);
        PkEncounter leftKill = kill(engine, left, NOW - DAY);
        Transaction leftLoot = receipt(left, NOW - DAY + 1L, 100L);
        left.attachTransactionToEncounter(leftLoot.getId(), leftKill.getId(), false);
        for (int index = 0; index < 3; index++)
        {
            PkEncounter rightKill = kill(engine, right, NOW - DAY + 10L + index);
            Transaction rightLoot = receipt(right, NOW - DAY + 11L + index, 900L + index);
            right.attachTransactionToEncounter(rightLoot.getId(), rightKill.getId(), false);
        }
        assertEquals(100.0d, left.pkMetrics().medianKillNetGp, 0.0d);
        assertEquals(901.0d, right.pkMetrics().medianKillNetGp, 0.0d);
    }

    // ── rollups ───────────────────────────────────────────────────────────────

    @Test
    public void explicitDeleteIsSubtractive()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_365);
        Session session = pkSession("doomed", NOW - DAY);
        engine.history.add(session);
        kill(engine, session, NOW - DAY);
        assertEquals(1, EngineProbe.profileFacts(engine.pkHistory, engine.uniqueProfileSessions()).getKills());
        assertTrue(engine.archive.deleteHistorySession(session.getId()));
        assertEquals("deliberately deleted facts disappear",
            0, EngineProbe.profileFacts(engine.pkHistory, engine.uniqueProfileSessions()).getKills());
    }

    @Test
    public void practicalScaleSoakReportsABoundedPlateau()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_90);
        int sessions = 250;
        int perSession = 10;
        int encounters = 0;
        for (int s = 0; s < sessions; s++)
        {
            Session session = pkSession("s" + s, NOW - 10L * DAY);
            engine.history.add(session);
            for (int e = 0; e < perSession; e++)
            {
                long at = NOW - 5L * DAY + s * 100_000L + e * 1_000L;
                PkEncounter encounter = kill(engine, session, at);
                Transaction loot = receipt(session, at + 1L, 100L + s + e);
                session.attachTransactionToEncounter(loot.getId(), encounter.getId(), false);
                encounters++;
            }
        }
        engine.pkHistory.enforceDetailRetention(engine.uniqueProfileSessions(), 90, NOW);
        int retainedFirst = 0;
        int anchorsFirst = 0;
        for (Session session : engine.uniqueProfileSessions())
        {
            retainedFirst += session.getPkEncounters().size();
            anchorsFirst += session.getPkAttributions().size();
        }
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS, retainedFirst);
        assertEquals("anchors stay proportional to live correction-eligible receipts",
            encounters, anchorsFirst);
        int sizeFirst = JsonCodec.gson().toJson(engine.createSavedState()).length();
        long netFirst = EngineProbe.profileFacts(engine.pkHistory, engine.uniqueProfileSessions()).getNet();
        assertEquals(encounters, EngineProbe.profileFacts(engine.pkHistory, engine.uniqueProfileSessions()).getKills());

        // Finalized growth with no new correction-eligible receipts must not grow persistent state.
        Session extra = pkSession("extra", NOW - DAY);
        engine.history.add(extra);
        for (int index = 0; index < 300; index++) kill(engine, extra, NOW - 1_000_000L + index);
        engine.pkHistory.enforceDetailRetention(engine.uniqueProfileSessions(), 90, NOW);
        int sizeSecond = JsonCodec.gson().toJson(engine.createSavedState()).length();
        assertEquals(encounters + 300,
            EngineProbe.profileFacts(engine.pkHistory, engine.uniqueProfileSessions()).getKills());
        int retainedSecond = 0;
        for (Session session : engine.uniqueProfileSessions())
        {
            retainedSecond += session.getPkEncounters().size();
        }
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS, retainedSecond);
        assertTrue("finalized growth must not grow the serialized profile: "
            + sizeFirst + " -> " + sizeSecond, sizeSecond <= sizeFirst + 20_000);

        SavedState state = engine.createSavedState();
        Engine reloaded = engine(ReceiptRetentionPeriod.DAYS_90);
        reloaded.restore(state, NOW);
        assertEquals(encounters + 300,
            EngineProbe.profileFacts(reloaded.pkHistory, reloaded.uniqueProfileSessions()).getKills());
        assertEquals(netFirst, EngineProbe.profileFacts(reloaded.pkHistory, reloaded.uniqueProfileSessions()).getNet());
    }

    @Test
    public void supplySplitAndCostsStayExactAcrossAnchorFinalization()
    {
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_30);
        Session session = pkSession("PK", NOW - 100L * DAY);
        engine.history.add(session);
        PkEncounter encounter = kill(engine, session, NOW - 100L * DAY);
        Transaction loot = receipt(session, NOW - 100L * DAY + 1_000L, 5_000L);
        session.attachTransactionToEncounter(loot.getId(), encounter.getId(), false);
        Transaction supply = supplyCost(session, NOW - 100L * DAY + 2_000L, 300L);
        session.attachTransactionToEncounter(supply.getId(), encounter.getId(), true);
        PkMetrics before = session.pkMetrics();
        assertEquals(300L, before.costs);
        assertEquals(300L, before.suppliesCosts);
        session.compactTransactionsBefore(NOW - 30L * DAY, null);
        PkMetrics after = session.pkMetrics();
        assertEquals(before.net, after.net);
        assertEquals(before.costs, after.costs);
        assertEquals(before.suppliesCosts, after.suppliesCosts);
        assertTrue(after.costSplitAvailable);
        assertEquals(0, session.getPkAttributions().size());
    }
}
