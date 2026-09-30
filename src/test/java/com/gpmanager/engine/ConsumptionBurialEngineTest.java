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
        List<Ab> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int price = entry.getKey() == 526 ? 31 : 100;
            flows.add(new Ab(
                entry.getKey(), "Item " + entry.getKey(), entry.getValue(), price, entry.getValue() * price));
        }
        return flows;
    };

    @Test
    public void activeBurialIsCountedConsumptionCost()
    {
        Am engine = startedWithBones(1L);

        Ac burial = settle(engine, Cc.empty(), 1_600L);

        assertEquals(Ai.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(-31L, burial.getNet());
        Bu metrics = engine.getMetrics(4_000L);
        assertEquals(0L, metrics.revenue);
        assertEquals(31L, metrics.costs);
        assertEquals(-31L, metrics.net);
    }

    @Test
    public void sameTickPickupThenBuryBooksConsumption()
    {
        // Acquire + bury in one stabilize window nets to zero vs baseline.
        Am engine = new Am(valuator, new TransactionClassifier(), CONFIG);
        engine.rm(1_000L);
        engine.setBaseline(Cc.empty());
        assertNull(engine.adj(snapshot(526, 1L), 1_000L));
        engine.noteConsumptionIntent(526, 8);
        engine.yz();

        Ac burial = settle(engine, Cc.empty(), 1_600L);

        assertNotNull(burial);
        assertEquals(Ai.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void hardTransferWithStaleBoneIntentDoesNotBookOakDepositAsConsume()
    {
        Am engine = new Am(valuator, new TransactionClassifier(), CONFIG);
        engine.rm(1_000L);
        engine.setBaseline(snapshot(1511, 1L)); // oak logs
        engine.noteConsumptionIntent(526, 6); // stale bones bury
        engine.markContext(Aj.TRANSFER, 6, "Bank container transfer");
        engine.yz();

        Ac deposit = settle(engine, Cc.empty(), 1_600L);

        assertEquals(Ai.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void repeatedBurialsAccumulateCosts()
    {
        Am engine = startedWithBones(2L);

        settle(engine, snapshot(526, 1L), 1_600L);
        settle(engine, Cc.empty(), 3_400L);

        Bu metrics = engine.getMetrics(6_000L);
        assertEquals(62L, metrics.costs);
        assertEquals(-62L, metrics.net);
        assertEquals(2, engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void lootThenBurialRecordsRevenueAndConsumptionRatherThanPerpetualProfit()
    {
        Am engine = engine();
        engine.rm(1_000L);
        engine.setBaseline(Cc.empty());
        engine.zk(Collections.singletonMap(526, 1L), 6, "Loot from Chicken", "Chicken");
        engine.yz();

        Ac loot = settle(engine, snapshot(526, 1L), 1_600L);
        engine.yz();
        Ac burial = settle(engine, Cc.empty(), 3_400L);

        assertEquals(Ai.LOOT, loot.getType());
        assertEquals(31L, loot.getRevenue());
        assertEquals(Ai.CONSUMPTION, burial.getType());
        assertEquals(31L, burial.getCosts());
        assertEquals(0L, engine.getMetrics(6_000L).net);
    }

    @Test
    public void staleTransferContextCannotHideBurialButFreshBankEvidenceCan()
    {
        Am stale = startedWithBones(1L);
        stale.markContext(Aj.TRANSFER, 6, "Bank transfer");
        for (int tick = 1; tick <= 7; tick++)
        {
            assertNull(stale.adj(snapshot(526, 1L), 1_000L + tick * 600L));
        }
        stale.yz();
        Ac burial = settle(stale, Cc.empty(), 6_000L);
        assertEquals(Ai.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());

        Am duringBank = startedWithBones(1L);
        duringBank.ze(6);
        duringBank.yz();
        Ac deposit = settle(duringBank, Cc.empty(), 1_600L);
        assertEquals(Ai.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, duringBank.getMetrics(4_000L).costs);
    }

    @Test
    public void closingBankBeforeBurialClearsTransferClassification()
    {
        Am engine = startedWithBones(1L);
        engine.ze(6);
        engine.yu();
        engine.yz();

        Ac burial = settle(engine, Cc.empty(), 1_600L);

        assertEquals(Ai.CONSUMPTION, burial.getType());
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
        Am engine = startedWithBones(1L);
        // Live GameTick refreshes bank-open (soft) evidence every tick the bank is visible.
        for (int tick = 0; tick < 5; tick++)
        {
            engine.ze(6);
        }
        // Bank closes; no more open notifications will ever arrive again.
        engine.yu();
        engine.yz();

        Ac burial = settle(engine, Cc.empty(), 1_600L);

        assertEquals(Ai.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(-31L, burial.getNet());
        Bu metrics = engine.getMetrics(4_000L);
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
        Am engine = startedWithBones(1L);
        engine.ze(6);
        engine.yu();
        engine.ze(6);
        engine.ze(6);
        engine.yu();
        engine.yz();

        Ac burial = settle(engine, Cc.empty(), 1_600L);

        assertEquals(Ai.CONSUMPTION, burial.getType());
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
        Am engine = startedWithBones(3L);
        engine.ze(6);
        engine.ze(6);
        engine.yu();
        engine.yz();

        Ac firstBurial = settle(engine, snapshot(526, 2L), 1_600L);
        assertEquals(Ai.CONSUMPTION, firstBurial.getType());
        assertTrue(firstBurial.isCounted());

        engine.yz();
        Ac secondBurial = settle(engine, Cc.empty(), 3_400L);
        assertEquals(Ai.CONSUMPTION, secondBurial.getType());
        assertTrue(secondBurial.isCounted());

        assertEquals(93L, engine.getMetrics(6_000L).costs);
    }

    @Test
    public void withdrawThenBurySameItemRecordsTransferThenConsumption()
    {
        Am engine = engine();
        engine.rm(1_000L);
        engine.setBaseline(Cc.empty());
        engine.ze(6);
        engine.yz();

        Ac withdrawal = settle(engine, snapshot(526, 1L), 1_600L);
        assertEquals(Ai.TRANSFER, withdrawal.getType());
        assertFalse(withdrawal.isCounted());

        engine.yu();
        engine.yz();
        Ac burial = settle(engine, Cc.empty(), 3_400L);

        assertEquals(Ai.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(6_000L).costs);
    }

    @Test
    public void hardDepositEvidenceBeforeInventorySurvivesCloseTickAsTransfer()
    {
        // Live race: bank-container/menu hard evidence, GameTick closes bank and advances
        // idle context, then inventory callback arrives — must stay ownership-neutral.
        Am engine = startedWithBones(1L);
        engine.markContext(Aj.TRANSFER, 6, "Bank container transfer");
        assertNull(engine.adj(snapshot(526, 1L), 1_200L));
        engine.yu();
        assertNull(engine.adj(snapshot(526, 1L), 1_800L));

        engine.yz();
        Ac deposit = settle(engine, Cc.empty(), 2_400L);

        assertEquals(Ai.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(5_000L).costs);
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void softOnlyDepositWhileBankOpenIsTransferNotLoss()
    {
        Am engine = startedWithBones(1L);
        engine.ze(6);
        engine.yz();

        Ac deposit = settle(engine, Cc.empty(), 1_600L);

        assertEquals(Ai.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void buryIntentAfterBankCloseBothCallbackOrdersBooksConsumption()
    {
        Am closeFirst = startedWithBones(1L);
        closeFirst.ze(6);
        closeFirst.yu();
        closeFirst.noteConsumptionIntent(526, 6);
        closeFirst.yz();
        Ac burialCloseFirst = settle(closeFirst, Cc.empty(), 1_600L);
        assertEquals(Ai.CONSUMPTION, burialCloseFirst.getType());
        assertTrue(burialCloseFirst.isCounted());
        assertEquals(31L, closeFirst.getMetrics(4_000L).costs);

        Am dirtyFirst = startedWithBones(1L);
        dirtyFirst.ze(6);
        dirtyFirst.yz();
        dirtyFirst.yu();
        dirtyFirst.noteConsumptionIntent(526, 6);
        Ac burialDirtyFirst = settle(dirtyFirst, Cc.empty(), 1_600L);
        assertEquals(Ai.CONSUMPTION, burialDirtyFirst.getType());
        assertTrue(burialDirtyFirst.isCounted());
        assertEquals(31L, dirtyFirst.getMetrics(4_000L).costs);
    }

    @Test
    public void buryIntentWinsOverSurvivingHardTransferEvidence()
    {
        Am engine = startedWithBones(1L);
        engine.markContext(Aj.TRANSFER, 6, "Bank container transfer");
        engine.yu();
        assertNull(engine.adj(snapshot(526, 1L), 1_200L));
        engine.noteConsumptionIntent(526, 6);
        engine.yz();

        Ac burial = settle(engine, Cc.empty(), 1_800L);

        assertEquals(Ai.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void eatIntentBooksConsumptionCost()
    {
        Am engine = engine();
        engine.rm(1_000L);
        engine.setBaseline(snapshot(379, 1L)); // lobster
        engine.noteConsumptionIntent(379, 6);
        engine.yz();

        Ac eaten = settle(engine, Cc.empty(), 1_600L);

        assertEquals(Ai.CONSUMPTION, eaten.getType());
        assertTrue(eaten.isCounted());
        assertEquals(100L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void drinkIntentWithDoseLeftoverStillBooksConsumption()
    {
        // Prayer potion(4) → potion(3): mixed delta would be UNCERTAIN without intent.
        Am engine = engine();
        engine.rm(1_000L);
        engine.setBaseline(snapshot(2434, 1L));
        engine.noteConsumptionIntent(2434, 6);
        engine.yz();

        Map<Integer, Long> after = new HashMap<>();
        after.put(139, 1L); // prayer potion(3)
        Ac drink = settle(engine, new Cc(after), 1_600L);

        assertEquals(Ai.CONSUMPTION, drink.getType());
        assertTrue(drink.isCounted());
        assertTrue(engine.getMetrics(4_000L).costs > 0L);
    }

    @Test
    public void openDrinkIntentWithoutItemIdStillBooksDoseLeftover()
    {
        // Live CC_OP Drink often reports itemId -1; open intent + dose step must still count.
        Am engine = namedEngine();
        engine.rm(1_000L);
        engine.setBaseline(snapshot(2434, 1L));
        engine.noteConsumptionIntent(-1, 6);
        engine.yz();

        Map<Integer, Long> after = new HashMap<>();
        after.put(139, 1L);
        Ac drink = settle(engine, new Cc(after), 1_600L);

        assertEquals(Ai.CONSUMPTION, drink.getType());
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
        Am engine = startedWithBones(1L);
        engine.markContext(Aj.TRANSFER, 6, "Bank container transfer");
        engine.yu();
        assertNull(engine.adj(snapshot(526, 1L), 1_200L));
        engine.noteConsumptionIntent(-1, 6);
        engine.yz();

        Ac burial = settle(engine, Cc.empty(), 1_800L);

        assertEquals(Ai.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void runeCastStylePureLossBooksConsumption()
    {
        // Air rune stack spend — pure cost; cast intent confirms over stale transfer.
        Am engine = engine();
        engine.rm(1_000L);
        engine.setBaseline(snapshot(556, 100L)); // air rune
        engine.markContext(Aj.TRANSFER, 6, "Bank container transfer");
        engine.yu();
        assertNull(engine.adj(snapshot(556, 100L), 1_200L));
        engine.noteConsumptionIntent(556, 6);
        engine.yz();

        Ac cast = settle(engine, snapshot(556, 95L), 1_800L);

        assertEquals(Ai.CONSUMPTION, cast.getType());
        assertTrue(cast.isCounted());
        assertEquals(500L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void observedAlchemyBooksTheRealizedItemLossAndCoinGainOnce()
    {
        // High-alchemy metadata is not a quote. An actual cast is represented by the observed
        // item removal plus the observed coin receipt under the existing cast/consume intent.
        Am engine = alchemyEngine();
        engine.rm(1_000L);
        engine.setBaseline(snapshot(1942, 1L));
        engine.noteConsumptionIntent(1942, 8);
        engine.yz();

        Map<Integer, Long> after = new HashMap<>();
        after.put(995, 60L);
        Ac alchemy = settle(engine, new Cc(after), 1_600L);

        assertNotNull(alchemy);
        assertEquals(Ai.CONSUMPTION, alchemy.getType());
        assertTrue(alchemy.isCounted());
        assertEquals(-40L, alchemy.getNet());
        assertEquals(100L, engine.getMetrics(4_000L).costs);
        assertEquals(60L, engine.getMetrics(4_000L).revenue);
        assertNull("replaying the settled snapshot must not book alchemy twice",
            engine.adj(new Cc(after), 2_800L));
    }

    @Test
    public void bankDepositStillTransferWhenConsumptionIntentAbsent()
    {
        Am engine = startedWithBones(1L);
        engine.markContext(Aj.TRANSFER, 6, "Bank container transfer");
        engine.yz();

        Ac deposit = settle(engine, Cc.empty(), 1_600L);

        assertEquals(Ai.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void bankOpenClearsStaleConsumeIntentSoDepositStaysTransfer()
    {
        Am engine = startedWithBones(1L);
        engine.noteConsumptionIntent(526, 6);
        engine.ze(6);
        engine.yz();

        Ac deposit = settle(engine, Cc.empty(), 1_600L);

        assertEquals(Ai.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void pickingPotatoRegistersCountedGain()
    {
        Am engine = engine();
        engine.rm(1_000L);
        engine.setBaseline(Cc.empty());
        engine.setDetectedActivity("Farming", 1_100L);
        engine.yz();

        Ac harvest = settle(engine, snapshot(1942, 1L), 1_600L); // potato

        assertEquals(Ai.GAIN, harvest.getType());
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
        Am engine = namedEngine();
        engine.rm(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 4L); // bones
        before.put(3012, 1L); // energy potion(2)
        before.put(1942, 3L); // potatoes already held
        engine.setBaseline(new Cc(before));
        engine.noteConsumptionIntent(-1, 8);
        engine.yz();

        Map<Integer, Long> after = new HashMap<>();
        after.put(526, 2L); // buried ×2
        after.put(3014, 1L); // energy potion(1) leftover
        after.put(1942, 5L); // picked ×2
        Ac settled = settle(engine, new Cc(after), 1_600L);

        assertEquals(Ai.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        Bu metrics = engine.getMetrics(4_000L);
        assertTrue("bone + dose costs must book", metrics.costs > 0L);
        assertTrue("potato picks must book as revenue in the coalesced window",
            metrics.revenue > 0L);

        long boneUsed = 0L;
        long potatoGained = 0L;
        boolean sawPotionLoss = false;
        boolean sawPotionGain = false;
        for (Ab flow : settled.getFlows())
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
        Am engine = startedWithBones(1L);
        engine.noteConsumptionIntent(526, 4);
        engine.aew(8);
        engine.yz();

        Ac burial = settle(engine, Cc.empty(), 1_600L);
        assertEquals(Ai.CONSUMPTION, burial.getType());
        assertTrue(burial.isCounted());
        assertEquals(31L, engine.getMetrics(4_000L).costs);
    }

    @Test
    public void openDrinkWithCompanionPotatoGainStillBooksDoseCost()
    {
        Am engine = namedEngine();
        engine.rm(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(2434, 1L);
        before.put(1942, 1L);
        engine.setBaseline(new Cc(before));
        engine.noteConsumptionIntent(-1, 6);
        engine.yz();

        Map<Integer, Long> after = new HashMap<>();
        after.put(139, 1L);
        after.put(1942, 3L);
        Ac drink = settle(engine, new Cc(after), 1_600L);

        assertEquals(Ai.CONSUMPTION, drink.getType());
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
        Am engine = namedEngine();
        engine.rm(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(3012, 1L); // energy potion(2)
        before.put(1942, 1L);
        engine.setBaseline(new Cc(before));
        engine.yz();

        Map<Integer, Long> after = new HashMap<>();
        after.put(3014, 1L); // energy potion(1)
        after.put(1942, 4L); // picked ×3
        Ac settled = settle(engine, new Cc(after), 1_600L);

        assertEquals(Ai.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertTrue(engine.getMetrics(4_000L).costs > 0L);
        assertTrue(engine.getMetrics(4_000L).revenue > 0L);
        long potatoGained = 0L;
        for (Ab flow : settled.getFlows())
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
        Am engine = namedEngine();
        engine.rm(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 2L);
        before.put(1942, 2L);
        engine.setBaseline(new Cc(before));
        engine.noteConsumptionIntent(9999, 8); // wrong id (not bones)
        engine.yz();

        Map<Integer, Long> after = new HashMap<>();
        after.put(526, 0L);
        after.put(1942, 5L);
        after.entrySet().removeIf(e -> e.getValue() <= 0L);
        Ac settled = settle(engine, new Cc(after), 1_600L);

        assertEquals(Ai.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertEquals(62L, engine.getMetrics(4_000L).costs);
        assertTrue(engine.getMetrics(4_000L).revenue > 0L);
    }

    @Test
    public void chatReinforcedBuryWithPotatoPickBooksUsedLostAndGain()
    {
        // Dig+bury chat arms open intent after menu itemId was -1; Pick coalesces.
        Am engine = namedEngine();
        engine.rm(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 2L);
        before.put(1942, 1L);
        engine.setBaseline(new Cc(before));
        engine.aew(12);
        engine.yz();

        Map<Integer, Long> after = new HashMap<>();
        after.put(1942, 3L);
        Ac settled = settle(engine, new Cc(after), 1_600L);

        assertEquals(Ai.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertEquals(62L, engine.getMetrics(4_000L).costs);
        assertTrue(engine.getMetrics(4_000L).revenue > 0L);
    }

    @Test
    public void repeatedBankOpenRefreshDoesNotClearArmedConsumeIntent()
    {
        // Live GameTick calls ze every tick while bank is open.
        // Clearing intent each refresh made Drink→Pick settle as soft TRANSFER.
        Am engine = namedEngine();
        engine.rm(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(3012, 1L);
        before.put(1942, 1L);
        engine.setBaseline(new Cc(before));
        engine.ze(6); // open once
        engine.noteConsumptionIntent(-1, 8); // armed while bank open (menu/chat)
        engine.ze(6); // per-tick refresh must keep intent
        engine.ze(6);
        engine.yz();

        Map<Integer, Long> after = new HashMap<>();
        after.put(3014, 1L);
        after.put(1942, 3L);
        Ac settled = settle(engine, new Cc(after), 1_600L);

        assertEquals(Ai.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertTrue(engine.getMetrics(4_000L).costs > 0L);
        assertTrue(engine.getMetrics(4_000L).revenue > 0L);
    }

    @Test
    public void softBankLatchWithDrinkPickMixedWindowStillBooksUsedLostAndPotato()
    {
        // Soft UI-open transfer evidence previously forced TRANSFER for any dirty
        // settle — including Drink dose leftover + Pick potato (Used/lost ×0).
        Am engine = namedEngine();
        engine.rm(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 2L);
        before.put(3012, 1L);
        before.put(1942, 2L);
        engine.setBaseline(new Cc(before));
        engine.ze(6);
        engine.noteConsumptionIntent(-1, 8);
        engine.yz();

        Map<Integer, Long> after = new HashMap<>();
        after.put(526, 0L);
        after.put(3014, 1L);
        after.put(1942, 5L);
        after.entrySet().removeIf(e -> e.getValue() <= 0L);
        Ac settled = settle(engine, new Cc(after), 1_600L);

        assertEquals(Ai.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertTrue("bones + dose must book Used/lost", engine.getMetrics(4_000L).costs > 0L);
        assertTrue("potato picks must book Received", engine.getMetrics(4_000L).revenue > 0L);
    }

    @Test
    public void softBankLatchDoseWithoutIntentStillBooksWhenMixedWithPick()
    {
        Am engine = namedEngine();
        engine.rm(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(3012, 1L);
        before.put(1942, 1L);
        engine.setBaseline(new Cc(before));
        engine.ze(6);
        engine.yz();

        Map<Integer, Long> after = new HashMap<>();
        after.put(3014, 1L);
        after.put(1942, 4L);
        Ac settled = settle(engine, new Cc(after), 1_600L);

        assertEquals(Ai.CONSUMPTION, settled.getType());
        assertTrue(settled.isCounted());
        assertTrue(engine.getMetrics(4_000L).costs > 0L);
        assertTrue(engine.getMetrics(4_000L).revenue > 0L);
    }

    @Test
    public void liveDrinkPickEatBuryCoalescedTimingsBookAllSupplies()
    {
        // Closest offline replay of the live Falador potato sequence:
        // Drink energy(2)→(1) + Pick×2 + Bury×2 settle together; Eat potato next.
        Am engine = namedEngine();
        engine.rm(1_000L);
        Map<Integer, Long> before = new HashMap<>();
        before.put(526, 4L);
        before.put(3012, 1L);
        before.put(1942, 3L);
        engine.setBaseline(new Cc(before));

        engine.noteConsumptionIntent(-1, 12);
        engine.aew(12); // chat: drink / dig+bury
        engine.yz();
        Map<Integer, Long> afterField = new HashMap<>();
        afterField.put(526, 2L);
        afterField.put(3014, 1L);
        afterField.put(1942, 5L);
        Ac field = settle(engine, new Cc(afterField), 1_600L);
        assertEquals(Ai.CONSUMPTION, field.getType());
        assertTrue(field.isCounted());

        engine.noteConsumptionIntent(1942, 8);
        engine.aew(8); // chat: you eat the potato. yuck!
        engine.yz();
        Map<Integer, Long> afterEat = new HashMap<>();
        afterEat.put(526, 2L);
        afterEat.put(3014, 1L);
        afterEat.put(1942, 4L);
        Ac eat = settle(engine, new Cc(afterEat), 3_400L);
        assertEquals(Ai.CONSUMPTION, eat.getType());
        assertTrue(eat.isCounted());

        Bu metrics = engine.getMetrics(6_000L);
        assertTrue(metrics.costs > 0L);
        assertTrue(metrics.revenue > 0L);
        long boneUsed = 0L;
        long potatoNet = 0L;
        for (Ac tx : engine.getActiveSession().getTransactions())
        {
            if (!tx.isCounted())
            {
                continue;
            }
            for (Ab flow : tx.getFlows())
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
        Am engine = startedWithBones(1L);
        engine.togglePause(1_100L);
        engine.yz();
        assertNull(engine.adj(Cc.empty(), 1_600L));
        engine.togglePause(2_000L);

        assertNull(engine.adj(Cc.empty(), 2_600L));
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(0L, engine.getMetrics(3_000L).costs);
    }

    @Test
    public void duplicateContainerDirtyForSameRemovalDoesNotDoubleCount()
    {
        Am engine = startedWithBones(1L);
        engine.yz();
        Ac burial = settle(engine, Cc.empty(), 1_600L);
        assertNotNull(burial);

        engine.yz();
        assertNull(settle(engine, Cc.empty(), 3_400L));
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertEquals(31L, engine.getMetrics(6_000L).costs);
    }

    @Test
    public void retainedSessionMetricsExposeCountedNegativeFlowForUsedLostAggregation()
    {
        Am engine = startedWithBones(1L);
        Ac burial = settle(engine, Cc.empty(), 1_600L);

        assertTrue(burial.isCounted());
        assertEquals(31L, burial.getCosts());
        assertEquals(-31L, burial.getNet());
        assertEquals(31L, engine.getMetrics(4_000L).costs);
    }

    private Am startedWithBones(long quantity)
    {
        Am engine = engine();
        engine.rm(1_000L);
        engine.setBaseline(snapshot(526, quantity));
        return engine;
    }

    private Am engine()
    {
        return new Am(valuator, new TransactionClassifier(), CONFIG);
    }

    private Am alchemyEngine()
    {
        FlowValuator alchemy = deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : deltas.entrySet())
            {
                int price = entry.getKey() == 995 ? 1 : 100;
                flows.add(new Ab(
                    entry.getKey(), "Item " + entry.getKey(), entry.getValue(), price,
                    entry.getValue() * price));
            }
            return flows;
        };
        return new Am(alchemy, new TransactionClassifier(), CONFIG);
    }

    /** Valuator with potion dose names so open-intent dose matching can run offline. */
    private Am namedEngine()
    {
        FlowValuator named = deltas ->
        {
            List<Ab> flows = new ArrayList<>();
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
                flows.add(new Ab(
                    id, name, entry.getValue(), price, entry.getValue() * price));
            }
            return flows;
        };
        return new Am(named, new TransactionClassifier(), CONFIG);
    }

    private static Ac settle(Am engine, Cc snapshot, long firstTick)
    {
        assertNull(engine.adj(snapshot, firstTick));
        assertNull(engine.adj(snapshot, firstTick + 600L));
        return engine.adj(snapshot, firstTick + 1_200L);
    }

    private static Cc snapshot(int itemId, long quantity)
    {
        Map<Integer, Long> values = new HashMap<>();
        if (quantity > 0L)
        {
            values.put(itemId, quantity);
        }
        return new Cc(values);
    }
}
