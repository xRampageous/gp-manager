package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-11-03 (Colosseum Profit Tracker 1.3.3, Hub PR 19987): Minimus takes a spare Dizana's
 * quiver (uncharged) for 4,000 sunfire splinters (OSRS Wiki, Transcript:Minimus). The quiver keeps
 * its alch value when it is won; the swap books one counted Exchanged trade for the difference.
 */
public class QuiverExchangeTest
{
    private static final int QUIVER = 28947, LOCKED = 28949, SPLINTERS = 28924;
    private static final Map<Integer, String> NAMES = Map.of(QUIVER, "Dizana's quiver (uncharged)",
        LOCKED, "Dizana's quiver (l) (uncharged)", SPLINTERS, "Sunfire splinters");
    private static final Map<Integer, Integer> PRICES = Map.of(QUIVER, 48_000, LOCKED, 48_000, SPLINTERS, 333);

    private final Map<Integer, Long> inventory = new HashMap<>(Map.of(QUIVER, 1L, LOCKED, 1L));
    private final Engine engine = new Engine(deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) -> flows.add(new Flow(id, NAMES.get(id), quantity, PRICES.get(id),
            quantity * PRICES.get(id), PriceSource.GRAND_EXCHANGE)));
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

    private void start()
    {
        engine.startCustom("Colosseum", now);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(inventory)));
    }

    private Transaction change(Object... idsAndDeltas)
    {
        for (int index = 0; index < idsAndDeltas.length; index += 2)
            inventory.merge((Integer) idsAndDeltas[index], (Long) idsAndDeltas[index + 1], Long::sum);
        inventory.values().removeIf(quantity -> quantity == 0L);
        int before = engine.session().transactions().size();
        now += 3_000L;
        engine.processIfDirty(new ContainerSnapshot(new HashMap<>(inventory)), now, null);
        engine.processIfDirty(new ContainerSnapshot(new HashMap<>(inventory)), now + 600L, null);
        List<Transaction> all = engine.session().transactions();
        return all.size() > before ? all.get(all.size() - 1) : null;
    }

    private void talking()
    {
        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
    }

    @Test
    public void minimusSwapBooksOneCountedExchange()
    {
        start();
        talking();
        Transaction swap = change(QUIVER, -1L, SPLINTERS, 4_000L);
        assertSame(TransactionType.TRADE, swap.type());
        assertEquals("Exchanged", swap.note());
        assertTrue(swap.counts());
        assertFalse("not Review", ReviewRules.needsReview(swap));
        assertEquals("the splinters less the quiver's booked value", 4_000L * 333 - 48_000,
            engine.metrics(now + 1_000L).net);
    }

    @Test
    public void otherShapesStayAsTheyWere()
    {
        start();
        assertTrue("without a conversation it stays Review",
            ReviewRules.needsReview(change(QUIVER, -1L, SPLINTERS, 4_000L)));
        talking();
        Transaction locked = change(LOCKED, -1L, SPLINTERS, 4_000L);
        assertNotEquals("only the listed quiver", "Exchanged", locked == null ? null : locked.note());
        talking();
        Transaction partial = change(SPLINTERS, -4_000L, QUIVER, 1L);
        assertNotEquals("the reverse is not the swap", "Exchanged", partial == null ? null : partial.note());
    }
}
