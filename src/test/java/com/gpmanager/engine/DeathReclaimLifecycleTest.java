package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.SkullIcon;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PvM death lifecycle: the gear wipe is an ownership-neutral transfer, the reclaim returns
 * items as a transfer and books only the coins actually observed as the fee, labelled with
 * the service's published amount. Fees paid from the bank and expired gravestones are never
 * invented.
 */
public class DeathReclaimLifecycleTest
{
    private static final int WHIP = 4151;
    private static final int TORTURE = 19553;
    private static final int COINS = 995;
    private static final int SHARK = 385;

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 2;
        }

        @Override
        public boolean keepTransferAuditRows()
        {
            return true;
        }
    };

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    private final FlowValuator valuator = deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int id = entry.getKey();
            int price = id == COINS ? 1 : id == SHARK ? 800 : id == WHIP ? 2_000_000 : 15_000_000;
            String name = id == COINS ? "Coins" : id == SHARK ? "Shark" : id == WHIP ? "Abyssal whip" : "Amulet of torture";
            flows.add(new Flow(id, name, entry.getValue(), price, entry.getValue() * price));
        }
        return flows;
    };

    @Test
    public void catalogueMatchesServicesByNameAndRetrievalVerb()
    {
        BossRetrievalCatalogue.Service zulrah = BossRetrievalCatalogue.forMenu("Talk-to", "Priestess Zul-Gwenwynig");
        assertNotNull(zulrah);
        assertEquals(100_000L, zulrah.expectedFee);
        assertFalse(zulrah.ambiguousTarget);
        assertEquals(60_000L, BossRetrievalCatalogue.forMenu("Claim", "Shura").expectedFee);
        assertEquals(50_000L, BossRetrievalCatalogue.forMenu("Search", "Magical chest").expectedFee);
        assertEquals(25_000L, BossRetrievalCatalogue.forMenu("Talk-to", "Arno").expectedFee);
        BossRetrievalCatalogue.Service chest = BossRetrievalCatalogue.forMenu("Open", "Chest");
        assertNotNull(chest);
        assertTrue(chest.ambiguousTarget);
        assertEquals(BossRetrievalCatalogue.VARIABLE_FEE, chest.expectedFee);
        assertTrue(BossRetrievalCatalogue.forMenu("Loot", "Gravestone").ambiguousTarget);
        // Non-retrieval verbs and unrelated NPCs never match.
        assertNull(BossRetrievalCatalogue.forMenu("Attack", "Priestess Zul-Gwenwynig"));
        assertNull(BossRetrievalCatalogue.forMenu("Talk-to", "Banker"));
        assertNull(BossRetrievalCatalogue.forMenu("Use", "Chest"));
    }

    @Test
    public void whyLineCarriesPublishedFeeAndFlagsAMismatch()
    {
        BossRetrievalCatalogue.Service zulrah = BossRetrievalCatalogue.forMenu("Talk-to", "Priestess Zul-Gwenwynig");
        assertEquals(
            "Item retrieval fee — Zulrah (Priestess Zul-Gwenwynig), published 100,000. Free below 50 kills and for Ultimate Ironmen",
            zulrah.why(100_000L));
        assertTrue(zulrah.why(90_000L).contains("observed 90,000"));
        assertTrue(BossRetrievalCatalogue.forMenu("Open", "Chest").why(250_000L).contains("fee varies"));
    }

    @Test
    public void lifecycleAcceptsAmbiguousTargetsOnlyWhileAwaitingReclaim()
    {
        DeathReclaimLifecycle lifecycle = new DeathReclaimLifecycle();
        BossRetrievalCatalogue.Service chest = BossRetrievalCatalogue.forMenu("Open", "Chest");
        BossRetrievalCatalogue.Service torfinn = BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn");
        assertFalse("random chest without a death is not a reclaim", lifecycle.noteReclaimIntent(chest, 10));
        assertFalse("a generic Talk-to at a service NPC is not enough", lifecycle.noteReclaimIntent(torfinn, 10));
        lifecycle.onLocalPvmDeath(java.util.Collections.singletonMap(WHIP, 1L));
        lifecycle.onDeathItemsRemoved(java.util.Collections.singletonList(
            new Flow(WHIP, "Abyssal whip", -1L, 15_000_000, -15_000_000L)));
        assertTrue(lifecycle.isAwaitingReclaim());
        assertTrue("a named service is accepted after a local PvM death", lifecycle.noteReclaimIntent(torfinn, 10));
        assertTrue(lifecycle.noteReclaimIntent(chest, 10));
        BossRetrievalCatalogue.Service grave = BossRetrievalCatalogue.forMenu("Check", "Grave");
        assertNotNull(grave);
        assertTrue("a Grave Check re-arms an awaiting reclaim", lifecycle.noteReclaimIntent(grave, 1));
        assertTrue(lifecycle.isReclaimArmed());
        for (int i = 0; i < DeathReclaimLifecycle.MIN_RECLAIM_ARM_TICKS - 1; i++)
        {
            lifecycle.tick(false);
        }
        assertTrue("reclaim stays armed through its two-minute minimum", lifecycle.isReclaimArmed());
        lifecycle.tick(false);
        assertFalse("window expires", lifecycle.isReclaimArmed());
        assertTrue("death still awaits", lifecycle.isAwaitingReclaim());
        lifecycle.reset();
        assertFalse(lifecycle.isAwaitingReclaim());
    }

    @Test
    public void missingDeathSnapshotCannotAuthorizeNeutralizingLosses()
    {
        DeathReclaimLifecycle lifecycle = new DeathReclaimLifecycle();
        lifecycle.onLocalPvmDeath((Map<Integer, Long>) null);

        assertFalse("no canonical snapshot means no reclaim whitelist",
            lifecycle.isDeathWipePending());
        assertFalse(lifecycle.isAwaitingReclaim());
        assertTrue(lifecycle.onDeathItemsRemoved(java.util.Collections.singletonList(
            new Flow(WHIP, "Abyssal whip", -1L, 2_000_000, -2_000_000L))).isEmpty());
    }

    @Test
    public void missingEngineDeathSnapshotLeavesMeasuredLossCounted()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markLocalPvmDeath(20, null, null);
        engine.markInventoryDirty();

        Transaction loss = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(loss);
        assertEquals(TransactionType.CONSUMPTION, loss.getType());
        assertTrue("without the canonical death snapshot the loss remains counted", loss.isCounted());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void delayedDeathWipeUsesCapturedStacksAfterDeathContextExpiresAndDirectLootIsNeutral()
    {
        ContainerSnapshot carried = gear(1L, 1L, 0L, 0L);
        Engine engine = started(carried);
        engine.markLocalPvmDeath(1, null, carried.quantities);

        // Let the short-lived transfer context expire before the post-respawn inventory
        // removal settles. The separate captured-stack evidence remains live.
        for (int i = 0; i < 5; i++)
        {
            assertNull(engine.processIfDirty(carried, 1_100L + i * 600L));
        }
        engine.markInventoryDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 5_000L);

        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.getType());
        assertFalse(wipe.isCounted());
        assertEquals("Death: items held by gravestone / retrieval service", wipe.getNote());
        assertEquals(2L, engine.getDeathReclaimStatus().outstandingItemCount);
        assertTrue(engine.getDeathReclaimStatus().awaiting);
        assertTrue(engine.getDeathReclaimStatus().ageTicks >= 5L);

        // A direct gravestone Loot needs no still-live reclaim menu window.
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markInventoryDirty();
        Transaction returned = settle(engine, carried, 8_000L);
        assertNotNull(returned);
        assertEquals(TransactionType.TRANSFER, returned.getType());
        assertFalse(returned.isCounted());
        assertEquals("Death reclaim: items recovered", returned.getNote());
        assertFalse(engine.getDeathReclaimStatus().awaiting);
        assertEquals(0L, engine.getMetrics(12_000L).net);
    }

    @Test
    public void deathWipeOnlyTransfersItemsActuallyHeldAtDeath()
    {
        ContainerSnapshot baseline = gear(1L, 1L, 0L, 0L);
        Engine engine = started(baseline);
        engine.markLocalPvmDeath(1, null, gear(1L, 0L, 0L, 0L).quantities);
        engine.markInventoryDirty();
        Transaction residualLoss = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(residualLoss);
        assertEquals("unobserved ownership follows ordinary accounting", TransactionType.CONSUMPTION,
            residualLoss.getType());
        assertTrue(residualLoss.isCounted());
        assertEquals(-15_000_000L, residualLoss.getNet());
        assertEquals(1L, engine.getDeathReclaimStatus().outstandingItemCount);
        Transaction deathTransfer = findTransaction(engine,
            "Death: items held by gravestone / retrieval service");
        assertNotNull(deathTransfer);
        assertFalse(deathTransfer.isCounted());
        assertEquals(WHIP, deathTransfer.getFlows().get(0).itemId);
    }

    @Test
    public void splitInventoryAndEquipmentLossesBothUseTheRemainingDeathWhitelist()
    {
        ContainerSnapshot carried = gear(1L, 1L, 0L, 0L);
        Engine engine = started(carried);
        engine.markLocalPvmDeath(1, null, carried.quantities);

        engine.markInventoryDirty();
        Transaction firstWipe = settle(engine, gear(0L, 1L, 0L, 0L), 1_600L);
        assertNotNull(firstWipe);
        assertEquals(TransactionType.TRANSFER, firstWipe.getType());
        assertTrue(engine.getDeathReclaimStatus().awaiting);
        assertEquals("only the still-held item remains outstanding", 1L,
            engine.getDeathReclaimStatus().outstandingItemCount);

        engine.setBaseline(gear(0L, 1L, 0L, 0L));
        engine.markInventoryDirty();
        Transaction secondWipe = settle(engine, ContainerSnapshot.empty(), 3_000L);
        assertNotNull(secondWipe);
        assertEquals(TransactionType.TRANSFER, secondWipe.getType());
        assertFalse(secondWipe.isCounted());
        assertEquals(2L, engine.getDeathReclaimStatus().outstandingItemCount);
    }

    @Test
    public void delayedCoinOnlyDeathWipeIsNeutralEvenAfterDeathContextExpires()
    {
        ContainerSnapshot carried = gear(0L, 0L, 75_000L, 0L);
        Engine engine = started(carried);
        engine.markLocalPvmDeath(1, null, carried.quantities);
        for (int i = 0; i < 5; i++)
        {
            assertNull(engine.processIfDirty(carried, 1_100L + i * 600L));
        }

        engine.markInventoryDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 5_000L);

        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.getType());
        assertFalse(wipe.isCounted());
        assertEquals("Death: items held by gravestone / retrieval service", wipe.getNote());
        assertEquals(0L, engine.getMetrics(8_000L).net);
    }

    @Test
    public void armedReclaimDoesNotMislabelCoinLossAlongsideDelayedDeathWipeAsFee()
    {
        ContainerSnapshot carried = gear(1L, 0L, 200_000L, 0L);
        Engine engine = started(carried);
        engine.markLocalPvmDeath(20, null, carried.quantities);
        assertTrue(engine.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));
        engine.markInventoryDirty();

        Transaction ambiguousWipe = settle(engine, gear(0L, 0L, 180_000L, 0L), 1_600L);

        assertNotNull(ambiguousWipe);
        assertTrue("ambiguous coin outflow stays an ordinary measured cost", ambiguousWipe.isCounted());
        assertEquals("the ordinary cost contains the observed coin loss", -20_000L,
            ambiguousWipe.getNet());
        assertFalse("a menu interaction must not turn an overlapping death wipe into a fee",
            "Death reclaim".equals(ambiguousWipe.getActivityName()));
        assertEquals(1, findTransaction(engine,
            "Death: items held by gravestone / retrieval service").getFlows().size());

        engine.setBaseline(gear(0L, 0L, 180_000L, 0L));
        engine.markInventoryDirty();
        Transaction observedFee = settle(engine, gear(1L, 0L, 80_000L, 0L), 4_000L);

        assertNotNull(observedFee);
        assertEquals("a separate observed outflow with a matching returned item remains a fee",
            "Death reclaim", observedFee.getActivityName());
        assertEquals(-100_000L, observedFee.getNet());
    }

    @Test
    public void pendingDeathSnapshotCannotHideLaterUnclaimedGeMovement()
    {
        ContainerSnapshot carried = gear(1L, 0L, 0L, 0L);
        Engine engine = started(carried);
        engine.markLocalPvmDeath(1, null, carried.quantities);
        for (int i = 0; i < 3; i++)
        {
            assertNull(engine.processIfDirty(carried, 1_100L + i * 600L));
        }
        engine.markContext(Context.MARKET, 100, "Grand Exchange sale");
        engine.markInventoryDirty();

        Transaction movement = settle(engine, gear(0L, 0L, 15_000_000L, 0L), 1_600L);

        assertNotNull(movement);
        assertEquals(Context.MARKET, movement.getContext());
        assertEquals(TransactionType.UNCERTAIN, movement.getType());
        assertFalse("without custody, the GE movement waits for owner review", movement.isCounted());
        assertEquals(0L, engine.getMetrics(1_600L).net);
        assertFalse("the stronger market evidence closes the stale wipe whitelist",
            engine.getDeathReclaimStatus().awaiting);
    }

    @Test
    public void confirmedConsumptionOfSameItemOutranksPendingDeathSnapshot()
    {
        ContainerSnapshot carried = gear(1L, 0L, 0L, 0L);
        Engine engine = started(carried);
        engine.markLocalPvmDeath(1, null, carried.quantities);
        for (int i = 0; i < 3; i++)
        {
            assertNull(engine.processIfDirty(carried, 1_100L + i * 600L));
        }
        engine.noteConsumptionIntent(WHIP, 20);
        engine.markInventoryDirty();

        Transaction consumed = settle(engine, ContainerSnapshot.empty(), 3_000L);

        assertNotNull(consumed);
        assertEquals(TransactionType.CONSUMPTION, consumed.getType());
        assertTrue(consumed.isCounted());
        assertFalse(engine.getDeathReclaimStatus().awaiting);
    }

    @Test
    public void mixedDeathWipeAndNamedConsumptionPartitionByItemId()
    {
        ContainerSnapshot carried = gear(1L, 0L, 0L, 1L);
        Engine engine = started(carried);
        engine.markLocalPvmDeath(1, null, carried.quantities);
        engine.noteConsumptionIntent(SHARK, 30);
        engine.markInventoryDirty();

        Transaction consumption = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(consumption);
        assertEquals(TransactionType.CONSUMPTION, consumption.getType());
        assertTrue(consumption.isCounted());
        Transaction deathTransfer = findTransaction(engine,
            "Death: items held by gravestone / retrieval service");
        assertNotNull("unrelated whip loss remains an ownership-neutral death transfer", deathTransfer);
        assertFalse(deathTransfer.isCounted());
        assertEquals(WHIP, deathTransfer.getFlows().get(0).itemId);
        assertEquals(1L, engine.getDeathReclaimStatus().outstandingItemCount);
    }

    @Test
    public void finalGravestoneReturnSettlingOnExpiryTickWinsBeforeExpiryAudit()
    {
        ContainerSnapshot carried = gear(1L, 0L, 0L, 0L);
        Engine engine = started(carried);
        engine.markLocalPvmDeath(1, null, carried.quantities);
        engine.markInventoryDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));
        engine.setBaseline(ContainerSnapshot.empty());

        while (engine.getDeathReclaimStatus().ageTicks
            < DeathReclaimLifecycle.AWAIT_TICKS - 1L)
        {
            assertNull(engine.processIfDirty(ContainerSnapshot.empty(), 4_000L));
        }
        // The final return can be observed before a delayed/missed dirty callback.
        Transaction returned = settle(engine, carried, 5_000L);

        assertNotNull(returned);
        assertEquals(TransactionType.TRANSFER, returned.getType());
        assertEquals("Death reclaim: items recovered", returned.getNote());
        assertFalse(engine.getDeathReclaimStatus().awaiting);
        assertNull(findTransaction(engine, "Death reclaim expired"));
    }

    @Test
    public void deathWipeWhitelistExpiresAtItsOneMinuteEvidenceCap()
    {
        DeathReclaimLifecycle lifecycle = new DeathReclaimLifecycle();
        lifecycle.onLocalPvmDeath(java.util.Collections.singletonMap(WHIP, 1L));
        for (int i = 0; i < DeathReclaimLifecycle.DEATH_WIPE_WINDOW_TICKS - 1; i++)
        {
            lifecycle.tick(false);
        }
        assertTrue(lifecycle.isDeathWipePending());
        lifecycle.tick(false);
        assertFalse(lifecycle.isDeathWipePending());
        assertFalse(lifecycle.isAwaitingReclaim());
    }

    @Test
    public void gravestoneTimerAdvancesAndExpiresWhileInventoryIsStillChanging()
    {
        final int[] stabilization = {2};
        GpManagerConfig changingConfig = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return stabilization[0];
            }

            @Override
            public boolean keepTransferAuditRows()
            {
                return true;
            }
        };
        ContainerSnapshot carried = gear(1L, 0L, 0L, 1L);
        Engine engine = started(carried, changingConfig);
        engine.markLocalPvmDeath(1, null, carried.quantities);
        engine.markInventoryDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));
        assertEquals(2L, engine.getDeathReclaimStatus().outstandingItemCount);

        stabilization[0] = DeathReclaimLifecycle.AWAIT_TICKS + 100;
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markInventoryDirty();
        ContainerSnapshot changing = new ContainerSnapshot(
            java.util.Collections.singletonMap(99_999, 1L));
        Transaction expired = null;
        Transaction unrelatedGain = null;
        int maxWait = DeathReclaimLifecycle.AWAIT_TICKS + stabilization[0] + 5;
        for (int i = 0; i < maxWait; i++)
        {
            Transaction tick = engine.processIfDirty(changing, 4_000L + i * 600L);
            if (tick != null && "Death reclaim expired".equals(tick.getNote()))
            {
                expired = tick;
                break;
            }
            if (tick != null && tick.getNet() > 0L)
            {
                unrelatedGain = tick;
            }
        }

        assertNotNull("timer ages while dirty, then expires after the outstanding snapshot settles", expired);
        assertNotNull("an unrelated settled change still books normally", unrelatedGain);
        assertTrue(unrelatedGain.isCounted());
        assertTrue(expired.getFlows().isEmpty());
        assertFalse(engine.getDeathReclaimStatus().awaiting);
        engine.processIfDirty(changing, 4_000L + maxWait * 600L);
    }

    @Test
    public void observedCoinFeeAndReturnedItemsSplitIntoSeparateRows()
    {
        ContainerSnapshot carried = gear(1L, 0L, 150_000L, 0L);
        Engine engine = started(carried);
        engine.markLocalPvmDeath(1, null, carried.quantities);
        engine.markInventoryDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));
        engine.setBaseline(gear(0L, 0L, 150_000L, 0L));

        assertTrue(engine.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Loot", "Gravestone"), 1));
        assertTrue(engine.getDeathReclaimStatus().armed);
        engine.markInventoryDirty();
        Transaction fee = settle(engine, gear(1L, 0L, 50_000L, 0L), 4_000L);

        assertNotNull(fee);
        assertEquals(TransactionType.CONSUMPTION, fee.getType());
        assertEquals(-100_000L, fee.getNet());
        assertTrue(fee.getExplanation().contains("Gravestone"));
        assertFalse(engine.getDeathReclaimStatus().awaiting);
        Transaction recovered = findTransaction(engine, "Death reclaim: items recovered");
        assertNotNull(recovered);
        assertFalse(recovered.isCounted());
        assertEquals(WHIP, recovered.getFlows().get(0).itemId);
        assertEquals(-100_000L, engine.getMetrics(8_000L).net);
    }

    @Test
    public void lowValueReturnedItemClosesBeforeTransactionMinimumFilter()
    {
        GpManagerConfig minimum = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 2;
            }

            @Override
            public boolean keepTransferAuditRows()
            {
                return true;
            }
        };
        ContainerSnapshot carried = gear(0L, 0L, 0L, 1L);
        Engine engine = started(carried, minimum);
        engine.markLocalPvmDeath(1, null, carried.quantities);
        engine.markInventoryDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.getType());
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markInventoryDirty();
        Transaction returned = settle(engine, carried, 4_000L);

        assertNotNull("the measured return is partitioned before the minimum-value gate", returned);
        assertEquals(TransactionType.TRANSFER, returned.getType());
        assertFalse(returned.isCounted());
        assertEquals("Death reclaim: items recovered", returned.getNote());
        assertFalse(engine.getDeathReclaimStatus().awaiting);
        assertEquals(0L, engine.getMetrics(8_000L).net);
    }

    @Test
    public void partialLootReportsOutstandingItemsThenExpiresWithoutGuessingLoss()
    {
        ContainerSnapshot carried = gear(1L, 1L, 0L, 0L);
        Engine engine = started(carried);
        engine.markLocalPvmDeath(1, null, carried.quantities);
        engine.markInventoryDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markInventoryDirty();
        Transaction firstReturn = settle(engine, gear(1L, 0L, 0L, 0L), 4_000L);

        assertNotNull(firstReturn);
        assertEquals(TransactionType.TRANSFER, firstReturn.getType());
        assertTrue(engine.getDeathReclaimStatus().awaiting);
        assertEquals(1L, engine.getDeathReclaimStatus().outstandingItemCount);

        Transaction expired = null;
        ContainerSnapshot afterPartialReturn = gear(1L, 0L, 0L, 0L);
        for (int i = 0; i < DeathReclaimLifecycle.AWAIT_TICKS; i++)
        {
            Transaction tick = engine.processIfDirty(afterPartialReturn, 6_000L + i * 600L);
            if (tick != null)
            {
                expired = tick;
            }
        }

        assertNotNull("timer expiry is retained as an audit event", expired);
        assertEquals("Death reclaim expired", expired.getNote());
        assertEquals(TransactionType.TRANSFER, expired.getType());
        assertFalse(expired.isCounted());
        assertTrue(expired.getFlows().isEmpty());
        assertTrue(expired.getExplanation().contains("Amulet of torture"));
        assertTrue(expired.getExplanation().contains("No item loss or fee was inferred"));
        assertFalse(engine.getDeathReclaimStatus().awaiting);
        assertEquals(0L, engine.getMetrics(1_000_000L).net);
    }

    @Test
    public void secondDeathMergesOutstandingItemsIntoNewGravestone()
    {
        ContainerSnapshot firstHeld = gear(1L, 0L, 0L, 0L);
        Engine engine = started(firstHeld);
        engine.markLocalPvmDeath(1, null, firstHeld.quantities);
        engine.markInventoryDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));

        ContainerSnapshot secondHeld = gear(0L, 1L, 0L, 0L);
        engine.setBaseline(secondHeld);
        engine.markLocalPvmDeath(1, null, secondHeld.quantities);
        engine.markInventoryDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 4_000L));
        assertTrue(engine.getDeathReclaimStatus().awaiting);
        assertEquals(2L, engine.getDeathReclaimStatus().outstandingItemCount);

        engine.setBaseline(ContainerSnapshot.empty());
        engine.markInventoryDirty();
        Transaction recovered = settle(engine, gear(1L, 1L, 0L, 0L), 6_000L);
        assertNotNull(recovered);
        assertEquals(TransactionType.TRANSFER, recovered.getType());
        assertEquals("Death reclaim: items recovered", recovered.getNote());
        assertFalse(engine.getDeathReclaimStatus().awaiting);
        assertEquals(0L, engine.getMetrics(10_000L).net);
    }

    @Test
    public void pvmDeathWipeIsATransferAndZulrahReclaimReturnsItemsAndBooksTheFee()
    {
        Engine engine = started(gear(1L, 1L, 150_000L, 0L));
        long before = engine.getMetrics(1_000L).net;

        // Death: everything leaves the inventory in one settle.
        engine.markLocalPvmDeath(20,
            LocalDeathEvidence.capture(null, false, SkullIcon.NONE, null, null),
            gear(1L, 1L, 150_000L, 0L).quantities);
        engine.markInventoryDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.getType());
        assertFalse(wipe.isCounted());
        assertTrue(wipe.getNote(), wipe.getNote().startsWith("Death: items held"));
        assertTrue(wipe.getExplanation(), wipe.getExplanation().startsWith("Ownership-neutral transfer: Death"));
        assertTrue(wipe.getExplanation().contains("Death evidence"));
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        // Respawn with the coins for the fee (coins are kept on death in this fixture).
        engine.markInventoryDirty();
        assertNull(engine.processIfDirty(gear(0L, 0L, 150_000L, 0L), 4_000L));
        engine.setBaseline(gear(0L, 0L, 150_000L, 0L));

        // Reclaim at Zul-Gwenwynig: 100k leaves, whip + torture return in the same settle.
        assertTrue(engine.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Priestess Zul-Gwenwynig"), 40));
        engine.markInventoryDirty();
        Transaction fee = settle(engine, gear(1L, 1L, 50_000L, 0L), 6_000L);
        assertNotNull(fee);
        assertEquals(TransactionType.CONSUMPTION, fee.getType());
        assertTrue(fee.isCounted());
        assertEquals(-100_000L, fee.getNet());
        assertEquals("Death reclaim", fee.getActivityName());
        assertTrue(fee.getExplanation().startsWith("Item retrieval fee — Zulrah"));
        assertFalse("death evidence must not attach to later reclaim rows",
            fee.getExplanation().contains("Death evidence"));
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));

        // The returned gear is an uncounted transfer row, not revenue.
        List<Transaction> rows = engine.getActiveSession().getTransactions();
        Transaction recovered = null;
        for (Transaction row : rows)
        {
            if ("Death reclaim: items recovered".equals(row.getNote()))
            {
                recovered = row;
            }
        }
        assertNotNull(recovered);
        assertEquals(TransactionType.TRANSFER, recovered.getType());
        assertFalse(recovered.isCounted());
        assertFalse(recovered.getExplanation().contains("Death evidence"));
        assertEquals(2, recovered.getFlows().size());
        // Only the fee moved net: 17M of gear went out and came back without touching profit.
        assertEquals(before - 100_000L, engine.getMetrics(8_000L).net);
    }

    @Test
    public void localDeathEvidenceOnlyChangesTheSettledExplanation()
    {
        ContainerSnapshot carried = gear(1L, 1L, 150_000L, 0L);
        Engine plain = started(carried);
        Engine annotated = started(carried);
        LocalDeathEvidence evidence = LocalDeathEvidence.capture(
            null, true, SkullIcon.SKULL, null, null);

        plain.markLocalPvmDeath(20, null, carried.quantities);
        annotated.markLocalPvmDeath(20, evidence, carried.quantities);
        plain.markInventoryDirty();
        annotated.markInventoryDirty();
        Transaction plainWipe = settle(plain, ContainerSnapshot.empty(), 1_600L);
        Transaction annotatedWipe = settle(annotated, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(plainWipe);
        assertNotNull(annotatedWipe);
        assertEquals(plainWipe.getType(), annotatedWipe.getType());
        assertEquals(plainWipe.getContext(), annotatedWipe.getContext());
        assertEquals(plainWipe.isCounted(), annotatedWipe.isCounted());
        assertEquals(plainWipe.getFlows(), annotatedWipe.getFlows());
        assertEquals(plainWipe.getRevenue(), annotatedWipe.getRevenue());
        assertEquals(plainWipe.getCosts(), annotatedWipe.getCosts());
        assertEquals(plainWipe.getNet(), annotatedWipe.getNet());
        assertFalse(plainWipe.getExplanation().contains("Death evidence"));
        assertTrue(annotatedWipe.getExplanation().contains("Kept: unavailable"));
        assertTrue(annotatedWipe.getExplanation(), annotatedWipe.getExplanation().contains("lost: "));
        assertTrue(annotatedWipe.getExplanation().contains("Abyssal whip"));
        assertTrue(annotatedWipe.getExplanation().contains("Amulet of torture"));
        assertTrue(annotatedWipe.getExplanation().contains("Coins ×150,000"));
        assertTrue(annotatedWipe.getExplanation().contains("Protect Item on; skull: skulled"));
        assertEquals(0L, plain.getMetrics(5_000L).net);
        assertEquals(0L, annotated.getMetrics(5_000L).net);
    }

    @Test
    public void unclassifiedLocalDeathEvidenceOnlyChangesTheSettledExplanation()
    {
        ContainerSnapshot carried = gear(1L, 0L, 150_000L, 0L);
        Engine plain = started(carried);
        Engine annotated = started(carried);
        LocalDeathEvidence evidence = LocalDeathEvidence.capture(
            null, false, SkullIcon.NONE, null, null);

        plain.markUnclassifiedLocalDeath(null);
        annotated.markUnclassifiedLocalDeath(evidence);
        plain.markInventoryDirty();
        annotated.markInventoryDirty();
        Transaction plainLoss = settle(plain, ContainerSnapshot.empty(), 1_600L);
        Transaction annotatedLoss = settle(annotated, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(plainLoss);
        assertNotNull(annotatedLoss);
        assertEquals(plainLoss.getType(), annotatedLoss.getType());
        assertEquals(plainLoss.getContext(), annotatedLoss.getContext());
        assertEquals(plainLoss.isCounted(), annotatedLoss.isCounted());
        assertEquals(plainLoss.getFlows(), annotatedLoss.getFlows());
        assertEquals(plainLoss.getRevenue(), annotatedLoss.getRevenue());
        assertEquals(plainLoss.getCosts(), annotatedLoss.getCosts());
        assertEquals(plainLoss.getNet(), annotatedLoss.getNet());
        assertFalse(plainLoss.getExplanation().contains("Death evidence"));
        assertTrue(annotatedLoss.getExplanation().contains("Death evidence"));
        assertEquals(plain.getMetrics(5_000L).net, annotated.getMetrics(5_000L).net);
    }

    @Test
    public void reclaimWindowLeavesUnrelatedConsumptionToNormalClassification()
    {
        Engine engine = started(gear(0L, 0L, 0L, 2L));
        engine.markLocalPvmDeath(20, null, gear(0L, 0L, 0L, 2L).quantities);
        engine.markInventoryDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertNotNull(wipe);
        engine.setBaseline(gear(0L, 0L, 0L, 2L));
        assertTrue(engine.noteDeathReclaimIntent(BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));
        engine.noteConsumptionIntent(SHARK, 6);
        engine.markInventoryDirty();

        Transaction eat = settle(engine, gear(0L, 0L, 0L, 1L), 1_600L);

        assertNotNull(eat);
        assertEquals("a shark eaten in the window is still a consume", TransactionType.CONSUMPTION, eat.getType());
        assertEquals(-800L, eat.getNet());
        assertFalse("Death reclaim".equals(eat.getActivityName()));
    }

    @Test
    public void unrelatedLootDoesNotConsumeReclaimWindowOrBecomeRecoveredDeathGear()
    {
        Engine engine = started(gear(1L, 0L, 150_000L, 0L));
        engine.markLocalPvmDeath(20, null, gear(1L, 0L, 150_000L, 0L).quantities);
        engine.markInventoryDirty();
        Transaction wipe = settle(engine, gear(0L, 0L, 150_000L, 0L), 1_600L);
        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.getType());
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        assertTrue(engine.noteDeathReclaimIntent(BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));
        engine.markInventoryDirty();
        Transaction loot = settle(engine, gear(0L, 0L, 150_000L, 1L), 4_000L);

        assertNotNull(loot);
        assertEquals("ordinary loot keeps its normal counted classification", TransactionType.GAIN, loot.getType());
        assertTrue(loot.isCounted());
        assertEquals(800L, loot.getNet());
        assertTrue("the unrelated gain must not finish the reclaim", EngineProbe.isAwaitingDeathReclaim(engine));

        engine.markInventoryDirty();
        Transaction fee = settle(engine, gear(1L, 0L, 50_000L, 1L), 6_000L);
        assertNotNull(fee);
        assertEquals(TransactionType.CONSUMPTION, fee.getType());
        assertEquals(-100_000L, fee.getNet());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));

        Transaction recovered = null;
        for (Transaction row : engine.getActiveSession().getTransactions())
        {
            if ("Death reclaim: items recovered".equals(row.getNote()))
            {
                recovered = row;
            }
        }
        assertNotNull(recovered);
        assertEquals(1, recovered.getFlows().size());
        assertEquals(WHIP, recovered.getFlows().get(0).itemId);
        assertEquals(1L, recovered.getFlows().get(0).quantityDelta);
    }

    @Test
    public void observedLootWithSameItemIdAsDeathGearStaysCounted()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markLocalPvmDeath(20, null, gear(1L, 0L, 0L, 0L).quantities);
        engine.markInventoryDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertNotNull(wipe);
        assertTrue(engine.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));

        engine.markLootContext(
            java.util.Collections.singletonMap(WHIP, 1L), 20, "Loot from Dragon", "Dragon");
        engine.markInventoryDirty();
        Transaction loot = settle(engine, gear(1L, 0L, 0L, 0L), 4_000L);

        assertNotNull(loot);
        assertEquals(TransactionType.LOOT, loot.getType());
        assertTrue("RuneLite's matched loot source stays counted", loot.isCounted());
        assertEquals(2_000_000L, loot.getNet());
        assertEquals("Loot from Dragon", loot.getNote());
        assertTrue("the unrelated drop does not finish reclaim", EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void sameIdLootAndReclaimedGearPartitionByMatchedQuantity()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markLocalPvmDeath(20, null, gear(1L, 0L, 0L, 0L).quantities);
        engine.markInventoryDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));
        assertTrue(engine.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));

        engine.markLootContext(
            java.util.Collections.singletonMap(WHIP, 1L), 20, "Loot from Dragon", "Dragon");
        engine.markInventoryDirty();
        Transaction loot = settle(engine,
            new ContainerSnapshot(java.util.Collections.singletonMap(WHIP, 2L)), 4_000L);

        assertNotNull(loot);
        assertEquals("only the source-backed quantity is a counted drop", TransactionType.LOOT, loot.getType());
        assertTrue(loot.isCounted());
        assertEquals(2_000_000L, loot.getNet());
        assertEquals(1L, loot.getFlows().get(0).quantityDelta);
        assertFalse("returning the final wiped item closes the reclaim", EngineProbe.isAwaitingDeathReclaim(engine));

        Transaction recovered = null;
        for (Transaction row : engine.getActiveSession().getTransactions())
        {
            if ("Death reclaim: items recovered".equals(row.getNote()))
            {
                recovered = row;
            }
        }
        assertNotNull(recovered);
        assertEquals(1L, recovered.getFlows().get(0).quantityDelta);
        assertFalse(recovered.isCounted());
    }

    @Test
    public void hardBankTransferDuringReclaimDoesNotBecomeADeathFee()
    {
        Engine engine = started(gear(0L, 0L, 150_000L, 0L));
        engine.markLocalPvmDeath(20, null, gear(0L, 0L, 150_000L, 0L).quantities);
        assertTrue(engine.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));
        engine.markContext(Context.TRANSFER, 20, "Bank deposit");
        engine.markInventoryDirty();

        Transaction deposit = settle(engine, gear(0L, 0L, 149_000L, 0L), 1_600L);

        assertNotNull(deposit);
        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertFalse("bank movement is not labelled as a reclaim fee",
            "Death reclaim".equals(deposit.getActivityName()));
        assertEquals(0L, engine.getMetrics(4_000L).net);
    }

    @Test
    public void laterExplicitMarketContextSupersedesDeathWipeMarker()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markLocalPvmDeath(20, null,
            java.util.Collections.singletonMap(WHIP, 1L));
        engine.markContext(Context.MARKET, 20, "Market sale");
        engine.markInventoryDirty();

        Transaction sale = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(sale);
        assertEquals("the explicit market evidence owns this loss", Context.MARKET,
            sale.getContext());
        assertTrue("the sale remains counted", sale.isCounted());
        assertFalse("it must not be neutralized as a death transfer",
            "Death: items held by gravestone / retrieval service".equals(sale.getNote()));
        assertFalse("the canceled death marker has no reclaim whitelist",
            EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void mixedReclaimAndLootPartitionsReturnsAndKeepsWindowOpen()
    {
        Engine engine = started(gear(1L, 1L, 150_000L, 0L));
        engine.markLocalPvmDeath(20, null, gear(1L, 1L, 150_000L, 0L).quantities);
        engine.markInventoryDirty();
        Transaction wipe = settle(engine, gear(0L, 0L, 150_000L, 0L), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        assertTrue(engine.noteDeathReclaimIntent(BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));
        engine.markInventoryDirty();
        Transaction loot = settle(engine, gear(1L, 0L, 150_000L, 1L), 4_000L);

        assertNotNull(loot);
        assertEquals(TransactionType.GAIN, loot.getType());
        assertTrue(loot.isCounted());
        assertEquals(800L, loot.getNet());
        assertTrue("an unmatched gain must not close a partially returned reclaim", EngineProbe.isAwaitingDeathReclaim(engine));

        Transaction recovered = null;
        for (Transaction row : engine.getActiveSession().getTransactions())
        {
            if ("Death reclaim: items recovered".equals(row.getNote()))
            {
                recovered = row;
            }
        }
        assertNotNull(recovered);
        assertEquals(1, recovered.getFlows().size());
        assertEquals(WHIP, recovered.getFlows().get(0).itemId);

        engine.markInventoryDirty();
        Transaction fee = settle(engine, gear(1L, 1L, 50_000L, 1L), 6_000L);
        assertNotNull(fee);
        assertEquals(TransactionType.CONSUMPTION, fee.getType());
        assertEquals(-100_000L, fee.getNet());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void unclassifiedLocalDeathClearsEarlierReclaimEvidence()
    {
        Engine engine = started(gear(1L, 0L, 150_000L, 0L));
        engine.markLocalPvmDeath(20, null, gear(1L, 0L, 150_000L, 0L).quantities);
        engine.markInventoryDirty();
        Transaction wipe = settle(engine, gear(0L, 0L, 150_000L, 0L), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        engine.markUnclassifiedLocalDeath(null);

        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
        assertFalse("unclassified death must not arm retrieval accounting", engine.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));
        engine.markInventoryDirty();
        Transaction returned = settle(engine, gear(1L, 0L, 150_000L, 0L), 4_000L);

        assertNotNull(returned);
        assertEquals(TransactionType.GAIN, returned.getType());
        assertTrue(returned.isCounted());
    }

    @Test
    public void partialDeathItemReturnKeepsRemainingItemsEligibleForReclaim()
    {
        Engine engine = started(gear(1L, 1L, 150_000L, 0L));
        engine.markLocalPvmDeath(20, null, gear(1L, 1L, 150_000L, 0L).quantities);
        engine.markInventoryDirty();
        Transaction wipe = settle(engine, gear(0L, 0L, 150_000L, 0L), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        assertTrue(engine.noteDeathReclaimIntent(BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));
        engine.markInventoryDirty();
        Transaction firstReturn = settle(engine, gear(1L, 0L, 150_000L, 0L), 4_000L);
        assertNotNull(firstReturn);
        assertEquals("Death reclaim: items recovered", firstReturn.getNote());
        assertTrue("returning only part of the death wipe must leave the reclaim active",
            EngineProbe.isAwaitingDeathReclaim(engine));

        engine.markInventoryDirty();
        Transaction finalReturn = settle(engine, gear(1L, 1L, 150_000L, 0L), 6_000L);
        assertNotNull(finalReturn);
        assertEquals("Death reclaim: items recovered", finalReturn.getNote());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void reclaimStateIsClearedWhenAnotherIdentityIsRestored()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markLocalPvmDeath(20, null, gear(1L, 0L, 0L, 0L).quantities);
        engine.markInventoryDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        engine.restore(new SavedState());
        engine.ensureSession(4_000L);
        engine.setBaseline(ContainerSnapshot.empty());

        assertFalse("account restore cannot retain another owner's death whitelist",
            EngineProbe.isAwaitingDeathReclaim(engine));
        assertFalse(engine.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));
        engine.markInventoryDirty();
        Transaction newOwnerGain = settle(engine, gear(1L, 0L, 0L, 0L), 5_000L);
        assertNotNull(newOwnerGain);
        assertEquals(TransactionType.GAIN, newOwnerGain.getType());
        assertTrue(newOwnerGain.isCounted());
        assertEquals(2_000_000L, newOwnerGain.getNet());
    }

    @Test
    public void reclaimStateSurvivesPauseAndResumeAfterTheDeathWipeSettles()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markLocalPvmDeath(20, null, gear(1L, 0L, 0L, 0L).quantities);
        engine.markInventoryDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        engine.pauseForLifecycle(3_000L);
        assertNull("paused inventory churn is ignored", engine.processIfDirty(gear(0L, 0L, 0L, 1L), 3_200L));
        engine.resume(4_000L, PauseReason.LIFECYCLE);
        engine.setBaseline(ContainerSnapshot.empty());
        assertTrue("pause must not discard same-owner death ownership evidence",
            EngineProbe.isAwaitingDeathReclaim(engine));
        assertTrue(engine.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));

        engine.markInventoryDirty();
        Transaction returned = settle(engine, gear(1L, 0L, 0L, 0L), 5_000L);

        assertNotNull(returned);
        assertEquals(TransactionType.TRANSFER, returned.getType());
        assertFalse(returned.isCounted());
        assertEquals(0L, engine.getMetrics(8_000L).net);
    }

    @Test
    public void persistedDeathEvidenceKeepsReclaimNeutralAcrossRestart()
    {
        Engine first = started(gear(1L, 0L, 0L, 0L));
        first.markLocalPvmDeath(20, null, gear(1L, 0L, 0L, 0L).quantities);
        first.markInventoryDirty();
        assertNotNull(settle(first, ContainerSnapshot.empty(), 1_600L));
        assertTrue(EngineProbe.isAwaitingDeathReclaim(first));

        SavedState direct = first.createSavedState();
        assertNotNull("pending record on the live snapshot", direct.pendingDeathReclaim);
        SavedState persisted = roundTrip(direct);
        assertNotNull("the pending death record must persist", persisted.pendingDeathReclaim);
        assertEquals(1, persisted.pendingDeathReclaim.getOutstandingItems().size());
        assertEquals(WHIP, persisted.pendingDeathReclaim.getOutstandingItems().get(0).itemId);

        Engine restarted = new Engine(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(persisted, 10_000L);
        restarted.resume(11_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        restarted.setBaseline(ContainerSnapshot.empty());
        assertTrue("a restart restores the pending reclaim", EngineProbe.isAwaitingDeathReclaim(restarted));
        assertTrue(restarted.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));

        restarted.markInventoryDirty();
        Transaction returned = settle(restarted, gear(1L, 0L, 0L, 0L), 12_000L);

        assertNotNull(returned);
        assertEquals(TransactionType.TRANSFER, returned.getType());
        assertFalse(returned.isCounted());
        assertEquals("reclaimed gear must not become revenue", 0L, restarted.getMetrics(20_000L).revenue);
        assertEquals(0L, restarted.getMetrics(20_000L).net);
        assertFalse("a full reclaim clears the pending state", EngineProbe.isAwaitingDeathReclaim(restarted));
        assertNull(roundTrip(restarted.createSavedState()).pendingDeathReclaim);
    }

    @Test
    public void persistedDeathEvidenceBeforeWipeSurvivesRestart()
    {
        ContainerSnapshot carried = gear(1L, 0L, 0L, 0L);
        Engine first = started(carried);
        first.markLocalPvmDeath(20, null, carried.quantities);

        SavedState persisted = roundTrip(first.createSavedState());
        assertNotNull("death evidence is persisted before the inventory wipe", persisted.pendingDeathReclaim);

        Engine restarted = new Engine(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(persisted, 10_000L);
        restarted.resume(11_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        restarted.setBaseline(carried);
        restarted.markInventoryDirty();
        Transaction wipe = settle(restarted, ContainerSnapshot.empty(), 12_000L);

        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.getType());
        assertFalse("the post-restart death wipe remains ownership-neutral", wipe.isCounted());
        assertTrue(EngineProbe.isAwaitingDeathReclaim(restarted));
    }

    @Test
    public void partialReclaimRemainderSurvivesRestart()
    {
        Engine first = started(gear(1L, 1L, 0L, 0L));
        first.markLocalPvmDeath(20, null, gear(1L, 1L, 0L, 0L).quantities);
        first.markInventoryDirty();
        assertNotNull(settle(first, ContainerSnapshot.empty(), 1_600L));
        assertTrue(first.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));
        first.markInventoryDirty();
        Transaction partial = settle(first, gear(1L, 0L, 0L, 0L), 3_000L);
        assertNotNull(partial);
        assertEquals(TransactionType.TRANSFER, partial.getType());
        assertEquals("one item remains outstanding", 1L, first.getDeathReclaimStatus().outstandingItemCount);

        SavedState persisted = roundTrip(first.createSavedState());
        assertNotNull(persisted.pendingDeathReclaim);
        assertEquals(1, persisted.pendingDeathReclaim.getOutstandingItems().size());

        Engine restarted = new Engine(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(persisted, 10_000L);
        restarted.resume(11_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        restarted.setBaseline(gear(1L, 0L, 0L, 0L));
        assertEquals(1L, restarted.getDeathReclaimStatus().outstandingItemCount);
        assertTrue(restarted.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));
        restarted.markInventoryDirty();
        Transaction remainder = settle(restarted, gear(1L, 1L, 0L, 0L), 12_000L);

        assertNotNull(remainder);
        assertEquals(TransactionType.TRANSFER, remainder.getType());
        assertEquals(0L, restarted.getMetrics(20_000L).net);
        assertFalse(EngineProbe.isAwaitingDeathReclaim(restarted));
        assertNull(roundTrip(restarted.createSavedState()).pendingDeathReclaim);
    }

    @Test
    public void closedReclaimCannotReclassifyLaterGainsAfterRestart()
    {
        Engine first = started(gear(1L, 0L, 0L, 0L));
        first.markLocalPvmDeath(20, null, gear(1L, 0L, 0L, 0L).quantities);
        first.markInventoryDirty();
        assertNotNull(settle(first, ContainerSnapshot.empty(), 1_600L));
        assertTrue(first.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));
        first.markInventoryDirty();
        assertNotNull(settle(first, gear(1L, 0L, 0L, 0L), 3_000L));
        assertNull(roundTrip(first.createSavedState()).pendingDeathReclaim);

        Engine restarted = new Engine(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(roundTrip(first.createSavedState()), 10_000L);
        restarted.resume(11_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        restarted.setBaseline(ContainerSnapshot.empty());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(restarted));
        assertFalse("closed reclaim evidence cannot arm again", restarted.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Torfinn"), 40));

        restarted.markInventoryDirty();
        Transaction later = settle(restarted, gear(1L, 0L, 0L, 0L), 12_000L);
        assertNotNull(later);
        assertEquals(TransactionType.GAIN, later.getType());
        assertTrue(later.isCounted());
        assertEquals(2_000_000L, restarted.getMetrics(20_000L).revenue);
    }

    @Test
    public void ownerSwitchAndDestructiveResetClearPersistedDeathEvidence()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markLocalPvmDeath(20, null, gear(1L, 0L, 0L, 0L).quantities);
        engine.markInventoryDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));
        assertNotNull(engine.createSavedState().pendingDeathReclaim);

        // Another owner's state carries no pending record and must not inherit this one.
        engine.restoreForProfile("profile-b", new SavedState(), 3_000L);
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
        assertNull(engine.createSavedState().pendingDeathReclaim);

        Engine reset = started(gear(1L, 0L, 0L, 0L));
        reset.markLocalPvmDeath(20, null, gear(1L, 0L, 0L, 0L).quantities);
        reset.markInventoryDirty();
        assertNotNull(settle(reset, ContainerSnapshot.empty(), 1_600L));
        reset.resetTrackingData(3_000L);
        assertFalse(EngineProbe.isAwaitingDeathReclaim(reset));
        assertNull(reset.createSavedState().pendingDeathReclaim);
    }

    @Test
    public void observedReclaimFeeAfterRestartBooksOnce()
    {
        Engine first = started(gear(1L, 0L, 150_000L, 0L));
        first.markLocalPvmDeath(20, null, gear(1L, 0L, 150_000L, 0L).quantities);
        first.markInventoryDirty();
        assertNotNull(settle(first, ContainerSnapshot.empty(), 1_600L));

        Engine restarted = new Engine(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(roundTrip(first.createSavedState()), 10_000L);
        restarted.resume(11_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        restarted.setBaseline(gear(0L, 0L, 150_000L, 0L));
        assertTrue(restarted.noteDeathReclaimIntent(
            BossRetrievalCatalogue.forMenu("Talk-to", "Priestess Zul-Gwenwynig"), 40));
        restarted.markInventoryDirty();
        Transaction fee = settle(restarted, gear(1L, 0L, 50_000L, 0L), 12_000L);

        assertNotNull(fee);
        assertEquals(TransactionType.CONSUMPTION, fee.getType());
        assertTrue(fee.isCounted());
        assertEquals(-100_000L, fee.getNet());
        assertEquals(100_000L, restarted.getMetrics(20_000L).costs);
        assertEquals(0L, restarted.getMetrics(20_000L).revenue);

        // Replaying the same settled state cannot book a second fee.
        restarted.markInventoryDirty();
        assertNull(settle(restarted, gear(1L, 0L, 50_000L, 0L), 20_000L));
        assertEquals(100_000L, restarted.getMetrics(30_000L).costs);
    }

    private static SavedState roundTrip(SavedState state)
    {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        return gson.fromJson(gson.toJson(state), SavedState.class);
    }

    private Engine started(ContainerSnapshot baseline)
    {
        return started(baseline, CONFIG);
    }

    private Engine started(ContainerSnapshot baseline, GpManagerConfig config)
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        engine.setBaseline(baseline);
        return engine;
    }

    private static ContainerSnapshot gear(long whip, long torture, long coins, long sharks)
    {
        Map<Integer, Long> values = new HashMap<>();
        if (whip > 0L)
        {
            values.put(WHIP, whip);
        }
        if (torture > 0L)
        {
            values.put(TORTURE, torture);
        }
        if (coins > 0L)
        {
            values.put(COINS, coins);
        }
        if (sharks > 0L)
        {
            values.put(SHARK, sharks);
        }
        return new ContainerSnapshot(values);
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        assertNull(engine.processIfDirty(snapshot, firstTick));
        assertNull(engine.processIfDirty(snapshot, firstTick + 600L));
        return engine.processIfDirty(snapshot, firstTick + 1_200L);
    }

    private static Transaction findTransaction(Engine engine, String note)
    {
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            if (note.equals(transaction.getNote()))
            {
                return transaction;
            }
        }
        return null;
    }
}
