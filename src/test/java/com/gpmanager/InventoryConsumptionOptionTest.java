package com.gpmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Menu/chat vocabulary that becomes an accounting intent; anything else stays ambiguous. */
public class InventoryConsumptionOptionTest
{
    @Test
    public void recognisesSupportedInventoryConsumeOptions()
    {
        for (String option : new String[] {"bury", "scatter", "eat", "drink", "empty", "break", "cast", "release", "eat 1", "drink 1", "light", "offer", "tend-to"})
        {
            assertTrue(option, ActionSignals.isInventoryConsumptionOption(option));
        }
        assertFalse(ActionSignals.isInventoryConsumptionOption("cook"));
        assertTrue(ActionSignals.isLossOnlySpendOption("light"));
        assertFalse(ActionSignals.isLossOnlySpendOption("cook"));
    }

    @Test
    public void ignoresDropUseAndBankishOptions()
    {
        for (String option : new String[] {"drop", "use", "wear", "wield", "deposit", ""})
        {
            assertFalse(option, ActionSignals.isInventoryConsumptionOption(option));
        }
        assertFalse(ActionSignals.isInventoryConsumptionOption(null));
    }

    @Test
    public void transformOptionsArmProductionBySkill()
    {
        assertEquals("Cooking", ActionSignals.transformSkillForOption("cook"));
        assertEquals("Smelting", ActionSignals.transformSkillForOption("smelt"));
        assertEquals("Herblore", ActionSignals.transformSkillForOption("clean"));
        assertEquals("Fletching", ActionSignals.transformSkillForOption("fletch"));
        assertEquals("", ActionSignals.transformSkillForOption("eat"));
        assertTrue(ActionSignals.isFiremakingUsePair("use", "Tinderbox -> Willow logs"));
        assertFalse(ActionSignals.isFiremakingUsePair("use", "Willow logs"));
        assertTrue(ActionSignals.isPrayerAltarUsePair("use", "Dragon bones -> Altar"));
        assertTrue(ActionSignals.xp("cast", "Sinister Offering"));
    }

    @Test
    public void recognisesHarvestGatherOptionsForActivityHints()
    {
        assertTrue(ActionSignals.isHarvestGatherOption("pick"));
        assertTrue(ActionSignals.isHarvestGatherOption("harvest"));
        assertTrue(ActionSignals.isHarvestGatherOption("chop down"));
        assertTrue(ActionSignals.isHarvestGatherOption("mine"));
        assertFalse(ActionSignals.isHarvestGatherOption("rake"));
        assertEquals("Farming", ActionSignals.activityHintForGatherOption("pick"));
        assertEquals("Woodcutting", ActionSignals.activityHintForGatherOption("chop"));
        assertEquals("Mining", ActionSignals.activityHintForGatherOption("mine"));
        assertEquals("Fishing", ActionSignals.activityHintForGatherOption("fish"));
        assertEquals("General", ActionSignals.activityHintForGatherOption("open"));
    }

    @Test
    public void recognisesLiveConsumptionChatMessages()
    {
        assertTrue(ActionSignals.isConsumptionChatMessage("you drink some of your prayer potion."));
        assertTrue(ActionSignals.isConsumptionChatMessage("you dig a hole and bury the bones."));
        assertTrue(ActionSignals.isConsumptionChatMessage("you eat the lobster."));
        assertTrue(ActionSignals.isConsumptionChatMessage("you scatter the ashes."));
        assertTrue(ActionSignals.isConsumptionChatMessage("the gods are very pleased with your offering."));
        assertFalse("bone-save keeps the bone: never a spend", ActionSignals.isConsumptionChatMessage("the dark lord spares your sacrifice but still rewards you."));
        assertTrue(ActionSignals.isChaosAltarBoneSaveChat("The Dark Lord spares your sacrifice."));
        assertFalse(ActionSignals.isConsumptionChatMessage("you pick a potato."));
        assertFalse(ActionSignals.isConsumptionChatMessage(""));
        assertFalse(ActionSignals.isConsumptionChatMessage(null));
    }
}
