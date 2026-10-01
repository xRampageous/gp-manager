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
        + ItemID.TOXIC_BLOWPIPE_LOADED + ":" + ChargeRead.Variant.V1b.name();

    @Test
    public void scalesUsedOnTheBlowpipeArmTheLoad()
    {
        Engine engine = engine();
        Widget blowpipe = item(ItemID.TOXIC_BLOWPIPE_LOADED, BLOWPIPE_SLOT);
        Widget scales = item(ItemID.SNAKEBOSS_SCALE, SCALES_SLOT);
        intake(selecting(scales), engine).armChargeLoadTransfer(
            click(MenuAction.WIDGET_TARGET_ON_WIDGET, "Use", blowpipe), "");
        assertEquals(ChargeRead.Variant.V1b, engine.chargeLoadTransferEvidence.variant);
        assertEquals("the item in hand is the source", ItemID.SNAKEBOSS_SCALE,
            engine.chargeLoadTransferEvidence.selectedItemId);
        assertEquals("the weapon's own slot", BLOWPIPE_IDENTITY, engine.chargeLoadTransferEvidence.targetIdentity);
    }

    @Test
    public void theBlowpipeUsedOnScalesArmsTheSameLoad()
    {
        Engine engine = engine();
        Widget blowpipe = item(ItemID.TOXIC_BLOWPIPE_LOADED, BLOWPIPE_SLOT);
        Widget scales = item(ItemID.SNAKEBOSS_SCALE, SCALES_SLOT);
        intake(selecting(blowpipe), engine).armChargeLoadTransfer(
            click(MenuAction.WIDGET_TARGET_ON_WIDGET, "Use", scales), "");
        assertEquals(ChargeRead.Variant.V1b, engine.chargeLoadTransferEvidence.variant);
        assertEquals(ItemID.SNAKEBOSS_SCALE, engine.chargeLoadTransferEvidence.selectedItemId);
        assertEquals("the identity follows the weapon, not the clicked scales", BLOWPIPE_IDENTITY,
            engine.chargeLoadTransferEvidence.targetIdentity);
    }

    @Test
    public void anUnrelatedItemOrPlainClickArmsNothing()
    {
        Engine engine = engine();
        Widget blowpipe = item(ItemID.TOXIC_BLOWPIPE_LOADED, BLOWPIPE_SLOT);
        intake(selecting(item(ItemID.BIG_BONES, 2)), engine).armChargeLoadTransfer(
            click(MenuAction.WIDGET_TARGET_ON_WIDGET, "Use", blowpipe), "");
        assertNull(engine.chargeLoadTransferEvidence.variant);
        intake(selecting(item(ItemID.SNAKEBOSS_SCALE, SCALES_SLOT)), engine).armChargeLoadTransfer(
            click(MenuAction.CC_OP, "Check", blowpipe), "");
        assertNull("only a use-on click arms a load", engine.chargeLoadTransferEvidence.variant);
    }

    @Test
    public void anyOtherActionEndsTheUnchargeWatchButAnsweringThePromptKeepsIt()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        ChargeIntake intake = intake(selecting(null), engine);
        Widget trident = item(ItemID.TOTS_CHARGED, 0);
        intake.onMenuOptionClicked(click(MenuAction.CC_OP, "uncharge", trident), "uncharge", "trident of the seas");
        assertEquals(ChargeRead.Variant.TRIDENT_SEAS, intake.returnVariant);
        intake.onMenuOptionClicked(click(MenuAction.WIDGET_CONTINUE, "continue", null), "continue", "");
        assertEquals("answering the prompt keeps watching", ChargeRead.Variant.TRIDENT_SEAS, intake.returnVariant);
        intake.onMenuOptionClicked(click(MenuAction.WALK, "walk here", null), "walk here", "");
        assertNull("walking away ends it, so later runes stay loot", intake.returnVariant);
    }
}
