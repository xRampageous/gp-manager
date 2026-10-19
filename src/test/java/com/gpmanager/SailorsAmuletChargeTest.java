package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static com.gpmanager.EngineTestFixtures.mapOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The sailors' amulet: one law rune and ten water runes load ten charges neutrally; each teleport
 * names its destination on the click (worn menu or teleport interface) and books one charge's
 * share, 1/10 law plus 1 water; its Check measures the true count (OSRS Wiki, owner 2026-10-14).
 */
public class SailorsAmuletChargeTest
{
    private static final long T0 = 1_000_000L;
    private static final int AMULET = ItemID.SAILORS_AMULET;
    private static final int LAW = ItemID.LAWRUNE;
    private static final int WATER = ItemID.WATERRUNE;

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 0;
        }
    };

    private static final GpManagerConfig SETTLE_CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 2;
        }

        @Override
        public boolean keepTransferAuditRows()
        {
            return true;
        }
    };

    @Test
    public void bothCheckWordingsResolveToTheAmulet()
    {
        ChargeCheck exact = ChargeCheck.parse("The amulet has 106 charges.");
        assertNotNull(exact);
        assertEquals(ChargeCheck.ChargedItem.SAILORS_AMULET, exact.variant);
        ChargeCheck left = ChargeCheck.parse("Your amulet has 105 charges left.");
        assertNotNull(left);
        assertEquals(ChargeCheck.ChargedItem.SAILORS_AMULET, left.variant);
        assertNotNull(ChargeCheck.parse("The amulet has one charge."));
        assertNotNull(ChargeCheck.parse("The amulet has no charges."));
    }

    @Test
    public void theCheckMeasuresTeleportsAsLawTenthsAndWater()
    {
        Engine engine = engine();
        ChargeOutcomeTest.none(observe(engine, "The amulet has 100 charges.", 1_000L));
        ChargeDelta delta = observe(engine, "The amulet has 90 charges.", 2_000L);
        assertNotNull(delta);
        // Ten charges spent; the stock is the count times one use's recipe.
        assertEquals(-10L, quantityDelta(delta, LAW));
        assertEquals(-100L, quantityDelta(delta, WATER));
        assertEquals(10, ChargeCheck.unitsPerItem(ChargeCheck.ChargedItem.SAILORS_AMULET, LAW));
    }

    @Test
    public void aDestinationClickBooksOneChargeShareFromTheWornAmulet()
    {
        Harness h = new Harness(
            container(EquipmentInventorySlot.AMULET.getSlotIdx(), AMULET), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP_LOW_PRIORITY, "the pandemonium",
                ChargeLoadAnyItemTest.widget(AMULET, InterfaceID.Wornitems.UNIVERSE, -1)),
            "the pandemonium", "");
        List<Transaction> rows = h.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(1L, rows.get(0).quantity(LAW, false));
        assertEquals(10L, rows.get(0).quantity(WATER, false));
        assertEquals(ActionKind.SUPPLIES, rows.get(0).getActionKind());
        assertEquals("one charge: a tenth of a law plus a water", 14L,
            h.engine.getMetrics(T0 + 1_000L).costs);
    }

    @Test
    public void aDestinationClickBooksFromThePackAmulet()
    {
        Harness h = new Harness(emptyContainer(), container(0, AMULET));
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "deepfin point",
                ChargeLoadAnyItemTest.widget(AMULET, InterfaceID.Wornitems.UNIVERSE, -1)),
            "deepfin point", "");
        List<Transaction> rows = h.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(1L, rows.get(0).quantity(LAW, false));
    }

    @Test
    public void withoutTheAmuletNothingBooks()
    {
        Harness h = new Harness(emptyContainer(), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "port roberts",
                ChargeLoadAnyItemTest.widget(AMULET, InterfaceID.Wornitems.UNIVERSE, -1)),
            "port roberts", "");
        assertEquals(0, h.engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void aDestinationOptionThatNamesNoAmuletNeverBooks()
    {
        // A port travel menu that happens to offer "deepfin point" must not spend the carried
        // amulet's charge (owner 2026-10-14).
        Harness h = new Harness(
            container(EquipmentInventorySlot.AMULET.getSlotIdx(), AMULET), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "deepfin point",
                ChargeLoadAnyItemTest.widget(0, InterfaceID.Wornitems.UNIVERSE, -1)),
            "deepfin point", "");
        assertEquals(0, h.engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void aWornMinimenuDestinationTargetingTheAmuletBooks()
    {
        // The worn amulet's own menu names it on the target, with no item id (Red Rock included).
        Harness h = new Harness(
            container(EquipmentInventorySlot.AMULET.getSlotIdx(), AMULET), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP_LOW_PRIORITY, "red rock",
                ChargeLoadAnyItemTest.widget(-1, InterfaceID.Wornitems.UNIVERSE, -1)),
            "red rock", "sailors' amulet");
        List<Transaction> rows = h.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(1L, rows.get(0).quantity(LAW, false));
    }

    @Test
    public void tenTeleportsBookExactlyOneLawAndTenWaters()
    {
        Harness h = new Harness(
            container(EquipmentInventorySlot.AMULET.getSlotIdx(), AMULET), emptyContainer());
        String[] destinations =
            {"the pandemonium", "port roberts", "red rock", "deepfin point"};
        for (int i = 0; i < 10; i++)
        {
            String option = destinations[i % destinations.length];
            h.intake.onMenuOptionClicked(
                ChargeLoadAnyItemTest.click(MenuAction.CC_OP, option,
                    ChargeLoadAnyItemTest.widget(AMULET, InterfaceID.Wornitems.UNIVERSE, -1)),
                option, "");
        }
        assertEquals("ten charges cost exactly one law and ten waters", 140L,
            h.engine.getMetrics(T0 + 1_000L).costs);
    }

    @Test
    public void aLoadNeverReadsAsSpendAtTheNextCheck()
    {
        Engine engine = new Engine(SailorsAmuletChargeTest::flows, new TransactionClassifier(), SETTLE_CONFIG);
        long start = 10_000L;
        engine.ensureSession(start);
        engine.setBaseline(new ContainerSnapshot(mapOf(LAW, 500L, WATER, 500L)));
        String identity = identity();

        ChargeCheck read = ChargeCheck.parse("The amulet has 100 charges.");
        assertNotNull(read);
        ChargeOutcomeTest.none(engine.observeCheck(read, identity, start));

        // Two law runes load twenty charges; the next Check must read them back as loaded, never
        // as twenty charges of spend (the other rune's stock rises with them, so the read is a
        // LOAD).
        assertTrue(engine.markChargeLoad(ChargeCheck.ChargedItem.SAILORS_AMULET, LAW, "Law rune",
            identity, ChargeIntake.LOAD_TICKS));
        engine.markDirty();
        Transaction lawLoad = settle(engine, new ContainerSnapshot(mapOf(LAW, 498L, WATER, 500L)), start + 600L);
        assertEquals(TransactionType.TRANSFER, lawLoad.getType());
        ChargeDelta loadedLaw = observe(engine, "The amulet has 120 charges.", start + 3_000L);
        assertNotNull(loadedLaw);
        ChargeOutcomeTest.outcome(ChargeDelta.CheckResult.LOAD, loadedLaw);

        // Twenty water runes load twenty charges the same way.
        assertTrue(engine.markChargeLoad(ChargeCheck.ChargedItem.SAILORS_AMULET, WATER, "Water rune",
            identity, ChargeIntake.LOAD_TICKS));
        engine.markDirty();
        Transaction waterLoad = settle(engine, new ContainerSnapshot(mapOf(LAW, 498L, WATER, 480L)), start + 6_600L);
        assertEquals(TransactionType.TRANSFER, waterLoad.getType());
        ChargeDelta loadedWater = observe(engine, "The amulet has 140 charges.", start + 9_000L);
        assertNotNull(loadedWater);
        ChargeOutcomeTest.outcome(ChargeDelta.CheckResult.LOAD, loadedWater);
    }

    @Test
    public void theAmuletTeleportDialogBooksToo()
    {
        // Owner live 2026-10-14: the amulet's own dialog ("Where would you like to teleport
        // to?") lists each destination as a sentence and its click carries no item id.
        Harness h = new Harness(
            container(EquipmentInventorySlot.AMULET.getSlotIdx(), AMULET), emptyContainer());
        h.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "the pandemonium.",
                ChargeLoadAnyItemTest.widget(-1, InterfaceID.Chatmenu.OPTIONS, -1)),
            "the pandemonium.", "");
        List<Transaction> rows = h.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(1L, rows.get(0).quantity(LAW, false));
        assertEquals(10L, rows.get(0).quantity(WATER, false));

        Harness none = new Harness(emptyContainer(), emptyContainer());
        none.intake.onMenuOptionClicked(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "deepfin point.",
                ChargeLoadAnyItemTest.widget(-1, InterfaceID.Chatmenu.OPTIONS, -1)),
            "deepfin point.", "");
        assertEquals("no amulet, nothing to spend", 0,
            none.engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void everyDestinationNameIsATeleport()
    {
        assertTrue(ChargeIntake.isAmuletLocation("the pandemonium"));
        assertTrue(ChargeIntake.isAmuletLocation("port roberts"));
        assertTrue(ChargeIntake.isAmuletLocation("red rock"));
        assertTrue(ChargeIntake.isAmuletLocation("deepfin point"));
        assertTrue("the dialog's sentence form", ChargeIntake.isAmuletLocation("the pandemonium."));
        assertTrue(ChargeIntake.isAmuletLocation("deepfin point."));
        assertFalse("checking is not a teleport", ChargeIntake.isAmuletLocation("check"));
        assertFalse(ChargeIntake.isAmuletLocation("withdraw-1"));
        assertFalse(ChargeIntake.isAmuletLocation(""));
        assertFalse(ChargeIntake.isAmuletLocation(null));
    }

    @Test
    public void chargingTheAmuletLoadsItsRunesNeutrally()
    {
        Engine engine = new Engine(SailorsAmuletChargeTest::flows, new TransactionClassifier(), SETTLE_CONFIG);
        long start = 10_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(LAW, 500L));

        Widget law = item(LAW, 5);
        ChargeIntake intake = new ChargeIntake(selecting(law), null, SETTLE_CONFIG, engine, null)
        {
            @Override
            String itemName(int itemId)
            {
                return itemId == LAW ? "Law rune" : null;
            }
        };
        intake.armLoad(
            ChargeLoadAnyItemTest.click(MenuAction.WIDGET_TARGET_ON_WIDGET, "use", item(AMULET, 3)),
            "use");
        assertEquals(ChargeCheck.ChargedItem.SAILORS_AMULET, engine.inbox.peek(Inbox.LOAD_TARGET).variant);
        assertEquals(LAW, engine.inbox.item(Inbox.LOAD_TARGET));

        engine.markDirty();
        Transaction load = settle(engine, snapshot(LAW, 400L), start + 600L);
        assertEquals("a load is a neutral transfer", TransactionType.TRANSFER, load.getType());
        assertFalse(load.isCounted());
        assertEquals(0L, engine.getMetrics(start + 2_400L).net);
    }

    private static ChargeDelta observe(Engine engine, String chat, long now)
    {
        ChargeCheck read = ChargeCheck.parse(chat);
        assertNotNull(chat, read);
        return engine.observeCheck(read, identity(), now);
    }

    /** The production identity shape, so the Check link and a drift in it cannot pass unseen. */
    private static String identity()
    {
        return ChargeIntake.weaponIdentity(InterfaceID.Inventory.ITEMS, 5, AMULET,
            ChargeCheck.ChargedItem.SAILORS_AMULET);
    }

    private static long quantityDelta(ChargeDelta delta, int itemId)
    {
        for (ChargeDelta.ComponentDelta component : delta.getComponentDeltas())
        {
            if (component.getItemId() == itemId) return component.getQuantityDelta();
        }
        return 0L;
    }

    private static Engine engine()
    {
        return new Engine(SailorsAmuletChargeTest::flows, new TransactionClassifier(), CONFIG);
    }

    private static List<Flow> flows(Map<Integer, Long> deltas)
    {
        List<Flow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            flows.add(new Flow(entry.getKey(), "Charge", entry.getValue(), 1, entry.getValue()));
        }
        return flows;
    }

    private static ContainerSnapshot snapshot(int id, long quantity)
    {
        return new ContainerSnapshot(mapOf(id, quantity));
    }


    private static Transaction settle(Engine engine, ContainerSnapshot next, long firstTick)
    {
        assertNull(engine.processIfDirty(next, firstTick, null));
        assertNull(engine.processIfDirty(next, firstTick + 600L, null));
        return engine.processIfDirty(next, firstTick + 1_200L, null);
    }

    private static final class Harness
    {
        final Engine engine;
        final ChargeIntake intake;

        Harness(ItemContainer worn, ItemContainer inventory)
        {
            Valuation valuation = ModelProbe.valuation(CONFIG, SailorsAmuletChargeTest::compositionOf,
                id -> id == LAW ? 100 : id == WATER ? 4 : 0);
            engine = new Engine(SailorsAmuletChargeTest::flows, new TransactionClassifier(), CONFIG);
            engine.startCustom("Sailors", T0);
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
        String name = id == LAW ? "Law rune" : id == WATER ? "Water rune" : "Item " + id;
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

    private static Client selecting(Widget selected)
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) ->
            {
                if ("getSelectedWidget".equals(method.getName())) return selected;
                if ("getTickCount".equals(method.getName())) return 10;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    private static Widget item(int itemId, int slot)
    {
        return ChargeLoadAnyItemTest.widget(itemId, InterfaceID.Inventory.ITEMS, slot);
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
}
