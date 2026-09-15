package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Menu-option gate for Eat/Bury/Drink/Cast/etc. consumption intents and gather hints. */
public class InventoryConsumptionOptionTest
{
    @Test
    public void recognisesSupportedInventoryConsumeOptions()
    {
        assertTrue(GpManagerPlugin.isInventoryConsumptionOption("bury"));
        assertTrue(GpManagerPlugin.isInventoryConsumptionOption("scatter"));
        assertTrue(GpManagerPlugin.isInventoryConsumptionOption("eat"));
        assertTrue(GpManagerPlugin.isInventoryConsumptionOption("drink"));
        assertTrue(GpManagerPlugin.isInventoryConsumptionOption("empty"));
        assertTrue(GpManagerPlugin.isInventoryConsumptionOption("break"));
        assertTrue(GpManagerPlugin.isInventoryConsumptionOption("cast"));
        assertTrue(GpManagerPlugin.isInventoryConsumptionOption("release"));
        assertTrue(GpManagerPlugin.isInventoryConsumptionOption("eat 1"));
        assertTrue(GpManagerPlugin.isInventoryConsumptionOption("drink 1"));
        assertTrue(GpManagerPlugin.isInventoryConsumptionOption("light"));
        assertFalse(GpManagerPlugin.isInventoryConsumptionOption("cook"));
        assertTrue(GpManagerPlugin.isLossOnlyProcessSpendOption("light"));
        assertFalse(GpManagerPlugin.isLossOnlyProcessSpendOption("cook"));
        assertEquals("Firemaking", GpManagerPlugin.processSpendSkillForOption("light"));
        assertEquals("Firemaking", GpManagerPlugin.processSpendSkillForOption("tend-to"));
        assertTrue(GpManagerPlugin.isLossOnlyProcessSpendOption("tend-to"));
        assertEquals("Prayer", GpManagerPlugin.processSpendSkillForOption("bury"));
        assertTrue(com.gpmanager.ui.HudPlusProcessLabels.isTransformProductionOption("cook"));
        assertTrue(com.gpmanager.ui.HudPlusProcessLabels.isTransformProductionOption("fletch"));
        assertEquals("Cooking", com.gpmanager.ui.HudPlusProcessLabels.transformSkillForOption("cook"));
        assertEquals("Fletching", com.gpmanager.ui.HudPlusProcessLabels.transformSkillForOption("fletch"));
        assertEquals("Crafting", com.gpmanager.ui.HudPlusProcessLabels.transformSkillForOption("spin"));
        assertEquals("Firemaking",
            com.gpmanager.ui.HudPlusProcessLabels.gerundFromMenuOption("tend-to"));
    }

    @Test
    public void ignoresDropUseAndBankishOptions()
    {
        assertFalse(GpManagerPlugin.isInventoryConsumptionOption("drop"));
        assertFalse(GpManagerPlugin.isInventoryConsumptionOption("use"));
        assertFalse(GpManagerPlugin.isInventoryConsumptionOption("wear"));
        assertFalse(GpManagerPlugin.isInventoryConsumptionOption("wield"));
        assertFalse(GpManagerPlugin.isInventoryConsumptionOption("deposit"));
        assertFalse(GpManagerPlugin.isInventoryConsumptionOption(""));
        assertFalse(GpManagerPlugin.isInventoryConsumptionOption(null));
    }

    @Test
    public void recognisesHarvestGatherOptionsForActivityHints()
    {
        assertTrue(GpManagerPlugin.isHarvestGatherOption("pick"));
        assertTrue(GpManagerPlugin.isHarvestGatherOption("harvest"));
        assertTrue(GpManagerPlugin.isHarvestGatherOption("chop down"));
        assertTrue(GpManagerPlugin.isHarvestGatherOption("mine"));
        assertEquals("Farming", GpManagerPlugin.activityHintForGatherOption("pick"));
        assertEquals("Farming", GpManagerPlugin.activityHintForGatherOption("harvest"));
        assertEquals("Woodcutting", GpManagerPlugin.activityHintForGatherOption("chop"));
        assertEquals("Mining", GpManagerPlugin.activityHintForGatherOption("mine"));
        assertEquals("Fishing", GpManagerPlugin.activityHintForGatherOption("fish"));
    }

    @Test
    public void recognisesLiveConsumptionChatMessages()
    {
        assertTrue(GpManagerPlugin.isConsumptionChatMessage("you drink some of your energy potion."));
        assertTrue(GpManagerPlugin.isConsumptionChatMessage("you bury the bones."));
        assertTrue(GpManagerPlugin.isConsumptionChatMessage("you eat the lobster."));
        assertTrue(GpManagerPlugin.isConsumptionChatMessage("you eat the potato. yuck!"));
        assertTrue(GpManagerPlugin.isConsumptionChatMessage("you scatter the ashes."));
        // Live bury often prints dig+bury rather than "You bury …" alone.
        assertTrue(GpManagerPlugin.isConsumptionChatMessage(
            "you dig a hole and bury the bones."));
        assertTrue(GpManagerPlugin.isConsumptionChatMessage(
            "you have 1 dose of potion left."));
        // OSRS PoH gilded altar — https://oldschool.runescape.wiki/w/Gilded_altar
        assertTrue(GpManagerPlugin.isConsumptionChatMessage(
            "The gods are pleased with your offering"));
        assertTrue(GpManagerPlugin.isConsumptionChatMessage(
            "The gods are very pleased with your offering"));
        // OSRS Chaos Altar bone-save — must NOT reinforce consume (invent may keep bone)
        // https://oldschool.runescape.wiki/w/Chaos_Temple_(church)
        assertFalse(GpManagerPlugin.isConsumptionChatMessage(
            "The Dark Lord spares your sacrifice but still rewards you for your efforts."));
        assertFalse(GpManagerPlugin.isConsumptionChatMessage("you pick a potato."));
        assertFalse(GpManagerPlugin.isConsumptionChatMessage("you dig a hole in the ground..."));
        assertFalse(GpManagerPlugin.isConsumptionChatMessage(""));
        assertFalse(GpManagerPlugin.isConsumptionChatMessage(null));
    }

    @Test
    public void chatSkillFailsafeMapsLightAndCookMessages()
    {
        assertEquals("Firemaking",
            GpManagerPlugin.skillActivityFromChatMessage("You light the logs."));
        assertEquals("Firemaking",
            GpManagerPlugin.skillActivityFromChatMessage("The fire catches and the logs begin to burn."));
        assertEquals("Cooking",
            GpManagerPlugin.skillActivityFromChatMessage("You successfully cook a shark."));
        assertEquals("Cooking",
            GpManagerPlugin.skillActivityFromChatMessage("You accidentally burn the shark."));
        assertEquals("Prayer",
            GpManagerPlugin.skillActivityFromChatMessage("You bury the bones."));
        assertEquals("Smelting",
            GpManagerPlugin.skillActivityFromChatMessage("You smelt the iron ore in the furnace."));
        assertEquals("Cooking",
            GpManagerPlugin.skillActivityFromChatMessage("How many would you like to cook?"));
        assertEquals("Firemaking",
            GpManagerPlugin.skillActivityFromChatMessage("How many would you like to burn?"));
        assertEquals("",
            GpManagerPlugin.skillActivityFromChatMessage("You pick a potato."));
        assertEquals("",
            GpManagerPlugin.skillActivityFromChatMessage(null));
    }
}
