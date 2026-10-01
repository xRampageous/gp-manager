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
            Am engine = new Am(deltas -> {
                java.util.List<Ab> flows = new java.util.ArrayList<>();
                deltas.forEach((id, qty) -> flows.add(new Ab(id, "Item " + id, qty, 50, qty * 50)));
                return flows;
            }, new TransactionClassifier(), config);
            engine.rm(1000);
            Cc held = new Cc(Collections.singletonMap(itemId, 1L));
            engine.setBaseline(consuming ? held : Cc.empty());
            // The same bank visibility decision used by the production tick adapter.
            if (bankOpen(false)) engine.ze(6);
            else engine.yu();
            engine.yz();
            Cc next = consuming ? Cc.empty() : held;
            engine.adj(next, 1600);
            assertEquals(consuming ? Ai.CONSUMPTION : Ai.GAIN,
                engine.adj(next, 2200).getType());
            assertEquals(consuming ? -50 : 50, engine.getMetrics(2200).net);
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
        assertTrue(Ee.yf(open));
        assertFalse(Ee.yf(closed));
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
        return Ee.vp(client);
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
