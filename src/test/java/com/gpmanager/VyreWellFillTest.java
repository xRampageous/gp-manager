package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import org.junit.Test;

import static com.gpmanager.GpManagerPluginProbe.set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Owner live 2026-10-13: the Vyre well is refundable storage for scythe charges. Filling it
 * (announced by its own message box, "You fill the well with N charges.") moves the player's
 * vials and blood runes neutrally; the scythe's charge estimates book each use, never the load.
 */
public class VyreWellFillTest
{
    private static final long T0 = 1_000_000L;
    private static final int VIAL = 22446;
    private static final int RUNE = 565;

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 2;
        }
    };

    private static Engine engine()
    {
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) ->
                flows.add(new Flow(id, "Item " + id, quantity, 100, quantity * 100)));
            return flows;
        }, new TransactionClassifier(), CONFIG);
        engine.ensureSession(T0);
        return engine;
    }

    private static void fill(Engine engine) throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        set(plugin, "config", CONFIG);
        set(plugin, "engine", engine);
        ChatMessage message = new ChatMessage();
        message.setType(ChatMessageType.MESBOX);
        message.setMessage("You fill the well with 10 charges.");
        plugin.onChatMessage(message);
    }

    private static Transaction deposit(Engine engine)
    {
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(VIAL, 10L, RUNE, 2_000L))));
        engine.markDirty();
        Transaction row = null;
        for (long tick = 1; row == null && tick <= 5; tick++)
            row = engine.processIfDirty(new ContainerSnapshot(new HashMap<>()), T0 + tick * 600L, null);
        return row;
    }

    @Test
    public void theFillLineKeepsTheDepositNeutral() throws Exception
    {
        Engine engine = engine();
        fill(engine);
        Transaction row = deposit(engine);
        assertNotNull(row);
        assertEquals(0L, engine.getMetrics(T0 + 10_000L).net);
        assertTrue("nothing counted for a refundable deposit",
            engine.getActiveSession().getTransactions().stream().noneMatch(Transaction::isCounted));
    }

    @Test
    public void withoutTheLineTheDepositBooks() throws Exception
    {
        Engine engine = engine();
        Transaction row = deposit(engine);
        assertNotNull(row);
        assertEquals(-(10L * 100L + 2_000L * 100L), engine.getMetrics(T0 + 10_000L).net);
    }

    @Test
    public void theEmptyOptionKeepsTheReturnNeutral() throws Exception
    {
        Engine engine = engine();
        empty(engine);
        Transaction row = refund(engine);
        assertNotNull(row);
        assertEquals(0L, engine.getMetrics(T0 + 10_000L).net);
        assertTrue("storage coming back is never income",
            engine.getActiveSession().getTransactions().stream().noneMatch(Transaction::isCounted));
    }

    @Test
    public void withoutTheEmptyClickTheReturnBooks() throws Exception
    {
        Engine engine = engine();
        Transaction row = refund(engine);
        assertNotNull(row);
        assertEquals(10L * 100L + 2_000L * 100L, engine.getMetrics(T0 + 10_000L).net);
    }

    private static void empty(Engine engine) throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        set(plugin, "config", CONFIG);
        set(plugin, "engine", engine);
        set(plugin, "interactionContextTracker", GpManagerPluginProbe.tracker(null, engine, CONFIG));
        set(plugin, "charges", new ChargeIntake(null, null, CONFIG, engine, null));
        plugin.onMenuOptionClicked(wellClick("Empty"));
    }

    private static Transaction refund(Engine engine)
    {
        engine.setBaseline(new ContainerSnapshot(new HashMap<>()));
        engine.markDirty();
        Transaction row = null;
        for (long tick = 1; row == null && tick <= 5; tick++)
            row = engine.processIfDirty(
                new ContainerSnapshot(new HashMap<>(Map.of(VIAL, 10L, RUNE, 2_000L))), T0 + tick * 600L, null);
        return row;
    }

    private static MenuOptionClicked wellClick(String option)
    {
        MenuEntry entry = (MenuEntry) java.lang.reflect.Proxy.newProxyInstance(
            MenuEntry.class.getClassLoader(), new Class<?>[] {MenuEntry.class},
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getOption":
                        return option;
                    case "getTarget":
                        return "Vyre well";
                    case "getType":
                        return MenuAction.GAME_OBJECT_FOURTH_OPTION;
                    default:
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) return false;
                        if (type == int.class) return 0;
                        if (type == long.class) return 0L;
                        return null;
                }
            });
        return new MenuOptionClicked(entry);
    }

}
