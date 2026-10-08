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
        assertEquals("Vorkath", ActivityLabel.d1(true, "Woodcutting", "Vorkath", true, false));
        assertEquals("Vorkath", ActivityLabel.d1(false, "Theatre of Blood (Hard Mode)", "Vorkath", true, false));
    }

    @Test
    public void freePlayShowsANamedActivity()
    {
        assertEquals("Woodcutting", ActivityLabel.d1(false, "Woodcutting", "Overall", true, true));
        assertEquals("a pickpocket target is no fight", "Thieving",
            ActivityLabel.d1(true, "Thieving", "Overall", true, true));
        assertEquals("Seers' Village Rooftop",
            ActivityLabel.d1(false, "Seers' Village Rooftop", "Overall", true, true));
        assertEquals("PKing", ActivityLabel.d1(true, "PKing", "Overall", true, true));
    }

    @Test
    public void raidsKeepTheirCuratedName()
    {
        assertEquals("ToB (HM)",
            ActivityLabel.d1(true, "Theatre of Blood (Hard Mode)", "Overall", true, true));
        assertEquals("CoX (CM)",
            ActivityLabel.d1(true, "Chambers of Xeric (Challenge Mode)", "Overall", false, false));
    }

    @Test
    public void fightsAndNpcLootReadCombat()
    {
        assertEquals("Combat", ActivityLabel.d1(true, "", "Overall", true, true));
        assertEquals("an NPC's loot never names it", "Combat",
            ActivityLabel.d1(false, "Greater Nechryael", "Overall", true, true));
        assertEquals("a 1.0 save's Hitpoints hint", "Combat",
            ActivityLabel.d1(false, "Hitpoints", "Overall", true, true));
    }

    @Test
    public void placeholdersNeverSurface()
    {
        assertEquals("free play shows nothing", "", ActivityLabel.d1(false, "", "Overall", true, true));
        assertEquals("", ActivityLabel.d1(false, "General", "Vorkath", false, false));
        assertEquals("", ActivityLabel.d1(false, "NPC loot", "Overall", true, true));
    }
}
