package com.gpmanager.engine;

import com.gpmanager.model.ItemFlow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * PvM death → reclaim lifecycle. A separate held-stack snapshot is intersected with
 * measured negative inventory flows to establish which items actually left the player.
 * Those exact quantities remain ownership-neutral until reclaimed or the gravestone timer
 * expires. The explanatory {@link LocalDeathEvidence} is intentionally not used here.
 *
 * <p>Fees are never inferred here; the engine can book only an observed carried-coin loss.
 * A timer expiry records an informational audit event and never creates an item loss.
 *
 * <p>Pure tick-counted state; the engine owns the single instance.
 */
final class DeathReclaimLifecycle
{
    /** Ticks a gravestone retains items. */
    static final int AWAIT_TICKS = 1_500; // 15 minutes
    /** Keep a grave interaction armed through the usual open/confirm/claim sequence. */
    static final int MIN_RECLAIM_ARM_TICKS = 200; // 2 minutes
    /** Bound delayed death-wipe reconciliation so later ordinary losses cannot match it. */
    static final int DEATH_WIPE_WINDOW_TICKS = 100; // 1 minute

    private boolean awaitingReclaim;
    private boolean deathWipePending;
    private int deathWipeTicks;
    private int ageTicks;
    private int reclaimTicks;
    @Nullable
    private BossRetrievalCatalogue.Service reclaimService;
    /** Null means this caller supplied no snapshot, for legacy/offline engine callers. */
    @Nullable
    private Map<Integer, Long> carriedAtDeath;
    private final Map<Integer, OutstandingItem> deathItems = new HashMap<>();

    /** Local unsafe PvM death: the snapshot is ownership evidence, not presentation evidence. */
    void onLocalPvmDeath()
    {
        onLocalPvmDeath(null);
    }

    /** Local unsafe PvM death: the snapshot is ownership evidence, not presentation evidence. */
    void onLocalPvmDeath(@Nullable Map<Integer, Long> heldItems)
    {
        // A second death moves outstanding items to the new gravestone. Keep their
        // quantities and restart the single gravestone timer with the new grave.
        if (!awaitingReclaim)
        {
            deathItems.clear();
        }
        awaitingReclaim = true;
        carriedAtDeath = heldItems == null ? null : positiveCopy(heldItems);
        // Missing canonical evidence is not permission to neutralize arbitrary losses.
        deathWipePending = carriedAtDeath != null && !carriedAtDeath.isEmpty();
        deathWipeTicks = deathWipePending ? DEATH_WIPE_WINDOW_TICKS : 0;
        ageTicks = 0;
        reclaimTicks = 0;
        reclaimService = null;
        completeIfEmpty();
    }

    /** True while waiting for the measured death inventory/equipment removal. */
    boolean isDeathWipePending()
    {
        return awaitingReclaim && deathWipePending;
    }

    /** Stop the short death-wipe whitelist when stronger later evidence owns the loss. */
    void cancelDeathWipe()
    {
        deathWipePending = false;
        deathWipeTicks = 0;
        carriedAtDeath = null;
        completeIfEmpty();
    }

    /** Consume death-time candidates whose later loss is explicitly action-attributed. */
    void excludeActionLosses(Map<Integer, Long> actionLosses)
    {
        if (!deathWipePending || actionLosses == null || actionLosses.isEmpty())
        {
            return;
        }
        if (carriedAtDeath == null)
        {
            cancelDeathWipe();
            return;
        }
        for (Map.Entry<Integer, Long> loss : actionLosses.entrySet())
        {
            if (loss.getKey() == null || loss.getValue() == null || loss.getValue() <= 0L)
            {
                continue;
            }
            long held = carriedAtDeath.getOrDefault(loss.getKey(), 0L);
            long remaining = held - Math.min(held, loss.getValue());
            if (remaining <= 0L)
            {
                carriedAtDeath.remove(loss.getKey());
            }
            else
            {
                carriedAtDeath.put(loss.getKey(), remaining);
            }
        }
        if (carriedAtDeath.isEmpty())
        {
            deathWipePending = false;
            deathWipeTicks = 0;
            carriedAtDeath = null;
        }
        completeIfEmpty();
    }

    /**
     * Intersect a settled negative-flow batch with the stacks held at death. The returned
     * quantities identify the exact negative flow portions that may be labelled as the
     * ownership-neutral death transfer.
     */
    Map<Integer, Long> onDeathItemsRemoved(List<ItemFlow> flows)
    {
        if (!isDeathWipePending())
        {
            return Collections.emptyMap();
        }
        if (carriedAtDeath == null)
        {
            return Collections.emptyMap();
        }
        Map<Integer, Long> removed = new HashMap<>();
        if (flows != null)
        {
            for (ItemFlow flow : flows)
            {
                if (flow == null || flow.getQuantityDelta() >= 0L
                    || flow.getQuantityDelta() == Long.MIN_VALUE)
                {
                    continue;
                }
                long observed = -flow.getQuantityDelta();
                long candidate = carriedAtDeath.getOrDefault(flow.getItemId(), 0L);
                if (flow.getItemId() == 995 && isReclaimArmed())
                {
                    // A live retrieval interaction makes measured coin loss a possible
                    // fee; do not consume it as a delayed death wipe.
                    continue;
                }
                long matched = Math.min(observed, Math.max(0L, candidate));
                if (matched <= 0L)
                {
                    continue;
                }
                removed.put(flow.getItemId(), matched);
                // Coins can be part of the measured death wipe transfer, but they are
                // never reclaim whitelist items: a later coin loss may be the observed fee.
                if (flow.getItemId() != 995)
                {
                    mergeOutstanding(flow.getItemId(), flow.getItemName(), matched);
                }
                if (carriedAtDeath != null)
                {
                    long left = candidate - matched;
                    if (left <= 0L)
                    {
                        carriedAtDeath.remove(flow.getItemId());
                    }
                    else
                    {
                        carriedAtDeath.put(flow.getItemId(), left);
                    }
                }
            }
        }
        if (carriedAtDeath.isEmpty())
        {
            deathWipePending = false;
            deathWipeTicks = 0;
            carriedAtDeath = null;
        }
        completeIfEmpty();
        return removed.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(removed);
    }

    private void mergeOutstanding(int itemId, @Nullable String itemName, long quantity)
    {
        if (quantity <= 0L)
        {
            return;
        }
        OutstandingItem previous = deathItems.get(itemId);
        long existing = previous == null ? 0L : previous.quantity;
        String name = itemName == null || itemName.trim().isEmpty()
            ? previous == null ? "item #" + itemId : previous.name
            : itemName.trim();
        deathItems.put(itemId, new OutstandingItem(name, saturatedAdd(existing, quantity)));
    }

    /**
     * Match a positive return against the still-outstanding measured death quantity.
     * Returns zero when the item was not part of the actual settled death wipe.
     */
    long matchReturnedItem(int itemId, long quantity)
    {
        if (quantity <= 0L)
        {
            return 0L;
        }
        OutstandingItem removed = deathItems.get(itemId);
        if (removed == null || removed.quantity <= 0L)
        {
            return 0L;
        }
        long matched = Math.min(removed.quantity, quantity);
        long remaining = removed.quantity - matched;
        if (remaining == 0L)
        {
            deathItems.remove(itemId);
        }
        else
        {
            deathItems.put(itemId, new OutstandingItem(removed.name, remaining));
        }
        if (deathItems.isEmpty())
        {
            // All measured grave contents have returned. Remaining stacks from the
            // death-time snapshot were kept and can no longer be a delayed wipe.
            deathWipePending = false;
            deathWipeTicks = 0;
            carriedAtDeath = null;
        }
        completeIfEmpty();
        return matched;
    }

    boolean hasOutstandingItems()
    {
        return !deathItems.isEmpty();
    }

    long outstandingItemCount()
    {
        long count = 0L;
        for (OutstandingItem item : deathItems.values())
        {
            count = saturatedAdd(count, item.quantity);
        }
        return count;
    }

    int ageTicks()
    {
        return ageTicks;
    }

    boolean isAwaitingReclaim()
    {
        return awaitingReclaim;
    }

    /**
     * Player interacted with a retrieval service. A fresh Grave / Gravestone click can
     * always re-arm while this local death remains active.
     *
     * @return true only when a local PvM death is awaiting reclaim
     */
    boolean noteReclaimIntent(BossRetrievalCatalogue.Service service, int ticks)
    {
        if (service == null || !isAwaitingReclaim())
        {
            return false;
        }
        reclaimService = service;
        reclaimTicks = Math.max(MIN_RECLAIM_ARM_TICKS, ticks);
        return true;
    }

    boolean isReclaimArmed()
    {
        return reclaimTicks > 0 && reclaimService != null;
    }

    @Nullable
    BossRetrievalCatalogue.Service reclaimService()
    {
        return reclaimService;
    }

    /** Reclaim settled: the death is no longer awaiting. */
    void onReclaimed()
    {
        reset();
    }

    /** One inventory/game tick. Returns an informational expiry snapshot once. */
    @Nullable
    Expired tick()
    {
        return tick(false);
    }

    /** One inventory/game tick; defers gravestone expiry while a snapshot is unsettled. */
    @Nullable
    Expired tick(boolean deferGravestoneExpiry)
    {
        if (awaitingReclaim && ageTicks < Integer.MAX_VALUE)
        {
            ageTicks++;
        }
        if (reclaimTicks > 0)
        {
            reclaimTicks--;
            if (reclaimTicks == 0)
            {
                reclaimService = null;
            }
        }
        if (deathWipePending && deathWipeTicks > 0)
        {
            deathWipeTicks--;
            if (deathWipeTicks == 0)
            {
                deathWipePending = false;
                carriedAtDeath = null;
                completeIfEmpty();
            }
        }
        if (!deferGravestoneExpiry && awaitingReclaim && ageTicks >= AWAIT_TICKS)
        {
            Expired expired = deathItems.isEmpty() ? null : new Expired(deathItems);
            onReclaimed();
            return expired;
        }
        return null;
    }

    void reset()
    {
        awaitingReclaim = false;
        deathWipePending = false;
        deathWipeTicks = 0;
        ageTicks = 0;
        reclaimTicks = 0;
        reclaimService = null;
        carriedAtDeath = null;
        deathItems.clear();
    }

    private void completeIfEmpty()
    {
        if (awaitingReclaim && !deathWipePending && deathItems.isEmpty())
        {
            onReclaimed();
        }
    }

    private static Map<Integer, Long> positiveCopy(Map<Integer, Long> items)
    {
        Map<Integer, Long> copy = new HashMap<>();
        for (Map.Entry<Integer, Long> entry : items.entrySet())
        {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L)
            {
                copy.merge(entry.getKey(), entry.getValue(), DeathReclaimLifecycle::saturatedAdd);
            }
        }
        return copy;
    }

    private static long saturatedAdd(long left, long right)
    {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static final class OutstandingItem
    {
        private final String name;
        private final long quantity;

        private OutstandingItem(String name, long quantity)
        {
            this.name = name;
            this.quantity = quantity;
        }
    }

    static final class Expired
    {
        private final String items;
        private final long itemCount;

        private Expired(Map<Integer, OutstandingItem> outstanding)
        {
            List<String> labels = new ArrayList<>();
            long total = 0L;
            for (OutstandingItem item : outstanding.values())
            {
                labels.add(item.quantity == 1L
                    ? item.name
                    : item.name + " ×" + String.format(Locale.ROOT, "%,d", item.quantity));
                total = saturatedAdd(total, item.quantity);
            }
            Collections.sort(labels, String.CASE_INSENSITIVE_ORDER);
            StringBuilder summary = new StringBuilder();
            for (String label : labels)
            {
                if (summary.length() > 0)
                {
                    summary.append(", ");
                }
                summary.append(label);
            }
            items = summary.toString();
            itemCount = total;
        }

        String items()
        {
            return items;
        }

        long itemCount()
        {
            return itemCount;
        }
    }
}
