package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.*;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Owner 2026-09-29: uncharging returns the loaded components. The refund is the player's own
 * charge investment coming back, so it books as an ownership-neutral transfer, never income.
 */
public class ChargeUnloadNeutralTest
{
    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void anUnchargeClickMakesTheReturnedComponentsNeutral()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 1;
            }
        };
        Am engine = engine(config);
        ChargeIntake intake = new ChargeIntake(null, null, config, engine, null);

        engine.ajl("Nechryael", Cx.GENERAL, T0);
        Map<Integer, Long> held = new HashMap<>();
        held.put(ItemID.TOTS_CHARGED, 1L);
        engine.setBaseline(new Cc(new HashMap<>(held)));
        held.remove(ItemID.TOTS_CHARGED);
        held.put(ItemID.TOTS_UNCHARGED, 1L);
        held.put(ItemID.DEATHRUNE, 100L);
        engine.yz();
        // A right-click Uncharge on the worn trident; the plugin passes the lowercased option.
        intake.ld(click(ItemID.TOTS_CHARGED), "uncharge", "Trident of the seas");
        Ac result = null;
        for (int i = 0; i < 3; i++)
        {
            Ac settled = engine.adj(new Cc(new HashMap<>(held)), T0 + 600L + i * 600L);
            if (settled != null)
            {
                result = settled;
            }
        }
        assertNotNull(result);
        assertEquals("the refund is a transfer, not income", Ai.TRANSFER, result.getType());
        assertFalse(result.isCounted());
        assertEquals("nothing books to the session", 0L, engine.getMetrics(T0 + 2_400L).net);
        assertEquals(0L, engine.getMetrics(T0 + 2_400L).costs);
    }

    @Test
    public void aConfirmThatTakesLongerThanTheClickStillBooksNeutral()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 1;
            }
        };
        Am engine = engine(config);
        int[] tick = {100};
        Map<Integer, Long> held = new HashMap<>();
        held.put(ItemID.TOTS_CHARGED, 1L);
        ChargeIntake intake = new ChargeIntake(client(tick, held), null, config, engine, null)
        {
            @Override
            String itemName(int itemId)
            {
                return itemId == ItemID.DEATHRUNE ? "Death rune" : null;
            }
        };
        engine.ajl("Nechryael", Cx.GENERAL, T0);
        engine.setBaseline(new Cc(new HashMap<>(held)));
        intake.ld(click(ItemID.TOTS_CHARGED), "uncharge", "Trident of the seas");
        long now = T0;
        // The "Really uncharge?" prompt sits open for twelve ticks: the click's own context lapses.
        for (int i = 0; i < 12; i++)
        {
            tick[0]++;
            intake.watchReturn();
            assertNull(engine.adj(new Cc(new HashMap<>(held)), now += 600L));
        }
        held.remove(ItemID.TOTS_CHARGED);
        held.put(ItemID.TOTS_UNCHARGED, 1L);
        held.put(ItemID.DEATHRUNE, 100L);
        engine.yz();
        Ac result = null;
        for (int i = 0; i < 3; i++)
        {
            tick[0]++;
            intake.watchReturn();
            Ac settled = engine.adj(new Cc(new HashMap<>(held)), now += 600L);
            if (settled != null)
            {
                result = settled;
            }
        }
        assertNotNull(result);
        assertEquals("a slow confirm is still a transfer, not income", Ai.TRANSFER, result.getType());
        assertEquals(0L, engine.getMetrics(now).net);
    }

    /** A client whose tick and inventory the test drives. */
    private static net.runelite.api.Client client(int[] tick, Map<Integer, Long> held)
    {
        net.runelite.api.ItemContainer inventory = (net.runelite.api.ItemContainer) Proxy.newProxyInstance(
            net.runelite.api.ItemContainer.class.getClassLoader(), new Class<?>[] {net.runelite.api.ItemContainer.class},
            (proxy, method, args) ->
            {
                if ("getItems".equals(method.getName()))
                {
                    return held.entrySet().stream()
                        .map(e -> new net.runelite.api.Item(e.getKey(), e.getValue().intValue()))
                        .toArray(net.runelite.api.Item[]::new);
                }
                return method.getReturnType() == int.class ? 0 : null;
            });
        return (net.runelite.api.Client) Proxy.newProxyInstance(net.runelite.api.Client.class.getClassLoader(),
            new Class<?>[] {net.runelite.api.Client.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getTickCount": return tick[0];
                    case "getItemContainer": return inventory;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
    }

    private static Am engine(GpManagerConfig config)
    {
        Map<Integer, String> names = new HashMap<>();
        names.put(ItemID.TOTS_CHARGED, "Trident of the seas (full)");
        names.put(ItemID.TOTS_UNCHARGED, "Trident of the seas");
        names.put(ItemID.DEATHRUNE, "Death rune");
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int unit = delta.getKey() == ItemID.DEATHRUNE ? 200 : 1_000_000;
                flows.add(new Ab(delta.getKey(), names.get(delta.getKey()), delta.getValue(), unit,
                    delta.getValue() * unit, Av.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static MenuOptionClicked click(int itemId)
    {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getOption": return "Uncharge";
                    case "getTarget": return "Trident of the seas";
                    case "getType": return MenuAction.CC_OP;
                    case "getItemId": return itemId;
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
