package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
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
 * Owner 1.3.4 live: the last amulet-of-glory charge leaves the plain uncharged amulet, and that
 * step must book like every other charge (3&gt;2, 2&gt;1) instead of landing in Review.
 */
public class GloryLastChargeTest
{
    private static final int GLORY = 1704;
    private static final int GLORY_1 = 1706;
    private static final int GLORY_2 = 1708;

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 2;
        }
    };

    @Test
    public void theLastChargeStepMatchesThePlainAmulet()
    {
        assertTrue("glory(1) to the plain amulet is one dose step",
            ActionReader.isDoseStep(Arrays.asList(
                flow(GLORY_1, "Amulet of glory(1)", -1L),
                flow(GLORY, "Amulet of glory", 1L))));
        assertFalse("two remaining doses never leave the plain base",
            ActionReader.isDoseStep(Arrays.asList(
                flow(GLORY_2, "Amulet of glory(2)", -1L),
                flow(GLORY, "Amulet of glory", 1L))));
    }

    @Test
    public void theLastChargeBooksOneSuppliesStep()
    {
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) ->
                flows.add(flow(id, id == GLORY_1 ? "Amulet of glory(1)" : "Amulet of glory",
                    quantity)));
            return flows;
        }, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(held(GLORY_1, 1L));

        Transaction step = settle(engine, held(GLORY, 1L), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, step.getType());
        assertEquals(ActionKind.SUPPLIES, step.getActionKind());
        assertEquals(0, engine.getReviewRows(10_000L).size());
        assertEquals("both legs priced at the same quote", 0L, engine.getMetrics(10_000L).net);
    }

    private static Flow flow(int itemId, String name, long quantity)
    {
        return new Flow(itemId, name, quantity, 11_931, quantity * 11_931);
    }

    private static ContainerSnapshot held(int itemId, long quantity)
    {
        return new ContainerSnapshot(new HashMap<>(Map.of(itemId, quantity)));
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        assertNull(engine.processIfDirty(snapshot, firstTick));
        assertNull(engine.processIfDirty(snapshot, firstTick + 600L));
        Transaction tx = engine.processIfDirty(snapshot, firstTick + 1_200L);
        assertNotNull(tx);
        return tx;
    }
}
