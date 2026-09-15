package com.gpmanager.engine;

import com.gpmanager.model.TrackingContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class LootMatch
{
    static final LootMatch NONE = new LootMatch(
        false,
        "",
        "General",
        TrackingContext.GENERIC,
        null,
        Collections.<Integer, Long>emptyMap());

    private final boolean matched;
    private final String note;
    private final String activityName;
    private final TrackingContext context;
    private final String encounterId;
    private final Map<Integer, Long> matchedQuantities;

    LootMatch(
        boolean matched,
        String note,
        String activityName,
        TrackingContext context,
        String encounterId,
        Map<Integer, Long> matchedQuantities)
    {
        this.matched = matched;
        this.note = note == null ? "" : note;
        this.activityName = activityName == null || activityName.trim().isEmpty()
            ? "General"
            : activityName.trim();
        this.context = context == null ? TrackingContext.GENERIC : context;
        this.encounterId = encounterId;
        Map<Integer, Long> safeQuantities = new HashMap<>();
        if (matchedQuantities != null)
        {
            matchedQuantities.forEach((itemId, quantity) ->
            {
                if (itemId != null && quantity != null && quantity > 0L)
                {
                    safeQuantities.merge(itemId, quantity, Long::sum);
                }
            });
        }
        this.matchedQuantities = safeQuantities.isEmpty()
            ? Collections.<Integer, Long>emptyMap()
            : Collections.unmodifiableMap(safeQuantities);
    }

    boolean isMatched() { return matched; }
    String getNote() { return note; }
    String getActivityName() { return activityName; }
    TrackingContext getContext() { return context; }
    String getEncounterId() { return encounterId; }
    Map<Integer, Long> getMatchedQuantities() { return matchedQuantities; }
}
