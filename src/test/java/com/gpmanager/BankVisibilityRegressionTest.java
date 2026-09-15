package com.gpmanager;

import com.gpmanager.engine.ContainerSnapshot;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.TransactionType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
            GpManagerEngine engine = new GpManagerEngine(deltas -> {
                java.util.List<ItemFlow> flows = new java.util.ArrayList<>();
                deltas.forEach((id, qty) -> flows.add(new ItemFlow(id, "Item " + id, qty, 50, qty * 50)));
                return flows;
            }, new TransactionClassifier(), config);
            engine.ensureSession(1000);
            ContainerSnapshot held = new ContainerSnapshot(Collections.singletonMap(itemId, 1L));
            engine.setBaseline(consuming ? held : ContainerSnapshot.empty());
            // The same bank visibility decision used by the production tick adapter.
            if (bankOpen(false)) engine.markBankInterfaceOpen(6);
            else engine.markBankInterfaceClosed();
            engine.markInventoryDirty();
            ContainerSnapshot next = consuming ? ContainerSnapshot.empty() : held;
            engine.processIfDirty(next, 1600);
            assertEquals(consuming ? TransactionType.CONSUMPTION : TransactionType.GAIN,
                engine.processIfDirty(next, 2200).getType());
            assertEquals(consuming ? -50 : 50, engine.getMetrics(2200).getNet());
        }
    }

    @Test public void visibleBankStillSuppressesTransfers() throws Exception
    {
        assertTrue(bankOpen(true));
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
        GpManagerPlugin plugin = new GpManagerPlugin();
        Field field = GpManagerPlugin.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(plugin, client);
        Method method = GpManagerPlugin.class.getDeclaredMethod("isBankOpen");
        method.setAccessible(true);
        return (boolean) method.invoke(plugin);
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
