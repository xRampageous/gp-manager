package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Taking an item apart is neutral; a crystal tool's 120 shards are the only cost (owner 1.2). */
public class DismantleNeutralTest
{
    private static final Map<Integer, String> NAMES = Map.of(ItemID.CRYSTAL_HARPOON, "Crystal harpoon",
        ItemID.DRAGON_HARPOON, "Dragon harpoon", ItemID.PRIF_TOOL_SEED, "Crystal tool seed",
        ItemID.PRIF_CRYSTAL_SHARD, "Crystal shard", ItemID.AGS, "Armadyl godsword",
        ItemID.GODWARS_GODSWORD_BLADE1_2_3, "Godsword blade", ItemID.GODWARS_GODSWORD_HILT_ARMADYL, "Armadyl hilt",
        ItemID.WILD_CAVE_BRACELET_UNCHARGED, "Bracelet of ethereum (uncharged)",
        ItemID.WILD_CAVE_SHARD, "Revenant ether");
    private static final Map<Integer, Integer> PRICES = Map.of(ItemID.CRYSTAL_HARPOON, 0,
        ItemID.DRAGON_HARPOON, 3_000_000, ItemID.PRIF_TOOL_SEED, 1_000_000, ItemID.PRIF_CRYSTAL_SHARD, 2_000,
        ItemID.AGS, 12_000_000, ItemID.GODWARS_GODSWORD_BLADE1_2_3, 1_000_000, ItemID.GODWARS_GODSWORD_HILT_ARMADYL, 10_500_000,
        ItemID.WILD_CAVE_BRACELET_UNCHARGED, 40_000, ItemID.WILD_CAVE_SHARD, 250);

    private static Engine settle(int clicked, Map<Integer, Long> before, Map<Integer, Long> after)
    {
        return settle(clicked, before, after, new GpManagerConfig()
        {
        });
    }

    private static Engine settle(int clicked, Map<Integer, Long> before, Map<Integer, Long> after, GpManagerConfig config)
    {
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, NAMES.get(id), quantity, PRICES.get(id),
                quantity * PRICES.get(id))));
            return flows;
        }, new TransactionClassifier(), config);
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

    @Test
    public void dismantlingABraceletOfEthereumIsNotNeutral()
    {
        // The parts (250 ether) are worth more than the item: the neutral parts rule would bury
        // the gain, so it settles like the same change without the click (Muse review 2026-10-12).
        Engine engine = settle(ItemID.WILD_CAVE_BRACELET_UNCHARGED,
            Map.of(ItemID.WILD_CAVE_BRACELET_UNCHARGED, 1L), Map.of(ItemID.WILD_CAVE_SHARD, 250L));
        List<Transaction> rows = engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(TransactionType.UNCERTAIN, rows.get(0).autoType());
        assertFalse(rows.get(0).isCounted());
        assertTrue(ReviewRules.needsOwnerDecision(rows.get(0)));
        assertEquals(0L, engine.getMetrics(1_010_000L).net);
    }

    @Test
    public void aCountedUncertainSettingBooksTheBraceletConversion()
    {
        Engine engine = settle(ItemID.WILD_CAVE_BRACELET_UNCHARGED,
            Map.of(ItemID.WILD_CAVE_BRACELET_UNCHARGED, 1L), Map.of(ItemID.WILD_CAVE_SHARD, 250L),
            new GpManagerConfig()
            {
                @Override public boolean countUncertainMixedChanges()
                {
                    return true;
                }
            });
        assertEquals(250L * 250L - 40_000L, engine.getMetrics(1_010_000L).net);
    }
}
