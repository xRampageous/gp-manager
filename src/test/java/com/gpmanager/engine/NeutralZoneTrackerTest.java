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
        assertTrue(MinigameRegionHints.isNeutralZoneRegion(7512));
        assertTrue(MinigameRegionHints.isNeutralZoneRegion(7768));
        assertFalse("reward chest is claimed in the lobby", MinigameRegionHints.isNeutralZoneRegion(12127));
        assertFalse(MinigameRegionHints.isNeutralZoneRegion(12850)); // Lumbridge
        assertFalse(MinigameRegionHints.isNeutralZoneRegion(0));
    }

    @Test
    public void insideEveryTickThenExactlyOneLeaveSignal()
    {
        NeutralZoneTracker tracker = new NeutralZoneTracker();
        assertEquals(NeutralZoneTracker.Signal.NONE, tracker.onRegion(12127));
        assertEquals(NeutralZoneTracker.Signal.ENTERED, tracker.onRegion(7512));
        assertEquals(NeutralZoneTracker.Signal.INSIDE, tracker.onRegion(7512));
        assertTrue(tracker.isInside());
        assertEquals(NeutralZoneTracker.Signal.LEFT, tracker.onRegion(12127));
        assertFalse(tracker.isInside());
        assertEquals(NeutralZoneTracker.Signal.NONE, tracker.onRegion(12127));
    }

    @Test
    public void unknownRegionDuringLoadingKeepsStateWithoutSignalling()
    {
        NeutralZoneTracker tracker = new NeutralZoneTracker();
        tracker.onRegion(7768);
        assertEquals(NeutralZoneTracker.Signal.NONE, tracker.onRegion(0));
        assertTrue(tracker.isInside());
        assertEquals(NeutralZoneTracker.Signal.LEFT, tracker.onRegion(12127));
        tracker.onRegion(7768);
        tracker.reset();
        assertFalse(tracker.isInside());
        assertEquals(NeutralZoneTracker.Signal.NONE, tracker.onRegion(12127));
    }

    @Test
    public void engineBooksGearStoreAndRestoreAsTransfersInsideTheZone() throws Exception
    {
        GpManagerConfigStub config = new GpManagerConfigStub();
        Engine engine = new Engine(deltas ->
        {
            java.util.List<Flow> flows = new java.util.ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new Flow(id, "Item " + id, qty, 100_000, qty * 100_000L)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        java.util.Map<Integer, Long> gear = new java.util.HashMap<>();
        gear.put(4151, 1L);
        gear.put(11802, 1L);
        engine.setBaseline(new ContainerSnapshot(gear));

        // Entering the instance: the tick marks the zone, then the gear vanishes.
        NeutralZoneTracker tracker = new NeutralZoneTracker();
        assertEquals(NeutralZoneTracker.Signal.ENTERED, tracker.onRegion(7512));
        engine.beginNeutralZoneTransfer();
        engine.markTransfer("Neutral zone instance", config.stabilizationTicks() + 4);
        engine.markInventoryDirty();
        Transaction stored = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.TRANSFER, stored.getType());
        assertFalse(stored.isCounted());
        assertEquals("Neutral zone instance", stored.getNote());
        assertTrue(stored.getExplanation().startsWith("Ownership-neutral transfer: Neutral zone"));

        // Leaving: one closing window, then the gear comes back.
        assertEquals(NeutralZoneTracker.Signal.LEFT, tracker.onRegion(12127));
        engine.endNeutralZoneTransfer(20);
        engine.markInventoryDirty();
        java.util.Map<Integer, Long> lobbyItems = new java.util.HashMap<>(gear);
        lobbyItems.put(526, 1L); // reward chest item arriving beside the restored gear
        Transaction loot = settle(engine, new ContainerSnapshot(lobbyItems), 4_000L);
        assertEquals(TransactionType.GAIN, loot.getType());
        assertTrue("unmatched lobby chest loot remains counted", loot.isCounted());
        assertEquals(526, loot.getFlows().get(0).itemId);
        assertEquals(100_000L, engine.getMetrics(8_000L).net);

        Transaction restored = null;
        for (Transaction row : engine.getActiveSession().getTransactions())
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
        Engine engine = new Engine(deltas ->
        {
            java.util.List<Flow> flows = new java.util.ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new Flow(
                id, "Item " + id, qty, 100_000, qty * 100_000L)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        java.util.Map<Integer, Long> gear = new java.util.HashMap<>();
        gear.put(4151, 1L);
        gear.put(11802, 1L);
        gear.put(385, 2L); // in-zone supplies must not be captured as entry gear forever
        engine.setBaseline(new ContainerSnapshot(gear));

        engine.beginNeutralZoneTransfer();
        engine.markTransfer("Neutral zone instance", config.stabilizationTicks() + 4);
        engine.markInventoryDirty();
        java.util.Map<Integer, Long> firstBatchItems = new java.util.HashMap<>();
        firstBatchItems.put(11802, 1L);
        firstBatchItems.put(385, 2L);
        Transaction firstStore = settle(
            engine, new ContainerSnapshot(firstBatchItems), 1_600L);
        assertFalse(firstStore.isCounted());

        // Equipment and inventory callbacks can report separate stable removals.
        engine.markTransfer("Neutral zone instance", config.stabilizationTicks() + 4);
        engine.markInventoryDirty();
        java.util.Map<Integer, Long> secondBatchItems = new java.util.HashMap<>();
        secondBatchItems.put(385, 2L);
        Transaction secondStore = settle(
            engine, new ContainerSnapshot(secondBatchItems), 3_400L);
        assertFalse(secondStore.isCounted());

        // The bounded capture expires even though the tracker continues to mark
        // every in-zone sample as TRANSFER. A later supply loss cannot join the
        // set of entry-stored items.
        engine.processIfDirty(new ContainerSnapshot(secondBatchItems), 4_600L);
        engine.processIfDirty(new ContainerSnapshot(secondBatchItems), 5_200L);
        engine.markTransfer("Neutral zone instance", config.stabilizationTicks() + 4);
        engine.markInventoryDirty();
        java.util.Map<Integer, Long> afterSupplyUse = new java.util.HashMap<>();
        afterSupplyUse.put(385, 1L);
        Transaction supplyUse = settle(
            engine, new ContainerSnapshot(afterSupplyUse), 5_800L);
        assertFalse(supplyUse.isCounted());

        engine.endNeutralZoneTransfer(20);
        engine.markInventoryDirty();
        java.util.Map<Integer, Long> restoredPlusLoot = new java.util.HashMap<>();
        restoredPlusLoot.put(4151, 2L); // one stored whip plus one unrelated whip gain
        restoredPlusLoot.put(11802, 1L);
        restoredPlusLoot.put(526, 1L); // unrelated lobby reward
        restoredPlusLoot.put(385, 2L); // a later supply gain is outside the entry capture
        Transaction gain = settle(
            engine, new ContainerSnapshot(restoredPlusLoot), 7_600L);

        assertEquals(TransactionType.GAIN, gain.getType());
        assertTrue("same-id excess and unrelated reward remain counted", gain.isCounted());
        assertEquals(3, gain.getFlows().size());
        assertEquals(1L, gain.getFlows().stream().filter(flow -> flow.itemId == 4151)
            .findFirst().get().quantityDelta);
        assertEquals(1L, gain.getFlows().stream().filter(flow -> flow.itemId == 526)
            .findFirst().get().quantityDelta);
        assertEquals(1L, gain.getFlows().stream().filter(flow -> flow.itemId == 385)
            .findFirst().get().quantityDelta);

        Transaction restored = null;
        for (Transaction row : engine.getActiveSession().getTransactions())
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

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        engine.processIfDirty(snapshot, firstTick);
        engine.processIfDirty(snapshot, firstTick + 600L);
        return engine.processIfDirty(snapshot, firstTick + 1_200L);
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
