package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Owner report 2026-09-28: an Ice Barrage cast settling with a pickup showed as loose Death, Blood and
 * Water "losses" on the loot receipt, and a spell clicked while autocasting renamed the autocast.
 */
public class CastWithPickupTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final Map<Integer, String> NAMES = new HashMap<>();

    static
    {
        NAMES.put(560, "Death rune");
        NAMES.put(565, "Blood rune");
        NAMES.put(555, "Water rune");
        NAMES.put(1359, "Rune axe");
    }

    @Test
    public void aCastSettlingWithAPickupBooksAsItsOwnCast()
    {
        Am engine = engine();
        engine.ajl("Nechryael", Cx.GENERAL, T0);
        Map<Integer, Long> held = new HashMap<>();
        held.put(560, 100L);
        held.put(565, 100L);
        held.put(555, 100L);
        engine.setBaseline(new Cc(new HashMap<>(held)));
        held.put(560, 96L);
        held.put(565, 98L);
        held.put(555, 94L);
        held.put(1359, 1L);
        engine.yz();
        List<Ac> booked = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            Ac settled = engine.adj(new Cc(new HashMap<>(held)),
                T0 + 600L + i * 600L, booked::add);
            if (settled != null)
            {
                booked.add(settled);
            }
        }
        Ac cast = booked.stream().filter(t -> t.getActionKind() == Au.CAST).findFirst().orElse(null);
        assertNotNull("the runes book as a Cast: " + booked, cast);
        assertEquals("Ice Barrage", cast.spellName());
        assertEquals(3, cast.getFlows().size());
        assertTrue("the pickup keeps only its gain", booked.stream().anyMatch(t -> t != cast
            && t.getFlows().size() == 1 && t.getFlows().get(0).itemId == 1359 && t.isCounted()));
    }

    @Test
    public void aQueuedClickNeverRenamesTheSpellItsRunesPaid()
    {
        Ac barrage = new Ac(T0, null, Ai.CONSUMPTION, Aj.GENERIC,
            "", "Nechryael", true, Arrays.asList(new Ab(560, "Death rune", -4L, 187, -748L),
                new Ab(565, "Blood rune", -2L, 341, -682L), new Ab(555, "Water rune", -6L, 5, -30L)),
            Bd.CONFIRMED, "fixture", null);
        barrage.setActionKind(Au.CAST);
        barrage.ahu(Bb.of("Ice Burst"));
        assertEquals("Ice Barrage", barrage.spellName());

        Ac alch = new Ac(T0, null, Ai.CONSUMPTION, Aj.GENERIC,
            "", "Magic", true, Arrays.asList(new Ab(561, "Nature rune", -1L, 100, -100L),
                new Ab(554, "Fire rune", -5L, 5, -25L)), Bd.CONFIRMED, "fixture", null);
        alch.setActionKind(Au.CAST);
        alch.ahu(Bb.of("High Level Alchemy"));
        assertEquals("a spell outside the combat table keeps its click", "High Level Alchemy", alch.spellName());
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
                int unit = delta.getKey() == 1359 ? 7_202 : 100;
                flows.add(new Ab(delta.getKey(), NAMES.get(delta.getKey()), delta.getValue(), unit,
                    delta.getValue() * unit, Av.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
