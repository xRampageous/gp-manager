package com.gpmanager.ui;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Singleton;

/**
 * Persistent latest-drop highlight plus a separate transient arrival animation
 * window. Shared by HUD and Infobox presentations.
 *
 * <p>Encounter-scoped offers merge split deliveries and may change the winning
 * item as stacks accumulate. Skilling offers without an encounter id stack the
 * same item (oak logs ×1 → ×2 → ×3). Animation expiry never clears the highlight.
 */
@Singleton
public class LatestDropModel
{
    /** Explicit integrated arrival duration; not floating hang time. */
    public static final long INTEGRATED_ARRIVAL_MILLIS = 900L;

    private final AtomicInteger generationCounter = new AtomicInteger();
    private final List<Contribution> contributions = new ArrayList<>();
    private LatestDropHighlight highlight;
    private long animationExpiresAtEpochMillis;
    private int generation;
    private int replacementCount;
    private int animationStartCount;

    public synchronized void clear()
    {
        contributions.clear();
        highlight = null;
        animationExpiresAtEpochMillis = 0L;
        generation = generationCounter.incrementAndGet();
    }

    /**
     * Remove a contributing transaction (correction/undo). Rebuilds the highlight
     * from remaining contributions without a fake arrival animation.
     */
    public synchronized void invalidateSource(String transactionId)
    {
        if (transactionId == null || transactionId.isEmpty())
        {
            return;
        }
        boolean removed = false;
        for (Iterator<Contribution> it = contributions.iterator(); it.hasNext(); )
        {
            if (transactionId.equals(it.next().transactionId))
            {
                it.remove();
                removed = true;
            }
        }
        if (!removed)
        {
            return;
        }
        rebuild(System.currentTimeMillis(), false);
    }

    public synchronized void offer(ProfitTransaction transaction, long now, long animationMillis)
    {
        offer(transaction, now, animationMillis, true);
    }

    public synchronized void offer(
        ProfitTransaction transaction,
        long now,
        long animationMillis,
        boolean animateArrival)
    {
        if (transaction == null || !isEligibleDrop(transaction))
        {
            return;
        }
        Map<Integer, Aggregate> gained = aggregatesFrom(transaction);
        if (gained.isEmpty())
        {
            return;
        }

        String encounterId = safeEncounter(transaction.getEncounterId());
        LatestDropHighlight previous = highlight;

        if (!encounterId.isEmpty())
        {
            // New kill/encounter replaces the previous bag entirely.
            if (previous != null && !encounterId.equals(previous.getEncounterId()))
            {
                contributions.clear();
            }
            else if (previous != null && previous.getEncounterId().isEmpty())
            {
                contributions.clear();
            }
        }
        else
        {
            // Skilling / no encounter: keep stacking only while the same item
            // continues. A different item starts a new highlight bag.
            Aggregate incomingBest = bestOf(gained);
            if (previous != null
                && !previous.getEncounterId().isEmpty())
            {
                contributions.clear();
            }
            else if (previous != null
                && incomingBest != null
                && previous.getItemId() != incomingBest.itemId)
            {
                contributions.clear();
            }
        }

        contributions.add(new Contribution(
            transaction.getId(),
            encounterId,
            gained,
            now));

        boolean winnerChanged = rebuild(now, false);
        if (highlight == null)
        {
            return;
        }
        boolean isNewLogicalDrop = previous == null
            || (!encounterId.isEmpty() && !encounterId.equals(previous.getEncounterId()))
            || (encounterId.isEmpty() && previous.getItemId() != highlight.getItemId())
            || winnerChanged;

        if (isNewLogicalDrop && previous != null)
        {
            replacementCount++;
        }

        if (animateArrival && animationMillis > 0L && (previous == null || isNewLogicalDrop || winnerChanged))
        {
            long duration = Math.max(400L, animationMillis);
            animationExpiresAtEpochMillis = now + duration;
            animationStartCount++;
        }
        else if (!animateArrival || animationMillis <= 0L)
        {
            animationExpiresAtEpochMillis = 0L;
        }
        // Same-item skilling stack / same-winner encounter accumulation: keep
        // highlight, do not extend or restart arrival animation.
    }

    public synchronized LatestDropHighlight highlight()
    {
        return highlight;
    }

    public synchronized boolean isAnimating(long now)
    {
        return highlight != null && now < animationExpiresAtEpochMillis;
    }

    public synchronized float animationProgress(long now, long animationMillis)
    {
        if (animationMillis <= 0L || !isAnimating(now))
        {
            return 1f;
        }
        long duration = Math.max(400L, animationMillis);
        long started = animationExpiresAtEpochMillis - duration;
        float progress = (now - started) / (float) duration;
        return Math.max(0f, Math.min(1f, progress));
    }

    public synchronized int getGeneration()
    {
        return generation;
    }

    public synchronized int getReplacementCount()
    {
        return replacementCount;
    }

    public synchronized int getAnimationStartCount()
    {
        return animationStartCount;
    }

    public synchronized int getContributionCount()
    {
        return contributions.size();
    }

    /**
     * @return true when the winning item identity changed
     */
    private boolean rebuild(long now, boolean animate)
    {
        LatestDropHighlight previous = highlight;
        if (contributions.isEmpty())
        {
            highlight = null;
            animationExpiresAtEpochMillis = 0L;
            generation = generationCounter.incrementAndGet();
            return previous != null;
        }

        Map<Integer, Aggregate> bag = new HashMap<>();
        String encounterId = contributions.get(contributions.size() - 1).encounterId;
        String latestTxId = contributions.get(contributions.size() - 1).transactionId;
        for (Contribution contribution : contributions)
        {
            for (Aggregate part : contribution.items.values())
            {
                Aggregate existing = bag.get(part.itemId);
                if (existing == null)
                {
                    bag.put(part.itemId, new Aggregate(part));
                }
                else
                {
                    existing.merge(part);
                }
            }
        }
        Aggregate best = bestOf(bag);
        if (best == null)
        {
            highlight = null;
            generation = generationCounter.incrementAndGet();
            return previous != null;
        }

        int nextGeneration = generationCounter.incrementAndGet();
        highlight = new LatestDropHighlight(
            best.itemId,
            best.itemName,
            best.quantity,
            best.valueKnown ? best.value : 0L,
            best.valueKnown,
            best.priceSource,
            encounterId,
            latestTxId,
            previous != null && previous.getItemId() == best.itemId
                ? previous.getCreatedAtEpochMillis()
                : now,
            nextGeneration);
        generation = nextGeneration;

        boolean winnerChanged = previous != null && previous.getItemId() != best.itemId;
        if (animate && winnerChanged)
        {
            animationExpiresAtEpochMillis = now + 2_000L;
            animationStartCount++;
        }
        return winnerChanged;
    }

    static boolean isEligibleDrop(ProfitTransaction transaction)
    {
        if (transaction.getCorrection() != TransactionCorrection.AUTO)
        {
            return false;
        }
        TransactionType type = transaction.getType();
        if (type == TransactionType.TRANSFER
            || type == TransactionType.TRADE
            || type == TransactionType.CONSUMPTION
            || type == TransactionType.PK_SUPPLY_COST
            || type == TransactionType.PK_DEATH_LOSS
            || type == TransactionType.PK_FEE)
        {
            return false;
        }
        List<ItemFlow> flows = transaction.getFlows();
        if (flows == null || flows.isEmpty())
        {
            return false;
        }
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getQuantityDelta() > 0L)
            {
                return true;
            }
        }
        return false;
    }

    private static Map<Integer, Aggregate> aggregatesFrom(ProfitTransaction transaction)
    {
        Map<Integer, Aggregate> byItem = new HashMap<>();
        List<ItemFlow> flows = transaction.getFlows();
        if (flows == null)
        {
            return byItem;
        }
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() <= 0L)
            {
                continue;
            }
            Aggregate aggregate = byItem.computeIfAbsent(flow.getItemId(), id -> new Aggregate(flow));
            aggregate.add(flow);
        }
        return byItem;
    }

    private static Aggregate bestOf(Map<Integer, Aggregate> byItem)
    {
        Aggregate best = null;
        for (Aggregate candidate : byItem.values())
        {
            if (best == null || candidate.beats(best))
            {
                best = candidate;
            }
        }
        return best;
    }

    private static String safeEncounter(String encounterId)
    {
        return encounterId == null ? "" : encounterId;
    }

    private static final class Contribution
    {
        private final String transactionId;
        private final String encounterId;
        private final Map<Integer, Aggregate> items;
        private final long offeredAtEpochMillis;

        private Contribution(
            String transactionId,
            String encounterId,
            Map<Integer, Aggregate> items,
            long offeredAtEpochMillis)
        {
            this.transactionId = transactionId == null ? "" : transactionId;
            this.encounterId = encounterId;
            this.items = items;
            this.offeredAtEpochMillis = offeredAtEpochMillis;
        }
    }

    private static final class Aggregate
    {
        private final int itemId;
        private String itemName;
        private long quantity;
        private long value;
        private boolean valueKnown = true;
        private ItemPriceSource priceSource = ItemPriceSource.UNKNOWN;

        private Aggregate(ItemFlow seed)
        {
            this.itemId = seed.getItemId();
            this.itemName = seed.getItemName();
        }

        private Aggregate(Aggregate seed)
        {
            this.itemId = seed.itemId;
            this.itemName = seed.itemName;
            this.quantity = seed.quantity;
            this.value = seed.value;
            this.valueKnown = seed.valueKnown;
            this.priceSource = seed.priceSource;
        }

        private void add(ItemFlow flow)
        {
            quantity += flow.getQuantityDelta();
            if (flow.getPriceSource() == ItemPriceSource.UNKNOWN && flow.getValueDelta() == 0L)
            {
                valueKnown = false;
            }
            else
            {
                value += flow.getValueDelta();
                priceSource = flow.getPriceSource();
            }
            if (itemName == null || itemName.isEmpty())
            {
                itemName = flow.getItemName();
            }
        }

        private void merge(Aggregate other)
        {
            quantity += other.quantity;
            if (!other.valueKnown)
            {
                valueKnown = false;
            }
            else
            {
                value += other.value;
                priceSource = other.priceSource;
            }
            if ((itemName == null || itemName.isEmpty()) && other.itemName != null)
            {
                itemName = other.itemName;
            }
        }

        private boolean beats(Aggregate other)
        {
            if (valueKnown != other.valueKnown)
            {
                return valueKnown;
            }
            if (value != other.value)
            {
                return value > other.value;
            }
            if (quantity != other.quantity)
            {
                return quantity > other.quantity;
            }
            if (itemId != other.itemId)
            {
                return itemId > other.itemId;
            }
            String left = itemName == null ? "" : itemName;
            String right = other.itemName == null ? "" : other.itemName;
            return left.compareToIgnoreCase(right) > 0;
        }
    }
}
