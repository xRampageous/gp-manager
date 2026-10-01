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
        Am engine = engine();
        engine.ajl("Smithing", Cx.GENERAL, T0);
        Map<Integer, Long> held = new HashMap<>();
        held.put(IRON_ORE, 7L);
        engine.setBaseline(new Cc(new HashMap<>(held)));

        // The furnace's Smelt click (or a failed-smelt message) keeps the run in PRODUCTION.
        engine.markContext(Aj.PRODUCTION, 6, "Smelting");
        held.put(IRON_ORE, 6L);
        Ac failed = settle(engine, held, T0 + 600L);

        assertNotNull(failed);
        assertFalse("a failed smelt is not a Review decision", Eh.aal(failed));
        assertEquals("the ore it used is a supply", CostKind.SUPPLIES, CostKind.of(failed, failed.getFlows().get(0)));
    }

    @Test
    public void anOreGoneWithoutAnyRunStaysALoss()
    {
        Am engine = engine();
        engine.ajl("Smithing", Cx.GENERAL, T0);
        Map<Integer, Long> held = new HashMap<>();
        held.put(IRON_ORE, 7L);
        engine.setBaseline(new Cc(new HashMap<>(held)));

        held.put(IRON_ORE, 6L);
        Ac gone = settle(engine, held, T0 + 600L);
        assertNotNull(gone);
        assertEquals(CostKind.LOSS, CostKind.of(gone, gone.getFlows().get(0)));
    }

    private static Ac settle(Am engine, Map<Integer, Long> held, long now)
    {
        engine.yz();
        Ac result = null;
        for (int i = 0; i < 3; i++)
        {
            Ac settled = engine.adj(new Cc(new HashMap<>(held)), now + i * 600L);
            if (settled != null)
            {
                result = settled;
            }
        }
        return result;
    }

    private static Am engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 1;
            }
        };
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                flows.add(new Ab(delta.getKey(), "Iron ore", delta.getValue(), 71, delta.getValue() * 71L,
                    Av.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
