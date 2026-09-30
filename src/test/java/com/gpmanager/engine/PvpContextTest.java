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
        assertEquals(Dh.DANGEROUS_PVP, Dh.classify(true, false, false, false, 0));
        assertEquals("attackable on a PvP world", Dh.DANGEROUS_PVP,
            Dh.classify(false, true, true, false, LUMBRIDGE_REGION));
        assertTrue(Dh.DANGEROUS_PVP.ws());
    }

    @Test
    public void positivelyIdentifiedSafeArenasWinOverEverything()
    {
        assertEquals(Dh.SAFE_PVP_OR_MINIGAME, Dh.classify(false, true, false, false, LMS_REGION));
        assertEquals(Dh.SAFE_PVP_OR_MINIGAME, Dh.classify(false, false, false, false, CASTLE_WARS_REGION));
        assertEquals("PvP Arena worlds are safe PvP", Dh.SAFE_PVP_OR_MINIGAME,
            Dh.classify(false, true, true, true, LUMBRIDGE_REGION));
        assertEquals("a safe arena inside conflicting dangerous signals stays safe", Dh.SAFE_PVP_OR_MINIGAME,
            Dh.classify(true, true, true, false, LMS_REGION));
    }

    @Test
    public void noSignalsIsNone()
    {
        assertEquals(Dh.NONE, Dh.classify(false, false, false, false, LUMBRIDGE_REGION));
        assertEquals("unknown region with no signals", Dh.NONE, Dh.classify(false, false, false, false, 0));
    }

    @Test
    public void conflictingOrInsufficientSignalsNeverBecomeDangerous()
    {
        assertEquals("PvP world bank safe zone (no attackable orb)", Dh.NONE,
            Dh.classify(false, false, true, false, LUMBRIDGE_REGION));
        assertEquals("attackable orb without a world/Wilderness fact is ambiguous", Dh.NONE,
            Dh.classify(false, true, false, false, LUMBRIDGE_REGION));
        assertEquals("orb alone with an unknown region", Dh.NONE, Dh.classify(false, true, false, false, 0));
    }

    @Test
    public void manualPkTripSessionIsIndependentOfContextDetection()
    {
        Am engine = engine();
        engine.rm(1_000L);
        engine.ajl("PK Trip", Cx.PK, 2_000L);
        String tripId = engine.getActiveSession().getId();

        // Context is bookkeeping only: it never creates, renames or splits the manual session.
        engine.zn(Collections.singletonMap(4151, 1L), 10, "Player kill", 3_000L);
        engine.zo("Player death", 4_000L, null);

        assertEquals(tripId, engine.getActiveSession().getId());
        assertEquals("PK Trip", engine.getActiveSession().getName());
        assertEquals(Cx.PK, engine.getActiveSession().getMode());
        assertEquals(2, engine.getActiveSession().getPkEncounters().size());
        assertEquals("no automatic session was spawned", 0, engine.getHistory().size());
    }

    @Test
    public void opponentNamesNeverPersistByDefaultAndDeathsNeverCarryThem()
    {
        Am engine = engine();
        engine.rm(1_000L);
        engine.zn(Collections.emptyMap(), 10, "Player kill", 2_000L);
        engine.zo("Player death", 3_000L, null);
        for (Bx encounter : engine.getActiveSession().getPkEncounters())
        {
            assertFalse(encounter.label.contains(":"));
        }
        assertEquals(Be.DEATH, engine.getActiveSession().getPkEncounters().get(1).getType());
    }

    @Test
    public void correctionsRecomputeBestKillWorstDeathAndNet()
    {
        Am engine = engine();
        engine.rm(1_000L);
        engine.setBaseline(Cc.empty());

        engine.zn(Collections.singletonMap(4151, 1L), 10, "Player kill", 2_000L);
        Ac loot = settle(engine, Collections.singletonMap(4151, 1L), 2_600L);
        assertNotNull(loot);
        assertEquals(Ai.PK_LOOT, loot.getType());
        Dt before = engine.getActiveSession().ava();
        assertEquals(1_000_000L, before.bestKill);
        assertEquals(1_000_000L, before.net);

        assertTrue(engine.qi(loot.getId(), Ah.IGNORE, 3_000L, "not mine"));
        Dt excluded = engine.getActiveSession().ava();
        assertEquals("an excluded kill receipt leaves the encounter but not its value", 1, excluded.kills);
        assertEquals(0L, excluded.bestKill);
        assertEquals(0L, excluded.net);

        assertTrue(engine.qi(loot.getId(), Ah.REVENUE, 3_500L, "counted again"));
        assertEquals(1_000_000L, engine.getActiveSession().ava().bestKill);

        engine.zo("Player death", 4_000L, null);
        Dt afterDeath = engine.getActiveSession().ava();
        assertEquals(1, afterDeath.deaths);
        assertEquals("a death without a settled loss is not a guessed worst death", 0L, afterDeath.largestDeathLoss);
    }

    @Test
    public void relogKeepsEncountersOnTheSameOwner()
    {
        Am engine = engine();
        engine.rm(1_000L);
        engine.zn(Collections.emptyMap(), 10, "Player kill", 2_000L);
        engine.acu(3_000L);
        engine.resume(4_000L, Ed.LIFECYCLE);
        engine.zo("Player death", 5_000L, null);
        assertEquals(1, engine.getActiveSession().ava().kills);
        assertEquals(1, engine.getActiveSession().ava().deaths);
        assertEquals("relog never closes the owner", 0L, engine.getActiveSession().endedAtEpochMillis);
    }

    private static Ac settle(Am engine, Map<Integer, Long> quantities, long now)
    {
        engine.yz();
        Cc snapshot = new Cc(quantities);
        Ac result = engine.adj(snapshot, now);
        return result != null ? result : engine.adj(snapshot, now + 1L);
    }

    private static Am engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
        };
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new Ab(id, "Item " + id, qty, 1_000_000, qty * 1_000_000L)));
            return flows;
        }, new TransactionClassifier(), config);
    }
}
