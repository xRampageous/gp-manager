package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import static org.junit.Assert.*;

/** Recorded first Revenant teleport: the click precedes the confirmation and physical tablet loss. */
public class TeleportStartBoundaryTest
{
    private static final int TABLET = 21802;
    private static final GpManagerConfig CONFIG = new GpManagerConfig() {
        @Override public int stabilizationTicks() { return 1; }
    };

    private long now = 1_000;
    private java.util.function.LongSupplier previousClock;
    @Before public void fixedClock() { previousClock = SafeMath.clock; SafeMath.clock = () -> now; }
    @After public void restoreClock() { SafeMath.clock = previousClock; }

    private final Engine engine = new Engine(deltas -> {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, count) -> flows.add(new Flow(id, "Revenant cave teleport", count,
                492, count * 492, PriceSource.GRAND_EXCHANGE)));
        return flows;
    }, new TransactionClassifier(), CONFIG);

    private void click() throws Exception
    {
        click(true);
    }

    /** An item click on the tablet, or an NPC's own Teleport option (a wizard) with the tablet in slot 0. */
    private void click(boolean item) throws Exception
    {
        ItemContainer inventory = proxy(ItemContainer.class, method ->
                "getItems".equals(method) ? new Item[] {new Item(TABLET, 1)} : null);
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[] {Client.class}, (object, method, args) -> {
                    if ("getGameState".equals(method.getName())) return GameState.LOGGED_IN;
                    if ("getItemContainer".equals(method.getName()))
                        return Integer.valueOf(InventoryID.INV).equals(args[0]) ? inventory : null;
                    if (!method.getReturnType().isPrimitive()) return null;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return 0;
                });
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        GpManagerPluginProbe.set(plugin, "client", client);
        GpManagerPluginProbe.set(plugin, "config", CONFIG);
        GpManagerPluginProbe.set(plugin, "engine", engine);
        GpManagerPluginProbe.set(plugin, "containers", new ContainerSnapshotFactory(client, null));
        GpManagerPluginProbe.set(plugin, "targets", GpManagerPluginProbe.tracker(client, engine, CONFIG));
        GpManagerPluginProbe.set(plugin, "charges", new ChargeIntake(client, null, CONFIG, engine, null));
        MenuEntry entry = proxy(MenuEntry.class, method -> {
            if ("getOption".equals(method)) return "Teleport";
            if ("getTarget".equals(method)) return item ? "Revenant cave teleport" : "Aubury";
            if ("getItemId".equals(method)) return item ? TABLET : -1;
            if ("getType".equals(method)) return item ? MenuAction.CC_OP : MenuAction.NPC_THIRD_OPTION;
            if ("isItemOp".equals(method)) return item;
            return null;
        });
        plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
    }

    private void settle(long quantity)
    {
        engine.markDirty();
        ContainerSnapshot held = new ContainerSnapshot(quantity == 0 ? Map.of() : Map.of(TABLET, quantity));
        for (int tick = 1; tick <= 6; tick++) engine.processIfDirty(held, now += 600, null);
    }

    @Test public void firstTeleportStartsBeforeTheTabletDisappears() throws Exception
    {
        click();
        assertNotNull("the menu starts tracking before the item changes", engine.session());
        settle(0);
        assertEquals(0, engine.metrics(now).income);
        assertEquals(492, engine.metrics(now).cost);
        assertEquals(1, engine.session().transactions().size());
        Flow spent = engine.session().transactions().get(0).flows().get(0);
        assertEquals(-1, spent.quantity);
        assertEquals(492, spent.unitPrice);
    }

    @Test public void anNpcTeleportNeverNamesTheItemInTheFirstSlot() throws Exception
    {
        click(false);
        assertTrue("an NPC entry's scene coordinates are not an inventory slot",
                engine.intent() == null || engine.intent().item != TABLET);
    }

    @Test public void cancelledTeleportNeverInventsACost() throws Exception
    {
        click();
        settle(1);
        assertEquals(0, engine.metrics(now).net);
        assertTrue(engine.session() == null || engine.session().transactions().isEmpty());
    }

    @Test public void aManuallyPausedRunStaysPaused() throws Exception
    {
        engine.ensureSession(now);
        engine.setBaseline(new ContainerSnapshot(Map.of(TABLET, 1L)));
        engine.session().pause(now, PauseReason.MANUAL);
        click();
        settle(0);
        assertTrue(engine.session().paused);
        assertEquals(0, engine.metrics(now).cost);
    }

    @Test public void anAlreadyRunningTeleportCountsOnlyOnce() throws Exception
    {
        engine.ensureSession(now);
        engine.setBaseline(new ContainerSnapshot(Map.of(TABLET, 1L)));
        click();
        settle(0);
        settle(0);
        assertEquals(492, engine.metrics(now).cost);
        assertEquals(1, engine.session().transactions().size());
    }

    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> values)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (object, method, args) -> {
                    Object value = values.apply(method.getName());
                    if (value != null || !method.getReturnType().isPrimitive()) return value;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return 0;
                }));
    }
}
