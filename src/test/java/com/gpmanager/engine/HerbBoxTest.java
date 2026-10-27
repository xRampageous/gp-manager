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
 * Owner 1.3.3 (in-game test): a herb box is worth nothing until opened, like a reward casket. Each
 * Take-one counts its herb; Bank-all only says how many went to the bank, so it counts nothing.
 */
public class HerbBoxTest
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
        deltas.forEach((id, quantity) -> flows.add(id == ItemID.NZONE_HERBBOX
            ? new Flow(id, "Herb box", quantity, 0, 0L, PriceSource.DEFERRED_CLAIM, 0L)
            : new Flow(id, "Grimy marrentill", quantity, 228, quantity * 228)));
        return flows;
    };

    @Test
    public void theBoxIsAClaimUntilOpened()
    {
        assertTrue(GameData.pattern("clue").matcher("herb box").matches());
        assertTrue(ActionReader.isClaim("Herb box"));
    }

    @Test
    public void everyTakeOneCountsItsHerbWithoutReview()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(1L, 0L));
        assertEquals(TransactionType.GAIN, settle(engine, snapshot(1L, 1L), 1_600L).type());
        assertEquals("the last herb empties the box", TransactionType.GAIN, settle(engine, snapshot(0L, 2L), 6_000L).type());
        assertEquals(456L, engine.metrics(20_000L).net);
        assertEquals(0, engine.getReviewRows(20_000L).size());
    }

    private static ContainerSnapshot snapshot(long boxes, long herbs)
    {
        Map<Integer, Long> held = new HashMap<>();
        if (boxes > 0) held.put(ItemID.NZONE_HERBBOX, boxes);
        if (herbs > 0) held.put(ItemID.UNIDENTIFIED_MARENTILL, herbs);
        return new ContainerSnapshot(held);
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        Transaction row = null;
        for (long t = firstTick; row == null && t < firstTick + 4_000L; t += 600L) row = engine.processIfDirty(snapshot, t, null);
        return row;
    }
}
