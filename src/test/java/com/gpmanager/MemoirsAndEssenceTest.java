package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Owner 1.2: the costs of Kharedst's memoirs (runes per teleport) and the blood essence. */
public class MemoirsAndEssenceTest
{
    private static final long T0 = 1_000_000L;
    private static final Map<Integer, Long> RUNES = Map.of(ItemID.LAWRUNE, 100L, ItemID.BODYRUNE, 100L,
        ItemID.MINDRUNE, 100L, ItemID.SOULRUNE, 100L);

    @Test
    public void theMemoirsCheckReadsItsRunes()
    {
        Ar read = Ar.acs("On the inside of the cover a message is displayed in dark ink. It reads: 87 memories remain.");
        assertEquals(Ar.V.MEMOIRS, read.variant);
        assertEquals(Long.valueOf(87L), read.componentCounts.get(ItemID.SOULRUNE));
        assertEquals(Long.valueOf(87L), read.componentCounts.get(ItemID.LAWRUNE));
        assertEquals(Ar.V.MEMOIRS, Ar.ajb("Book of the dead"));
    }

    @Test
    public void theBooksTeleportsAreLocations()
    {
        assertTrue(ChargeIntake.lh("the fisher's flute"));
        assertTrue(ChargeIntake.lh("lunch by the lancalliums"));
        assertTrue(ChargeIntake.lh("a dark disposition"));
    }

    @Test
    public void chargingAtTheOldMemorialBooksNothing()
    {
        assertEquals(0L, memorial(true).getMetrics(T0 + 10_000L).net);
        assertTrue("without the memorial click the runes would be a loss", memorial(false).getMetrics(T0 + 10_000L).net < 0L);
    }

    @Test
    public void activatingABloodEssenceIsUsedNotReview()
    {
        assertTrue(Dw.b1("activate"));
        Am engine = HudBuilderTest.engine();
        engine.rm(T0);
        engine.setBaseline(new Cc(new HashMap<>(Map.of(ItemID.BLOOD_ESSENCE_INACTIVE, 2L))));
        engine.ci(ItemID.BLOOD_ESSENCE_INACTIVE, 6);
        engine.yz();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.adj(new Cc(new HashMap<>(Map.of(ItemID.BLOOD_ESSENCE_INACTIVE, 1L, ItemID.BLOOD_ESSENCE_ACTIVE, 1L))),
                T0 + tick * 600L);
        }
        assertTrue(engine.getActiveSession().getTransactions().toString(),
            engine.getActiveSession().getTransactions().stream().allMatch(row -> row.tm() == Ai.CONSUMPTION));
        assertEquals(1, engine.getActiveSession().getTransactions().size());
    }

    private static Am memorial(boolean inspected)
    {
        Am engine = HudBuilderTest.engine();
        engine.rm(T0);
        engine.setBaseline(new Cc(new HashMap<>(RUNES)));
        if (inspected)
        {
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, args) -> "getTickCount".equals(method.getName()) ? 10 : null);
            ChargeIntake intake = new ChargeIntake(client, null, HudBuilderTest.config(true, 4), engine, null);
            intake.onMenuOptionClicked(ChargeLoadAnyItemTest.click(MenuAction.GAME_OBJECT_FIRST_OPTION, "Inspect", null),
                "inspect", "old memorial");
        }
        engine.yz();
        var after = new HashMap<Integer, Long>();
        RUNES.forEach((id, quantity) -> after.put(id, quantity - 20L));
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.adj(new Cc(new HashMap<>(after)), T0 + tick * 600L);
        }
        return engine;
    }
}
