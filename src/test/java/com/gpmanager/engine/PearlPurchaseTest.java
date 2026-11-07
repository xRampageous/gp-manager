package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-11-03: abyssal pearls are worth their alch value on their own. At Temple Supplies (a
 * shop window, OSRS Wiki) 400 pearls buy a ring of the elements, which books as a counted trade
 * at the ring's value less the pearls' worth.
 */
public class PearlPurchaseTest
{
    private static final int PEARLS = ItemID.ABYSSAL_PEARL, PEARL_ALCH = 30, RING = 26815, RING_PRICE = 2_400_000;

    private final Map<Integer, Long> inventory = new HashMap<>(Map.of(PEARLS, 500L));
    private final Engine engine = new Engine(deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) -> flows.add(id == PEARLS
            ? new Flow(id, "Abyssal pearls", quantity, PEARL_ALCH, quantity * PEARL_ALCH, PriceSource.HIGH_ALCHEMY)
            : new Flow(id, "Ring of the elements", quantity, RING_PRICE, quantity * RING_PRICE, PriceSource.GRAND_EXCHANGE)));
        return flows;
    }, new TransactionClassifier(), new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 0;
        }
    });
    private long now = 1_000_000L;

    @Test
    public void pearlsSpentAtTheShopBookTheRingTheyBought()
    {
        engine.startCustom("Guardians of the Rift", now);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(inventory)));
        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        inventory.put(PEARLS, 100L);
        inventory.put(RING, 1L);
        now += 3_000L;
        engine.processIfDirty(new ContainerSnapshot(new HashMap<>(inventory)), now, null);
        engine.processIfDirty(new ContainerSnapshot(new HashMap<>(inventory)), now + 600L, null);
        List<Transaction> rows = engine.session().transactions();
        assertEquals(1, rows.size());
        Transaction bought = rows.get(0);
        assertSame(TransactionType.TRADE, bought.type());
        assertTrue(bought.counts());
        assertFalse("not Review", ReviewRules.needsReview(bought));
        assertEquals("the pearls cost their alch value; the ring is worth its price",
            RING_PRICE - 400L * PEARL_ALCH, engine.metrics(now + 1_000L).net);
    }
}
