package com.gpmanager;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Owner 1.3.4: one burning-amulet teleport costs one dose on the ledger — the live shape booked
 * the whole amulet once and an unknown row after, because RuneLite has no quote for the partial
 * variants.
 */
public class BurningAmuletTeleportTest
{
    private static final int BURNING_5 = 21166;
    private static final int BURNING_4 = 21169;
    private static final int BURNING_3 = 21171;

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 2;
        }
    };

    @Test
    public void aTeleportBooksOneDoseNotTheWholeAmulet()
    {
        Engine engine = new Engine(
            DoseFamilyValueTest.service(new GpManagerConfig() { }, Map.of(BURNING_5, 2_231)),
            new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(held(BURNING_5, 1L));

        Transaction first = settle(engine, held(BURNING_4, 1L), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, first.getType());
        assertEquals(ActionKind.SUPPLIES, first.getActionKind());
        assertEquals(-447L, engine.getMetrics(10_000L).net);
        assertEquals("no unknown row", 0, engine.getReviewRows(10_000L).size());

        engine.markDirty();
        settle(engine, held(BURNING_3, 1L), 4_000L);
        assertEquals("two teleports are two doses", -893L, engine.getMetrics(10_000L).net);
        assertEquals(0, engine.getReviewRows(10_000L).size());
    }

    private static ContainerSnapshot held(int itemId, long quantity)
    {
        return new ContainerSnapshot(new HashMap<>(Map.of(itemId, quantity)));
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        assertNull(engine.processIfDirty(snapshot, firstTick, null));
        assertNull(engine.processIfDirty(snapshot, firstTick + 600L, null));
        Transaction tx = engine.processIfDirty(snapshot, firstTick + 1_200L, null);
        assertNotNull(tx);
        return tx;
    }
}
