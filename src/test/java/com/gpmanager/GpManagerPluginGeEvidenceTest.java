package com.gpmanager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * C3a: the offer ledger is observation/evidence only. A passive offer transition (progress without
 * settlement) must not arm MARKET context for whatever inventory change settles next.
 */
public class GpManagerPluginGeEvidenceTest
{
    private static final int SHARK = 385;
    private static final int COINS = ItemID.COINS;
    private static final int AIR_RUNE = ItemID.AIRRUNE;
    private static final int NATURE_RUNE = ItemID.NATURERUNE;
    private static final int FIRE_RUNE = ItemID.FIRERUNE;
    private static final int ASTRAL_RUNE = ItemID.ASTRALRUNE;
    private static final int LAVA_RUNE = ItemID.LAVARUNE;
    private static final int STEAM_RUNE = ItemID.STEAMRUNE;
    private static final long T0 = 1_000_000_000_000L;

    @Test
    public void passiveOfferProgressDoesNotArmMarketContext() throws Exception
    {
        RecordingEngine engine = new RecordingEngine();
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        set(plugin, "engine", engine);
        set(plugin, "config", new GpManagerConfig() {});

        OfferLedger ledger = new OfferLedger();
        ledger.observe(new OfferLedger.Snapshot(0, GrandExchangeOfferState.BUYING, SHARK, 5, 0, 800, 0));
        OfferLedger.Transition progress = ledger.observe(
            new OfferLedger.Snapshot(0, GrandExchangeOfferState.BUYING, SHARK, 5, 2, 800, 1_600))
            .orElseThrow(() -> new AssertionError("expected a progress transition"));

        Method handler = GpManagerPlugin.class.getDeclaredMethod(
            "handleGeOfferTransition", OfferLedger.Transition.class);
        handler.setAccessible(true);
        handler.invoke(plugin, progress);

        assertTrue("passive progress must not arm MARKET", engine.marketArms.isEmpty());
    }

    @Test
    public void grandExchangeWidgetVisibilityDoesNotArmUnrelatedInventoryChanges() throws Exception
    {
        RecordingEngine engine = new RecordingEngine();
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        set(plugin, "engine", engine);
        set(plugin, "config", new GpManagerConfig() {});
        set(plugin, "client", geVisibleClient());

        plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, null));

        assertTrue("inventory callbacks must wait for offer evidence instead of UI visibility",
            engine.marketArms.isEmpty());
    }

    @Test
    public void depositMenuTextAloneDoesNotNeutralizeAnUnrelatedItem() throws Exception
    {
        RecordingEngine engine = new RecordingEngine();
        GpManagerPlugin plugin = plugin(engine, emptyClient());

        plugin.onMenuOptionClicked(menu("Deposit-1", "Shark"));

        assertTrue("menu vocabulary without a live bank context must not arm TRANSFER",
            engine.transferArms.isEmpty());
    }

    @Test
    public void liveBankContextKeepsDepositNeutral() throws Exception
    {
        RecordingEngine engine = new RecordingEngine();
        GpManagerPlugin plugin = plugin(engine, bankVisibleClient());

        plugin.onMenuOptionClicked(menu("Deposit-1", "Shark"));

        assertEquals(1, engine.transferArms.size());
    }

    @Test
    public void lawRuneWithdrawalStaysNeutralWhenBankClosesBeforeSettlement() throws Exception
    {
        for (String option : new String[] {"Withdraw-200", "Withdraw-All"})
        for (boolean observedBankTick : new boolean[] {true, false})
        {
            GpManagerConfig config = new GpManagerConfig()
            {
                @Override public int stabilizationTicks() { return 0; }
                @Override public boolean keepTransferAuditRows() { return true; }
            };
            Engine engine = new Engine(deltas ->
            {
                List<Flow> flows = new ArrayList<>();
                deltas.forEach((id, quantity) -> flows.add(new Flow(id, "Law rune", quantity,
                    122, quantity * 122L)));
                return flows;
            }, new TransactionClassifier(), config);
            engine.startCustomSession("Trading", SessionMode.AUTO, T0);
            engine.setBaseline(ContainerSnapshot.empty());
            GpManagerPlugin plugin = plugin(engine, bankVisibleClient());
            if (observedBankTick) engine.markBankInterfaceOpen(6);
            plugin.onMenuOptionClicked(menu(option, "Law rune"));

            // Close before inventory stabilization, without relying on a BANK callback.
            set(plugin, "client", emptyClient());
            plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, null));
            Transaction withdrawn = settleProduction(engine, inventory(ItemID.LAWRUNE, 200L), T0 + 600L);

            assertNotNull(withdrawn);
            assertEquals("a bank withdrawal is ownership movement", TransactionType.TRANSFER, withdrawn.getType());
            assertTrue("a withdrawal never counts as revenue", !withdrawn.isCounted());
            assertEquals(0L, engine.getMetrics(T0 + 1_800L).net);
            assertEquals(0L, engine.getMetrics(T0 + 1_800L).revenue);
        }
    }

    @Test
    public void withdrawMenuTextWithoutLiveBankDoesNotMaskAGain() throws Exception
    {
        RecordingEngine engine = new RecordingEngine();
        GpManagerPlugin plugin = plugin(engine, emptyClient());
        plugin.onMenuOptionClicked(menu("Withdraw-All", "Law rune"));
        assertTrue("a menu verb alone is not bank ownership evidence", engine.transferArms.isEmpty());
    }

    @Test
    public void cancelledWithdrawXPromptDoesNotHideTheNextHarvest() throws Exception
    {
        Engine engine = productionEngine();
        engine.startCustomSession("Gathering", SessionMode.AUTO, T0);
        engine.setBaseline(ContainerSnapshot.empty());
        GpManagerPlugin plugin = plugin(engine, bankVisibleClient());
        engine.markBankInterfaceOpen(6);
        plugin.onMenuOptionClicked(menu("Withdraw-X", "Law rune"));
        // No quantity was confirmed and no bank ownership movement occurred.
        set(plugin, "client", emptyClient());
        plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, null));
        Transaction harvested = settleProduction(engine, inventory(ItemID.LOGS, 1L), T0 + 600L);
        assertNotNull(harvested);
        assertEquals(TransactionType.GAIN, harvested.getType());
        assertEquals(200L, engine.getMetrics(T0 + 1_800L).net);
    }

    // ── production-faithful GE sell placement evidence ─────────────────────────────────────────

    @Test
    public void geSellOfferClickOwnsTheRuneLossAsNeutralCustody() throws Exception
    {
        Engine engine = productionEngine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, NATURE_RUNE, 5L)));
        GpManagerPlugin plugin = gePlugin(engine);

        // The player opens a GE sell offer and clicks the rune in the inventory ("Offer").
        plugin.onMenuOptionClicked(menuWithItem("Offer", "Nature rune", NATURE_RUNE));
        // The server confirms the placement: the slot reports SELLING.
        plugin.onGrandExchangeOfferChanged(offerChanged(0, GrandExchangeOfferState.SELLING, NATURE_RUNE, 5, 0, 200, 0));

        Transaction sale = settleProduction(engine, inventory(COINS, 100_000L, NATURE_RUNE, 4L), now);
        assertNotNull("the rune loss settles", sale);
        assertEquals("a proven GE sale placement is ownership-neutral custody",
            TransactionType.TRANSFER, sale.getType());
        assertEquals(Context.TRANSFER, sale.getContext());
        assertTrue("custody never counts money", !sale.isCounted());
        assertEquals("pending custody never changes Net", 0L, engine.getMetrics(now + 1_800L).net);
        assertEquals(1, engine.getMarketSettlements().size());
    }

    @Test
    public void sequentialGeSellOfferClicksAllSettleAsNeutralCustody() throws Exception
    {
        Engine engine = productionEngine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> inventory = inventory(COINS, 100_000L);
        int[] runes = {AIR_RUNE, NATURE_RUNE, FIRE_RUNE, ASTRAL_RUNE, LAVA_RUNE, STEAM_RUNE};
        String[] names = {"Air rune", "Nature rune", "Fire rune", "Astral rune", "Lava rune", "Steam rune"};
        for (int rune : runes)
        {
            inventory.put(rune, 5L);
        }
        engine.setBaseline(new ContainerSnapshot(inventory));
        GpManagerPlugin plugin = gePlugin(engine);

        for (int i = 0; i < runes.length; i++)
        {
            int rune = runes[i];
            plugin.onMenuOptionClicked(menuWithItem("Offer", names[i], rune));
            plugin.onGrandExchangeOfferChanged(offerChanged(i, GrandExchangeOfferState.SELLING, rune, 5, 0, 200, 0));
            inventory.put(rune, inventory.get(rune) - 1L);
            Transaction sale = settleProduction(engine, inventory, now + i * 10_000L);
            assertNotNull("sale settles: " + names[i], sale);
            assertEquals("every genuine sell placement is custody: " + names[i],
                TransactionType.TRANSFER, sale.getType());
            assertEquals("custody is never counted: " + names[i], false, sale.isCounted());
        }
        assertEquals("no placement fell through to a counted cost",
            0L, engine.getMetrics(now).net);
        assertEquals(runes.length, engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void offerClickWithoutTheGrandExchangeKeepsTheConsumptionIntent() throws Exception
    {
        RecordingEngine engine = new RecordingEngine();
        GpManagerPlugin plugin = plugin(engine, emptyClient());
        set(plugin, "activity", new ActivityDetector(new GpManagerConfig() {}, engine));

        plugin.onMenuOptionClicked(menuWithItem("Offer", "Dragon bones", 536));

        assertTrue("no GE UI: an Offer click must not arm MARKET", engine.marketArms.isEmpty());
        assertTrue("no GE UI: the altar/bones spend intent is preserved",
            engine.consumptionArms.contains(536));
    }

    private static Engine productionEngine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 2; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new Engine(deltas ->
        {
            java.util.List<Flow> flows = new java.util.ArrayList<>();
            for (java.util.Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                flows.add(new Flow(id, runeName(id), delta.getValue(), 200, delta.getValue() * 200));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static GpManagerPlugin gePlugin(Engine engine) throws Exception
    {
        GpManagerPlugin plugin = plugin(engine, geVisibleClient());
        set(plugin, "activity", new ActivityDetector(new GpManagerConfig() {}, engine));
        set(plugin, "geOfferLedger", new OfferLedger());
        return plugin;
    }

    private static Transaction settleProduction(Engine engine, Map<Integer, Long> next, long now)
    {
        engine.markInventoryDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(next);
        Transaction result = null;
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(snapshot, now + i * 600L);
            if (settled != null)
            {
                result = settled;
            }
        }
        return result;
    }

    private static GrandExchangeOfferChanged offerChanged(int slot, GrandExchangeOfferState state, int itemId,
        int totalQuantity, int quantitySold, int price, int spent)
    {
        GrandExchangeOffer offer = (GrandExchangeOffer) Proxy.newProxyInstance(
            GrandExchangeOffer.class.getClassLoader(), new Class<?>[] {GrandExchangeOffer.class},
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getState": return state;
                    case "getItemId": return itemId;
                    case "getTotalQuantity": return totalQuantity;
                    case "getQuantitySold": return quantitySold;
                    case "getPrice": return (long) price;
                    case "getSpent": return (long) spent;
                    default: return null;
                }
            });
        GrandExchangeOfferChanged event = new GrandExchangeOfferChanged();
        event.setOffer(offer);
        event.setSlot(slot);
        return event;
    }

    private static MenuOptionClicked menuWithItem(String option, String target, int itemId)
    {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
            {
                if ("getOption".equals(method.getName())) return option;
                if ("getTarget".equals(method.getName())) return target;
                if ("getItemId".equals(method.getName())) return itemId;
                if ("getType".equals(method.getName())) return MenuAction.ITEM_FIRST_OPTION;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
        return new MenuOptionClicked(entry);
    }

    private static Map<Integer, Long> inventory(Object... pairs)
    {
        Map<Integer, Long> map = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((Integer) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }

    private static String runeName(int id)
    {
        if (id == COINS) return "Coins";
        if (id == AIR_RUNE) return "Air rune";
        if (id == NATURE_RUNE) return "Nature rune";
        if (id == FIRE_RUNE) return "Fire rune";
        if (id == ASTRAL_RUNE) return "Astral rune";
        if (id == LAVA_RUNE) return "Lava rune";
        if (id == STEAM_RUNE) return "Steam rune";
        return "Item " + id;
    }

    private static GpManagerPlugin plugin(Engine engine, Client client) throws Exception
    {
        GpManagerConfig config = new GpManagerConfig() {};
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        set(plugin, "engine", engine);
        set(plugin, "config", config);
        set(plugin, "client", client);
        set(plugin, "interactionContextTracker",
            GpManagerPluginProbe.tracker(client, engine, config));
        set(plugin, "charges", new ChargeIntake(client, null, config, engine, null));
        return plugin;
    }

    private static MenuOptionClicked menu(String option, String target)
    {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
            {
                if ("getOption".equals(method.getName())) return option;
                if ("getTarget".equals(method.getName())) return target;
                if ("getType".equals(method.getName())) return MenuAction.ITEM_FIRST_OPTION;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
        return new MenuOptionClicked(entry);
    }

    private static Client emptyClient()
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[] {Client.class}, (proxy, method, args) ->
            {
                if ("getWidget".equals(method.getName())) return null;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    private static Client bankVisibleClient()
    {
        Widget visible = widget(false);
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[] {Client.class}, (proxy, method, args) ->
            {
                if ("getWidget".equals(method.getName()) && args != null && args.length == 1
                    && Integer.valueOf(InterfaceID.Bankmain.ITEMS_CONTAINER).equals(args[0]))
                {
                    return visible;
                }
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    private static Client geVisibleClient()
    {
        Widget visible = widget(false);
        Widget hidden = widget(true);
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[] {Client.class}, (proxy, method, args) ->
            {
                if ("getWidget".equals(method.getName()))
                {
                    boolean geRoot = args != null && args.length == 1
                        && Integer.valueOf(InterfaceID.GeOffers.UNIVERSE).equals(args[0]);
                    return geRoot ? visible : hidden;
                }
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    private static Widget widget(boolean hidden)
    {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
            new Class<?>[] {Widget.class}, (proxy, method, args) ->
            {
                if ("isHidden".equals(method.getName())) return hidden;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    static final class RecordingEngine extends Engine
    {
        final List<String> marketArms = new ArrayList<>();
        final List<String> transferArms = new ArrayList<>();
        final List<Integer> consumptionArms = new ArrayList<>();

        RecordingEngine()
        {
            super(deltas -> Collections.<Flow>emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        }

        @Override
        public synchronized void markContext(Context newContext, int ticks, String note)
        {
            if (newContext == Context.MARKET)
            {
                marketArms.add(note == null ? "" : note);
            }
            if (newContext == Context.TRANSFER)
            {
                transferArms.add(note == null ? "" : note);
            }
            super.markContext(newContext, ticks, note);
        }

        @Override
        public synchronized void noteConsumptionIntent(int itemId, int ticks, boolean destroy,
            @javax.annotation.Nullable ActionKind actionKind)
        {
            consumptionArms.add(itemId);
            super.noteConsumptionIntent(itemId, ticks, destroy, actionKind);
        }

        @Override
        public synchronized void noteConsumptionIntent(int itemId, int ticks, boolean destroy,
            @javax.annotation.Nullable ActionKind actionKind,
            @javax.annotation.Nullable ActionLabel actionLabel)
        {
            consumptionArms.add(itemId);
            super.noteConsumptionIntent(itemId, ticks, destroy, actionKind, actionLabel);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = GpManagerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
