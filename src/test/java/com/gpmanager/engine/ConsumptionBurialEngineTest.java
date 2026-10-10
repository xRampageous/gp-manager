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

        Transaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(-31L, burial.getNet());
        Metrics metrics = engine.getMetrics(4_000L);
        assertEquals(0L, metrics.revenue);
        assertEquals(31L, metrics.costs);
        assertEquals(-31L, metrics.net);
    }

    @Test
    public void sameTickPickupThenBuryBooksConsumption()
    {
        // Acquire + bury in one stabilize window nets to zero vs baseline.
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_000L));
        engine.noteConsume(526, 8, false);
        engine.markDirty();

        Transaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(burial);
        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void hardTransferWithStaleBoneIntentDoesNotBookOakDepositAsConsume()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(1511, 1L)); // oak logs
        engine.noteConsume(526, 6, false); // stale bones bury
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.markDirty();

        Transaction deposit = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void repeatedBurialsAccumulateCosts()
    {
        Engine engine = startedWithBones(2L);

        settle(engine, snapshot(526, 1L), 1_600L);
        settle(engine, ContainerSnapshot.empty(), 3_400L);

        Metrics metrics = engine.getMetrics(6_000L);
        assertEquals(62L, metrics.costs);
        assertEquals(-62L, metrics.net);
        assertEquals(2, engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void lootThenBurialRecordsRevenueAndConsumptionRatherThanPerpetualProfit()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markLoot(Collections.singletonMap(526, 1L), 6, "Loot from Chicken", "Chicken");
        engine.markDirty();

        Transaction loot = settle(engine, snapshot(526, 1L), 1_600L);
        engine.markDirty();
        Transaction burial = settle(engine, ContainerSnapshot.empty(), 3_400L);

        assertEquals(TransactionType.LOOT, loot.getType());
        assertEquals(31L, loot.getRevenue());
        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertEquals(31L, burial.getCosts());
        assertEquals(0L, engine.getMetrics(6_000L).net);
    }

    @Test
    public void staleTransferContextCannotHideBurialButFreshBankEvidenceCan()
    {
        Engine stale = startedWithBones(1L);
        stale.markContext(Context.TRANSFER, 6, "Bank transfer");
        for (int tick = 1; tick <= 7; tick++)
        {
            assertNull(stale.processIfDirty(snapshot(526, 1L), 1_000L + tick * 600L));
        }
        stale.markDirty();
        Transaction burial = settle(stale, ContainerSnapshot.empty(), 6_000L);
        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());

        Engine duringBank = startedWithBones(1L);
        duringBank.bankOpened(6);
        duringBank.markDirty();
        Transaction deposit = settle(duringBank, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, duringBank.getMetrics(4_000L).costs);
    }

    @Test
    public void closingBankBeforeBurialClearsTransferClassification()
    {
        Engine engine = startedWithBones(1L);
        engine.bankOpened(6);
        engine.bankClosed();
        engine.markDirty();

        Transaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).costs);
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

        Transaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(-31L, burial.getNet());
        Metrics metrics = engine.getMetrics(4_000L);
        assertEquals(31L, metrics.costs);
        assertEquals(0L, metrics.revenue);
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

        Transaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).costs);
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
        assertEquals(TransactionType.CONSUMPTION, firstBurial.getType());
        assertTrue(firstBurial.isCounted());

        engine.markDirty();
        Transaction secondBurial = settle(engine, ContainerSnapshot.empty(), 3_400L);
        assertEquals(TransactionType.CONSUMPTION, secondBurial.getType());
        assertTrue(secondBurial.isCounted());

        assertEquals(93L, engine.getMetrics(6_000L).costs);
    }

    @Test
    public void withdrawThenBurySameItemRecordsTransferThenConsumption()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.bankOpened(6);
        engine.markDirty();

        Transaction withdrawal = settle(engine, snapshot(526, 1L), 1_600L);
        assertEquals(TransactionType.TRANSFER, withdrawal.getType());
        assertFalse(withdrawal.isCounted());

        engine.bankClosed();
        engine.markDirty();
        Transaction burial = settle(engine, ContainerSnapshot.empty(), 3_400L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(6_000L).costs);
    }

    @Test
    public void hardDepositEvidenceBeforeInventorySurvivesCloseTickAsTransfer()
    {
        // Live race: bank-container/menu hard evidence, GameTick closes bank and advances
        // idle context, then inventory callback arrives — must stay ownership-neutral.
        Engine engine = startedWithBones(1L);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_200L));
        engine.bankClosed();
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_800L));

        engine.markDirty();
        Transaction deposit = settle(engine, ContainerSnapshot.empty(), 2_400L);

        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(5_000L).costs);
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void softOnlyDepositWhileBankOpenIsTransferNotLoss()
    {
        Engine engine = startedWithBones(1L);
        engine.bankOpened(6);
        engine.markDirty();

        Transaction deposit = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void buryIntentAfterBankCloseBothCallbackOrdersBooksConsumption()
    {
        Engine closeFirst = startedWithBones(1L);
        closeFirst.bankOpened(6);
        closeFirst.bankClosed();
        closeFirst.noteConsume(526, 6, false);
        closeFirst.markDirty();
        Transaction burialCloseFirst = settle(closeFirst, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, burialCloseFirst.getType());
        assertTrue(burialCloseFirst.isCounted());
        assertEquals(31L, closeFirst.getMetrics(4_000L).costs);

        Engine dirtyFirst = startedWithBones(1L);
        dirtyFirst.bankOpened(6);
        dirtyFirst.markDirty();
        dirtyFirst.bankClosed();
        dirtyFirst.noteConsume(526, 6, false);
        Transaction burialDirtyFirst = settle(dirtyFirst, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, burialDirtyFirst.getType());
        assertTrue(burialDirtyFirst.isCounted());
        assertEquals(31L, dirtyFirst.getMetrics(4_000L).costs);
    }

    @Test
    public void buryIntentWinsOverSurvivingHardTransferEvidence()
    {
        Engine engine = startedWithBones(1L);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.bankClosed();
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_200L));
        engine.noteConsume(526, 6, false);
        engine.markDirty();

        Transaction burial = settle(engine, ContainerSnapshot.empty(), 1_800L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void eatIntentBooksConsumptionCost()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(379, 1L)); // lobster
        engine.noteConsume(379, 6, false);
        engine.markDirty();

        Transaction eaten = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, eaten.getType());
        assertTrue(eaten.isCounted());
        assertEquals(100L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void drinkIntentWithDoseLeftoverStillBooksConsumption()
    {
        // Prayer potion(4) → potion(3): mixed delta would be UNCERTAIN without intent.
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(2434, 1L));
        engine.noteConsume(2434, 6, false);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(139, 1L); // prayer potion(3)
        Transaction drink = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, drink.getType());
        assertTrue(drink.isCounted());
        assertTrue(engine.getMetrics(4_000L).costs > 0L);
    }

    @Test
    public void openDrinkIntentWithoutItemIdStillBooksDoseLeftover()
    {
        // Live CC_OP Drink often reports itemId -1; open intent + dose step must still count.
        Engine engine = namedEngine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(2434, 1L));
        engine.noteConsume(-1, 6, false);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(139, 1L);
        Transaction drink = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, drink.getType());
        assertTrue(drink.isCounted());
        // Net dose cost: lose (4) valued 200, gain (3) valued 100.
        assertEquals(-100L, drink.getNet());
        // Owner 2026-09-28: only the dose is a cost; the (3) left over is no gain.
        assertEquals(100L, engine.getMetrics(4_000L).costs);
        assertEquals(0L, engine.getMetrics(4_000L).revenue);
    }

    @Test
    public void openBuryIntentBeatsSurvivingHardTransferEvidence()
    {
        Engine engine = startedWithBones(1L);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.bankClosed();
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_200L));
        engine.noteConsume(-1, 6, false);
        engine.markDirty();

        Transaction burial = settle(engine, ContainerSnapshot.empty(), 1_800L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).costs);
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
        assertNull(engine.processIfDirty(snapshot(556, 100L), 1_200L));
        engine.noteConsume(556, 6, false);
        engine.markDirty();

        Transaction cast = settle(engine, snapshot(556, 95L), 1_800L);

        assertEquals(TransactionType.CONSUMPTION, cast.getType());
        assertTrue(cast.isCounted());
        assertEquals(500L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void observedAlchemyBooksTheRealizedItemLossAndCoinGainOnce()
    {
        // High-alchemy metadata is not a quote. An actual cast is represented by the observed
        // item removal plus the observed coin receipt under the existing cast/consume intent.
        Engine engine = alchemyEngine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(1942, 1L));
        engine.noteConsume(1942, 8, false);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(995, 60L);
        Transaction alchemy = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertNotNull(alchemy);
        assertEquals(TransactionType.CONSUMPTION, alchemy.getType());
        assertTrue(alchemy.isCounted());
        assertEquals(-40L, alchemy.getNet());
        assertEquals(100L, engine.getMetrics(4_000L).costs);
        assertEquals(60L, engine.getMetrics(4_000L).revenue);
        assertNull("replaying the settled snapshot must not book alchemy twice",
            engine.processIfDirty(new ContainerSnapshot(after), 2_800L));
    }

    @Test
    public void bankDepositStillTransferWhenConsumptionIntentAbsent()
    {
        Engine engine = startedWithBones(1L);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.markDirty();

        Transaction deposit = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void bankOpenClearsStaleConsumeIntentSoDepositStaysTransfer()
    {
        Engine engine = startedWithBones(1L);
        engine.noteConsume(526, 6, false);
        engine.bankOpened(6);
        engine.markDirty();

        Transaction deposit = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void pickingPotatoRegistersCountedGain()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.setDetectedActivity("Farming", 1_100L);
        engine.markDirty();

        Transaction harvest = settle(engine, snapshot(1942, 1L), 1_600L); // potato

        assertEquals(TransactionType.GAIN, harvest.getType());
        assertTrue(harvest.isCounted());
        assertEquals(100L, harvest.getNet());
        assertEquals(100L, engine.getMetrics(4_000L).revenue);
        assertEquals(100L, engine.getMetrics(4_000L).net);
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
        engine.noteConsume(-1, 8, false);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(526, 2L); // buried ×2
        after.put(3014, 1L); // energy potion(1) leftover
        after.put(1942, 5L); // picked ×2
        Transaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        Metrics metrics = engine.getMetrics(4_000L);
        assertTrue("bone + dose costs must book", metrics.costs > 0L);
        assertTrue("potato picks must book as revenue in the coalesced window",
            metrics.revenue > 0L);

        long boneUsed = 0L;
        long potatoGained = 0L;
        boolean sawPotionLoss = false;
        boolean sawPotionGain = false;
        for (Flow flow : settled.getFlows())
        {
            if (flow.itemId == 526 && flow.quantityDelta < 0L)
            {
                boneUsed += Math.abs(flow.quantityDelta);
            }
            if (flow.itemId == 1942 && flow.quantityDelta > 0L)
            {
                potatoGained += flow.quantityDelta;
            }
            if (flow.itemId == 3012 && flow.quantityDelta < 0L)
            {
                sawPotionLoss = true;
            }
            if (flow.itemId == 3014 && flow.quantityDelta > 0L)
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
        engine.noteConsume(526, 4, false);
        engine.extendConsume(8);
        engine.markDirty();

        Transaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).costs);
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
        engine.noteConsume(-1, 6, false);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(139, 1L);
        after.put(1942, 3L);
        Transaction drink = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, drink.getType());
        assertTrue(drink.isCounted());
        assertTrue(engine.getMetrics(4_000L).costs > 0L);
        assertTrue(engine.getMetrics(4_000L).revenue > 0L);
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

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertTrue(engine.getMetrics(4_000L).costs > 0L);
        assertTrue(engine.getMetrics(4_000L).revenue > 0L);
        long potatoGained = 0L;
        for (Flow flow : settled.getFlows())
        {
            if (flow.itemId == 1942 && flow.quantityDelta > 0L)
            {
                potatoGained += flow.quantityDelta;
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
        engine.noteConsume(9999, 8, false); // wrong id (not bones)
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(526, 0L);
        after.put(1942, 5L);
        after.entrySet().removeIf(e -> e.getValue() <= 0L);
        Transaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertEquals(62L, engine.getMetrics(4_000L).costs);
        assertTrue(engine.getMetrics(4_000L).revenue > 0L);
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
        engine.extendConsume(12);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(1942, 3L);
        Transaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertEquals(62L, engine.getMetrics(4_000L).costs);
        assertTrue(engine.getMetrics(4_000L).revenue > 0L);
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
        engine.noteConsume(-1, 8, false); // armed while bank open (menu/chat)
        engine.bankOpened(6); // per-tick refresh must keep intent
        engine.bankOpened(6);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(3014, 1L);
        after.put(1942, 3L);
        Transaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertTrue(engine.getMetrics(4_000L).costs > 0L);
        assertTrue(engine.getMetrics(4_000L).revenue > 0L);
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
        engine.noteConsume(-1, 8, false);
        engine.markDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(526, 0L);
        after.put(3014, 1L);
        after.put(1942, 5L);
        after.entrySet().removeIf(e -> e.getValue() <= 0L);
        Transaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertTrue("bones + dose must book Used/lost", engine.getMetrics(4_000L).costs > 0L);
        assertTrue("potato picks must book Received", engine.getMetrics(4_000L).revenue > 0L);
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

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertTrue(engine.getMetrics(4_000L).costs > 0L);
        assertTrue(engine.getMetrics(4_000L).revenue > 0L);
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

        engine.noteConsume(-1, 12, false);
        engine.extendConsume(12); // chat: drink / dig+bury
        engine.markDirty();
        Map<Integer, Long> afterField = new HashMap<>();
        afterField.put(526, 2L);
        afterField.put(3014, 1L);
        afterField.put(1942, 5L);
        Transaction field = settle(engine, new ContainerSnapshot(afterField), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, field.getType());
        assertTrue(field.isCounted());

        engine.noteConsume(1942, 8, false);
        engine.extendConsume(8); // chat: you eat the potato. yuck!
        engine.markDirty();
        Map<Integer, Long> afterEat = new HashMap<>();
        afterEat.put(526, 2L);
        afterEat.put(3014, 1L);
        afterEat.put(1942, 4L);
        Transaction eat = settle(engine, new ContainerSnapshot(afterEat), 3_400L);
        assertEquals(TransactionType.CONSUMPTION, eat.getType());
        assertTrue(eat.isCounted());

        Metrics metrics = engine.getMetrics(6_000L);
        assertTrue(metrics.costs > 0L);
        assertTrue(metrics.revenue > 0L);
        long boneUsed = 0L;
        long potatoNet = 0L;
        for (Transaction tx : engine.getActiveSession().getTransactions())
        {
            if (!tx.isCounted())
            {
                continue;
            }
            for (Flow flow : tx.getFlows())
            {
                if (flow.itemId == 526 && flow.quantityDelta < 0L)
                {
                    boneUsed += Math.abs(flow.quantityDelta);
                }
                if (flow.itemId == 1942)
                {
                    potatoNet += flow.quantityDelta;
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
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), 1_600L));
        engine.togglePause(2_000L);

        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), 2_600L));
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(0L, engine.getMetrics(3_000L).costs);
    }

    @Test
    public void duplicateContainerDirtyForSameRemovalDoesNotDoubleCount()
    {
        Engine engine = startedWithBones(1L);
        engine.markDirty();
        Transaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertNotNull(burial);

        engine.markDirty();
        assertNull(settle(engine, ContainerSnapshot.empty(), 3_400L));
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertEquals(31L, engine.getMetrics(6_000L).costs);
    }

    @Test
    public void retainedSessionMetricsExposeCountedNegativeFlowForUsedLostAggregation()
    {
        Engine engine = startedWithBones(1L);
        Transaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertTrue(burial.isCounted());
        assertEquals(31L, burial.getCosts());
        assertEquals(-31L, burial.getNet());
        assertEquals(31L, engine.getMetrics(4_000L).costs);
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
        assertNull(engine.processIfDirty(snapshot, firstTick));
        assertNull(engine.processIfDirty(snapshot, firstTick + 600L));
        return engine.processIfDirty(snapshot, firstTick + 1_200L);
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
