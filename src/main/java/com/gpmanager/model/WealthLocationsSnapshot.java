package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable collection of current, non-accounting wealth-location read models. */
public final class WealthLocationsSnapshot
{
    private final long capturedAtEpochMillis;
    private final List<WealthLocationSnapshot> locations;

    public WealthLocationsSnapshot(long capturedAtEpochMillis, List<WealthLocationSnapshot> locations)
    {
        this.capturedAtEpochMillis = Math.max(0L, capturedAtEpochMillis);
        List<WealthLocationSnapshot> copy = new ArrayList<>();
        if (locations != null)
        {
            for (WealthLocationSnapshot location : locations)
            {
                if (location != null) copy.add(location);
            }
        }
        this.locations = Collections.unmodifiableList(copy);
    }

    public long getCapturedAtEpochMillis() { return capturedAtEpochMillis; }
    public List<WealthLocationSnapshot> getLocations() { return locations; }

    public WealthLocationSnapshot getLocation(String id)
    {
        if (id == null) return null;
        for (WealthLocationSnapshot location : locations)
        {
            if (id.equals(location.getId())) return location;
        }
        return null;
    }
}
