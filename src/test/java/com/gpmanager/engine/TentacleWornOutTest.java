package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Owner 1.3.3: an abyssal tentacle costs nothing per hit; it is a loss only when it is used up or
 * dissolved, which leaves the kraken tentacle and loses the whip (OSRS Wiki).
 */
public class TentacleWornOutTest
{
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 2;
        }
    };

    /** RuneLite prices the tentacle as the whip (1.3M) plus the kraken tentacle (300k). */
    private final FlowValuator valuator = deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) -> {
            int unit = id == ItemID.ABYSSAL_TENTACLE ? 1_600_000 : 300_000;
            flows.add(new Flow(id, id == ItemID.ABYSSAL_TENTACLE ? "Abyssal tentacle" : "Kraken tentacle",
                quantity, unit, quantity * unit));
        });
        return flows;
    };

    @Test
    public void aUsedUpTentacleBooksTheWhipAsALossWithoutReview()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(ItemID.ABYSSAL_TENTACLE));
        Transaction row = settle(engine, snapshot(ItemID.KRAKEN_TENTACLE), 1_600L);
        assertNotNull(row);
        assertTrue(row.isCounted());
        assertEquals(TransactionType.CONSUMPTION, row.getType());
        assertEquals("the whip is the loss", -1_300_000L, engine.getMetrics(10_000L).net);
        Flow tentacle = row.getFlows().stream().filter(Flow::isCost).findFirst().get();
        assertEquals(CostKind.LOSS, CostKind.of(row, tentacle));
        assertEquals(0, engine.getReviewRows(10_000L).size());
    }

    @Test
    public void aTentacleLeavingWithoutItsKrakenTentacleIsNotThisRow()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(ItemID.ABYSSAL_TENTACLE));
        Transaction row = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertTrue("only a used-up or dissolved tentacle books this way",
            row == null || !GameData.msg("tentacle-used").equals(row.getNote()));
    }

    private static ContainerSnapshot snapshot(int itemId)
    {
        return new ContainerSnapshot(Collections.singletonMap(itemId, 1L));
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        Transaction row = null;
        for (long t = firstTick; row == null && t < firstTick + 6_000L; t += 600L) row = engine.processIfDirty(snapshot, t);
        return row;
    }
}
