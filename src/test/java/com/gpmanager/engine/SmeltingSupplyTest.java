package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/** Owner report 2026-09-28: ore used up by a failed iron smelt is a supply, not a loss. */
public class SmeltingSupplyTest
{
    private static final int IRON_ORE = 440;
    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void aFailedSmeltInsideTheRunIsASupplyAndNeverReview()
    {
        Engine engine = engine();
        engine.startCustomSession("Smithing", SessionMode.GENERAL, T0);
        Map<Integer, Long> held = new HashMap<>();
        held.put(IRON_ORE, 7L);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(held)));

        // The furnace's Smelt click (or a failed-smelt message) keeps the run in PRODUCTION.
        engine.markContext(Context.PRODUCTION, 6, "Smelting");
        held.put(IRON_ORE, 6L);
        Transaction failed = settle(engine, held, T0 + 600L);

        assertNotNull(failed);
        assertFalse("a failed smelt is not a Review decision", ReviewEligibility.needsOwnerDecision(failed));
        assertEquals("the ore it used is a supply", CostKind.SUPPLIES, CostKind.of(failed, failed.getFlows().get(0)));
    }

    @Test
    public void anOreGoneWithoutAnyRunStaysALoss()
    {
        Engine engine = engine();
        engine.startCustomSession("Smithing", SessionMode.GENERAL, T0);
        Map<Integer, Long> held = new HashMap<>();
        held.put(IRON_ORE, 7L);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(held)));

        held.put(IRON_ORE, 6L);
        Transaction gone = settle(engine, held, T0 + 600L);
        assertNotNull(gone);
        assertEquals(CostKind.LOSS, CostKind.of(gone, gone.getFlows().get(0)));
    }

    private static Transaction settle(Engine engine, Map<Integer, Long> held, long now)
    {
        engine.markInventoryDirty();
        Transaction result = null;
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(new ContainerSnapshot(new HashMap<>(held)), now + i * 600L);
            if (settled != null)
            {
                result = settled;
            }
        }
        return result;
    }

    private static Engine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 1;
            }
        };
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                flows.add(new Flow(delta.getKey(), "Iron ore", delta.getValue(), 71, delta.getValue() * 71L,
                    PriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
