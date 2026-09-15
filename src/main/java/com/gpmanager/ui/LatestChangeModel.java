package com.gpmanager.ui;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TransactionCorrection;
import java.util.List;
import javax.inject.Singleton;

/** Builds and coalesces HUD latest-change notices from committed transactions. */
@Singleton
public class LatestChangeModel
{
    private static final int MAX_QUEUE = 8;
    private LatestChangeNotice current;
    private int overflowCount;
    private int coalesceCount;

    public synchronized void clear()
    {
        current = null;
    }

    public synchronized void offer(ProfitTransaction transaction, long now, long durationMillis)
    {
        if (transaction == null)
        {
            return;
        }
        LatestChangeNotice next = fromTransaction(transaction, now, durationMillis);
        if (current != null && !current.isExpired(now) && current.canCoalesce(next))
        {
            current = current.coalesce(next, now, durationMillis);
            coalesceCount++;
            return;
        }
        if (current != null && !current.isExpired(now))
        {
            overflowCount++;
        }
        current = next;
    }

    public synchronized LatestChangeNotice current(long now)
    {
        if (current == null || current.isExpired(now))
        {
            current = null;
            return null;
        }
        return current;
    }

    public synchronized int getOverflowCount()
    {
        return overflowCount;
    }

    public synchronized int getCoalesceCount()
    {
        return coalesceCount;
    }

    public synchronized int getQueueSize()
    {
        return current == null ? 0 : 1;
    }

    static LatestChangeNotice fromTransaction(ProfitTransaction transaction, long now, long durationMillis)
    {
        List<ItemFlow> flows = transaction.getFlows();
        boolean correction = transaction.getCorrection() != TransactionCorrection.AUTO;
        if (flows == null || flows.isEmpty())
        {
            return new LatestChangeNotice(
                0,
                transaction.getActivityName(),
                0L,
                transaction.getNet(),
                1,
                correction,
                now,
                now + durationMillis);
        }
        int distinct = 0;
        int firstId = 0;
        String firstName = "";
        long firstQty = 0L;
        for (ItemFlow flow : flows)
        {
            if (flow == null)
            {
                continue;
            }
            distinct++;
            if (distinct == 1)
            {
                firstId = flow.getItemId();
                firstName = flow.getItemName();
                firstQty = flow.getQuantityDelta();
            }
        }
        if (distinct <= 1)
        {
            return new LatestChangeNotice(
                firstId,
                firstName,
                firstQty,
                transaction.getNet(),
                1,
                correction,
                now,
                now + durationMillis);
        }
        return new LatestChangeNotice(
            0,
            distinct + " items",
            0L,
            transaction.getNet(),
            distinct,
            correction,
            now,
            now + durationMillis);
    }
}
