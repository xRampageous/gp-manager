package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Owner 1.2: charging any charged item moves its components into charges; it never books a loss. */
public class ChargeLoadAnyItemTest
{
    private static final long T0 = 1_000_000L;

    @Test
    public void everyItemsOwnChargeMaterialIsALoad()
    {
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.CRYSTAL_BOW, ItemID.PRIF_CRYSTAL_SHARD, "Crystal shard"));
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.SANGUINESTI, ItemID.BLOODRUNE, "Blood rune"));
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.SHADOW, ItemID.SOULRUNE, "Soul rune"));
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.ATES, ItemID.FROZEN_TEAR, "Frozen tear"));
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.VENATOR, ItemID.ANCIENT_ESSENCE, "Ancient essence"));
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.CRAW, ItemID.WILD_CAVE_SHARD, "Revenant ether"));
        assertTrue("the scythe's vials too", ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.SCYTHE, ItemID.VIAL_BLOOD, "Vial of blood"));
        assertFalse("another item is not its charge", ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.SANGUINESTI, ItemID.FIRERUNE, "Fire rune"));
    }

    @Test
    public void chargingACrystalBowBooksNothing()
    {
        Engine engine = settle(true);
        assertEquals(0L, engine.getMetrics(T0 + 10_000L).net);
        assertTrue(engine.getActiveSession().getTransactions().stream().noneMatch(Transaction::isCounted));
    }

    @Test
    public void chargingBryophytasStaffBooksNothing()
    {
        // Owner 2026-10-09 (recorded): 1,000 nature runes into the staff turned the uncharged staff into the
        // charged one and went to Review; the runes are now its charges.
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.BRYOPHYTA, ItemID.NATURERUNE, "Nature rune"));
        Engine engine = HudBuilderTest.engine();
        engine.ensureSession(T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(ItemID.NATURERUNE, 6_766L, ItemID.NATURE_STAFF_UNCHARGED, 1L))));
        engine.markChargeLoad(ChargeCheck.ChargedItem.BRYOPHYTA, ItemID.NATURERUNE, "Nature rune", "149:1:22368:BRYOPHYTA", ChargeIntake.LOAD_TICKS);
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(Map.of(ItemID.NATURERUNE, 5_766L, ItemID.NATURE_STAFF_CHARGED, 1L))), T0 + tick * 600L);
        }
        assertEquals("charging the staff is not a cost", 0L, engine.getMetrics(T0 + 10_000L).net);
        assertTrue("nor Review", engine.getActiveSession().getTransactions().stream().noneMatch(ReviewRules::needsOwnerDecision));
    }

    @Test
    public void withoutTheLoadTheSameShardsWouldBeALoss()
    {
        assertTrue(settle(false).getMetrics(T0 + 10_000L).net < 0L);
    }

    @Test
    public void theScytheOnTheVyreWellLoadsItsRunesAndVialsThroughThePrompt()
    {
        Engine engine = HudBuilderTest.engine();
        engine.ensureSession(T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(ItemID.BLOODRUNE, 1_000L, ItemID.VIAL_BLOOD, 5L))));
        Widget scythe = widget(ItemID.SCYTHE_OF_VITUR, 149 << 16, 3);
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> "getSelectedWidget".equals(method.getName()) ? scythe
                : "getTickCount".equals(method.getName()) ? 10 : null);
        ChargeIntake intake = new ChargeIntake(client, null, HudBuilderTest.config(true, 4), engine, null);
        intake.armLoad(click(MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, "Use", null), "use");
        intake.armLoad(click(MenuAction.WIDGET_CONTINUE, "Continue", null), "continue");
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(Map.of(ItemID.BLOODRUNE, 800L, ItemID.VIAL_BLOOD, 4L))), T0 + tick * 600L);
        }
        assertEquals("charging the scythe is not a cost", 0L, engine.getMetrics(T0 + 10_000L).net);
    }

    private static Engine settle(boolean loaded)
    {
        Engine engine = HudBuilderTest.engine();
        engine.ensureSession(T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(ItemID.PRIF_CRYSTAL_SHARD, 500L))));
        if (loaded)
        {
            engine.markChargeLoad(ChargeCheck.ChargedItem.CRYSTAL_BOW, ItemID.PRIF_CRYSTAL_SHARD, "Crystal shard", "149:0:23983:CRYSTAL_BOW",
                ChargeIntake.LOAD_TICKS);
        }
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(Map.of(ItemID.PRIF_CRYSTAL_SHARD, 400L))), T0 + tick * 600L);
        }
        return engine;
    }

    static Widget widget(int itemId, int id, int index)
    {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
            (proxy, method, args) -> "getItemId".equals(method.getName()) ? itemId
                : "getId".equals(method.getName()) ? id : "getIndex".equals(method.getName()) ? index
                : method.getReturnType() == int.class ? 0 : method.getReturnType() == boolean.class ? false : null);
    }

    static MenuOptionClicked click(MenuAction type, String option, Widget widget)
    {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[]{MenuEntry.class}, (proxy, method, args) ->
            {
                if ("getOption".equals(method.getName())) return option;
                if ("getTarget".equals(method.getName())) return "";
                if ("getType".equals(method.getName())) return type;
                if ("getWidget".equals(method.getName())) return widget;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
        return new MenuOptionClicked(entry);
    }
}
