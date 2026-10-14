package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Imbuing keeps an item's worth: X to X(i)/(ei)/(ri) swaps are neutral, the imbued leg prices
 * from its plain item where a quote exists, and lookalike pairs stay (Muse review, owner
 * 2026-10-12).
 */
public class ImbueSwapTest
{
    private static final int DAMNED = 12851;
    private static final int DAMNED_DUST = 12853;

    @Test
    public void everyImbuedFamilySwapIsNeutral()
    {
        assertSwapped(ItemID.NZONE_SALVE_AMULET, 4081);
        assertSwapped(ItemID.NZONE_SALVE_AMULET_E, 4081);
        assertSwapped(ItemID.NZONE_SALVE_AMULET, ItemID.NZONE_SALVE_AMULET_E);
        assertSwapped(ItemID.SW_SALVE_AMULET, ItemID.PVPA_SALVE_AMULET);
        assertSwapped(ItemID.NZONE_SEER_RING, ItemID.SEER_RING);
        assertSwapped(ItemID.SW_BERZERKER_RING, ItemID.BERZERKER_RING);
        assertSwapped(ItemID.PVPA_WARRIOR_RING, ItemID.WARRIOR_RING);
        assertSwapped(ItemID.NZONE_ROTG, ItemID.ROTG);
        assertSwapped(ItemID.SW_HEAVY_RING, ItemID.HEAVY_RING);
        assertSwapped(ItemID.PVPA_SHARP_RING, ItemID.SHARP_RING);
        assertSwapped(ItemID.SW_GRANITE_RING, ItemID.GRANITE_RING);
        assertSwapped(ItemID.RING_OF_WEALTH_I, ItemID.RING_OF_WEALTH);
        assertSwapped(ItemID.RING_OF_WEALTH_I5, ItemID.RING_OF_WEALTH_5);
        assertSwapped(ItemID.MAGIC_SHORTBOW_I, ItemID.MAGIC_SHORTBOW);
    }

    @Test
    public void theSufferingImbuedSwapStaysNeutralThroughItsChargeRow()
    {
        assertSwapped(ItemID.ZENYTE_RING_ENCHANTED, ItemID.NZONE_ZENYTE_RING_ENCHANTED);
        assertSwapped(ItemID.NZONE_ZENYTE_RING_ENCHANTED_RECOIL, ItemID.PVPA_ZENYTE_RING_ENCHANTED_RECOIL);
    }

    @Test
    public void lookalikePairsStay()
    {
        assertKept(ItemID.SEER_RING, ItemID.RANGER_RING);
        assertKept(ItemID.NZONE_SEER_RING, ItemID.NZONE_RANGER_RING);
    }

    @Test
    public void theImbuedLegPricesFromItsPlainItem()
    {
        Valuation valuation = new Valuation(new GpManagerConfig() { }, id -> null,
            id -> id == ItemID.BERZERKER_RING ? 2_800_000
                : id == ItemID.RING_OF_WEALTH_5 ? 400_000
                : id == ItemID.MAGIC_SHORTBOW ? 1_800 : 0);
        assertEquals(2_800_000, valuation.valueComponent(ItemID.NZONE_BERZERKER_RING, 1L, 0L).unitPrice);
        assertSame(PriceSource.CURRENCY_PROXY,
            valuation.valueComponent(ItemID.NZONE_BERZERKER_RING, 1L, 0L).getPriceSource());
        assertEquals(400_000, valuation.valueComponent(ItemID.RING_OF_WEALTH_I5, 1L, 0L).unitPrice);
        assertEquals(1_800, valuation.valueComponent(ItemID.MAGIC_SHORTBOW_I, 1L, 0L).unitPrice);
        assertSame("a quoteless base leaves the imbued leg unpriced",
            PriceSource.UNPRICED, valuation.valueComponent(ItemID.NZONE_SALVE_AMULET, 1L, 0L).getPriceSource());
    }

    @Test
    public void aPointsImbueDropsThePairButKeepsARealCost()
    {
        // The NMZ/Soul Wars imbues consume no items; a rune lost in the same settle still books.
        Engine engine = settle(mapOf(ItemID.RING_OF_WEALTH, 1L, ItemID.NATURERUNE, 1L),
            mapOf(ItemID.RING_OF_WEALTH_I, 1L));
        for (Transaction row : engine.getActiveSession().getTransactions())
        {
            for (Flow flow : row.getFlows())
            {
                assertTrue("the imbue pair never books: " + flow.itemId,
                    flow.itemId != ItemID.RING_OF_WEALTH && flow.itemId != ItemID.RING_OF_WEALTH_I);
            }
        }
        assertEquals(-300L, engine.getMetrics(1_010_000L).net);
    }

    @Test
    public void theDamnedDegradeBooksNothing()
    {
        Engine engine = settle(mapOf(DAMNED, 1L), mapOf(DAMNED_DUST, 1L));
        assertTrue(engine.getActiveSession().getTransactions().toString(),
            engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(0L, engine.getMetrics(1_010_000L).net);
    }

    @Test
    public void theSailorsAmuletStateSwapIsNeutral()
    {
        // Charging the sailors' amulet turns its inert state into the charged one; the pair is the
        // same worth. The armed use-on load books nothing (owner 2026-10-14: its charges measure
        // through the amulet's Check); this no-intent settle is the fail-safe where the runes
        // book rather than vanish.
        assertSwapped(ItemID.SAILORS_AMULET_EMPTY, ItemID.SAILORS_AMULET);

        Engine engine = settle(
            mapOf(ItemID.SAILORS_AMULET_EMPTY, 1L, ItemID.LAWRUNE, 1L, ItemID.WATERRUNE, 10L),
            mapOf(ItemID.SAILORS_AMULET, 1L));
        for (Transaction row : engine.getActiveSession().getTransactions())
        {
            for (Flow flow : row.getFlows())
            {
                assertTrue("the amulet's states never book: " + flow.itemId,
                    flow.itemId != ItemID.SAILORS_AMULET
                        && flow.itemId != ItemID.SAILORS_AMULET_EMPTY);
            }
        }
        assertEquals(-(300L + 10L * 5L), engine.getMetrics(1_010_000L).net);
    }

    private static void assertSwapped(int first, int second)
    {
        List<Flow> flows = new ArrayList<>();
        flows.add(flow(first, -1L));
        flows.add(flow(second, 1L));
        assertTrue(first + " -> " + second, FlowFilters.withoutSwaps(flows).isEmpty());
    }

    private static void assertKept(int first, int second)
    {
        List<Flow> flows = new ArrayList<>();
        flows.add(flow(first, -1L));
        flows.add(flow(second, 1L));
        assertEquals(first + " -> " + second, 2, FlowFilters.withoutSwaps(flows).size());
    }

    private static Flow flow(int itemId, long quantity)
    {
        return new Flow(itemId, "Item " + itemId, quantity, 100, quantity * 100);
    }

    private static Engine settle(Map<Integer, Long> before, Map<Integer, Long> after)
    {
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) ->
            {
                int price = id == ItemID.NATURERUNE ? 300
                    : id == ItemID.LAWRUNE ? 300
                    : id == ItemID.WATERRUNE ? 5 : 400_000;
                flows.add(new Flow(id, "Item " + id, quantity, price, quantity * price));
            });
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig()
        {
        });
        engine.ensureSession(1_000_000L);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(before)));
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(after)), 1_000_000L + tick * 600L);
        }
        return engine;
    }

    private static Map<Integer, Long> mapOf(Object... values)
    {
        Map<Integer, Long> result = new HashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2)
        {
            result.put((Integer) values[index], (Long) values[index + 1]);
        }
        return result;
    }
}
