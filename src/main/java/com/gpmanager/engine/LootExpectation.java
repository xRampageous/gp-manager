package com.gpmanager.engine;

import com.gpmanager.model.TrackingContext;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class LootExpectation
{
    private final String note;
    private final String activityName;
    private final TrackingContext context;
    private final String encounterId;
    private final Map<Integer, Long> remaining;
    private int remainingTicks;

    LootExpectation(
        Map<Integer, Long> expected,
        int remainingTicks,
        String note,
        String activityName)
    {
        this(expected, remainingTicks, note, activityName, TrackingContext.LOOT, null);
    }

    LootExpectation(
        Map<Integer, Long> expected,
        int remainingTicks,
        String note,
        String activityName,
        TrackingContext context,
        String encounterId)
    {
        this.note = note == null ? "" : note;
        this.activityName = activityName == null || activityName.trim().isEmpty()
            ? "General"
            : activityName.trim();
        this.context = context == null ? TrackingContext.LOOT : context;
        this.encounterId = encounterId;
        this.remaining = new HashMap<>();
        if (expected != null)
        {
            for (Map.Entry<Integer, Long> entry : expected.entrySet())
            {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L)
                {
                    remaining.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
            }
        }
        this.remainingTicks = Math.max(1, remainingTicks);
    }

    Map<Integer, Long> consumeMatching(Map<Integer, Long> availablePositiveQuantities)
    {
        Map<Integer, Long> matched = new HashMap<>();
        for (Map.Entry<Integer, Long> expected : new HashMap<>(remaining).entrySet())
        {
            long outstanding = expected.getValue();
            long available = availablePositiveQuantities.getOrDefault(expected.getKey(), 0L);
            if (available <= 0L)
            {
                continue;
            }

            long consumed = Math.min(available, outstanding);
            matched.put(expected.getKey(), consumed);
            long left = outstanding - consumed;
            if (left <= 0L)
            {
                remaining.remove(expected.getKey());
            }
            else
            {
                remaining.put(expected.getKey(), left);
            }
            long availableLeft = available - consumed;
            if (availableLeft <= 0L)
            {
                availablePositiveQuantities.remove(expected.getKey());
            }
            else
            {
                availablePositiveQuantities.put(expected.getKey(), availableLeft);
            }
        }
        return matched;
    }

    void tick() { remainingTicks--; }
    boolean isExpired() { return remainingTicks <= 0; }

    boolean isComplete()
    {
        if (remaining.isEmpty())
        {
            return true;
        }

        Iterator<Map.Entry<Integer, Long>> iterator = remaining.entrySet().iterator();
        while (iterator.hasNext())
        {
            if (iterator.next().getValue() <= 0L)
            {
                iterator.remove();
            }
        }
        return remaining.isEmpty();
    }

    String getNote() { return note; }
    String getActivityName() { return activityName; }
    TrackingContext getContext() { return context; }
    String getEncounterId() { return encounterId; }
}
