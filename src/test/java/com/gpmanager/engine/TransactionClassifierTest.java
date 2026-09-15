package com.gpmanager.engine;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TransactionClassifierTest
{
    private final TransactionClassifier classifier = new TransactionClassifier();

    @Test
    public void transferContextAlwaysWins()
    {
        assertEquals(
            TransactionType.TRANSFER,
            classifier.classify(
                TrackingContext.TRANSFER,
                Collections.singletonList(new ItemFlow(1, "Item", 1, 100, 100))));
    }

    @Test
    public void genericMixedFlowsBecomeUncertain()
    {
        assertEquals(
            TransactionType.UNCERTAIN,
            classifier.classify(
                TrackingContext.GENERIC,
                Arrays.asList(
                    new ItemFlow(1, "Input", -1, 100, -100),
                    new ItemFlow(2, "Output", 1, 150, 150))));
    }

    @Test
    public void lootContextLabelsPositiveFlowAsLoot()
    {
        assertEquals(
            TransactionType.LOOT,
            classifier.classify(
                TrackingContext.LOOT,
                Collections.singletonList(new ItemFlow(1, "Drop", 1, 100, 100))));
    }

    @Test
    public void productionContextLabelsMixedFlowsAsProcessing()
    {
        assertEquals(
            TransactionType.PROCESSING,
            classifier.classify(
                TrackingContext.PRODUCTION,
                Arrays.asList(
                    new ItemFlow(1, "Input", -1, 100, -100),
                    new ItemFlow(2, "Output", 1, 150, 150))));
    }

    @Test
    public void playerLootContextLabelsPositiveFlowAsPkLoot()
    {
        assertEquals(
            TransactionType.PK_LOOT,
            classifier.classify(
                TrackingContext.PK_LOOT,
                Collections.singletonList(new ItemFlow(1, "Drop", 1, 100, 100))));
    }

    @Test
    public void unpricedQuantityStillHasItsRealDirection()
    {
        ItemFlow gain = new ItemFlow(1, "Unknown gain", 2, 0, 0, ItemPriceSource.UNPRICED);
        ItemFlow cost = new ItemFlow(2, "Unknown cost", -1, 0, 0, ItemPriceSource.UNPRICED);

        assertEquals(TransactionType.GAIN,
            classifier.classify(TrackingContext.GENERIC, Collections.singletonList(gain)));
        assertEquals(TransactionType.CONSUMPTION,
            classifier.classify(TrackingContext.GENERIC, Collections.singletonList(cost)));
        assertEquals(TransactionType.UNCERTAIN,
            classifier.classify(TrackingContext.GENERIC, Arrays.asList(gain, cost)));
    }

}
