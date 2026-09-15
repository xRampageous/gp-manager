package com.gpmanager.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HudPlusProcessLabelsTest
{
    @Test
    public void gatherSkillsBannedProcessAllowed()
    {
        assertTrue(HudPlusProcessLabels.isGatherSkillTitle("Woodcutting"));
        assertTrue(HudPlusProcessLabels.isGatherSkillTitle("Mining"));
        assertFalse(HudPlusProcessLabels.isGatherSkillTitle("Crafting"));
        assertTrue(HudPlusProcessLabels.isProcessTitle("Smelting"));
        assertTrue(HudPlusProcessLabels.isProcessTitle("Crafting"));
    }

    @Test
    public void sceneryAndMenuMapToGerunds()
    {
        assertEquals("Smelting", HudPlusProcessLabels.gerundFromMenuOption("smelt"));
        assertEquals("Chiselling", HudPlusProcessLabels.gerundFromMenuOption("chisel"));
        assertEquals("Cooking", HudPlusProcessLabels.gerundFromMenuOption("cook"));
        assertEquals("Firemaking", HudPlusProcessLabels.gerundFromMenuOption("light"));
        assertEquals("Herblore", HudPlusProcessLabels.gerundFromMenuOption("mix"));
        assertEquals("Construction", HudPlusProcessLabels.gerundFromMenuOption("build"));
        assertNull(HudPlusProcessLabels.gerundFromMenuOption("make"));
        assertFalse(HudPlusProcessLabels.isTrackedObjectOption("make"));
        assertTrue(HudPlusProcessLabels.isTrackedObjectOption("climb-up"));
        assertEquals("Smelting", HudPlusProcessLabels.gerundFromScenery("Furnace"));
        assertEquals("Smithing", HudPlusProcessLabels.gerundFromScenery("Anvil"));
        assertEquals("Spinning", HudPlusProcessLabels.gerundFromScenery("Spinning wheel"));
        assertEquals("Cooking", HudPlusProcessLabels.gerundFromScenery("Cooking range"));
        assertNull("Bare Fire is cook/firemake ambiguous",
            HudPlusProcessLabels.gerundFromScenery("Fire"));
        assertNull(HudPlusProcessLabels.gerundFromScenery("Campfire"));
        assertEquals("Prayer", HudPlusProcessLabels.gerundFromScenery("Altar"));
        assertEquals("Oak tree",
            HudPlusProcessLabels.resolveHudPlusObjectName("Oak tree", null));
        assertEquals("Smelting",
            HudPlusProcessLabels.resolveHudPlusObjectName("Furnace", "Smelting"));
        assertEquals("Cooking",
            HudPlusProcessLabels.resolveHudPlusObjectName("Fire", "Cooking"));
        assertEquals("Firemaking",
            HudPlusProcessLabels.resolveHudPlusObjectName("Fire", "Firemaking"));
        assertEquals("Fire",
            HudPlusProcessLabels.resolveHudPlusObjectName("Fire", null));
        assertEquals("Farming", HudPlusProcessLabels.activityHintFromOption("rake"));
        assertEquals("Agility", HudPlusProcessLabels.activityHintFromOption("climb-up"));
    }

    @Test
    public void titlePredicateSeparatesMovementFromChestResourceAndMinigameActivity()
    {
        assertFalse(HudPlusProcessLabels.isTitledObjectOption("Cross", "Wilderness Ditch"));
        assertFalse(HudPlusProcessLabels.isTitledObjectOption("Climb-up", "Ladder"));
        assertFalse(HudPlusProcessLabels.isTitledObjectOption("Open", "Barrows stairs"));
        assertFalse(HudPlusProcessLabels.isTitledObjectOption("Open", "Oak door"));
        assertFalse(HudPlusProcessLabels.isTitledObjectOption("Close", "Gate"));
        assertFalse(HudPlusProcessLabels.isTitledObjectOption("Use", "Fairy ring"));
        assertTrue(HudPlusProcessLabels.isTitledObjectOption("Chop down", "Oak tree"));
        assertTrue(HudPlusProcessLabels.isTitledObjectOption("Open", "Brimstone chest"));
        assertTrue(HudPlusProcessLabels.isTitledObjectOption("Unlock", "Bronze coffer"));
        assertTrue(HudPlusProcessLabels.isTitledObjectOption("Start", "Gauntlet entrance"));
        assertFalse(HudPlusProcessLabels.isTitledObjectOption("Cross", " "));
        assertFalse(HudPlusProcessLabels.isTitledObjectOption(" ", "Oak tree"));
    }

    @Test
    public void transformOptionsMapToProcessTitlesNotGatherSkills()
    {
        assertEquals("Smelting", HudPlusProcessLabels.transformSkillForOption("smelt"));
        assertEquals("Fletching", HudPlusProcessLabels.transformSkillForOption("fletch"));
        assertTrue(HudPlusProcessLabels.isTransformProductionOption("smelt"));
        assertTrue(HudPlusProcessLabels.isTransformProcessSkill("Fletching"));
        assertFalse(HudPlusProcessLabels.isTrackedObjectOption("make"));
        assertTrue(HudPlusProcessLabels.isGatherSkillTitle("Woodcutting"));
        assertFalse(HudPlusProcessLabels.isGatherSkillTitle("Smelting"));
    }

    @Test
    public void transformOptionsCoverTipFeatherPlankTanMould()
    {
        assertTrue(HudPlusProcessLabels.isTransformProductionOption("tip"));
        assertTrue(HudPlusProcessLabels.isTransformProductionOption("feather"));
        assertTrue(HudPlusProcessLabels.isTransformProductionOption("attach"));
        assertTrue(HudPlusProcessLabels.isTransformProductionOption("plank"));
        assertTrue(HudPlusProcessLabels.isTransformProductionOption("tan"));
        assertTrue(HudPlusProcessLabels.isTransformProductionOption("mould"));
        assertEquals("Fletching", HudPlusProcessLabels.transformSkillForOption("tip"));
        assertEquals("Construction", HudPlusProcessLabels.transformSkillForOption("plank"));
        assertTrue(HudPlusProcessLabels.isTransformProcessSkill("Smelting"));
        assertTrue(HudPlusProcessLabels.isTransformProcessSkill("Runecraft"));
        assertEquals("Fletching", HudPlusProcessLabels.gerundFromMenuOption("feather"));
        assertEquals("Planking", HudPlusProcessLabels.gerundFromMenuOption("plank"));
    }

    @Test
    public void inventorySpendTitlesYieldToNpcEngagementButNpcTargetedSkillsDoNot()
    {
        // Bury/Offer, Firemaking, Cooking cannot involve an NPC: a fresh target wins.
        assertTrue(HudPlusProcessLabels.yieldsToNpcEngagement("Prayer"));
        assertTrue(HudPlusProcessLabels.yieldsToNpcEngagement("Firemaking"));
        assertTrue(HudPlusProcessLabels.yieldsToNpcEngagement("Cooking"));
        // Pickpocketing and casting on an NPC are the interaction itself.
        assertFalse(HudPlusProcessLabels.yieldsToNpcEngagement("Thieving"));
        assertFalse(HudPlusProcessLabels.yieldsToNpcEngagement("Magic"));
        // Non-process hints are untouched by the rule.
        assertFalse(HudPlusProcessLabels.yieldsToNpcEngagement("General"));
        assertFalse(HudPlusProcessLabels.yieldsToNpcEngagement("Woodcutting"));
        assertFalse(HudPlusProcessLabels.yieldsToNpcEngagement(null));
    }

    @Test
    public void lateProcessSignalsAreSuppressedOnlyWhileAnNpcIsTheActiveTarget()
    {
        assertTrue(HudPlusProcessLabels.shouldSuppressProcessSignalDuringNpcEngagement("Prayer", true));
        assertTrue(HudPlusProcessLabels.shouldSuppressProcessSignalDuringNpcEngagement("Cooking", true));
        assertFalse(HudPlusProcessLabels.shouldSuppressProcessSignalDuringNpcEngagement("Prayer", false));
        assertFalse(HudPlusProcessLabels.shouldSuppressProcessSignalDuringNpcEngagement("Magic", true));
        assertFalse(HudPlusProcessLabels.shouldSuppressProcessSignalDuringNpcEngagement("Thieving", true));
    }
}
