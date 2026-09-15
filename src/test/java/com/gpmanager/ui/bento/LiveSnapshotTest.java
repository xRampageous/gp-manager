package com.gpmanager.ui.bento;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.ContainerSnapshot;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Live page read model built from a real engine: figures match SessionMetrics, recent rows
 * follow the action-to-surface policy (quiet rows never appear), runs and goal flow through.
 */
public class LiveSnapshotTest
{
    private static final int LOGS = 1519;
    private static final int RUNE = 554;
    private static final int SHARK = 385;

    @Test
    public void noSessionIsWaiting()
    {
        GpManagerEngine engine = engine();
        LiveSnapshot s = LiveSnapshot.capture(engine, 1_000L, false, false);
        assertFalse(s.hasSession);
        assertEquals(SessionsSnapshot.FREE_PLAY, s.ownerLabel);
        assertTrue(s.recent.isEmpty());
        assertTrue(s.ribbonMarks.isEmpty());
    }

    @Test
    public void figuresRecentRunsAndGoalComeFromTheEngine()
    {
        GpManagerEngine engine = engine();
        long now = 10_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(Collections.emptyMap()));

        // Gain: 27 logs.
        ProfitTransaction gain = settle(engine, snapshot(map(LOGS, 27L)), now + 1_000L);
        assertNotNull(gain);
        // Quiet cost: runes used (CAST is Ledger-only).
        engine.setBaseline(snapshot(map(LOGS, 27L, RUNE, 100L)));
        ProfitTransaction cast = settle(engine, snapshot(map(LOGS, 27L, RUNE, 55L)), now + 2_000L);
        assertNotNull(cast);
        cast.setActionKind(ActionKind.CAST);
        // Visible cost: shark eaten.
        engine.setBaseline(snapshot(map(LOGS, 27L, RUNE, 55L, SHARK, 2L)));
        ProfitTransaction ate = settle(engine, snapshot(map(LOGS, 27L, RUNE, 55L, SHARK, 0L)), now + 3_000L);
        assertNotNull(ate);
        ate.setActionKind(ActionKind.EAT);

        engine.getActiveSession().setProfitTargetGp(200_000L);

        long later = now + 60_000L;
        LiveSnapshot s = LiveSnapshot.capture(engine, later, true, false);
        assertTrue(s.hasSession);
        assertFalse(s.custom);
        assertEquals(engine.getMetrics(later).getNet(), s.net);
        assertEquals(engine.getMetrics(later).getRevenue(), s.gains);
        assertEquals(engine.getMetrics(later).getCosts(), s.loss + s.supplies);
        if (engine.getMetrics(later).isCostSplitAvailable())
        {
            // The model's split wins when the engine vouches for it.
            assertEquals(engine.getMetrics(later).getSuppliesCosts(), s.supplies);
            assertEquals(engine.getMetrics(later).getOtherCosts(), s.loss);
        }
        assertFalse(s.reclaimPending);
        assertFalse(s.reclaimArmed);
        assertEquals(0L, s.reclaimOutstanding);
        // Runes cast and shark eaten are consumables; nothing else left the account.
        assertEquals(engine.getMetrics(later).getCosts(), s.supplies);
        assertEquals(0L, s.loss);
        assertEquals(Long.valueOf(200_000L), s.goalGp);
        // A legacy per-session target reads as a net goal through the engine's progress.
        assertNotNull(s.goal);
        assertEquals(com.gpmanager.model.GoalDefinition.Kind.NET, s.goal.kind);
        assertEquals("200k", s.goal.label);
        assertTrue(s.goal.available);
        assertFalse(s.goal.reached);
        assertTrue(s.neutralZone);

        List<String> verbs = new ArrayList<>();
        for (LiveSnapshot.Recent r : s.recent)
        {
            verbs.add(r.verb);
            assertFalse("quiet rows never reach Live", "runes used".equals(r.verb));
        }
        assertTrue(verbs.contains("ate"));
        assertEquals("ate", s.recent.get(0).verb);
        assertEquals("Shark", s.recent.get(0).name);
        assertEquals("×2", s.recent.get(0).qty);
        assertEquals("received", s.recent.get(1).verb);
        assertEquals("×27", s.recent.get(1).qty);

        // One owner, one bar: the ribbon carries marks only; free play until a session starts.
        assertTrue(s.freePlay);
        assertFalse(s.auto);
        assertEquals(engine.getActiveSession().getStartedAtEpochMillis(), s.startedAt);
        assertEquals(0, s.sessionsToday);   // free play is not a session
        assertEquals(s.net, s.overallToday);
    }

    @Test
    public void notableDropReadsTheContext()
    {
        GpManagerEngine engine = engine();
        long now = 10_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(map(SHARK, 2L)));
        ProfitTransaction ate = settle(engine, snapshot(map(SHARK, 1L)), now + 1_000L);
        assertNotNull(ate);
        ate.setActionKind(ActionKind.EAT);
        engine.setBaseline(snapshot(map(SHARK, 1L)));
        settle(engine, snapshot(map(SHARK, 1L, LOGS, 100L)), now + 2_000L);

        LiveSnapshot s = LiveSnapshot.capture(engine, now + 60_000L, false, false,
            new LiveContext(4_000L, null, null));
        assertNotNull(s.notableDrop);
        assertEquals(LOGS, s.notableDrop.itemId);
        assertEquals(4_800L, s.notableDrop.value);

        // Under the threshold: no notable drop.
        LiveSnapshot quiet = LiveSnapshot.capture(engine, now + 60_000L, false, false,
            new LiveContext(1_000_000L, null, null));
        assertNull(quiet.notableDrop);
        assertEquals("Rival Name", LiveSnapshot.opponent("Kill: Rival Name"));
        assertEquals("Super antifire", LiveSnapshot.baseName("Super antifire(4)"));
    }

    @Test
    public void goalParsingAcceptsSuffixes()
    {
        assertEquals(200_000L, BentoPanel.parseGp("200k"));
        assertEquals(1_500_000L, BentoPanel.parseGp("1.5m"));
        assertEquals(2_000_000_000L, BentoPanel.parseGp("2b"));
        assertEquals(12_345L, BentoPanel.parseGp("12345"));
    }

    private static GpManagerEngine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public int minimumTransactionValue() { return 1; }
        };
        return new GpManagerEngine(deltas ->
        {
            List<ItemFlow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                int price = id == LOGS ? 48 : id == RUNE ? 4 : 800;
                String name = id == LOGS ? "Willow logs" : id == RUNE ? "Fire rune" : "Shark";
                flows.add(new ItemFlow(id, name, delta.getValue(), price, delta.getValue() * price,
                    ItemPriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static ProfitTransaction settle(GpManagerEngine engine, ContainerSnapshot snapshot, long now)
    {
        engine.markInventoryDirty();
        ProfitTransaction first = engine.processIfDirty(snapshot, now);
        ProfitTransaction settled = engine.processIfDirty(snapshot, now + 600L);
        return settled == null ? first : settled;
    }

    private static ContainerSnapshot snapshot(Map<Integer, Long> items)
    {
        return new ContainerSnapshot(items);
    }

    private static Map<Integer, Long> map(Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((Integer) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }

    @Test
    public void goalKindsReadTheEngineProgress()
    {
        GpManagerEngine engine = engine();
        long now = 10_000L;
        engine.togglePause(now);
        engine.setBaseline(snapshot(Collections.emptyMap()));
        settle(engine, snapshot(map(LOGS, 100L)), now + 1_000L);

        // Kills: the profile's session goal wins over the legacy target; nothing killed yet → 0 / 15.
        engine.getActiveSession().setProfitTargetGp(999L);
        engine.setGoalDefinitions(Collections.singletonList(new com.gpmanager.model.GoalDefinition(
            com.gpmanager.model.GoalDefinition.Kind.KILLS, com.gpmanager.model.GoalDefinition.Scope.SESSION, 15L, Collections.emptyList())));
        LiveSnapshot kills = LiveSnapshot.capture(engine, now + 60_000L, false, false);
        assertNotNull(kills.goal);
        assertEquals(com.gpmanager.model.GoalDefinition.Kind.KILLS, kills.goal.kind);
        assertEquals("15 kills", kills.goal.label);
        if (kills.goal.available)
        {
            assertEquals("0 / 15", kills.goal.progress);
            assertFalse(kills.goal.reached);
        }
        else
        {
            assertEquals("—", kills.goal.progress);
            assertNotNull(kills.goal.unavailableReason);
        }

        // GP/h: reached when the rate clears the target.
        engine.setGoalDefinitions(Collections.singletonList(new com.gpmanager.model.GoalDefinition(
            com.gpmanager.model.GoalDefinition.Kind.GP_PER_HOUR, com.gpmanager.model.GoalDefinition.Scope.SESSION, 1L, Collections.emptyList())));
        LiveSnapshot rate = LiveSnapshot.capture(engine, now + 60_000L, false, false);
        assertNotNull(rate.goal);
        assertEquals("1/h", rate.goal.label);
        assertTrue(rate.goal.progress.endsWith("/h") || "—".equals(rate.goal.progress));

        // No goal at all: null.
        engine.setGoalDefinitions(Collections.emptyList());
        engine.getActiveSession().setProfitTargetGp(null);
        assertNull(LiveSnapshot.capture(engine, now + 60_000L, false, false).goal);
        assertEquals("Kills", LiveSnapshot.Goal.kindLabel(com.gpmanager.model.GoalDefinition.Kind.KILLS));
        assertEquals("1.50M/h", LiveSnapshot.Goal.targetLabel(com.gpmanager.model.GoalDefinition.Kind.GP_PER_HOUR, 1_500_000L));
    }

    @Test
    public void goalsAndTileLayoutSurviveEngineSaveAndRestore()
    {
        GpManagerEngine engine = engine();
        com.gpmanager.model.GoalDefinition goal = new com.gpmanager.model.GoalDefinition(
            com.gpmanager.model.GoalDefinition.Kind.NET, com.gpmanager.model.GoalDefinition.Scope.SESSION, 200_000L,
            java.util.Arrays.asList(50, 80));
        engine.setGoalDefinitions(Collections.singletonList(goal));
        com.gpmanager.model.TileLayout layout = new com.gpmanager.model.TileLayout(Collections.singletonMap("live",
            new com.gpmanager.model.TileLayout.PageLayout(java.util.Arrays.asList("stat", "party", "recent"),
                new java.util.HashSet<>(Collections.singletonList("party")))));
        engine.setTileLayout(layout);

        com.gpmanager.persistence.SavedState saved = engine.createSavedState();
        GpManagerEngine fresh = engine();
        fresh.restore(saved);

        assertEquals(1, fresh.getGoalDefinitions().size());
        assertEquals(200_000L, fresh.getGoalDefinitions().get(0).getTargetValue());
        assertTrue(fresh.getTileLayout().isHidden("live", "party"));
        assertEquals(java.util.Arrays.asList("stat", "party", "recent"), fresh.getTileLayout().getOrderedTileIds("live"));
    }
}
