package com.gpmanager.engine.evidence;

import com.gpmanager.model.ItemFlow;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChargeLoadTransferEvidenceTest
{
    @Test
    public void itemOnItemIntentCannotPartitionWithoutQuantity()
    {
        ChargeLoadTransferEvidence evidence = new ChargeLoadTransferEvidence();
        assertTrue(evidence.arm(
            MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            12934,
            "Zulrah's scales",
            5));

        ItemFlow scales = new ItemFlow(12934, "Zulrah's scales", -50L, 100, -5_000L);
        ItemFlow darts = new ItemFlow(ItemID.RUNE_DART, "Rune dart", -1L, 500, -500L);
        ChargeLoadTransferEvidence.Partition partition = evidence.partition(Arrays.asList(scales, darts));

        assertFalse(partition.hasTransfer());
        assertTrue(partition.ambiguousQuantity);
        assertTrue(partition.remaining.isEmpty());
        assertEquals(Arrays.asList(scales, darts), partition.ambiguousCandidates);
        assertTrue(evidence.isArmed());
    }

    @Test
    public void exactMeasuredQuantityPartitionsOnlyExactSingleLoss()
    {
        ChargeLoadTransferEvidence evidence = new ChargeLoadTransferEvidence();
        assertTrue(evidence.armWithExactQuantity(
            MeasuredChargeRead.Variant.TOXIC_BLOWPIPE, 12934, "Zulrah's scales", 50L, 5));

        ItemFlow scales = new ItemFlow(12934, "Zulrah's scales", -50L, 100, -5_000L);
        ItemFlow darts = new ItemFlow(ItemID.RUNE_DART, "Rune dart", -1L, 500, -500L);
        ChargeLoadTransferEvidence.Partition partition = evidence.partition(Arrays.asList(scales, darts));

        assertTrue(partition.hasTransfer());
        assertFalse(partition.ambiguousQuantity);
        assertEquals(Collections.singletonList(scales), partition.transferred);
        assertEquals(Collections.singletonList(darts), partition.remaining);
        assertFalse(evidence.isArmed());
    }

    @Test
    public void overAndUnderPredictionsHoldComponentFlowsForReview()
    {
        ItemFlow over = new ItemFlow(12934, "Zulrah's scales", -51L, 100, -5_100L);
        ItemFlow under = new ItemFlow(12934, "Zulrah's scales", -49L, 100, -4_900L);
        assertAmbiguousQuantity(over, 50L);
        assertAmbiguousQuantity(under, 50L);
    }

    @Test
    public void summedSameIdCandidatesFailClosedEvenWhenTotalMatchesPrediction()
    {
        ChargeLoadTransferEvidence evidence = new ChargeLoadTransferEvidence();
        assertTrue(evidence.armWithExactQuantity(
            MeasuredChargeRead.Variant.TOXIC_BLOWPIPE, 12934, "Zulrah's scales", 50L, 5));
        ItemFlow first = new ItemFlow(12934, "Zulrah's scales", -20L, 100, -2_000L);
        ItemFlow second = new ItemFlow(12934, "Zulrah's scales", -30L, 100, -3_000L);

        ChargeLoadTransferEvidence.Partition partition = evidence.partition(Arrays.asList(first, second));

        assertTrue(partition.ambiguousQuantity);
        assertFalse(partition.hasTransfer());
        assertTrue(partition.remaining.isEmpty());
        assertEquals(Arrays.asList(first, second), partition.ambiguousCandidates);
    }

    @Test
    public void secondSameIdLossInWindowAlsoFailsClosed()
    {
        ChargeLoadTransferEvidence evidence = new ChargeLoadTransferEvidence();
        assertTrue(evidence.arm(
            MeasuredChargeRead.Variant.TOXIC_BLOWPIPE, 12934, "Zulrah's scales", 5));
        ItemFlow first = new ItemFlow(12934, "Zulrah's scales", -3L, 100, -300L);
        ItemFlow second = new ItemFlow(12934, "Zulrah's scales", -2L, 100, -200L);

        ChargeLoadTransferEvidence.Partition firstPartition = evidence.partition(Collections.singletonList(first));
        ChargeLoadTransferEvidence.Partition secondPartition = evidence.partition(Collections.singletonList(second));

        assertTrue(firstPartition.ambiguousQuantity);
        assertFalse(firstPartition.hasTransfer());
        assertEquals(Collections.singletonList(first), firstPartition.ambiguousCandidates);
        assertTrue(secondPartition.ambiguousQuantity);
        assertFalse(secondPartition.hasTransfer());
        assertEquals(Collections.singletonList(second), secondPartition.ambiguousCandidates);
        assertTrue(evidence.isArmed());
    }

    @Test
    public void anotherWeaponComponentAndMismatchedIdentityCannotArmOrTransfer()
    {
        ChargeLoadTransferEvidence evidence = new ChargeLoadTransferEvidence();
        assertFalse(evidence.arm(
            MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            ItemID.RUNE_DART,
            "Adamant dart",
            5));
        assertTrue(evidence.arm(
            MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            ItemID.RUNE_DART,
            "Rune dart",
            5));

        ItemFlow scales = new ItemFlow(12934, "Zulrah's scales", -2L, 100, -200L);
        ChargeLoadTransferEvidence.Partition partition = evidence.partition(Collections.singletonList(scales));
        assertFalse(partition.hasTransfer());
        assertEquals(Collections.singletonList(scales), partition.remaining);
        assertTrue(evidence.isArmed());
    }

    @Test
    public void intentExpiresOnTickWithoutInventorySettlement()
    {
        ChargeLoadTransferEvidence evidence = new ChargeLoadTransferEvidence();
        assertTrue(evidence.arm(
            MeasuredChargeRead.Variant.TRIDENT_SEAS,
            560,
            "Death rune",
            2));
        evidence.tick();
        assertTrue(evidence.isArmed());
        evidence.tick();
        assertFalse(evidence.isArmed());
    }

    private static void assertAmbiguousQuantity(ItemFlow flow, long expectedQuantity)
    {
        ChargeLoadTransferEvidence evidence = new ChargeLoadTransferEvidence();
        assertTrue(evidence.armWithExactQuantity(
            MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            12934,
            "Zulrah's scales",
            expectedQuantity,
            5));
        ChargeLoadTransferEvidence.Partition partition = evidence.partition(Collections.singletonList(flow));
        assertTrue(partition.ambiguousQuantity);
        assertFalse(partition.hasTransfer());
        assertTrue(partition.remaining.isEmpty());
        assertEquals(Collections.singletonList(flow), partition.ambiguousCandidates);
        assertTrue(evidence.isArmed());
    }
}
