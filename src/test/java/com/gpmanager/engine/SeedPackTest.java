package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-10-13 gap pass: a farming-contract seed pack and the Bounty Hunter supply crate are
 * claims like the herb box — worth nothing until taken from, and each Take counts its contents
 * without Review.
 */
public class SeedPackTest
{
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 2;
        }
    };

    private final FlowValuator valuator = deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) -> flows.add(id == ItemID.SEEDBOX
            ? new Flow(id, "Seed pack", quantity, 0, 0L, PriceSource.DEFERRED_CLAIM, 0L)
            : new Flow(id, "Ranarr seed", quantity, 25_000, quantity * 25_000)));
        return flows;
    };

    @Test
    public void thePackIsAClaimUntilTakenFrom()
    {
        assertTrue(ActionReader.isClaim("Seed pack"));
        assertTrue("the Bounty supply crate is the same shape",
            ActionReader.isClaim("Bounty supply crate (anglerfish)"));
    }

    @Test
    public void everyTakeCountsItsSeedsWithoutReview()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(1L, 0L));
        assertEquals(TransactionType.GAIN, settle(engine, snapshot(1L, 1L), 1_600L).getType());
        assertEquals("the last seed empties the pack", TransactionType.GAIN,
            settle(engine, snapshot(0L, 2L), 6_000L).getType());
        assertEquals(50_000L, engine.getMetrics(20_000L).net);
        assertEquals(0, engine.getReviewRows(20_000L).size());
    }

    private static ContainerSnapshot snapshot(long packs, long seeds)
    {
        Map<Integer, Long> held = new HashMap<>();
        if (packs > 0) held.put(ItemID.SEEDBOX, packs);
        if (seeds > 0) held.put(ItemID.RANARR_SEED, seeds);
        return new ContainerSnapshot(held);
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        Transaction row = null;
        for (long t = firstTick; row == null && t < firstTick + 4_000L; t += 600L)
            row = engine.processIfDirty(snapshot, t);
        return row;
    }
}
