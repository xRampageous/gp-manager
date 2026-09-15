package com.gpmanager.reward;

/**
 * Presentation-only batch metadata for nearby same-source encounters.
 * Kill counts come from distinct offered encounters, never from bone/stack
 * quantities.
 */
public final class RewardBatchState
{
    private final int encounterCount;
    private final boolean exact;
    private final boolean mixedSources;

    public RewardBatchState(int encounterCount, boolean exact, boolean mixedSources)
    {
        this.encounterCount = Math.max(1, encounterCount);
        this.exact = exact;
        this.mixedSources = mixedSources;
    }

    public static RewardBatchState single()
    {
        return new RewardBatchState(1, true, false);
    }

    public int getEncounterCount()
    {
        return encounterCount;
    }

    public boolean isExact()
    {
        return exact;
    }

    public boolean isMixedSources()
    {
        return mixedSources;
    }

    public RewardBatchState withAddedEncounter(boolean exactAddition, boolean mixed)
    {
        return new RewardBatchState(
            encounterCount + 1,
            exact && exactAddition,
            mixedSources || mixed);
    }

    public String headerSuffix()
    {
        if (encounterCount <= 1)
        {
            return "";
        }
        if (mixedSources)
        {
            return " · " + encounterCount + " rewards";
        }
        String unit = encounterCount == 1 ? "kill" : "kills";
        return " · " + encounterCount + " " + unit + (exact ? "" : "~");
    }
}
