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
        List<Ab> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int id = entry.getKey();
            int price = id == COINS ? 1 : id == SHARK ? 800 : id == WHIP ? 2_000_000 : 15_000_000;
            String name = id == COINS ? "Coins" : id == SHARK ? "Shark" : id == WHIP ? "Abyssal whip" : "Amulet of torture";
            flows.add(new Ab(id, name, entry.getValue(), price, entry.getValue() * price));
        }
        return flows;
    };

    @Test
    public void catalogueMatchesServicesByNameAndRetrievalVerb()
    {
        BossRetrievalCatalogue.Service zulrah = BossRetrievalCatalogue.axr("Talk-to", "Priestess Zul-Gwenwynig");
        assertNotNull(zulrah);
        assertEquals(100_000L, zulrah.expectedFee);
        assertFalse(zulrah.ambiguousTarget);
        assertEquals(60_000L, BossRetrievalCatalogue.axr("Claim", "Shura").expectedFee);
        assertEquals(50_000L, BossRetrievalCatalogue.axr("Search", "Magical chest").expectedFee);
        assertEquals(25_000L, BossRetrievalCatalogue.axr("Talk-to", "Arno").expectedFee);
        BossRetrievalCatalogue.Service chest = BossRetrievalCatalogue.axr("Open", "Chest");
        assertNotNull(chest);
        assertTrue(chest.ambiguousTarget);
        assertEquals(BossRetrievalCatalogue.VARIABLE_FEE, chest.expectedFee);
        assertTrue(BossRetrievalCatalogue.axr("Loot", "Gravestone").ambiguousTarget);
        // Non-retrieval verbs and unrelated NPCs never match.
        assertNull(BossRetrievalCatalogue.axr("Attack", "Priestess Zul-Gwenwynig"));
        assertNull(BossRetrievalCatalogue.axr("Talk-to", "Banker"));
        assertNull(BossRetrievalCatalogue.axr("Use", "Chest"));
    }

    @Test
    public void whyLineCarriesPublishedFeeAndFlagsAMismatch()
    {
        BossRetrievalCatalogue.Service zulrah = BossRetrievalCatalogue.axr("Talk-to", "Priestess Zul-Gwenwynig");
        assertEquals(
            "Item retrieval fee — Zulrah (Priestess Zul-Gwenwynig), published 100,000. Free below 50 kills and for Ultimate Ironmen",
            zulrah.why(100_000L));
        assertTrue(zulrah.why(90_000L).contains("observed 90,000"));
        assertTrue(BossRetrievalCatalogue.axr("Open", "Chest").why(250_000L).contains("fee varies"));
    }

    @Test
    public void lifecycleAcceptsAmbiguousTargetsOnlyWhileAwaitingReclaim()
    {
        DeathReclaimLifecycle lifecycle = new DeathReclaimLifecycle();
        BossRetrievalCatalogue.Service chest = BossRetrievalCatalogue.axr("Open", "Chest");
        BossRetrievalCatalogue.Service torfinn = BossRetrievalCatalogue.axr("Talk-to", "Torfinn");
        assertFalse("random chest without a death is not a reclaim", lifecycle.aca(chest, 10));
        assertFalse("a generic Talk-to at a service NPC is not enough", lifecycle.aca(torfinn, 10));
        lifecycle.ace(java.util.Collections.singletonMap(WHIP, 1L));
        lifecycle.onDeathItemsRemoved(java.util.Collections.singletonList(
            new Ab(WHIP, "Abyssal whip", -1L, 15_000_000, -15_000_000L)));
        assertTrue(lifecycle.isAwaitingReclaim());
        assertTrue("a named service is accepted after a local PvM death", lifecycle.aca(torfinn, 10));
        assertTrue(lifecycle.aca(chest, 10));
        BossRetrievalCatalogue.Service grave = BossRetrievalCatalogue.axr("Check", "Grave");
        assertNotNull(grave);
        assertTrue("a Grave Check re-arms an awaiting reclaim", lifecycle.aca(grave, 1));
        assertTrue(lifecycle.xk());
        for (int i = 0; i < DeathReclaimLifecycle.MIN_RECLAIM_ARM_TICKS - 1; i++)
        {
            lifecycle.tick(false);
        }
        assertTrue("reclaim stays armed through its two-minute minimum", lifecycle.xk());
        lifecycle.tick(false);
        assertFalse("window expires", lifecycle.xk());
        assertTrue("death still awaits", lifecycle.isAwaitingReclaim());
        lifecycle.reset();
        assertFalse(lifecycle.isAwaitingReclaim());
    }

    @Test
    public void missingDeathSnapshotCannotAuthorizeNeutralizingLosses()
    {
        DeathReclaimLifecycle lifecycle = new DeathReclaimLifecycle();
        lifecycle.ace((Map<Integer, Long>) null);

        assertFalse("no canonical snapshot means no reclaim whitelist",
            lifecycle.wh());
        assertFalse(lifecycle.isAwaitingReclaim());
        assertTrue(lifecycle.onDeathItemsRemoved(java.util.Collections.singletonList(
            new Ab(WHIP, "Abyssal whip", -1L, 2_000_000, -2_000_000L))).isEmpty());
    }

    @Test
    public void missingEngineDeathSnapshotLeavesMeasuredLossCounted()
    {
        Am engine = started(gear(1L, 0L, 0L, 0L));
        engine.zi(20, null, null);
        engine.yz();

        Ac loss = settle(engine, Cc.empty(), 1_600L);

        assertNotNull(loss);
        assertEquals(Ai.CONSUMPTION, loss.getType());
        assertTrue("without the canonical death snapshot the loss remains counted", loss.isCounted());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void delayedDeathWipeUsesCapturedStacksAfterDeathContextExpiresAndDirectLootIsNeutral()
    {
        Cc carried = gear(1L, 1L, 0L, 0L);
        Am engine = started(carried);
        engine.zi(1, null, carried.quantities);

        // Let the short-lived transfer context expire before the post-respawn inventory
        // removal settles. The separate captured-stack evidence remains live.
        for (int i = 0; i < 5; i++)
        {
            assertNull(engine.adj(carried, 1_100L + i * 600L));
        }
        engine.yz();
        Ac wipe = settle(engine, Cc.empty(), 5_000L);

        assertNotNull(wipe);
        assertEquals(Ai.TRANSFER, wipe.getType());
        assertFalse(wipe.isCounted());
        assertEquals("Death: items held by gravestone / retrieval service", wipe.getNote());
        assertEquals(2L, engine.tt().outstandingItemCount);
        assertTrue(engine.tt().awaiting);
        assertTrue(engine.tt().ageTicks >= 5L);

        // A direct gravestone Loot needs no still-live reclaim menu window.
        engine.setBaseline(Cc.empty());
        engine.yz();
        Ac returned = settle(engine, carried, 8_000L);
        assertNotNull(returned);
        assertEquals(Ai.TRANSFER, returned.getType());
        assertFalse(returned.isCounted());
        assertEquals("Death reclaim: items recovered", returned.getNote());
        assertFalse(engine.tt().awaiting);
        assertEquals(0L, engine.getMetrics(12_000L).net);
    }

    @Test
    public void deathWipeOnlyTransfersItemsActuallyHeldAtDeath()
    {
        Cc baseline = gear(1L, 1L, 0L, 0L);
        Am engine = started(baseline);
        engine.zi(1, null, gear(1L, 0L, 0L, 0L).quantities);
        engine.yz();
        Ac residualLoss = settle(engine, Cc.empty(), 1_600L);

        assertNotNull(residualLoss);
        assertEquals("unobserved ownership follows ordinary accounting", Ai.CONSUMPTION,
            residualLoss.getType());
        assertTrue(residualLoss.isCounted());
        assertEquals(-15_000_000L, residualLoss.getNet());
        assertEquals(1L, engine.tt().outstandingItemCount);
        Ac deathTransfer = sw(engine,
            "Death: items held by gravestone / retrieval service");
        assertNotNull(deathTransfer);
        assertFalse(deathTransfer.isCounted());
        assertEquals(WHIP, deathTransfer.getFlows().get(0).itemId);
    }

    @Test
    public void splitInventoryAndEquipmentLossesBothUseTheRemainingDeathWhitelist()
    {
        Cc carried = gear(1L, 1L, 0L, 0L);
        Am engine = started(carried);
        engine.zi(1, null, carried.quantities);

        engine.yz();
        Ac firstWipe = settle(engine, gear(0L, 1L, 0L, 0L), 1_600L);
        assertNotNull(firstWipe);
        assertEquals(Ai.TRANSFER, firstWipe.getType());
        assertTrue(engine.tt().awaiting);
        assertEquals("only the still-held item remains outstanding", 1L,
            engine.tt().outstandingItemCount);

        engine.setBaseline(gear(0L, 1L, 0L, 0L));
        engine.yz();
        Ac secondWipe = settle(engine, Cc.empty(), 3_000L);
        assertNotNull(secondWipe);
        assertEquals(Ai.TRANSFER, secondWipe.getType());
        assertFalse(secondWipe.isCounted());
        assertEquals(2L, engine.tt().outstandingItemCount);
    }

    @Test
    public void delayedCoinOnlyDeathWipeIsNeutralEvenAfterDeathContextExpires()
    {
        Cc carried = gear(0L, 0L, 75_000L, 0L);
        Am engine = started(carried);
        engine.zi(1, null, carried.quantities);
        for (int i = 0; i < 5; i++)
        {
            assertNull(engine.adj(carried, 1_100L + i * 600L));
        }

        engine.yz();
        Ac wipe = settle(engine, Cc.empty(), 5_000L);

        assertNotNull(wipe);
        assertEquals(Ai.TRANSFER, wipe.getType());
        assertFalse(wipe.isCounted());
        assertEquals("Death: items held by gravestone / retrieval service", wipe.getNote());
        assertEquals(0L, engine.getMetrics(8_000L).net);
    }

    @Test
    public void armedReclaimDoesNotMislabelCoinLossAlongsideDelayedDeathWipeAsFee()
    {
        Cc carried = gear(1L, 0L, 200_000L, 0L);
        Am engine = started(carried);
        engine.zi(20, null, carried.quantities);
        assertTrue(engine.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));
        engine.yz();

        Ac ambiguousWipe = settle(engine, gear(0L, 0L, 180_000L, 0L), 1_600L);

        assertNotNull(ambiguousWipe);
        assertTrue("ambiguous coin outflow stays an ordinary measured cost", ambiguousWipe.isCounted());
        assertEquals("the ordinary cost contains the observed coin loss", -20_000L,
            ambiguousWipe.getNet());
        assertFalse("a menu interaction must not turn an overlapping death wipe into a fee",
            "Death reclaim".equals(ambiguousWipe.getActivityName()));
        assertEquals(1, sw(engine,
            "Death: items held by gravestone / retrieval service").getFlows().size());

        engine.setBaseline(gear(0L, 0L, 180_000L, 0L));
        engine.yz();
        Ac observedFee = settle(engine, gear(1L, 0L, 80_000L, 0L), 4_000L);

        assertNotNull(observedFee);
        assertEquals("a separate observed outflow with a matching returned item remains a fee",
            "Death reclaim", observedFee.getActivityName());
        assertEquals(-100_000L, observedFee.getNet());
    }

    @Test
    public void pendingDeathSnapshotCannotHideLaterUnclaimedGeMovement()
    {
        Cc carried = gear(1L, 0L, 0L, 0L);
        Am engine = started(carried);
        engine.zi(1, null, carried.quantities);
        for (int i = 0; i < 3; i++)
        {
            assertNull(engine.adj(carried, 1_100L + i * 600L));
        }
        engine.markContext(Aj.MARKET, 100, "Grand Exchange sale");
        engine.yz();

        Ac movement = settle(engine, gear(0L, 0L, 15_000_000L, 0L), 1_600L);

        assertNotNull(movement);
        assertEquals(Aj.MARKET, movement.getContext());
        assertEquals(Ai.UNCERTAIN, movement.getType());
        assertFalse("without custody, the GE movement waits for owner review", movement.isCounted());
        assertEquals(0L, engine.getMetrics(1_600L).net);
        assertFalse("the stronger market evidence closes the stale wipe whitelist",
            engine.tt().awaiting);
    }

    @Test
    public void confirmedConsumptionOfSameItemOutranksPendingDeathSnapshot()
    {
        Cc carried = gear(1L, 0L, 0L, 0L);
        Am engine = started(carried);
        engine.zi(1, null, carried.quantities);
        for (int i = 0; i < 3; i++)
        {
            assertNull(engine.adj(carried, 1_100L + i * 600L));
        }
        engine.noteConsumptionIntent(WHIP, 20);
        engine.yz();

        Ac consumed = settle(engine, Cc.empty(), 3_000L);

        assertNotNull(consumed);
        assertEquals(Ai.CONSUMPTION, consumed.getType());
        assertTrue(consumed.isCounted());
        assertFalse(engine.tt().awaiting);
    }

    @Test
    public void mixedDeathWipeAndNamedConsumptionPartitionByItemId()
    {
        Cc carried = gear(1L, 0L, 0L, 1L);
        Am engine = started(carried);
        engine.zi(1, null, carried.quantities);
        engine.noteConsumptionIntent(SHARK, 30);
        engine.yz();

        Ac consumption = settle(engine, Cc.empty(), 1_600L);

        assertNotNull(consumption);
        assertEquals(Ai.CONSUMPTION, consumption.getType());
        assertTrue(consumption.isCounted());
        Ac deathTransfer = sw(engine,
            "Death: items held by gravestone / retrieval service");
        assertNotNull("unrelated whip loss remains an ownership-neutral death transfer", deathTransfer);
        assertFalse(deathTransfer.isCounted());
        assertEquals(WHIP, deathTransfer.getFlows().get(0).itemId);
        assertEquals(1L, engine.tt().outstandingItemCount);
    }

    @Test
    public void finalGravestoneReturnSettlingOnExpiryTickWinsBeforeExpiryAudit()
    {
        Cc carried = gear(1L, 0L, 0L, 0L);
        Am engine = started(carried);
        engine.zi(1, null, carried.quantities);
        engine.yz();
        assertNotNull(settle(engine, Cc.empty(), 1_600L));
        engine.setBaseline(Cc.empty());

        while (engine.tt().ageTicks
            < DeathReclaimLifecycle.AWAIT_TICKS - 1L)
        {
            assertNull(engine.adj(Cc.empty(), 4_000L));
        }
        // The final return can be observed before a delayed/missed dirty callback.
        Ac returned = settle(engine, carried, 5_000L);

        assertNotNull(returned);
        assertEquals(Ai.TRANSFER, returned.getType());
        assertEquals("Death reclaim: items recovered", returned.getNote());
        assertFalse(engine.tt().awaiting);
        assertNull(sw(engine, "Death reclaim expired"));
    }

    @Test
    public void deathWipeWhitelistExpiresAtItsOneMinuteEvidenceCap()
    {
        DeathReclaimLifecycle lifecycle = new DeathReclaimLifecycle();
        lifecycle.ace(java.util.Collections.singletonMap(WHIP, 1L));
        for (int i = 0; i < DeathReclaimLifecycle.DEATH_WIPE_WINDOW_TICKS - 1; i++)
        {
            lifecycle.tick(false);
        }
        assertTrue(lifecycle.wh());
        lifecycle.tick(false);
        assertFalse(lifecycle.wh());
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
        Cc carried = gear(1L, 0L, 0L, 1L);
        Am engine = started(carried, changingConfig);
        engine.zi(1, null, carried.quantities);
        engine.yz();
        assertNotNull(settle(engine, Cc.empty(), 1_600L));
        assertEquals(2L, engine.tt().outstandingItemCount);

        stabilization[0] = DeathReclaimLifecycle.AWAIT_TICKS + 100;
        engine.setBaseline(Cc.empty());
        engine.yz();
        Cc changing = new Cc(
            java.util.Collections.singletonMap(99_999, 1L));
        Ac expired = null;
        Ac unrelatedGain = null;
        int maxWait = DeathReclaimLifecycle.AWAIT_TICKS + stabilization[0] + 5;
        for (int i = 0; i < maxWait; i++)
        {
            Ac tick = engine.adj(changing, 4_000L + i * 600L);
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
        assertFalse(engine.tt().awaiting);
        engine.adj(changing, 4_000L + maxWait * 600L);
    }

    @Test
    public void observedCoinFeeAndReturnedItemsSplitIntoSeparateRows()
    {
        Cc carried = gear(1L, 0L, 150_000L, 0L);
        Am engine = started(carried);
        engine.zi(1, null, carried.quantities);
        engine.yz();
        assertNotNull(settle(engine, Cc.empty(), 1_600L));
        engine.setBaseline(gear(0L, 0L, 150_000L, 0L));

        assertTrue(engine.abe(
            BossRetrievalCatalogue.axr("Loot", "Gravestone"), 1));
        assertTrue(engine.tt().armed);
        engine.yz();
        Ac fee = settle(engine, gear(1L, 0L, 50_000L, 0L), 4_000L);

        assertNotNull(fee);
        assertEquals(Ai.CONSUMPTION, fee.getType());
        assertEquals(-100_000L, fee.getNet());
        assertTrue(fee.getExplanation().contains("Gravestone"));
        assertFalse(engine.tt().awaiting);
        Ac recovered = sw(engine, "Death reclaim: items recovered");
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
        Cc carried = gear(0L, 0L, 0L, 1L);
        Am engine = started(carried, minimum);
        engine.zi(1, null, carried.quantities);
        engine.yz();
        Ac wipe = settle(engine, Cc.empty(), 1_600L);

        assertNotNull(wipe);
        assertEquals(Ai.TRANSFER, wipe.getType());
        engine.setBaseline(Cc.empty());
        engine.yz();
        Ac returned = settle(engine, carried, 4_000L);

        assertNotNull("the measured return is partitioned before the minimum-value gate", returned);
        assertEquals(Ai.TRANSFER, returned.getType());
        assertFalse(returned.isCounted());
        assertEquals("Death reclaim: items recovered", returned.getNote());
        assertFalse(engine.tt().awaiting);
        assertEquals(0L, engine.getMetrics(8_000L).net);
    }

    @Test
    public void partialLootReportsOutstandingItemsThenExpiresWithoutGuessingLoss()
    {
        Cc carried = gear(1L, 1L, 0L, 0L);
        Am engine = started(carried);
        engine.zi(1, null, carried.quantities);
        engine.yz();
        assertNotNull(settle(engine, Cc.empty(), 1_600L));
        engine.setBaseline(Cc.empty());
        engine.yz();
        Ac firstReturn = settle(engine, gear(1L, 0L, 0L, 0L), 4_000L);

        assertNotNull(firstReturn);
        assertEquals(Ai.TRANSFER, firstReturn.getType());
        assertTrue(engine.tt().awaiting);
        assertEquals(1L, engine.tt().outstandingItemCount);

        Ac expired = null;
        Cc afterPartialReturn = gear(1L, 0L, 0L, 0L);
        for (int i = 0; i < DeathReclaimLifecycle.AWAIT_TICKS; i++)
        {
            Ac tick = engine.adj(afterPartialReturn, 6_000L + i * 600L);
            if (tick != null)
            {
                expired = tick;
            }
        }

        assertNotNull("timer expiry is retained as an audit event", expired);
        assertEquals("Death reclaim expired", expired.getNote());
        assertEquals(Ai.TRANSFER, expired.getType());
        assertFalse(expired.isCounted());
        assertTrue(expired.getFlows().isEmpty());
        assertTrue(expired.getExplanation().contains("Amulet of torture"));
        assertTrue(expired.getExplanation().contains("No item loss or fee was inferred"));
        assertFalse(engine.tt().awaiting);
        assertEquals(0L, engine.getMetrics(1_000_000L).net);
    }

    @Test
    public void secondDeathMergesOutstandingItemsIntoNewGravestone()
    {
        Cc firstHeld = gear(1L, 0L, 0L, 0L);
        Am engine = started(firstHeld);
        engine.zi(1, null, firstHeld.quantities);
        engine.yz();
        assertNotNull(settle(engine, Cc.empty(), 1_600L));

        Cc secondHeld = gear(0L, 1L, 0L, 0L);
        engine.setBaseline(secondHeld);
        engine.zi(1, null, secondHeld.quantities);
        engine.yz();
        assertNotNull(settle(engine, Cc.empty(), 4_000L));
        assertTrue(engine.tt().awaiting);
        assertEquals(2L, engine.tt().outstandingItemCount);

        engine.setBaseline(Cc.empty());
        engine.yz();
        Ac recovered = settle(engine, gear(1L, 1L, 0L, 0L), 6_000L);
        assertNotNull(recovered);
        assertEquals(Ai.TRANSFER, recovered.getType());
        assertEquals("Death reclaim: items recovered", recovered.getNote());
        assertFalse(engine.tt().awaiting);
        assertEquals(0L, engine.getMetrics(10_000L).net);
    }

    @Test
    public void pvmDeathWipeIsATransferAndZulrahReclaimReturnsItemsAndBooksTheFee()
    {
        Am engine = started(gear(1L, 1L, 150_000L, 0L));
        long before = engine.getMetrics(1_000L).net;

        // Death: everything leaves the inventory in one settle.
        engine.zi(20,
            Ch.capture(null, false, SkullIcon.NONE, null, null),
            gear(1L, 1L, 150_000L, 0L).quantities);
        engine.yz();
        Ac wipe = settle(engine, Cc.empty(), 1_600L);
        assertNotNull(wipe);
        assertEquals(Ai.TRANSFER, wipe.getType());
        assertFalse(wipe.isCounted());
        assertTrue(wipe.getNote(), wipe.getNote().startsWith("Death: items held"));
        assertTrue(wipe.getExplanation(), wipe.getExplanation().startsWith("Ownership-neutral transfer: Death"));
        assertTrue(wipe.getExplanation().contains("Death evidence"));
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        // Respawn with the coins for the fee (coins are kept on death in this fixture).
        engine.yz();
        assertNull(engine.adj(gear(0L, 0L, 150_000L, 0L), 4_000L));
        engine.setBaseline(gear(0L, 0L, 150_000L, 0L));

        // Reclaim at Zul-Gwenwynig: 100k leaves, whip + torture return in the same settle.
        assertTrue(engine.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Priestess Zul-Gwenwynig"), 40));
        engine.yz();
        Ac fee = settle(engine, gear(1L, 1L, 50_000L, 0L), 6_000L);
        assertNotNull(fee);
        assertEquals(Ai.CONSUMPTION, fee.getType());
        assertTrue(fee.isCounted());
        assertEquals(-100_000L, fee.getNet());
        assertEquals("Death reclaim", fee.getActivityName());
        assertTrue(fee.getExplanation().startsWith("Item retrieval fee — Zulrah"));
        assertFalse("death evidence must not attach to later reclaim rows",
            fee.getExplanation().contains("Death evidence"));
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));

        // The returned gear is an uncounted transfer row, not revenue.
        List<Ac> rows = engine.getActiveSession().getTransactions();
        Ac recovered = null;
        for (Ac row : rows)
        {
            if ("Death reclaim: items recovered".equals(row.getNote()))
            {
                recovered = row;
            }
        }
        assertNotNull(recovered);
        assertEquals(Ai.TRANSFER, recovered.getType());
        assertFalse(recovered.isCounted());
        assertFalse(recovered.getExplanation().contains("Death evidence"));
        assertEquals(2, recovered.getFlows().size());
        // Only the fee moved net: 17M of gear went out and came back without touching profit.
        assertEquals(before - 100_000L, engine.getMetrics(8_000L).net);
    }

    @Test
    public void localDeathEvidenceOnlyChangesTheSettledExplanation()
    {
        Cc carried = gear(1L, 1L, 150_000L, 0L);
        Am plain = started(carried);
        Am annotated = started(carried);
        Ch evidence = Ch.capture(
            null, true, SkullIcon.SKULL, null, null);

        plain.zi(20, null, carried.quantities);
        annotated.zi(20, evidence, carried.quantities);
        plain.yz();
        annotated.yz();
        Ac plainWipe = settle(plain, Cc.empty(), 1_600L);
        Ac annotatedWipe = settle(annotated, Cc.empty(), 1_600L);

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
        Cc carried = gear(1L, 0L, 150_000L, 0L);
        Am plain = started(carried);
        Am annotated = started(carried);
        Ch evidence = Ch.capture(
            null, false, SkullIcon.NONE, null, null);

        plain.zj(null);
        annotated.zj(evidence);
        plain.yz();
        annotated.yz();
        Ac plainLoss = settle(plain, Cc.empty(), 1_600L);
        Ac annotatedLoss = settle(annotated, Cc.empty(), 1_600L);

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
        Am engine = started(gear(0L, 0L, 0L, 2L));
        engine.zi(20, null, gear(0L, 0L, 0L, 2L).quantities);
        engine.yz();
        Ac wipe = settle(engine, Cc.empty(), 1_600L);
        assertNotNull(wipe);
        engine.setBaseline(gear(0L, 0L, 0L, 2L));
        assertTrue(engine.abe(BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));
        engine.noteConsumptionIntent(SHARK, 6);
        engine.yz();

        Ac eat = settle(engine, gear(0L, 0L, 0L, 1L), 1_600L);

        assertNotNull(eat);
        assertEquals("a shark eaten in the window is still a consume", Ai.CONSUMPTION, eat.getType());
        assertEquals(-800L, eat.getNet());
        assertFalse("Death reclaim".equals(eat.getActivityName()));
    }

    @Test
    public void unrelatedLootDoesNotConsumeReclaimWindowOrBecomeRecoveredDeathGear()
    {
        Am engine = started(gear(1L, 0L, 150_000L, 0L));
        engine.zi(20, null, gear(1L, 0L, 150_000L, 0L).quantities);
        engine.yz();
        Ac wipe = settle(engine, gear(0L, 0L, 150_000L, 0L), 1_600L);
        assertNotNull(wipe);
        assertEquals(Ai.TRANSFER, wipe.getType());
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        assertTrue(engine.abe(BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));
        engine.yz();
        Ac loot = settle(engine, gear(0L, 0L, 150_000L, 1L), 4_000L);

        assertNotNull(loot);
        assertEquals("ordinary loot keeps its normal counted classification", Ai.GAIN, loot.getType());
        assertTrue(loot.isCounted());
        assertEquals(800L, loot.getNet());
        assertTrue("the unrelated gain must not finish the reclaim", EngineProbe.isAwaitingDeathReclaim(engine));

        engine.yz();
        Ac fee = settle(engine, gear(1L, 0L, 50_000L, 1L), 6_000L);
        assertNotNull(fee);
        assertEquals(Ai.CONSUMPTION, fee.getType());
        assertEquals(-100_000L, fee.getNet());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));

        Ac recovered = null;
        for (Ac row : engine.getActiveSession().getTransactions())
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
        Am engine = started(gear(1L, 0L, 0L, 0L));
        engine.zi(20, null, gear(1L, 0L, 0L, 0L).quantities);
        engine.yz();
        Ac wipe = settle(engine, Cc.empty(), 1_600L);
        assertNotNull(wipe);
        assertTrue(engine.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));

        engine.zk(
            java.util.Collections.singletonMap(WHIP, 1L), 20, "Loot from Dragon", "Dragon");
        engine.yz();
        Ac loot = settle(engine, gear(1L, 0L, 0L, 0L), 4_000L);

        assertNotNull(loot);
        assertEquals(Ai.LOOT, loot.getType());
        assertTrue("RuneLite's matched loot source stays counted", loot.isCounted());
        assertEquals(2_000_000L, loot.getNet());
        assertEquals("Loot from Dragon", loot.getNote());
        assertTrue("the unrelated drop does not finish reclaim", EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void sameIdLootAndReclaimedGearPartitionByMatchedQuantity()
    {
        Am engine = started(gear(1L, 0L, 0L, 0L));
        engine.zi(20, null, gear(1L, 0L, 0L, 0L).quantities);
        engine.yz();
        assertNotNull(settle(engine, Cc.empty(), 1_600L));
        assertTrue(engine.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));

        engine.zk(
            java.util.Collections.singletonMap(WHIP, 1L), 20, "Loot from Dragon", "Dragon");
        engine.yz();
        Ac loot = settle(engine,
            new Cc(java.util.Collections.singletonMap(WHIP, 2L)), 4_000L);

        assertNotNull(loot);
        assertEquals("only the source-backed quantity is a counted drop", Ai.LOOT, loot.getType());
        assertTrue(loot.isCounted());
        assertEquals(2_000_000L, loot.getNet());
        assertEquals(1L, loot.getFlows().get(0).quantityDelta);
        assertFalse("returning the final wiped item closes the reclaim", EngineProbe.isAwaitingDeathReclaim(engine));

        Ac recovered = null;
        for (Ac row : engine.getActiveSession().getTransactions())
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
        Am engine = started(gear(0L, 0L, 150_000L, 0L));
        engine.zi(20, null, gear(0L, 0L, 150_000L, 0L).quantities);
        assertTrue(engine.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));
        engine.markContext(Aj.TRANSFER, 20, "Bank deposit");
        engine.yz();

        Ac deposit = settle(engine, gear(0L, 0L, 149_000L, 0L), 1_600L);

        assertNotNull(deposit);
        assertEquals(Ai.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertFalse("bank movement is not labelled as a reclaim fee",
            "Death reclaim".equals(deposit.getActivityName()));
        assertEquals(0L, engine.getMetrics(4_000L).net);
    }

    @Test
    public void laterExplicitMarketContextSupersedesDeathWipeMarker()
    {
        Am engine = started(gear(1L, 0L, 0L, 0L));
        engine.zi(20, null,
            java.util.Collections.singletonMap(WHIP, 1L));
        engine.markContext(Aj.MARKET, 20, "Market sale");
        engine.yz();

        Ac sale = settle(engine, Cc.empty(), 1_600L);

        assertNotNull(sale);
        assertEquals("the explicit market evidence owns this loss", Aj.MARKET,
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
        Am engine = started(gear(1L, 1L, 150_000L, 0L));
        engine.zi(20, null, gear(1L, 1L, 150_000L, 0L).quantities);
        engine.yz();
        Ac wipe = settle(engine, gear(0L, 0L, 150_000L, 0L), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        assertTrue(engine.abe(BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));
        engine.yz();
        Ac loot = settle(engine, gear(1L, 0L, 150_000L, 1L), 4_000L);

        assertNotNull(loot);
        assertEquals(Ai.GAIN, loot.getType());
        assertTrue(loot.isCounted());
        assertEquals(800L, loot.getNet());
        assertTrue("an unmatched gain must not close a partially returned reclaim", EngineProbe.isAwaitingDeathReclaim(engine));

        Ac recovered = null;
        for (Ac row : engine.getActiveSession().getTransactions())
        {
            if ("Death reclaim: items recovered".equals(row.getNote()))
            {
                recovered = row;
            }
        }
        assertNotNull(recovered);
        assertEquals(1, recovered.getFlows().size());
        assertEquals(WHIP, recovered.getFlows().get(0).itemId);

        engine.yz();
        Ac fee = settle(engine, gear(1L, 1L, 50_000L, 1L), 6_000L);
        assertNotNull(fee);
        assertEquals(Ai.CONSUMPTION, fee.getType());
        assertEquals(-100_000L, fee.getNet());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void unclassifiedLocalDeathClearsEarlierReclaimEvidence()
    {
        Am engine = started(gear(1L, 0L, 150_000L, 0L));
        engine.zi(20, null, gear(1L, 0L, 150_000L, 0L).quantities);
        engine.yz();
        Ac wipe = settle(engine, gear(0L, 0L, 150_000L, 0L), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        engine.zj(null);

        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
        assertFalse("unclassified death must not arm retrieval accounting", engine.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));
        engine.yz();
        Ac returned = settle(engine, gear(1L, 0L, 150_000L, 0L), 4_000L);

        assertNotNull(returned);
        assertEquals(Ai.GAIN, returned.getType());
        assertTrue(returned.isCounted());
    }

    @Test
    public void partialDeathItemReturnKeepsRemainingItemsEligibleForReclaim()
    {
        Am engine = started(gear(1L, 1L, 150_000L, 0L));
        engine.zi(20, null, gear(1L, 1L, 150_000L, 0L).quantities);
        engine.yz();
        Ac wipe = settle(engine, gear(0L, 0L, 150_000L, 0L), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        assertTrue(engine.abe(BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));
        engine.yz();
        Ac firstReturn = settle(engine, gear(1L, 0L, 150_000L, 0L), 4_000L);
        assertNotNull(firstReturn);
        assertEquals("Death reclaim: items recovered", firstReturn.getNote());
        assertTrue("returning only part of the death wipe must leave the reclaim active",
            EngineProbe.isAwaitingDeathReclaim(engine));

        engine.yz();
        Ac finalReturn = settle(engine, gear(1L, 1L, 150_000L, 0L), 6_000L);
        assertNotNull(finalReturn);
        assertEquals("Death reclaim: items recovered", finalReturn.getNote());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void reclaimStateIsClearedWhenAnotherIdentityIsRestored()
    {
        Am engine = started(gear(1L, 0L, 0L, 0L));
        engine.zi(20, null, gear(1L, 0L, 0L, 0L).quantities);
        engine.yz();
        Ac wipe = settle(engine, Cc.empty(), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        engine.restore(new SavedState());
        engine.rm(4_000L);
        engine.setBaseline(Cc.empty());

        assertFalse("account restore cannot retain another owner's death whitelist",
            EngineProbe.isAwaitingDeathReclaim(engine));
        assertFalse(engine.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));
        engine.yz();
        Ac newOwnerGain = settle(engine, gear(1L, 0L, 0L, 0L), 5_000L);
        assertNotNull(newOwnerGain);
        assertEquals(Ai.GAIN, newOwnerGain.getType());
        assertTrue(newOwnerGain.isCounted());
        assertEquals(2_000_000L, newOwnerGain.getNet());
    }

    @Test
    public void reclaimStateSurvivesPauseAndResumeAfterTheDeathWipeSettles()
    {
        Am engine = started(gear(1L, 0L, 0L, 0L));
        engine.zi(20, null, gear(1L, 0L, 0L, 0L).quantities);
        engine.yz();
        Ac wipe = settle(engine, Cc.empty(), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        engine.acu(3_000L);
        assertNull("paused inventory churn is ignored", engine.adj(gear(0L, 0L, 0L, 1L), 3_200L));
        engine.resume(4_000L, Ed.LIFECYCLE);
        engine.setBaseline(Cc.empty());
        assertTrue("pause must not discard same-owner death ownership evidence",
            EngineProbe.isAwaitingDeathReclaim(engine));
        assertTrue(engine.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));

        engine.yz();
        Ac returned = settle(engine, gear(1L, 0L, 0L, 0L), 5_000L);

        assertNotNull(returned);
        assertEquals(Ai.TRANSFER, returned.getType());
        assertFalse(returned.isCounted());
        assertEquals(0L, engine.getMetrics(8_000L).net);
    }

    @Test
    public void persistedDeathEvidenceKeepsReclaimNeutralAcrossRestart()
    {
        Am first = started(gear(1L, 0L, 0L, 0L));
        first.zi(20, null, gear(1L, 0L, 0L, 0L).quantities);
        first.yz();
        assertNotNull(settle(first, Cc.empty(), 1_600L));
        assertTrue(EngineProbe.isAwaitingDeathReclaim(first));

        SavedState direct = first.qm();
        assertNotNull("pending record on the live snapshot", direct.pendingDeathReclaim);
        SavedState persisted = roundTrip(direct);
        assertNotNull("the pending death record must persist", persisted.pendingDeathReclaim);
        assertEquals(1, persisted.pendingDeathReclaim.getOutstandingItems().size());
        assertEquals(WHIP, persisted.pendingDeathReclaim.getOutstandingItems().get(0).itemId);

        Am restarted = new Am(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(persisted, 10_000L);
        restarted.resume(11_000L, Ed.IDLE, Ed.RECOVERY);
        restarted.setBaseline(Cc.empty());
        assertTrue("a restart restores the pending reclaim", EngineProbe.isAwaitingDeathReclaim(restarted));
        assertTrue(restarted.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));

        restarted.yz();
        Ac returned = settle(restarted, gear(1L, 0L, 0L, 0L), 12_000L);

        assertNotNull(returned);
        assertEquals(Ai.TRANSFER, returned.getType());
        assertFalse(returned.isCounted());
        assertEquals("reclaimed gear must not become revenue", 0L, restarted.getMetrics(20_000L).revenue);
        assertEquals(0L, restarted.getMetrics(20_000L).net);
        assertFalse("a full reclaim clears the pending state", EngineProbe.isAwaitingDeathReclaim(restarted));
        assertNull(roundTrip(restarted.qm()).pendingDeathReclaim);
    }

    @Test
    public void persistedDeathEvidenceBeforeWipeSurvivesRestart()
    {
        Cc carried = gear(1L, 0L, 0L, 0L);
        Am first = started(carried);
        first.zi(20, null, carried.quantities);

        SavedState persisted = roundTrip(first.qm());
        assertNotNull("death evidence is persisted before the inventory wipe", persisted.pendingDeathReclaim);

        Am restarted = new Am(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(persisted, 10_000L);
        restarted.resume(11_000L, Ed.IDLE, Ed.RECOVERY);
        restarted.setBaseline(carried);
        restarted.yz();
        Ac wipe = settle(restarted, Cc.empty(), 12_000L);

        assertNotNull(wipe);
        assertEquals(Ai.TRANSFER, wipe.getType());
        assertFalse("the post-restart death wipe remains ownership-neutral", wipe.isCounted());
        assertTrue(EngineProbe.isAwaitingDeathReclaim(restarted));
    }

    @Test
    public void partialReclaimRemainderSurvivesRestart()
    {
        Am first = started(gear(1L, 1L, 0L, 0L));
        first.zi(20, null, gear(1L, 1L, 0L, 0L).quantities);
        first.yz();
        assertNotNull(settle(first, Cc.empty(), 1_600L));
        assertTrue(first.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));
        first.yz();
        Ac partial = settle(first, gear(1L, 0L, 0L, 0L), 3_000L);
        assertNotNull(partial);
        assertEquals(Ai.TRANSFER, partial.getType());
        assertEquals("one item remains outstanding", 1L, first.tt().outstandingItemCount);

        SavedState persisted = roundTrip(first.qm());
        assertNotNull(persisted.pendingDeathReclaim);
        assertEquals(1, persisted.pendingDeathReclaim.getOutstandingItems().size());

        Am restarted = new Am(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(persisted, 10_000L);
        restarted.resume(11_000L, Ed.IDLE, Ed.RECOVERY);
        restarted.setBaseline(gear(1L, 0L, 0L, 0L));
        assertEquals(1L, restarted.tt().outstandingItemCount);
        assertTrue(restarted.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));
        restarted.yz();
        Ac remainder = settle(restarted, gear(1L, 1L, 0L, 0L), 12_000L);

        assertNotNull(remainder);
        assertEquals(Ai.TRANSFER, remainder.getType());
        assertEquals(0L, restarted.getMetrics(20_000L).net);
        assertFalse(EngineProbe.isAwaitingDeathReclaim(restarted));
        assertNull(roundTrip(restarted.qm()).pendingDeathReclaim);
    }

    @Test
    public void closedReclaimCannotReclassifyLaterGainsAfterRestart()
    {
        Am first = started(gear(1L, 0L, 0L, 0L));
        first.zi(20, null, gear(1L, 0L, 0L, 0L).quantities);
        first.yz();
        assertNotNull(settle(first, Cc.empty(), 1_600L));
        assertTrue(first.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));
        first.yz();
        assertNotNull(settle(first, gear(1L, 0L, 0L, 0L), 3_000L));
        assertNull(roundTrip(first.qm()).pendingDeathReclaim);

        Am restarted = new Am(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(roundTrip(first.qm()), 10_000L);
        restarted.resume(11_000L, Ed.IDLE, Ed.RECOVERY);
        restarted.setBaseline(Cc.empty());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(restarted));
        assertFalse("closed reclaim evidence cannot arm again", restarted.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Torfinn"), 40));

        restarted.yz();
        Ac later = settle(restarted, gear(1L, 0L, 0L, 0L), 12_000L);
        assertNotNull(later);
        assertEquals(Ai.GAIN, later.getType());
        assertTrue(later.isCounted());
        assertEquals(2_000_000L, restarted.getMetrics(20_000L).revenue);
    }

    @Test
    public void ownerSwitchAndDestructiveResetClearPersistedDeathEvidence()
    {
        Am engine = started(gear(1L, 0L, 0L, 0L));
        engine.zi(20, null, gear(1L, 0L, 0L, 0L).quantities);
        engine.yz();
        assertNotNull(settle(engine, Cc.empty(), 1_600L));
        assertNotNull(engine.qm().pendingDeathReclaim);

        // Another owner's state carries no pending record and must not inherit this one.
        engine.agl("profile-b", new SavedState(), 3_000L);
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
        assertNull(engine.qm().pendingDeathReclaim);

        Am reset = started(gear(1L, 0L, 0L, 0L));
        reset.zi(20, null, gear(1L, 0L, 0L, 0L).quantities);
        reset.yz();
        assertNotNull(settle(reset, Cc.empty(), 1_600L));
        reset.agr(3_000L);
        assertFalse(EngineProbe.isAwaitingDeathReclaim(reset));
        assertNull(reset.qm().pendingDeathReclaim);
    }

    @Test
    public void observedReclaimFeeAfterRestartBooksOnce()
    {
        Am first = started(gear(1L, 0L, 150_000L, 0L));
        first.zi(20, null, gear(1L, 0L, 150_000L, 0L).quantities);
        first.yz();
        assertNotNull(settle(first, Cc.empty(), 1_600L));

        Am restarted = new Am(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(roundTrip(first.qm()), 10_000L);
        restarted.resume(11_000L, Ed.IDLE, Ed.RECOVERY);
        restarted.setBaseline(gear(0L, 0L, 150_000L, 0L));
        assertTrue(restarted.abe(
            BossRetrievalCatalogue.axr("Talk-to", "Priestess Zul-Gwenwynig"), 40));
        restarted.yz();
        Ac fee = settle(restarted, gear(1L, 0L, 50_000L, 0L), 12_000L);

        assertNotNull(fee);
        assertEquals(Ai.CONSUMPTION, fee.getType());
        assertTrue(fee.isCounted());
        assertEquals(-100_000L, fee.getNet());
        assertEquals(100_000L, restarted.getMetrics(20_000L).costs);
        assertEquals(0L, restarted.getMetrics(20_000L).revenue);

        // Replaying the same settled state cannot book a second fee.
        restarted.yz();
        assertNull(settle(restarted, gear(1L, 0L, 50_000L, 0L), 20_000L));
        assertEquals(100_000L, restarted.getMetrics(30_000L).costs);
    }

    private static SavedState roundTrip(SavedState state)
    {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        return gson.fromJson(gson.toJson(state), SavedState.class);
    }

    private Am started(Cc baseline)
    {
        return started(baseline, CONFIG);
    }

    private Am started(Cc baseline, GpManagerConfig config)
    {
        Am engine = new Am(valuator, new TransactionClassifier(), config);
        engine.rm(1_000L);
        engine.setBaseline(baseline);
        return engine;
    }

    private static Cc gear(long whip, long torture, long coins, long sharks)
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
        return new Cc(values);
    }

    private static Ac settle(Am engine, Cc snapshot, long firstTick)
    {
        assertNull(engine.adj(snapshot, firstTick));
        assertNull(engine.adj(snapshot, firstTick + 600L));
        return engine.adj(snapshot, firstTick + 1_200L);
    }

    private static Ac sw(Am engine, String note)
    {
        for (Ac transaction : engine.getActiveSession().getTransactions())
        {
            if (note.equals(transaction.getNote()))
            {
                return transaction;
            }
        }
        return null;
    }
}
