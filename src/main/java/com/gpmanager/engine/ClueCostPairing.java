package com.gpmanager.engine;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * While a clue/casket/key path is active, digs / teleports / key spends attach as
 * costs against that clue's Pending Rewards → Claimed batch. Unmatched supply
 * spends stay normal Used.
 */
public final class ClueCostPairing
{
    public enum SupplyKind
    {
        CLUE_COST,
        UNRELATED_USED
    }

    @Nullable
    private String activeClueEncounterId;
    @Nullable
    private String activeClueLabel;

    public synchronized void beginClue(String encounterId, String label)
    {
        this.activeClueEncounterId = emptyToNull(encounterId);
        this.activeClueLabel = emptyToNull(label);
    }

    public synchronized void endClue()
    {
        activeClueEncounterId = null;
        activeClueLabel = null;
    }

    public synchronized boolean isActive()
    {
        return activeClueEncounterId != null;
    }

    @Nullable
    public synchronized String getActiveEncounterId()
    {
        return activeClueEncounterId;
    }

    @Nullable
    public synchronized String getActiveLabel()
    {
        return activeClueLabel;
    }

    /**
     * Classify a spend note while a clue path may be open.
     * Dig / teleport / clue key → CLUE_COST; food / potions → UNRELATED_USED.
     */
    public synchronized SupplyKind classifySpend(String noteOrOption)
    {
        if (activeClueEncounterId == null)
        {
            return SupplyKind.UNRELATED_USED;
        }
        String lower = norm(noteOrOption);
        if (lower == null)
        {
            return SupplyKind.UNRELATED_USED;
        }
        if (looksLikeClueCost(lower))
        {
            return SupplyKind.CLUE_COST;
        }
        return SupplyKind.UNRELATED_USED;
    }

    public static boolean looksLikeClueCost(String lowerNote)
    {
        if (lowerNote == null || lowerNote.isEmpty())
        {
            return false;
        }
        return lowerNote.contains("dig")
            || lowerNote.contains("teleport")
            || lowerNote.contains("tele ")
            || lowerNote.contains("clue")
            || lowerNote.contains("casket")
            || lowerNote.contains("spade")
            || lowerNote.contains("key") && (lowerNote.contains("clue") || lowerNote.contains("chest"));
    }

    public static boolean looksLikeClueActivity(String name)
    {
        String lower = norm(name);
        return lower != null
            && (lower.contains("clue") || lower.contains("casket") || lower.contains("treasure trail"));
    }

    public synchronized String costNote()
    {
        String label = activeClueLabel == null ? "clue" : activeClueLabel;
        return "Clue cost paired to " + label;
    }

    public synchronized void clear()
    {
        endClue();
    }

    private static String emptyToNull(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return null;
        }
        return value.trim();
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
