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
            assertTrue(option, Dw.b1(option));
        }
        assertFalse(Dw.b1("cook"));
        assertTrue(Dw.c1("light"));
        assertFalse(Dw.c1("cook"));
    }

    @Test
    public void ignoresDropUseAndBankishOptions()
    {
        for (String option : new String[] {"drop", "use", "wear", "wield", "deposit", ""})
        {
            assertFalse(option, Dw.b1(option));
        }
        assertFalse(Dw.b1(null));
    }

    @Test
    public void transformOptionsArmProductionBySkill()
    {
        assertEquals("Cooking", Dw.ajx("cook"));
        assertEquals("Smelting", Dw.ajx("smelt"));
        assertEquals("Herblore", Dw.ajx("clean"));
        assertEquals("Fletching", Dw.ajx("fletch"));
        assertEquals("", Dw.ajx("eat"));
        assertTrue(Dw.d1("use", "Tinderbox -> Willow logs"));
        assertFalse(Dw.d1("use", "Willow logs"));
        assertTrue(Dw.e1("use", "Dragon bones -> Altar"));
        assertTrue(Dw.f1("cast", "Sinister Offering"));
    }

    @Test
    public void recognisesHarvestGatherOptionsForActivityHints()
    {
        assertTrue(Dw.i1("pick"));
        assertTrue(Dw.i1("harvest"));
        assertTrue(Dw.i1("chop down"));
        assertTrue(Dw.i1("mine"));
        assertFalse(Dw.i1("rake"));
        assertEquals("Farming", Dw.ka("pick"));
        assertEquals("Woodcutting", Dw.ka("chop"));
        assertEquals("Mining", Dw.ka("mine"));
        assertEquals("Fishing", Dw.ka("fish"));
        assertEquals("General", Dw.ka("open"));
    }

    @Test
    public void recognisesLiveConsumptionChatMessages()
    {
        assertTrue(Dw.h1("you drink some of your prayer potion."));
        assertTrue(Dw.h1("you dig a hole and bury the bones."));
        assertTrue(Dw.h1("you eat the lobster."));
        assertTrue(Dw.h1("you scatter the ashes."));
        assertTrue(Dw.h1("the gods are very pleased with your offering."));
        assertFalse("bone-save keeps the bone: never a spend", Dw.h1("the dark lord spares your sacrifice but still rewards you."));
        assertTrue(Dw.g1("The Dark Lord spares your sacrifice."));
        assertFalse(Dw.h1("you pick a potato."));
        assertFalse(Dw.h1(""));
        assertFalse(Dw.h1(null));
    }
}
