package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * PR1-PRE-R5C.2C.6A.1A2: one observed collect coin movement is attributed to exactly the sold
 * offers it proves. The owner's real partial-collect sequence is the primary fixture; ties,
 * contradictions, unprovable executions and non-collect movements are pinned separately.
 */
public class GePartialCollectAttributionTest
{
    private static final int COINS = ItemID.COINS;
    private static final int CHICKEN = ItemID.COOKED_CHICKEN;
    private static final int MITHRIL = ItemID.MITHRIL_BAR;
    private static final int SILVER = ItemID.SILVER_BAR;
    private static final int SHARK = ItemID.SHARK;
    private static final int MAPLE = ItemID.MAPLE_LOGS;
    private static final int OAK_PLANK = ItemID.PLANK_OAK;
    private static final int MAGIC = ItemID.MAGIC_LOGS;
    private static final int MAHOGANY = ItemID.MAHOGANY_LOGS;
    private static final int BOLT = ItemID.BOLT_OF_LINEN;
    private static final int RUNE = ItemID.NATURERUNE;
    private static final long T0 = 1_000_000_000_000L;

    // §7 owner fixture: slot order, quantities, gross execution, actual tax and actual Received.
    private static final int[] OWNER_ITEM = {CHICKEN, MITHRIL, SILVER, SHARK, MAPLE, OAK_PLANK, MAGIC,
        MAHOGANY};
    private static final long[] OWNER_QTY = {1L, 30L, 60L, 30L, 4L, 20L, 4L, 9L};
    private static final long[] OWNER_GROSS = {63L, 29_340L, 7_500L, 29_280L, 44L, 9_920L, 2_824L, 1_161L};
    private static final long[] OWNER_TAX = {0L, 570L, 120L, 570L, 0L, 180L, 56L, 18L};
    private static final long[] OWNER_RECEIVED = {63L, 28_770L, 7_380L, 28_710L, 44L, 9_740L, 2_768L,
        1_143L};
    private static final long BOLT_QTY = 10L;
    private static final long BOLT_GROSS = 4_350L;
    private static final long BOLT_TAX = 80L;
    private static final long BOLT_RECEIVED = 4_270L;
    private static final long OWNER_SECOND_COLLECT = BOLT_RECEIVED + 28_770L + 7_380L + 28_710L
        + 44L + 9_740L + 2_768L + 1_143L;
    private static final long OWNER_TOTAL_TAX = 1_594L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    private enum Ordering
    {
        COINS_FIRST,
        CLEARED_FIRST,
        INTERLEAVED
    }

    // ── the owner fixture (§7) ─────────────────────────────────────────────────────────────────

    @Test
    public void ownerPartialCollectSettlesOnlyTheClearedSlotCoinsFirst()
    {
        assertOwnerPartialCollect(Ordering.COINS_FIRST);
    }

    @Test
    public void ownerPartialCollectSettlesOnlyTheClearedSlotClearedFirst()
    {
        assertOwnerPartialCollect(Ordering.CLEARED_FIRST);
    }

    @Test
    public void ownerPartialCollectSettlesOnlyTheClearedSlotInterleaved()
    {
        assertOwnerPartialCollect(Ordering.INTERLEAVED);
    }

    private static void assertOwnerPartialCollect(Ordering ordering)
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.listOwnerBatch(t);

        // Step 2: collect ONLY the cooked chicken slot (+63, exempt).
        long firstGain = t + 20_000L;
        withCollect(owner, ordering, firstGain, new int[] {0}, new int[] {CHICKEN}, 63L);

        MarketSettlementProjection.Row chicken = owner.row(CHICKEN);
        assertEquals(MarketSettlementProjection.Lifecycle.REALIZED, chicken.lifecycle);
        assertEquals("Received is the actual collected cash", 63L, chicken.observedSettlementGp);
        assertEquals("the exempt item proves no tax", 0L, chicken.inferredGeTaxGp);
        assertEquals(0L, chicken.realizedResultGp);
        assertFalse(chicken.knownCostOnly);
        for (int slot = 1; slot < OWNER_ITEM.length; slot++)
        {
            MarketSettlementProjection.Row pending = owner.row(OWNER_ITEM[slot]);
            assertEquals("slot " + slot + " is untouched",
                MarketSettlementProjection.Lifecycle.EXECUTED_UNSETTLED, pending.lifecycle);
            assertEquals(0L, pending.settledQty);
            assertFalse(pending.collectionAmbiguous);
        }
        assertEquals("the single collect never changes Net", 0L, owner.net(t + 22_000L));
        assertEquals(0, owner.reviewCount());
        assertEquals("no Coins row", 0, owner.unattributedCoinRows());
        assertEquals("no adjustment row", 0, owner.adjustmentRows());
        assertEquals(0L, EngineProbe.pendingSettlementCash(owner.engine.geCustody));

        // Step 3: list Bolt of linen in slot 0; step 4: Collect All.
        owner.placeAndSell(0, BOLT, BOLT_QTY, BOLT_GROSS, t + 30_000L);
        long secondGain = t + 40_000L;
        withCollect(owner, ordering, secondGain, new int[] {0, 1, 2, 3, 4, 5, 6, 7},
            new int[] {BOLT, MITHRIL, SILVER, SHARK, MAPLE, OAK_PLANK, MAGIC, MAHOGANY},
            OWNER_SECOND_COLLECT);

        assertEquals("nine booked sale settlements", 9, owner.settlementTransactions());
        // The chicken's slot was reused by the bolt listing; its settled lifecycle stays as closed
        // market history next to the bolt's, so every booked sale keeps its market row (G1).
        assertEquals("nine market rows", 9, owner.engine.getMarketSettlements().size());
        assertEquals("the reused slot's settled chicken keeps its row", 63L,
            owner.rowAt(0, CHICKEN).observedSettlementGp);
        assertEquals("Bolt of linen", BOLT_RECEIVED, owner.row(BOLT).observedSettlementGp);
        assertEquals(BOLT_TAX, owner.row(BOLT).inferredGeTaxGp);
        assertTrue(owner.row(BOLT).knownCostOnly);
        for (int slot = 1; slot < OWNER_ITEM.length; slot++)
        {
            MarketSettlementProjection.Row row = owner.row(OWNER_ITEM[slot]);
            assertEquals("slot " + slot + " Received", OWNER_RECEIVED[slot],
                row.observedSettlementGp);
            assertEquals(OWNER_TAX[slot], row.inferredGeTaxGp);
            assertEquals(MarketSettlementProjection.Lifecycle.REALIZED, row.lifecycle);
            if (OWNER_TAX[slot] > 0L)
            {
                assertTrue("a taxed sale is KNOWN_COST_ONLY", row.knownCostOnly);
                assertEquals(-OWNER_TAX[slot], row.realizedResultGp);
            }
            else
            {
                assertFalse("the tax-free maple sale stays UNCOUNTED", row.knownCostOnly);
                assertEquals(0L, row.realizedResultGp);
            }
            assertFalse("a settled receipt is never titled by Coins", row.itemId == COINS);
        }
        assertEquals("Net is exactly the total proven GE tax", -OWNER_TOTAL_TAX, owner.net(t + 42_000L));
        assertEquals(0, owner.reviewCount());
        assertEquals(0, owner.unattributedCoinRows());
        assertEquals(0, owner.adjustmentRows());
        assertEquals(0L, EngineProbe.pendingSettlementCash(owner.engine.geCustody));
    }

    @Test
    public void ownerFixtureSurvivesSaveReloadBetweenCollects()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.listOwnerBatch(t);
        owner.clearSlot(0, CHICKEN, t + 20_000L);
        owner.collect(63L, t + 20_000L, true);
        assertEquals(63L, owner.row(CHICKEN).observedSettlementGp);

        SavedState state = owner.engine.createSavedState();
        Owner restored = new Owner();
        restored.engine.restore(state, t + 30_000L);
        restored.engine.resume(t + 30_100L, PauseReason.IDLE, PauseReason.RECOVERY);
        restored.inventory.putAll(owner.inventory);
        restored.engine.setBaseline(new ContainerSnapshot(restored.inventory));

        restored.placeAndSell(0, BOLT, BOLT_QTY, BOLT_GROSS, t + 30_000L);
        restored.collect(OWNER_SECOND_COLLECT, t + 40_000L, true);
        assertEquals("the collect is retained after the reload", OWNER_SECOND_COLLECT,
            EngineProbe.pendingSettlementCash(restored.engine.geCustody));
        // Slot-clear evidence is transient and did not survive the restart; the window end still
        // books the one exact attribution because no contradicting evidence arrived.
        restored.maintenance(t + 42_000L);

        assertEquals(9, restored.settlementTransactions());
        assertEquals(9, restored.engine.getMarketSettlements().size());
        assertEquals(BOLT_RECEIVED, restored.row(BOLT).observedSettlementGp);
        assertEquals(OWNER_RECEIVED[3], restored.row(SHARK).observedSettlementGp);
        assertEquals(-OWNER_TOTAL_TAX, restored.net(t + 42_000L));
        assertEquals(0, restored.reviewCount());
        assertEquals(0, restored.unattributedCoinRows());
        assertEquals(0, restored.adjustmentRows());
    }

    // ── the attribution law ────────────────────────────────────────────────────────────────────

    @Test
    public void singleTaxedSlotSettlesWhileOthersWait()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.listOwnerBatch(t);
        long gain = t + 20_000L;
        owner.clearSlot(3, SHARK, gain + 600L);
        owner.collect(28_710L, gain, true);

        MarketSettlementProjection.Row shark = owner.row(SHARK);
        assertEquals(MarketSettlementProjection.Lifecycle.REALIZED, shark.lifecycle);
        assertEquals(28_710L, shark.observedSettlementGp);
        assertTrue(shark.knownCostOnly);
        assertEquals(-570L, owner.net(t + 22_000L));
        for (int slot = 0; slot < OWNER_ITEM.length; slot++)
        {
            if (slot == 3)
            {
                continue;
            }
            MarketSettlementProjection.Row pending = owner.row(OWNER_ITEM[slot]);
            assertEquals(0L, pending.settledQty);
            assertFalse(pending.collectionAmbiguous);
        }
        assertEquals(0, owner.reviewCount());
    }

    @Test
    public void partialSlotEvidenceCannotHideOtherMatchingSubsets()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        for (int slot = 0; slot < 4; slot++) owner.placeAndSell(slot, CHICKEN, 1L, 100L, t + slot * 2_000L);
        owner.clearSlot(1, CHICKEN, t + 20_600L);
        owner.collect(200L, t + 20_000L, true);
        for (int slot = 0; slot < 4; slot++) assertEquals("no arbitrary pair settles", 0L,
            owner.rowAt(slot, CHICKEN).settledQty);
        assertEquals(200L, EngineProbe.pendingSettlementCash(owner.engine.geCustody));
        owner.maintenance(t + 22_500L);
        assertEquals(1, owner.unattributedCoinRows());
        assertTrue("only the cleared slot is quarantined", owner.rowAt(1, CHICKEN).collectionAmbiguous);
        for (int slot : new int[] {0, 2, 3}) assertEquals("uncleared offers remain pending", 0L,
            owner.rowAt(slot, CHICKEN).settledQty);
        assertEquals("ambiguous cash never becomes counted profit", 0L, owner.net(t + 23_000L));
    }

    @Test
    public void logoutReleasesHeldCollectionWithoutReusingItsIntent()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.placeAndSell(3, SHARK, 10L, 9_780L, t);
        owner.placeAndSell(5, SHARK, 10L, 9_780L, t + 2_000L);
        owner.collect(9_590L, t + 20_000L, true);
        assertEquals(9_590L, EngineProbe.pendingSettlementCash(owner.engine.geCustody));
        owner.engine.pauseForLifecycle(t + 20_500L);
        assertEquals("observed cash remains reviewable before evidence is dropped", 1, owner.unattributedCoinRows());
        assertEquals(0L, EngineProbe.pendingSettlementCash(owner.engine.geCustody));
        assertEquals(0L, owner.engine.geCollectionIntentUntil);
        owner.engine.resume(t + 20_600L, PauseReason.LIFECYCLE);
        owner.clearSlot(3, SHARK, t + 20_700L);
        owner.maintenance(t + 21_000L);
        assertEquals("a new login cannot settle the old held interaction", 0L,
            owner.rowAt(3, SHARK).settledQty);
        assertEquals(0L, owner.rowAt(5, SHARK).settledQty);
        assertEquals(1, owner.unattributedCoinRows());
    }

    @Test
    public void heldCollectionStaysWithItsOwnerWhenStartingOrEndingAGrind()
    {
        for (boolean end : new boolean[] {false, true})
        {
            Owner owner = new Owner();
            long t = T0 + 10_000L;
            if (!end) owner.engine.finishCustomSession(t);
            owner.placeAndSell(3, SHARK, 10L, 9_780L, t + 1_000L);
            owner.placeAndSell(5, SHARK, 10L, 9_780L, t + 3_000L);
            owner.collect(9_590L, t + 20_000L, true);
            Session original = owner.engine.getActiveSession();
            if (end) owner.engine.finishCustomSession(t + 20_500L);
            else owner.engine.startCustomSession("New", SessionMode.AUTO, t + 20_500L);
            long reviews = original.getTransactions().stream().filter(row -> row.getType() == TransactionType.UNCERTAIN
                && row.getNote().equals("Grand Exchange")).count();
            assertEquals("held cash is preserved on the original financial owner", 1L, reviews);
            assertTrue(owner.engine.getActiveSession().getTransactions().stream().noneMatch(row -> row.getType() == TransactionType.UNCERTAIN));
            assertEquals(0L, EngineProbe.pendingSettlementCash(owner.engine.geCustody));
        }
    }

    @Test
    public void tieIsResolvedBySlotEvidence()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.placeAndSell(3, SHARK, 10L, 9_780L, t);
        owner.placeAndSell(5, SHARK, 10L, 9_780L, t + 2_000L);

        owner.clearSlot(3, SHARK, t + 20_000L + 600L);
        owner.collect(9_590L, t + 20_000L, true);

        assertEquals("the CLEARED offer settles", 9_590L, owner.rowAt(3, SHARK).observedSettlementGp);
        assertEquals(MarketSettlementProjection.Lifecycle.REALIZED, owner.rowAt(3, SHARK).lifecycle);
        assertEquals("the identical uncleared offer stays pending", 0L, owner.rowAt(5, owner.otherItem).settledQty);
        assertFalse(owner.rowAt(5, owner.otherItem).collectionAmbiguous);
        assertEquals(-190L, owner.net(t + 22_000L));
        assertEquals(0, owner.reviewCount());
    }

    @Test
    public void tieWithoutSlotEvidenceFailsClosedAtWindowEnd()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.placeAndSell(3, SHARK, 10L, 9_780L, t);
        owner.placeAndSell(5, SHARK, 10L, 9_780L, t + 2_000L);

        owner.collect(9_590L, t + 20_000L, true);
        assertEquals("a tie is held for the SLOT WINDOW", 9_590L,
            EngineProbe.pendingSettlementCash(owner.engine.geCustody));
        owner.maintenance(t + 22_500L);

        assertEquals("neither offer is settled arbitrarily", 0L, owner.rowAt(3, SHARK).settledQty);
        assertEquals(0L, owner.rowAt(5, owner.otherItem).settledQty);
        assertEquals("nothing was slot-evidenced, so nothing is quarantined",
            0, owner.ambiguousCount());
        assertEquals("the tied cash fails closed as one uncounted row", 1,
            owner.unattributedCoinRows());
        assertEquals(0L, owner.net(t + 23_000L));
    }

    @Test
    public void uniqueSubsetContradictedBySlotEvidenceFailsClosed()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.otherItem = SILVER;
        owner.placeAndSell(3, SHARK, 10L, 9_780L, t);
        owner.placeAndSell(5, SILVER, 60L, 7_500L, t + 2_000L);

        owner.collect(9_590L, t + 20_000L, true);
        // A slot cleared OUTSIDE the unique match contradicts it.
        owner.clearSlot(5, SILVER, t + 20_000L + 600L);

        assertEquals("the contradicted subset is not settled", 0L, owner.rowAt(3, SHARK).settledQty);
        assertFalse(owner.rowAt(3, SHARK).collectionAmbiguous);
        assertTrue("the slot-evidenced contradicted offer is quarantined",
            owner.rowAt(5, owner.otherItem).collectionAmbiguous);
        assertEquals(MarketSettlementProjection.Lifecycle.AMBIGUOUS, owner.rowAt(5, owner.otherItem).lifecycle);
        assertEquals(1, owner.unattributedCoinRows());
        assertEquals(0L, owner.net(t + 21_000L));
    }

    @Test
    public void wrongGapFailsClosedAndUnclearedOffersStayPending()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.otherItem = SILVER;
        owner.placeAndSell(3, SHARK, 10L, 9_780L, t);
        owner.placeAndSell(5, SILVER, 60L, 7_500L, t + 2_000L);

        // One coin off the exact attribution.
        owner.collect(9_591L, t + 20_000L, true);
        owner.clearSlot(3, SHARK, t + 20_000L + 600L);
        owner.maintenance(t + 22_500L);

        assertEquals("the slot-evidenced offer is quarantined", 1, owner.ambiguousCount());
        assertTrue(owner.rowAt(3, SHARK).collectionAmbiguous);
        assertEquals("the uncleared offer stays pending", 0L, owner.rowAt(5, owner.otherItem).settledQty);
        assertFalse(owner.rowAt(5, owner.otherItem).collectionAmbiguous);
        assertEquals(1, owner.unattributedCoinRows());
        assertEquals(0L, owner.net(t + 23_000L));

        // The uncleared offer later settles cleanly on its own exact collect.
        owner.clearSlot(5, SILVER, t + 24_000L + 600L);
        owner.collect(7_380L, t + 24_000L, true);
        assertEquals(7_380L, owner.rowAt(5, owner.otherItem).observedSettlementGp);
        assertEquals(MarketSettlementProjection.Lifecycle.REALIZED, owner.rowAt(5, owner.otherItem).lifecycle);
        assertEquals(-120L, owner.net(t + 26_000L));
    }

    @Test
    public void unprovableCandidateFailsItsCollectButNotOthers()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.otherItem = RUNE;
        owner.placeAndSell(3, SHARK, 10L, 9_780L, t);
        // A non-uniform execution (1,000 gross over 3 runes) can never prove its tax.
        owner.placeAndSell(5, RUNE, 3L, 1_000L, t + 2_000L);

        // The provable offer's own exact collect is unaffected by the unprovable candidate.
        owner.clearSlot(3, SHARK, t + 20_000L + 600L);
        owner.collect(9_590L, t + 20_000L, true);
        assertEquals(9_590L, owner.rowAt(3, SHARK).observedSettlementGp);
        assertEquals(0L, owner.rowAt(5, owner.otherItem).settledQty);
        assertEquals(0, owner.reviewCount());
        assertEquals(-190L, owner.net(t + 22_000L));

        // A collect that needs the unprovable candidate fails closed for the slot-evidenced set.
        owner.clearSlot(5, RUNE, t + 30_000L + 600L);
        owner.collect(990L, t + 30_000L, true);
        owner.maintenance(t + 32_500L);
        assertTrue(owner.rowAt(5, owner.otherItem).collectionAmbiguous);
        assertEquals(1, owner.unattributedCoinRows());
    }

    @Test
    public void partialFillCollectFromAnActiveSellIsUnchanged()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.place(0, RUNE, 100L, t);
        owner.offer(0, SELLING, RUNE, 100, 40, (int) reference(RUNE), 240, t + 1_000L);

        owner.collect(240L, t + 2_000L, true);
        assertEquals("the partial slice settles exactly as before", 40L,
            owner.row(RUNE).settledQty);
        assertEquals(240L, owner.row(RUNE).observedSettlementGp);
        assertEquals(0, owner.reviewCount());

        owner.offer(0, SOLD, RUNE, 100, 100, (int) reference(RUNE), 600, t + 3_000L);
        owner.collect(360L, t + 4_000L, true);
        assertEquals("the second collect settles only the new 60 units", 100L,
            owner.row(RUNE).settledQty);
        assertEquals(0, owner.reviewCount());
    }

    @Test
    public void matchingSubsetsRefusesMoreThanEightCandidates()
    {
        long[] nets = new long[9];
        boolean[] provable = new boolean[9];
        for (int index = 0; index < 9; index++)
        {
            nets[index] = 10L;
            provable[index] = true;
        }
        assertTrue("more than eight candidates are never enumerated",
            GeCustodyLedger.matchingSubsets(nets, provable, 10L, 2).isEmpty());
        assertEquals(8, GeCustodyLedger.MAX_SETTLEMENT_CANDIDATES);

        long[] eight = new long[8];
        boolean[] eightProvable = new boolean[8];
        for (int index = 0; index < 8; index++)
        {
            eight[index] = 10L;
            eightProvable[index] = true;
        }
        assertEquals("a matching pair stops the enumeration early", 2,
            GeCustodyLedger.matchingSubsets(eight, eightProvable, 10L, 2).size());
    }

    @Test
    public void attributionIsDeterministicAcrossRecordOrderings()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.placeAndSell(5, SHARK, 10L, 9_780L, t);
        owner.placeAndSell(3, SHARK, 10L, 9_780L, t + 2_000L);

        owner.clearSlot(3, SHARK, t + 20_000L + 600L);
        owner.collect(9_590L, t + 20_000L, true);

        assertEquals("the slot-evidenced offer settles regardless of insertion order",
            9_590L, owner.rowAt(3, SHARK).observedSettlementGp);
        assertEquals(0L, owner.rowAt(5, owner.otherItem).settledQty);
        assertFalse(owner.rowAt(5, owner.otherItem).collectionAmbiguous);
        assertEquals(0, owner.reviewCount());
    }

    // ── §4 fail-open fix and §5 expiry fix ────────────────────────────────────────────────────

    @Test
    public void knownBasisSingleSaleWithWrongCashFailsClosed()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.gain(RUNE, 26L, 5_000L, t - 60_000L);
        owner.placeAndSell(0, RUNE, 26L, 5_460L, t);

        // Received should be 5,356; 100 coins less must never count as a clean FULL_RESULT.
        owner.collect(5_256L, t + 20_000L, true);
        owner.maintenance(t + 22_500L);

        MarketSettlementProjection.Row row = owner.row(RUNE);
        assertEquals("no clean result is invented", 0L, row.settledQty);
        assertEquals("the earlier counted gain is the only Net", 5_000L, owner.net(t + 23_000L));
        assertFalse(row.collectionAmbiguous);
        assertEquals(1, owner.unattributedCoinRows());
    }

    @Test
    public void knownBasisSingleSaleWithExactNetStaysAFullResult()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.gain(RUNE, 26L, 5_000L, t - 60_000L);
        owner.placeAndSell(0, RUNE, 26L, 5_460L, t);

        owner.collect(5_356L, t + 20_000L, true);

        MarketSettlementProjection.Row row = owner.row(RUNE);
        assertEquals(MarketSettlementProjection.Coverage.FULLY_KNOWN, row.coverage);
        assertEquals(5_356L, row.observedSettlementGp);
        assertEquals("Result = after-tax Received - proven basis", 356L, row.realizedResultGp);
        assertEquals("the counted gain plus the sale Result", 5_356L, owner.net(t + 21_000L));
        assertEquals(0, owner.reviewCount());
    }

    @Test
    public void soldUncollectedOfferDoesNotExpireByTime()
    {
        Owner owner = new Owner();
        long t = T0 + 10_000L;
        owner.placeAndSell(0, SHARK, 10L, 9_780L, t);
        assertFalse(owner.row(SHARK).isRealizedIncluded());

        // More than the unobserved grace passes with unrelated activity; the coins are still in
        // GE custody and the lifecycle must not be handed to Review.
        owner.unrelatedTransfer(t + 30L * 60_000L);
        assertEquals("the sold offer is still in custody",
            MarketSettlementProjection.Lifecycle.EXECUTED_UNSETTLED, owner.row(SHARK).lifecycle);
        assertEquals("nothing was handed to Review", 0, owner.reviewCount());
        assertTrue("the durable lifecycle survives", owner.recordCount() == 1);

        // The late collect still settles exactly.
        owner.clearSlot(0, SHARK, t + 31L * 60_000L + 600L);
        owner.collect(9_590L, t + 31L * 60_000L, true);
        assertEquals(MarketSettlementProjection.Lifecycle.REALIZED, owner.row(SHARK).lifecycle);
        assertEquals(9_590L, owner.row(SHARK).observedSettlementGp);
        assertEquals(-190L, owner.net(t + 31L * 60_000L + 2_000L));
        assertEquals(0, owner.reviewCount());
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    /**
     * Run one collect interaction in the requested ordering: the coin gain and every slot's
     * CLEARED evidence all land inside the SLOT WINDOW, only their relative order differs.
     */
    private static void withCollect(Owner owner, Ordering ordering, long gainAt, int[] slots,
        int[] items, long amount)
    {
        switch (ordering)
        {
            case CLEARED_FIRST:
                for (int index = 0; index < slots.length; index++)
                {
                    owner.clearSlot(slots[index], items[index], gainAt - 600L + index * 10L);
                }
                owner.collect(amount, gainAt, true);
                break;
            case INTERLEAVED:
                for (int index = 0; index < slots.length; index += 2)
                {
                    owner.clearSlot(slots[index], items[index], gainAt - 600L + index * 10L);
                }
                owner.collect(amount, gainAt, true);
                for (int index = 1; index < slots.length; index += 2)
                {
                    owner.clearSlot(slots[index], items[index], gainAt + 600L + index * 10L);
                }
                break;
            case COINS_FIRST:
            default:
                owner.collect(amount, gainAt, true);
                for (int index = 0; index < slots.length; index++)
                {
                    owner.clearSlot(slots[index], items[index], gainAt + 600L + index * 10L);
                }
                break;
        }
    }

    private static long reference(int itemId)
    {
        switch (itemId)
        {
            case CHICKEN: return 69L;
            case MITHRIL: return 984L;
            case SILVER: return 121L;
            case SHARK: return 978L;
            case MAPLE: return 12L;
            case OAK_PLANK: return 502L;
            case MAGIC: return 725L;
            case MAHOGANY: return 126L;
            case BOLT: return 467L;
            case RUNE: return 6L;
            default: return 6L;
        }
    }

    private static String name(int itemId)
    {
        switch (itemId)
        {
            case CHICKEN: return "Cooked chicken";
            case MITHRIL: return "Mithril bar";
            case SILVER: return "Silver bar";
            case SHARK: return "Shark";
            case MAPLE: return "Maple logs";
            case OAK_PLANK: return "Oak plank";
            case MAGIC: return "Magic logs";
            case MAHOGANY: return "Mahogany logs";
            case BOLT: return "Bolt of linen";
            case RUNE: return "Nature rune";
            default: return "Item " + itemId;
        }
    }

    /** One real engine with the owner's frozen references and offline GE offer observations. */
    private static final class Owner
    {
        final Engine engine;
        final OfferLedger ledger = new OfferLedger();
        final Map<Integer, Long> inventory = new HashMap<>();
        /** The item offered in the second comparison slot (scenario-specific). */
        int otherItem = SHARK;
        long now = T0;

        Owner()
        {
            engine = new Engine(deltas ->
            {
                List<Flow> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> delta : deltas.entrySet())
                {
                    int id = delta.getKey();
                    long unit = id == COINS ? 1L : reference(id);
                    flows.add(new Flow(id, id == COINS ? "Coins" : name(id), delta.getValue(),
                        (int) unit, delta.getValue() * unit,
                        id == COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE));
                }
                return flows;
            }, new TransactionClassifier(), new GpManagerConfig()
            {
                @Override
                public ReceiptRetentionPeriod receiptRetentionDays()
                {
                    return ReceiptRetentionPeriod.DAYS_365;
                }

                @Override
                public int stabilizationTicks()
                {
                    return 0;
                }
            });
            engine.startCustomSession("Trading", SessionMode.AUTO, now);
            inventory.put(COINS, 1_000_000L);
            engine.setBaseline(new ContainerSnapshot(inventory));
        }

        /** Place one sell offer from unknown stock (the frozen reference is the quote). */
        void place(int slot, int item, long qty, long at)
        {
            inventory.put(item, qty);
            engine.setBaseline(new ContainerSnapshot(inventory));
            offer(slot, SELLING, item, (int) qty, 0, (int) reference(item), 0, at);
            inventory.put(item, 0L);
            settleAt(at + 100L);
        }

        void placeAndSell(int slot, int item, long qty, long gross, long at)
        {
            place(slot, item, qty, at);
            offer(slot, SOLD, item, (int) qty, (int) qty, (int) reference(item), (int) gross,
                at + 200L);
        }

        void listOwnerBatch(long at)
        {
            for (int slot = 0; slot < OWNER_ITEM.length; slot++)
            {
                placeAndSell(slot, OWNER_ITEM[slot], OWNER_QTY[slot], OWNER_GROSS[slot],
                    at + slot * 2_000L);
            }
        }

        void collect(long amount, long at, boolean collectionIntent)
        {
            if (collectionIntent)
            {
                engine.noteGeCollectionIntent(at);
            }
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + amount);
            settleAt(at);
        }

        void clearSlot(int slot, int item, long at)
        {
            offer(slot, EMPTY, item, 0, 0, 0, 0, at);
        }

        /** Trigger the engine's own custody maintenance without inventing a movement. */
        void maintenance(long at)
        {
            for (Transaction booking : engine.geCustody.maintenance(at,
                engine.getActiveSession().getId()).countedBookings)
            {
                engine.getActiveSession().addTransaction(booking, 500, true);
            }
        }

        /** An unrelated bank transfer still runs custody maintenance. */
        void unrelatedTransfer(long at)
        {
            engine.markContext(Context.TRANSFER, 10, "Bank transfer");
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + 1_000L);
            settleAt(at);
        }

        void gain(int item, long quantity, long value, long at)
        {
            engine.getActiveSession().addTransaction(new Transaction(at, null,
                TransactionType.GAIN, Context.GENERIC, "", "Loot", true,
                Collections.singletonList(new Flow(item, name(item), quantity,
                    (int) (quantity > 0L ? value / quantity : 0L), value,
                    PriceSource.GRAND_EXCHANGE)),
                ClassificationConfidence.CONFIRMED, "", null), 500);
        }

        private void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent, long at)
        {
            OfferLedger.Transition transition = ledger.observe(
                new OfferLedger.Snapshot(slot, state, item, total, traded, price, spent))
                .orElse(null);
            if (transition != null)
            {
                engine.noteGeOfferObservation(transition, name(item), at);
            }
        }

        private void settleAt(long at)
        {
            engine.markInventoryDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
            engine.processIfDirty(snapshot, at);
            engine.processIfDirty(snapshot, at + 1L);
        }

        MarketSettlementProjection.Row row(int itemId)
        {
            for (MarketSettlementProjection.Row row : engine.getMarketSettlements())
            {
                if (row.itemId == itemId)
                {
                    return row;
                }
            }
            throw new AssertionError("no market row for " + itemId);
        }

        MarketSettlementProjection.Row rowAt(int slot, int itemId)
        {
            for (MarketSettlementProjection.Row row : engine.getMarketSettlements())
            {
                if (row.slot == slot && row.itemId == itemId)
                {
                    return row;
                }
            }
            throw new AssertionError("no market row for slot " + slot);
        }

        long net(long at)
        {
            return engine.getMetrics(at).net;
        }

        int reviewCount()
        {
            int reviews = 0;
            for (Transaction transaction : engine.getActiveSession().getTransactions())
            {
                if (ReviewEligibility.needsOwnerDecision(transaction))
                {
                    reviews++;
                }
            }
            return reviews;
        }

        int ambiguousCount()
        {
            int ambiguous = 0;
            for (GeRecord record : engine.geCustody.snapshotRecords())
            {
                if (record.collectionAmbiguous)
                {
                    ambiguous++;
                }
            }
            return ambiguous;
        }

        int recordCount()
        {
            return engine.geCustody.snapshotRecords().size();
        }

        /** One uncounted Coins-only row: the fail-closed Review evidence. */
        int unattributedCoinRows()
        {
            int rows = 0;
            for (Transaction transaction : engine.getActiveSession().getTransactions())
            {
                if (transaction.getAutomaticType() == TransactionType.UNCERTAIN
                    && transaction.getFlows().size() == 1
                    && transaction.getFlows().get(0).itemId == COINS)
                {
                    rows++;
                }
            }
            return rows;
        }

        /** All booked MARKET settlement transactions (counted and uncounted). */
        int settlementTransactions()
        {
            int rows = 0;
            for (Transaction transaction : engine.getActiveSession().getTransactions())
            {
                if (transaction.getAutomaticType() == TransactionType.TRADE
                    && transaction.getNote().equals("Grand Exchange"))
                {
                    rows++;
                }
            }
            return rows;
        }

        int adjustmentRows()
        {
            int rows = 0;
            for (Transaction transaction : engine.getActiveSession().getTransactions())
            {
                if (transaction.getNote().startsWith("Grand Exchange settlement adjustment"))
                {
                    rows++;
                }
            }
            return rows;
        }
    }
}
