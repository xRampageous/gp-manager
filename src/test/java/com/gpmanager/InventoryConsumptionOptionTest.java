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
            assertTrue(option, Dw.wy(option));
        }
        assertFalse(Dw.wy("cook"));
        assertTrue(Dw.xi("light"));
        assertFalse(Dw.xi("cook"));
    }

    @Test
    public void ignoresDropUseAndBankishOptions()
    {
        for (String option : new String[] {"drop", "use", "wear", "wield", "deposit", ""})
        {
            assertFalse(option, Dw.wy(option));
        }
        assertFalse(Dw.wy(null));
    }

    @Test
    public void transformOptionsArmProductionBySkill()
    {
        assertEquals("Cooking", Dw.ajx("cook"));
        assertEquals("Smelting", Dw.ajx("smelt"));
        assertEquals("Herblore", Dw.ajx("clean"));
        assertEquals("Fletching", Dw.ajx("fletch"));
        assertEquals("", Dw.ajx("eat"));
        assertTrue(Dw.ww("use", "Tinderbox -> Willow logs"));
        assertFalse(Dw.ww("use", "Willow logs"));
        assertTrue(Dw.xt("use", "Dragon bones -> Altar"));
        assertTrue(Dw.xp("cast", "Sinister Offering"));
    }

    @Test
    public void recognisesHarvestGatherOptionsForActivityHints()
    {
        assertTrue(Dw.xb("pick"));
        assertTrue(Dw.xb("harvest"));
        assertTrue(Dw.xb("chop down"));
        assertTrue(Dw.xb("mine"));
        assertFalse(Dw.xb("rake"));
        assertEquals("Farming", Dw.ka("pick"));
        assertEquals("Woodcutting", Dw.ka("chop"));
        assertEquals("Mining", Dw.ka("mine"));
        assertEquals("Fishing", Dw.ka("fish"));
        assertEquals("General", Dw.ka("open"));
    }

    @Test
    public void recognisesLiveConsumptionChatMessages()
    {
        assertTrue(Dw.vy("you drink some of your prayer potion."));
        assertTrue(Dw.vy("you dig a hole and bury the bones."));
        assertTrue(Dw.vy("you eat the lobster."));
        assertTrue(Dw.vy("you scatter the ashes."));
        assertTrue(Dw.vy("the gods are very pleased with your offering."));
        assertFalse("bone-save keeps the bone: never a spend", Dw.vy("the dark lord spares your sacrifice but still rewards you."));
        assertTrue(Dw.vt("The Dark Lord spares your sacrifice."));
        assertFalse(Dw.vy("you pick a potato."));
        assertFalse(Dw.vy(""));
        assertFalse(Dw.vy(null));
    }
}
