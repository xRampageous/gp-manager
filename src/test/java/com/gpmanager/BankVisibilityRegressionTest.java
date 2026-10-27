package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;

/** Retained bank contents are not proof that the bank interface is still open. */
public class BankVisibilityRegressionTest
{
    @Test public void cachedBankAfterCloseDoesNotSuppressHarvestOrSupplies() throws Exception
    {
        for (int itemId : new int[] {1942, 1947, 1511, 1519, 385})
        {
            boolean consuming = itemId == 385;
            GpManagerConfig config = new GpManagerConfig() {
                public int stabilizationTicks() { return 0; }
            };
            Engine engine = new Engine(deltas -> {
                java.util.List<Flow> flows = new java.util.ArrayList<>();
                deltas.forEach((id, qty) -> flows.add(new Flow(id, "Item " + id, qty, 50, qty * 50)));
                return flows;
            }, new TransactionClassifier(), config);
            engine.ensureSession(1000);
            ContainerSnapshot held = new ContainerSnapshot(Collections.singletonMap(itemId, 1L));
            engine.setBaseline(consuming ? held : ContainerSnapshot.empty());
            // The same bank visibility decision used by the production tick adapter.
            if (bankOpen(false)) engine.bankOpened(6);
            else engine.bankClosed();
            engine.markDirty();
            ContainerSnapshot next = consuming ? ContainerSnapshot.empty() : held;
            engine.processIfDirty(next, 1600, null);
            assertEquals(consuming ? TransactionType.CONSUMPTION : TransactionType.GAIN,
                engine.processIfDirty(next, 2200, null).type());
            assertEquals(consuming ? -50 : 50, engine.metrics(2200).net);
        }
    }

    @Test public void visibleBankStillSuppressesTransfers() throws Exception
    {
        assertTrue(bankOpen(true));
    }

    @Test public void toolLeprechaunStoreIsStorageOnlyWhileItsWindowShows()
    {
        Widget shown = proxy(Widget.class, name -> "isHidden".equals(name) ? false : null);
        Client open = proxy(Client.class, name -> "getWidget".equals(name) ? shown : null);
        Client closed = proxy(Client.class, name -> null);
        assertTrue(Widgets.isToolStoreOpen(open));
        assertFalse(Widgets.isToolStoreOpen(closed));
    }

    /**
     * Owner 2026-10-08: storing in or taking from a store with its own window (Elnock, the seed
     * vault, a cargo hold, Death's coffer, item sets...) is no loot. Every d29 row names a real
     * RuneLite component, and only that store's window gives its note.
     */
    @Test public void storeWindowsAreStorageWhileEitherPanelShows() throws Exception
    {
        Widget shown = proxy(Widget.class, name -> "isHidden".equals(name) ? false : null);
        assertTrue(Widgets.STORES.size() >= 16);
        for (String[] row : Widgets.STORES)
        {
            String[] name = row[1].split("\\.");
            int component = Class.forName("net.runelite.api.gameval.InterfaceID$" + name[0])
                .getField(name[1]).getInt(null);
            assertEquals(row[1], component, Integer.parseInt(row[0]));
            Client open = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
                (obj, method, args) -> "getWidget".equals(method.getName()) && args.length == 1
                    && Integer.valueOf(component).equals(args[0]) ? shown : null);
            assertEquals(row[1], row[2], Widgets.store(open));
            assertFalse("not the tool store", Widgets.isToolStoreOpen(open));
            assertFalse("not the Grand Exchange", Widgets.isGeOpen(open));
        }
        assertNull(Widgets.store(proxy(Client.class, name -> null)));
    }

    /** A banker's collection box collects Grand Exchange offers: a Collect there is GE evidence. */
    @Test public void collectionBoxCountsAsTheGrandExchange()
    {
        Widget shown = proxy(Widget.class, name -> "isHidden".equals(name) ? false : null);
        int box = net.runelite.api.gameval.InterfaceID.GeCollect.UNIVERSE;
        Client open = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (obj, method, args) -> "getWidget".equals(method.getName()) && args.length == 1
                && Integer.valueOf(box).equals(args[0]) ? shown : null);
        assertTrue(Widgets.isGeOpen(open));
        assertFalse(Widgets.isGeOpen(proxy(Client.class, name -> null)));
    }

    private boolean bankOpen(boolean visible) throws Exception
    {
        ItemContainer cachedBank = proxy(ItemContainer.class, (name) -> null);
        Widget widget = proxy(Widget.class, name -> "isHidden".equals(name) ? !visible : null);
        Client client = proxy(Client.class, name -> {
            if ("getItemContainer".equals(name)) return cachedBank;
            if ("getWidget".equals(name)) return widget;
            return null;
        });
        return Widgets.isBankOpen(client);
    }

    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> values)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (obj, method, args) -> {
            Object value = values.apply(method.getName());
            if (value != null || !method.getReturnType().isPrimitive()) return value;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == long.class) return 0L;
            return 0;
        }));
    }
}
