package com.gpmanager.engine;

import java.util.Locale;

/**
 * LMS invent wipe, raid private/shared bags, GIM shared storage — all ownership-neutral
 * TRANSFER / baseline refresh. Never book full invent as Lost/LOOT on enter/exit.
 */
public final class MinigameTransferClassifier
{
    public enum Event
    {
        LMS_ENTER,
        LMS_EXIT,
        RAID_BAG_STORE,
        RAID_BAG_WITHDRAW,
        RAID_BAG_CLEAR,
        GIM_SHARED_DEPOSIT,
        GIM_SHARED_WITHDRAW
    }

    private MinigameTransferClassifier()
    {
    }

    /** Optional live client signals layered on menu/chat notes. */
    public static final class LiveSignals
    {
        public final int regionId;
        public final boolean gimStorageInterfaceOpen;
        public final boolean raidStorageInterfaceOpen;

        public LiveSignals(int regionId, boolean gimStorageInterfaceOpen, boolean raidStorageInterfaceOpen)
        {
            this.regionId = regionId;
            this.gimStorageInterfaceOpen = gimStorageInterfaceOpen;
            this.raidStorageInterfaceOpen = raidStorageInterfaceOpen;
        }
    }

    public static Event classify(String noteOrRegion)
    {
        return classify(noteOrRegion, null);
    }

    public static Event classify(String noteOrRegion, @javax.annotation.Nullable LiveSignals signals)
    {
        String lower = norm(noteOrRegion);
        if (lower == null && signals == null)
        {
            return null;
        }
        if (lower != null)
        {
            Event fromNote = classifyNote(lower);
            if (fromNote != null)
            {
                return fromNote;
            }
        }
        if (signals == null)
        {
            return null;
        }
        if (MinigameRegionHints.isLmsRegion(signals.regionId))
        {
            if (lower != null && (lower.contains("leave") || lower.contains("exit")))
            {
                return Event.LMS_EXIT;
            }
            if (lower == null || lower.contains("enter") || lower.contains("join") || lower.contains("wipe"))
            {
                return Event.LMS_ENTER;
            }
        }
        if (signals.gimStorageInterfaceOpen)
        {
            if (lower != null && (lower.contains("withdraw") || lower.contains("take")))
            {
                return Event.GIM_SHARED_WITHDRAW;
            }
            return Event.GIM_SHARED_DEPOSIT;
        }
        if (signals.raidStorageInterfaceOpen)
        {
            if (lower != null && (lower.contains("clear") || lower.contains("end")))
            {
                return Event.RAID_BAG_CLEAR;
            }
            if (lower != null && (lower.contains("withdraw") || lower.contains("take")))
            {
                return Event.RAID_BAG_WITHDRAW;
            }
            return Event.RAID_BAG_STORE;
        }
        return null;
    }

    private static Event classifyNote(String lower)
    {
        if (lower == null)
        {
            return null;
        }
        if (lower.contains("last man standing") || lower.contains("lms"))
        {
            if (lower.contains("leave") || lower.contains("exit") || lower.contains("restore"))
            {
                return Event.LMS_EXIT;
            }
            if (lower.contains("enter") || lower.contains("join") || lower.contains("wipe")
                || lower.contains("loadout"))
            {
                return Event.LMS_ENTER;
            }
        }
        if (lower.contains("raid bag") || lower.contains("private storage")
            || lower.contains("cox storage") || lower.contains("toa storage"))
        {
            if (lower.contains("clear") || lower.contains("end") || lower.contains("wipe"))
            {
                return Event.RAID_BAG_CLEAR;
            }
            if (lower.contains("withdraw") || lower.contains("take"))
            {
                return Event.RAID_BAG_WITHDRAW;
            }
            return Event.RAID_BAG_STORE;
        }
        if (lower.contains("group storage") || lower.contains("shared storage")
            || lower.contains("gim"))
        {
            if (lower.contains("withdraw") || lower.contains("take"))
            {
                return Event.GIM_SHARED_WITHDRAW;
            }
            return Event.GIM_SHARED_DEPOSIT;
        }
        return null;
    }

    /** LMS invent wipe must stay ownership-neutral TRANSFER — never LOOT/Lost on enter. */
    public static boolean isOwnershipNeutral(Event event)
    {
        return event != null;
    }

    public static String transferNote(Event event)
    {
        if (event == null)
        {
            return "Minigame ownership-neutral transfer";
        }
        switch (event)
        {
            case LMS_ENTER:
                return "LMS invent wipe (enter) — baseline refresh";
            case LMS_EXIT:
                return "LMS invent restore (exit) — baseline refresh";
            case RAID_BAG_STORE:
                return "Raid bag store";
            case RAID_BAG_WITHDRAW:
                return "Raid bag withdraw";
            case RAID_BAG_CLEAR:
                return "Raid bag clear — stored snapshot only";
            case GIM_SHARED_DEPOSIT:
                return "GIM shared storage deposit";
            case GIM_SHARED_WITHDRAW:
                return "GIM shared storage withdraw";
            default:
                return "Minigame ownership-neutral transfer";
        }
    }

    private static String norm(String value)
    {
        if (value == null)
        {
            return null;
        }
        String key = value.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }
}
