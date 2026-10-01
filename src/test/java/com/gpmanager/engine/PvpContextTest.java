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
        engine.startCustomSession("PK Trip", SessionMode.PK, 2_000L);
        String tripId = engine.getActiveSession().getId();

        // Context is bookkeeping only: it never creates, renames or splits the manual session.
        engine.markPkLootContext(Collections.singletonMap(4151, 1L), 10, "Player kill", 3_000L);
        engine.markPkDeath("Player death", 4_000L, null);

        assertEquals(tripId, engine.getActiveSession().getId());
        assertEquals("PK Trip", engine.getActiveSession().getName());
        assertEquals(SessionMode.PK, engine.getActiveSession().getMode());
        assertEquals(2, engine.getActiveSession().getPkEncounters().size());
        assertEquals("no automatic session was spawned", 0, engine.getHistory().size());
    }

    @Test
    public void opponentNamesNeverPersistByDefaultAndDeathsNeverCarryThem()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.markPkLootContext(Collections.emptyMap(), 10, "Player kill", 2_000L);
        engine.markPkDeath("Player death", 3_000L, null);
        for (PkEncounter encounter : engine.getActiveSession().getPkEncounters())
        {
            assertFalse(encounter.label.contains(":"));
        }
        assertEquals(EncounterType.DEATH, engine.getActiveSession().getPkEncounters().get(1).getType());
    }

    @Test
    public void correctionsRecomputeBestKillWorstDeathAndNet()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());

        engine.markPkLootContext(Collections.singletonMap(4151, 1L), 10, "Player kill", 2_000L);
        Transaction loot = settle(engine, Collections.singletonMap(4151, 1L), 2_600L);
        assertNotNull(loot);
        assertEquals(TransactionType.PK_LOOT, loot.getType());
        PkMetrics before = engine.getActiveSession().pkMetrics();
        assertEquals(1_000_000L, before.bestKill);
        assertEquals(1_000_000L, before.net);

        assertTrue(engine.correctTransaction(loot.getId(), Correction.IGNORE, 3_000L, "not mine"));
        PkMetrics excluded = engine.getActiveSession().pkMetrics();
        assertEquals("an excluded kill receipt leaves the encounter but not its value", 1, excluded.kills);
        assertEquals(0L, excluded.bestKill);
        assertEquals(0L, excluded.net);

        assertTrue(engine.correctTransaction(loot.getId(), Correction.REVENUE, 3_500L, "counted again"));
        assertEquals(1_000_000L, engine.getActiveSession().pkMetrics().bestKill);

        engine.markPkDeath("Player death", 4_000L, null);
        PkMetrics afterDeath = engine.getActiveSession().pkMetrics();
        assertEquals(1, afterDeath.deaths);
        assertEquals("a death without a settled loss is not a guessed worst death", 0L, afterDeath.largestDeathLoss);
    }

    @Test
    public void relogKeepsEncountersOnTheSameOwner()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.markPkLootContext(Collections.emptyMap(), 10, "Player kill", 2_000L);
        engine.pauseForLifecycle(3_000L);
        engine.resume(4_000L, PauseReason.LIFECYCLE);
        engine.markPkDeath("Player death", 5_000L, null);
        assertEquals(1, engine.getActiveSession().pkMetrics().kills);
        assertEquals(1, engine.getActiveSession().pkMetrics().deaths);
        assertEquals("relog never closes the owner", 0L, engine.getActiveSession().endedAtEpochMillis);
    }

    private static Transaction settle(Engine engine, Map<Integer, Long> quantities, long now)
    {
        engine.markInventoryDirty();
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
