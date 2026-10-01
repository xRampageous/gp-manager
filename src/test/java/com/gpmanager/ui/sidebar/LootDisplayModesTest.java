package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import net.runelite.api.WorldType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Owner report 2026-09-28: drops showed "?" values. Each Display loot filter mode shows the right
 * rows, every shown drop keeps its price, and no mode changes Net.
 */
public class LootDisplayModesTest
{
    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void everyLootDisplayModeKeepsDropValuesAndShowsItsRows()
    {
        // The Ground Items lists: Iron mace highlighted, Bones hidden, Onion seed on neither.
        LootPresentationFilterService service = new LootPresentationFilterService(
            new Bc(true, "Iron mace", "Bones", false));
        Am engine = PresentationLifecycleTest.engine();
        engine.ajl("Guard", Cx.GENERAL, T0);
        book(engine, T0 + 1_000L, new Ab(1420, "Iron mace", 1, 52, 52L));
        book(engine, T0 + 2_000L, new Ab(526, "Bones", 4, 90, 360L));
        book(engine, T0 + 3_000L, new Ab(5319, "Onion seed", 4, 3, 12L));
        long net = engine.getMetrics(T0 + 4_000L).net;

        assertEquals(List.of("Onion seed", "Bones", "Iron mace"),
            shown(engine, service, Dl.ALL_ITEMS));
        assertEquals("Follow Ground Items hides the hidden list", List.of("Onion seed", "Iron mace"),
            shown(engine, service, Dl.FOLLOW_GROUND_ITEMS));
        assertEquals("Highlighted list only shows the highlighted list", List.of("Iron mace"),
            shown(engine, service, Dl.HIGHLIGHTED_LIST_ONLY));
        assertEquals("no display mode changes Net", net, engine.getMetrics(T0 + 5_000L).net);
        assertEquals(52L + 360L + 12L, net);
    }

    /** Ordinary main-game worlds, PvP and LMS included, price drops; only separate economies do not. */
    @Test
    public void mainGameWorldsPriceDrops()
    {
        for (WorldType flag : EnumSet.complementOf(EnumSet.of(WorldType.DEADMAN, WorldType.SEASONAL,
            WorldType.QUEST_SPEEDRUNNING, WorldType.BETA_WORLD, WorldType.TOURNAMENT_WORLD, WorldType.NOSAVE_MODE)))
        {
            Bl valuation = new Bl(new GpManagerConfig() {}, id -> null,
                (id, active) -> 52, id -> false,
                Bl.rh(EnumSet.of(WorldType.MEMBERS, flag)));
            Ab drop = valuation.value(Collections.singletonMap(1420, 1L), T0).get(0);
            assertEquals(flag + " prices drops", Av.GRAND_EXCHANGE, drop.getPriceSource());
            assertEquals(52L, drop.valueDelta);
        }
    }

    private static List<String> shown(Am engine, LootPresentationFilterService service,
        Dl mode)
    {
        RecentFilter filter = flow -> service.isFlowIncluded(flow, mode);
        Ca snapshot = Ca.capture(engine, T0 + 4_000L,
            new Dz(false, filter, 0L, Bo.NONE, "", false, ""));
        List<String> names = new ArrayList<>();
        for (Ca.Recent row : snapshot.recent)
        {
            assertFalse(mode + ": " + row.name + " keeps its price", row.unpriced);
            assertTrue(mode + ": " + row.name + " shows a value", row.value > 0L);
            names.add(row.name);
        }
        return names;
    }

    private static void book(Am engine, long at, Ab flow)
    {
        engine.getActiveSession().kf(Tx.of(at, Ai.LOOT, Aj.LOOT,
            "Loot from Guard", true, Collections.singletonList(flow)), 2_000);
    }
}
