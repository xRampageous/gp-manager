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
            Ai.TRANSFER,
            classifier.classify(
                Aj.TRANSFER,
                Collections.singletonList(new Ab(1, "Item", 1, 100, 100))));
    }

    @Test
    public void genericMixedFlowsBecomeUncertain()
    {
        assertEquals(
            Ai.UNCERTAIN,
            classifier.classify(
                Aj.GENERIC,
                Arrays.asList(
                    new Ab(1, "Input", -1, 100, -100),
                    new Ab(2, "Output", 1, 150, 150))));
    }

    @Test
    public void lootContextLabelsPositiveFlowAsLoot()
    {
        assertEquals(
            Ai.LOOT,
            classifier.classify(
                Aj.LOOT,
                Collections.singletonList(new Ab(1, "Drop", 1, 100, 100))));
    }

    @Test
    public void productionContextLabelsMixedFlowsAsProcessing()
    {
        assertEquals(
            Ai.PROCESSING,
            classifier.classify(
                Aj.PRODUCTION,
                Arrays.asList(
                    new Ab(1, "Input", -1, 100, -100),
                    new Ab(2, "Output", 1, 150, 150))));
    }

    @Test
    public void playerLootContextLabelsPositiveFlowAsPkLoot()
    {
        assertEquals(
            Ai.PK_LOOT,
            classifier.classify(
                Aj.PK_LOOT,
                Collections.singletonList(new Ab(1, "Drop", 1, 100, 100))));
    }

    @Test
    public void unpricedQuantityStillHasItsRealDirection()
    {
        Ab gain = new Ab(1, "Unknown gain", 2, 0, 0, Av.UNPRICED);
        Ab cost = new Ab(2, "Unknown cost", -1, 0, 0, Av.UNPRICED);

        assertEquals(Ai.GAIN,
            classifier.classify(Aj.GENERIC, Collections.singletonList(gain)));
        assertEquals(Ai.CONSUMPTION,
            classifier.classify(Aj.GENERIC, Collections.singletonList(cost)));
        assertEquals(Ai.UNCERTAIN,
            classifier.classify(Aj.GENERIC, Arrays.asList(gain, cost)));
    }

}
