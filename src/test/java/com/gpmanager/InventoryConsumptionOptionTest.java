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
            assertTrue(option, ActionSignals.isSpend(option));
        }
        assertFalse(ActionSignals.isSpend("cook"));
        assertTrue(ActionSignals.isSpendName("light"));
        assertFalse(ActionSignals.isSpendName("cook"));
    }

    @Test
    public void ignoresDropUseAndBankishOptions()
    {
        for (String option : new String[] {"drop", "use", "wear", "wield", "deposit", ""})
        {
            assertFalse(option, ActionSignals.isSpend(option));
        }
        assertFalse(ActionSignals.isSpend(null));
    }

    @Test
    public void transformOptionsArmProductionBySkill()
    {
        assertEquals("Cooking", ActionSignals.transformSkill("cook"));
        assertEquals("Smelting", ActionSignals.transformSkill("smelt"));
        assertEquals("Herblore", ActionSignals.transformSkill("clean"));
        assertEquals("Fletching", ActionSignals.transformSkill("fletch"));
        assertEquals("", ActionSignals.transformSkill("eat"));
        assertTrue(ActionSignals.lightsFire("use", "Tinderbox -> Willow logs"));
        assertFalse(ActionSignals.lightsFire("use", "Willow logs"));
        assertTrue(ActionSignals.offersBones("use", "Dragon bones -> Altar"));
        assertTrue(ActionSignals.isOffering("cast", "Sinister Offering"));
    }

    @Test
    public void recognisesHarvestGatherOptionsForActivityHints()
    {
        assertTrue(ActionSignals.namesActivityOnly("pick"));
        assertTrue(ActionSignals.namesActivityOnly("harvest"));
        assertTrue(ActionSignals.namesActivityOnly("chop down"));
        assertTrue(ActionSignals.namesActivityOnly("mine"));
        assertFalse(ActionSignals.namesActivityOnly("rake"));
        assertEquals("Farming", ActionSignals.gatherActivity("pick"));
        assertEquals("Woodcutting", ActionSignals.gatherActivity("chop"));
        assertEquals("Mining", ActionSignals.gatherActivity("mine"));
        assertEquals("Fishing", ActionSignals.gatherActivity("fish"));
        assertEquals("General", ActionSignals.gatherActivity("open"));
    }

    @Test
    public void recognisesLiveConsumptionChatMessages()
    {
        assertTrue(ActionSignals.isChatSpend("you drink some of your prayer potion."));
        assertTrue(ActionSignals.isChatSpend("you dig a hole and bury the bones."));
        assertTrue(ActionSignals.isChatSpend("you eat the lobster."));
        assertTrue(ActionSignals.isChatSpend("you scatter the ashes."));
        assertTrue(ActionSignals.isChatSpend("the gods are very pleased with your offering."));
        assertFalse("bone-save keeps the bone: never a spend", ActionSignals.isChatSpend("the dark lord spares your sacrifice but still rewards you."));
        assertTrue(ActionSignals.isSpared("The Dark Lord spares your sacrifice."));
        assertFalse(ActionSignals.isChatSpend("you pick a potato."));
        assertFalse(ActionSignals.isChatSpend(""));
        assertFalse(ActionSignals.isChatSpend(null));
    }
}
