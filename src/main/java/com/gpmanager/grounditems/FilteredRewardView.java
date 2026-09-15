package com.gpmanager.grounditems;

import com.gpmanager.LootPresentationFilter;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardObservation;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Display-only projection of a complete {@link RewardObservation}. Accounting
 * and the underlying observation stay intact; only visible stacks, selected
 * item, and displayed totals change with the active filter.
 *
 * <p>Hidden-stack honesty ({@link #isAllFiltered}, {@link #getHiddenItems}) is
 * measured against presentation candidates (remaining unpicked for incomplete
 * Ground Loot), not against confirmed pickups that already left the tray.
 */
public final class FilteredRewardView
{
    private final RewardObservation observation;
    private final List<RewardItem> presentationCandidates;
    private final List<RewardItem> visibleItems;
    private final Map<Integer, Color> itemColors;
    private final RewardItem selectedVisibleItem;
    private final long visibleLootTotal;
    private final boolean visibleLootKnown;
    private final boolean allFiltered;
    private final boolean partiallyFiltered;
    private final LootPresentationFilter filterMode;

    private FilteredRewardView(
        RewardObservation observation,
        List<RewardItem> presentationCandidates,
        List<RewardItem> visibleItems,
        Map<Integer, Color> itemColors,
        LootPresentationFilter filterMode)
    {
        this.observation = observation;
        List<RewardItem> candidates = presentationCandidates == null
            ? Collections.emptyList()
            : presentationCandidates;
        this.presentationCandidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.visibleItems = Collections.unmodifiableList(new ArrayList<>(visibleItems));
        this.itemColors = Collections.unmodifiableMap(new LinkedHashMap<>(itemColors));
        this.filterMode = filterMode == null ? LootPresentationFilter.ALL_ITEMS : filterMode;

        RewardItem best = null;
        long total = 0L;
        boolean known = !this.visibleItems.isEmpty();
        for (RewardItem item : this.visibleItems)
        {
            if (item == null)
            {
                continue;
            }
            if (!item.isValueKnown())
            {
                known = false;
            }
            else
            {
                total += item.getRecordedValue();
            }
            if (item.beats(best))
            {
                best = item;
            }
        }
        this.selectedVisibleItem = best;
        this.visibleLootTotal = total;
        this.visibleLootKnown = known && !this.visibleItems.isEmpty();

        int baseCount = this.presentationCandidates.size();
        this.allFiltered = observation != null
            && baseCount > 0
            && this.visibleItems.isEmpty();
        this.partiallyFiltered = observation != null
            && !this.visibleItems.isEmpty()
            && this.visibleItems.size() < baseCount;
    }

    public static FilteredRewardView allVisible(@Nullable RewardObservation observation)
    {
        if (observation == null)
        {
            return empty(null, LootPresentationFilter.ALL_ITEMS);
        }
        List<RewardItem> candidates = observation.presentationStacks();
        return new FilteredRewardView(
            observation,
            candidates,
            candidates,
            Collections.emptyMap(),
            LootPresentationFilter.ALL_ITEMS);
    }

    public static FilteredRewardView empty(
        @Nullable RewardObservation observation,
        LootPresentationFilter filterMode)
    {
        List<RewardItem> candidates = observation == null
            ? Collections.emptyList()
            : observation.presentationStacks();
        return new FilteredRewardView(
            observation,
            candidates,
            Collections.emptyList(),
            Collections.emptyMap(),
            filterMode);
    }

    public static FilteredRewardView of(
        @Nullable RewardObservation observation,
        List<RewardItem> visibleItems,
        Map<Integer, Color> itemColors,
        LootPresentationFilter filterMode)
    {
        if (observation == null)
        {
            return empty(null, filterMode);
        }
        List<RewardItem> candidates = observation.presentationStacks();
        return of(observation, candidates, visibleItems, itemColors, filterMode);
    }

    public static FilteredRewardView of(
        @Nullable RewardObservation observation,
        List<RewardItem> presentationCandidates,
        List<RewardItem> visibleItems,
        Map<Integer, Color> itemColors,
        LootPresentationFilter filterMode)
    {
        if (observation == null)
        {
            return empty(null, filterMode);
        }
        return new FilteredRewardView(
            observation,
            presentationCandidates == null ? Collections.emptyList() : presentationCandidates,
            visibleItems == null ? Collections.emptyList() : visibleItems,
            itemColors == null ? Collections.emptyMap() : itemColors,
            filterMode);
    }

    @Nullable
    public RewardObservation getObservation()
    {
        return observation;
    }

    /** Source label from the complete observation — never rewritten by filtering. */
    public String getSourceName()
    {
        return observation == null ? "" : observation.getSourceName();
    }

    public List<RewardItem> getVisibleItems()
    {
        return visibleItems;
    }

    public List<RewardItem> visibleItemsByValueDesc()
    {
        List<RewardItem> sorted = new ArrayList<>(visibleItems);
        sorted.sort((a, b) -> {
            int byValue = Long.compare(b.getRecordedValue(), a.getRecordedValue());
            return byValue != 0 ? byValue : Integer.compare(a.getItemId(), b.getItemId());
        });
        return sorted;
    }

    @Nullable
    public RewardItem getSelectedVisibleItem()
    {
        return selectedVisibleItem;
    }

    public long getVisibleLootTotal()
    {
        return visibleLootTotal;
    }

    public boolean isVisibleLootKnown()
    {
        return visibleLootKnown;
    }

    /** Presentation candidates existed, but none remain visible under the active filter. */
    public boolean isAllFiltered()
    {
        return allFiltered;
    }

    public boolean isPartiallyFiltered()
    {
        return partiallyFiltered;
    }

    public boolean hasVisibleItems()
    {
        return !visibleItems.isEmpty();
    }

    /** Candidate stacks removed by presentation filters (not confirmed pickups). */
    public int getHiddenStackCount()
    {
        return Math.max(0, presentationCandidates.size() - visibleItems.size());
    }

    /**
     * Candidate stacks not in the visible projection (order preserved).
     * Empty when nothing was filtered out.
     */
    public List<RewardItem> getHiddenItems()
    {
        if (presentationCandidates.isEmpty())
        {
            return Collections.emptyList();
        }
        if (!allFiltered && !partiallyFiltered)
        {
            return Collections.emptyList();
        }
        if (allFiltered)
        {
            return Collections.unmodifiableList(new ArrayList<>(presentationCandidates));
        }
        java.util.HashSet<Integer> visibleIds = new java.util.HashSet<>();
        for (RewardItem item : visibleItems)
        {
            if (item != null)
            {
                visibleIds.add(item.getItemId());
            }
        }
        List<RewardItem> hidden = new ArrayList<>();
        for (RewardItem item : presentationCandidates)
        {
            if (item != null && !visibleIds.contains(item.getItemId()))
            {
                hidden.add(item);
            }
        }
        return Collections.unmodifiableList(hidden);
    }

    public LootPresentationFilter getFilterMode()
    {
        return filterMode;
    }

    @Nullable
    public Color colorFor(int itemId)
    {
        return itemColors.get(itemId);
    }

    /**
     * Projection used by renderers that still consume {@link RewardObservation}.
     * Preserves source/encounter/collection evidence; item list and totals are
     * visible-only. Returns null when all filtered or no observation.
     */
    @Nullable
    public RewardObservation asDisplayObservation()
    {
        if (observation == null || allFiltered || visibleItems.isEmpty())
        {
            return null;
        }
        // Same list reference as complete observation → no projection/filter rewrite.
        if (!partiallyFiltered
            && filterMode == LootPresentationFilter.ALL_ITEMS
            && visibleItems.size() == observation.getItems().size()
            && presentationCandidates.size() == observation.getItems().size())
        {
            return observation;
        }
        return new RewardObservation(
            observation.getRewardId(),
            observation.getDedupeKey(),
            observation.getSourceKind(),
            observation.getSourceName(),
            observation.getEncounterId(),
            observation.getObservedAtEpochMillis(),
            visibleItems,
            observation.getConfirmedQuantities(),
            observation.getGeneration(),
            observation.getOwnerScopeKey(),
            observation.getBatch());
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof FilteredRewardView))
        {
            return false;
        }
        FilteredRewardView that = (FilteredRewardView) o;
        return Objects.equals(observation, that.observation)
            && Objects.equals(visibleItems, that.visibleItems)
            && filterMode == that.filterMode;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(observation, visibleItems, filterMode);
    }
}
