package com.gpmanager;

import java.util.*;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Owner 2026-09-29: a blood shard becomes a charge only when a read measures hits used.
 * Attaching one to a plain fury is an uncounted mixed change, and a pending recharge blocks
 * the shard from measured spend until the Review row is decided — never both at once.
 */
public class BloodFuryLoadTest
{
    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void attachingABloodShardToAFuryIsAnUncountedMixedChange()
    {
        Engine engine = engine();
        engine.ensureSession(T0);
        Map<Integer, Long> held = new HashMap<>();
        held.put(ItemID.BLOOD_SHARD, 1L);
        held.put(ItemID.ENCHANTED_ONYX_AMULET, 1L);
        engine.setBaseline(new ContainerSnapshot(held));
        engine.markInventoryDirty();
        Map<Integer, Long> attached = new HashMap<>();
        attached.put(ItemID.BLOOD_AMULET, 1L);

        Transaction settled = settle(engine, attached, T0);
        assertNotNull(settled);
        assertEquals("a mixed attach with no context stays uncertain", TransactionType.UNCERTAIN,
            settled.getType());
        assertFalse("the default never counts an uncertain mixed change", settled.isCounted());
        assertEquals("Net stays 0 until a read measures hits used", 0L,
            engine.getMetrics(T0 + 2_400L).net);
    }

    @Test
    public void aValidatedRechargeTransfersNeutrallyAndNeverBlocks()
    {
        Engine engine = engine();
        engine.ensureSession(T0);
        Map<Integer, Long> held = new HashMap<>();
        held.put(ItemID.BLOOD_SHARD, 1L);
        engine.setBaseline(new ContainerSnapshot(held));
        engine.markInventoryDirty();
        assertTrue(engine.markChargeLoadTransfer(ChargeRead.Variant.BLOOD_FURY, ItemID.BLOOD_SHARD, "Blood shard",
            "149:2:0:" + ItemID.BLOOD_AMULET + ":BLOOD_FURY", 6));

        Transaction load = settle(engine, new HashMap<>(), T0);
        assertNotNull(load);
        assertEquals("value moved into charges, never a cost", TransactionType.TRANSFER, load.getAutomaticType());
        assertFalse(load.isCounted());
        assertEquals(0L, engine.getMetrics(T0 + 2_400L).net);
        assertTrue("nothing is left to block measured hits",
            engine.chargeLoadReviews.pendingChargeLoadComponents(ChargeRead.Variant.BLOOD_FURY).isEmpty());
    }

    private static Transaction settle(Engine engine, Map<Integer, Long> held, long from)
    {
        Transaction result = null;
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(new ContainerSnapshot(new HashMap<>(held)), from + 600L + i * 600L);
            if (settled != null)
            {
                result = settled;
            }
        }
        return result;
    }

    private static Engine engine()
    {
        Map<Integer, String> names = new HashMap<>();
        names.put(ItemID.BLOOD_SHARD, "Blood shard");
        names.put(ItemID.ENCHANTED_ONYX_AMULET, "Amulet of fury");
        names.put(ItemID.BLOOD_AMULET, "Amulet of blood fury");
        Map<Integer, Integer> prices = new HashMap<>();
        prices.put(ItemID.BLOOD_SHARD, 8_000_000);
        prices.put(ItemID.ENCHANTED_ONYX_AMULET, 2_800_000);
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int unit = prices.getOrDefault(delta.getKey(), 0);
                flows.add(new Flow(delta.getKey(),
                    names.getOrDefault(delta.getKey(), "Item " + delta.getKey()),
                    delta.getValue(), unit, delta.getValue() * unit, PriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 0;
            }
        });
    }
}
