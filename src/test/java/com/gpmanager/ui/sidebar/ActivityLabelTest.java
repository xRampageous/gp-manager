package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** One activity ladder for HUD+ and Live: target, then activity, then the named run. */
public class ActivityLabelTest
{
    @Test
    public void targetBeatsActivityAndGrind()
    {
        assertEquals("Goblin", ActivityLabel.resolve("Goblin", "Woodcutting", "Vorkath", true, false));
    }

    @Test
    public void activityFillsInWhenNoTargetIsFresh()
    {
        assertEquals("Woodcutting", ActivityLabel.resolve("", "Woodcutting", "Vorkath", true, false));
    }

    @Test
    public void theNamedRunSpeaksWhenThereIsNoEvidence()
    {
        assertEquals("Vorkath", ActivityLabel.resolve("", "", "Vorkath", true, false));
        assertEquals("free play shows nothing", "", ActivityLabel.resolve("", "", "Overall", true, true));
        assertEquals("", ActivityLabel.resolve("", "", "Vorkath", false, false));
    }

    @Test
    public void raidsKeepTheirCuratedNameOverATarget()
    {
        assertEquals("ToB (HM)",
            ActivityLabel.resolve("Goblin", "Theatre of Blood (Hard Mode)", "Vorkath", true, false));
        assertEquals("CoX (CM)",
            ActivityLabel.resolve("Goblin", "Chambers of Xeric (Challenge Mode)", "Vorkath", true, false));
    }

    @Test
    public void placeholdersNeverSurface()
    {
        assertEquals("Vorkath", ActivityLabel.resolve("", "General", "Vorkath", true, false));
        assertEquals("Vorkath", ActivityLabel.resolve("", "NPC loot", "Vorkath", true, false));
        assertEquals("", ActivityLabel.resolve("", "NPC loot", "Overall", true, true));
    }
}
