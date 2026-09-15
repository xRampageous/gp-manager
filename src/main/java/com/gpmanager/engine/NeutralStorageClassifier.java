package com.gpmanager.engine;

import java.util.Locale;

/**
 * Ownership-neutral world / coin storages — treat like bank TRANSFER.
 * Never invent profit/loss on deposit or withdraw.
 */
public final class NeutralStorageClassifier
{
    public enum Kind
    {
        SEED_VAULT,
        TOOL_LEPRECHAUN,
        NMZ_COFFER,
        BLAST_FURNACE_COFFER,
        LMS_COFFER,
        MLM_HOPPER,
        GIM_SHARED,
        RAID_PRIVATE_BAG,
        RAID_SHARED_BAG,
        STASH_UNIT,
        POH_COSTUME,
        DEPOSIT_BOX,
        UNKNOWN_NEUTRAL
    }

    private NeutralStorageClassifier()
    {
    }

    public static boolean isNeutralNote(String noteOrTarget)
    {
        return classify(noteOrTarget) != null;
    }

    /** @return storage kind, or null when not a neutral storage signal. */
    public static Kind classify(String noteOrTarget)
    {
        String lower = norm(noteOrTarget);
        if (lower == null)
        {
            return null;
        }
        if (lower.contains("seed vault"))
        {
            return Kind.SEED_VAULT;
        }
        if (lower.contains("leprechaun") || lower.contains("tool store"))
        {
            return Kind.TOOL_LEPRECHAUN;
        }
        if (lower.contains("nightmare zone")
            || (lower.contains("nmz") && lower.contains("coffer")))
        {
            return Kind.NMZ_COFFER;
        }
        if (lower.contains("blast furnace")
            && (lower.contains("coffer") || lower.contains("deposit")))
        {
            return Kind.BLAST_FURNACE_COFFER;
        }
        if (lower.contains("last man standing")
            || (lower.contains("lms") && lower.contains("coffer")))
        {
            return Kind.LMS_COFFER;
        }
        if (lower.contains("pay-dirt") || lower.contains("pay dirt")
            || (lower.contains("motherlode")
                && (lower.contains("hopper") || lower.contains("sack"))))
        {
            return Kind.MLM_HOPPER;
        }
        if (lower.contains("group storage") || lower.contains("shared storage")
            || lower.contains("gim storage"))
        {
            return Kind.GIM_SHARED;
        }
        if (lower.contains("raid party") || lower.contains("private storage")
            || lower.contains("raid bag"))
        {
            return Kind.RAID_PRIVATE_BAG;
        }
        if (lower.contains("shared raid") || lower.contains("cox shared"))
        {
            return Kind.RAID_SHARED_BAG;
        }
        if (lower.contains("stash unit") || lower.contains("stash "))
        {
            return Kind.STASH_UNIT;
        }
        if (lower.contains("costume room") || lower.contains("poh costume"))
        {
            return Kind.POH_COSTUME;
        }
        if (lower.contains("deposit box") || lower.contains("bank deposit box"))
        {
            return Kind.DEPOSIT_BOX;
        }
        return null;
    }

    public static String transferNote(Kind kind)
    {
        if (kind == null)
        {
            return "Ownership-neutral storage transfer";
        }
        switch (kind)
        {
            case SEED_VAULT:
                return "Seed vault transfer";
            case TOOL_LEPRECHAUN:
                return "Tool Leprechaun store";
            case NMZ_COFFER:
                return "NMZ coffer transfer";
            case BLAST_FURNACE_COFFER:
                return "Blast Furnace coffer transfer";
            case LMS_COFFER:
                return "LMS coffer transfer";
            case MLM_HOPPER:
                return "Motherlode hopper transfer";
            case GIM_SHARED:
                return "GIM shared storage transfer";
            case RAID_PRIVATE_BAG:
                return "Raid private bag transfer";
            case RAID_SHARED_BAG:
                return "Raid shared bag transfer";
            case STASH_UNIT:
                return "STASH unit transfer";
            case POH_COSTUME:
                return "PoH costume room transfer";
            case DEPOSIT_BOX:
                return "Deposit box transfer";
            default:
                return "Ownership-neutral storage transfer";
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
