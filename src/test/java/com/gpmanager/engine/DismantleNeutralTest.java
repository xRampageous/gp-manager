package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Taking an item apart is neutral; a crystal tool's 120 shards are the only cost (owner 1.2). */
public class DismantleNeutralTest
{
    private static final Map<Integer, String> NAMES = Map.of(ItemID.CRYSTAL_HARPOON, "Crystal harpoon",
        ItemID.DRAGON_HARPOON, "Dragon harpoon", ItemID.PRIF_TOOL_SEED, "Crystal tool seed",
        ItemID.PRIF_CRYSTAL_SHARD, "Crystal shard", ItemID.AGS, "Armadyl godsword",
        ItemID.GODWARS_GODSWORD_BLADE1_2_3, "Godsword blade", ItemID.GODWARS_GODSWORD_HILT_ARMADYL, "Armadyl hilt");
    private static final Map<Integer, Integer> PRICES = Map.of(ItemID.CRYSTAL_HARPOON, 0,
        ItemID.DRAGON_HARPOON, 3_000_000, ItemID.PRIF_TOOL_SEED, 1_000_000, ItemID.PRIF_CRYSTAL_SHARD, 2_000,
        ItemID.AGS, 12_000_000, ItemID.GODWARS_GODSWORD_BLADE1_2_3, 1_000_000, ItemID.GODWARS_GODSWORD_HILT_ARMADYL, 10_500_000);

    private static Engine settle(int clicked, Map<Integer, Long> before, Map<Integer, Long> after)
    {
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, NAMES.get(id), quantity, PRICES.get(id),
                quantity * PRICES.get(id))));
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig()
        {
        });
        engine.ensureSession(1_000_000L);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(before)));
        engine.markDirty();
        if (clicked > 0)
        {
            engine.dismantle(clicked);
        }
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(after)), 1_000_000L + tick * 600L);
        }
        return engine;
    }

    private static final Map<Integer, Long> HARPOON = Map.of(ItemID.CRYSTAL_HARPOON, 1L);
    private static final Map<Integer, Long> PARTS = Map.of(ItemID.DRAGON_HARPOON, 1L, ItemID.PRIF_TOOL_SEED, 1L);

    @Test
    public void revertingACrystalToolCostsOnlyItsShards()
    {
        Engine engine = settle(ItemID.CRYSTAL_HARPOON, HARPOON, PARTS);
        List<Transaction> rows = engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        Transaction shards = rows.get(0);
        assertEquals(TransactionType.CONSUMPTION, shards.autoType());
        assertEquals("Dismantled Crystal harpoon", shards.getNote());
        assertEquals(ItemID.PRIF_CRYSTAL_SHARD, shards.getFlows().get(0).itemId);
        assertEquals(-120L, shards.getFlows().get(0).quantityDelta);
        assertEquals(-240_000L, engine.getMetrics(1_010_000L).net);
    }

    @Test
    public void withoutTheClickTheSameChangeStillNeedsReview()
    {
        Engine engine = settle(-1, HARPOON, PARTS);
        assertTrue(engine.getActiveSession().getTransactions().stream().anyMatch(row -> row.autoType() == TransactionType.UNCERTAIN));
    }

    @Test
    public void dismantlingAGodswordBooksNothing()
    {
        Engine engine = settle(ItemID.AGS, Map.of(ItemID.AGS, 1L), Map.of(ItemID.GODWARS_GODSWORD_BLADE1_2_3, 1L, ItemID.GODWARS_GODSWORD_HILT_ARMADYL, 1L));
        assertTrue(engine.getActiveSession().getTransactions().toString(),
            engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(0L, engine.getMetrics(1_010_000L).net);
    }
}
