package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JButton;

/** Test-side access to LivePage controls. */
public final class LivePageProbe
{
    private LivePageProbe()
    {
    }

    /** Finds any leftover Start/Resume button, including invisible controls. */
    public static boolean hasTrackingButton(Container page)
    {
        for (Component child : page.getComponents())
        {
            if (child instanceof JButton && ((JButton) child).getText().toLowerCase(java.util.Locale.ROOT).contains("tracking"))
            {
                return true;
            }
            if (child instanceof Container && hasTrackingButton((Container) child))
            {
                return true;
            }
        }
        return false;
    }
}
