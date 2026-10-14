package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A pending loot key's chest is valued exactly from its observed contents only (owner 2026-10-14):
 * the game's own display rounds to the nearest million, while the Ledger and the Live tooltip show
 * the exact manifest sum; a part without a quote shows nothing.
 */
public class PendingKeyValueTest
{
    private static final long T0 = 1_000_000L;
    private static final int KEY = ItemID.WILDY_LOOT_KEY0;
    private static final int KEY1 = ItemID.WILDY_LOOT_KEY1;
    private static final int FANG = 26219;
    private static final int RING = 6737;
    private static final long FANG_PRICE = 400_000L;
    private static final long RING_PRICE = 15_000L;

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 0;
        }

        @Override
        public boolean keepTransferAuditRows()
        {
            return true;
        }
    };

    @Test
    public void theObservedChestPricesExactly()
    {
        Engine engine = engine();
        engine.setChestVisible(true);
        engine.observeKeyContainer(KEY, mapOf(FANG, 1L, RING, 1L));
        assertEquals(
                "the exact manifest sum, never rounded to a million",
                FANG_PRICE + RING_PRICE,
                engine.pendingKeyValue(KEY, T0));
    }

    @Test
    public void anUnseenChestShowsNothing()
    {
        Engine engine = engine();
        assertEquals(0L, engine.pendingKeyValue(KEY, T0));
        engine.setChestVisible(true);
        assertEquals(0L, engine.pendingKeyValue(KEY, T0));
    }

    @Test
    public void aClosedOrEmptiedChestShowsNothing()
    {
        Engine engine = engine();
        engine.setChestVisible(true);
        engine.observeKeyContainer(KEY, mapOf(FANG, 1L, RING, 1L));
        assertEquals(FANG_PRICE + RING_PRICE, engine.pendingKeyValue(KEY, T0));

        // The loot taken: the emptied container is the last thing seen.
        engine.observeKeyContainer(KEY, Collections.emptyMap());
        assertEquals(0L, engine.pendingKeyValue(KEY, T0));

        // A closed chest keeps no stale worth either (reopening starts fresh).
        engine.observeKeyContainer(KEY, mapOf(FANG, 1L, RING, 1L));
        engine.setChestVisible(false);
        assertEquals(0L, engine.pendingKeyValue(KEY, T0));
    }

    @Test
    public void twoObservedKeyTypesSumTheirChests()
    {
        Engine engine = engine();
        engine.markPkLoot(mapOf(KEY, 1L, KEY1, 1L), 50, T0);
        assertNotNull(settle(engine, mapOf(KEY, 1L, KEY1, 1L), T0 + 600L));
        engine.setChestVisible(true);
        engine.observeKeyContainer(KEY, mapOf(FANG, 1L));
        engine.observeKeyContainer(KEY1, mapOf(RING, 3L));
        assertEquals(FANG_PRICE + 3L * RING_PRICE, engine.pendingKeysValue(T0 + 10_000L));
    }

    @Test
    public void theLedgerNamesTheWorthOncePerKeyType()
    {
        Engine engine = engine();
        engine.markPkLoot(mapOf(KEY, 1L), 50, T0);
        assertNotNull(settle(engine, mapOf(KEY, 1L), T0 + 600L));
        engine.markPkLoot(mapOf(KEY, 1L), 50, T0 + 5_000L);
        assertNotNull(settle(engine, mapOf(KEY, 2L), T0 + 5_600L));
        engine.setChestVisible(true);
        engine.observeKeyContainer(KEY, mapOf(FANG, 1L, RING, 1L));

        List<LedgerData.PendingItem> items =
                LedgerData.pendingItems(engine, LedgerData.Entry.current(), T0 + 10_000L);
        int rows = 0;
        int valued = 0;
        for (LedgerData.PendingItem item : items)
        {
            if (item.itemId != KEY) continue;
            rows++;
            if (item.value > 0L) valued++;
        }
        assertTrue("every stacked claim of the key lists", rows >= 2);
        assertEquals("but one chest names its worth once", 1, valued);
        assertEquals(FANG_PRICE + RING_PRICE, engine.pendingKeysValue(T0 + 10_000L));
    }

    @Test
    public void anUnpricedPartShowsNothing()
    {
        Engine engine = engine();
        engine.setChestVisible(true);
        engine.observeKeyContainer(KEY, mapOf(FANG, 1L, 123456, 1L));
        assertEquals(
                "one part without a quote fails the whole manifest closed",
                0L,
                engine.pendingKeyValue(KEY, T0));
    }

    @Test
    public void everyPendingKeyTypeCountsItsObservedChestOnce()
    {
        Engine engine = engine();
        engine.markPkLoot(Collections.singletonMap(KEY, 2L), 50, T0);
        assertNotNull("the key pickup books its audit row", settle(engine, KEY, 2L, T0 + 600L));
        assertFalse("and opens a pending claim", engine.getPendingClaims().isEmpty());
        assertEquals(2L, engine.getPendingClaims().get(0).getQuantity());

        engine.setChestVisible(true);
        engine.observeKeyContainer(KEY, mapOf(FANG, 1L, RING, 1L));
        assertEquals(
                "two keys of one type take one chest's contents once",
                FANG_PRICE + RING_PRICE,
                engine.pendingKeysValue(T0 + 10_000L));
        assertEquals(FANG_PRICE + RING_PRICE, engine.pendingKeyValue(KEY, T0 + 10_000L));
    }

    private static Transaction settle(Engine engine, int id, long qty, long now)
    {
        return settle(engine, Collections.singletonMap(id, qty), now);
    }

    private static Transaction settle(Engine engine, Map<Integer, Long> quantities, long now)
    {
        engine.markDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(quantities);
        Transaction result = engine.processIfDirty(snapshot, now);
        return result != null ? result : engine.processIfDirty(snapshot, now + 1L);
    }

    private static Engine engine()
    {
        Engine engine = new Engine(PendingKeyValueTest::flows, new TransactionClassifier(), CONFIG);
        engine.ensureSession(T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>()));
        return engine;
    }

    private static List<Flow> flows(Map<Integer, Long> deltas)
    {
        List<Flow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            long price =
                    entry.getKey() == FANG ? FANG_PRICE : entry.getKey() == RING ? RING_PRICE : 0L;
            flows.add(
                    new Flow(
                            entry.getKey(),
                            "Item " + entry.getKey(),
                            entry.getValue(),
                            (int) price,
                            entry.getValue() * price));
        }
        return flows;
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
