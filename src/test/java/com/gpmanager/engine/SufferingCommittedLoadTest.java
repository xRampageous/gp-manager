package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
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
 * The ring of suffering's recoils are committed: a discharge never refunds them, so a load books
 * one counted Supplies row at the recoil's price (owner 2026-10-12). The ring keeps no Check
 * comparison; the effect toggle, an uncharge and the bank's Configure-Charges book nothing.
 */
public class SufferingCommittedLoadTest
{
    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    private static final String RING = "149:0:9764864:20657:SUFFERING";
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override public int stabilizationTicks() { return 2; }
        @Override public boolean keepTransferAuditRows() { return true; }
    };

    @Test
    public void committedRecoilLoadsBookTheirRecoilsAsSupplies()
    {
        Engine engine = engine();
        long start = 100_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(ItemID.RING_OF_RECOIL, 30L));

        assertTrue(engine.markChargeLoad(ChargeCheck.ChargedItem.SUFFERING, ItemID.RING_OF_RECOIL,
            "Ring of recoil", RING, 8));
        engine.markDirty();
        Transaction load = settle(engine, snapshot(ItemID.RING_OF_RECOIL, 25L), start + 600L);

        assertEquals(TransactionType.CONSUMPTION, load.original());
        assertTrue(load.counts());
        assertEquals(GameData.msg("committed-load") + "Ring of suffering", load.note());
        assertEquals(ActionKind.SUPPLIES, load.action());
        assertEquals(-2_500L, load.net());
        assertEquals(2_500L, engine.metrics(start + 2_400L).cost);
    }

    @Test
    public void theRingsCheckLineBooksNothing()
    {
        Engine engine = engine();
        long start = 120_000L;
        engine.ensureSession(start);

        // The exact live Check line (owner recording 2026-10-12): a committed load has nothing
        // to compare, so the read never books.
        new ChargeIntake(null, null, CONFIG, engine, null).observeCheck(
            "Your ring currently has 5,097 recoil charges remaining. The recoil effect is currently disabled.",
            start);

        assertTrue(engine.session().transactions().isEmpty());
        assertEquals(0L, engine.metrics(start).net);
    }

    @Test
    public void anUnchargeBooksNothing()
    {
        Engine engine = engine();
        long start = 140_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(ItemID.NZONE_ZENYTE_RING_ENCHANTED_RECOIL, 1L));

        ChargeIntake intent = new ChargeIntake(selecting(null), null, CONFIG, engine, null);
        intent.armCheck(click(MenuAction.CC_OP, "Uncharge", item(ItemID.NZONE_ZENYTE_RING_ENCHANTED_RECOIL, 0)),
            "uncharge", "Ring of suffering (ri)");
        assertNotNull("the uncharge watch arms", engine.events.peek(Inbox.UNCHARGE));
        engine.markDirty();
        settle(engine, snapshot(ItemID.ZENYTE_RING_ENCHANTED, 1L), start + 600L);

        assertTrue("the charge was booked at load; the uncharge adds nothing",
            engine.session().transactions().stream().noneMatch(Transaction::counts));
        assertEquals(0L, engine.metrics(start + 2_400L).net);
    }

    @Test
    public void theBankConfigureChargesRouteNeverPhantomBooks()
    {
        Engine engine = engine();
        long start = 160_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(ItemID.RING_OF_RECOIL, 30L));

        ChargeIntake intent = new ChargeIntake(selecting(null), null, CONFIG, engine, null);
        intent.armCheck(click(MenuAction.CC_OP, "Configure-Charges", item(ItemID.NZONE_ZENYTE_RING_ENCHANTED_RECOIL, 0)),
            "configure-charges", "Ring of suffering (ri)");
        assertNull("no load arms without a use-on click", engine.events.peek(Inbox.LOAD_TARGET));

        // Bank-side charges are a documented blind spot: a recoil move without the use click is
        // never a charge load, and the recoils still book as the ordinary spend they are.
        engine.markDirty();
        settle(engine, snapshot(ItemID.RING_OF_RECOIL, 25L), start + 600L);
        for (Transaction row : engine.session().transactions())
        {
            assertFalse("a bank load cannot phantom-book as a charge load",
                row.note() != null && row.note().startsWith(GameData.msg("committed-load")));
        }
        assertTrue("the recoils still book ordinarily",
            engine.session().transactions().stream().anyMatch(Transaction::counts));
    }

    private static Engine engine()
    {
        return new Engine(SufferingCommittedLoadTest::flows, new TransactionClassifier(), CONFIG);
    }

    private static List<Flow> flows(Map<Integer, Long> deltas)
    {
        List<Flow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            flows.add(new Flow(entry.getKey(), "Ring of recoil", entry.getValue(), 500,
                entry.getValue() * 500));
        }
        return flows;
    }

    private static ContainerSnapshot snapshot(int id, long quantity)
    {
        return snapshot(mapOf(id, quantity));
    }

    private static ContainerSnapshot snapshot(Map<Integer, Long> quantities)
    {
        return new ContainerSnapshot(quantities);
    }


    private static Transaction settle(Engine engine, ContainerSnapshot next, long firstTick)
    {
        assertNull(engine.processIfDirty(next, firstTick, null));
        assertNull(engine.processIfDirty(next, firstTick + 600L, null));
        return engine.processIfDirty(next, firstTick + 1_200L, null);
    }

    private static Widget item(int itemId, int slot)
    {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[] {Widget.class},
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getItemId": return itemId;
                    case "getIndex": return slot;
                    case "getId": return InterfaceID.Inventory.ITEMS;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                }
            });
    }

    private static Client selecting(Widget selected)
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getSelectedWidget": return selected;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
    }

    private static MenuOptionClicked click(MenuAction action, String option, Widget clicked)
    {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getOption": return option;
                    case "getTarget": return "";
                    case "getType": return action;
                    case "getWidget": return clicked;
                    case "getItemId": return clicked == null ? -1 : clicked.getItemId();
                    case "getParam0": return clicked == null ? -1 : clicked.getIndex();
                    case "getParam1": return clicked == null ? -1 : clicked.getId();
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
        return new MenuOptionClicked(entry);
    }
}
