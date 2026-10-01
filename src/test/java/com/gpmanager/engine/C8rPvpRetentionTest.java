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

    private static GpManagerConfig config(Db period, int maxHistory)
    {
        return new GpManagerConfig()
        {
            @Override
            public int maxHistorySessions() { return maxHistory; }
            @Override
            public int maxTransactionsPerSession() { return 100_000; }
            @Override
            public Db receiptRetentionDays() { return period; }
            @Override
            public boolean autoStartSession() { return false; }
        };
    }

    private static Am engine(Db period)
    {
        return engine(period, 5_000);
    }

    private static Am engine(Db period, int maxHistory)
    {
        return new Am(deltas -> Collections.emptyList(), new TransactionClassifier(),
            config(period, maxHistory));
    }

    private static Ad pkSession(String name, long startedAt)
    {
        return new Ad(name, startedAt, Cx.PK);
    }

    private static Bx kill(Am engine, Ad session, long at)
    {
        Bx encounter = session.ke(Be.KILL, at, "Player kill",
            Bd.CONFIRMED, "Kill");
        engine.pkHistory.aeu(encounter);
        return encounter;
    }

    private static Bx death(Am engine, Ad session, long at)
    {
        Bx encounter = session.ke(Be.DEATH, at, "Player death",
            Bd.CONFIRMED, "Death");
        engine.pkHistory.aeu(encounter);
        return encounter;
    }

    private static Ac receipt(Ad session, long at, long netGp)
    {
        Ac transaction = new Ac(at, null,
            netGp >= 0 ? Ai.PK_LOOT : Ai.PK_DEATH_LOSS,
            Aj.PK_LOOT, "pk", "PKing", true,
            Collections.singletonList(new Ab(netGp >= 0 ? 995 : 385,
                netGp >= 0 ? "Coins" : "Shark", netGp >= 0 ? netGp : -1L,
                (int) Math.max(1L, Math.abs(netGp)), netGp)),
            Bd.CONFIRMED, "test", null);
        session.kf(transaction, 100_000);
        return transaction;
    }

    private static Ac supplyCost(Ad session, long at, long costsGp)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "pk supply", "PKing", true,
            Collections.singletonList(new Ab(385, "Shark", -1L, (int) Math.max(1L, costsGp), -costsGp)),
            Bd.CONFIRMED, "test", null);
        session.kf(transaction, 100_000);
        return transaction;
    }

    // ── retention boundaries ──────────────────────────────────────────────────

    @Test
    public void detailBoundaryRetainsExactlyTheLimit()
    {
        Am engine = engine(Db.DAYS_365);
        Ad session = pkSession("PK", NOW - DAY);
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
        Am engine = engine(Db.DAYS_365);
        Ad older = pkSession("older", NOW - DAY);
        Ad newer = pkSession("newer", NOW - 1_000L);
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
        Am engine = engine(Db.DAYS_30);
        Ad session = pkSession("PK", NOW - 100L * DAY);
        engine.history.add(session);
        long cutoff = NOW - 30L * DAY;
        kill(engine, session, cutoff - 1L);
        kill(engine, session, cutoff);
        kill(engine, session, cutoff + 1L);
        engine.pkHistory.ri(engine.akf(), 30, NOW);
        assertEquals(2, session.getPkEncounters().size());
        assertEquals(Di.RETAINED_WINDOW, session.pkProjection.ud());
        assertEquals(3, session.pkProjection.getKills());
    }

    @Test
    public void completionSequencePersistsAndNeverRenumbers()
    {
        Am engine = engine(Db.DAYS_365);
        Ad session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        kill(engine, session, NOW - 1_000L);
        long first = session.getPkEncounters().get(0).completionSequence;
        SavedState state = engine.qm();
        Am reloaded = engine(Db.DAYS_365);
        reloaded.restore(state, NOW);
        Ad restored = reloaded.getHistory().get(0);
        assertEquals(first, restored.getPkEncounters().get(0).completionSequence);
        Bx next = kill(reloaded, restored, NOW);
        assertTrue("new detail never reuses a completion position", next.completionSequence > first);
        assertEquals(2, restored.pkProjection.getKills());
    }

    @Test
    public void compactedDetailNeverBackfillsAfterNewerRowsDisappear()
    {
        Am engine = engine(Db.DAYS_365);
        Ad session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        for (int index = 0; index <= PkHistoryArchive.MAX_DETAILED_ENCOUNTERS; index++)
        {
            kill(engine, session, NOW - 2_000_000L + index);
        }
        long floor = engine.pkHistory.state.detailFloorSequence;
        assertTrue("the oldest position was compacted", floor >= 0L);
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS, session.getPkEncounters().size());
        // Removing a newer retained row must not resurrect or lower the floor.
        session.afw(session.getPkEncounters().get(session.getPkEncounters().size() - 1));
        engine.pkHistory.ri(engine.akf(), 365, NOW);
        assertEquals(floor, engine.pkHistory.state.detailFloorSequence);
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS - 1, session.getPkEncounters().size());
    }

    @Test
    public void serializedStateStaysBoundedAtTheCap()
    {
        Am engine = engine(Db.DAYS_365);
        Ad session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        for (int index = 0; index < 2_500; index++) kill(engine, session, NOW - 3_000_000L + index);
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS, session.getPkEncounters().size());
        SavedState state = engine.qm();
        int bytes = JsonCodec.gson().toJson(state).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue("bounded serialized profile: " + bytes, bytes < 1_500_000);
        assertEquals(2_500, state.getHistory().get(0).pkProjection.getKills());
    }

    // ── anchors / corrections ─────────────────────────────────────────────────

    @Test
    public void receiptFinalizationFoldsExactlyOnceAndRemovesTheAnchor()
    {
        Am engine = engine(Db.DAYS_30);
        Ad session = pkSession("PK", NOW - 100L * DAY);
        engine.history.add(session);
        Bx encounter = kill(engine, session, NOW - 100L * DAY);
        Ac loot = receipt(session, NOW - 100L * DAY + 1_000L, 5_000L);
        session.ll(loot.getId(), encounter.getId(), false);
        assertEquals(5_000L, session.ava().net);
        assertEquals(1, session.getPkAttributions().size());

        assertEquals(1, session.pj(NOW - 30L * DAY, null));
        assertEquals("anchors disappear once every receipt has finalized",
            0, session.getPkAttributions().size());
        assertEquals(5_000L, session.ava().net);
        assertEquals(5_000L, session.pkProjection.getNet());
        assertEquals(0, session.pj(NOW, null));
        assertEquals(5_000L, session.ava().net);
    }

    @Test
    public void correctionAfterDetailRemovalStillRevisesTheProjection()
    {
        Am engine = engine(Db.DAYS_365);
        Ad session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        Bx encounter = kill(engine, session, NOW - DAY);
        Ac loot = receipt(session, NOW - DAY + 1_000L, 5_000L);
        session.ll(loot.getId(), encounter.getId(), false);
        assertTrue(session.afw(encounter));
        assertEquals(0, session.getPkEncounters().size());
        assertEquals(1, session.getPkAttributions().size());
        assertEquals(5_000L, session.ava().net);

        assertTrue(session.qi(loot.getId(), Ah.IGNORE, NOW, "not mine"));
        assertEquals("correction updates the retained projection after detail loss",
            0L, session.ava().net);
        assertEquals("a zeroed correction stays correction-eligible", 1, session.getPkAttributions().size());
        assertEquals(0L, session.getPkAttributions().get(0).current().netGp);
        assertTrue(session.qi(loot.getId(), Ah.AUTO, NOW, "restored"));
        assertEquals(5_000L, session.ava().net);
        assertEquals(1, session.pkProjection.getKills());
    }

    @Test
    public void undoRemovesTheAnchorChildWithoutLosingCounts()
    {
        Am engine = engine(Db.DAYS_365);
        Ad session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        Bx encounter = kill(engine, session, NOW - DAY);
        Ac loot = receipt(session, NOW - DAY + 1_000L, 5_000L);
        session.ll(loot.getId(), encounter.getId(), false);
        assertNotNull(session.akc(NOW));
        assertEquals(0L, session.ava().net);
        assertEquals(0, session.getPkAttributions().size());
        assertEquals(1, session.ava().kills);
    }

    @Test
    public void findingF3UndoRedoRestoresTheMutableAnchorAndFinalizesOnce()
    {
        Am engine = engine(Db.DAYS_365);
        Ad session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        Bx encounter = kill(engine, session, NOW - DAY);
        Ac loot = receipt(session, NOW - DAY + 1_000L, 5_000L);
        session.ll(loot.getId(), encounter.getId(), false);

        assertNotNull(session.akc(NOW));
        assertNotNull(session.agn(NOW));
        assertEquals(5_000L, session.ava().net);
        assertEquals(0L, session.pkProjection.getNet());
        assertEquals(1, session.getPkAttributions().size());
        assertTrue(session.getPkAttributions().get(0).holds(loot.getId()));

        assertEquals(1, session.pj(NOW, null));
        assertEquals(0, session.getPkAttributions().size());
        assertEquals(5_000L, session.pkProjection.getNet());
        assertEquals(5_000L, session.ava().net);

        SavedState state = engine.qm();
        Am reloaded = engine(Db.DAYS_365);
        reloaded.restore(state, NOW);
        assertEquals(5_000L, reloaded.getHistory().get(0).pkProjection.getNet());
    }

    @Test
    public void findingF3UndoRedoMixedFinalizedAndLiveChildDoesNotDoubleCount()
    {
        Am engine = engine(Db.DAYS_365);
        Ad session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        Bx encounter = kill(engine, session, NOW - DAY);
        Ac finalized = receipt(session, NOW - DAY + 1_000L, 2_000L);
        session.ll(finalized.getId(), encounter.getId(), false);
        Ac live = receipt(session, NOW - DAY + 2_000L, 3_000L);
        session.ll(live.getId(), encounter.getId(), false);
        assertEquals(1, session.pj(NOW - DAY + 1_500L, null));
        assertEquals(2_000L, session.getPkAttributions().get(0).finalizedNet);

        assertNotNull(session.akc(NOW));
        assertEquals(2_000L, session.pkProjection.getNet());
        assertNotNull(session.agn(NOW));
        assertEquals(5_000L, session.ava().net);
        assertEquals(2_000L, session.pkProjection.getNet());
        assertEquals(1, session.getPkAttributions().size());

        assertEquals(1, session.pj(NOW, null));
        assertEquals(5_000L, session.pkProjection.getNet());
        assertEquals(0, session.getPkAttributions().size());
        assertEquals(5_000L,
            EngineProbe.profileFacts(engine.pkHistory, engine.akf()).getNet());

        SavedState state = engine.qm();
        Am reloaded = engine(Db.DAYS_365);
        reloaded.restore(state, NOW);
        assertEquals(5_000L,
            EngineProbe.profileFacts(reloaded.pkHistory, reloaded.akf()).getNet());
    }

    // ── extrema ───────────────────────────────────────────────────────────────

    @Test
    public void mutableWinnerOverFinalizedExtremumAndCorrectionRevealsTheNext()
    {
        Am engine = engine(Db.DAYS_30);
        Ad session = pkSession("PK", NOW - 100L * DAY);
        engine.history.add(session);
        Bx finalized = kill(engine, session, NOW - 100L * DAY);
        Ac first = receipt(session, NOW - 100L * DAY + 1_000L, 8_000_000L);
        session.ll(first.getId(), finalized.getId(), false);
        session.pj(NOW - 30L * DAY, null);

        Bx mutable = kill(engine, session, NOW - 1L * DAY);
        Ac second = receipt(session, NOW - 1L * DAY + 1_000L, 10_000_000L);
        session.ll(second.getId(), mutable.getId(), false);
        assertEquals(10_000_000L, session.ava().bestKill);
        assertEquals(18_000_000L, session.ava().net);

        assertTrue(session.qi(second.getId(), Ah.IGNORE, NOW, "wrong"));
        assertEquals("correcting the mutable winner reveals the finalized extremum",
            8_000_000L, session.ava().bestKill);
        assertEquals(8_000_000L, session.ava().net);
    }

    @Test
    public void equalMutableAndFinalizedMaximaStayIndependent()
    {
        Am engine = engine(Db.DAYS_30);
        Ad session = pkSession("PK", NOW - 100L * DAY);
        engine.history.add(session);
        Bx finalized = kill(engine, session, NOW - 100L * DAY);
        Ac first = receipt(session, NOW - 100L * DAY + 1_000L, 10_000_000L);
        session.ll(first.getId(), finalized.getId(), false);
        session.pj(NOW - 30L * DAY, null);

        Bx mutableA = kill(engine, session, NOW - 2L * DAY);
        Ac second = receipt(session, NOW - 2L * DAY + 1_000L, 10_000_000L);
        session.ll(second.getId(), mutableA.getId(), false);
        Bx mutableB = kill(engine, session, NOW - 1L * DAY);
        Ac third = receipt(session, NOW - 1L * DAY + 1_000L, 10_000_000L);
        session.ll(third.getId(), mutableB.getId(), false);
        assertEquals(10_000_000L, session.ava().bestKill);

        assertTrue(session.qi(second.getId(), Ah.IGNORE, NOW, "wrong"));
        assertEquals("the remaining equal mutable maximum keeps the extremum",
            10_000_000L, session.ava().bestKill);
        assertTrue(session.qi(third.getId(), Ah.IGNORE, NOW, "wrong"));
        assertEquals("the finalized equal maximum still holds", 10_000_000L, session.ava().bestKill);
    }

    @Test
    public void negativeKillNetAndPositiveDeathNetFollowTheMetricFloors()
    {
        Am engine = engine(Db.DAYS_365);
        Ad session = pkSession("PK", NOW - DAY);
        engine.history.add(session);
        Bx kill = kill(engine, session, NOW - DAY);
        Ac loss = receipt(session, NOW - DAY + 1_000L, -50L);
        session.ll(loss.getId(), kill.getId(), false);
        Bx death = death(engine, session, NOW - DAY + 2_000L);
        Ac gain = receipt(session, NOW - DAY + 3_000L, 20L);
        session.ll(gain.getId(), death.getId(), false);
        Dt metrics = session.ava();
        assertEquals("best kill is floored at zero", 0L, metrics.bestKill);
        assertEquals(-50L, metrics.totalKillNet);
        assertEquals("death loss is floored at zero", 0L, metrics.largestDeathLoss);
        assertEquals(-30L, metrics.net);
    }

    // ── coverage / medians ────────────────────────────────────────────────────

    @Test
    public void zeroEncountersExposeAdditiveZeroButNoExtremaOrMedians()
    {
        Ad session = pkSession("PK", NOW - DAY);
        Dt metrics = session.ava();
        assertEquals(0, metrics.kills);
        assertEquals(0L, metrics.net);
        assertEquals(0L, metrics.bestKill);
        assertFalse(metrics.medianKillNetGp != null);
        assertFalse(metrics.medianDeathLossGp != null);
    }

    @Test
    public void mediansCoverOnlyTheRetainedDetailWindow()
    {
        Am engine = engine(Db.DAYS_30);
        Ad session = pkSession("PK", NOW - 100L * DAY);
        engine.history.add(session);
        // Three retained kills: 100, 300, 200 -> median 200.
        long[] values = {100L, 300L, 200L};
        for (int index = 0; index < values.length; index++)
        {
            Bx encounter = kill(engine, session, NOW - 10L * DAY + index);
            Ac loot = receipt(session, NOW - 10L * DAY + index + 1L, values[index]);
            session.ll(loot.getId(), encounter.getId(), false);
        }
        assertEquals(200.0d, session.ava().medianKillNetGp, 0.0d);
        assertFalse((session.ava().detailScope == Di.RETAINED_WINDOW));

        // An older kill leaves the window: its net must not influence the retained median.
        Bx old = kill(engine, session, NOW - 100L * DAY);
        Ac oldLoot = receipt(session, NOW - 100L * DAY + 1L, 1_000L);
        session.ll(oldLoot.getId(), old.getId(), false);
        engine.pkHistory.ri(engine.akf(), 30, NOW);
        assertEquals(3, session.getPkEncounters().size());
        assertEquals("the retained-window median ignores compacted detail",
            200.0d, session.ava().medianKillNetGp, 0.0d);
        assertTrue((session.ava().detailScope == Di.RETAINED_WINDOW));
    }

    @Test
    public void sessionMediansNeverUseAnotherSessionsRows()
    {
        Am engine = engine(Db.DAYS_365);
        Ad left = pkSession("left", NOW - DAY);
        Ad right = pkSession("right", NOW - DAY);
        engine.history.add(left);
        engine.history.add(right);
        Bx leftKill = kill(engine, left, NOW - DAY);
        Ac leftLoot = receipt(left, NOW - DAY + 1L, 100L);
        left.ll(leftLoot.getId(), leftKill.getId(), false);
        for (int index = 0; index < 3; index++)
        {
            Bx rightKill = kill(engine, right, NOW - DAY + 10L + index);
            Ac rightLoot = receipt(right, NOW - DAY + 11L + index, 900L + index);
            right.ll(rightLoot.getId(), rightKill.getId(), false);
        }
        assertEquals(100.0d, left.ava().medianKillNetGp, 0.0d);
        assertEquals(901.0d, right.ava().medianKillNetGp, 0.0d);
    }

    // ── rollups ───────────────────────────────────────────────────────────────

    @Test
    public void explicitDeleteIsSubtractive()
    {
        Am engine = engine(Db.DAYS_365);
        Ad session = pkSession("doomed", NOW - DAY);
        engine.history.add(session);
        kill(engine, session, NOW - DAY);
        assertEquals(1, EngineProbe.profileFacts(engine.pkHistory, engine.akf()).getKills());
        assertTrue(engine.archive.qu(session.getId()));
        assertEquals("deliberately deleted facts disappear",
            0, EngineProbe.profileFacts(engine.pkHistory, engine.akf()).getKills());
    }

    @Test
    public void practicalScaleSoakReportsABoundedPlateau()
    {
        Am engine = engine(Db.DAYS_90);
        int sessions = 250;
        int perSession = 10;
        int encounters = 0;
        for (int s = 0; s < sessions; s++)
        {
            Ad session = pkSession("s" + s, NOW - 10L * DAY);
            engine.history.add(session);
            for (int e = 0; e < perSession; e++)
            {
                long at = NOW - 5L * DAY + s * 100_000L + e * 1_000L;
                Bx encounter = kill(engine, session, at);
                Ac loot = receipt(session, at + 1L, 100L + s + e);
                session.ll(loot.getId(), encounter.getId(), false);
                encounters++;
            }
        }
        engine.pkHistory.ri(engine.akf(), 90, NOW);
        int retainedFirst = 0;
        int anchorsFirst = 0;
        for (Ad session : engine.akf())
        {
            retainedFirst += session.getPkEncounters().size();
            anchorsFirst += session.getPkAttributions().size();
        }
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS, retainedFirst);
        assertEquals("anchors stay proportional to live correction-eligible receipts",
            encounters, anchorsFirst);
        int sizeFirst = JsonCodec.gson().toJson(engine.qm()).length();
        long netFirst = EngineProbe.profileFacts(engine.pkHistory, engine.akf()).getNet();
        assertEquals(encounters, EngineProbe.profileFacts(engine.pkHistory, engine.akf()).getKills());

        // Finalized growth with no new correction-eligible receipts must not grow persistent state.
        Ad extra = pkSession("extra", NOW - DAY);
        engine.history.add(extra);
        for (int index = 0; index < 300; index++) kill(engine, extra, NOW - 1_000_000L + index);
        engine.pkHistory.ri(engine.akf(), 90, NOW);
        int sizeSecond = JsonCodec.gson().toJson(engine.qm()).length();
        assertEquals(encounters + 300,
            EngineProbe.profileFacts(engine.pkHistory, engine.akf()).getKills());
        int retainedSecond = 0;
        for (Ad session : engine.akf())
        {
            retainedSecond += session.getPkEncounters().size();
        }
        assertEquals(PkHistoryArchive.MAX_DETAILED_ENCOUNTERS, retainedSecond);
        assertTrue("finalized growth must not grow the serialized profile: "
            + sizeFirst + " -> " + sizeSecond, sizeSecond <= sizeFirst + 20_000);

        SavedState state = engine.qm();
        Am reloaded = engine(Db.DAYS_90);
        reloaded.restore(state, NOW);
        assertEquals(encounters + 300,
            EngineProbe.profileFacts(reloaded.pkHistory, reloaded.akf()).getKills());
        assertEquals(netFirst, EngineProbe.profileFacts(reloaded.pkHistory, reloaded.akf()).getNet());
    }

    @Test
    public void supplySplitAndCostsStayExactAcrossAnchorFinalization()
    {
        Am engine = engine(Db.DAYS_30);
        Ad session = pkSession("PK", NOW - 100L * DAY);
        engine.history.add(session);
        Bx encounter = kill(engine, session, NOW - 100L * DAY);
        Ac loot = receipt(session, NOW - 100L * DAY + 1_000L, 5_000L);
        session.ll(loot.getId(), encounter.getId(), false);
        Ac supply = supplyCost(session, NOW - 100L * DAY + 2_000L, 300L);
        session.ll(supply.getId(), encounter.getId(), true);
        Dt before = session.ava();
        assertEquals(300L, before.costs);
        assertEquals(300L, before.suppliesCosts);
        session.pj(NOW - 30L * DAY, null);
        Dt after = session.ava();
        assertEquals(before.net, after.net);
        assertEquals(before.costs, after.costs);
        assertEquals(before.suppliesCosts, after.suppliesCosts);
        assertTrue(after.costSplitAvailable);
        assertEquals(0, session.getPkAttributions().size());
    }
}
