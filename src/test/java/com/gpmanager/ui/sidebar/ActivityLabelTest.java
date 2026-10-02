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
        assertEquals("Vorkath", ActivityLabel.resolve(true, "Woodcutting", "Vorkath", true, false));
        assertEquals("Vorkath", ActivityLabel.resolve(false, "Theatre of Blood (Hard Mode)", "Vorkath", true, false));
    }

    @Test
    public void freePlayShowsANamedActivity()
    {
        assertEquals("Woodcutting", ActivityLabel.resolve(false, "Woodcutting", "Overall", true, true));
        assertEquals("a pickpocket target is no fight", "Thieving",
            ActivityLabel.resolve(true, "Thieving", "Overall", true, true));
        assertEquals("Seers' Village Rooftop",
            ActivityLabel.resolve(false, "Seers' Village Rooftop", "Overall", true, true));
        assertEquals("PKing", ActivityLabel.resolve(true, "PKing", "Overall", true, true));
    }

    @Test
    public void raidsKeepTheirCuratedName()
    {
        assertEquals("ToB (HM)",
            ActivityLabel.resolve(true, "Theatre of Blood (Hard Mode)", "Overall", true, true));
        assertEquals("CoX (CM)",
            ActivityLabel.resolve(true, "Chambers of Xeric (Challenge Mode)", "Overall", false, false));
    }

    @Test
    public void fightsAndNpcLootReadCombat()
    {
        assertEquals("Combat", ActivityLabel.resolve(true, "", "Overall", true, true));
        assertEquals("an NPC's loot never names it", "Combat",
            ActivityLabel.resolve(false, "Greater Nechryael", "Overall", true, true));
        assertEquals("a 1.0 save's Hitpoints hint", "Combat",
            ActivityLabel.resolve(false, "Hitpoints", "Overall", true, true));
    }

    @Test
    public void placeholdersNeverSurface()
    {
        assertEquals("free play shows nothing", "", ActivityLabel.resolve(false, "", "Overall", true, true));
        assertEquals("", ActivityLabel.resolve(false, "General", "Vorkath", false, false));
        assertEquals("", ActivityLabel.resolve(false, "NPC loot", "Overall", true, true));
    }
}
