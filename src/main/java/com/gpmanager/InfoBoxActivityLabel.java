package com.gpmanager;

import com.gpmanager.model.InsightsActivityBreakdown;
import com.gpmanager.model.SessionMode;
import com.gpmanager.ui.HudNames;

/** Pure display policy for the lightweight in-game activity header. */
public final class InfoBoxActivityLabel
{
    private InfoBoxActivityLabel()
    {
    }

    public static String resolve(
        String activity,
        SessionMode mode,
        boolean paused,
        int actionCount,
        int transactionCount)
    {
        SessionMode safeMode = mode == null ? SessionMode.GENERAL : mode;
        String trimmed = activity == null ? "" : activity.trim();

        if (trimmed.isEmpty())
        {
            return safeMode == SessionMode.AUTO ? "Waiting" : safeMode.toString();
        }

        if (safeMode == SessionMode.AUTO && "General".equalsIgnoreCase(trimmed))
        {
            if (paused)
            {
                return "AFK";
            }
            return actionCount == 0 && transactionCount == 0 ? "Waiting" : "Tracking";
        }

        // Insights buckets are not current-activity titles (never blank the header).
        if (InsightsActivityBreakdown.isGeneralizedCategory(trimmed))
        {
            return "Tracking";
        }
        return HudNames.compact(trimmed);
    }
}
