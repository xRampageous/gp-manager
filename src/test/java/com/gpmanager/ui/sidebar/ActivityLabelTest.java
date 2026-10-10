package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * One activity ladder for HUD+ and Live (owner 1.1): the named run, else a named activity, else
 * Combat. It never names an NPC, and never Hitpoints or another combat skill.
 */
public class ActivityLabelTest
{
    @Test
    public void theNamedRunAlwaysSpeaks()
    {
        assertEquals("Vorkath", ActivityLabel.label(true, "Woodcutting", "Vorkath", true, false));
        assertEquals("Vorkath", ActivityLabel.label(false, "Theatre of Blood (Hard Mode)", "Vorkath", true, false));
    }

    @Test
    public void freePlayShowsANamedActivity()
    {
        assertEquals("Woodcutting", ActivityLabel.label(false, "Woodcutting", "Overall", true, true));
        assertEquals("a pickpocket target is no fight", "Thieving",
            ActivityLabel.label(true, "Thieving", "Overall", true, true));
        assertEquals("Seers' Village Rooftop",
            ActivityLabel.label(false, "Seers' Village Rooftop", "Overall", true, true));
        assertEquals("PKing", ActivityLabel.label(true, "PKing", "Overall", true, true));
    }

    @Test
    public void raidsKeepTheirCuratedName()
    {
        assertEquals("ToB (HM)",
            ActivityLabel.label(true, "Theatre of Blood (Hard Mode)", "Overall", true, true));
        assertEquals("CoX (CM)",
            ActivityLabel.label(true, "Chambers of Xeric (Challenge Mode)", "Overall", false, false));
    }

    @Test
    public void fightsAndNpcLootReadCombat()
    {
        assertEquals("Combat", ActivityLabel.label(true, "", "Overall", true, true));
        assertEquals("an NPC's loot never names it", "Combat",
            ActivityLabel.label(false, "Greater Nechryael", "Overall", true, true));
        assertEquals("a 1.0 save's Hitpoints hint", "Combat",
            ActivityLabel.label(false, "Hitpoints", "Overall", true, true));
    }

    @Test
    public void placeholdersNeverSurface()
    {
        assertEquals("free play shows nothing", "", ActivityLabel.label(false, "", "Overall", true, true));
        assertEquals("", ActivityLabel.label(false, "General", "Vorkath", false, false));
        assertEquals("", ActivityLabel.label(false, "NPC loot", "Overall", true, true));
    }
}
