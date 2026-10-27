package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pouch controls must resume Idle before a split transfer becomes the ownership baseline. */
public class PouchIdleResumeTest {
    static final int RUNE = ItemID.LAWRUNE, FOOD = ItemID.SHARK;
    LongSupplier clock;

    @Before public void pinClock() { clock = SafeMath.clock; SafeMath.clock = () -> ReviewFixesTest.T0; }
    @After public void restoreClock() { SafeMath.clock = clock; }

    @Test public void custodyControlResumesBeforeEitherTransferHalf() {
        for (boolean itemOp : new boolean[]{false, true}) for (boolean pouchFirst : new boolean[]{false, true}) {
            Harness h = new Harness();
            h.click(InterfaceID.RUNE_POUCH, "Fill", itemOp);
            assertFalse("the control resumes before quantities change", h.engine.session().paused);
            if (pouchFirst) { h.pouch(10); h.inventory(RUNE, 0); }
            else { h.inventory(RUNE, 0); h.pouch(10); }
            h.settle();
            assertEquals(0, h.net());
            assertTrue("a transfer creates no financial row", h.engine.session().transactions().isEmpty());
        }
    }

    @Test public void laterRuneAndFoodSpendingRetainTheirFullCosts() {
        Harness h = new Harness();
        h.click(InterfaceID.RUNE_POUCH, "Fill", false);
        h.pouch(10); h.inventory(RUNE, 0); h.settle();
        h.engine.noteConsume(FOOD, 10, false, ActionKind.EAT, null);
        h.inventory(FOOD, 0); h.pouch(9); h.settle();
        assertEquals("one rune and one food at fixture prices", -200, h.net());
    }

    @Test public void manualPauseRemainsHeld() {
        Harness h = new Harness();
        h.engine.session().resume(h.now); h.engine.togglePause(h.now);
        h.click(InterfaceID.RUNE_POUCH, "Fill", false);
        h.pouch(10); h.inventory(RUNE, 0); h.settle();
        assertTrue(h.engine.session().paused);
        assertEquals(PauseReason.MANUAL, h.engine.session().pauseReason());
        assertEquals(0, h.net());
    }

    @Test public void otherWidgetsAndNonCustodyControlsLeaveIdleHeld() {
        Harness h = new Harness();
        h.click(InterfaceID.RUNE_POUCH, "Examine", false);
        assertTrue(h.engine.isIdlePaused());
        h.click(InterfaceID.BANKMAIN, "Fill", false);
        assertTrue(h.engine.isIdlePaused());
    }

    static final class Harness {
        final Engine engine = ReviewFixesTest.engine();
        final GpManagerPlugin plugin = new GpManagerPluginProbe();
        final Map<Integer, Long> inventory = new HashMap<>();
        int tick, quantity;
        long now = ReviewFixesTest.T0;

        Harness() {
            inventory.put(RUNE, 10L); inventory.put(FOOD, 1L);
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getGameState": return GameState.LOGGED_IN;
                        case "getTickCount": return tick;
                        case "getVarbitValue": return (int) args[0] == VarbitID.RUNE_POUCH_TYPE_1 ? 1
                            : (int) args[0] == VarbitID.RUNE_POUCH_QUANTITY_1 ? quantity : 0;
                        case "getItemContainer": return (int) args[0] == InventoryID.INV ? container() : null;
                        case "getEnum": return ReviewFixesTest.proxy(EnumComposition.class, Map.of("getIntValue", RUNE));
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                    }
                });
            plugin.engine = engine; plugin.config = ReviewFixesTest.CONFIG; plugin.client = client;
            plugin.containers = new ContainerSnapshotFactory(client, null);
            plugin.targets = GpManagerPluginProbe.tracker(client, engine, plugin.config);
            plugin.charges = new ChargeIntake(client, null, plugin.config, engine, null);
            plugin.activity = new ActivityDetector(plugin.config, engine);
            engine.setBaseline(plugin.containers.capture(false, true)); engine.pauseIdle(now);
        }

        ItemContainer container() {
            Item[] items = inventory.entrySet().stream().map(e -> new Item(e.getKey(), e.getValue().intValue())).toArray(Item[]::new);
            return ReviewFixesTest.proxy(ItemContainer.class, Map.of("getItems", items));
        }

        void click(int group, String option, boolean itemOp) {
            MenuEntry entry = ReviewFixesTest.proxy(MenuEntry.class, Map.of("getOption", option, "getTarget", "Law rune",
                "getType", MenuAction.CC_OP, "isItemOp", itemOp, "getParam1", group << 16 | 7, "getItemId", ItemID.BH_RUNE_POUCH));
            plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
        }

        void inventory(int id, long amount) {
            if (amount > 0) inventory.put(id, amount); else inventory.remove(id);
            plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, container()));
        }

        void pouch(int amount) {
            quantity = amount;
            VarbitChanged event = new VarbitChanged(); event.setVarbitId(VarbitID.RUNE_POUCH_QUANTITY_1);
            plugin.onVarbitChanged(event);
        }

        void settle() {
            for (int i = 0; i < 5; i++) {
                tick++; now += 600; engine.processIfDirty(plugin.containers.capture(false, true), now, null);
            }
        }

        long net() { return engine.metrics(now).net; }
    }
}
