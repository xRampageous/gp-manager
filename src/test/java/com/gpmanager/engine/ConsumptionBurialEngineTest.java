package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Regression coverage for item removals made by the in-game "Bury" action. */
public class ConsumptionBurialEngineTest
{
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

    private final FlowValuator valuator = deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int price = entry.getKey() == 526 ? 31 : 100;
            flows.add(new Flow(
                entry.getKey(), "Item " + entry.getKey(), entry.getValue(), price, entry.getValue() * price));
        }
        return flows;
    };

    @Test
    public void activeBurialIsCountedConsumptionCost()
    {
        Engine engine = startedWithBones(1L);

        Transaction burial = settle(engine, FixtureReads.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.type());
        assertTrue(burial.counts());
        assertEquals(-31L, burial.net());
        Metrics metrics = engine.metrics(4_000L);
        assertEquals(0L, metrics.income);
        assertEquals(31L, metrics.cost);
        assertEquals(-31L, metrics.net);
    }

    @Test
    public void sameTickPickupThenBuryBooksConsumption()
    {
        // Acquire + bury in one stabilize window nets to zero vs baseline.
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(FixtureReads.empty());
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_000L, null));
        engine.noteConsume(526, 8, false, null, null);
        engine.markDirty();

        Transaction burial = settle(engine, FixtureReads.empty(), 1_600L);

        assertNotNull(burial);
        assertEquals(TransactionType.CONSUMPTION, burial.type());
        assertTrue(burial.counts());
        assertEquals(31L, engine.metrics(4_000L).cost);
    }

    @Test
    public void hardTransferWithStaleBoneIntentDoesNotBookOakDepositAsConsume()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(1511, 1L)); // oak logs
        engine.noteConsume(526, 6, false, null, null); // stale bones bury
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.markDirty();

        Transaction deposit = settle(engine, FixtureReads.empty(), 1_600L);

        assertEquals(TransactionType.TRANSFER, deposit.type());
        assertFalse(deposit.counts());
        assertEquals(0L, engine.metrics(4_000L).cost);
    }

    @Test
    public void repeatedBurialsAccumulateCosts()
    {
        Engine engine = startedWithBones(2L);

        settle(engine, snapshot(526, 1L), 1_600L);
        settle(engine, FixtureReads.empty(), 3_400L);

        Metrics metrics = engine.metrics(6_000L);
        assertEquals(62L, metrics.cost);
        assertEquals(-62L, metrics.net);
        assertEquals(2, engine.session().transactions().size());
    }

    @Test
    public void lootThenBurialRecordsRevenueAndConsumptionRatherThanPerpetualProfit()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(FixtureReads.empty());
        engine.markLoot(Collections.singletonMap(526, 1L), 6, "Loot from Chicken", "Chicken");
        engine.markDirty();

        Transaction loot = settle(engine, snapshot(526, 1L), 1_600L);
        engine.markDirty();
        Transaction burial = settle(engine, FixtureReads.empty(), 3_400L);

        assertEquals(TransactionType.LOOT, loot.type());
        assertEquals(31L, loot.revenue());
        assertEquals(TransactionType.CONSUMPTION, burial.type());
        assertEquals(31L, burial.costs());
        assertEquals(0L, engine.metrics(6_000L).net);
    }

    @Test
    public void staleTransferContextCannotHideBurialButFreshBankEvidenceCan()
    {
        Engine stale = startedWithBones(1L);
        stale.markContext(Context.TRANSFER, 6, "Bank transfer");
        for (int tick = 1; tick <= 7; tick++)
        {
            assertNull(stale.processIfDirty(snapshot(526, 1L), 1_000L + tick * 600L, null));
        }
        stale.markDirty();
        Transaction burial = settle(stale, FixtureReads.empty(), 6_000L);
        assertEquals(TransactionType.CONSUMPTION, burial.type());
        assertTrue(burial.counts());

        Engine duringBank = startedWithBones(1L);
        duringBank.bankOpened(6);
        duringBank.markDirty();
        Transaction deposit = settle(duringBank, FixtureReads.empty(), 1_600L);
        assertEquals(TransactionType.TRANSFER, deposit.type());
        assertFalse(deposit.counts());
        assertEquals(0L, duringBank.metrics(4_000L).cost);
    }

    @Test
    public void closingBankBeforeBurialClearsTransferClassification()
    {
        Engine engine = startedWithBones(1L);
        engine.bankOpened(6);
        engine.bankClosed();
        engine.markDirty();

        Transaction burial = settle(engine, FixtureReads.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.type());
        assertTrue(burial.counts());
        assertEquals(31L, engine.metrics(4_000L).cost);
    }

    /**
     * P1 regression, permanent coverage. Reproduces the exact reported failure
     * mode verbatim: trusted bone inventory, bank-open evidence refreshed every
     * tick (as live {@code GameTick} does while the bank widget is visible),
     * the bank closes with no further open notifications, and the very next
     * inventory observation loses one bone. Soft bank-open evidence must not
     * outlive the close and must not poison this burial as TRANSFER — it is
     * CONSUMPTION with a real, counted cost, and no genuine deposit ever
     * occurred (no bank-container/menu hard evidence at any point).
     */
    @Test
    public void burialAfterBankClosesWithNoFurtherOpenNotificationsIsConsumptionNotTransfer()
    {
        Engine engine = startedWithBones(1L);
        // Live GameTick refreshes bank-open (soft) evidence every tick the bank is visible.
        for (int tick = 0; tick < 5; tick++)
        {
            engine.bankOpened(6);
        }
        // Bank closes; no more open notifications will ever arrive again.
        engine.bankClosed();
        engine.markDirty();

        Transaction burial = settle(engine, FixtureReads.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.type());
        assertTrue(burial.counts());
        assertEquals(-31L, burial.net());
        Metrics metrics = engine.metrics(4_000L);
        assertEquals(31L, metrics.cost);
        assertEquals(0L, metrics.income);
        assertEquals(-31L, metrics.net);
    }

    /**
     * Same P1 scenario but with an open/close/open/close flutter before the
     * final close — bank detection flickering across ticks must not leave any
     * stale soft latch alive once the bank is genuinely and finally closed.
     */
    @Test
    public void repeatedBankOpenCloseFlutterBeforeFinalCloseStillBooksBurialAsConsumption()
    {
        Engine engine = startedWithBones(1L);
        engine.bankOpened(6);
        engine.bankClosed();
        engine.bankOpened(6);
        engine.bankOpened(6);
        engine.bankClosed();
        engine.markDirty();

        Transaction burial = settle(engine, FixtureReads.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.type());
        assertTrue(burial.counts());
        assertEquals(31L, engine.metrics(4_000L).cost);
    }

    /**
     * Bury multiple bones across several ticks entirely after the bank has
     * closed, with no consume-intent evidence at all (menu-click intent may
     * legitimately expire) — the pure-cost inventory shape after a finally
     * closed bank must still resolve to CONSUMPTION every time, never TRANSFER.
     */
    @Test
    public void multipleBurialsAfterFinalBankCloseAllBookAsConsumption()
    {
        Engine engine = startedWithBones(3L);
        engine.bankOpened(6);
        engine.bankOpened(6);
        engine.bankClosed();
        engine.markDirty();

        Transaction firstBurial = settle(engine, snapshot(526, 2L), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, firstBurial.type());
        assertTrue(firstBurial.counts());

        engine.markDirty();
        Transaction secondBurial = settle(engine, FixtureReads.empty(), 3_400L);
        assertEquals(TransactionType.CONSUMPTION, secondBurial.type());
        assertTrue(secondBurial.counts());

        assertEquals(93L, engine.metrics(6_000L).cost);
    }

    @Test
    public void withdrawThenBurySameItemRecordsTransferThenConsumption()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(FixtureReads.empty());
        engine.bankOpened(6);
        engine.markDirty();

        Transaction withdrawal = settle(engine, snapshot(526, 1L), 1_600L);
        assertEquals(TransactionType.TRANSFER, withdrawal.type());
        assertFalse(withdrawal.counts());

        engine.bankClosed();
        engine.markDirty();
        Transaction burial = settle(engine, FixtureReads.empty(), 3_400L);

        assertEquals(TransactionType.CONSUMPTION, burial.type());
        assertTrue(burial.counts());
        assertEquals(31L, engine.metrics(6_000L).cost);
    }

    @Test
    public void hardDepositEvidenceBeforeInventorySurvivesCloseTickAsTransfer()
    {
        // Live race: bank-container/menu hard evidence, GameTick closes bank and advances
        // idle context, then inventory callback arrives — must stay ownership-neutral.
        Engine engine = startedWithBones(1L);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_200L, null));
        engine.bankClosed();
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_800L, null));

        engine.markDirty();
        Transaction deposit = settle(engine, FixtureReads.empty(), 2_400L);

        assertEquals(TransactionType.TRANSFER, deposit.type());
        assertFalse(deposit.counts());
        assertEquals(0L, engine.metrics(5_000L).cost);
        assertEquals(0L, engine.metrics(5_000L).net);
    }

    @Test
    public void softOnlyDepositWhileBankOpenIsTransferNotLoss()
    {
        Engine engine = startedWithBones(1L);
        engine.bankOpened(6);
        engine.markDirty();

        Transaction deposit = settle(engine, FixtureReads.empty(), 1_600L);

        assertEquals(TransactionType.TRANSFER, deposit.type());
        assertFalse(deposit.counts());
        assertEquals(0L, engine.metrics(4_000L).cost);
    }

    @Test
    public void buryIntentAfterBankCloseBothCallbackOrdersBooksConsumption()
    {
        Engine closeFirst = startedWithBones(1L);
        closeFirst.bankOpened(6);
        closeFirst.bankClosed();
        closeFirst.noteConsume(526, 6, false, null, null);
        closeFirst.markDirty();
        Transaction burialCloseFirst = settle(closeFirst, FixtureReads.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, burialCloseFirst.type());
        assertTrue(burialCloseFirst.counts());
        assertEquals(31L, closeFirst.metrics(4_000L).cost);

        Engine dirtyFirst = startedWithBones(1L);
        dirtyFirst.bankOpened(6);
        dirtyFirst.markDirty();
        dirtyFirst.bankClosed();
        dirtyFirst.noteConsume(526, 6, false, null, null);
        Transaction burialDirtyFirst = settle(dirtyFirst, FixtureReads.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, burialDirtyFirst.type());
        assertTrue(burialDirtyFirst.counts());
        assertEquals(31L, dirtyFirst.metrics(4_000L).cost);
    }

    @Test
    public void buryIntentWinsOverSurvivingHardTransferEvidence()
    {
        Engine engine = startedWithBones(1L);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.bankClosed();
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_200L, null));
        engine.noteConsume(526, 6, false, null, null);
        engine.markDirty();

        Transaction burial = settle(engine, FixtureReads.empty(), 1_800L);

        assertEquals(TransactionType.CONSUMPTION, burial.type());
        assertTrue(burial.counts());
        assertEquals(31L, engine.metrics(4_000L).cost);
    }

    @Test
    public void eatIntentBooksConsumptionCost()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(379, 1L)); // lobster
        engine.noteConsume(379, 6, false, null, null);
        engine.markDirty();

        Transaction eaten = settle(engine, FixtureReads.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, eaten.type());
        assertTrue(eaten.counts());
        assertEquals(100L, engine.metrics(4_000L).cost);
    }

    @Test
    public void drinkIntentWithDoseLeftoverStillBooksConsumption()
    {
        // Prayer potion(4) → potion(3): mixed delta would be UNCERTAIN without intent.
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(2434, 1L));
        engine.noteConsume(2434, 6, false, null, null);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(139, 1L); // prayer potion(3)
        Transaction drink = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, drink.type());
        assertTrue(drink.counts());
        assertTrue(engine.metrics(4_000L).cost > 0L);
    }

    @Test
    public void openDrinkIntentWithoutItemIdStillBooksDoseLeftover()
    {
        // Live CC_OP Drink often reports itemId -1; open intent + dose step must still count.
        Engine engine = namedEngine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(2434, 1L));
        engine.noteConsume(-1, 6, false, null, null);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(139, 1L);
        Transaction drink = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, drink.type());
        assertTrue(drink.counts());
        // Net dose cost: lose (4) valued 200, gain (3) valued 100.
        assertEquals(-100L, drink.net());
        // Owner 2026-09-28: only the dose is a cost; the (3) left over is no gain.
        assertEquals(100L, engine.metrics(4_000L).cost);
        assertEquals(0L, engine.metrics(4_000L).income);
    }

    @Test
    public void openBuryIntentBeatsSurvivingHardTransferEvidence()
    {
        Engine engine = startedWithBones(1L);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.bankClosed();
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_200L, null));
        engine.noteConsume(-1, 6, false, null, null);
        engine.markDirty();

        Transaction burial = settle(engine, FixtureReads.empty(), 1_800L);

        assertEquals(TransactionType.CONSUMPTION, burial.type());
        assertTrue(burial.counts());
        assertEquals(31L, engine.metrics(4_000L).cost);
    }

    @Test
    public void runeCastStylePureLossBooksConsumption()
    {
        // Air rune stack spend — pure cost; cast intent confirms over stale transfer.
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(556, 100L)); // air rune
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.bankClosed();
        assertNull(engine.processIfDirty(snapshot(556, 100L), 1_200L, null));
        engine.noteConsume(556, 6, false, null, null);
        engine.markDirty();

        Transaction cast = settle(engine, snapshot(556, 95L), 1_800L);

        assertEquals(TransactionType.CONSUMPTION, cast.type());
        assertTrue(cast.counts());
        assertEquals(500L, engine.metrics(4_000L).cost);
    }

    @Test
    public void observedAlchemyBooksTheRealizedItemLossAndCoinGainOnce()
    {
        // High-alchemy metadata is not a quote. An actual cast is represented by the observed
        // item removal plus the observed coin receipt under the existing cast/consume intent.
        Engine engine = alchemyEngine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(1942, 1L));
        engine.noteConsume(1942, 8, false, null, null);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(995, 60L);
        Transaction alchemy = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertNotNull(alchemy);
        assertEquals(TransactionType.CONSUMPTION, alchemy.type());
        assertTrue(alchemy.counts());
        assertEquals(-40L, alchemy.net());
        assertEquals(100L, engine.metrics(4_000L).cost);
        assertEquals(60L, engine.metrics(4_000L).income);
        assertNull("replaying the settled snapshot must not book alchemy twice",
            engine.processIfDirty(new ContainerSnapshot(after), 2_800L, null));
    }

    @Test
    public void bankDepositStillTransferWhenConsumptionIntentAbsent()
    {
        Engine engine = startedWithBones(1L);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.markDirty();

        Transaction deposit = settle(engine, FixtureReads.empty(), 1_600L);

        assertEquals(TransactionType.TRANSFER, deposit.type());
        assertFalse(deposit.counts());
        assertEquals(0L, engine.metrics(4_000L).cost);
    }

    @Test
    public void bankOpenClearsStaleConsumeIntentSoDepositStaysTransfer()
    {
        Engine engine = startedWithBones(1L);
        engine.noteConsume(526, 6, false, null, null);
        engine.bankOpened(6);
        engine.markDirty();

        Transaction deposit = settle(engine, FixtureReads.empty(), 1_600L);

        assertEquals(TransactionType.TRANSFER, deposit.type());
        assertFalse(deposit.counts());
        assertEquals(0L, engine.metrics(4_000L).cost);
    }

    @Test
    public void pickingPotatoRegistersCountedGain()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(FixtureReads.empty());
        engine.setDetectedActivity("Farming", 1_100L);
        engine.markDirty();

        Transaction harvest = settle(engine, snapshot(1942, 1L), 1_600L); // potato

        assertEquals(TransactionType.GAIN, harvest.type());
        assertTrue(harvest.counts());
        assertEquals(100L, harvest.net());
        assertEquals(100L, engine.metrics(4_000L).income);
        assertEquals(100L, engine.metrics(4_000L).net);
    }

    @Test
    public void liveDrinkPickBuryCoalescedWindowWithOpenIntentBooksUsedLostAndPotato()
    {
        // Live screenshot sequence: Drink dose leftover + Pick potato×2 + Bury×2 settle
        // in one dirty window. Open menu itemId (-1) previously failed dose-pair /
        // pure-cost match → UNCERTAIN → Used/lost 0 and no Potato row.
        Engine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 4L); // bones
        before.put(3012, 1L); // energy potion(2)
        before.put(1942, 3L); // potatoes already held
        engine.setBaseline(new ContainerSnapshot(before));
        engine.noteConsume(-1, 8, false, null, null);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(526, 2L); // buried ×2
        after.put(3014, 1L); // energy potion(1) leftover
        after.put(1942, 5L); // picked ×2
        Transaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.type());
        assertTrue(settled.counts());
        Metrics metrics = engine.metrics(4_000L);
        assertTrue("bone + dose costs must book", metrics.cost > 0L);
        assertTrue("potato picks must book as revenue in the coalesced window",
            metrics.income > 0L);

        long boneUsed = 0L;
        long potatoGained = 0L;
        boolean sawPotionLoss = false;
        boolean sawPotionGain = false;
        for (Flow flow : settled.flows())
        {
            if (flow.item == 526 && flow.quantity < 0L)
            {
                boneUsed += Math.abs(flow.quantity);
            }
            if (flow.item == 1942 && flow.quantity > 0L)
            {
                potatoGained += flow.quantity;
            }
            if (flow.item == 3012 && flow.quantity < 0L)
            {
                sawPotionLoss = true;
            }
            if (flow.item == 3014 && flow.quantity > 0L)
            {
                sawPotionGain = true;
            }
        }
        assertEquals(2L, boneUsed);
        assertEquals(2L, potatoGained);
        assertTrue(sawPotionLoss);
        assertTrue(sawPotionGain);
    }

    @Test
    public void chatReinforceKeepsNamedIntentItemId()
    {
        Engine engine = startedWithBones(1L);
        engine.noteConsume(526, 4, false, null, null);
        engine.extendConsume(8, null);
        engine.markDirty();

        Transaction burial = settle(engine, FixtureReads.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, burial.type());
        assertTrue(burial.counts());
        assertEquals(31L, engine.metrics(4_000L).cost);
    }

    @Test
    public void openDrinkWithCompanionPotatoGainStillBooksDoseCost()
    {
        Engine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(2434, 1L);
        before.put(1942, 1L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.noteConsume(-1, 6, false, null, null);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(139, 1L);
        after.put(1942, 3L);
        Transaction drink = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, drink.type());
        assertTrue(drink.counts());
        assertTrue(engine.metrics(4_000L).cost > 0L);
        assertTrue(engine.metrics(4_000L).income > 0L);
    }

    @Test
    public void liveDrinkPickWithoutIntentStillBooksDoseAndPotato()
    {
        // Live miss: Drink intent expired before settle; dose leftover + Pick potato
        // coalesced into UNCERTAIN (Used/lost x0, no Potato row). Dose shape alone
        // must force CONSUMPTION without an armed intent.
        Engine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(3012, 1L); // energy potion(2)
        before.put(1942, 1L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(3014, 1L); // energy potion(1)
        after.put(1942, 4L); // picked ×3
        Transaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.type());
        assertTrue(settled.counts());
        assertTrue(engine.metrics(4_000L).cost > 0L);
        assertTrue(engine.metrics(4_000L).income > 0L);
        long potatoGained = 0L;
        for (Flow flow : settled.flows())
        {
            if (flow.item == 1942 && flow.quantity > 0L)
            {
                potatoGained += flow.quantity;
            }
        }
        assertEquals(3L, potatoGained);
    }

    @Test
    public void wrongNamedIntentWithDrinkPickStillBooksViaAnyCost()
    {
        // CC_OP sometimes resolves the wrong inventory slot id. Named match fails and
        // companion potato gains block hasOnlyCosts — any armed intent + any cost wins.
        Engine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 2L);
        before.put(1942, 2L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.noteConsume(9999, 8, false, null, null); // wrong id (not bones)
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(526, 0L);
        after.put(1942, 5L);
        after.entrySet().removeIf(e -> e.getValue() <= 0L);
        Transaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.type());
        assertTrue(settled.counts());
        assertEquals(62L, engine.metrics(4_000L).cost);
        assertTrue(engine.metrics(4_000L).income > 0L);
    }

    @Test
    public void chatReinforcedBuryWithPotatoPickBooksUsedLostAndGain()
    {
        // Dig+bury chat arms open intent after menu itemId was -1; Pick coalesces.
        Engine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 2L);
        before.put(1942, 1L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.extendConsume(12, null);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(1942, 3L);
        Transaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.type());
        assertTrue(settled.counts());
        assertEquals(62L, engine.metrics(4_000L).cost);
        assertTrue(engine.metrics(4_000L).income > 0L);
    }

    @Test
    public void repeatedBankOpenRefreshDoesNotClearArmedConsumeIntent()
    {
        // Live GameTick calls ze every tick while bank is open.
        // Clearing intent each refresh made Drink→Pick settle as soft TRANSFER.
        Engine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(3012, 1L);
        before.put(1942, 1L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.bankOpened(6); // open once
        engine.noteConsume(-1, 8, false, null, null); // armed while bank open (menu/chat)
        engine.bankOpened(6); // per-tick refresh must keep intent
        engine.bankOpened(6);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(3014, 1L);
        after.put(1942, 3L);
        Transaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.type());
        assertTrue(settled.counts());
        assertTrue(engine.metrics(4_000L).cost > 0L);
        assertTrue(engine.metrics(4_000L).income > 0L);
    }

    @Test
    public void softBankLatchWithDrinkPickMixedWindowStillBooksUsedLostAndPotato()
    {
        // Soft UI-open transfer evidence previously forced TRANSFER for any dirty
        // settle — including Drink dose leftover + Pick potato (Used/lost ×0).
        Engine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 2L);
        before.put(3012, 1L);
        before.put(1942, 2L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.bankOpened(6);
        engine.noteConsume(-1, 8, false, null, null);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(526, 0L);
        after.put(3014, 1L);
        after.put(1942, 5L);
        after.entrySet().removeIf(e -> e.getValue() <= 0L);
        Transaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.type());
        assertTrue(settled.counts());
        assertTrue("bones + dose must book Used/lost", engine.metrics(4_000L).cost > 0L);
        assertTrue("potato picks must book Received", engine.metrics(4_000L).income > 0L);
    }

    @Test
    public void softBankLatchDoseWithoutIntentStillBooksWhenMixedWithPick()
    {
        Engine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(3012, 1L);
        before.put(1942, 1L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.bankOpened(6);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(3014, 1L);
        after.put(1942, 4L);
        Transaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.type());
        assertTrue(settled.counts());
        assertTrue(engine.metrics(4_000L).cost > 0L);
        assertTrue(engine.metrics(4_000L).income > 0L);
    }

    @Test
    public void liveDrinkPickEatBuryCoalescedTimingsBookAllSupplies()
    {
        // Closest offline replay of the live Falador potato sequence:
        // Drink energy(2)→(1) + Pick×2 + Bury×2 settle together; Eat potato next.
        Engine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 4L);
        before.put(3012, 1L);
        before.put(1942, 3L);
        engine.setBaseline(new ContainerSnapshot(before));

        engine.noteConsume(-1, 12, false, null, null);
        engine.extendConsume(12, null); // chat: drink / dig+bury
        engine.markDirty();
        Map<Integer, Long> afterField = new HashMap<>();
        afterField.put(526, 2L);
        afterField.put(3014, 1L);
        afterField.put(1942, 5L);
        Transaction field = settle(engine, new ContainerSnapshot(afterField), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, field.type());
        assertTrue(field.counts());

        engine.noteConsume(1942, 8, false, null, null);
        engine.extendConsume(8, null); // chat: you eat the potato. yuck!
        engine.markDirty();
        Map<Integer, Long> afterEat = new HashMap<>();
        afterEat.put(526, 2L);
        afterEat.put(3014, 1L);
        afterEat.put(1942, 4L);
        Transaction eat = settle(engine, new ContainerSnapshot(afterEat), 3_400L);
        assertEquals(TransactionType.CONSUMPTION, eat.type());
        assertTrue(eat.counts());

        Metrics metrics = engine.metrics(6_000L);
        assertTrue(metrics.cost > 0L);
        assertTrue(metrics.income > 0L);
        long boneUsed = 0L;
        long potatoNet = 0L;
        for (Transaction tx : engine.session().transactions())
        {
            if (!tx.counts())
            {
                continue;
            }
            for (Flow flow : tx.flows())
            {
                if (flow.item == 526 && flow.quantity < 0L)
                {
                    boneUsed += Math.abs(flow.quantity);
                }
                if (flow.item == 1942)
                {
                    potatoNet += flow.quantity;
                }
            }
        }
        assertEquals(2L, boneUsed);
        assertEquals(1L, potatoNet); // +2 pick −1 eat
    }

    @Test
    public void pausedBurialDoesNotCreateCatchUpCostAfterResume()
    {
        Engine engine = startedWithBones(1L);
        engine.togglePause(1_100L);
        engine.markDirty();
        assertNull(engine.processIfDirty(FixtureReads.empty(), 1_600L, null));
        engine.togglePause(2_000L);

        assertNull(engine.processIfDirty(FixtureReads.empty(), 2_600L, null));
        assertTrue(engine.session().transactions().isEmpty());
        assertEquals(0L, engine.metrics(3_000L).cost);
    }

    @Test
    public void duplicateContainerDirtyForSameRemovalDoesNotDoubleCount()
    {
        Engine engine = startedWithBones(1L);
        engine.markDirty();
        Transaction burial = settle(engine, FixtureReads.empty(), 1_600L);
        assertNotNull(burial);

        engine.markDirty();
        assertNull(settle(engine, FixtureReads.empty(), 3_400L));
        assertEquals(1, engine.session().transactions().size());
        assertEquals(31L, engine.metrics(6_000L).cost);
    }

    @Test
    public void retainedSessionMetricsExposeCountedNegativeFlowForUsedLostAggregation()
    {
        Engine engine = startedWithBones(1L);
        Transaction burial = settle(engine, FixtureReads.empty(), 1_600L);

        assertTrue(burial.counts());
        assertEquals(31L, burial.costs());
        assertEquals(-31L, burial.net());
        assertEquals(31L, engine.metrics(4_000L).cost);
    }

    @Test
    public void aBurialInsideTheWithdrawalWindowIsNotNettedAway()
    {
        // Live capture (owner 2026-10-12): withdraw-all, then a bite two ticks later with the
        // bank just closed — the +26 and the −1 settled together, netted positive, and the bite
        // vanished into the transfer. A later click re-arms the intent at the lower count, so the
        // window's high-water must keep the stack's top for the split.
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(FixtureReads.empty());
        engine.bankOpened(6);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.markDirty();
        assertNull(engine.processIfDirty(snapshot(526, 26L), 1_400L, null));
        engine.bankClosed();
        engine.noteConsume(526, 4, false, ActionKind.BURY, null);
        assertNull(engine.processIfDirty(snapshot(526, 25L), 1_450L, null));
        engine.noteConsume(526, 4, false, ActionKind.BURY, null);
        assertNull(engine.processIfDirty(snapshot(526, 25L), 1_500L, null));

        // The stable window commits one batch: +26 withdrawn, −1 bitten.
        Transaction settled = engine.processIfDirty(snapshot(526, 25L), 1_600L, null);
        if (settled == null) settled = engine.processIfDirty(snapshot(526, 25L), 1_700L, null);

        assertNotNull(settled);
        List<Transaction> rows = engine.session().transactions();
        Transaction burial = null;
        Transaction transfer = null;
        for (Transaction row : rows)
        {
            if (row.type() == TransactionType.CONSUMPTION) burial = row;
            if (row.type() == TransactionType.TRANSFER) transfer = row;
        }
        assertNotNull("the bite must not vanish into the withdrawal: " + rows, burial);
        assertTrue(burial.counts());
        assertEquals("rows=" + rows, ActionKind.BURY, burial.action());
        assertEquals(-31L, burial.net());
        assertNotNull("the withdrawal keeps its own neutral row: " + rows, transfer);
        assertFalse(transfer.counts());
        assertEquals(26L, transfer.quantity(526, true));
        Metrics metrics = engine.metrics(4_000L);
        assertEquals("the bite is the only cost", 31L, metrics.cost);
        assertEquals("the withdrawal is never revenue", 0L, metrics.income);
        assertEquals(-31L, metrics.net);
    }

    @Test
    public void aSingleChickenWithdrawnAndEatenQuicklyStillBooks()
    {
        // Live capture (owner 2026-10-12): withdraw one chicken, close the bank, eat it right
        // away — the +1 and the −1 net to zero and the bite vanished into the bank window.
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(FixtureReads.empty());
        engine.bankOpened(6);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.markDirty();
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_400L, null));
        engine.bankClosed();
        engine.noteConsume(526, 4, false, ActionKind.BURY, null);
        assertNull(engine.processIfDirty(FixtureReads.empty(), 1_450L, null));
        assertNull(engine.processIfDirty(FixtureReads.empty(), 1_500L, null));
        Transaction settled = engine.processIfDirty(FixtureReads.empty(), 1_600L, null);
        if (settled == null) settled = engine.processIfDirty(FixtureReads.empty(), 1_700L, null);

        assertNotNull("the single bite must book", settled);
        assertEquals(TransactionType.CONSUMPTION, settled.type());
        assertEquals(ActionKind.BURY, settled.action());
        Metrics metrics = engine.metrics(4_000L);
        assertEquals(31L, metrics.cost);
        assertEquals(0L, metrics.income);
        assertEquals(-31L, metrics.net);
    }

    @Test
    public void aBiteInsideALootWindowKeepsBothRows()
    {
        // A looted stack settling with a bite must keep the loot as revenue and book the bite as
        // its own Supplies cost, never one mixed row (review 2026-10-12).
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(FixtureReads.empty());
        engine.markLoot(Collections.singletonMap(526, 26L), 6, "Loot from Goblin", "Goblin");
        engine.markDirty();
        assertNull(engine.processIfDirty(snapshot(526, 26L), 1_400L, null));
        engine.noteConsume(526, 4, false, ActionKind.BURY, null);
        assertNull(engine.processIfDirty(snapshot(526, 25L), 1_450L, null));
        engine.noteConsume(526, 4, false, ActionKind.BURY, null);
        assertNull(engine.processIfDirty(snapshot(526, 25L), 1_500L, null));
        Transaction settled = engine.processIfDirty(snapshot(526, 25L), 1_600L, null);
        if (settled == null) settled = engine.processIfDirty(snapshot(526, 25L), 1_700L, null);
        assertNotNull(settled);

        List<Transaction> rows = engine.session().transactions();
        Transaction loot = null;
        Transaction burial = null;
        for (Transaction row : rows)
        {
            if (row.type() == TransactionType.LOOT) loot = row;
            if (row.type() == TransactionType.CONSUMPTION) burial = row;
        }
        assertNotNull("the loot keeps its own row: " + rows, loot);
        assertNotNull("the bite books its own row: " + rows, burial);
        assertEquals("rows=" + rows, ActionKind.BURY, burial.action());
        Metrics metrics = engine.metrics(4_000L);
        assertEquals(26L * 31L, metrics.income);
        assertEquals(31L, metrics.cost);
        assertEquals(26L * 31L - 31L, metrics.net);
    }

    @Test
    public void aChatConfirmationAfterTheClicksLapseKeepsItsVerb()
    {
        // Live capture (owner 2026-10-12): the Eat clicks stopped and the queued last bite
        // matched a chat-armed intent, reading "Used"; the chat line itself proves Ate.
        Engine engine = startedWithBones(1L);
        engine.extendConsume(6, ActionKind.BURY);
        engine.markDirty();

        Transaction burial = settle(engine, FixtureReads.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.type());
        assertTrue(burial.counts());
        assertEquals(ActionKind.BURY, burial.action());
        assertEquals(31L, engine.metrics(4_000L).cost);
    }

    private Engine startedWithBones(long quantity)
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(526, quantity));
        return engine;
    }

    private Engine engine()
    {
        return new Engine(valuator, new TransactionClassifier(), CONFIG);
    }

    private Engine alchemyEngine()
    {
        FlowValuator alchemy = deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : deltas.entrySet())
            {
                int price = entry.getKey() == 995 ? 1 : 100;
                flows.add(new Flow(
                    entry.getKey(), "Item " + entry.getKey(), entry.getValue(), price,
                    entry.getValue() * price));
            }
            return flows;
        };
        return new Engine(alchemy, new TransactionClassifier(), CONFIG);
    }

    /** Valuator with potion dose names so open-intent dose matching can run offline. */
    private Engine namedEngine()
    {
        FlowValuator named = deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : deltas.entrySet())
            {
                int id = entry.getKey();
                String name;
                int price;
                if (id == 2434)
                {
                    name = "Prayer potion(4)";
                    price = 200;
                }
                else if (id == 139)
                {
                    name = "Prayer potion(3)";
                    price = 100;
                }
                else if (id == 3012)
                {
                    name = "Energy potion(2)";
                    price = 120;
                }
                else if (id == 3014)
                {
                    name = "Energy potion(1)";
                    price = 60;
                }
                else if (id == 526)
                {
                    name = "Bones";
                    price = 31;
                }
                else if (id == 1942)
                {
                    name = "Potato";
                    price = 50;
                }
                else
                {
                    name = "Item " + id;
                    price = 100;
                }
                flows.add(new Flow(
                    id, name, entry.getValue(), price, entry.getValue() * price));
            }
            return flows;
        };
        return new Engine(named, new TransactionClassifier(), CONFIG);
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        assertNull(engine.processIfDirty(snapshot, firstTick, null));
        assertNull(engine.processIfDirty(snapshot, firstTick + 600L, null));
        return engine.processIfDirty(snapshot, firstTick + 1_200L, null);
    }

    private static ContainerSnapshot snapshot(int itemId, long quantity)
    {
        Map<Integer, Long> values = new HashMap<>();
        if (quantity > 0L)
        {
            values.put(itemId, quantity);
        }
        return new ContainerSnapshot(values);
    }
}
