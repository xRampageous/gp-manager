package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Place breakdown for one Insights window. Unknown labels never create a
 * fabricated location row; their measured time is exposed as a remainder.
 */
public final class PkPlaceSummaries
{
    private final List<PkPlaceSummary> places;
    private final long unlabelledMillis;
    private final long unlabelledEncounterCount;
    private final boolean encounterCountsAvailable;
    private final boolean financeAvailable;
    private final boolean activeTimeAvailable;

    public PkPlaceSummaries(List<PkPlaceSummary> places, long unlabelledMillis,
        long unlabelledEncounterCount, boolean encounterCountsAvailable,
        boolean financeAvailable, boolean activeTimeAvailable)
    {
        this.places = Collections.unmodifiableList(new ArrayList<>(
            places == null ? Collections.emptyList() : places));
        this.unlabelledMillis = Math.max(0L, unlabelledMillis);
        this.unlabelledEncounterCount = Math.max(0L, unlabelledEncounterCount);
        this.encounterCountsAvailable = encounterCountsAvailable;
        this.financeAvailable = financeAvailable;
        this.activeTimeAvailable = activeTimeAvailable;
    }

    public List<PkPlaceSummary> getPlaces() { return places; }
    public long getUnlabelledMillis() { return unlabelledMillis; }
    public long getUnlabelledEncounterCount() { return unlabelledEncounterCount; }
    public boolean isEncounterCountsAvailable() { return encounterCountsAvailable; }
    public boolean isFinanceAvailable() { return financeAvailable; }
    public boolean isActiveTimeAvailable() { return activeTimeAvailable; }
}
