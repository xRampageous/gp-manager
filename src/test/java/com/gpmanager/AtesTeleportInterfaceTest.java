package com.gpmanager;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Owner 1.3.4: a Pendant of Ates Rub-interface destination click books the frozen tear even though
 * the button carries no item id; the pendant, worn or in the pack, names the row, the minimenu
 * path is unchanged and a click with no pendant books nothing.
 */
public class AtesTeleportInterfaceTest
{
    private static final long T0 = 1_000_000L;
    private static final int TEAR = ItemID.FROZEN_TEAR;
    private static final int PENDANT = ItemID.PENDANT_OF_ATES;

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 0;
        }
    };

    @Test
    public void aDestinationClickBooksOneTearFromTheWornPendant()
    {
        Harness h = new Harness(
            container(EquipmentInventorySlot.AMULET.getSlotIdx(), PENDANT), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "kastori",
                ChargeLoadAnyItemTest.widget(0, InterfaceID.PendantOfAtes.TELEPORT_AUBURN, -1)),
            "kastori", "");
        List<Transaction> rows = h.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(1L, rows.get(0).quantity(TEAR, false));
        assertEquals(ActionKind.SUPPLIES, rows.get(0).getActionKind());
    }

    @Test
    public void aDestinationClickBooksOneTearFromThePack()
    {
        Harness h = new Harness(emptyContainer(), container(0, PENDANT));
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "nemus",
                ChargeLoadAnyItemTest.widget(0, InterfaceID.PendantOfAtes.TELEPORT_TLATI, -1)),
            "nemus", "");
        assertEquals(1, h.engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void withoutAPendantNothingBooks()
    {
        Harness h = new Harness(emptyContainer(), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "kastori",
                ChargeLoadAnyItemTest.widget(0, InterfaceID.PendantOfAtes.TELEPORT_TLATI, -1)),
            "kastori", "");
        assertEquals(0, h.engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void closeAndOtherComponentsBookNothing()
    {
        Harness h = new Harness(
            container(EquipmentInventorySlot.AMULET.getSlotIdx(), PENDANT), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "close",
                ChargeLoadAnyItemTest.widget(0, InterfaceID.PendantOfAtes.CLOSE, -1)),
            "close", "");
        assertEquals(0, h.engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void theMinimenuDestinationStillBooksOnce()
    {
        Harness h = new Harness(emptyContainer(), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "darkfrost",
                ChargeLoadAnyItemTest.widget(PENDANT, InterfaceID.Inventory.ITEMS, 0)),
            "darkfrost", "");
        List<Transaction> rows = h.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(1L, rows.get(0).quantity(TEAR, false));
    }

    @Test
    public void aMinimenuDestinationWithoutAnItemIdBooksFromTheWornPendant()
    {
        // Owner 1.3.4 live capture: "Nemus Retreat" on the worn pendant arrived as
        // CC_OP_LOW_PRIORITY with itemId -1 and the worn-amulet widget.
        Harness h = new Harness(
            container(EquipmentInventorySlot.AMULET.getSlotIdx(), PENDANT), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP_LOW_PRIORITY, "nemus retreat",
                ChargeLoadAnyItemTest.widget(-1, InterfaceID.Wornitems.UNIVERSE, -1)),
            "nemus retreat", "pendant of ates");
        List<Transaction> rows = h.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(1L, rows.get(0).quantity(TEAR, false));
    }

    @Test
    public void aMemoirsLocationWithoutAnItemIdNeverBooksATear()
    {
        Harness h = new Harness(
            container(EquipmentInventorySlot.AMULET.getSlotIdx(), PENDANT), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP_LOW_PRIORITY, "the fisher's flute",
                ChargeLoadAnyItemTest.widget(-1, InterfaceID.Wornitems.UNIVERSE, -1)),
            "the fisher's flute", "kharedst's memoirs");
        assertEquals(0, h.engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void everyDestinationComponentAndBothEventShapesAreRecognized()
    {
        int[] components = {
            InterfaceID.PendantOfAtes.TELEPORT_AUBURN,
            InterfaceID.PendantOfAtes.TELEPORT_TWILIGHT,
            InterfaceID.PendantOfAtes.TELEPORT_DARKFROST,
            InterfaceID.PendantOfAtes.TELEPORT_RALOS,
            InterfaceID.PendantOfAtes.TELEPORT_ALDARIN,
            InterfaceID.PendantOfAtes.TELEPORT_TLATI};
        for (int component : components)
        {
            assertTrue(Integer.toString(component), ChargeIntake.isAtesDestination(
                ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "kastori",
                    ChargeLoadAnyItemTest.widget(0, component, -1))));
            assertTrue(Integer.toString(component), ChargeIntake.isAtesDestination(clickWithParam1(component)));
        }
        assertFalse(ChargeIntake.isAtesDestination(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "close",
                ChargeLoadAnyItemTest.widget(0, InterfaceID.PendantOfAtes.CLOSE, -1))));
        assertFalse(ChargeIntake.isAtesDestination(null));
    }

    @Test
    public void alternateMinimenuNamesAreLocations()
    {
        assertTrue(ChargeIntake.isAtesLocation("north of kastori"));
        assertTrue(ChargeIntake.isAtesLocation("south of nemus retreat"));
        assertFalse("rubbing is not a teleport", ChargeIntake.isAtesLocation("rub"));
    }

    @Test
    public void aRefusedTeleportRemovesTheEstimateItBooked()
    {
        // Owner live 2026-10-12: "already at that location" booked no charge.
        Harness h = new Harness(
            container(EquipmentInventorySlot.AMULET.getSlotIdx(), PENDANT), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP_LOW_PRIORITY, "nemus retreat",
                ChargeLoadAnyItemTest.widget(-1, InterfaceID.Wornitems.UNIVERSE, -1)),
            "nemus retreat", "pendant of ates");
        List<Transaction> rows = h.engine.getActiveSession().getTransactions();
        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).quantity(TEAR, false));
        h.intake.refusedTeleport(System.currentTimeMillis());
        assertEquals("the refused click is not a use", Correction.IGNORE, rows.get(0).getCorrection());
        assertEquals(0L, rows.get(0).quantity(TEAR, false));
        assertEquals(0L, h.engine.getMetrics(System.currentTimeMillis() + 1_000L).costs);
    }

    @Test
    public void aRefusalCancelsOnlyTheNewestClick()
    {
        Harness h = new Harness(
            container(EquipmentInventorySlot.AMULET.getSlotIdx(), PENDANT), emptyContainer());
        for (String option : new String[] {"nemus retreat", "kastori"})
        {
            h.intake.onMenuOptionClicked(
                ChargeLoadAnyItemTest.click(MenuAction.CC_OP_LOW_PRIORITY, option,
                    ChargeLoadAnyItemTest.widget(-1, InterfaceID.Wornitems.UNIVERSE, -1)),
                option, "pendant of ates");
        }
        List<Transaction> rows = h.engine.getActiveSession().getTransactions();
        assertEquals(2, rows.size());
        h.intake.refusedTeleport(System.currentTimeMillis());
        assertEquals("an earlier successful click stays", Correction.AUTO,
            rows.get(0).getCorrection());
        assertEquals(1L, rows.get(0).quantity(TEAR, false));
        assertEquals(Correction.IGNORE, rows.get(1).getCorrection());
        assertEquals(1_000L, h.engine.getMetrics(System.currentTimeMillis() + 1_000L).costs);
    }

    @Test
    public void theRealRefusalLineDropsTheEstimateForBothApostrophesAndMessageTypes()
            throws Exception
    {
        // Owner 1.3.4 review: the game's line must reach the intake through the plugin's chat
        // path, so a pattern or wording drift cannot pass the gate unseen. Owner live 2026-10-13:
        // the live line arrives as a "click here to continue" box (MESBOX), not GAMEMESSAGE.
        for (ChatMessageType type : new ChatMessageType[] {
            ChatMessageType.GAMEMESSAGE, ChatMessageType.MESBOX})
        {
            for (String apostrophe : new String[] {"'", "\u2019"})
            {
                Harness h = new Harness(
                    container(EquipmentInventorySlot.AMULET.getSlotIdx(), PENDANT), emptyContainer());
                h.intake.onMenuOptionClicked(
                    ChargeLoadAnyItemTest.click(MenuAction.CC_OP_LOW_PRIORITY, "nemus retreat",
                        ChargeLoadAnyItemTest.widget(-1, InterfaceID.Wornitems.UNIVERSE, -1)),
                    "nemus retreat", "pendant of ates");
                List<Transaction> rows = h.engine.getActiveSession().getTransactions();
                assertEquals(apostrophe, 1, rows.size());

                GpManagerPlugin plugin = new GpManagerPluginProbe();
                set(plugin, "config", CONFIG);
                set(plugin, "engine", h.engine);
                set(plugin, "charges", h.intake);
                ChatMessage refusal = new ChatMessage();
                refusal.setType(type);
                refusal.setMessage("The pendant doesn" + apostrophe
                    + "t respond as you appear to already be at that location.");
                plugin.onChatMessage(refusal);

                assertEquals(type + " " + apostrophe, Correction.IGNORE,
                    rows.get(0).getCorrection());
                assertEquals(0L, rows.get(0).quantity(TEAR, false));
            }
        }
    }

    @Test
    public void aStaleRefusalOrOneWithoutAClickChangesNothing()
    {
        Harness none = new Harness(emptyContainer(), emptyContainer());
        none.intake.refusedTeleport(System.currentTimeMillis());
        assertEquals("no click, nothing to drop", 0,
            none.engine.getActiveSession().getTransactions().size());

        Harness h = new Harness(
            container(EquipmentInventorySlot.AMULET.getSlotIdx(), PENDANT), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP_LOW_PRIORITY, "kastori",
                ChargeLoadAnyItemTest.widget(-1, InterfaceID.Wornitems.UNIVERSE, -1)),
            "kastori", "pendant of ates");
        List<Transaction> rows = h.engine.getActiveSession().getTransactions();
        assertEquals(1, rows.size());
        h.intake.refusedTeleport(System.currentTimeMillis() + 4_000L);
        assertEquals("a refusal outside the window is not a cancel", Correction.AUTO,
            rows.get(0).getCorrection());
        assertEquals(1L, rows.get(0).quantity(TEAR, false));
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = GpManagerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Harness
    {
        final Engine engine;
        final ChargeIntake intake;

        Harness(ItemContainer worn, ItemContainer inventory)
        {
            Valuation valuation = ModelProbe.valuation(CONFIG, AtesTeleportInterfaceTest::compositionOf,
                id -> id == TEAR ? 1_000 : 0);
            engine = new Engine(valuation, new TransactionClassifier(), CONFIG);
            engine.startCustom("Ates", T0);
            engine.setBaseline(new ContainerSnapshot(new HashMap<>()));
            intake = new ChargeIntake(client(worn, inventory), lookup(), CONFIG, engine, valuation);
        }
    }

    private static ItemLookup lookup()
    {
        return new ItemLookup(null)
        {
            @Override
            ItemComposition composition(int id)
            {
                return compositionOf(id);
            }
        };
    }

    private static ItemComposition compositionOf(int id)
    {
        String name = id == TEAR ? "Frozen tear" : "Item " + id;
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
            new Class<?>[] {ItemComposition.class}, (proxy, method, args) ->
            {
                if ("getName".equals(method.getName())) return name;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    private static Client client(ItemContainer worn, ItemContainer inventory)
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) ->
            {
                if ("getItemContainer".equals(method.getName()))
                {
                    int containerId = (int) args[0];
                    if (containerId == InventoryID.WORN) return worn;
                    if (containerId == InventoryID.INV) return inventory;
                    return null;
                }
                if ("getTickCount".equals(method.getName())) return 10;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    private static ItemContainer container(int slot, int itemId)
    {
        return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
            new Class<?>[] {ItemContainer.class}, (proxy, method, args) ->
            {
                if ("getItem".equals(method.getName()))
                    return (int) args[0] == slot ? new Item(itemId, 1) : null;
                if ("size".equals(method.getName())) return 28;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    private static ItemContainer emptyContainer()
    {
        return container(-1, 0);
    }

    private static MenuOptionClicked clickWithParam1(int component)
    {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
            {
                if ("getOption".equals(method.getName())) return "kastori";
                if ("getTarget".equals(method.getName())) return "";
                if ("getType".equals(method.getName())) return MenuAction.CC_OP;
                if ("getParam1".equals(method.getName())) return component;
                if ("getWidget".equals(method.getName())) return null;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
        return new MenuOptionClicked(entry);
    }
}
