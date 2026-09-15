package com.gpmanager.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProcessSkillSignalsTest
{
    @Test
    public void makeXQuestionsMapToCanonicalSkills()
    {
        assertEquals("Cooking",
            ProcessSkillSignals.skillFromMakeXQuestion("How many would you like to cook?"));
        assertEquals("Firemaking",
            ProcessSkillSignals.skillFromMakeXQuestion("How many would you like to burn?"));
        assertEquals("Smelting",
            ProcessSkillSignals.skillFromMakeXQuestion("How many would you like to smelt?"));
        assertEquals("Smithing",
            ProcessSkillSignals.skillFromMakeXQuestion("How many sets would you like to smith?"));
        assertEquals("Herblore",
            ProcessSkillSignals.skillFromMakeXQuestion("How many would you like to mix?"));
        assertEquals("Fletching",
            ProcessSkillSignals.skillFromMakeXQuestion("How many bows would you like to make?"));
        assertEquals("", ProcessSkillSignals.skillFromMakeXQuestion("Hello traveller."));
    }

    @Test
    public void chatFailsafeCoversProcessSkills()
    {
        assertEquals("Firemaking",
            ProcessSkillSignals.skillFromChatMessage("You light the logs."));
        assertEquals("Cooking",
            ProcessSkillSignals.skillFromChatMessage("You successfully cook a shark."));
        assertEquals("Crafting",
            ProcessSkillSignals.skillFromChatMessage("You craft a leather body."));
        assertEquals("Magic",
            ProcessSkillSignals.skillFromChatMessage("You cast high level alchemy."));
        assertEquals("Cooking",
            ProcessSkillSignals.skillFromChatMessage("How many would you like to cook?"));
    }

    @Test
    public void tendToAndLightAreFiremakingSpendMenus()
    {
        assertTrue(ProcessSkillSignals.isLossOnlyProcessSpendOption("tend-to"));
        assertTrue(ProcessSkillSignals.isLossOnlyProcessSpendOption("light"));
        assertEquals("Firemaking", ProcessSkillSignals.processSpendSkillForOption("tend-to"));
        assertEquals("Firemaking", ProcessSkillSignals.processSpendSkillForOption("light"));
        assertEquals("Prayer", ProcessSkillSignals.processSpendSkillForOption("bury"));
    }

    @Test
    public void openSpendFamilyRejectsUnrelatedStacks()
    {
        assertTrue(ProcessSkillSignals.matchesOpenSpendFamily("Firemaking", "Oak logs", 1511));
        assertFalse(ProcessSkillSignals.matchesOpenSpendFamily("Firemaking", "Cooked chicken", 2140));
        assertTrue(ProcessSkillSignals.matchesOpenSpendFamily("Prayer", "Bones", 526));
        assertFalse(ProcessSkillSignals.matchesOpenSpendFamily("Prayer", "Oak logs", 1511));
        assertTrue(ProcessSkillSignals.isFiremakingUsePair("use", "Tinderbox -> Logs"));
        assertTrue(ProcessSkillSignals.isFiremakingUsePair("use", "Oak logs -> Tinderbox"));
        assertFalse(ProcessSkillSignals.isFiremakingUsePair("use", "Oak logs"));
        assertFalse(ProcessSkillSignals.isFiremakingUsePair("cook", "Raw chicken"));
    }

    @Test
    public void osrsPrayerAltarUseAndModes()
    {
        // PoH / wilderness: Use bones on altar
        assertTrue(ProcessSkillSignals.isPrayerAltarUsePair("use", "Bones -> Gilded altar"));
        assertTrue(ProcessSkillSignals.isPrayerAltarUsePair("use", "Dragon bones -> Chaos altar"));
        assertFalse(ProcessSkillSignals.isPrayerAltarUsePair("use", "Bones"));
        assertFalse(ProcessSkillSignals.isPrayerAltarUsePair("bury", "Bones -> Gilded altar"));

        assertEquals(ProcessSkillSignals.PrayerSpendMode.BURY,
            ProcessSkillSignals.prayerModeFromOption("Bury"));
        assertEquals(ProcessSkillSignals.PrayerSpendMode.SCATTER,
            ProcessSkillSignals.prayerModeFromOption("Scatter"));
        assertEquals(ProcessSkillSignals.PrayerSpendMode.OFFER,
            ProcessSkillSignals.prayerModeFromOption("Offer"));
        assertEquals(ProcessSkillSignals.PrayerSpendMode.OFFER,
            ProcessSkillSignals.prayerModeFromOption("Pray-at"));
    }

    @Test
    public void osrsAltarAndChaosChatMessages()
    {
        // https://oldschool.runescape.wiki/w/Gilded_altar
        assertTrue(ProcessSkillSignals.isAltarOfferingChat(
            "The gods are pleased with your offering"));
        assertTrue(ProcessSkillSignals.isAltarOfferingChat(
            "The gods are very pleased with your offering"));
        assertEquals("Prayer", ProcessSkillSignals.skillFromChatMessage(
            "The gods are very pleased with your offering"));

        // https://oldschool.runescape.wiki/w/Chaos_Temple_(church)
        assertTrue(ProcessSkillSignals.isChaosAltarBoneSaveChat(
            "The Dark Lord spares your sacrifice but still rewards you for your efforts."));
        assertFalse("bone-save must not reinforce consume",
            ProcessSkillSignals.isAltarOfferingChat(
                "The Dark Lord spares your sacrifice but still rewards you for your efforts."));

        // https://oldschool.runescape.wiki/w/Sinister_Offering
        assertTrue(ProcessSkillSignals.isSinisterOfferingCast("Cast", "Sinister Offering"));
        assertTrue(ProcessSkillSignals.isSinisterOfferingChat("You cast Sinister Offering."));
    }
}
