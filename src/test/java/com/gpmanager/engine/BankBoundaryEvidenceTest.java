package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import static org.junit.Assert.assertEquals;

/**
 * Owner 1.7.0 bank boundary through the plugin's own callbacks: the BANK container's counts are the
 * only bank movement. Every item is quoted at 10 GP; counted logs carry 10 GP of basis each.
 */
public class BankBoundaryEvidenceTest
{
    private static final int LOGS = 1511, AIR = 556, BOLTS = 9140;
    private static final Map<Integer, Long> PRICES = Map.of(995, 1L, 1515, 94L, 66, 155L, 13441, 1459L,
            BOLTS, 10L);
    private static final Map<Integer, String> NAMES = Map.of(LOGS, "Logs", AIR, "Air rune", 995, "Coins",
            1515, "Yew logs", 66, "Yew longbow (u)", 13441, "Anglerfish", BOLTS, "Iron bolts");
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override public int stabilizationTicks() { return 2; }
        @Override public boolean keepTransferAuditRows() { return true; }
    };

    private long now = 1_000;
    private java.util.function.LongSupplier previousClock;
    @Before public void fixedClock() {
        previousClock = SafeMath.clock;
        SafeMath.clock = () -> now;
    }
    @After public void restoreClock() { SafeMath.clock = previousClock; }
    private boolean bankShown;
    private Engine e;
    private GpManagerPlugin plugin;
    private final List<Transaction> published = new ArrayList<>();

    private void start(long logs, long air) throws Exception
    {
        e = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> {
                long unit = PRICES.getOrDefault(id, 10L);
                flows.add(new Flow(id, NAMES.get(id), quantity, unit,
                        quantity * unit, id == 995 ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE));
            });
            return flows;
        }, new TransactionClassifier(), CONFIG);
        e.ensureSession(now);
        e.setBaseline(held(logs, air));
        e.session().addTransaction(Tx.of(now, TransactionType.GAIN, Context.GENERIC, "Initial loot", true,
            List.of(new Flow(LOGS, "Logs", logs, 10, logs * 10, PriceSource.GRAND_EXCHANGE))), 2_000, false);
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) ->
            {
                if ("getGameState".equals(method.getName())) return GameState.LOGGED_IN;
                // The bank's item panel is visible exactly while the bank is open.
                if ("getWidget".equals(method.getName()) && bankShown && args != null && args.length == 1
                    && Integer.valueOf(InterfaceID.Bankmain.ITEMS_CONTAINER).equals(args[0]))
                    return Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[] {Widget.class},
                        (widget, call, callArgs) -> zero(call.getReturnType()));
                return zero(method.getReturnType());
            });
        plugin = new GpManagerPlugin() {
            @Override boolean canIngest() { return true; }
            @Override void transactionBooked(Transaction transaction, long at) { published.add(transaction); }
        };
        GpManagerPluginProbe.set(plugin, "engine", e);
        plugin.activity = new ActivityDetector(CONFIG, e);
        GpManagerPluginProbe.set(plugin, "config", CONFIG);
        GpManagerPluginProbe.set(plugin, "client", client);
        GpManagerPluginProbe.set(plugin, "targets", GpManagerPluginProbe.tracker(client, e, CONFIG));
        GpManagerPluginProbe.set(plugin, "charges", new ChargeIntake(client, null, CONFIG, e, null));
        GpManagerPluginProbe.set(plugin, "containers", new ContainerSnapshotFactory(client, null));
    }

    private static Object zero(Class<?> type)
    {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static ContainerSnapshot held(long logs, long air)
    {
        Map<Integer, Long> items = new HashMap<>();
        if (logs > 0) items.put(LOGS, logs);
        if (air > 0) items.put(AIR, air);
        return new ContainerSnapshot(items);
    }

    private void dropClick()
    {
        dropClick(LOGS);
    }

    private void dropClick(int item)
    {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(), new Class<?>[] {MenuEntry.class},
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getOption": return "Drop";
                    case "getTarget": return item == BOLTS ? "Iron bolts" : "Logs";
                    case "getItemId": return item;
                    case "getType": return MenuAction.CC_OP;
                    default: return zero(method.getReturnType());
                }
            });
        plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
    }

    private void groundClick(int item)
    {
        e.events.put(Inbox.GROUND_CLICK, item, 1, 5, null);
    }

    /** An inventory (or worn equipment) callback: the engine samples the snapshot on the next tick. */
    private void inventory(int container)
    {
        plugin.onItemContainerChanged(new ItemContainerChanged(container, null));
    }

    /** The BANK container callback with the bank's own counts. */
    private void bank(long logs, long air)
    {
        Map<Integer, Long> counts = new HashMap<>();
        if (logs > 0) counts.put(LOGS, logs);
        if (air > 0) counts.put(AIR, air);
        bankItems(counts);
    }

    private void bankItems(Map<Integer, Long> counts) {
        bankShown = true;
        List<Item> items = new ArrayList<>();
        counts.forEach((id, quantity) -> items.add(new Item(id, quantity.intValue())));
        ItemContainer container = (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
            new Class<?>[] {ItemContainer.class},
            (proxy, method, args) -> "getItems".equals(method.getName()) ? items.toArray(new Item[0])
                : "getId".equals(method.getName()) ? InventoryID.BANK : zero(method.getReturnType()));
        plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK, container));
    }

    private void tick(long logs, long air)
    {
        e.processIfDirty(held(logs, air), now += 600, null);
    }

    private void openTicks(long logs, long air, int ticks)
    {
        bankShown = true;
        for (int i = 0; i < ticks; i++)
        {
            e.bankOpened(6);
            tick(logs, air);
        }
    }

    private void close()
    {
        bankShown = false;
        e.bankClosed();
    }

    private void closeAndPickUp(long before, long after)
    {
        close();
        for (int i = 0; i < 4; i++) tick(before, 0);
        inventory(InventoryID.INV);
        for (int i = 0; i < 4; i++) tick(after, 0);
    }

    private long count(String note)
    {
        return e.session().transactions().stream().filter(t -> note.equals(t.note())).count();
    }

    private Transaction dropped()
    {
        return e.session().transactions().stream().filter(t -> "Dropped".equals(t.note())).findFirst().orElse(null);
    }

    private void assertMoney(long net, long income, long cost)
    {
        assertEquals("net", net, e.metrics(now).net);
        assertEquals("income", income, e.metrics(now).income);
        assertEquals("cost", cost, e.metrics(now).cost);
    }

    private void assertKnown(int item, long quantity, long basis)
    {
        assertEquals("known quantity", quantity, EngineProbe.availableQty(e, item));
        assertEquals("known basis", basis, EngineProbe.availableBasisGp(e, item));
    }

    @Test public void pickingUpDroppedBoltsImmediatelyAfterBankCloseRecoversTheLoss() throws Exception
    {
        start(0, 0);
        e.setBaseline(new ContainerSnapshot(Map.of(BOLTS, 7L)));
        e.session().addTransaction(Tx.of(now, TransactionType.GAIN, Context.GENERIC, "Initial bolts", true,
                List.of(new Flow(BOLTS, "Iron bolts", 7, 10, 70, PriceSource.GRAND_EXCHANGE))), 2_000, false);
        dropClick(BOLTS);
        inventory(InventoryID.INV);
        for (int i = 0; i < 4; i++)
            e.processIfDirty(new ContainerSnapshot(Map.of()), now += 600, null);
        assertEquals(1, count("Dropped"));
        bankItems(Map.of());
        e.bankOpened(6);
        e.processIfDirty(new ContainerSnapshot(Map.of()), now += 600, null);
        close();
        groundClick(BOLTS);
        inventory(InventoryID.INV);
        for (int i = 0; i < 4; i++)
            e.processIfDirty(new ContainerSnapshot(Map.of(BOLTS, 7L)), now += 600, null);
        assertEquals("drop audit is empty", 0, dropped().flows().size());
        assertEquals("no recovered loss", 0, dropped().net());
        assertMoney(70, 70, 0);
        assertKnown(BOLTS, 7, 70);
    }

    @Test public void bankWithdrawalOfTheSameItemDoesNotRecoverTheGroundDrop() throws Exception
    {
        start(0, 0);
        e.setBaseline(new ContainerSnapshot(Map.of(BOLTS, 7L)));
        e.session().addTransaction(Tx.of(now, TransactionType.GAIN, Context.GENERIC, "Initial bolts", true,
                List.of(new Flow(BOLTS, "Iron bolts", 7, 10, 70, PriceSource.GRAND_EXCHANGE))), 2_000, false);
        dropClick(BOLTS);
        inventory(InventoryID.INV);
        for (int i = 0; i < 4; i++)
            e.processIfDirty(new ContainerSnapshot(Map.of()), now += 600, null);
        bankItems(Map.of(BOLTS, 7L));
        e.bankOpened(6);
        e.processIfDirty(new ContainerSnapshot(Map.of()), now += 600, null);
        bankItems(Map.of());
        inventory(InventoryID.INV);
        for (int i = 0; i < 4; i++)
            e.processIfDirty(new ContainerSnapshot(Map.of(BOLTS, 7L)), now += 600, null);
        assertEquals("withdrawal is not a ground pickup", 1, dropped().flows().size());
        assertEquals(-70, dropped().net());
        assertEquals(0, count("Picked back up"));
        assertMoney(0, 70, 70);
    }

    @Test public void nonBankTransferGainDoesNotRecoverTheGroundDrop() throws Exception
    {
        start(0, 0);
        e.setBaseline(new ContainerSnapshot(Map.of(BOLTS, 7L)));
        e.session().addTransaction(Tx.of(now, TransactionType.GAIN, Context.GENERIC, "Initial bolts", true,
                List.of(new Flow(BOLTS, "Iron bolts", 7, 10, 70, PriceSource.GRAND_EXCHANGE))), 2_000, false);
        dropClick(BOLTS);
        inventory(InventoryID.INV);
        for (int i = 0; i < 4; i++)
            e.processIfDirty(new ContainerSnapshot(Map.of()), now += 600, null);
        e.markContext(Context.TRANSFER, 6, "Grand Exchange");
        inventory(InventoryID.INV);
        for (int i = 0; i < 4; i++)
            e.processIfDirty(new ContainerSnapshot(Map.of(BOLTS, 7L)), now += 600, null);
        assertEquals("transfer gain is not a ground pickup", 1, dropped().flows().size());
        assertEquals(-70, dropped().net());
        assertEquals(0, count("Picked back up"));
        assertMoney(0, 70, 70);
    }

    // G1: the bank's first sight arrives before the drop's inventory callback.
    @Test public void bankSyncBeforeTheDropsInventoryCallbackKeepsTheDrop() throws Exception
    {
        start(3, 0);
        dropClick();
        bank(0, 0);
        inventory(InventoryID.INV);
        openTicks(0, 0, 4);
        assertEquals(1, count("Dropped"));
        assertEquals(-30, dropped().net());
        assertEquals(10, dropped().flows().get(0).unitPrice);
        closeAndPickUp(0, 3);
        assertEquals(1, count("Picked back up"));
        assertMoney(30, 30, 0);
        assertKnown(LOGS, 3, 30);
    }

    @Test public void bankPanelBeforeTheBankSyncKeepsTheDrop() throws Exception
    {
        start(3, 0);
        dropClick();
        bankShown = true;
        e.bankOpened(6);
        bank(0, 0);
        inventory(InventoryID.INV);
        openTicks(0, 0, 4);
        assertEquals(1, count("Dropped"));
        closeAndPickUp(0, 3);
        assertMoney(30, 30, 0);
        assertKnown(LOGS, 3, 30);
    }

    @Test public void sampledDropThenBankSyncKeepsTheDrop() throws Exception
    {
        start(3, 0);
        dropClick();
        inventory(InventoryID.INV);
        tick(0, 0);
        bank(0, 0);
        openTicks(0, 0, 4);
        closeAndPickUp(0, 3);
        assertMoney(30, 30, 0);
        assertKnown(LOGS, 3, 30);
    }

    // G2: a cancelled Drop, an unrelated equipment callback, then a real deposit before any sample.
    @Test public void staleClickAndUnrelatedCallbackCannotTurnARealDepositIntoADrop() throws Exception
    {
        start(3, 0);
        dropClick();
        inventory(InventoryID.WORN);
        bank(0, 0);
        e.bankOpened(6);
        bank(3, 0);
        inventory(InventoryID.INV);
        openTicks(0, 0, 4);
        close();
        assertEquals(0, count("Dropped"));
        assertMoney(30, 30, 0);
        assertKnown(LOGS, 3, 30);
    }

    @Test public void staleClickThenDepositWithTheInventoryCallbackFirstStaysNeutral() throws Exception
    {
        start(3, 0);
        dropClick();
        bank(0, 0);
        e.bankOpened(6);
        inventory(InventoryID.INV);
        bank(3, 0);
        openTicks(0, 0, 4);
        close();
        assertEquals(0, count("Dropped"));
        assertMoney(30, 30, 0);
        assertKnown(LOGS, 3, 30);
    }

    private void dropThreeAndDepositTwo() throws Exception
    {
        start(5, 0);
        dropClick();
        inventory(InventoryID.INV);
        bank(0, 0);
        e.bankOpened(6);
        bank(2, 0);
        inventory(InventoryID.INV);
        openTicks(0, 0, 4);
        assertEquals(1, count("Dropped"));
        assertEquals("only the 3 dropped logs", -3, dropped().flows().get(0).quantity);
        assertEquals(-30, dropped().net());
        assertMoney(20, 50, 30);
    }

    // G3: the drop and a same-item deposit land in one unsampled change.
    @Test public void unsampledDropAndSameItemDepositKeepTheirOwnQuantities() throws Exception
    {
        dropThreeAndDepositTwo();
        closeAndPickUp(0, 3);
        assertMoney(50, 50, 0);
        assertKnown(LOGS, 5, 50);
    }

    @Test public void partialRecoveryAfterADropAndDepositDepletesOnlyTheLostLogs() throws Exception
    {
        dropThreeAndDepositTwo();
        closeAndPickUp(0, 1);
        for (int i = 0; i < 300; i++) tick(1, 0);
        assertMoney(30, 50, 20);
        assertKnown(LOGS, 3, 30);
    }

    @Test public void excessPickupAfterADropAndDepositCountsOnlyTheNewLog() throws Exception
    {
        dropThreeAndDepositTwo();
        closeAndPickUp(0, 4);
        assertMoney(60, 60, 0);
        assertKnown(LOGS, 6, 60);
    }

    // G4: an acquisition already sampled before the bank opened stays income.
    @Test public void aGainSampledBeforeTheBankStaysIncome() throws Exception
    {
        start(3, 0);
        inventory(InventoryID.INV);
        tick(3, 4);
        bank(0, 0);
        openTicks(3, 4, 4);
        close();
        assertMoney(70, 70, 0);
        assertKnown(AIR, 4, 40);
        assertEquals("publish the boundary receipt exactly once", 1, published.size());
        assertEquals(40, published.get(0).net());
        assertEquals(1_600, published.get(0).timestamp);
        assertEquals(true, GpManagerPlugin.saveDue(plugin.saveTicks + 10, e.revision(), -1));
    }

    @Test public void preBankGainAndLaterDepositStaySeparate() throws Exception {
        start(3, 0);
        inventory(InventoryID.INV);
        tick(3, 4);
        bank(0, 0);
        bank(0, 2);
        inventory(InventoryID.INV);
        openTicks(3, 2, 4);
        assertMoney(70, 70, 0);
        assertKnown(AIR, 4, 40);
        assertEquals(1, published.size());
    }

    @Test public void completedFletchingKeepsItsSourceBeforeBanking() throws Exception {
        start(3, 0);
        e.setBaseline(new ContainerSnapshot(Map.of(LOGS, 3L, 1515, 27L)));
        e.session().addTransaction(Tx.of(now, TransactionType.GAIN, Context.GENERIC, "Initial logs", true,
                List.of(new Flow(1515, "Yew logs", 27, 94, 2538, PriceSource.GRAND_EXCHANGE))), 2_000, false);
        e.markContext(Context.PRODUCTION, 6, "Fletching");
        inventory(InventoryID.INV);
        var crafted = new ContainerSnapshot(Map.of(LOGS, 3L, 66, 27L));
        e.processIfDirty(crafted, now += 600, null);
        bank(0, 0);
        for (int i = 0; i < 4; i++) {
            e.bankOpened(6);
            e.processIfDirty(crafted, now += 600, null);
        }
        assertMoney(4_215, 6_753, 2_538);
        assertEquals(1, published.size());
        Transaction row = published.get(0);
        assertEquals(TransactionType.PROCESSING, row.type());
        assertEquals(Context.PRODUCTION, row.context());
        assertEquals("Fletching", row.note());
        assertEquals(27, row.quantity(66, true));
        assertEquals(27, row.quantity(1515, false));
        assertEquals(1_647, row.net());
    }

    @Test public void confirmedFoodBeforeBankingKeepsItsCost() throws Exception {
        start(3, 0);
        e.setBaseline(new ContainerSnapshot(Map.of(LOGS, 3L, 13441, 1L)));
        e.session().addTransaction(Tx.of(now, TransactionType.GAIN, Context.GENERIC, "Initial food", true,
                List.of(new Flow(13441, "Anglerfish", 1, 1459, 1459, PriceSource.GRAND_EXCHANGE))), 2_000, false);
        e.noteConsume(13441, 6, false, ActionKind.EAT, null);
        inventory(InventoryID.INV);
        tick(3, 0);
        bank(0, 0);
        openTicks(3, 0, 4);
        assertMoney(30, 1_489, 1_459);
        assertEquals(1, published.size());
        Transaction row = published.get(0);
        assertEquals(TransactionType.CONSUMPTION, row.type());
        assertEquals(ActionKind.EAT, row.action());
        assertEquals(-1_459, row.net());
        assertEquals(1_459, row.flows().get(0).unitPrice);
    }

    @Test public void ownerFenceDiscardsThePreviousAccountsBankCounts() throws Exception {
        start(3, 0);
        bank(9, 0);
        e.events.owner(e.events.owner + 1);
        dropClick(); // cancelled: all three logs remain until the real deposit
        bank(0, 0);
        bank(3, 0);
        inventory(InventoryID.INV);
        openTicks(0, 0, 4);
        assertMoney(30, 30, 0);
        assertEquals(0, count("Dropped"));
        assertKnown(LOGS, 3, 30);
    }

    @Test public void equalDropAndDepositKeepTheNeutralAudit() throws Exception {
        start(4, 0);
        dropClick();
        inventory(InventoryID.INV);
        bank(0, 0);
        bank(2, 0);
        inventory(InventoryID.INV);
        openTicks(0, 0, 4);
        assertMoney(20, 40, 20);
        assertEquals(-20, dropped().net());
        assertEquals(1, e.session().transactions().stream()
                .filter(row -> row.type() == TransactionType.TRANSFER && row.quantity(LOGS, false) == 2).count());
        closeAndPickUp(0, 2);
        assertMoney(40, 40, 0);
        assertKnown(LOGS, 4, 40);
    }

    @Test public void aTokenDepositDoesNotBecomePartOfACoinDrop() throws Exception {
        start(3, 0);
        // Physical stock: 3 coins and one token, projected as 1,003 accounting coins.
        e.setBaseline(new ContainerSnapshot(Map.of(LOGS, 3L, 995, 1_003L)));
        e.session().addTransaction(Tx.of(now, TransactionType.GAIN, Context.GENERIC, "Initial cash", true,
                List.of(new Flow(995, "Coins", 1_003, 1, 1_003, PriceSource.FACE_VALUE))), 2_000, false);
        e.noteDrop(995, 6);
        inventory(InventoryID.INV);
        bankItems(Map.of());
        bankItems(Map.of(13204, 1L));
        inventory(InventoryID.INV);
        openTicks(3, 0, 4);
        assertEquals(-3, dropped().net());
        assertMoney(1_030, 1_033, 3);
        close();
        for (int i = 0; i < 4; i++) tick(3, 0);
        inventory(InventoryID.INV);
        for (int i = 0; i < 4; i++)
            e.processIfDirty(new ContainerSnapshot(Map.of(LOGS, 3L, 995, 3L)), now += 600, null);
        assertMoney(1_033, 1_033, 0);
        assertEquals(3, e.baseline.quantity(995));
        assertKnown(LOGS, 3, 30);
    }

    @Test public void genuineDepositAndWithdrawalStayNeutralInEitherCallbackOrder() throws Exception
    {
        for (boolean bankFirst : new boolean[] {true, false})
        {
            start(3, 0);
            bank(0, 0);
            e.bankOpened(6);
            if (bankFirst) bank(3, 0);
            inventory(InventoryID.INV);
            if (!bankFirst) bank(3, 0);
            openTicks(0, 0, 4);
            assertMoney(30, 30, 0);
            if (bankFirst) bank(0, 0);
            inventory(InventoryID.INV);
            if (!bankFirst) bank(0, 0);
            openTicks(3, 0, 4);
            close();
            assertEquals(0, count("Dropped"));
            assertMoney(30, 30, 0);
            assertKnown(LOGS, 3, 30);
        }
    }

    @Test public void aDropBeforeWithdrawingAnotherItemKeepsBothOutcomes() throws Exception
    {
        start(3, 0);
        dropClick();
        inventory(InventoryID.INV);
        bank(0, 4);
        e.bankOpened(6);
        bank(0, 0);
        inventory(InventoryID.INV);
        openTicks(0, 4, 4);
        close();
        assertEquals(1, count("Dropped"));
        assertEquals(-30, dropped().net());
        assertMoney(0, 30, 30);
        assertKnown(AIR, 0, 0);
    }

    // Lifecycle: each opening's first sight is a fresh baseline, never movement from a previous one.
    @Test public void aReopenedBankIsAFreshBaseline() throws Exception
    {
        start(3, 0);
        bank(0, 0);
        openTicks(3, 0, 1);
        close();
        tick(3, 0);
        dropClick();
        inventory(InventoryID.INV);
        bank(3, 0); // changed while closed elsewhere: a baseline, not this drop's deposit
        openTicks(0, 0, 4);
        close();
        assertEquals(1, count("Dropped"));
        closeAndPickUp(0, 3);
        assertMoney(30, 30, 0);
        assertKnown(LOGS, 3, 30);
    }

    @Test public void aPauseForgetsThePendingDropAndBankMovement() throws Exception
    {
        start(3, 0);
        dropClick();
        bank(0, 0);
        e.bankOpened(6);
        bank(3, 0);
        inventory(InventoryID.INV);
        e.pauseForLifecycle(now);
        tick(0, 0);
        e.session().resume(now);
        close();
        tick(0, 0);
        dropClick();
        inventory(InventoryID.INV);
        tick(0, 0);
        for (int i = 0; i < 4; i++) tick(0, 0);
        assertEquals("nothing left to drop after the paused deposit", 0, count("Dropped"));
        assertMoney(30, 30, 0);
    }
}
