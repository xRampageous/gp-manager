package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Tonalztics of Ralos and the tomes of water and earth: their own components (sunfire
 * splinters, soaked and soiled pages) load neutrally and measure through Checks (OSRS Wiki
 * transcripts and pages, owner 2026-10-12).
 */
public class TonalzticsAndTomeChargeTest
{
    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    private static final String RALOS = "149:0:9764864:28922:TONALZTICS";
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override public int stabilizationTicks() { return 2; }
        @Override public boolean keepTransferAuditRows() { return true; }
    };

    @Test
    public void tonalzticsChecksMeasureSunfireSplinters()
    {
        Engine engine = engine();
        ChargeOutcomeTest.none(observe(engine, "Your Tonalztics of Ralos has 1,000 charges.", RALOS, 1_000L));
        ChargeDelta delta = observe(engine, "Your Tonalztics of Ralos has 998 charges.", RALOS, 2_000L);
        assertNotNull(delta);
        assertEquals(-2L, quantityDelta(delta, ItemID.SUNFIRESPLINTER));
        assertEquals(1, ChargeCheck.unitsPerItem(ChargeCheck.ChargedItem.TONALZTICS, ItemID.SUNFIRESPLINTER));
    }

    @Test
    public void theChargeOptionArmsAndBooksTheTonalzticsLoadNeutral()
    {
        Engine engine = engine();
        long start = 10_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(ItemID.SUNFIRESPLINTER, 500L));

        ChargeIntake intent = new ChargeIntake(selecting(null), null, CONFIG, engine, null)
        {
            @Override
            String itemName(int itemId)
            {
                return itemId == ItemID.SUNFIRESPLINTER ? "Sunfire splinter" : null;
            }
        };
        intent.armCheck(click(MenuAction.CC_OP, "Charge", item(ItemID.TONALZTICS_OF_RALOS_CHARGED, 0)),
            "charge", "Tonalztics of Ralos");
        assertEquals(ChargeCheck.ChargedItem.TONALZTICS, engine.inbox.peek(Inbox.LOAD_TARGET).variant);
        assertEquals(ItemID.SUNFIRESPLINTER, engine.inbox.item(Inbox.LOAD_TARGET));

        engine.markDirty();
        Transaction load = settle(engine, snapshot(ItemID.SUNFIRESPLINTER, 400L), start + 600L);
        assertEquals("a load is a neutral transfer", TransactionType.TRANSFER, load.getType());
        assertFalse(load.isCounted());
        assertEquals(0L, engine.getMetrics(start + 2_400L).net);
    }

    @Test
    public void waterTomeChecksMeasureSoakedPages()
    {
        Engine engine = engine();
        ChargeOutcomeTest.none(observe(engine,
            "Your tome has been charged with Soaked Pages. It currently holds 1,000 charges.",
            "149:5:0:25574:TW", 1_000L));
        ChargeDelta delta = observe(engine,
            "Your tome has been charged with Soaked Pages. It currently holds 960 charges.",
            "149:5:0:25574:TW", 2_000L);
        assertNotNull(delta);
        assertEquals(-40L, quantityDelta(delta, ItemID.SOAKED_PAGE));
        assertEquals(20, ChargeCheck.unitsPerItem(ChargeCheck.ChargedItem.TOME_OF_WATER, ItemID.SOAKED_PAGE));
    }

    @Test
    public void earthTomeChecksMeasureSoiledPages()
    {
        Engine engine = engine();
        ChargeOutcomeTest.none(observe(engine,
            "Your tome has been charged with Soiled Pages. It currently holds 1,000 charges.",
            "149:5:0:30064:TE", 1_000L));
        ChargeDelta delta = observe(engine,
            "Your tome has been charged with Soiled Pages. It currently holds 960 charges.",
            "149:5:0:30064:TE", 2_000L);
        assertNotNull(delta);
        assertEquals(-40L, quantityDelta(delta, ItemID.SOILED_PAGE));
    }

    @Test
    public void aBareTomeCheckMeasuresTheClickedTomesOwnPages()
    {
        Engine engine = engine();
        long start = 20_000L;
        engine.ensureSession(start);

        // The bare Check names no page; the clicked tome names the row and its own pages rebuild
        // the read (owner 2026-10-12).
        ChargeIntake intent = new ChargeIntake(ticking(101), null, CONFIG, engine, null);
        assertTrue(intent.startCheck(ChargeCheck.ChargedItem.TOME_OF_WATER, "149:5:0:25574:TW", 100, 2));
        assertTrue(intent.observeCheck("Your tome currently holds 1,000 charges.", start));

        ChargeDelta delta = engine.observeCheck(ChargeCheck.parse(
            "Your tome has been charged with Soaked Pages. It currently holds 960 charges."),
            "149:5:0:25574:TW", start + 1_000L);
        assertNotNull("the bare line seeded the water tome's own baseline", delta);
        assertEquals(-40L, quantityDelta(delta, ItemID.SOAKED_PAGE));
    }

    private static ChargeDelta observe(Engine engine, String chat, String target, long now)
    {
        ChargeCheck read = ChargeCheck.parse(chat);
        assertNotNull(chat, read);
        return engine.observeCheck(read, target, now);
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
        return new Engine(TonalzticsAndTomeChargeTest::flows, new TransactionClassifier(), CONFIG);
    }

    private static List<Flow> flows(Map<Integer, Long> deltas)
    {
        List<Flow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            flows.add(new Flow(entry.getKey(), "Charge", entry.getValue(), 500, entry.getValue() * 500));
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

    private static Map<Integer, Long> mapOf(Object... values)
    {
        Map<Integer, Long> result = new HashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2)
        {
            result.put((Integer) values[index], (Long) values[index + 1]);
        }
        return result;
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
                    case "getTickCount": return 101;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
    }

    private static Client ticking(int tick)
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) ->
            {
                if ("getTickCount".equals(method.getName())) return tick;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
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
