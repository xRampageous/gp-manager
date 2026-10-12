package com.gpmanager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ChargeLoadTransferEvidenceTest
{
    @Test
    public void itemOnItemIntentCannotPartitionWithoutQuantity()
    {
        Engine evidence = ReviewFixesTest.engine();
        assertTrue(evidence.markChargeLoad(
            ChargeCheck.ChargedItem.BLOWPIPE,
            12934,
            "Zulrah's scales",
            null,
            5));

        Flow scales = new Flow(12934, "Zulrah's scales", -50L, 100, -5_000L);
        Flow darts = new Flow(ItemID.RUNE_DART, "Rune dart", -1L, 500, -500L);
        LoadTarget partition = evidence.loadTarget(Arrays.asList(scales, darts));

        assertTrue(partition.remaining.isEmpty());
        assertEquals(Arrays.asList(scales, darts), partition.ambiguousCandidates);
    }

    @Test
    public void allSupportedSameIdCandidatesStayInReview()
    {
        Engine evidence = ReviewFixesTest.engine();
        assertTrue(evidence.markChargeLoad(
            ChargeCheck.ChargedItem.BLOWPIPE, 12934, "Zulrah's scales", null, 5));
        Flow first = new Flow(12934, "Zulrah's scales", -20L, 100, -2_000L);
        Flow second = new Flow(12934, "Zulrah's scales", -30L, 100, -3_000L);

        LoadTarget partition = evidence.loadTarget(Arrays.asList(first, second));

        assertTrue(partition.remaining.isEmpty());
        assertEquals(Arrays.asList(first, second), partition.ambiguousCandidates);
    }

    @Test
    public void secondSameIdLossInWindowAlsoFailsClosed()
    {
        Engine evidence = ReviewFixesTest.engine();
        assertTrue(evidence.markChargeLoad(
            ChargeCheck.ChargedItem.BLOWPIPE, 12934, "Zulrah's scales", null, 5));
        Flow first = new Flow(12934, "Zulrah's scales", -3L, 100, -300L);
        Flow second = new Flow(12934, "Zulrah's scales", -2L, 100, -200L);

        LoadTarget firstPartition = evidence.loadTarget(Collections.singletonList(first));
        LoadTarget secondPartition = evidence.loadTarget(Collections.singletonList(second));

        assertEquals(Collections.singletonList(first), firstPartition.ambiguousCandidates);
        assertEquals(Collections.singletonList(second), secondPartition.ambiguousCandidates);
    }

    @Test
    public void anotherWeaponComponentAndMismatchedIdentityCannotArm()
    {
        Engine evidence = ReviewFixesTest.engine();
        assertFalse(evidence.markChargeLoad(
            ChargeCheck.ChargedItem.BLOWPIPE,
            ItemID.RUNE_DART,
            "Adamant dart",
            null,
            5));
        assertTrue(evidence.markChargeLoad(
            ChargeCheck.ChargedItem.BLOWPIPE,
            ItemID.RUNE_DART,
            "Rune dart",
            null,
            5));

        Flow scales = new Flow(12934, "Zulrah's scales", -2L, 100, -200L);
        LoadTarget partition = evidence.loadTarget(Collections.singletonList(scales));
        assertEquals(Collections.singletonList(scales), partition.remaining);
    }

    @Test
    public void intentExpiresOnTickWithoutInventorySettlement()
    {
        Engine evidence = ReviewFixesTest.engine();
        assertTrue(evidence.markChargeLoad(
            ChargeCheck.ChargedItem.TRIDENT_SEAS,
            560,
            "Death rune",
            null,
            2));
        evidence.inbox.tick(evidence.inbox.now + 1);
        evidence.inbox.tick(evidence.inbox.now + 1);
        assertNull("the stale load intent is gone", evidence.inbox.peek(Inbox.LOAD_TARGET));
    }

}
