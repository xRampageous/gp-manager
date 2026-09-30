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
        Am engine = engine();
        engine.rm(T0);
        Map<Integer, Long> held = new HashMap<>();
        held.put(ItemID.BLOOD_SHARD, 1L);
        held.put(ItemID.ENCHANTED_ONYX_AMULET, 1L);
        engine.setBaseline(new Cc(held));
        engine.yz();
        Map<Integer, Long> attached = new HashMap<>();
        attached.put(ItemID.BLOOD_AMULET, 1L);

        Ac settled = settle(engine, attached, T0);
        assertNotNull(settled);
        assertEquals("a mixed attach with no context stays uncertain", Ai.UNCERTAIN,
            settled.getType());
        assertFalse("the default never counts an uncertain mixed change", settled.isCounted());
        assertEquals("Net stays 0 until a read measures hits used", 0L,
            engine.getMetrics(T0 + 2_400L).net);
    }

    @Test
    public void aValidatedRechargeTransfersNeutrallyAndNeverBlocks()
    {
        Am engine = engine();
        engine.rm(T0);
        Map<Integer, Long> held = new HashMap<>();
        held.put(ItemID.BLOOD_SHARD, 1L);
        engine.setBaseline(new Cc(held));
        engine.yz();
        assertTrue(engine.yx(Ar.V.BLOOD_FURY, ItemID.BLOOD_SHARD, "Blood shard",
            "149:2:0:" + ItemID.BLOOD_AMULET + ":BLOOD_FURY", 6));

        Ac load = settle(engine, new HashMap<>(), T0);
        assertNotNull(load);
        assertEquals("value moved into charges, never a cost", Ai.TRANSFER, load.tm());
        assertFalse(load.isCounted());
        assertEquals(0L, engine.getMetrics(T0 + 2_400L).net);
        assertTrue("nothing is left to block measured hits",
            engine.chargeLoadReviews.acy(Ar.V.BLOOD_FURY).isEmpty());
    }

    private static Ac settle(Am engine, Map<Integer, Long> held, long from)
    {
        Ac result = null;
        for (int i = 0; i < 3; i++)
        {
            Ac settled = engine.adj(new Cc(new HashMap<>(held)), from + 600L + i * 600L);
            if (settled != null)
            {
                result = settled;
            }
        }
        return result;
    }

    private static Am engine()
    {
        Map<Integer, String> names = new HashMap<>();
        names.put(ItemID.BLOOD_SHARD, "Blood shard");
        names.put(ItemID.ENCHANTED_ONYX_AMULET, "Amulet of fury");
        names.put(ItemID.BLOOD_AMULET, "Amulet of blood fury");
        Map<Integer, Integer> prices = new HashMap<>();
        prices.put(ItemID.BLOOD_SHARD, 8_000_000);
        prices.put(ItemID.ENCHANTED_ONYX_AMULET, 2_800_000);
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int unit = prices.getOrDefault(delta.getKey(), 0);
                flows.add(new Ab(delta.getKey(),
                    names.getOrDefault(delta.getKey(), "Item " + delta.getKey()),
                    delta.getValue(), unit, delta.getValue() * unit, Av.GRAND_EXCHANGE));
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
