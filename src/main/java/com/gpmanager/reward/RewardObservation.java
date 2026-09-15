package com.gpmanager.reward;

import com.gpmanager.model.ItemPriceSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * One observed or confirmed reward batch. Display-only; loot totals are never
 * written into Net profit.
 */
public final class RewardObservation
{
    private final String rewardId;
    private final String dedupeKey;
    private final RewardSourceKind sourceKind;
    private final String sourceName;
    private final String encounterId;
    private final long observedAtEpochMillis;
    private final List<RewardItem> items;
    private final long lootValueTotal;
    private final boolean lootValueKnown;
    private final Map<Integer, Long> confirmedQuantities;
    private final int generation;
    private final String ownerScopeKey;
    private final RewardBatchState batch;

    public RewardObservation(
        String rewardId,
        String dedupeKey,
        RewardSourceKind sourceKind,
        String sourceName,
        String encounterId,
        long observedAtEpochMillis,
        List<RewardItem> items,
        boolean collectionConfirmed,
        int generation,
        String ownerScopeKey)
    {
        this(
            rewardId,
            dedupeKey,
            sourceKind,
            sourceName,
            encounterId,
            observedAtEpochMillis,
            items,
            collectionConfirmed ? fullyConfirmed(items) : Collections.emptyMap(),
            generation,
            ownerScopeKey,
            RewardBatchState.single());
    }

    public RewardObservation(
        String rewardId,
        String dedupeKey,
        RewardSourceKind sourceKind,
        String sourceName,
        String encounterId,
        long observedAtEpochMillis,
        List<RewardItem> items,
        Map<Integer, Long> confirmedQuantities,
        int generation,
        String ownerScopeKey)
    {
        this(
            rewardId,
            dedupeKey,
            sourceKind,
            sourceName,
            encounterId,
            observedAtEpochMillis,
            items,
            confirmedQuantities,
            generation,
            ownerScopeKey,
            RewardBatchState.single());
    }

    public RewardObservation(
        String rewardId,
        String dedupeKey,
        RewardSourceKind sourceKind,
        String sourceName,
        String encounterId,
        long observedAtEpochMillis,
        List<RewardItem> items,
        Map<Integer, Long> confirmedQuantities,
        int generation,
        String ownerScopeKey,
        RewardBatchState batch)
    {
        this.rewardId = rewardId == null ? "" : rewardId;
        this.dedupeKey = dedupeKey == null ? "" : dedupeKey;
        this.sourceKind = sourceKind == null ? RewardSourceKind.UNKNOWN : sourceKind;
        this.sourceName = sourceName == null || sourceName.trim().isEmpty() ? "Reward" : sourceName.trim();
        this.encounterId = encounterId == null ? "" : encounterId;
        this.observedAtEpochMillis = observedAtEpochMillis;
        List<RewardItem> copy = items == null ? Collections.emptyList() : new ArrayList<>(items);
        this.items = Collections.unmodifiableList(copy);
        long total = 0L;
        boolean known = !this.items.isEmpty();
        for (RewardItem item : this.items)
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
        }
        this.lootValueTotal = total;
        this.lootValueKnown = known && !this.items.isEmpty();
        this.confirmedQuantities = Collections.unmodifiableMap(normalizeConfirmed(confirmedQuantities, this.items));
        this.generation = generation;
        this.ownerScopeKey = ownerScopeKey == null ? "" : ownerScopeKey;
        this.batch = batch == null ? RewardBatchState.single() : batch;
    }

    private static Map<Integer, Long> fullyConfirmed(List<RewardItem> items)
    {
        Map<Integer, Long> map = new LinkedHashMap<>();
        if (items == null)
        {
            return map;
        }
        for (RewardItem item : items)
        {
            if (item != null && item.getItemId() > 0 && item.getQuantity() > 0L)
            {
                map.merge(item.getItemId(), item.getQuantity(), Long::sum);
            }
        }
        return map;
    }

    private static Map<Integer, Long> normalizeConfirmed(Map<Integer, Long> incoming, List<RewardItem> items)
    {
        Map<Integer, Long> expected = new HashMap<>();
        for (RewardItem item : items)
        {
            if (item != null && item.getItemId() > 0)
            {
                expected.merge(item.getItemId(), Math.max(0L, item.getQuantity()), Long::sum);
            }
        }
        Map<Integer, Long> out = new LinkedHashMap<>();
        if (incoming != null)
        {
            for (Map.Entry<Integer, Long> entry : incoming.entrySet())
            {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L)
                {
                    continue;
                }
                long cap = expected.getOrDefault(entry.getKey(), 0L);
                if (cap <= 0L)
                {
                    continue;
                }
                out.put(entry.getKey(), Math.min(cap, entry.getValue()));
            }
        }
        return out;
    }

    public static List<RewardItem> mergeStacks(List<RewardItem> incoming)
    {
        Map<Integer, RewardItem> byId = new LinkedHashMap<>();
        if (incoming != null)
        {
            for (RewardItem item : incoming)
            {
                if (item == null || item.getItemId() <= 0 || item.getQuantity() == 0L)
                {
                    continue;
                }
                RewardItem existing = byId.get(item.getItemId());
                byId.put(item.getItemId(), existing == null ? item : existing.merge(item));
            }
        }
        return new ArrayList<>(byId.values());
    }

    public RewardItem bestItem()
    {
        RewardItem best = null;
        for (RewardItem item : items)
        {
            if (item != null && item.beats(best))
            {
                best = item;
            }
        }
        return best;
    }

    /**
     * Highest-value stack with at least one confirmed unit. Used by the settled
     * HUD row so an unpicked top drop is never shown as the "counted" highlight.
     */
    @Nullable
    public RewardItem bestConfirmedItem()
    {
        RewardItem best = null;
        for (RewardItem item : items)
        {
            if (item == null || confirmedQuantity(item.getItemId()) <= 0L)
            {
                continue;
            }
            if (item.beats(best))
            {
                best = item;
            }
        }
        return best;
    }

    /**
     * Settled / compact highlight: prefer a confirmed stack; otherwise fall back
     * to {@link #bestItem()} (caller must still paint collection status honestly).
     */
    @Nullable
    public RewardItem bestSettledDisplayItem()
    {
        RewardItem confirmed = bestConfirmedItem();
        return confirmed != null ? confirmed : bestItem();
    }

    public List<RewardItem> itemsByValueDesc()
    {
        return itemsByValueDesc(items);
    }

    /** Value-sorted stacks for HUD painting — Ground Loot remaining / Received confirmed. */
    public List<RewardItem> presentationItemsByValueDesc()
    {
        return itemsByValueDesc(presentationStacks());
    }

    private static List<RewardItem> itemsByValueDesc(List<RewardItem> source)
    {
        List<RewardItem> sorted = new ArrayList<>(source == null ? Collections.emptyList() : source);
        sorted.sort(Comparator.comparingLong(RewardItem::getRecordedValue).reversed()
            .thenComparingInt(RewardItem::getItemId));
        return sorted;
    }

    public String fingerprint()
    {
        StringBuilder sb = new StringBuilder(sourceName).append('|');
        List<RewardItem> sorted = itemsByValueDesc();
        for (RewardItem item : sorted)
        {
            sb.append(item.getItemId()).append('x').append(item.getQuantity()).append(';');
        }
        return sb.toString();
    }

    public long confirmedQuantity(int itemId)
    {
        return confirmedQuantities.getOrDefault(itemId, 0L);
    }

    public long remainingQuantity(int itemId)
    {
        long expected = 0L;
        for (RewardItem item : items)
        {
            if (item != null && item.getItemId() == itemId)
            {
                expected += item.getQuantity();
            }
        }
        return Math.max(0L, expected - confirmedQuantity(itemId));
    }

    public CollectionStatus collectionStatus()
    {
        if (items.isEmpty() || sourceKind.isSkilling())
        {
            return CollectionStatus.UNCONFIRMED;
        }
        boolean anyExpected = false;
        boolean allDone = true;
        boolean anyConfirmed = false;
        for (RewardItem item : items)
        {
            if (item == null || item.getQuantity() <= 0L)
            {
                continue;
            }
            anyExpected = true;
            long confirmed = confirmedQuantity(item.getItemId());
            if (confirmed > 0L)
            {
                anyConfirmed = true;
            }
            if (confirmed < item.getQuantity())
            {
                allDone = false;
            }
        }
        if (!anyExpected)
        {
            return CollectionStatus.UNCONFIRMED;
        }
        if (allDone)
        {
            return CollectionStatus.COLLECTED;
        }
        if (anyConfirmed)
        {
            return CollectionStatus.PARTIAL;
        }
        return CollectionStatus.UNCONFIRMED;
    }

    public RewardObservation withMergedItems(List<RewardItem> moreItems, int nextGeneration)
    {
        List<RewardItem> merged = mergeStacks(new ArrayList<>(items));
        merged.addAll(moreItems == null ? Collections.emptyList() : moreItems);
        merged = mergeStacks(merged);
        return new RewardObservation(
            rewardId,
            dedupeKey,
            sourceKind,
            sourceName,
            encounterId,
            observedAtEpochMillis,
            merged,
            confirmedQuantities,
            nextGeneration,
            ownerScopeKey,
            batch);
    }

    /** Replace display stacks; keeps source/encounter/batch identity. */
    public RewardObservation withItems(List<RewardItem> nextItems, int nextGeneration)
    {
        return new RewardObservation(
            rewardId,
            dedupeKey,
            sourceKind,
            sourceName,
            encounterId,
            observedAtEpochMillis,
            nextItems == null ? Collections.emptyList() : nextItems,
            confirmedQuantities,
            nextGeneration,
            ownerScopeKey,
            batch);
    }

    /**
     * Presentation-only source label (e.g. neutral "Skilling" when multiple
     * resources share Always-expanded receipts). Does not change accounting.
     */
    public RewardObservation withDisplaySourceName(String displaySourceName)
    {
        return new RewardObservation(
            rewardId,
            dedupeKey,
            sourceKind,
            displaySourceName == null || displaySourceName.isEmpty() ? sourceName : displaySourceName,
            encounterId,
            observedAtEpochMillis,
            items,
            confirmedQuantities,
            generation,
            ownerScopeKey,
            batch);
    }

    /** Presentation-only kind/name rewrite (e.g. Used → Burned after Firemaking XP). */
    public RewardObservation withSourceKindAndName(RewardSourceKind nextKind, String nextName)
    {
        return new RewardObservation(
            rewardId,
            dedupeKey,
            nextKind == null ? sourceKind : nextKind,
            nextName == null || nextName.isEmpty() ? sourceName : nextName,
            encounterId,
            observedAtEpochMillis,
            items,
            confirmedQuantities,
            generation,
            ownerScopeKey,
            batch);
    }

    /**
     * Cross-adapter duplicate of the same encounter: keep quantities/values and
     * confirmation evidence exactly; only bump generation for render coherence.
     */
    public RewardObservation withRetainedIdentity(int nextGeneration)
    {
        return new RewardObservation(
            rewardId,
            dedupeKey,
            sourceKind,
            sourceName,
            encounterId,
            observedAtEpochMillis,
            items,
            confirmedQuantities,
            nextGeneration,
            ownerScopeKey,
            batch);
    }

    /**
     * Coalesce another confirmed same-source encounter into this presentation
     * batch. Sums display stacks; increments encounter count (never derived
     * from bone/stack quantity alone).
     */
    public RewardObservation withBatchedEncounter(List<RewardItem> moreItems, int nextGeneration)
    {
        return withBatchedEncounter(moreItems, nextGeneration, observedAtEpochMillis);
    }

    public RewardObservation withBatchedEncounter(List<RewardItem> moreItems, int nextGeneration, long observedAt)
    {
        List<RewardItem> merged = mergeStacks(new ArrayList<>(items));
        merged.addAll(moreItems == null ? Collections.emptyList() : moreItems);
        merged = mergeStacks(merged);
        return new RewardObservation(
            rewardId,
            dedupeKey,
            sourceKind,
            sourceName,
            encounterId,
            observedAt,
            merged,
            confirmedQuantities,
            nextGeneration,
            ownerScopeKey,
            batch.withAddedEncounter(true, false));
    }

    /**
     * Display stacks for Ground Loot / Received trays.
     * <ul>
     *   <li>Unconfirmed NPC drops → full stacks (Ground Loot)</li>
     *   <li>Partial floor pickups → confirmed quantities only (Received)</li>
     *   <li>Fully collected → complete stacks (Received)</li>
     * </ul>
     */
    public List<RewardItem> presentationStacks()
    {
        if (items.isEmpty() || !sourceKind.isObservedLoot())
        {
            return items;
        }
        CollectionStatus status = collectionStatus();
        if (status == CollectionStatus.COLLECTED)
        {
            return items;
        }
        if (status == CollectionStatus.PARTIAL)
        {
            return confirmedDisplayStacks();
        }
        // UNCONFIRMED Ground Loot — still-expected drop stacks.
        return unconfirmedDisplayStacks();
    }

    /** Confirmed floor-pickup quantities for Received. */
    private List<RewardItem> confirmedDisplayStacks()
    {
        List<RewardItem> confirmed = new ArrayList<>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (RewardItem item : items)
        {
            if (item == null || item.getQuantity() <= 0L || !seen.add(item.getItemId()))
            {
                continue;
            }
            long got = confirmedQuantity(item.getItemId());
            if (got <= 0L)
            {
                continue;
            }
            confirmed.add(scaleStack(item.getItemId(), got));
        }
        return confirmed;
    }

    /** Remaining unpicked quantities for Ground Loot. */
    private List<RewardItem> unconfirmedDisplayStacks()
    {
        List<RewardItem> remaining = new ArrayList<>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (RewardItem item : items)
        {
            if (item == null || item.getQuantity() <= 0L || !seen.add(item.getItemId()))
            {
                continue;
            }
            long rem = remainingQuantity(item.getItemId());
            if (rem <= 0L)
            {
                continue;
            }
            remaining.add(scaleStack(item.getItemId(), rem));
        }
        return remaining;
    }

    private RewardItem scaleStack(int itemId, long qty)
    {
        long expected = 0L;
        long valueSum = 0L;
        boolean known = true;
        String name = "";
        ItemPriceSource price = ItemPriceSource.UNKNOWN;
        for (RewardItem stack : items)
        {
            if (stack == null || stack.getItemId() != itemId)
            {
                continue;
            }
            expected += Math.max(0L, stack.getQuantity());
            if (!stack.isValueKnown())
            {
                known = false;
            }
            else
            {
                valueSum += stack.getRecordedValue();
            }
            if (name.isEmpty())
            {
                name = stack.getItemName();
            }
            if (price == ItemPriceSource.UNKNOWN)
            {
                price = stack.getPriceSource();
            }
        }
        long scaled = 0L;
        if (known && expected > 0L)
        {
            scaled = valueSum * qty / expected;
        }
        else
        {
            known = false;
            scaled = 0L;
        }
        return new RewardItem(itemId, name, qty, scaled, known && expected > 0L, price);
    }

    public RewardObservation withCollectionConfirmed(int nextGeneration)
    {
        return new RewardObservation(
            rewardId,
            dedupeKey,
            sourceKind,
            sourceName,
            encounterId,
            observedAtEpochMillis,
            items,
            fullyConfirmed(items),
            nextGeneration,
            ownerScopeKey,
            batch);
    }

    /**
     * Apply acquired quantities toward remaining unconfirmed stacks. Each unit
     * is confirmed at most once. Returns this instance when nothing applies.
     */
    public RewardObservation withCollectionApplied(List<RewardItem> acquired, int nextGeneration)
    {
        if (acquired == null || acquired.isEmpty())
        {
            return this;
        }
        Map<Integer, Long> next = new LinkedHashMap<>(confirmedQuantities);
        boolean changed = false;
        for (RewardItem item : mergeStacks(acquired))
        {
            if (item == null)
            {
                continue;
            }
            long remaining = remainingQuantity(item.getItemId());
            if (remaining <= 0L)
            {
                continue;
            }
            long apply = Math.min(remaining, item.getQuantity());
            if (apply <= 0L)
            {
                continue;
            }
            next.merge(item.getItemId(), apply, Long::sum);
            changed = true;
        }
        if (!changed)
        {
            return this;
        }
        return new RewardObservation(
            rewardId,
            dedupeKey,
            sourceKind,
            sourceName,
            encounterId,
            observedAtEpochMillis,
            items,
            next,
            nextGeneration,
            ownerScopeKey,
            batch);
    }

    /** Remove previously confirmed quantities after a correction/undo. */
    public RewardObservation withCollectionRemoved(List<RewardItem> removed, int nextGeneration)
    {
        if (removed == null || removed.isEmpty() || confirmedQuantities.isEmpty())
        {
            return this;
        }
        Map<Integer, Long> next = new LinkedHashMap<>(confirmedQuantities);
        boolean changed = false;
        for (RewardItem item : mergeStacks(removed))
        {
            if (item == null)
            {
                continue;
            }
            long have = next.getOrDefault(item.getItemId(), 0L);
            if (have <= 0L)
            {
                continue;
            }
            long drop = Math.min(have, item.getQuantity());
            long left = have - drop;
            if (left <= 0L)
            {
                next.remove(item.getItemId());
            }
            else
            {
                next.put(item.getItemId(), left);
            }
            changed = true;
        }
        if (!changed)
        {
            return this;
        }
        return new RewardObservation(
            rewardId,
            dedupeKey,
            sourceKind,
            sourceName,
            encounterId,
            observedAtEpochMillis,
            items,
            next,
            nextGeneration,
            ownerScopeKey,
            batch);
    }

    public String getRewardId()
    {
        return rewardId;
    }

    public String getDedupeKey()
    {
        return dedupeKey;
    }

    public RewardSourceKind getSourceKind()
    {
        return sourceKind;
    }

    public String getSourceName()
    {
        return sourceName;
    }

    /** Display header including optional batch suffix (e.g. {@code Chicken · 4 kills}). */
    public String displaySourceLabel()
    {
        String base = headerSourceBase();
        if (base.isEmpty())
        {
            return "";
        }
        int encounters = batch.getEncounterCount();
        if (encounters <= 1)
        {
            return base;
        }
        if (sourceKind.isObservedLoot() || "chest".equalsIgnoreCase(sourceName))
        {
            return base + " \u00d7" + encounters;
        }
        return base + " \u00d7" + encounters + " rewards";
    }

    /**
     * Header-facing source text. Gather skills never title the HUD — leave empty so
     * interaction scenery (Willow tree) or Tracking wins. Item names stay on the tray.
     * Peer tray tags (Dropped/Traded/Pending Rewards/…) never rewrite the header.
     */
    private String headerSourceBase()
    {
        String raw = sourceName == null ? "" : sourceName.trim();
        if (sourceKind != null && sourceKind.isSkilling()
            && com.gpmanager.ui.HudPlusProcessLabels.isGatherSkillTitle(raw))
        {
            return "";
        }
        if (sourceKind != null && isTrayOnlyPeerTag(sourceKind, raw))
        {
            return "";
        }
        return raw;
    }

    /** Presentation tray tags that must not become the HUD+ interaction header. */
    private static boolean isTrayOnlyPeerTag(RewardSourceKind kind, String sourceName)
    {
        if (kind == RewardSourceKind.BANKED
            || kind == RewardSourceKind.TRADED
            || kind == RewardSourceKind.RECOVERED
            || kind == RewardSourceKind.CHARGED
            || kind == RewardSourceKind.STORED
            || kind == RewardSourceKind.USED
            || kind == RewardSourceKind.LOST)
        {
            return true;
        }
        if (sourceName == null || sourceName.isEmpty())
        {
            return kind == RewardSourceKind.PENDING_REWARDS || kind == RewardSourceKind.CLAIMED;
        }
        // Action receipts (Drank / Ate / Decanted / Buried / Offered / Supplies used) are
        // what happened, not where you are — the header keeps the target, skill or
        // Tracking so the same word is never painted twice.
        if (com.gpmanager.model.ActionKind.fromCompletedVerb(sourceName) != null)
        {
            return true;
        }
        String lower = sourceName.toLowerCase(java.util.Locale.ROOT);
        if ("dropped".equals(lower)
            || "destroyed".equals(lower)
            || "traded".equals(lower)
            || "banked".equals(lower)
            || "recovered".equals(lower)
            || "claimed".equals(lower)
            || "pending rewards".equals(lower)
            || "used".equals(lower)
            || "lost".equals(lower))
        {
            return true;
        }
        // Kind is Claimed/Pending Rewards but source is a raid/chest name — keep for header.
        return false;
    }

    public RewardBatchState getBatch()
    {
        return batch;
    }

    public String getEncounterId()
    {
        return encounterId;
    }

    public long getObservedAtEpochMillis()
    {
        return observedAtEpochMillis;
    }

    public List<RewardItem> getItems()
    {
        return items;
    }

    public long getLootValueTotal()
    {
        return lootValueTotal;
    }

    public boolean isLootValueKnown()
    {
        return lootValueKnown;
    }

    /** True only when every expected stack is fully confirmed. */
    public boolean isCollectionConfirmed()
    {
        return collectionStatus() == CollectionStatus.COLLECTED;
    }

    public Map<Integer, Long> getConfirmedQuantities()
    {
        return confirmedQuantities;
    }

    public int getGeneration()
    {
        return generation;
    }

    public String getOwnerScopeKey()
    {
        return ownerScopeKey;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof RewardObservation))
        {
            return false;
        }
        RewardObservation that = (RewardObservation) o;
        return Objects.equals(rewardId, that.rewardId) && generation == that.generation;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(rewardId, generation);
    }
}
