package com.gpmanager;

/**
 * Derives change-feedback surfaces from {@link TrackingDisplay} (+ HUD+ floating
 * toggle). Replaces the old Integrated / Floating / Off picker.
 *
 * <ul>
 *   <li>HUD+ — reward tray always (when density allows); floating optional</li>
 *   <li>HUD — floating drops only</li>
 *   <li>Infobox — coin chip + tooltip; floating drops</li>
 *   <li>Off — no on-screen change feedback</li>
 * </ul>
 */
public final class ChangeFeedbackPolicy
{
    private ChangeFeedbackPolicy()
    {
    }

    /** HUD+ cassette / reward tray. */
    public static boolean showHudPlusTray(GpManagerConfig config)
    {
        return config != null && config.trackingDisplay() == TrackingDisplay.HUD_PLUS;
    }

    /** Separate GP-drop overlay. */
    public static boolean showFloatingDrops(GpManagerConfig config)
    {
        if (config == null)
        {
            return false;
        }
        TrackingDisplay display = config.trackingDisplay();
        if (display == TrackingDisplay.OFF)
        {
            return false;
        }
        if (display == TrackingDisplay.HUD_PLUS)
        {
            return config.hudPlusFloatingDrops();
        }
        // HUD and Infobox: floating only.
        return true;
    }

    /** Animate HUD+ tray reveals (not used for HUD/Infobox). */
    public static boolean animateHudPlusTray(GpManagerConfig config)
    {
        return showHudPlusTray(config) && config != null && !config.reducedMotion();
    }
}
