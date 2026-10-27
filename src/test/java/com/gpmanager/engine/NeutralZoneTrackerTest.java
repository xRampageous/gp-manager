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
        assertEquals(NeutralZoneTracker.Signal.NONE, tracker.onRegion(12127, false));
        assertEquals(NeutralZoneTracker.Signal.ENTERED, tracker.onRegion(7512, false));
        assertEquals(NeutralZoneTracker.Signal.INSIDE, tracker.onRegion(7512, false));
        assertTrue(tracker.isInside());
        assertEquals(NeutralZoneTracker.Signal.LEFT, tracker.onRegion(12127, false));
        assertFalse(tracker.isInside());
        assertEquals(NeutralZoneTracker.Signal.NONE, tracker.onRegion(12127, false));
    }

    @Test
    public void unknownRegionDuringLoadingKeepsStateWithoutSignalling()
    {
        NeutralZoneTracker tracker = new NeutralZoneTracker();
        tracker.onRegion(7768, false);
        assertEquals(NeutralZoneTracker.Signal.NONE, tracker.onRegion(0, false));
        assertTrue(tracker.isInside());
        assertEquals(NeutralZoneTracker.Signal.LEFT, tracker.onRegion(12127, false));
        tracker.onRegion(7768, false);
        tracker.reset();
        assertFalse(tracker.isInside());
        assertEquals(NeutralZoneTracker.Signal.NONE, tracker.onRegion(12127, false));
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
        assertEquals(NeutralZoneTracker.Signal.ENTERED, tracker.onRegion(7512, false));
        engine.beginNeutralZone();
        engine.markTransfer("Neutral zone instance", config.stabilizationTicks() + 4);
        engine.markDirty();
        Transaction stored = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.TRANSFER, stored.type());
        assertFalse(stored.counts());
        assertEquals("Neutral zone instance", stored.note());
        assertTrue(stored.explanation().startsWith("Ownership-neutral transfer: Neutral zone"));

        // Leaving: one closing window, then the gear comes back.
        assertEquals(NeutralZoneTracker.Signal.LEFT, tracker.onRegion(12127, false));
        engine.endNeutralZone(20);
        engine.markDirty();
        java.util.Map<Integer, Long> lobbyItems = new java.util.HashMap<>(gear);
        lobbyItems.put(526, 1L); // reward chest item arriving beside the restored gear
        Transaction loot = settle(engine, new ContainerSnapshot(lobbyItems), 4_000L);
        assertEquals(TransactionType.GAIN, loot.type());
        assertTrue("unmatched lobby chest loot remains counted", loot.counts());
        assertEquals(526, loot.flows().get(0).item);
        assertEquals(100_000L, engine.metrics(8_000L).net);

        Transaction restored = null;
        for (Transaction row : engine.session().transactions())
        {
            if ("Neutral zone exit restore".equals(row.note()))
            {
                restored = row;
            }
        }
        assertTrue("restored gear remains an uncounted audit row", restored != null && !restored.counts());
        assertEquals(2, restored.flows().size());
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

        engine.beginNeutralZone();
        engine.markTransfer("Neutral zone instance", config.stabilizationTicks() + 4);
        engine.markDirty();
        java.util.Map<Integer, Long> firstBatchItems = new java.util.HashMap<>();
        firstBatchItems.put(11802, 1L);
        firstBatchItems.put(385, 2L);
        Transaction firstStore = settle(
            engine, new ContainerSnapshot(firstBatchItems), 1_600L);
        assertFalse(firstStore.counts());

        // Equipment and inventory callbacks can report separate stable removals.
        engine.markTransfer("Neutral zone instance", config.stabilizationTicks() + 4);
        engine.markDirty();
        java.util.Map<Integer, Long> secondBatchItems = new java.util.HashMap<>();
        secondBatchItems.put(385, 2L);
        Transaction secondStore = settle(
            engine, new ContainerSnapshot(secondBatchItems), 3_400L);
        assertFalse(secondStore.counts());

        // The bounded capture expires even though the tracker continues to mark
        // every in-zone sample as TRANSFER. A later supply loss cannot join the
        // set of entry-stored items.
        engine.processIfDirty(new ContainerSnapshot(secondBatchItems), 4_600L, null);
        engine.processIfDirty(new ContainerSnapshot(secondBatchItems), 5_200L, null);
        engine.markTransfer("Neutral zone instance", config.stabilizationTicks() + 4);
        engine.markDirty();
        java.util.Map<Integer, Long> afterSupplyUse = new java.util.HashMap<>();
        afterSupplyUse.put(385, 1L);
        Transaction supplyUse = settle(
            engine, new ContainerSnapshot(afterSupplyUse), 5_800L);
        assertFalse(supplyUse.counts());

        engine.endNeutralZone(20);
        engine.markDirty();
        java.util.Map<Integer, Long> restoredPlusLoot = new java.util.HashMap<>();
        restoredPlusLoot.put(4151, 2L); // one stored whip plus one unrelated whip gain
        restoredPlusLoot.put(11802, 1L);
        restoredPlusLoot.put(526, 1L); // unrelated lobby reward
        restoredPlusLoot.put(385, 2L); // a later supply gain is outside the entry capture
        Transaction gain = settle(
            engine, new ContainerSnapshot(restoredPlusLoot), 7_600L);

        assertEquals(TransactionType.GAIN, gain.type());
        assertTrue("same-id excess and unrelated reward remain counted", gain.counts());
        assertEquals(3, gain.flows().size());
        assertEquals(1L, gain.flows().stream().filter(flow -> flow.item == 4151)
            .findFirst().get().quantity);
        assertEquals(1L, gain.flows().stream().filter(flow -> flow.item == 526)
            .findFirst().get().quantity);
        assertEquals(1L, gain.flows().stream().filter(flow -> flow.item == 385)
            .findFirst().get().quantity);

        Transaction restored = null;
        for (Transaction row : engine.session().transactions())
        {
            if ("Neutral zone exit restore".equals(row.note()))
            {
                restored = row;
            }
        }
        assertTrue("both separately removed items remain an uncounted restore row",
            restored != null && !restored.counts());
        assertEquals(2, restored.flows().size());
        assertEquals(300_000L, engine.metrics(11_000L).net);
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        engine.processIfDirty(snapshot, firstTick, null);
        engine.processIfDirty(snapshot, firstTick + 600L, null);
        return engine.processIfDirty(snapshot, firstTick + 1_200L, null);
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
