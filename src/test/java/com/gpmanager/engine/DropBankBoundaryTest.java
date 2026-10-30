package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import static org.junit.Assert.assertEquals;

/**
 * Owner 1.7.0: a real drop followed quickly by opening a bank must stay the player's own drop, so
 * picking the items back up is neutral; a genuine deposit stays a neutral bank transfer. Every item
 * is quoted at 10 GP; the account starts with 3 counted logs (30 GP of known basis).
 */
public class DropBankBoundaryTest
{
    private static final int LOGS = 1511, AIR = 556;
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

    private Engine engine(long logs, long air)
    {
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, id == LOGS ? "Logs" : "Air rune", quantity,
                10, quantity * 10, PriceSource.GRAND_EXCHANGE)));
            return flows;
        }, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000);
        engine.setBaseline(held(logs, air));
        engine.session().addTransaction(Tx.of(1_000, TransactionType.GAIN, Context.GENERIC, "Initial loot", true,
            List.of(new Flow(LOGS, "Logs", logs, 10, logs * 10, PriceSource.GRAND_EXCHANGE))), 2_000, false);
        assertEquals(logs * 10, engine.metrics(now).net);
        assertEquals(logs, EngineProbe.availableQty(engine, LOGS));
        return engine;
    }

    private static ContainerSnapshot held(long logs, long air)
    {
        Map<Integer, Long> items = new HashMap<>();
        if (logs > 0) items.put(LOGS, logs);
        if (air > 0) items.put(AIR, air);
        return new ContainerSnapshot(items);
    }

    private void tick(Engine e, ContainerSnapshot inventory)
    {
        e.processIfDirty(inventory, now += 600, null);
    }

    private long bankLogs, bankAir;
    private boolean synced;

    /** The BANK callback: the plugin passes the bank's own counts. */
    private void bank(Engine e, long logs, long air)
    {
        bankLogs = logs;
        bankAir = air;
        synced = true;
        Map<Integer, Long> counts = new HashMap<>();
        if (logs > 0) counts.put(LOGS, logs);
        if (air > 0) counts.put(AIR, air);
        e.bankContents(counts, now, ignored -> {});
    }

    private void closeBank(Engine e)
    {
        synced = false;
        e.bankClosed();
    }

    private void bankTicks(Engine e, ContainerSnapshot inventory, int ticks)
    {
        for (int i = 0; i < ticks; i++)
        {
            // The BANK callback fires once when the bank opens, then only when its contents change.
            if (!synced) bank(e, bankLogs, bankAir);
            e.bankOpened(6);
            tick(e, inventory);
        }
    }

    private void closeAndPickUp(Engine e, ContainerSnapshot inventory)
    {
        closeBank(e);
        for (int i = 0; i < 4; i++) tick(e, held(0, 0));
        e.markDirty();
        for (int i = 0; i < 4; i++) tick(e, inventory);
    }

    private static Object zero(Class<?> type)
    {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static long count(Engine e, String note)
    {
        return e.session().transactions().stream().filter(t -> note.equals(t.note())).count();
    }

    private static Transaction dropped(Engine e)
    {
        return e.session().transactions().stream().filter(t -> "Dropped".equals(t.note())).findFirst().orElse(null);
    }

    /** Drop 3 logs, then the bank opens in one of the observed callback orders. */
    private Engine dropThenBank(boolean sampledBeforeBank, boolean bankContainerFirst)
    {
        Engine e = engine(3, 0);
        e.noteDrop(LOGS, 6);
        e.markDirty();
        if (sampledBeforeBank) tick(e, held(0, 0));
        if (bankContainerFirst) bank(e, bankLogs, bankAir);
        bankTicks(e, held(0, 0), 4);
        return e;
    }

    private void assertOwnDropRecovered(Engine e)
    {
        assertEquals("the drop books once at its captured value", 1, count(e, "Dropped"));
        assertEquals(-30, dropped(e).net());
        assertEquals(10, dropped(e).flows().get(0).unitPrice);
        assertEquals(0, e.metrics(now).net);
        closeAndPickUp(e, held(3, 0));
        assertEquals(1, count(e, "Picked back up"));
        assertEquals("own pickup restores the original Net", 30, e.metrics(now).net);
        assertEquals("no fresh income", 30, e.metrics(now).income);
        assertEquals(0, e.metrics(now).cost);
        assertEquals(3, EngineProbe.availableQty(e, LOGS));
        assertEquals(30, EngineProbe.availableBasisGp(e, LOGS));
    }

    @Test public void dropThenBankBeforeTheFirstSample() { assertOwnDropRecovered(dropThenBank(false, false)); }

    @Test public void dropAlreadyPendingWhenTheBankOpens() { assertOwnDropRecovered(dropThenBank(true, false)); }

    @Test public void bankContainerSyncWhileTheSampledDropIsPending() { assertOwnDropRecovered(dropThenBank(true, true)); }

    @Test public void bankContainerSyncBeforeTheFirstSample() { assertOwnDropRecovered(dropThenBank(false, true)); }

    @Test public void partialRecoveryDepletesOnlyTheItemsLeftOnTheGround()
    {
        Engine e = dropThenBank(true, true);
        closeAndPickUp(e, held(1, 0));
        assertEquals(-20, dropped(e).net());
        for (int i = 0; i < 300; i++) tick(e, held(1, 0));
        assertEquals(10, e.metrics(now).net);
        assertEquals(30, e.metrics(now).income);
        assertEquals(20, e.metrics(now).cost);
        assertEquals(1, EngineProbe.availableQty(e, LOGS));
        assertEquals(10, EngineProbe.availableBasisGp(e, LOGS));
    }

    @Test public void excessPickupCountsOnlyTheGenuinelyNewLogs()
    {
        Engine e = dropThenBank(false, true);
        closeAndPickUp(e, held(5, 0));
        assertEquals(1, count(e, "Picked back up"));
        assertEquals(50, e.metrics(now).net);
        assertEquals(50, e.metrics(now).income);
        assertEquals(0, e.metrics(now).cost);
        assertEquals(5, EngineProbe.availableQty(e, LOGS));
        assertEquals(50, EngineProbe.availableBasisGp(e, LOGS));
    }

    @Test public void aSettledDropStillReconcilesAfterBanking()
    {
        Engine e = engine(3, 0);
        e.noteDrop(LOGS, 6);
        e.markDirty();
        for (int i = 0; i < 4; i++) tick(e, held(0, 0));
        assertEquals(1, count(e, "Dropped"));
        bank(e, bankLogs, bankAir);
        bankTicks(e, held(0, 0), 4);
        closeAndPickUp(e, held(3, 0));
        assertEquals(30, e.metrics(now).net);
        assertEquals(30, e.metrics(now).income);
        assertEquals(3, EngineProbe.availableQty(e, LOGS));
    }

    @Test public void staleDropClickThenRealDepositAndWithdrawalStayNeutral()
    {
        for (boolean containerFirst : new boolean[] {false, true})
        {
            Engine e = engine(3, 0);
            e.noteDrop(LOGS, 6);
            if (containerFirst) bank(e, 0, 0);
            bankTicks(e, held(3, 0), 1);
            bank(e, 3, 0);
            e.markDirty();
            bankTicks(e, held(0, 0), 4);
            assertEquals(0, count(e, "Dropped"));
            assertEquals(30, e.metrics(now).net);
            bank(e, 0, 0);
            e.markDirty();
            bankTicks(e, held(3, 0), 4);
            closeBank(e);
            assertEquals(30, e.metrics(now).net);
            assertEquals(30, e.metrics(now).income);
            assertEquals(0, e.metrics(now).cost);
            assertEquals(3, EngineProbe.availableQty(e, LOGS));
            assertEquals(30, EngineProbe.availableBasisGp(e, LOGS));
        }
    }

    @Test public void staleDropClickWithAnUnrelatedInventoryCallbackThenDepositStaysNeutral()
    {
        Engine e = engine(3, 0);
        e.noteDrop(LOGS, 6);
        e.markDirty(); // something else touched the inventory; the logs never left
        bankTicks(e, held(3, 0), 1);
        bank(e, 3, 0);
        e.markDirty();
        bankTicks(e, held(0, 0), 4);
        closeBank(e);
        assertEquals(0, count(e, "Dropped"));
        assertEquals(30, e.metrics(now).net);
        assertEquals(0, e.metrics(now).cost);
        assertEquals(3, EngineProbe.availableQty(e, LOGS));
    }

    @Test public void depositedLogsAfterTheDropAreNeverAttributedToIt()
    {
        Engine e = engine(5, 4);
        e.noteDrop(LOGS, 6);
        e.markDirty();
        tick(e, held(2, 4));
        bank(e, 0, 0);
        bankTicks(e, held(2, 4), 1);
        bank(e, 2, 4);
        e.markDirty();
        bankTicks(e, held(0, 0), 4);
        assertEquals(1, count(e, "Dropped"));
        assertEquals("only the 3 dropped logs", -3, dropped(e).flows().get(0).quantity);
        assertEquals(50 - 30, e.metrics(now).net);
        closeAndPickUp(e, held(3, 0));
        assertEquals(50, e.metrics(now).net);
        assertEquals(50, e.metrics(now).income);
        assertEquals(0, e.metrics(now).cost);
        assertEquals("3 recovered plus 2 banked", 5, EngineProbe.availableQty(e, LOGS));
        assertEquals(50, EngineProbe.availableBasisGp(e, LOGS));
    }

    @Test public void aWithdrawalAfterThePendingDropStaysNeutral()
    {
        Engine e = engine(3, 0);
        e.noteDrop(LOGS, 6);
        e.markDirty();
        tick(e, held(0, 0));
        bankAir = 4;
        bankTicks(e, held(0, 0), 1);
        bank(e, 0, 0);
        e.markDirty();
        bankTicks(e, held(0, 4), 4);
        closeBank(e);
        assertEquals(1, count(e, "Dropped"));
        assertEquals(-30, dropped(e).net());
        assertEquals("the withdrawn runes book nothing", 0, e.metrics(now).net);
        assertEquals(0, EngineProbe.availableQty(e, AIR));
        for (int i = 0; i < 4; i++) tick(e, held(0, 4));
        e.markDirty();
        for (int i = 0; i < 4; i++) tick(e, held(3, 4));
        assertEquals(30, e.metrics(now).net);
        assertEquals(30, e.metrics(now).income);
        assertEquals(3, EngineProbe.availableQty(e, LOGS));
    }

    @Test public void aDropMixedWithAnotherChangeBeforeTheBankKeepsOnlyItsOwnItem()
    {
        Engine e = engine(3, 0);
        e.noteDrop(LOGS, 6);
        e.markDirty();
        tick(e, held(0, 4));
        bank(e, bankLogs, bankAir);
        bankTicks(e, held(0, 4), 4);
        closeBank(e);
        assertEquals(1, count(e, "Dropped"));
        assertEquals(-30, dropped(e).net());
        assertEquals("only the dropped logs", 1, dropped(e).flows().size());
        for (int i = 0; i < 4; i++) tick(e, held(0, 4));
        e.markDirty();
        for (int i = 0; i < 4; i++) tick(e, held(3, 4));
        assertEquals(1, count(e, "Picked back up"));
        assertEquals(0, dropped(e).quantity(LOGS, false));
        assertEquals(3, EngineProbe.availableQty(e, LOGS));
        assertEquals(30, EngineProbe.availableBasisGp(e, LOGS));
    }

    @Test public void pluginBankCallbackKeepsTheMenuDrop() throws Exception
    {
        Engine e = engine(3, 0);
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) -> "getGameState".equals(method.getName()) ? GameState.LOGGED_IN : zero(method.getReturnType()));
        GpManagerPluginProbe.set(plugin, "engine", e);
        plugin.activity = new ActivityDetector(CONFIG, e);
        GpManagerPluginProbe.set(plugin, "config", CONFIG);
        GpManagerPluginProbe.set(plugin, "client", client);
        GpManagerPluginProbe.set(plugin, "targets", GpManagerPluginProbe.tracker(client, e, CONFIG));
        GpManagerPluginProbe.set(plugin, "charges", new ChargeIntake(client, null, CONFIG, e, null));
        MenuEntry drop = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(), new Class<?>[] {MenuEntry.class},
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getOption": return "Drop";
                    case "getTarget": return "Logs";
                    case "getItemId": return LOGS;
                    case "getType": return MenuAction.CC_OP;
                    default: return zero(method.getReturnType());
                }
            });
        plugin.onMenuOptionClicked(new MenuOptionClicked(drop));
        plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, null));
        tick(e, held(0, 0));
        GpManagerPluginProbe.set(plugin, "containers", new ContainerSnapshotFactory(client, null));
        ItemContainer emptyBank = (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
            new Class<?>[] {ItemContainer.class}, (proxy, method, args) -> "getItems".equals(method.getName())
                ? new net.runelite.api.Item[0] : zero(method.getReturnType()));
        plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK, emptyBank));
        bankTicks(e, held(0, 0), 4);
        assertEquals(1, count(e, "Dropped"));
        closeBank(e);
        for (int i = 0; i < 4; i++) tick(e, held(0, 0));
        plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, null));
        for (int i = 0; i < 4; i++) tick(e, held(3, 0));
        assertEquals(1, count(e, "Picked back up"));
        assertEquals(30, e.metrics(now).net);
        assertEquals(30, e.metrics(now).income);
        assertEquals(3, EngineProbe.availableQty(e, LOGS));
    }
}
