package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Gauntlet-style instances are ownership-neutral on entry, inside and on the exit restore. */
public class NeutralZoneTrackerTest
{
    @Test
    public void gauntletInstancesAreNeutralAndTheLobbyIsNot()
    {
        assertTrue(MinigameRegionHints.xl(7512));
        assertTrue(MinigameRegionHints.xl(7768));
        assertFalse("reward chest is claimed in the lobby", MinigameRegionHints.xl(12127));
        assertFalse(MinigameRegionHints.xl(12850)); // Lumbridge
        assertFalse(MinigameRegionHints.xl(0));
    }

    @Test
    public void insideEveryTickThenExactlyOneLeaveSignal()
    {
        NeutralZoneTracker tracker = new NeutralZoneTracker();
        assertEquals(NeutralZoneTracker.Signal.NONE, tracker.awr(12127));
        assertEquals(NeutralZoneTracker.Signal.ENTERED, tracker.awr(7512));
        assertEquals(NeutralZoneTracker.Signal.INSIDE, tracker.awr(7512));
        assertTrue(tracker.isInside());
        assertEquals(NeutralZoneTracker.Signal.LEFT, tracker.awr(12127));
        assertFalse(tracker.isInside());
        assertEquals(NeutralZoneTracker.Signal.NONE, tracker.awr(12127));
    }

    @Test
    public void unknownRegionDuringLoadingKeepsStateWithoutSignalling()
    {
        NeutralZoneTracker tracker = new NeutralZoneTracker();
        tracker.awr(7768);
        assertEquals(NeutralZoneTracker.Signal.NONE, tracker.awr(0));
        assertTrue(tracker.isInside());
        assertEquals(NeutralZoneTracker.Signal.LEFT, tracker.awr(12127));
        tracker.awr(7768);
        tracker.reset();
        assertFalse(tracker.isInside());
        assertEquals(NeutralZoneTracker.Signal.NONE, tracker.awr(12127));
    }

    @Test
    public void engineBooksGearStoreAndRestoreAsTransfersInsideTheZone() throws Exception
    {
        GpManagerConfigStub config = new GpManagerConfigStub();
        Am engine = new Am(deltas ->
        {
            java.util.List<Ab> flows = new java.util.ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new Ab(id, "Item " + id, qty, 100_000, qty * 100_000L)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.rm(1_000L);
        java.util.Map<Integer, Long> gear = new java.util.HashMap<>();
        gear.put(4151, 1L);
        gear.put(11802, 1L);
        engine.setBaseline(new Cc(gear));

        // Entering the instance: the tick marks the zone, then the gear vanishes.
        NeutralZoneTracker tracker = new NeutralZoneTracker();
        assertEquals(NeutralZoneTracker.Signal.ENTERED, tracker.awr(7512));
        engine.lw();
        engine.zh("Neutral zone instance", config.stabilizationTicks() + 4);
        engine.yz();
        Ac stored = settle(engine, Cc.empty(), 1_600L);
        assertEquals(Ai.TRANSFER, stored.getType());
        assertFalse(stored.isCounted());
        assertEquals("Neutral zone instance", stored.getNote());
        assertTrue(stored.getExplanation().startsWith("Ownership-neutral transfer: Neutral zone"));

        // Leaving: one closing window, then the gear comes back.
        assertEquals(NeutralZoneTracker.Signal.LEFT, tracker.awr(12127));
        engine.rg(20);
        engine.yz();
        java.util.Map<Integer, Long> lobbyItems = new java.util.HashMap<>(gear);
        lobbyItems.put(526, 1L); // reward chest item arriving beside the restored gear
        Ac loot = settle(engine, new Cc(lobbyItems), 4_000L);
        assertEquals(Ai.GAIN, loot.getType());
        assertTrue("unmatched lobby chest loot remains counted", loot.isCounted());
        assertEquals(526, loot.getFlows().get(0).itemId);
        assertEquals(100_000L, engine.getMetrics(8_000L).net);

        Ac restored = null;
        for (Ac row : engine.getActiveSession().getTransactions())
        {
            if ("Neutral zone exit restore".equals(row.getNote()))
            {
                restored = row;
            }
        }
        assertTrue("restored gear remains an uncounted audit row", restored != null && !restored.isCounted());
        assertEquals(2, restored.getFlows().size());
    }

    @Test
    public void splitEntryRemovalBatchesAreRestoredWithoutHidingSameIdOrExtraGains() throws Exception
    {
        GpManagerConfigStub config = new GpManagerConfigStub();
        Am engine = new Am(deltas ->
        {
            java.util.List<Ab> flows = new java.util.ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new Ab(
                id, "Item " + id, qty, 100_000, qty * 100_000L)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.rm(1_000L);
        java.util.Map<Integer, Long> gear = new java.util.HashMap<>();
        gear.put(4151, 1L);
        gear.put(11802, 1L);
        gear.put(385, 2L); // in-zone supplies must not be captured as entry gear forever
        engine.setBaseline(new Cc(gear));

        engine.lw();
        engine.zh("Neutral zone instance", config.stabilizationTicks() + 4);
        engine.yz();
        java.util.Map<Integer, Long> firstBatchItems = new java.util.HashMap<>();
        firstBatchItems.put(11802, 1L);
        firstBatchItems.put(385, 2L);
        Ac firstStore = settle(
            engine, new Cc(firstBatchItems), 1_600L);
        assertFalse(firstStore.isCounted());

        // Equipment and inventory callbacks can report separate stable removals.
        engine.zh("Neutral zone instance", config.stabilizationTicks() + 4);
        engine.yz();
        java.util.Map<Integer, Long> secondBatchItems = new java.util.HashMap<>();
        secondBatchItems.put(385, 2L);
        Ac secondStore = settle(
            engine, new Cc(secondBatchItems), 3_400L);
        assertFalse(secondStore.isCounted());

        // The bounded capture expires even though the tracker continues to mark
        // every in-zone sample as TRANSFER. A later supply loss cannot join the
        // set of entry-stored items.
        engine.adj(new Cc(secondBatchItems), 4_600L);
        engine.adj(new Cc(secondBatchItems), 5_200L);
        engine.zh("Neutral zone instance", config.stabilizationTicks() + 4);
        engine.yz();
        java.util.Map<Integer, Long> afterSupplyUse = new java.util.HashMap<>();
        afterSupplyUse.put(385, 1L);
        Ac supplyUse = settle(
            engine, new Cc(afterSupplyUse), 5_800L);
        assertFalse(supplyUse.isCounted());

        engine.rg(20);
        engine.yz();
        java.util.Map<Integer, Long> restoredPlusLoot = new java.util.HashMap<>();
        restoredPlusLoot.put(4151, 2L); // one stored whip plus one unrelated whip gain
        restoredPlusLoot.put(11802, 1L);
        restoredPlusLoot.put(526, 1L); // unrelated lobby reward
        restoredPlusLoot.put(385, 2L); // a later supply gain is outside the entry capture
        Ac gain = settle(
            engine, new Cc(restoredPlusLoot), 7_600L);

        assertEquals(Ai.GAIN, gain.getType());
        assertTrue("same-id excess and unrelated reward remain counted", gain.isCounted());
        assertEquals(3, gain.getFlows().size());
        assertEquals(1L, gain.getFlows().stream().filter(flow -> flow.itemId == 4151)
            .findFirst().get().quantityDelta);
        assertEquals(1L, gain.getFlows().stream().filter(flow -> flow.itemId == 526)
            .findFirst().get().quantityDelta);
        assertEquals(1L, gain.getFlows().stream().filter(flow -> flow.itemId == 385)
            .findFirst().get().quantityDelta);

        Ac restored = null;
        for (Ac row : engine.getActiveSession().getTransactions())
        {
            if ("Neutral zone exit restore".equals(row.getNote()))
            {
                restored = row;
            }
        }
        assertTrue("both separately removed items remain an uncounted restore row",
            restored != null && !restored.isCounted());
        assertEquals(2, restored.getFlows().size());
        assertEquals(300_000L, engine.getMetrics(11_000L).net);
    }

    private static Ac settle(Am engine, Cc snapshot, long firstTick)
    {
        engine.adj(snapshot, firstTick);
        engine.adj(snapshot, firstTick + 600L);
        return engine.adj(snapshot, firstTick + 1_200L);
    }

    private static final class GpManagerConfigStub implements GpManagerConfig
    {
        @Override
        public int stabilizationTicks()
        {
            return 2;
        }

        @Override
        public boolean keepTransferAuditRows()
        {
            return true;
        }
    }
}
