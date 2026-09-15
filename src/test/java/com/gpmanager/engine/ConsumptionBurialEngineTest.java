package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
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
        List<ItemFlow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int price = entry.getKey() == 526 ? 31 : 100;
            flows.add(new ItemFlow(
                entry.getKey(), "Item " + entry.getKey(), entry.getValue(), price, entry.getValue() * price));
        }
        return flows;
    };

    @Test
    public void activeBurialIsCountedConsumptionCost()
    {
        GpManagerEngine engine = startedWithBones(1L);

        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(-31L, burial.getNet());
        SessionMetrics metrics = engine.getMetrics(4_000L);
        assertEquals(0L, metrics.getRevenue());
        assertEquals(31L, metrics.getCosts());
        assertEquals(-31L, metrics.getNet());
    }

    @Test
    public void sameTickPickupThenBuryBooksConsumption()
    {
        // Acquire + bury in one stabilize window nets to zero vs baseline.
        GpManagerEngine engine = new GpManagerEngine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_000L));
        engine.noteConsumptionIntent(526, 8);
        engine.markInventoryDirty();

        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(burial);
        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).getCosts());
    }

    @Test
    public void hardTransferWithStaleBoneIntentDoesNotBookOakDepositAsConsume()
    {
        GpManagerEngine engine = new GpManagerEngine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(1511, 1L)); // oak logs
        engine.noteConsumptionIntent(526, 6); // stale bones bury
        engine.markContext(TrackingContext.TRANSFER, 6, "Bank container transfer");
        engine.markInventoryDirty();

        ProfitTransaction deposit = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(4_000L).getCosts());
    }

    @Test
    public void repeatedBurialsAccumulateCosts()
    {
        GpManagerEngine engine = startedWithBones(2L);

        settle(engine, snapshot(526, 1L), 1_600L);
        settle(engine, ContainerSnapshot.empty(), 3_400L);

        SessionMetrics metrics = engine.getMetrics(6_000L);
        assertEquals(62L, metrics.getCosts());
        assertEquals(-62L, metrics.getNet());
        assertEquals(2, engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void lootThenBurialRecordsRevenueAndConsumptionRatherThanPerpetualProfit()
    {
        GpManagerEngine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markLootContext(Collections.singletonMap(526, 1L), 6, "Loot from Chicken");
        engine.markInventoryDirty();

        ProfitTransaction loot = settle(engine, snapshot(526, 1L), 1_600L);
        engine.markInventoryDirty();
        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 3_400L);

        assertEquals(TransactionType.LOOT, loot.getType());
        assertEquals(31L, loot.getRevenue());
        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertEquals(31L, burial.getCosts());
        assertEquals(0L, engine.getMetrics(6_000L).getNet());
    }

    @Test
    public void staleTransferContextCannotHideBurialButFreshBankEvidenceCan()
    {
        GpManagerEngine stale = startedWithBones(1L);
        stale.markContext(TrackingContext.TRANSFER, 6, "Bank transfer");
        for (int tick = 1; tick <= 7; tick++)
        {
            assertNull(stale.processIfDirty(snapshot(526, 1L), 1_000L + tick * 600L));
        }
        stale.markInventoryDirty();
        ProfitTransaction burial = settle(stale, ContainerSnapshot.empty(), 6_000L);
        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());

        GpManagerEngine duringBank = startedWithBones(1L);
        duringBank.markBankInterfaceOpen(6);
        duringBank.markInventoryDirty();
        ProfitTransaction deposit = settle(duringBank, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, duringBank.getMetrics(4_000L).getCosts());
    }

    @Test
    public void closingBankBeforeBurialClearsTransferClassification()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.markBankInterfaceOpen(6);
        engine.markBankInterfaceClosed();
        engine.markInventoryDirty();

        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).getCosts());
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
        GpManagerEngine engine = startedWithBones(1L);
        // Live GameTick refreshes bank-open (soft) evidence every tick the bank is visible.
        for (int tick = 0; tick < 5; tick++)
        {
            engine.markBankInterfaceOpen(6);
        }
        // Bank closes; no more open notifications will ever arrive again.
        engine.markBankInterfaceClosed();
        engine.markInventoryDirty();

        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(-31L, burial.getNet());
        SessionMetrics metrics = engine.getMetrics(4_000L);
        assertEquals(31L, metrics.getCosts());
        assertEquals(0L, metrics.getRevenue());
        assertEquals(-31L, metrics.getNet());
    }

    /**
     * Same P1 scenario but with an open/close/open/close flutter before the
     * final close — bank detection flickering across ticks must not leave any
     * stale soft latch alive once the bank is genuinely and finally closed.
     */
    @Test
    public void repeatedBankOpenCloseFlutterBeforeFinalCloseStillBooksBurialAsConsumption()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.markBankInterfaceOpen(6);
        engine.markBankInterfaceClosed();
        engine.markBankInterfaceOpen(6);
        engine.markBankInterfaceOpen(6);
        engine.markBankInterfaceClosed();
        engine.markInventoryDirty();

        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).getCosts());
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
        GpManagerEngine engine = startedWithBones(3L);
        engine.markBankInterfaceOpen(6);
        engine.markBankInterfaceOpen(6);
        engine.markBankInterfaceClosed();
        engine.markInventoryDirty();

        ProfitTransaction firstBurial = settle(engine, snapshot(526, 2L), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, firstBurial.getType());
        assertTrue(firstBurial.isCounted());

        engine.markInventoryDirty();
        ProfitTransaction secondBurial = settle(engine, ContainerSnapshot.empty(), 3_400L);
        assertEquals(TransactionType.CONSUMPTION, secondBurial.getType());
        assertTrue(secondBurial.isCounted());

        assertEquals(93L, engine.getMetrics(6_000L).getCosts());
    }

    @Test
    public void withdrawThenBurySameItemRecordsTransferThenConsumption()
    {
        GpManagerEngine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markBankInterfaceOpen(6);
        engine.markInventoryDirty();

        ProfitTransaction withdrawal = settle(engine, snapshot(526, 1L), 1_600L);
        assertEquals(TransactionType.TRANSFER, withdrawal.getType());
        assertFalse(withdrawal.isCounted());

        engine.markBankInterfaceClosed();
        engine.markInventoryDirty();
        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 3_400L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(6_000L).getCosts());
    }

    @Test
    public void hardDepositEvidenceBeforeInventorySurvivesCloseTickAsTransfer()
    {
        // Live race: bank-container/menu hard evidence, GameTick closes bank and advances
        // idle context, then inventory callback arrives — must stay ownership-neutral.
        GpManagerEngine engine = startedWithBones(1L);
        engine.markContext(TrackingContext.TRANSFER, 6, "Bank container transfer");
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_200L));
        engine.markBankInterfaceClosed();
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_800L));

        engine.markInventoryDirty();
        ProfitTransaction deposit = settle(engine, ContainerSnapshot.empty(), 2_400L);

        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(5_000L).getCosts());
        assertEquals(0L, engine.getMetrics(5_000L).getNet());
    }

    @Test
    public void softOnlyDepositWhileBankOpenIsTransferNotLoss()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.markBankInterfaceOpen(6);
        engine.markInventoryDirty();

        ProfitTransaction deposit = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(4_000L).getCosts());
    }

    @Test
    public void buryIntentAfterBankCloseBothCallbackOrdersBooksConsumption()
    {
        GpManagerEngine closeFirst = startedWithBones(1L);
        closeFirst.markBankInterfaceOpen(6);
        closeFirst.markBankInterfaceClosed();
        closeFirst.noteConsumptionIntent(526, 6);
        closeFirst.markInventoryDirty();
        ProfitTransaction burialCloseFirst = settle(closeFirst, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, burialCloseFirst.getType());
        assertTrue(burialCloseFirst.isCounted());
        assertEquals(31L, closeFirst.getMetrics(4_000L).getCosts());

        GpManagerEngine dirtyFirst = startedWithBones(1L);
        dirtyFirst.markBankInterfaceOpen(6);
        dirtyFirst.markInventoryDirty();
        dirtyFirst.markBankInterfaceClosed();
        dirtyFirst.noteConsumptionIntent(526, 6);
        ProfitTransaction burialDirtyFirst = settle(dirtyFirst, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, burialDirtyFirst.getType());
        assertTrue(burialDirtyFirst.isCounted());
        assertEquals(31L, dirtyFirst.getMetrics(4_000L).getCosts());
    }

    @Test
    public void buryIntentWinsOverSurvivingHardTransferEvidence()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.markContext(TrackingContext.TRANSFER, 6, "Bank container transfer");
        engine.markBankInterfaceClosed();
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_200L));
        engine.noteConsumptionIntent(526, 6);
        engine.markInventoryDirty();

        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 1_800L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).getCosts());
    }

    @Test
    public void eatIntentBooksConsumptionCost()
    {
        GpManagerEngine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(379, 1L)); // lobster
        engine.noteConsumptionIntent(379, 6);
        engine.markInventoryDirty();

        ProfitTransaction eaten = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, eaten.getType());
        assertTrue(eaten.isCounted());
        assertEquals(100L, engine.getMetrics(4_000L).getCosts());
    }

    @Test
    public void drinkIntentWithDoseLeftoverStillBooksConsumption()
    {
        // Prayer potion(4) → potion(3): mixed delta would be UNCERTAIN without intent.
        GpManagerEngine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(2434, 1L));
        engine.noteConsumptionIntent(2434, 6);
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(139, 1L); // prayer potion(3)
        ProfitTransaction drink = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, drink.getType());
        assertTrue(drink.isCounted());
        assertTrue(engine.getMetrics(4_000L).getCosts() > 0L);
    }

    @Test
    public void openDrinkIntentWithoutItemIdStillBooksDoseLeftover()
    {
        // Live CC_OP Drink often reports itemId -1; open intent + dose step must still count.
        GpManagerEngine engine = namedEngine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(2434, 1L));
        engine.noteConsumptionIntent(-1, 6);
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(139, 1L);
        ProfitTransaction drink = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, drink.getType());
        assertTrue(drink.isCounted());
        // Net dose cost: lose (4) valued 200, gain (3) valued 100.
        assertEquals(-100L, drink.getNet());
        assertEquals(200L, engine.getMetrics(4_000L).getCosts());
        assertEquals(100L, engine.getMetrics(4_000L).getRevenue());
    }

    @Test
    public void openBuryIntentBeatsSurvivingHardTransferEvidence()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.markContext(TrackingContext.TRANSFER, 6, "Bank container transfer");
        engine.markBankInterfaceClosed();
        assertNull(engine.processIfDirty(snapshot(526, 1L), 1_200L));
        engine.noteConsumptionIntent(-1, 6);
        engine.markInventoryDirty();

        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 1_800L);

        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).getCosts());
    }

    @Test
    public void runeCastStylePureLossBooksConsumption()
    {
        // Air rune stack spend — pure cost; cast intent confirms over stale transfer.
        GpManagerEngine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(556, 100L)); // air rune
        engine.markContext(TrackingContext.TRANSFER, 6, "Bank container transfer");
        engine.markBankInterfaceClosed();
        assertNull(engine.processIfDirty(snapshot(556, 100L), 1_200L));
        engine.noteConsumptionIntent(556, 6);
        engine.markInventoryDirty();

        ProfitTransaction cast = settle(engine, snapshot(556, 95L), 1_800L);

        assertEquals(TransactionType.CONSUMPTION, cast.getType());
        assertTrue(cast.isCounted());
        assertEquals(500L, engine.getMetrics(4_000L).getCosts());
    }

    @Test
    public void bankDepositStillTransferWhenConsumptionIntentAbsent()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.markContext(TrackingContext.TRANSFER, 6, "Bank container transfer");
        engine.markInventoryDirty();

        ProfitTransaction deposit = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(4_000L).getCosts());
    }

    @Test
    public void bankOpenClearsStaleConsumeIntentSoDepositStaysTransfer()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.noteConsumptionIntent(526, 6);
        engine.markBankInterfaceOpen(6);
        engine.markInventoryDirty();

        ProfitTransaction deposit = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(4_000L).getCosts());
    }

    @Test
    public void pickingPotatoRegistersCountedGain()
    {
        GpManagerEngine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.setDetectedActivity("Farming", 1_100L);
        engine.markInventoryDirty();

        ProfitTransaction harvest = settle(engine, snapshot(1942, 1L), 1_600L); // potato

        assertEquals(TransactionType.GAIN, harvest.getType());
        assertTrue(harvest.isCounted());
        assertEquals(100L, harvest.getNet());
        assertEquals(100L, engine.getMetrics(4_000L).getRevenue());
        assertEquals(100L, engine.getMetrics(4_000L).getNet());
    }

    @Test
    public void liveDrinkPickBuryCoalescedWindowWithOpenIntentBooksUsedLostAndPotato()
    {
        // Live screenshot sequence: Drink dose leftover + Pick potato×2 + Bury×2 settle
        // in one dirty window. Open menu itemId (-1) previously failed dose-pair /
        // pure-cost match → UNCERTAIN → Used/lost 0 and no Potato row.
        GpManagerEngine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 4L); // bones
        before.put(3012, 1L); // energy potion(2)
        before.put(1942, 3L); // potatoes already held
        engine.setBaseline(new ContainerSnapshot(before));
        engine.noteConsumptionIntent(-1, 8);
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(526, 2L); // buried ×2
        after.put(3014, 1L); // energy potion(1) leftover
        after.put(1942, 5L); // picked ×2
        ProfitTransaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        SessionMetrics metrics = engine.getMetrics(4_000L);
        assertTrue("bone + dose costs must book", metrics.getCosts() > 0L);
        assertTrue("potato picks must book as revenue in the coalesced window",
            metrics.getRevenue() > 0L);

        long boneUsed = 0L;
        long potatoGained = 0L;
        boolean sawPotionLoss = false;
        boolean sawPotionGain = false;
        for (ItemFlow flow : settled.getFlows())
        {
            if (flow.getItemId() == 526 && flow.getQuantityDelta() < 0L)
            {
                boneUsed += Math.abs(flow.getQuantityDelta());
            }
            if (flow.getItemId() == 1942 && flow.getQuantityDelta() > 0L)
            {
                potatoGained += flow.getQuantityDelta();
            }
            if (flow.getItemId() == 3012 && flow.getQuantityDelta() < 0L)
            {
                sawPotionLoss = true;
            }
            if (flow.getItemId() == 3014 && flow.getQuantityDelta() > 0L)
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
        GpManagerEngine engine = startedWithBones(1L);
        engine.noteConsumptionIntent(526, 4);
        engine.reinforceConsumptionIntent(8);
        engine.markInventoryDirty();

        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).getCosts());
    }

    @Test
    public void openDrinkWithCompanionPotatoGainStillBooksDoseCost()
    {
        GpManagerEngine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(2434, 1L);
        before.put(1942, 1L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.noteConsumptionIntent(-1, 6);
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(139, 1L);
        after.put(1942, 3L);
        ProfitTransaction drink = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, drink.getType());
        assertTrue(drink.isCounted());
        assertTrue(engine.getMetrics(4_000L).getCosts() > 0L);
        assertTrue(engine.getMetrics(4_000L).getRevenue() > 0L);
    }

    @Test
    public void liveDrinkPickWithoutIntentStillBooksDoseAndPotato()
    {
        // Live miss: Drink intent expired before settle; dose leftover + Pick potato
        // coalesced into UNCERTAIN (Used/lost x0, no Potato row). Dose shape alone
        // must force CONSUMPTION without an armed intent.
        GpManagerEngine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(3012, 1L); // energy potion(2)
        before.put(1942, 1L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(3014, 1L); // energy potion(1)
        after.put(1942, 4L); // picked ×3
        ProfitTransaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertTrue(engine.getMetrics(4_000L).getCosts() > 0L);
        assertTrue(engine.getMetrics(4_000L).getRevenue() > 0L);
        long potatoGained = 0L;
        for (ItemFlow flow : settled.getFlows())
        {
            if (flow.getItemId() == 1942 && flow.getQuantityDelta() > 0L)
            {
                potatoGained += flow.getQuantityDelta();
            }
        }
        assertEquals(3L, potatoGained);
    }

    @Test
    public void wrongNamedIntentWithDrinkPickStillBooksViaAnyCost()
    {
        // CC_OP sometimes resolves the wrong inventory slot id. Named match fails and
        // companion potato gains block hasOnlyCosts — any armed intent + any cost wins.
        GpManagerEngine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 2L);
        before.put(1942, 2L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.noteConsumptionIntent(9999, 8); // wrong id (not bones)
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(526, 0L);
        after.put(1942, 5L);
        after.entrySet().removeIf(e -> e.getValue() <= 0L);
        ProfitTransaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertEquals(62L, engine.getMetrics(4_000L).getCosts());
        assertTrue(engine.getMetrics(4_000L).getRevenue() > 0L);
    }

    @Test
    public void chatReinforcedBuryWithPotatoPickBooksUsedLostAndGain()
    {
        // Dig+bury chat arms open intent after menu itemId was -1; Pick coalesces.
        GpManagerEngine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 2L);
        before.put(1942, 1L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.reinforceConsumptionIntent(12);
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(1942, 3L);
        ProfitTransaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertEquals(62L, engine.getMetrics(4_000L).getCosts());
        assertTrue(engine.getMetrics(4_000L).getRevenue() > 0L);
    }

    @Test
    public void repeatedBankOpenRefreshDoesNotClearArmedConsumeIntent()
    {
        // Live GameTick calls markBankInterfaceOpen every tick while bank is open.
        // Clearing intent each refresh made Drink→Pick settle as soft TRANSFER.
        GpManagerEngine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(3012, 1L);
        before.put(1942, 1L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.markBankInterfaceOpen(6); // open once
        engine.noteConsumptionIntent(-1, 8); // armed while bank open (menu/chat)
        engine.markBankInterfaceOpen(6); // per-tick refresh must keep intent
        engine.markBankInterfaceOpen(6);
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(3014, 1L);
        after.put(1942, 3L);
        ProfitTransaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertTrue(engine.getMetrics(4_000L).getCosts() > 0L);
        assertTrue(engine.getMetrics(4_000L).getRevenue() > 0L);
    }

    @Test
    public void softBankLatchWithDrinkPickMixedWindowStillBooksUsedLostAndPotato()
    {
        // Soft UI-open transfer evidence previously forced TRANSFER for any dirty
        // settle — including Drink dose leftover + Pick potato (Used/lost ×0).
        GpManagerEngine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 2L);
        before.put(3012, 1L);
        before.put(1942, 2L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.markBankInterfaceOpen(6);
        engine.noteConsumptionIntent(-1, 8);
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(526, 0L);
        after.put(3014, 1L);
        after.put(1942, 5L);
        after.entrySet().removeIf(e -> e.getValue() <= 0L);
        ProfitTransaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertTrue("bones + dose must book Used/lost", engine.getMetrics(4_000L).getCosts() > 0L);
        assertTrue("potato picks must book Received", engine.getMetrics(4_000L).getRevenue() > 0L);
    }

    @Test
    public void softBankLatchDoseWithoutIntentStillBooksWhenMixedWithPick()
    {
        GpManagerEngine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(3012, 1L);
        before.put(1942, 1L);
        engine.setBaseline(new ContainerSnapshot(before));
        engine.markBankInterfaceOpen(6);
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(3014, 1L);
        after.put(1942, 4L);
        ProfitTransaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertTrue(engine.getMetrics(4_000L).getCosts() > 0L);
        assertTrue(engine.getMetrics(4_000L).getRevenue() > 0L);
    }

    @Test
    public void liveDrinkPickEatBuryCoalescedTimingsBookAllSupplies()
    {
        // Closest offline replay of the live Falador potato sequence:
        // Drink energy(2)→(1) + Pick×2 + Bury×2 settle together; Eat potato next.
        GpManagerEngine engine = namedEngine();
        engine.ensureSession(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 4L);
        before.put(3012, 1L);
        before.put(1942, 3L);
        engine.setBaseline(new ContainerSnapshot(before));

        engine.noteConsumptionIntent(-1, 12);
        engine.reinforceConsumptionIntent(12); // chat: drink / dig+bury
        engine.markInventoryDirty();
        Map<Integer, Long> afterField = new HashMap<>();
        afterField.put(526, 2L);
        afterField.put(3014, 1L);
        afterField.put(1942, 5L);
        ProfitTransaction field = settle(engine, new ContainerSnapshot(afterField), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, field.getType());
        assertTrue(field.isCounted());

        engine.noteConsumptionIntent(1942, 8);
        engine.reinforceConsumptionIntent(8); // chat: you eat the potato. yuck!
        engine.markInventoryDirty();
        Map<Integer, Long> afterEat = new HashMap<>();
        afterEat.put(526, 2L);
        afterEat.put(3014, 1L);
        afterEat.put(1942, 4L);
        ProfitTransaction eat = settle(engine, new ContainerSnapshot(afterEat), 3_400L);
        assertEquals(TransactionType.CONSUMPTION, eat.getType());
        assertTrue(eat.isCounted());

        SessionMetrics metrics = engine.getMetrics(6_000L);
        assertTrue(metrics.getCosts() > 0L);
        assertTrue(metrics.getRevenue() > 0L);
        long boneUsed = 0L;
        long potatoNet = 0L;
        for (ProfitTransaction tx : engine.getActiveSession().getTransactions())
        {
            if (!tx.isCounted())
            {
                continue;
            }
            for (ItemFlow flow : tx.getFlows())
            {
                if (flow.getItemId() == 526 && flow.getQuantityDelta() < 0L)
                {
                    boneUsed += Math.abs(flow.getQuantityDelta());
                }
                if (flow.getItemId() == 1942)
                {
                    potatoNet += flow.getQuantityDelta();
                }
            }
        }
        assertEquals(2L, boneUsed);
        assertEquals(1L, potatoNet); // +2 pick −1 eat
    }

    @Test
    public void pausedBurialDoesNotCreateCatchUpCostAfterResume()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.togglePause(1_100L);
        engine.markInventoryDirty();
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), 1_600L));
        engine.togglePause(2_000L);

        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), 2_600L));
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(0L, engine.getMetrics(3_000L).getCosts());
    }

    @Test
    public void duplicateContainerDirtyForSameRemovalDoesNotDoubleCount()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.markInventoryDirty();
        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertNotNull(burial);

        engine.markInventoryDirty();
        assertNull(settle(engine, ContainerSnapshot.empty(), 3_400L));
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertEquals(31L, engine.getMetrics(6_000L).getCosts());
    }

    @Test
    public void retainedSessionMetricsExposeCountedNegativeFlowForUsedLostAggregation()
    {
        GpManagerEngine engine = startedWithBones(1L);
        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertTrue(burial.isCounted());
        assertEquals(31L, burial.getCosts());
        assertEquals(-31L, burial.getNet());
        assertEquals(31L, engine.getMetrics(4_000L).getCosts());
    }

    private GpManagerEngine startedWithBones(long quantity)
    {
        GpManagerEngine engine = engine();
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(526, quantity));
        return engine;
    }

    private GpManagerEngine engine()
    {
        return new GpManagerEngine(valuator, new TransactionClassifier(), CONFIG);
    }

    /** Valuator with potion dose names so open-intent dose matching can run offline. */
    private GpManagerEngine namedEngine()
    {
        FlowValuator named = deltas ->
        {
            List<ItemFlow> flows = new ArrayList<>();
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
                flows.add(new ItemFlow(
                    id, name, entry.getValue(), price, entry.getValue() * price));
            }
            return flows;
        };
        return new GpManagerEngine(named, new TransactionClassifier(), CONFIG);
    }

    private static ProfitTransaction settle(GpManagerEngine engine, ContainerSnapshot snapshot, long firstTick)
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
