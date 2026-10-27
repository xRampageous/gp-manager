package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * D1 (2026-10-23 review): a death during a manual pause still sends gear to the grave. The paused
 * branch used to realign the baseline through the wipe without remembering the grave items, so the
 * later reclaim booked the whole gear return as counted income.
 */
public class ManualPauseDeathReclaimTest
{
    private static final int WHIP = 4151;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void reclaimAfterADeathDuringAManualPauseIsNotIncome()
    {
        Engine engine = new Engine(valuator(), new TransactionClassifier(), config());
        engine.ensureSession(1_000L);
        ContainerSnapshot gear = new ContainerSnapshot(inventory(WHIP, 1L));
        engine.setBaseline(gear);
        engine.togglePause(1_100L);
        assertTrue(engine.session().paused);
        // Paused ticks keep the baseline aligned to what is held.
        engine.processIfDirty(gear, 1_200L, null);
        engine.markPvmDeath(20, gear.items, Map.of(), engine.events.owner);
        engine.markDirty();
        for (int i = 0; i < 3; i++) engine.processIfDirty(ContainerSnapshot.empty(), 1_600L + i * 600L, null);
        engine.togglePause(4_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        assertTrue(engine.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        engine.markDirty();
        Transaction returned = null;
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(gear, 5_000L + i * 600L, null);
            if (settled != null) returned = settled;
        }
        assertNotNull(returned);
        assertFalse("reclaimed gear must not become revenue", returned.counts());
        assertEquals(0L, engine.metrics(9_000L).income);
    }

    /** Control: a death while unpaused keeps its existing neutral reclaim. */
    @Test
    public void reclaimAfterAnUnpausedDeathStaysNeutral()
    {
        Engine engine = new Engine(valuator(), new TransactionClassifier(), config());
        engine.ensureSession(1_000L);
        ContainerSnapshot gear = new ContainerSnapshot(inventory(WHIP, 1L));
        engine.setBaseline(gear);
        engine.markPvmDeath(20, gear.items, Map.of(), engine.events.owner);
        engine.markDirty();
        for (int i = 0; i < 3; i++) engine.processIfDirty(ContainerSnapshot.empty(), 1_600L + i * 600L, null);
        assertTrue(engine.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        engine.markDirty();
        for (int i = 0; i < 3; i++) engine.processIfDirty(gear, 5_000L + i * 600L, null);
        assertEquals(0L, engine.metrics(9_000L).income);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────

    private static Map<Integer, Long> inventory(Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((Integer) pairs[i], (Long) pairs[i + 1]);
        return map;
    }

    private static FlowValuator valuator()
    {
        return deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : deltas.entrySet())
                flows.add(new Flow(entry.getKey(), "Abyssal whip", entry.getValue(), 2_000_000,
                    entry.getValue() * 2_000_000L));
            return flows;
        };
    }

    private static GpManagerConfig config()
    {
        return new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 2; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
    }
}
