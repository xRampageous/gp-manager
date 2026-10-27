package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * A widget-target click carries the target as the clicked widget and the item in hand as the
 * client's selected widget. A load arms in either order, against the weapon's own slot.
 */
public class ChargeLoadArmingTest
{
    private static final int BLOWPIPE_SLOT = 5;
    private static final int SCALES_SLOT = 9;

    private static Widget item(int itemId, int slot)
    {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[] {Widget.class},
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getItemId": return itemId;
                    case "getIndex": return slot;
                    case "getId": return InterfaceID.Inventory.ITEMS;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                }
            });
    }

    private static Client selecting(Widget selected)
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getSelectedWidget": return selected;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
    }

    static MenuOptionClicked click(MenuAction action, String option, Widget clicked)
    {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getOption": return option;
                    case "getTarget": return "";
                    case "getType": return action;
                    case "getWidget": return clicked;
                    case "getItemId": return clicked == null ? -1 : clicked.getItemId();
                    case "getParam0": return clicked == null ? -1 : clicked.getIndex();
                    case "getParam1": return clicked == null ? -1 : clicked.getId();
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
        return new MenuOptionClicked(entry);
    }

    private static Engine engine()
    {
        return new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
    }

    private static ChargeIntake intake(Client client, Engine engine)
    {
        return new ChargeIntake(client, null, new GpManagerConfig() {}, engine, null)
        {
            @Override
            String itemName(int itemId)
            {
                return itemId == ItemID.SNAKEBOSS_SCALE ? "Zulrah's scales" : null;
            }
        };
    }

    private static final String BLOWPIPE_IDENTITY = (InterfaceID.Inventory.ITEMS >>> 16) + ":" + BLOWPIPE_SLOT + ":"
        + ItemID.TOXIC_BLOWPIPE_LOADED + ":" + ChargeCheck.ChargedItem.BLOWPIPE.name();

    @Test
    public void scalesUsedOnTheBlowpipeArmTheLoad()
    {
        Engine engine = engine();
        Widget blowpipe = item(ItemID.TOXIC_BLOWPIPE_LOADED, BLOWPIPE_SLOT);
        Widget scales = item(ItemID.SNAKEBOSS_SCALE, SCALES_SLOT);
        intake(selecting(scales), engine).armLoad(
            click(MenuAction.WIDGET_TARGET_ON_WIDGET, "Use", blowpipe), "");
        assertEquals(ChargeCheck.ChargedItem.BLOWPIPE, (engine.events.peek(Inbox.LOAD_TARGET) == null ? null : engine.events.peek(Inbox.LOAD_TARGET).variant));
        assertEquals("the item in hand is the source", ItemID.SNAKEBOSS_SCALE,
            engine.events.item(Inbox.LOAD_TARGET));
        assertEquals("the weapon's own slot", BLOWPIPE_IDENTITY, (engine.events.peek(Inbox.LOAD_TARGET) == null ? null : engine.events.peek(Inbox.LOAD_TARGET).id));
    }

    @Test
    public void theBlowpipeUsedOnScalesArmsTheSameLoad()
    {
        Engine engine = engine();
        Widget blowpipe = item(ItemID.TOXIC_BLOWPIPE_LOADED, BLOWPIPE_SLOT);
        Widget scales = item(ItemID.SNAKEBOSS_SCALE, SCALES_SLOT);
        intake(selecting(blowpipe), engine).armLoad(
            click(MenuAction.WIDGET_TARGET_ON_WIDGET, "Use", scales), "");
        assertEquals(ChargeCheck.ChargedItem.BLOWPIPE, (engine.events.peek(Inbox.LOAD_TARGET) == null ? null : engine.events.peek(Inbox.LOAD_TARGET).variant));
        assertEquals(ItemID.SNAKEBOSS_SCALE, engine.events.item(Inbox.LOAD_TARGET));
        assertEquals("the identity follows the weapon, not the clicked scales", BLOWPIPE_IDENTITY,
            (engine.events.peek(Inbox.LOAD_TARGET) == null ? null : engine.events.peek(Inbox.LOAD_TARGET).id));
    }

    @Test
    public void anUnrelatedItemOrPlainClickArmsNothing()
    {
        Engine engine = engine();
        Widget blowpipe = item(ItemID.TOXIC_BLOWPIPE_LOADED, BLOWPIPE_SLOT);
        intake(selecting(item(ItemID.BIG_BONES, 2)), engine).armLoad(
            click(MenuAction.WIDGET_TARGET_ON_WIDGET, "Use", blowpipe), "");
        assertNull((engine.events.peek(Inbox.LOAD_TARGET) == null ? null : engine.events.peek(Inbox.LOAD_TARGET).variant));
        intake(selecting(item(ItemID.SNAKEBOSS_SCALE, SCALES_SLOT)), engine).armLoad(
            click(MenuAction.CC_OP, "Check", blowpipe), "");
        assertNull("only a use-on click arms a load", (engine.events.peek(Inbox.LOAD_TARGET) == null ? null : engine.events.peek(Inbox.LOAD_TARGET).variant));
    }

    @Test
    public void anyOtherActionEndsTheUnchargeWatchButAnsweringThePromptKeepsIt()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        ChargeIntake intake = intake(selecting(null), engine);
        Widget trident = item(ItemID.TOTS_CHARGED, 0);
        intake.onMenuOptionClicked(click(MenuAction.CC_OP, "uncharge", trident), "uncharge", "trident of the seas");
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS, (intake.engine.events.peek(Inbox.UNCHARGE) == null ? null : intake.engine.events.peek(Inbox.UNCHARGE).variant));
        intake.onMenuOptionClicked(click(MenuAction.WIDGET_CONTINUE, "continue", null), "continue", "");
        assertEquals("answering the prompt keeps watching", ChargeCheck.ChargedItem.TRIDENT_SEAS, (intake.engine.events.peek(Inbox.UNCHARGE) == null ? null : intake.engine.events.peek(Inbox.UNCHARGE).variant));
        intake.onMenuOptionClicked(click(MenuAction.WALK, "walk here", null), "walk here", "");
        assertNull("walking away ends it, so later runes stay loot", (intake.engine.events.peek(Inbox.UNCHARGE) == null ? null : intake.engine.events.peek(Inbox.UNCHARGE).variant));
    }

    @Test
    public void aNotedRecoilLoadCanonicalisesToTheRingOfRecoil()
    {
        // The live menu carries the noted id (owner recording 2026-10-12); the intake
        // canonicalises it before validation, so the load arms as the ring of recoil.
        Engine engine = engine();
        Widget ring = item(ItemID.ZENYTE_RING_ENCHANTED_RECOIL, 0);
        ChargeIntake intake = new ChargeIntake(selecting(item(2551, 2)), canonicalising(),
            new GpManagerConfig() {}, engine, null)
        {
            @Override
            String itemName(int itemId)
            {
                return itemId == ItemID.RING_OF_RECOIL ? "Ring of recoil" : null;
            }
        };
        intake.armLoad(click(MenuAction.WIDGET_TARGET_ON_WIDGET, "Use", ring), "");
        assertEquals(ChargeCheck.ChargedItem.SUFFERING,
            (engine.events.peek(Inbox.LOAD_TARGET) == null ? null : engine.events.peek(Inbox.LOAD_TARGET).variant));
        assertEquals("the noted id canonicalises to the ring of recoil",
            ItemID.RING_OF_RECOIL, engine.events.item(Inbox.LOAD_TARGET));
    }

    /** A lookup that canonicalises the noted recoil the way RuneLite's ItemManager does. */
    private static ItemLookup canonicalising()
    {
        return new ItemLookup(null)
        {
            @Override
            int canon(int id)
            {
                return id == 2551 ? ItemID.RING_OF_RECOIL : id;
            }
        };
    }
}
