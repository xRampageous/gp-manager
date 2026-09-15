package com.gpmanager.ui;

import com.gpmanager.reward.RewardObservation;

/**
 * Presentation-only HUD+ header label. Never mutates accounting activity.
 * Prefers interaction targets (Oak tree, Chicken) and process titles (Smelting,
 * Crafting) over gather-skill buckets (Woodcutting). A fresh target may follow a
 * PAUSED / AFK / REC prefix; otherwise those status tokens stand alone. Custom
 * sessions prefix {@code Session ·} so Current vs Overall is unmistakable.
 */
public final class HudPlusHeaderLabel
{
    private HudPlusHeaderLabel()
    {
    }

    public static String resolve(String statusLabel, String activityHint, RewardObservation reward)
    {
        String statusToken = TrackingStatus.compactHudPlus(statusLabel);
        if (!statusToken.isEmpty())
        {
            // Higher-level statuses stand alone; receipts and activity hints stay separate.
            return statusToken;
        }
        // The legacy overload has no independent XP-confirmed title field.
        return activityFallback(activityHint);
    }

    public static String resolve(TrackingDisplaySnapshot snapshot)
    {
        return resolve(snapshot, false);
    }

    /**
     * @param activityHold true while skilling/process or Pending Rewards tray is held
     */
    public static String resolve(TrackingDisplaySnapshot snapshot, boolean activityHold)
    {
        return prefixSession(resolveBody(snapshot, activityHold, true), snapshot, false);
    }

    /** Full semantic title used for width measurement and hover provenance. */
    public static String resolveFull(TrackingDisplaySnapshot snapshot, boolean activityHold)
    {
        return prefixSession(resolveBody(snapshot, activityHold, false), snapshot, false);
    }

    /**
     * Hover / tooltip title: includes custom session name when active
     * ({@code Session · {name} · {activity}}).
     */
    public static String resolveHover(TrackingDisplaySnapshot snapshot)
    {
        return prefixSession(resolveBody(snapshot, false, false), snapshot, true);
    }

    /** Header body without Session · prefix — shared by status gem colour. */
    static String resolveBody(TrackingDisplaySnapshot snapshot)
    {
        return resolveBody(snapshot, false);
    }

    static String resolveBody(TrackingDisplaySnapshot snapshot, boolean activityHold)
    {
        return resolveBody(snapshot, activityHold, true);
    }

    private static String resolveBody(
        TrackingDisplaySnapshot snapshot, boolean activityHold, boolean compactNames)
    {
        String status = snapshot.getStatusLabel();
        String statusToken = TrackingStatus.compactHudPlus(status, false);

        // Rank 1 always wins. Keep a non-live state visible as a prefix; use its
        // status-only label only when no fresh interaction target remains.
        InteractionContextModel.View context = snapshot.getInteraction();
        if (context.isPresent())
        {
            String name = context.getHudPlusName();
            if ("Chest".equalsIgnoreCase(name.trim()))
            {
                name = "Opening chest";
            }
            RewardObservation reward = snapshot.getCompleteReward();
            if (!context.isSelected() && reward != null && reward.getSourceKind() != null
                && reward.getSourceKind().isObservedLoot()
                && context.getName().equalsIgnoreCase(reward.getSourceName()))
            {
                // Same NPC/chest name — allow batch suffix (Chicken · 4 kills).
                name = reward.displaySourceLabel();
            }
            if (compactNames)
            {
                name = HudNames.compact(name);
            }
            return withStatus(statusToken, name);
        }

        boolean live = status == null || status.isEmpty() || "Live".equalsIgnoreCase(status)
            || "Tracking".equalsIgnoreCase(status);
        if (!live)
        {
            return statusToken.isEmpty() ? resolve(status, snapshot.getActivity(), null) : statusToken;
        }

        // The five-tick XP hold, rather than character animation/idle state,
        // decides whether a rank-2 process title is still recent.
        String processTitle = preferredLiveProcessTitle(snapshot.getConfirmedProcessTitle());
        if (!processTitle.isEmpty())
        {
            return withStatus(statusToken,
                compactNames ? HudNames.compact(processTitle) : processTitle);
        }

        // Place state is lower priority than both a fresh target and process XP.
        if (snapshot.isBankingUiOpen())
        {
            return "Banking";
        }
        if (snapshot.isNeutralZoneActive())
        {
            return "Neutral zone";
        }
        if (snapshot.isCharacterIdle())
        {
            return "Waiting".equalsIgnoreCase(safeTrim(snapshot.getActivity()))
                ? "Waiting"
                : "";
        }

        // Busy receipts and reward-source names remain tray content; never let
        // presentation observations promote them into the active title.
        String body = activityFallback(snapshot.getActivity());
        if (statusToken.isEmpty())
        {
            return body;
        }
        if (body == null || body.isEmpty())
        {
            return statusToken;
        }
        return statusToken + " · " + body;
    }

    private static String prefixSession(String body, TrackingDisplaySnapshot snapshot, boolean includeName)
    {
        if (snapshot == null || !snapshot.isCustomSessionActive())
        {
            return body == null ? "" : body;
        }
        // The word "Session" carried nothing the sidebar does not already say; the header is
        // the activity (and, where asked for, the session's name).
        String activity = body == null ? "" : body.trim();
        if (includeName)
        {
            String name = safeTrim(snapshot.getSessionName());
            if (!name.isEmpty())
            {
                return activity.isEmpty() ? name : name + " · " + activity;
            }
        }
        return activity;
    }

    private static String preferredLiveProcessTitle(String activityHint)
    {
        String activity = safeTrim(activityHint);
        if (HudPlusProcessLabels.isProcessTitle(activity))
        {
            return titleCaseToken(activity);
        }
        return "";
    }

    private static String activityFallback(String activityHint)
    {
        String trimmed = safeTrim(activityHint);
        if ("Waiting".equalsIgnoreCase(trimmed))
        {
            return titleCaseToken(trimmed);
        }
        return "";
    }

    private static String withStatus(String status, String title)
    {
        return status == null || status.isEmpty() ? title : status + " · " + title;
    }

    private static String titleCaseToken(String trimmed)
    {
        if (trimmed.isEmpty())
        {
            return trimmed;
        }
        return trimmed.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
            + trimmed.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String safeTrim(String value)
    {
        return value == null ? "" : value.trim();
    }
}
