package com.gpmanager;

import net.runelite.api.MenuAction;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Owner 1.5.0: only playing lifts AFK pause; browsing tabs and panels does not. */
public class AfkResumeTest
{
    @Test
    public void worldClicksAndItemUsesResume()
    {
        for (MenuAction action : new MenuAction[] {MenuAction.WALK, MenuAction.NPC_FIRST_OPTION,
            MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GROUND_ITEM_THIRD_OPTION,
            MenuAction.PLAYER_FIRST_OPTION, MenuAction.WIDGET_TARGET_ON_NPC,
            MenuAction.WIDGET_TARGET_ON_WIDGET})
        {
            assertTrue(action.name(), GpManagerPlugin.worldAction(action, false));
        }
        assertTrue("eating or wielding from the inventory", GpManagerPlugin.worldAction(MenuAction.CC_OP, true));
    }

    @Test
    public void tabsAndPanelsDoNot()
    {
        assertFalse("Friends, Skills or the Inventory tab", GpManagerPlugin.worldAction(MenuAction.CC_OP, false));
        assertFalse(GpManagerPlugin.worldAction(MenuAction.EXAMINE_NPC, false));
        assertFalse(GpManagerPlugin.worldAction(MenuAction.CANCEL, false));
        assertFalse(GpManagerPlugin.worldAction(null, false));
    }
}
