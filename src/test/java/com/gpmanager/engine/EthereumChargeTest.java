package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ItemComposition;
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The bracelet of ethereum: revenant ether loads as a committed Supplies spend (an uncharge
 * destroys the stored ether), the charged bracelet prices from its uncharged form, and its
 * dismantle is not the neutral parts rule (the ether is worth more than the item) (OSRS Wiki,
 * Muse review, owner 2026-10-12).
 */
public class EthereumChargeTest
{
    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    private static final String BRACELET = "149:0:9764864:21816:ETHEREUM";
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override public int stabilizationTicks() { return 2; }
        @Override public boolean keepTransferAuditRows() { return true; }
    };

    @Test
    public void theChargedBraceletPricesFromItsUnchargedQuote()
    {
        // RuneLite's ItemMapping carries no bracelet entry; the charged form prices from the
        // uncharged form's live quote (Muse review 2026-10-12).
        Valuation valuation = ModelProbe.valuation(new GpManagerConfig() { }, EthereumChargeTest::composition,
            id -> id == ItemID.WILD_CAVE_BRACELET_UNCHARGED ? 40_000 : 0);
        Flow bracelet = valuation.valueComponent(ItemID.WILD_CAVE_BRACELET_CHARGED, 1L, 0L);
        assertEquals(40_000, bracelet.unitPrice);
        assertSame(PriceSource.GRAND_EXCHANGE, bracelet.getPriceSource());
        assertEquals("the uncharged keeps its own quote",
            40_000, valuation.valueComponent(ItemID.WILD_CAVE_BRACELET_UNCHARGED, 1L, 0L).unitPrice);
    }

    @Test
    public void committedEtherLoadsBookTheirEtherAsSupplies()
    {
        Engine engine = engine();
        long start = 100_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(ItemID.WILD_CAVE_SHARD, 30L));

        assertTrue(engine.markChargeLoad(ChargeCheck.ChargedItem.ETHEREUM, ItemID.WILD_CAVE_SHARD,
            "Revenant ether", BRACELET, 8));
        engine.markDirty();
        Transaction load = settle(engine, snapshot(ItemID.WILD_CAVE_SHARD, 25L), start + 600L);

        assertEquals(TransactionType.CONSUMPTION, load.autoType());
        assertTrue(load.isCounted());
        assertEquals(GameData.msg("committed-load") + "Bracelet of ethereum", load.getNote());
        assertEquals(ActionKind.SUPPLIES, load.getActionKind());
        assertEquals(-1_250L, load.getNet());
        assertEquals(1_250L, engine.getMetrics(start + 2_400L).costs);
    }

    @Test
    public void anUnchargeBooksNothing()
    {
        Engine engine = engine();
        long start = 120_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(ItemID.WILD_CAVE_BRACELET_CHARGED, 1L));

        ChargeIntake intent = new ChargeIntake(selecting(null), null, CONFIG, engine, null);
        intent.armCheck(click(MenuAction.CC_OP, "Uncharge", item(ItemID.WILD_CAVE_BRACELET_CHARGED, 0)),
            "uncharge", "Bracelet of ethereum");
        assertNotNull("the uncharge watch arms", engine.inbox.peek(Inbox.UNCHARGE));
        engine.markDirty();
        settle(engine, snapshot(ItemID.WILD_CAVE_BRACELET_UNCHARGED, 1L), start + 600L);

        assertTrue("the ether was booked at load; the uncharge adds nothing",
            engine.getActiveSession().getTransactions().stream().noneMatch(Transaction::isCounted));
        assertEquals(0L, engine.getMetrics(start + 2_400L).net);
    }

    @Test
    public void theBankConfigureChargesRouteNeverPhantomBooks()
    {
        Engine engine = engine();
        long start = 140_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(ItemID.WILD_CAVE_SHARD, 30L));

        ChargeIntake intent = new ChargeIntake(selecting(null), null, CONFIG, engine, null);
        intent.armCheck(click(MenuAction.CC_OP, "Configure-Charges", item(ItemID.WILD_CAVE_BRACELET_CHARGED, 0)),
            "configure-charges", "Bracelet of ethereum");
        assertNull("no load arms without a use-on click", engine.inbox.peek(Inbox.LOAD_TARGET));

        engine.markDirty();
        settle(engine, snapshot(ItemID.WILD_CAVE_SHARD, 25L), start + 600L);
        for (Transaction row : engine.getActiveSession().getTransactions())
        {
            assertFalse("a bank load cannot phantom-book as a charge load",
                row.getNote() != null && row.getNote().startsWith(GameData.msg("committed-load")));
        }
    }

    @Test
    public void alchemyBooksTheBraceletAtItsBaseQuoteOnce()
    {
        // The observed cast: the charged bracelet leaves and the coins arrive under consume
        // intent (the ConsumptionBurialEngineTest alchemy pattern).
        Engine engine = engine();
        long start = 160_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(ItemID.WILD_CAVE_BRACELET_CHARGED, 1L));
        engine.noteConsume(ItemID.WILD_CAVE_BRACELET_CHARGED, 8, false);
        engine.markDirty();

        Transaction alchemy = settle(engine, snapshot(995, 45_000L), start + 600L);

        assertNotNull(alchemy);
        assertTrue(alchemy.isCounted());
        long braceletLeg = alchemy.getFlows().stream()
            .filter(flow -> flow.itemId == ItemID.WILD_CAVE_BRACELET_CHARGED)
            .mapToLong(flow -> flow.valueDelta).sum();
        assertEquals("the bracelet leaves at its uncharged form's quote", -40_000L, braceletLeg);
        assertEquals(5_000L, alchemy.getNet());
    }

    @Test
    public void theChargeToAlchFlowNetsTheGuideMargin()
    {
        Engine engine = engine();
        long start = 200_000L;
        engine.ensureSession(start);

        // Buy the bracelet and one ether (both net-neutral), charge (the committed load), then
        // alch: the counted lifetime net is the guide's margin.
        engine.setBaseline(snapshot(995, 100_000L));
        engine.markDirty();
        settle(engine, snapshot(mapOf(995, 60_000L, ItemID.WILD_CAVE_BRACELET_UNCHARGED, 1L)), start + 600L);

        engine.markDirty();
        settle(engine, snapshot(mapOf(995, 59_750L, ItemID.WILD_CAVE_BRACELET_UNCHARGED, 1L,
            ItemID.WILD_CAVE_SHARD, 1L)), start + 1_400L);

        engine.markChargeLoad(ChargeCheck.ChargedItem.ETHEREUM, ItemID.WILD_CAVE_SHARD,
            "Revenant ether", BRACELET, 8);
        engine.markDirty();
        settle(engine, snapshot(mapOf(995, 59_750L, ItemID.WILD_CAVE_BRACELET_CHARGED, 1L)), start + 2_200L);

        engine.noteConsume(ItemID.WILD_CAVE_BRACELET_CHARGED, 8, false);
        engine.markDirty();
        settle(engine, snapshot(995, 104_750L), start + 3_000L);

        assertEquals("45,000 - 40,000 - 250", 4_750L, engine.getMetrics(start + 5_000L).net);
    }

    @Test
    public void aDeathWipeAddsNoEtherDoubleCount()
    {
        Engine engine = engine();
        long start = 240_000L;
        engine.ensureSession(start);
        ContainerSnapshot carried = snapshot(ItemID.WILD_CAVE_BRACELET_CHARGED, 1L);
        engine.setBaseline(carried);
        engine.markDirty();
        for (int tick = 1; tick <= 3; tick++)
        {
            engine.processIfDirty(carried, start + tick * 600L, null);
        }

        engine.markPvmDeath(1, carried.quantities);
        engine.markDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), start + 3_000L);

        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.getType());
        assertFalse(wipe.isCounted());
        assertEquals(0L, engine.getMetrics(start + 6_000L).net);
        for (Transaction row : engine.getActiveSession().getTransactions())
        {
            assertFalse(row.getNote() != null && row.getNote().startsWith(GameData.msg("committed-load")));
        }
    }

    private static ItemComposition composition(int id)
    {
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
            new Class<?>[] {ItemComposition.class}, (proxy, method, args) ->
            {
                if ("getName".equals(method.getName())) return "Bracelet of ethereum";
                if ("getHaPrice".equals(method.getName())) return 45_000;
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                return null;
            });
    }

    private static Engine engine()
    {
        return new Engine(EthereumChargeTest::flows, new TransactionClassifier(), CONFIG);
    }

    private static List<Flow> flows(Map<Integer, Long> deltas)
    {
        List<Flow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int price = priceOf(entry.getKey());
            flows.add(new Flow(entry.getKey(), nameOf(entry.getKey()), entry.getValue(), price,
                entry.getValue() * price));
        }
        return flows;
    }

    private static int priceOf(int itemId)
    {
        if (itemId == ItemID.COINS) return 1;
        if (itemId == ItemID.WILD_CAVE_SHARD) return 250;
        if (itemId == ItemID.WILD_CAVE_BRACELET_UNCHARGED
                || itemId == ItemID.WILD_CAVE_BRACELET_CHARGED) return 40_000;
        return 0;
    }

    private static String nameOf(int itemId)
    {
        if (itemId == ItemID.COINS) return "Coins";
        if (itemId == ItemID.WILD_CAVE_SHARD) return "Revenant ether";
        return "Bracelet of ethereum";
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

    private static net.runelite.api.Client selecting(Widget selected)
    {
        return (net.runelite.api.Client) Proxy.newProxyInstance(
            net.runelite.api.Client.class.getClassLoader(), new Class<?>[] {net.runelite.api.Client.class},
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
