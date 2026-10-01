package com.gpmanager;

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
                Context.TRANSFER,
                Collections.singletonList(new Flow(1, "Item", 1, 100, 100))));
    }

    @Test
    public void genericMixedFlowsBecomeUncertain()
    {
        assertEquals(
            TransactionType.UNCERTAIN,
            classifier.classify(
                Context.GENERIC,
                Arrays.asList(
                    new Flow(1, "Input", -1, 100, -100),
                    new Flow(2, "Output", 1, 150, 150))));
    }

    @Test
    public void lootContextLabelsPositiveFlowAsLoot()
    {
        assertEquals(
            TransactionType.LOOT,
            classifier.classify(
                Context.LOOT,
                Collections.singletonList(new Flow(1, "Drop", 1, 100, 100))));
    }

    @Test
    public void productionContextLabelsMixedFlowsAsProcessing()
    {
        assertEquals(
            TransactionType.PROCESSING,
            classifier.classify(
                Context.PRODUCTION,
                Arrays.asList(
                    new Flow(1, "Input", -1, 100, -100),
                    new Flow(2, "Output", 1, 150, 150))));
    }

    @Test
    public void playerLootContextLabelsPositiveFlowAsPkLoot()
    {
        assertEquals(
            TransactionType.PK_LOOT,
            classifier.classify(
                Context.PK_LOOT,
                Collections.singletonList(new Flow(1, "Drop", 1, 100, 100))));
    }

    @Test
    public void unpricedQuantityStillHasItsRealDirection()
    {
        Flow gain = new Flow(1, "Unknown gain", 2, 0, 0, PriceSource.UNPRICED);
        Flow cost = new Flow(2, "Unknown cost", -1, 0, 0, PriceSource.UNPRICED);

        assertEquals(TransactionType.GAIN,
            classifier.classify(Context.GENERIC, Collections.singletonList(gain)));
        assertEquals(TransactionType.CONSUMPTION,
            classifier.classify(Context.GENERIC, Collections.singletonList(cost)));
        assertEquals(TransactionType.UNCERTAIN,
            classifier.classify(Context.GENERIC, Arrays.asList(gain, cost)));
    }

}
