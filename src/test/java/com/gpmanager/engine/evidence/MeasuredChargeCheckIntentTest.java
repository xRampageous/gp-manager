package com.gpmanager;

import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class MeasuredChargeCheckIntentTest
{
    @Test
    public void exactChatReadConsumesOnlyItsFreshMenuTargetOnce()
    {
        ChargeIntake intent = new ChargeIntake(null, null, ReviewFixesTest.CONFIG, ReviewFixesTest.engine(), null);
        assertTrue(intent.startCheck(ChargeCheck.ChargedItem.TRIDENT_SEAS, "inventory:3:11907", 100, 2));
        ChargeCheck read = ChargeCheck.parse(
            "Your Trident of the seas has 1,234 charges.");

        assertEquals("inventory:3:11907", intent.resolveCheck(read, 101));
        assertNull(intent.resolveCheck(read, 101));
    }

    @Test
    public void wrongVariantMissingTargetAndExpiredTargetFailClosed()
    {
        ChargeIntake intent = new ChargeIntake(null, null, ReviewFixesTest.CONFIG, ReviewFixesTest.engine(), null);
        assertTrue(intent.startCheck(ChargeCheck.ChargedItem.TRIDENT_SEAS, "inventory:3", 100, 2));
        assertNull(intent.resolveCheck(ChargeCheck.parse(
            "Your Trident of the swamp has 1 charge."), 101));

        assertTrue(intent.startCheck(ChargeCheck.ChargedItem.TRIDENT_SEAS, "inventory:3", 100, 2));
        assertNull(intent.resolveCheck(ChargeCheck.parse(
            "Your Trident of the seas has 1 charge."), 103));

        assertFalse(intent.startCheck(ChargeCheck.ChargedItem.TRIDENT_SEAS, " ", 100, 2));
        assertNull(intent.resolveCheck(ChargeCheck.parse(
            "Your Trident of the seas has 1 charge."), 101));
    }

    @Test
    public void aCommittedLoadPieceArmsNoCheckIntent()
    {
        Engine engine = ReviewFixesTest.engine();
        ChargeIntake intent = new ChargeIntake(
            wornClient(EquipmentInventorySlot.SHIELD.getSlotIdx(), ItemID.CRYSTAL_SHIELD, 100),
            null, ReviewFixesTest.CONFIG, engine, null);
        intent.armCheck(wornCheckClick(ItemID.CRYSTAL_SHIELD), "check", "Crystal shield");
        assertNull(
            "a committed-load piece has no Check comparison",
            intent.resolveCheck(
                ChargeCheck.parse("Your crystal shield has 2,470 charges remaining."), 101));
        assertTrue(
            "the Check read is consumed",
            intent.observeCheck("Your crystal shield has 2,470 charges remaining.", 1_000L));
        assertTrue(
            "a Check on a committed-load piece books nothing",
            engine.getActiveSession().getTransactions().isEmpty());
    }

    @Test
    public void aWornWeaponCheckKeepsTheEstimateIdentity()
    {
        ChargeIntake intent = new ChargeIntake(
            wornClient(EquipmentInventorySlot.WEAPON.getSlotIdx(), ItemID.SCYTHE_OF_VITUR, 100),
            null, ReviewFixesTest.CONFIG, ReviewFixesTest.engine(), null);
        intent.armCheck(
            wornCheckClick(ItemID.SCYTHE_OF_VITUR, "Scythe of Vitur"), "check", "Scythe of Vitur");
        assertEquals(
            "the worn check reconciles against the hit estimates",
            ChargeIntake.wornIdentity(ChargeCheck.ChargedItem.SCYTHE, ItemID.SCYTHE_OF_VITUR),
            intent.resolveCheck(
                ChargeCheck.parse("Your Scythe of Vitur has 1,315 charges remaining."), 101));
    }

    private static Client wornClient(int slotIndex, int itemId, int tick)
    {
        Item[] slots = new Item[14];
        slots[slotIndex] = new Item(itemId, 1);
        ItemContainer container = (ItemContainer) Proxy.newProxyInstance(
            ItemContainer.class.getClassLoader(), new Class<?>[] {ItemContainer.class},
            (proxy, method, args) ->
            {
                if ("getItem".equals(method.getName()))
                {
                    int slot = (int) args[0];
                    return slot >= 0 && slot < slots.length ? slots[slot] : null;
                }
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                return null;
            });
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[] {Client.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getTickCount":
                        return tick;
                    case "getItemContainer":
                        return container;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
    }

    private static MenuOptionClicked wornCheckClick(int itemId)
    {
        return wornCheckClick(itemId, "Crystal shield");
    }

    /** The exact live click shape: worn tab, param0 -1, param1 the equipment-stat widget. */
    private static MenuOptionClicked wornCheckClick(int itemId, String target)
    {
        Widget widget = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
            new Class<?>[] {Widget.class}, (proxy, method, args) ->
            {
                if ("getItemId".equals(method.getName())) return itemId;
                if ("getId".equals(method.getName())) return 25362452;
                return null;
            });
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getOption":
                        return "Check";
                    case "getTarget":
                        return target;
                    case "getType":
                        return MenuAction.CC_OP;
                    case "getParam0":
                        return -1;
                    case "getParam1":
                        return 25362452;
                    case "getItemId":
                        return -1;
                    case "getWidget":
                        return widget;
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
