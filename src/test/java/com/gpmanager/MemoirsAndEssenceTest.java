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
        ChargeCheck read = ChargeCheck.parse("On the inside of the cover a message is displayed in dark ink. It reads: 87 memories remain.");
        assertEquals(ChargeCheck.ChargedItem.MEMOIRS, read.variant);
        assertEquals(Long.valueOf(87L), read.counts.get(ItemID.SOULRUNE));
        assertEquals(Long.valueOf(87L), read.counts.get(ItemID.LAWRUNE));
        assertEquals(ChargeCheck.ChargedItem.MEMOIRS, ChargeCheck.supportedForName("Book of the dead"));
    }

    @Test
    public void theBooksTeleportsAreLocations()
    {
        assertTrue(ChargeIntake.isAtesLocation("the fisher's flute"));
        assertTrue(ChargeIntake.isAtesLocation("lunch by the lancalliums"));
        assertTrue(ChargeIntake.isAtesLocation("a dark disposition"));
    }

    @Test
    public void chargingAtTheOldMemorialBooksNothing()
    {
        assertEquals(0L, memorial(true).metrics(T0 + 10_000L).net);
        assertTrue("without the memorial click the runes would be a loss", memorial(false).metrics(T0 + 10_000L).net < 0L);
    }

    @Test
    public void activatingABloodEssenceIsUsedNotReview()
    {
        assertTrue(ActionSignals.isSpend("activate"));
        Engine engine = HudBuilderTest.engine();
        engine.ensureSession(T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(ItemID.BLOOD_ESSENCE_INACTIVE, 2L))));
        engine.noteConsume(ItemID.BLOOD_ESSENCE_INACTIVE, 6, false, null, null);
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(Map.of(ItemID.BLOOD_ESSENCE_INACTIVE, 1L, ItemID.BLOOD_ESSENCE_ACTIVE, 1L))),
                T0 + tick * 600L, null);
        }
        assertTrue(engine.session().transactions().toString(),
            engine.session().transactions().stream().allMatch(row -> row.original() == TransactionType.CONSUMPTION));
        assertEquals(1, engine.session().transactions().size());
    }

    private static Engine memorial(boolean inspected)
    {
        Engine engine = HudBuilderTest.engine();
        engine.ensureSession(T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(RUNES)));
        if (inspected)
        {
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, args) -> "getTickCount".equals(method.getName()) ? 10 : null);
            ChargeIntake intake = new ChargeIntake(client, null, HudBuilderTest.config(true, 4), engine, null);
            intake.onMenuOptionClicked(ChargeLoadAnyItemTest.click(MenuAction.GAME_OBJECT_FIRST_OPTION, "Inspect", null),
                "inspect", "old memorial");
        }
        engine.markDirty();
        var after = new HashMap<Integer, Long>();
        RUNES.forEach((id, quantity) -> after.put(id, quantity - 20L));
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(after)), T0 + tick * 600L, null);
        }
        return engine;
    }
}
