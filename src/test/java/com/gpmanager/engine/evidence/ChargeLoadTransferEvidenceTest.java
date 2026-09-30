package com.gpmanager;

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
            Ar.V.V1b,
            12934,
            "Zulrah's scales",
            null,
            5));

        Ab scales = new Ab(12934, "Zulrah's scales", -50L, 100, -5_000L);
        Ab darts = new Ab(ItemID.RUNE_DART, "Rune dart", -1L, 500, -500L);
        ChargeLoadTransferEvidence.Partition partition = evidence.partition(Arrays.asList(scales, darts));

        assertTrue(partition.remaining.isEmpty());
        assertEquals(Arrays.asList(scales, darts), partition.ambiguousCandidates);
    }

    @Test
    public void allSupportedSameIdCandidatesStayInReview()
    {
        ChargeLoadTransferEvidence evidence = new ChargeLoadTransferEvidence();
        assertTrue(evidence.arm(
            Ar.V.V1b, 12934, "Zulrah's scales", null, 5));
        Ab first = new Ab(12934, "Zulrah's scales", -20L, 100, -2_000L);
        Ab second = new Ab(12934, "Zulrah's scales", -30L, 100, -3_000L);

        ChargeLoadTransferEvidence.Partition partition = evidence.partition(Arrays.asList(first, second));

        assertTrue(partition.remaining.isEmpty());
        assertEquals(Arrays.asList(first, second), partition.ambiguousCandidates);
    }

    @Test
    public void secondSameIdLossInWindowAlsoFailsClosed()
    {
        ChargeLoadTransferEvidence evidence = new ChargeLoadTransferEvidence();
        assertTrue(evidence.arm(
            Ar.V.V1b, 12934, "Zulrah's scales", null, 5));
        Ab first = new Ab(12934, "Zulrah's scales", -3L, 100, -300L);
        Ab second = new Ab(12934, "Zulrah's scales", -2L, 100, -200L);

        ChargeLoadTransferEvidence.Partition firstPartition = evidence.partition(Collections.singletonList(first));
        ChargeLoadTransferEvidence.Partition secondPartition = evidence.partition(Collections.singletonList(second));

        assertEquals(Collections.singletonList(first), firstPartition.ambiguousCandidates);
        assertEquals(Collections.singletonList(second), secondPartition.ambiguousCandidates);
    }

    @Test
    public void anotherWeaponComponentAndMismatchedIdentityCannotArm()
    {
        ChargeLoadTransferEvidence evidence = new ChargeLoadTransferEvidence();
        assertFalse(evidence.arm(
            Ar.V.V1b,
            ItemID.RUNE_DART,
            "Adamant dart",
            null,
            5));
        assertTrue(evidence.arm(
            Ar.V.V1b,
            ItemID.RUNE_DART,
            "Rune dart",
            null,
            5));

        Ab scales = new Ab(12934, "Zulrah's scales", -2L, 100, -200L);
        ChargeLoadTransferEvidence.Partition partition = evidence.partition(Collections.singletonList(scales));
        assertEquals(Collections.singletonList(scales), partition.remaining);
    }

    @Test
    public void intentExpiresOnTickWithoutInventorySettlement()
    {
        ChargeLoadTransferEvidence evidence = new ChargeLoadTransferEvidence();
        assertTrue(evidence.arm(
            Ar.V.TRIDENT_SEAS,
            560,
            "Death rune",
            null,
            2));
        evidence.tick();
        evidence.tick();
    }

}
