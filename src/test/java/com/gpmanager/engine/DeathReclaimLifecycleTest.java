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
        BossRetrievalCatalogue.Service zulrah = BossRetrievalCatalogue.serviceFor("Talk-to", "Priestess Zul-Gwenwynig");
        assertNotNull(zulrah);
        assertEquals(100_000L, zulrah.fee);
        assertFalse(zulrah.ambiguous);
        assertEquals(60_000L, BossRetrievalCatalogue.serviceFor("Claim", "Shura").fee);
        assertEquals(50_000L, BossRetrievalCatalogue.serviceFor("Search", "Magical chest").fee);
        assertEquals(25_000L, BossRetrievalCatalogue.serviceFor("Talk-to", "Arno").fee);
        BossRetrievalCatalogue.Service chest = BossRetrievalCatalogue.serviceFor("Open", "Chest");
        assertNotNull(chest);
        assertTrue(chest.ambiguous);
        assertEquals(BossRetrievalCatalogue.VARIABLE_FEE, chest.fee);
        assertTrue(BossRetrievalCatalogue.serviceFor("Loot", "Gravestone").ambiguous);
        // Non-retrieval verbs and unrelated NPCs never match.
        assertNull(BossRetrievalCatalogue.serviceFor("Attack", "Priestess Zul-Gwenwynig"));
        assertNull(BossRetrievalCatalogue.serviceFor("Talk-to", "Banker"));
        assertNull(BossRetrievalCatalogue.serviceFor("Use", "Chest"));
    }

    @Test
    public void whyLineCarriesPublishedFeeAndFlagsAMismatch()
    {
        BossRetrievalCatalogue.Service zulrah = BossRetrievalCatalogue.serviceFor("Talk-to", "Priestess Zul-Gwenwynig");
        assertEquals(
            "Item retrieval fee — Zulrah (Priestess Zul-Gwenwynig), published 100,000. Free below 50 kills and for Ultimate Ironmen",
            zulrah.why(100_000L));
        assertTrue(zulrah.why(90_000L).contains("observed 90,000"));
        assertTrue(BossRetrievalCatalogue.serviceFor("Open", "Chest").why(250_000L).contains("fee varies"));
    }

    @Test
    public void lifecycleAcceptsAmbiguousTargetsOnlyWhileAwaitingReclaim()
    {
        DeathReclaimLifecycle lifecycle = new DeathReclaimLifecycle();
        BossRetrievalCatalogue.Service chest = BossRetrievalCatalogue.serviceFor("Open", "Chest");
        BossRetrievalCatalogue.Service torfinn = BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn");
        assertFalse("random chest without a death is not a reclaim", lifecycle.noteReclaimIntent(chest, 10));
        assertFalse("a generic Talk-to at a service NPC is not enough", lifecycle.noteReclaimIntent(torfinn, 10));
        lifecycle.onPvmDeath(java.util.Collections.singletonMap(WHIP, 1L));
        lifecycle.removed(java.util.Collections.singletonList(
            new Flow(WHIP, "Abyssal whip", -1L, 15_000_000, -15_000_000L)));
        assertTrue(lifecycle.isAwaitingReclaim());
        assertTrue("a named service is accepted after a local PvM death", lifecycle.noteReclaimIntent(torfinn, 10));
        assertTrue(lifecycle.noteReclaimIntent(chest, 10));
        BossRetrievalCatalogue.Service grave = BossRetrievalCatalogue.serviceFor("Check", "Grave");
        assertNotNull(grave);
        assertTrue("a Grave Check re-arms an awaiting reclaim", lifecycle.noteReclaimIntent(grave, 1));
        assertTrue(lifecycle.armed());
        for (int i = 0; i < DeathReclaimLifecycle.MIN_RECLAIM_ARM_TICKS - 1; i++)
        {
            lifecycle.tick(false);
        }
        assertTrue("reclaim stays armed through its two-minute minimum", lifecycle.armed());
        lifecycle.tick(false);
        assertFalse("window expires", lifecycle.armed());
        assertTrue("death still awaits", lifecycle.isAwaitingReclaim());
        lifecycle.reset();
        assertFalse(lifecycle.isAwaitingReclaim());
    }

    @Test
    public void missingDeathSnapshotCannotAuthorizeNeutralizingLosses()
    {
        DeathReclaimLifecycle lifecycle = new DeathReclaimLifecycle();
        lifecycle.onPvmDeath((Map<Integer, Long>) null);

        assertFalse("no canonical snapshot means no reclaim whitelist",
            lifecycle.wipePending());
        assertFalse(lifecycle.isAwaitingReclaim());
        assertTrue(lifecycle.removed(java.util.Collections.singletonList(
            new Flow(WHIP, "Abyssal whip", -1L, 2_000_000, -2_000_000L))).isEmpty());
    }

    @Test
    public void missingEngineDeathSnapshotLeavesMeasuredLossCounted()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markPvmDeath(20, null, Map.of(), engine.events.owner);
        engine.markDirty();

        Transaction loss = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(loss);
        assertEquals(TransactionType.CONSUMPTION, loss.type());
        assertTrue("without the canonical death snapshot the loss remains counted", loss.counts());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void delayedDeathWipeUsesCapturedStacksAfterDeathContextExpiresAndDirectLootIsNeutral()
    {
        ContainerSnapshot carried = gear(1L, 1L, 0L, 0L);
        Engine engine = started(carried);
        engine.markPvmDeath(1, carried.items, Map.of(), engine.events.owner);

        // Let the short-lived transfer context expire before the post-respawn inventory
        // removal settles. The separate captured-stack evidence remains live.
        for (int i = 0; i < 5; i++)
        {
            assertNull(engine.processIfDirty(carried, 1_100L + i * 600L, null));
        }
        engine.markDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 5_000L);

        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.type());
        assertFalse(wipe.counts());
        assertEquals("Death: items held by gravestone / retrieval service", wipe.note());
        assertEquals(2L, engine.deathStatus().items);
        assertTrue(engine.deathStatus().pending);
        assertTrue(engine.deathStatus().age >= 5L);

        // A direct gravestone Loot needs no still-live reclaim menu window.
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markDirty();
        Transaction returned = settle(engine, carried, 8_000L);
        assertNotNull(returned);
        assertEquals(TransactionType.TRANSFER, returned.type());
        assertFalse(returned.counts());
        assertEquals("Death reclaim: items recovered", returned.note());
        assertFalse(engine.deathStatus().pending);
        assertEquals(0L, engine.metrics(12_000L).net);
    }

    @Test
    public void deathWipeOnlyTransfersItemsActuallyHeldAtDeath()
    {
        ContainerSnapshot baseline = gear(1L, 1L, 0L, 0L);
        Engine engine = started(baseline);
        engine.markPvmDeath(1, gear(1L, 0L, 0L, 0L).items, Map.of(), engine.events.owner);
        engine.markDirty();
        Transaction residualLoss = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(residualLoss);
        assertEquals("unobserved ownership follows ordinary accounting", TransactionType.CONSUMPTION,
            residualLoss.type());
        assertTrue(residualLoss.counts());
        assertEquals(-15_000_000L, residualLoss.net());
        assertEquals(1L, engine.deathStatus().items);
        Transaction deathTransfer = sw(engine,
            "Death: items held by gravestone / retrieval service");
        assertNotNull(deathTransfer);
        assertFalse(deathTransfer.counts());
        assertEquals(WHIP, deathTransfer.flows().get(0).item);
    }

    @Test
    public void splitInventoryAndEquipmentLossesBothUseTheRemainingDeathWhitelist()
    {
        ContainerSnapshot carried = gear(1L, 1L, 0L, 0L);
        Engine engine = started(carried);
        engine.markPvmDeath(1, carried.items, Map.of(), engine.events.owner);

        engine.markDirty();
        Transaction firstWipe = settle(engine, gear(0L, 1L, 0L, 0L), 1_600L);
        assertNotNull(firstWipe);
        assertEquals(TransactionType.TRANSFER, firstWipe.type());
        assertTrue(engine.deathStatus().pending);
        assertEquals("only the still-held item remains outstanding", 1L,
            engine.deathStatus().items);

        engine.setBaseline(gear(0L, 1L, 0L, 0L));
        engine.markDirty();
        Transaction secondWipe = settle(engine, ContainerSnapshot.empty(), 3_000L);
        assertNotNull(secondWipe);
        assertEquals(TransactionType.TRANSFER, secondWipe.type());
        assertFalse(secondWipe.counts());
        assertEquals(2L, engine.deathStatus().items);
    }

    @Test
    public void delayedCoinOnlyDeathWipeIsNeutralEvenAfterDeathContextExpires()
    {
        ContainerSnapshot carried = gear(0L, 0L, 75_000L, 0L);
        Engine engine = started(carried);
        engine.markPvmDeath(1, carried.items, Map.of(), engine.events.owner);
        for (int i = 0; i < 5; i++)
        {
            assertNull(engine.processIfDirty(carried, 1_100L + i * 600L, null));
        }

        engine.markDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 5_000L);

        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.type());
        assertFalse(wipe.counts());
        assertEquals("Death: items held by gravestone / retrieval service", wipe.note());
        assertEquals(0L, engine.metrics(8_000L).net);
    }

    @Test
    public void armedReclaimDoesNotMislabelCoinLossAlongsideDelayedDeathWipeAsFee()
    {
        ContainerSnapshot carried = gear(1L, 0L, 200_000L, 0L);
        Engine engine = started(carried);
        engine.markPvmDeath(20, carried.items, Map.of(), engine.events.owner);
        assertTrue(engine.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        engine.markDirty();

        Transaction ambiguousWipe = settle(engine, gear(0L, 0L, 180_000L, 0L), 1_600L);

        assertNotNull(ambiguousWipe);
        assertTrue("ambiguous coin outflow stays an ordinary measured cost", ambiguousWipe.counts());
        assertEquals("the ordinary cost contains the observed coin loss", -20_000L,
            ambiguousWipe.net());
        assertFalse("a menu interaction must not turn an overlapping death wipe into a fee",
            "Death reclaim".equals(ambiguousWipe.activityName()));
        assertEquals(1, sw(engine,
            "Death: items held by gravestone / retrieval service").flows().size());

        engine.setBaseline(gear(0L, 0L, 180_000L, 0L));
        engine.markDirty();
        Transaction observedFee = settle(engine, gear(1L, 0L, 80_000L, 0L), 4_000L);

        assertNotNull(observedFee);
        assertEquals("a separate observed outflow with a matching returned item remains a fee",
            "Death reclaim", observedFee.activityName());
        assertEquals(-100_000L, observedFee.net());
    }

    @Test
    public void pendingDeathSnapshotCannotHideLaterUnclaimedGeMovement()
    {
        ContainerSnapshot carried = gear(1L, 0L, 0L, 0L);
        Engine engine = started(carried);
        engine.markPvmDeath(1, carried.items, Map.of(), engine.events.owner);
        for (int i = 0; i < 3; i++)
        {
            assertNull(engine.processIfDirty(carried, 1_100L + i * 600L, null));
        }
        engine.markContext(Context.MARKET, 100, "Grand Exchange sale");
        engine.markDirty();

        Transaction movement = settle(engine, gear(0L, 0L, 15_000_000L, 0L), 1_600L);

        assertNotNull(movement);
        assertEquals(Context.MARKET, movement.context());
        assertEquals(TransactionType.UNCERTAIN, movement.type());
        assertFalse("without custody, the GE movement waits for owner review", movement.counts());
        assertEquals(0L, engine.metrics(1_600L).net);
        assertFalse("the stronger market evidence closes the stale wipe whitelist",
            engine.deathStatus().pending);
    }

    @Test
    public void confirmedConsumptionOfSameItemOutranksPendingDeathSnapshot()
    {
        ContainerSnapshot carried = gear(1L, 0L, 0L, 0L);
        Engine engine = started(carried);
        engine.markPvmDeath(1, carried.items, Map.of(), engine.events.owner);
        for (int i = 0; i < 3; i++)
        {
            assertNull(engine.processIfDirty(carried, 1_100L + i * 600L, null));
        }
        engine.noteConsume(WHIP, 20, false, null, null);
        engine.markDirty();

        Transaction consumed = settle(engine, ContainerSnapshot.empty(), 3_000L);

        assertNotNull(consumed);
        assertEquals(TransactionType.CONSUMPTION, consumed.type());
        assertTrue(consumed.counts());
        assertFalse(engine.deathStatus().pending);
    }

    @Test
    public void mixedDeathWipeAndNamedConsumptionPartitionByItemId()
    {
        ContainerSnapshot carried = gear(1L, 0L, 0L, 1L);
        Engine engine = started(carried);
        engine.markPvmDeath(1, carried.items, Map.of(), engine.events.owner);
        engine.noteConsume(SHARK, 30, false, null, null);
        engine.markDirty();

        Transaction consumption = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(consumption);
        assertEquals(TransactionType.CONSUMPTION, consumption.type());
        assertTrue(consumption.counts());
        Transaction deathTransfer = sw(engine,
            "Death: items held by gravestone / retrieval service");
        assertNotNull("unrelated whip loss remains an ownership-neutral death transfer", deathTransfer);
        assertFalse(deathTransfer.counts());
        assertEquals(WHIP, deathTransfer.flows().get(0).item);
        assertEquals(1L, engine.deathStatus().items);
    }

    @Test
    public void finalGravestoneReturnSettlingOnExpiryTickWinsBeforeExpiryAudit()
    {
        ContainerSnapshot carried = gear(1L, 0L, 0L, 0L);
        Engine engine = started(carried);
        engine.markPvmDeath(1, carried.items, Map.of(), engine.events.owner);
        engine.markDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));
        engine.setBaseline(ContainerSnapshot.empty());

        while (engine.deathStatus().age
            < DeathReclaimLifecycle.AWAIT_TICKS - 1L)
        {
            assertNull(engine.processIfDirty(ContainerSnapshot.empty(), 4_000L, null));
        }
        // The final return can be observed before a delayed/missed dirty callback.
        Transaction returned = settle(engine, carried, 5_000L);

        assertNotNull(returned);
        assertEquals(TransactionType.TRANSFER, returned.type());
        assertEquals("Death reclaim: items recovered", returned.note());
        assertFalse(engine.deathStatus().pending);
        assertNull(sw(engine, "Death reclaim expired"));
    }

    @Test
    public void deathWipeWhitelistExpiresAtItsOneMinuteEvidenceCap()
    {
        DeathReclaimLifecycle lifecycle = new DeathReclaimLifecycle();
        lifecycle.onPvmDeath(java.util.Collections.singletonMap(WHIP, 1L));
        for (int i = 0; i < DeathReclaimLifecycle.DEATH_WIPE_WINDOW_TICKS - 1; i++)
        {
            lifecycle.tick(false);
        }
        assertTrue(lifecycle.wipePending());
        lifecycle.tick(false);
        assertFalse(lifecycle.wipePending());
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
        engine.markPvmDeath(1, carried.items, Map.of(), engine.events.owner);
        engine.markDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));
        assertEquals(2L, engine.deathStatus().items);

        stabilization[0] = DeathReclaimLifecycle.AWAIT_TICKS + 100;
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markDirty();
        ContainerSnapshot changing = new ContainerSnapshot(
            java.util.Collections.singletonMap(99_999, 1L));
        Transaction expired = null;
        Transaction unrelatedGain = null;
        int maxWait = DeathReclaimLifecycle.AWAIT_TICKS + stabilization[0] + 5;
        for (int i = 0; i < maxWait; i++)
        {
            Transaction tick = engine.processIfDirty(changing, 4_000L + i * 600L, null);
            if (tick != null && "Death reclaim expired".equals(tick.note()))
            {
                expired = tick;
                break;
            }
            if (tick != null && tick.net() > 0L)
            {
                unrelatedGain = tick;
            }
        }

        assertNotNull("timer ages while dirty, then expires after the outstanding snapshot settles", expired);
        assertNotNull("an unrelated settled change still books normally", unrelatedGain);
        assertTrue(unrelatedGain.counts());
        assertTrue(expired.flows().isEmpty());
        assertFalse(engine.deathStatus().pending);
        engine.processIfDirty(changing, 4_000L + maxWait * 600L, null);
    }

    @Test
    public void observedCoinFeeAndReturnedItemsSplitIntoSeparateRows()
    {
        ContainerSnapshot carried = gear(1L, 0L, 150_000L, 0L);
        Engine engine = started(carried);
        engine.markPvmDeath(1, carried.items, Map.of(), engine.events.owner);
        engine.markDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));
        // Observe the kept cash returning after the fixture's all-items wipe; do not bypass
        // outstanding ownership by replacing its baseline before the return can settle.
        Transaction keptCash = settle(engine, gear(0L, 0L, 150_000L, 0L), 2_500L);
        assertNotNull(keptCash);
        assertEquals(TransactionType.TRANSFER, keptCash.type());
        assertFalse(keptCash.counts());

        assertTrue(engine.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Loot", "Gravestone"), 1));
        assertTrue(engine.deathStatus().armed);
        engine.markDirty();
        Transaction fee = settle(engine, gear(1L, 0L, 50_000L, 0L), 4_000L);

        assertNotNull(fee);
        assertEquals(TransactionType.CONSUMPTION, fee.type());
        assertEquals(-100_000L, fee.net());
        assertTrue(fee.explanation().contains("Gravestone"));
        assertFalse(engine.deathStatus().pending);
        Transaction recovered = engine.session().transactions().stream()
            .filter(row -> "Death reclaim: items recovered".equals(row.note()))
            .filter(row -> row.flows().stream().anyMatch(flow -> flow.item == WHIP))
            .findFirst().orElse(null);
        assertNotNull(recovered);
        assertFalse(recovered.counts());
        assertEquals(WHIP, recovered.flows().get(0).item);
        assertEquals(-100_000L, engine.metrics(8_000L).net);
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
        engine.markPvmDeath(1, carried.items, Map.of(), engine.events.owner);
        engine.markDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.type());
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markDirty();
        Transaction returned = settle(engine, carried, 4_000L);

        assertNotNull("the measured return is partitioned before the minimum-value gate", returned);
        assertEquals(TransactionType.TRANSFER, returned.type());
        assertFalse(returned.counts());
        assertEquals("Death reclaim: items recovered", returned.note());
        assertFalse(engine.deathStatus().pending);
        assertEquals(0L, engine.metrics(8_000L).net);
    }

    @Test
    public void partialLootReportsOutstandingItemsThenExpiresWithoutGuessingLoss()
    {
        ContainerSnapshot carried = gear(1L, 1L, 0L, 0L);
        Engine engine = started(carried);
        engine.markPvmDeath(1, carried.items, Map.of(), engine.events.owner);
        engine.markDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markDirty();
        Transaction firstReturn = settle(engine, gear(1L, 0L, 0L, 0L), 4_000L);

        assertNotNull(firstReturn);
        assertEquals(TransactionType.TRANSFER, firstReturn.type());
        assertTrue(engine.deathStatus().pending);
        assertEquals(1L, engine.deathStatus().items);

        Transaction expired = null;
        ContainerSnapshot afterPartialReturn = gear(1L, 0L, 0L, 0L);
        for (int i = 0; i < DeathReclaimLifecycle.AWAIT_TICKS; i++)
        {
            Transaction tick = engine.processIfDirty(afterPartialReturn, 6_000L + i * 600L, null);
            if (tick != null)
            {
                expired = tick;
            }
        }

        assertNotNull("timer expiry is retained as an audit event", expired);
        assertEquals("Death reclaim expired", expired.note());
        assertEquals(TransactionType.TRANSFER, expired.type());
        assertFalse(expired.counts());
        assertTrue(expired.flows().isEmpty());
        assertTrue(expired.explanation().contains("Amulet of torture"));
        assertTrue(expired.explanation().contains("No item loss or fee was inferred"));
        assertFalse(engine.deathStatus().pending);
        assertEquals(0L, engine.metrics(1_000_000L).net);

        // Review 2026-10-09: the expired items wait at Death's Office, so collecting them there
        // later is no income.
        assertTrue(engine.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Talk-to", "Death"), 40));
        engine.markDirty();
        Transaction collected = settle(engine, gear(1L, 1L, 0L, 0L), 1_000_000L);
        assertNotNull(collected);
        assertEquals(TransactionType.TRANSFER, collected.type());
        assertFalse(collected.counts());
        assertEquals(0L, engine.metrics(2_000_000L).net);
    }

    @Test
    public void secondDeathMergesOutstandingItemsIntoNewGravestone()
    {
        ContainerSnapshot firstHeld = gear(1L, 0L, 0L, 0L);
        Engine engine = started(firstHeld);
        engine.markPvmDeath(1, firstHeld.items, Map.of(), engine.events.owner);
        engine.markDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));

        ContainerSnapshot secondHeld = gear(0L, 1L, 0L, 0L);
        engine.setBaseline(secondHeld);
        engine.markPvmDeath(1, secondHeld.items, Map.of(), engine.events.owner);
        engine.markDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 4_000L));
        assertTrue(engine.deathStatus().pending);
        assertEquals(2L, engine.deathStatus().items);

        engine.setBaseline(ContainerSnapshot.empty());
        engine.markDirty();
        Transaction recovered = settle(engine, gear(1L, 1L, 0L, 0L), 6_000L);
        assertNotNull(recovered);
        assertEquals(TransactionType.TRANSFER, recovered.type());
        assertEquals("Death reclaim: items recovered", recovered.note());
        assertFalse(engine.deathStatus().pending);
        assertEquals(0L, engine.metrics(10_000L).net);
    }

    @Test
    public void pvmDeathWipeIsATransferAndZulrahReclaimReturnsItemsAndBooksTheFee()
    {
        Engine engine = started(gear(1L, 1L, 150_000L, 0L));
        long before = engine.metrics(1_000L).net;

        // Death: everything leaves the inventory in one settle.
        engine.markPvmDeath(20, gear(1L, 1L, 150_000L, 0L).items, Map.of(), engine.events.owner);
        engine.markDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.type());
        assertFalse(wipe.counts());
        assertTrue(wipe.note(), wipe.note().startsWith("Death: items held"));
        assertTrue(wipe.explanation(), wipe.explanation().startsWith("Ownership-neutral transfer: Death"));
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        // Respawn with the coins for the fee (coins are kept on death in this fixture).
        Transaction keptCash = settle(engine, gear(0L, 0L, 150_000L, 0L), 4_000L);
        assertNotNull(keptCash);
        assertEquals(TransactionType.TRANSFER, keptCash.type());
        assertFalse(keptCash.counts());

        // Reclaim at Zul-Gwenwynig: 100k leaves, whip + torture return in the same settle.
        assertTrue(engine.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Priestess Zul-Gwenwynig"), 40));
        engine.markDirty();
        Transaction fee = settle(engine, gear(1L, 1L, 50_000L, 0L), 6_000L);
        assertNotNull(fee);
        assertEquals(TransactionType.CONSUMPTION, fee.type());
        assertTrue(fee.counts());
        assertEquals(-100_000L, fee.net());
        assertEquals("Death reclaim", fee.activityName());
        assertTrue(fee.explanation().startsWith("Item retrieval fee — Zulrah"));
        assertFalse("death evidence must not attach to later reclaim rows",
            fee.explanation().contains("Death evidence"));
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));

        // The returned gear is an uncounted transfer row, not revenue.
        List<Transaction> rows = engine.session().transactions();
        Transaction recovered = null;
        for (Transaction row : rows)
        {
            if ("Death reclaim: items recovered".equals(row.note()))
            {
                recovered = row;
            }
        }
        assertNotNull(recovered);
        assertEquals(TransactionType.TRANSFER, recovered.type());
        assertFalse(recovered.counts());
        assertFalse(recovered.explanation().contains("Death evidence"));
        assertEquals(2, recovered.flows().size());
        // Only the fee moved net: 17M of gear went out and came back without touching profit.
        assertEquals(before - 100_000L, engine.metrics(8_000L).net);
    }

    @Test
    public void reclaimWindowLeavesUnrelatedConsumptionToNormalClassification()
    {
        Engine engine = started(gear(0L, 0L, 0L, 2L));
        engine.markPvmDeath(20, gear(0L, 0L, 0L, 2L).items, Map.of(), engine.events.owner);
        engine.markDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertNotNull(wipe);
        engine.setBaseline(gear(0L, 0L, 0L, 2L));
        assertTrue(engine.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        engine.noteConsume(SHARK, 6, false, null, null);
        engine.markDirty();

        Transaction eat = settle(engine, gear(0L, 0L, 0L, 1L), 1_600L);

        assertNotNull(eat);
        assertEquals("a shark eaten in the window is still a consume", TransactionType.CONSUMPTION, eat.type());
        assertEquals(-800L, eat.net());
        assertFalse("Death reclaim".equals(eat.activityName()));
    }

    @Test
    public void unrelatedLootDoesNotConsumeReclaimWindowOrBecomeRecoveredDeathGear()
    {
        Engine engine = started(gear(1L, 0L, 150_000L, 0L));
        engine.markPvmDeath(20, gear(1L, 0L, 150_000L, 0L).items, Map.of(), engine.events.owner);
        engine.markDirty();
        Transaction wipe = settle(engine, gear(0L, 0L, 150_000L, 0L), 1_600L);
        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.type());
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        assertTrue(engine.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        engine.markDirty();
        Transaction loot = settle(engine, gear(0L, 0L, 150_000L, 1L), 4_000L);

        assertNotNull(loot);
        assertEquals("ordinary loot keeps its normal counted classification", TransactionType.GAIN, loot.type());
        assertTrue(loot.counts());
        assertEquals(800L, loot.net());
        assertTrue("the unrelated gain must not finish the reclaim", EngineProbe.isAwaitingDeathReclaim(engine));

        engine.markDirty();
        Transaction fee = settle(engine, gear(1L, 0L, 50_000L, 1L), 6_000L);
        assertNotNull(fee);
        assertEquals(TransactionType.CONSUMPTION, fee.type());
        assertEquals(-100_000L, fee.net());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));

        Transaction recovered = null;
        for (Transaction row : engine.session().transactions())
        {
            if ("Death reclaim: items recovered".equals(row.note()))
            {
                recovered = row;
            }
        }
        assertNotNull(recovered);
        assertEquals(1, recovered.flows().size());
        assertEquals(WHIP, recovered.flows().get(0).item);
        assertEquals(1L, recovered.flows().get(0).quantity);
    }

    @Test
    public void observedLootWithSameItemIdAsDeathGearStaysCounted()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markPvmDeath(20, gear(1L, 0L, 0L, 0L).items, Map.of(), engine.events.owner);
        engine.markDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertNotNull(wipe);
        assertTrue(engine.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));

        engine.markLoot(
            java.util.Collections.singletonMap(WHIP, 1L), 20, "Loot from Dragon", "Dragon");
        engine.markDirty();
        Transaction loot = settle(engine, gear(1L, 0L, 0L, 0L), 4_000L);

        assertNotNull(loot);
        assertEquals(TransactionType.LOOT, loot.type());
        assertTrue("RuneLite's matched loot source stays counted", loot.counts());
        assertEquals(2_000_000L, loot.net());
        assertEquals("Loot from Dragon", loot.note());
        assertTrue("the unrelated drop does not finish reclaim", EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void sameIdLootAndReclaimedGearPartitionByMatchedQuantity()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markPvmDeath(20, gear(1L, 0L, 0L, 0L).items, Map.of(), engine.events.owner);
        engine.markDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));
        assertTrue(engine.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));

        engine.markLoot(
            java.util.Collections.singletonMap(WHIP, 1L), 20, "Loot from Dragon", "Dragon");
        engine.markDirty();
        Transaction loot = settle(engine,
            new ContainerSnapshot(java.util.Collections.singletonMap(WHIP, 2L)), 4_000L);

        assertNotNull(loot);
        assertEquals("only the source-backed quantity is a counted drop", TransactionType.LOOT, loot.type());
        assertTrue(loot.counts());
        assertEquals(2_000_000L, loot.net());
        assertEquals(1L, loot.flows().get(0).quantity);
        assertFalse("returning the final wiped item closes the reclaim", EngineProbe.isAwaitingDeathReclaim(engine));

        Transaction recovered = null;
        for (Transaction row : engine.session().transactions())
        {
            if ("Death reclaim: items recovered".equals(row.note()))
            {
                recovered = row;
            }
        }
        assertNotNull(recovered);
        assertEquals(1L, recovered.flows().get(0).quantity);
        assertFalse(recovered.counts());
    }

    @Test
    public void hardBankTransferDuringReclaimDoesNotBecomeADeathFee()
    {
        Engine engine = started(gear(0L, 0L, 150_000L, 0L));
        engine.markPvmDeath(20, gear(0L, 0L, 150_000L, 0L).items, Map.of(), engine.events.owner);
        assertTrue(engine.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        engine.markContext(Context.TRANSFER, 20, "Bank deposit");
        engine.markDirty();

        Transaction deposit = settle(engine, gear(0L, 0L, 149_000L, 0L), 1_600L);

        assertNotNull(deposit);
        assertEquals(TransactionType.TRANSFER, deposit.type());
        assertFalse(deposit.counts());
        assertFalse("bank movement is not labelled as a reclaim fee",
            "Death reclaim".equals(deposit.activityName()));
        assertEquals(0L, engine.metrics(4_000L).net);
    }

    @Test
    public void laterExplicitMarketContextSupersedesDeathWipeMarker()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markPvmDeath(20,
            java.util.Collections.singletonMap(WHIP, 1L), Map.of(), engine.events.owner);
        engine.markContext(Context.MARKET, 20, "Market sale");
        engine.markDirty();

        Transaction sale = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(sale);
        assertEquals("the explicit market evidence owns this loss", Context.MARKET,
            sale.context());
        assertTrue("the sale remains counted", sale.counts());
        assertFalse("it must not be neutralized as a death transfer",
            "Death: items held by gravestone / retrieval service".equals(sale.note()));
        assertFalse("the canceled death marker has no reclaim whitelist",
            EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void mixedReclaimAndLootPartitionsReturnsAndKeepsWindowOpen()
    {
        Engine engine = started(gear(1L, 1L, 150_000L, 0L));
        engine.markPvmDeath(20, gear(1L, 1L, 150_000L, 0L).items, Map.of(), engine.events.owner);
        engine.markDirty();
        Transaction wipe = settle(engine, gear(0L, 0L, 150_000L, 0L), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        assertTrue(engine.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        engine.markDirty();
        Transaction loot = settle(engine, gear(1L, 0L, 150_000L, 1L), 4_000L);

        assertNotNull(loot);
        assertEquals(TransactionType.GAIN, loot.type());
        assertTrue(loot.counts());
        assertEquals(800L, loot.net());
        assertTrue("an unmatched gain must not close a partially returned reclaim", EngineProbe.isAwaitingDeathReclaim(engine));

        Transaction recovered = null;
        for (Transaction row : engine.session().transactions())
        {
            if ("Death reclaim: items recovered".equals(row.note()))
            {
                recovered = row;
            }
        }
        assertNotNull(recovered);
        assertEquals(1, recovered.flows().size());
        assertEquals(WHIP, recovered.flows().get(0).item);

        engine.markDirty();
        Transaction fee = settle(engine, gear(1L, 1L, 50_000L, 1L), 6_000L);
        assertNotNull(fee);
        assertEquals(TransactionType.CONSUMPTION, fee.type());
        assertEquals(-100_000L, fee.net());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void unclassifiedLocalDeathClearsEarlierReclaimEvidence()
    {
        Engine engine = started(gear(1L, 0L, 150_000L, 0L));
        engine.markPvmDeath(20, gear(1L, 0L, 150_000L, 0L).items, Map.of(), engine.events.owner);
        engine.markDirty();
        Transaction wipe = settle(engine, gear(0L, 0L, 150_000L, 0L), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        engine.markUnknownDeath();

        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
        assertFalse("unclassified death must not arm retrieval accounting", engine.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        engine.markDirty();
        Transaction returned = settle(engine, gear(1L, 0L, 150_000L, 0L), 4_000L);

        assertNotNull(returned);
        assertEquals(TransactionType.GAIN, returned.type());
        assertTrue(returned.counts());
    }

    @Test
    public void partialDeathItemReturnKeepsRemainingItemsEligibleForReclaim()
    {
        Engine engine = started(gear(1L, 1L, 150_000L, 0L));
        engine.markPvmDeath(20, gear(1L, 1L, 150_000L, 0L).items, Map.of(), engine.events.owner);
        engine.markDirty();
        Transaction wipe = settle(engine, gear(0L, 0L, 150_000L, 0L), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        assertTrue(engine.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        engine.markDirty();
        Transaction firstReturn = settle(engine, gear(1L, 0L, 150_000L, 0L), 4_000L);
        assertNotNull(firstReturn);
        assertEquals("Death reclaim: items recovered", firstReturn.note());
        assertTrue("returning only part of the death wipe must leave the reclaim active",
            EngineProbe.isAwaitingDeathReclaim(engine));

        engine.markDirty();
        Transaction finalReturn = settle(engine, gear(1L, 1L, 150_000L, 0L), 6_000L);
        assertNotNull(finalReturn);
        assertEquals("Death reclaim: items recovered", finalReturn.note());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
    }

    @Test
    public void reclaimStateIsClearedWhenAnotherIdentityIsRestored()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markPvmDeath(20, gear(1L, 0L, 0L, 0L).items, Map.of(), engine.events.owner);
        engine.markDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        engine.restore(new SavedState(), SafeMath.now());
        engine.ensureSession(4_000L);
        engine.setBaseline(ContainerSnapshot.empty());

        assertFalse("account restore cannot retain another owner's death whitelist",
            EngineProbe.isAwaitingDeathReclaim(engine));
        assertFalse(engine.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        engine.markDirty();
        Transaction newOwnerGain = settle(engine, gear(1L, 0L, 0L, 0L), 5_000L);
        assertNotNull(newOwnerGain);
        assertEquals(TransactionType.GAIN, newOwnerGain.type());
        assertTrue(newOwnerGain.counts());
        assertEquals(2_000_000L, newOwnerGain.net());
    }

    @Test
    public void reclaimStateSurvivesPauseAndResumeAfterTheDeathWipeSettles()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markPvmDeath(20, gear(1L, 0L, 0L, 0L).items, Map.of(), engine.events.owner);
        engine.markDirty();
        Transaction wipe = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertNotNull(wipe);
        assertTrue(EngineProbe.isAwaitingDeathReclaim(engine));

        engine.pauseForLifecycle(3_000L);
        assertNull("paused inventory churn is ignored", engine.processIfDirty(gear(0L, 0L, 0L, 1L), 3_200L, null));
        engine.resume(4_000L, PauseReason.LIFECYCLE);
        engine.setBaseline(ContainerSnapshot.empty());
        assertTrue("pause must not discard same-owner death ownership evidence",
            EngineProbe.isAwaitingDeathReclaim(engine));
        assertTrue(engine.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));

        engine.markDirty();
        Transaction returned = settle(engine, gear(1L, 0L, 0L, 0L), 5_000L);

        assertNotNull(returned);
        assertEquals(TransactionType.TRANSFER, returned.type());
        assertFalse(returned.counts());
        assertEquals(0L, engine.metrics(8_000L).net);
    }

    @Test
    public void persistedDeathEvidenceKeepsReclaimNeutralAcrossRestart()
    {
        Engine first = started(gear(1L, 0L, 0L, 0L));
        first.markPvmDeath(20, gear(1L, 0L, 0L, 0L).items, Map.of(), first.events.owner);
        first.markDirty();
        assertNotNull(settle(first, ContainerSnapshot.empty(), 1_600L));
        assertTrue(EngineProbe.isAwaitingDeathReclaim(first));

        SavedState direct = first.savedState();
        assertNotNull("pending record on the live snapshot", direct.pendingDeathReclaim);
        SavedState persisted = roundTrip(direct);
        assertNotNull("the pending death record must persist", persisted.pendingDeathReclaim);
        assertEquals(1, persisted.pendingDeathReclaim.outstandingItems().size());
        assertEquals(WHIP, persisted.pendingDeathReclaim.outstandingItems().get(0).itemId);

        Engine restarted = new Engine(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(persisted, 10_000L);
        restarted.resume(11_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        restarted.setBaseline(ContainerSnapshot.empty());
        assertTrue("a restart restores the pending reclaim", EngineProbe.isAwaitingDeathReclaim(restarted));
        assertTrue(restarted.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));

        restarted.markDirty();
        Transaction returned = settle(restarted, gear(1L, 0L, 0L, 0L), 12_000L);

        assertNotNull(returned);
        assertEquals(TransactionType.TRANSFER, returned.type());
        assertFalse(returned.counts());
        assertEquals("reclaimed gear must not become revenue", 0L, restarted.metrics(20_000L).income);
        assertEquals(0L, restarted.metrics(20_000L).net);
        assertFalse("a full reclaim clears the pending state", EngineProbe.isAwaitingDeathReclaim(restarted));
        assertNull(roundTrip(restarted.savedState()).pendingDeathReclaim);
    }

    @Test
    public void persistedDeathEvidenceBeforeWipeSurvivesRestart()
    {
        ContainerSnapshot carried = gear(1L, 0L, 0L, 0L);
        Engine first = started(carried);
        first.markPvmDeath(20, carried.items, Map.of(), first.events.owner);

        SavedState persisted = roundTrip(first.savedState());
        assertNotNull("death evidence is persisted before the inventory wipe", persisted.pendingDeathReclaim);

        Engine restarted = new Engine(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(persisted, 10_000L);
        restarted.resume(11_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        restarted.setBaseline(carried);
        restarted.markDirty();
        Transaction wipe = settle(restarted, ContainerSnapshot.empty(), 12_000L);

        assertNotNull(wipe);
        assertEquals(TransactionType.TRANSFER, wipe.type());
        assertFalse("the post-restart death wipe remains ownership-neutral", wipe.counts());
        assertTrue(EngineProbe.isAwaitingDeathReclaim(restarted));
    }

    @Test
    public void partialReclaimRemainderSurvivesRestart()
    {
        Engine first = started(gear(1L, 1L, 0L, 0L));
        first.markPvmDeath(20, gear(1L, 1L, 0L, 0L).items, Map.of(), first.events.owner);
        first.markDirty();
        assertNotNull(settle(first, ContainerSnapshot.empty(), 1_600L));
        assertTrue(first.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        first.markDirty();
        Transaction partial = settle(first, gear(1L, 0L, 0L, 0L), 3_000L);
        assertNotNull(partial);
        assertEquals(TransactionType.TRANSFER, partial.type());
        assertEquals("one item remains outstanding", 1L, first.deathStatus().items);

        SavedState persisted = roundTrip(first.savedState());
        assertNotNull(persisted.pendingDeathReclaim);
        assertEquals(1, persisted.pendingDeathReclaim.outstandingItems().size());

        Engine restarted = new Engine(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(persisted, 10_000L);
        restarted.resume(11_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        restarted.setBaseline(gear(1L, 0L, 0L, 0L));
        assertEquals(1L, restarted.deathStatus().items);
        assertTrue(restarted.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        restarted.markDirty();
        Transaction remainder = settle(restarted, gear(1L, 1L, 0L, 0L), 12_000L);

        assertNotNull(remainder);
        assertEquals(TransactionType.TRANSFER, remainder.type());
        assertEquals(0L, restarted.metrics(20_000L).net);
        assertFalse(EngineProbe.isAwaitingDeathReclaim(restarted));
        assertNull(roundTrip(restarted.savedState()).pendingDeathReclaim);
    }

    @Test
    public void closedReclaimCannotReclassifyLaterGainsAfterRestart()
    {
        Engine first = started(gear(1L, 0L, 0L, 0L));
        first.markPvmDeath(20, gear(1L, 0L, 0L, 0L).items, Map.of(), first.events.owner);
        first.markDirty();
        assertNotNull(settle(first, ContainerSnapshot.empty(), 1_600L));
        assertTrue(first.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));
        first.markDirty();
        assertNotNull(settle(first, gear(1L, 0L, 0L, 0L), 3_000L));
        assertNull(roundTrip(first.savedState()).pendingDeathReclaim);

        Engine restarted = new Engine(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(roundTrip(first.savedState()), 10_000L);
        restarted.resume(11_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        restarted.setBaseline(ContainerSnapshot.empty());
        assertFalse(EngineProbe.isAwaitingDeathReclaim(restarted));
        assertFalse("closed reclaim evidence cannot arm again", restarted.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Torfinn"), 40));

        restarted.markDirty();
        Transaction later = settle(restarted, gear(1L, 0L, 0L, 0L), 12_000L);
        assertNotNull(later);
        assertEquals(TransactionType.GAIN, later.type());
        assertTrue(later.counts());
        assertEquals(2_000_000L, restarted.metrics(20_000L).income);
    }

    @Test
    public void ownerSwitchAndDestructiveResetClearPersistedDeathEvidence()
    {
        Engine engine = started(gear(1L, 0L, 0L, 0L));
        engine.markPvmDeath(20, gear(1L, 0L, 0L, 0L).items, Map.of(), engine.events.owner);
        engine.markDirty();
        assertNotNull(settle(engine, ContainerSnapshot.empty(), 1_600L));
        assertNotNull(engine.savedState().pendingDeathReclaim);

        // Another owner's state carries no pending record and must not inherit this one.
        engine.restoreProfile("profile-b", new SavedState(), 3_000L);
        assertFalse(EngineProbe.isAwaitingDeathReclaim(engine));
        assertNull(engine.savedState().pendingDeathReclaim);

        Engine reset = started(gear(1L, 0L, 0L, 0L));
        reset.markPvmDeath(20, gear(1L, 0L, 0L, 0L).items, Map.of(), reset.events.owner);
        reset.markDirty();
        assertNotNull(settle(reset, ContainerSnapshot.empty(), 1_600L));
        reset.resetData(3_000L);
        assertFalse(EngineProbe.isAwaitingDeathReclaim(reset));
        assertNull(reset.savedState().pendingDeathReclaim);
    }

    @Test
    public void observedReclaimFeeAfterRestartBooksOnce()
    {
        Engine first = started(gear(1L, 0L, 150_000L, 0L));
        first.markPvmDeath(20, gear(1L, 0L, 150_000L, 0L).items, Map.of(), first.events.owner);
        first.markDirty();
        assertNotNull(settle(first, ContainerSnapshot.empty(), 1_600L));

        Engine restarted = new Engine(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(roundTrip(first.savedState()), 10_000L);
        restarted.resume(11_000L, PauseReason.IDLE, PauseReason.RECOVERY);
        restarted.setBaseline(gear(0L, 0L, 150_000L, 0L));
        assertTrue(restarted.noteReclaimIntent(
            BossRetrievalCatalogue.serviceFor("Talk-to", "Priestess Zul-Gwenwynig"), 40));
        restarted.markDirty();
        Transaction fee = settle(restarted, gear(1L, 0L, 50_000L, 0L), 12_000L);

        assertNotNull(fee);
        assertEquals(TransactionType.CONSUMPTION, fee.type());
        assertTrue(fee.counts());
        assertEquals(-100_000L, fee.net());
        assertEquals(100_000L, restarted.metrics(20_000L).cost);
        assertEquals(0L, restarted.metrics(20_000L).income);

        // Replaying the same settled state cannot book a second fee.
        restarted.markDirty();
        assertNull(settle(restarted, gear(1L, 0L, 50_000L, 0L), 20_000L));
        assertEquals(100_000L, restarted.metrics(30_000L).cost);
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
        assertNull(engine.processIfDirty(snapshot, firstTick, null));
        assertNull(engine.processIfDirty(snapshot, firstTick + 600L, null));
        return engine.processIfDirty(snapshot, firstTick + 1_200L, null);
    }

    private static Transaction sw(Engine engine, String note)
    {
        for (Transaction transaction : engine.session().transactions())
        {
            if (note.equals(transaction.note()))
            {
                return transaction;
            }
        }
        return null;
    }
}
