package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Owner report 2026-09-28: 50 Chaos runes picked up in the same settle as one thrown Steel dart went
 * to an uncounted Review. A pickup with only a routine rune/ammo spend is a counted gain net of it.
 */
public class PickupWithRoutineSpendTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final int POUCH = 22538;
    private static final Map<Integer, String> NAMES = new HashMap<>();

    static
    {
        NAMES.put(562, "Chaos rune");
        NAMES.put(808, "Steel dart");
        NAMES.put(385, "Shark");
        NAMES.put(POUCH, "Coin pouch");
        NAMES.put(995, "Coins");
    }

    @Test
    public void openingCoinPouchesIsACountedGain()
    {
        Ac settled = settleFrom(POUCH, 2L, 995, 60L, POUCH, 0L);
        assertNotNull(settled);
        assertEquals("the coins count as income, not an uncounted Review",
            Ai.GAIN, settled.getType());
        assertTrue(settled.isCounted());
    }

    @Test
    public void aPickupWithADartThrownIsACountedGain()
    {
        Ac settled = settleFrom(808, 10L, 562, 50L, 808, 9L);
        assertNotNull(settled);
        assertEquals(Ai.GAIN, settled.getType());
        assertTrue("counted, never an uncounted Review", settled.isCounted());
        assertEquals("Net is the runes less the dart", 50L * 90L - 4L, settled.getNet());
    }

    @Test
    public void aPickupWithFoodEatenStaysReview()
    {
        Ac settled = settleFrom(385, 3L, 562, 50L, 385, 2L);
        assertNotNull(settled);
        assertEquals("food is not a routine combat spend", Ai.UNCERTAIN, settled.getType());
    }

    /** Baseline {first: firstQty}, then {gainId: gainQty, first: firstAfter}. */
    private static Ac settleFrom(int first, long firstQty, int gainId, long gainQty, int after, long afterQty)
    {
        Am engine = engine();
        engine.ajl("Nechryael", Cx.GENERAL, T0);
        Map<Integer, Long> held = new HashMap<>();
        held.put(first, firstQty);
        engine.setBaseline(new Cc(new HashMap<>(held)));
        held.put(gainId, gainQty);
        held.put(after, afterQty);
        engine.yz();
        Ac result = null;
        for (int i = 0; i < 3; i++)
        {
            Ac settled = engine.adj(new Cc(new HashMap<>(held)), T0 + 600L + i * 600L);
            if (settled != null)
            {
                result = settled;
            }
        }
        return result;
    }

    private static Am engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 1;
            }
        };
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int unit = delta.getKey() == 562 ? 90 : delta.getKey() == 808 ? 4 : 900;
                flows.add(new Ab(delta.getKey(), NAMES.get(delta.getKey()), delta.getValue(), unit,
                    delta.getValue() * unit, Av.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
