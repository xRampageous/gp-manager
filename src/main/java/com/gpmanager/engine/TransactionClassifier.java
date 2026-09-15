package com.gpmanager.engine;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.util.List;
import javax.inject.Singleton;

@Singleton
public class TransactionClassifier
{
    public TransactionType classify(TrackingContext context, List<ItemFlow> flows)
    {
        if (context == TrackingContext.TRANSFER)
        {
            return TransactionType.TRANSFER;
        }

        boolean hasGain = false;
        boolean hasCost = false;

        for (ItemFlow flow : flows)
        {
            // Direction comes from quantity. An unavailable unit price produces a
            // zero value, but it is still a real gain or cost that belongs in Review.
            hasGain |= flow.isGain();
            hasCost |= flow.isCost();
        }

        if (context == TrackingContext.MARKET)
        {
            return TransactionType.TRADE;
        }

        if (context == TrackingContext.PK_LOOT && hasGain)
        {
            return TransactionType.PK_LOOT;
        }

        if (context == TrackingContext.PK_DEATH && hasCost)
        {
            return TransactionType.PK_DEATH_LOSS;
        }

        if (context == TrackingContext.LOOT && hasGain)
        {
            return TransactionType.LOOT;
        }

        if (hasGain && hasCost)
        {
            return context == TrackingContext.PRODUCTION
                ? TransactionType.PROCESSING
                : TransactionType.UNCERTAIN;
        }

        if (hasGain)
        {
            return TransactionType.GAIN;
        }

        if (hasCost)
        {
            return TransactionType.CONSUMPTION;
        }

        return TransactionType.ADJUSTMENT;
    }
}
