package com.gpmanager;

import com.gpmanager.model.SessionMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class InfoBoxActivityLabelTest
{
    @Test
    public void autoModeNeverUsesGeneralAsTheHeader()
    {
        assertEquals("Waiting", InfoBoxActivityLabel.resolve("General", SessionMode.AUTO, false, 0, 0));
        assertEquals("Tracking", InfoBoxActivityLabel.resolve("General", SessionMode.AUTO, false, 2, 1));
        assertEquals("AFK", InfoBoxActivityLabel.resolve("General", SessionMode.AUTO, true, 2, 1));
    }

    @Test
    public void specificActivitiesRemainStableAndCorrectlyCased()
    {
        assertEquals("Tracking", InfoBoxActivityLabel.resolve("pvm", SessionMode.AUTO, false, 1, 1));
        assertEquals("Tracking", InfoBoxActivityLabel.resolve("pking", SessionMode.AUTO, false, 1, 1));
        assertEquals("Tracking", InfoBoxActivityLabel.resolve("Skilling", SessionMode.AUTO, false, 1, 1));
        assertEquals("Woodcutting", InfoBoxActivityLabel.resolve("Woodcutting", SessionMode.AUTO, false, 1, 1));
        assertEquals("Crafting", InfoBoxActivityLabel.resolve("Crafting", SessionMode.AUTO, false, 1, 1));
        assertEquals("GE Clerk", InfoBoxActivityLabel.resolve(
            "Grand Exchange Clerk", SessionMode.AUTO, false, 1, 1));
    }
}
