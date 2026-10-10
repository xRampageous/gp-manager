package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.SkullIcon;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Charter E: NONE / SAFE_PVP_OR_MINIGAME / DANGEROUS_PVP precedence is fail-closed, context never
 * touches session identity, opponent names never persist by default, and best/worst/net follow
 * the correction-aware encounter summary. Offline only; live acceptance is owner-gated.
 */
public class PvpContextTest
{
    private static final int LMS_REGION = 13658;
    private static final int CASTLE_WARS_REGION = 9520;
    private static final int LUMBRIDGE_REGION = 12850;

    @Test
    public void knownDangerousContextsAreConfident()
    {
        assertEquals(PvpContext.DANGEROUS_PVP, PvpContext.classify(true, false, false, false, 0));
        assertEquals("attackable on a PvP world", PvpContext.DANGEROUS_PVP,
            PvpContext.classify(false, true, true, false, LUMBRIDGE_REGION));
        assertTrue(PvpContext.DANGEROUS_PVP.isDangerous());
    }

    @Test
    public void positivelyIdentifiedSafeArenasWinOverEverything()
    {
        assertEquals(PvpContext.SAFE_PVP_OR_MINIGAME, PvpContext.classify(false, true, false, false, LMS_REGION));
        assertEquals(PvpContext.SAFE_PVP_OR_MINIGAME, PvpContext.classify(false, false, false, false, CASTLE_WARS_REGION));
        assertEquals("PvP Arena worlds are safe PvP", PvpContext.SAFE_PVP_OR_MINIGAME,
            PvpContext.classify(false, true, true, true, LUMBRIDGE_REGION));
        assertEquals("a safe arena inside conflicting dangerous signals stays safe", PvpContext.SAFE_PVP_OR_MINIGAME,
            PvpContext.classify(true, true, true, false, LMS_REGION));
    }

    @Test
    public void noSignalsIsNone()
    {
        assertEquals(PvpContext.NONE, PvpContext.classify(false, false, false, false, LUMBRIDGE_REGION));
        assertEquals("unknown region with no signals", PvpContext.NONE, PvpContext.classify(false, false, false, false, 0));
    }

    @Test
    public void conflictingOrInsufficientSignalsNeverBecomeDangerous()
    {
        assertEquals("PvP world bank safe zone (no attackable orb)", PvpContext.NONE,
            PvpContext.classify(false, false, true, false, LUMBRIDGE_REGION));
        assertEquals("attackable orb without a world/Wilderness fact is ambiguous", PvpContext.NONE,
            PvpContext.classify(false, true, false, false, LUMBRIDGE_REGION));
        assertEquals("orb alone with an unknown region", PvpContext.NONE, PvpContext.classify(false, true, false, false, 0));
    }

    @Test
    public void manualPkTripSessionIsIndependentOfContextDetection()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.startCustom("PK Trip", 2_000L);
        String tripId = engine.getActiveSession().getId();

        // Context is bookkeeping only: it never creates, renames or splits the manual session.
        engine.markPkLoot(Collections.singletonMap(4151, 1L), 10, 3_000L);
        engine.markPkDeath(4_000L);

        assertEquals(tripId, engine.getActiveSession().getId());
        assertEquals("PK Trip", engine.getActiveSession().getName());
        assertEquals(SessionMode.AUTO, engine.getActiveSession().getMode());
        assertEquals(2, engine.getActiveSession().pk().encounters());
        assertEquals("no automatic session was spawned", 0, engine.getHistory().size());
    }

    @Test
    public void opponentNamesNeverPersistByDefaultAndDeathsNeverCarryThem()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.markPkLoot(Collections.emptyMap(), 10, 2_000L);
        engine.markPkDeath(3_000L);
        String json = new com.google.gson.Gson().toJson(engine.getActiveSession());
        assertFalse("no opponent label is stored", json.contains("Player kill"));
        assertEquals(1, engine.getActiveSession().pk().deaths);
    }

    @Test
    public void correctionsRecomputeBestKillWorstDeathAndNet()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());

        engine.markPkLoot(Collections.singletonMap(4151, 1L), 10, 2_000L);
        Transaction loot = settle(engine, Collections.singletonMap(4151, 1L), 2_600L);
        assertNotNull(loot);
        assertEquals(TransactionType.PK_LOOT, loot.getType());
        assertEquals(1_000_000L, engine.bestKill());

        assertTrue(engine.correctTransaction(loot.getId(), Correction.IGNORE, 3_000L, "not mine"));
        assertEquals("an excluded kill receipt leaves the encounter but not its value", 1,
            engine.getActiveSession().pk().kills);
        assertEquals(0L, engine.bestKill());

        assertTrue(engine.correctTransaction(loot.getId(), Correction.REVENUE, 3_500L, "counted again"));
        assertEquals(1_000_000L, engine.bestKill());

        engine.markPkDeath(4_000L);
        assertEquals(1, engine.getActiveSession().pk().deaths);
        assertEquals("a death never changes the best kill", 1_000_000L, engine.bestKill());
    }

    @Test
    public void relogKeepsEncountersOnTheSameOwner()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.markPkLoot(Collections.emptyMap(), 10, 2_000L);
        engine.pauseForLifecycle(3_000L);
        engine.resume(4_000L, PauseReason.LIFECYCLE);
        engine.markPkDeath(5_000L);
        assertEquals(1, engine.getActiveSession().pk().kills);
        assertEquals(1, engine.getActiveSession().pk().deaths);
        assertEquals("relog never closes the owner", 0L, engine.getActiveSession().endedAtEpochMillis);
    }

    private static Transaction settle(Engine engine, Map<Integer, Long> quantities, long now)
    {
        engine.markDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(quantities);
        Transaction result = engine.processIfDirty(snapshot, now);
        return result != null ? result : engine.processIfDirty(snapshot, now + 1L);
    }

    private static Engine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
        };
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new Flow(id, "Item " + id, qty, 1_000_000, qty * 1_000_000L)));
            return flows;
        }, new TransactionClassifier(), config);
    }
}
